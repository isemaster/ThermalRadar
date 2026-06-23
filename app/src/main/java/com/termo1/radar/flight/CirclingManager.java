package com.termo1.radar.flight;

/**
 * CirclingManager — детекция вращения в термике, сбор варио по секторам,
 * оценка ветра по дрейфу центров спиралей, голосовые подсказки.
 *
 * State machine (по образу XCSoar CirclingComputer):
 *   CRUISE ──(turn_rate > 4°/s)──→ SUSPECT_CLIMB
 *   SUSPECT_CLIMB ──(turning > 4s)──→ CLIMB
 *   SUSPECT_CLIMB ──(turn_rate < 4°/s > 1s)──→ CRUISE
 *   CLIMB ──(turn_rate < 4°/s > 5s)──→ SUSPECT_CRUISE
 *   SUSPECT_CRUISE ──(turn_rate > 4°/s)──→ CLIMB
 *   SUSPECT_CRUISE ──(turn_rate < 4°/s > 5s)──→ CRUISE
 *
 * Особенности из XCSoar:
 * - Track-based turn rate с LowPassFilter(α=0.3), clamp ±50°/с
 * - Heading-based turn rate (отдельно)
 * - Запоминание turn_start_location, turn_start_altitude, climb_start_time
 * - Гистерезис входа/выхода
 *
 * Оценка ветра:
 * - При крутке: дрейф GPS-центра спирали → направление и скорость ветра
 * - На прямых участках: WindEKF по разнице GPS vs TAS
 * - Хранение в WindStore по слоям высоты
 */
public class CirclingManager {

    // ========================================================================
    // Состояния (state machine)
    // ========================================================================
    public enum CirclingState {
        CRUISE,          // прямой полёт
        SUSPECT_CLIMB,   // подозрение на крутку (turn_rate > порог, ждём 4с)
        CLIMB,           // подтверждённая крутка в термике
        SUSPECT_CRUISE   // подозрение на выход (turn_rate упал, ждём 5с)
    }

    // ========================================================================
    // Константы
    // ========================================================================

    // --- Turn rate thresholds (из XCSoar: MIN_TURN_RATE = 4°/s) ---
    private static final float MIN_TURN_RATE_DEG_S = 4f;      // °/с — порог входа/выхода
    private static final float MAX_TURN_RATE_CLAMP = 50f;     // °/с — клиппинг выбросов
    private static final float TURN_RATE_LOWPASS_ALPHA = 0.3f; // как в XCSoar

    // --- Time thresholds ---
    private static final long CRUISE_TO_CLIMB_MS = 4000;      // 4с подозрения → подтверждение
    private static final long CLIMB_TO_CRUISE_MS = 5000;      // 5с без поворота → выход
    private static final long DT_CAP_MS = 2000;               // кап разрыва времени

    // --- Gyro ---
    private static final float GYRO_THRESHOLD_RAD = (float) Math.toRadians(18.0);
    private static final float GYRO_EMA_ALPHA = 0.15f;

    // --- Label ---
    private static final float LABEL_ANGLE = 540f;            // показать метку через 540°
    private static final float FULL_CIRCLE = 360f;

    // --- Voice ---
    private static final long VOICE_DEBOUNCE_MS = 8000;

    // --- Sector analysis (4 сектора по 90°) ---
    private static final int SECTORS = 4;
    private static final int MIN_SECTOR_SAMPLES = 20;

    // --- GPS circle center ---
    private static final int MIN_GPS_CIRCLE_SAMPLES = 3;
    private static final float WIND_SPEED_EMA_ALPHA = 0.3f;
    private static final float WIND_DIR_EMA_ALPHA = 0.3f;
    private static final float MIN_WIND_DRIFT_DIST = 5.0f;     // м — минимальный дрейф для учёта
    private static final float MIN_WIND_DRIFT_SPEED = 0.3f;    // м/с

    /** Воздушная скорость параплана (м/с) — настраивается */
    private float airspeedMs = 9.5f;

    // ========================================================================
    // State machine
    // ========================================================================
    private CirclingState state = CirclingState.CRUISE;

    // Turn rate (сглаженный)
    private float turnRateDegS;            // track-based, LowPassFilter(0.3)
    private float turnRateHeadingDegS;     // heading-based, LowPassFilter(0.3)
    private float lastTrackDeg;
    private float lastHeadingDeg;
    private boolean trackInitialized;
    private long lastTrackTimeMs;

    // Gyro (дополнительный сенсор)
    private float gyroEma;

    // Тайминги состояний
    private long stateEnterMs;
    private long lastTurnMs;               // время последнего поворота (для гистерезиса)

    // Запоминание старта крутки (как в XCSoar)
    private double turnStartLat;
    private double turnStartLon;
    private float turnStartAltitude;
    private long turnStartMs;

    // Climb start (начало уверенного CLIMB)
    private double climbStartLat;
    private double climbStartLon;
    private float climbStartAltitude;
    private long climbStartMs;

    // ========================================================================
    // Трекинг угла поворота
    // ========================================================================
    private float totalAngle;               // накопленный угол за всё время крутки
    private float circleOffset;             // угол в текущей спирали
    private int fullCirclesCompleted;
    private boolean showLabel;

    // ========================================================================
    // Секторный анализ (4 сектора по 90°: N/E/S/W)
    // ========================================================================
    private final float[] sectorVario = new float[SECTORS];
    private final int[] sectorCount = new int[SECTORS];

    // ========================================================================
    // GPS-центры спиралей (для оценки ветра по дрейфу)
    // ========================================================================
    private double circleLatSum;
    private double circleLonSum;
    private int circleGpsCount;
    private long circleStartMs;

    private boolean hasPrevCenter;
    private double prevCircleLat;
    private double prevCircleLon;
    private long prevCircleEndMs;

    // ========================================================================
    // Оценка ветра (результат)
    // ========================================================================
    private float windFromDeg = -1f;
    private float windSpeedMs = -1f;
    private int windConfidence;

    private final WindEKF windEKF = new WindEKF();
    private final WindStore windStore = new WindStore();

    // ========================================================================
    // Voice callback
    // ========================================================================
    public interface VoiceCallback {
        void speak(String text);
    }

    private VoiceCallback voiceCallback;
    private long lastVoiceMs;

    // ========================================================================
    // Lock
    // ========================================================================
    private final Object stateLock = new Object();

    // ========================================================================
    // Init
    // ========================================================================

    public CirclingManager() {
        reset();
    }

    public void setVoiceCallback(VoiceCallback cb) {
        this.voiceCallback = cb;
    }

    public void reset() {
        synchronized (stateLock) {
            state = CirclingState.CRUISE;
            turnRateDegS = 0f;
            turnRateHeadingDegS = 0f;
            lastTrackDeg = 0;
            lastHeadingDeg = 0;
            trackInitialized = false;
            lastTrackTimeMs = 0;
            gyroEma = 0f;
            stateEnterMs = 0;
            lastTurnMs = 0;

            turnStartLat = 0;
            turnStartLon = 0;
            turnStartAltitude = 0;
            turnStartMs = 0;
            climbStartLat = 0;
            climbStartLon = 0;
            climbStartAltitude = 0;
            climbStartMs = 0;

            totalAngle = 0f;
            circleOffset = 0f;
            fullCirclesCompleted = 0;
            showLabel = false;

            for (int i = 0; i < SECTORS; i++) {
                sectorVario[i] = 0f;
                sectorCount[i] = 0;
            }

            circleLatSum = 0;
            circleLonSum = 0;
            circleGpsCount = 0;
            circleStartMs = 0;
            hasPrevCenter = false;
            prevCircleLat = 0;
            prevCircleLon = 0;
            prevCircleEndMs = 0;
            // Не сбрасываем ветер — он накапливается
        }
    }

    // ========================================================================
    // Главное обновление
    // ========================================================================

    /**
     * Главное обновление. Вызывать на каждом сэмпле (10-50 Гц).
     *
     * @param gyroZ    скорость поворота по гироскопу Z (рад/с), или 0 если нет
     * @param heading  текущий курс с компаса (градусы 0-360)
     * @param track    текущий GPS track (градусы 0-360)
     * @param vario    вертикальная скорость (м/с)
     * @param gpsLat   широта GPS
     * @param gpsLon   долгота GPS
     * @param gpsSpeed скорость по GPS (м/с)
     * @param gpsCourse курс по GPS (градусы)
     * @param altMsl   высота MSL (м)
     * @param nowMs    монотонное время
     */
    public void update(float gyroZ, float heading, float track,
                       float vario,
                       double gpsLat, double gpsLon,
                       float gpsSpeed, float gpsCourse,
                       float altMsl, long nowMs) {
        synchronized (stateLock) {

        // ================================================================
        // 1. Turn rate: track-based + heading-based (как XCSoar)
        // ================================================================
        updateTurnRates(track, heading, nowMs);

        // Gyro EMA (дополнительно)
        if (!Float.isNaN(gyroZ) && !Float.isInfinite(gyroZ)) {
            gyroEma += GYRO_EMA_ALPHA * (gyroZ - gyroEma);
        }

        // Combined turn detection: track_rate ИЛИ gyro
        float effectiveTurnRate = Math.max(Math.abs(turnRateDegS),
                (float) Math.toDegrees(Math.abs(gyroEma)));
        boolean turning = effectiveTurnRate >= MIN_TURN_RATE_DEG_S;

        if (turning) {
            lastTurnMs = nowMs;
        }

        // ================================================================
        // 2. State machine (XCSoar-style: CRUISE → SUSPECT → CLIMB)
        // ================================================================
        CirclingState previousState = state;

        switch (state) {
            case CRUISE:
                if (turning) {
                    // Начало поворота → запоминаем позицию/высоту (turn_start)
                    turnStartLat = gpsLat;
                    turnStartLon = gpsLon;
                    turnStartAltitude = altMsl;
                    turnStartMs = nowMs;
                    state = CirclingState.SUSPECT_CLIMB;
                    stateEnterMs = nowMs;
                }
                break;

            case SUSPECT_CLIMB:
                if (!turning) {
                    // Не поворачиваем — возвращаемся в cruise
                    state = CirclingState.CRUISE;
                    stateEnterMs = nowMs;
                    break;
                }
                if (nowMs - stateEnterMs >= CRUISE_TO_CLIMB_MS) {
                    // Подтверждённая крутка > 4с → CLIMB
                    state = CirclingState.CLIMB;
                    stateEnterMs = nowMs;

                    // Запоминаем начало climb
                    climbStartLat = turnStartLat;
                    climbStartLon = turnStartLon;
                    climbStartAltitude = turnStartAltitude;
                    climbStartMs = turnStartMs;

                    // Инициализируем трекинг для спиралей
                    totalAngle = 0f;
                    circleOffset = 0f;
                    fullCirclesCompleted = 0;
                    showLabel = false;
                    circleLatSum = gpsLat;
                    circleLonSum = gpsLon;
                    circleGpsCount = 1;
                    circleStartMs = nowMs;
                }
                break;

            case CLIMB:
                if (!turning && (nowMs - lastTurnMs) >= CLIMB_TO_CRUISE_MS) {
                    // 5с без поворота → подозрение на выход
                    state = CirclingState.SUSPECT_CRUISE;
                    stateEnterMs = nowMs;
                }
                break;

            case SUSPECT_CRUISE:
                if (turning) {
                    // Снова крутимся → возврат в CLIMB
                    state = CirclingState.CLIMB;
                    stateEnterMs = nowMs;
                    break;
                }
                if (nowMs - stateEnterMs >= CLIMB_TO_CRUISE_MS) {
                    // 5с подряд без поворота → окончательный выход в CRUISE
                    state = CirclingState.CRUISE;
                    stateEnterMs = nowMs;
                    showLabel = false;
                    for (int i = 0; i < SECTORS; i++) {
                        sectorVario[i] = 0f;
                        sectorCount[i] = 0;
                    }
                    fullCirclesCompleted = 0;
                    hasPrevCenter = false;
                    totalAngle = 0f;
                    circleOffset = 0f;
                }
                break;
        }

        boolean isCircling = (state == CirclingState.CLIMB)
                || (state == CirclingState.SUSPECT_CRUISE);

        // ================================================================
        // 3. Трекинг угла + сбор GPS спирали (только в CLIMB/SUSPECT_CRUISE)
        // ================================================================
        if (isCircling) {
            // 3a. Угол поворота
            if (!trackInitialized) {
                lastTrackDeg = track;
                lastHeadingDeg = heading;
                trackInitialized = true;
            }

            float delta = heading - lastHeadingDeg;
            if (delta > 180f) delta -= 360f;
            else if (delta < -180f) delta += 360f;

            totalAngle += delta;
            circleOffset += delta;
            lastHeadingDeg = heading;

            // Показать "крутим термик" после 540°
            if (!showLabel && Math.abs(totalAngle) >= LABEL_ANGLE) {
                showLabel = true;
            }

            // 3b. Сбор GPS для центра текущей спирали
            if (gpsLat != 0.0 && gpsLon != 0.0) {
                circleLatSum += gpsLat;
                circleLonSum += gpsLon;
                circleGpsCount++;
            }

            // 3c. Секторный анализ варио
            if (!Float.isNaN(vario) && !Float.isInfinite(vario)) {
                int sector = getSectorFromHeading(heading);
                if (sectorCount[sector] == 0) {
                    sectorVario[sector] = vario;
                } else {
                    sectorVario[sector] = 0.7f * sectorVario[sector] + 0.3f * vario;
                }
                sectorCount[sector]++;
            }

            // ============================================================
            // 4. Полный круг завершён
            // ============================================================
            if (Math.abs(circleOffset) >= FULL_CIRCLE) {
                fullCirclesCompleted++;
                circleOffset -= Math.signum(circleOffset) * FULL_CIRCLE;

                // 4a. Вычислить центр завершённой спирали
                processCircleCenter(gpsLat, gpsLon, altMsl, nowMs);

                // 4b. Голосовая подсказка
                if (voiceCallback != null && (nowMs - lastVoiceMs) >= VOICE_DEBOUNCE_MS) {
                    String guidance = getCircleGuidance();
                    if (guidance != null) {
                        voiceCallback.speak(guidance);
                        lastVoiceMs = nowMs;
                    }
                }

                // 4c. Сброс секторов для следующего круга
                for (int i = 0; i < SECTORS; i++) {
                    sectorVario[i] = 0f;
                    sectorCount[i] = 0;
                }
            }
        }

        // ================================================================
        // 5. Оценка ветра по EKF на прямых участках + WindStore
        // ================================================================
        if (!isCircling && gpsSpeed > 3f && gpsLat != 0.0 && gpsLon != 0.0) {
            double gpsRad = Math.toRadians(gpsCourse);
            double gpsVx = gpsSpeed * Math.sin(gpsRad);
            double gpsVy = gpsSpeed * Math.cos(gpsRad);

            windEKF.update(airspeedMs, gpsVx, gpsVy);

            if (windEKF.getQuality() >= 2) {
                double ekfSpeed = windEKF.getWindSpeed();
                double ekfDir = windEKF.getWindDirectionDeg();
                if (ekfSpeed > 0.5f) {
                    if (windConfidence < 2) {
                        float newFrom = (float) ekfDir;
                        if (windFromDeg < 0) {
                            windFromDeg = newFrom;
                            windSpeedMs = (float) ekfSpeed;
                        } else {
                            float wdiff = newFrom - windFromDeg;
                            if (wdiff > 180f) wdiff -= 360f;
                            else if (wdiff < -180f) wdiff += 360f;
                            windFromDeg += WIND_DIR_EMA_ALPHA * 0.5f * wdiff;
                            if (windFromDeg < 0f) windFromDeg += 360f;
                            if (windFromDeg >= 360f) windFromDeg -= 360f;
                            windSpeedMs += WIND_SPEED_EMA_ALPHA * 0.5f * ((float)ekfSpeed - windSpeedMs);
                        }
                    }
                    windStore.addEKFMeasurement(windEKF, altMsl, nowMs);
                }
            }
        }

        // Обновляем lastTrack для следующего кадра
        lastTrackDeg = track;
        }
    }

    // ========================================================================
    // Turn rate calculation (как XCSoar CirclingComputer)
    // ========================================================================

    private void updateTurnRates(float track, float heading, long nowMs) {
        if (!trackInitialized || lastTrackTimeMs == 0) {
            lastTrackDeg = track;
            lastHeadingDeg = heading;
            lastTrackTimeMs = nowMs;
            trackInitialized = true;
            return;
        }

        long dtMs = nowMs - lastTrackTimeMs;
        if (dtMs <= 0) dtMs = 1;
        if (dtMs > DT_CAP_MS) {
            // Слишком большой разрыв → сброс
            lastTrackDeg = track;
            lastHeadingDeg = heading;
            lastTrackTimeMs = nowMs;
            return;
        }
        float dt = dtMs / 1000f;

        // Track delta с нормализацией
        float trackDelta = track - lastTrackDeg;
        while (trackDelta > 180) trackDelta -= 360;
        while (trackDelta < -180) trackDelta += 360;

        float headingDelta = heading - lastHeadingDeg;
        while (headingDelta > 180) headingDelta -= 360;
        while (headingDelta < -180) headingDelta += 360;

        // Raw turn rates
        float rawTrackRate = trackDelta / dt;
        float rawHeadingRate = headingDelta / dt;

        // Clamp to ±50°/s (как XCSoar)
        rawTrackRate = Math.max(-MAX_TURN_RATE_CLAMP, Math.min(MAX_TURN_RATE_CLAMP, rawTrackRate));
        rawHeadingRate = Math.max(-MAX_TURN_RATE_CLAMP, Math.min(MAX_TURN_RATE_CLAMP, rawHeadingRate));

        // LowPassFilter(α=0.3) — как XCSoar
        turnRateDegS += TURN_RATE_LOWPASS_ALPHA * (rawTrackRate - turnRateDegS);
        turnRateHeadingDegS += TURN_RATE_LOWPASS_ALPHA * (rawHeadingRate - turnRateHeadingDegS);

        lastTrackDeg = track;
        lastHeadingDeg = heading;
        lastTrackTimeMs = nowMs;
    }

    // ========================================================================
    // Обработка завершённого круга
    // ========================================================================

    private void processCircleCenter(double gpsLat, double gpsLon, float altMsl, long nowMs) {
        if (circleGpsCount < MIN_GPS_CIRCLE_SAMPLES) return;

        double newCenterLat = circleLatSum / circleGpsCount;
        double newCenterLon = circleLonSum / circleGpsCount;

        if (hasPrevCenter) {
            double dist = haversineMeters(prevCircleLat, prevCircleLon,
                    newCenterLat, newCenterLon);
            long dtMs = nowMs - prevCircleEndMs;
            float driftSpeedMs = dtMs > 0 ? (float)(dist / (dtMs / 1000.0)) : 0f;

            if (dist >= MIN_WIND_DRIFT_DIST && driftSpeedMs >= MIN_WIND_DRIFT_SPEED) {
                float driftBearing = bearingDeg(prevCircleLat, prevCircleLon,
                        newCenterLat, newCenterLon);
                float newWindFrom = driftBearing + 180f;
                if (newWindFrom >= 360f) newWindFrom -= 360f;

                if (windFromDeg < 0) {
                    windFromDeg = newWindFrom;
                    windSpeedMs = driftSpeedMs;
                    windConfidence = 1;
                } else {
                    float wdiff = newWindFrom - windFromDeg;
                    if (wdiff > 180f) wdiff -= 360f;
                    else if (wdiff < -180f) wdiff += 360f;
                    windFromDeg += WIND_DIR_EMA_ALPHA * wdiff;
                    if (windFromDeg < 0f) windFromDeg += 360f;
                    if (windFromDeg >= 360f) windFromDeg -= 360f;
                    windSpeedMs += WIND_SPEED_EMA_ALPHA * (driftSpeedMs - windSpeedMs);
                    windConfidence++;
                }

                if (windConfidence >= 2 && windSpeedMs > 0.3f) {
                    windStore.addMeasurement(windFromDeg, windSpeedMs,
                            Math.min(5, windConfidence), altMsl, nowMs);
                }
            }
        }

        prevCircleLat = newCenterLat;
        prevCircleLon = newCenterLon;
        prevCircleEndMs = nowMs;
        hasPrevCenter = true;

        // Сброс для следующей спирали
        circleLatSum = gpsLat;
        circleLonSum = gpsLon;
        circleGpsCount = 1;
        circleStartMs = nowMs;
    }

    // ========================================================================
    // Голосовая подсказка после полного круга
    // ========================================================================

    private String getCircleGuidance() {
        // Найти сектор с максимальным средним варио
        int bestSector = -1;
        float bestVario = -999f;
        for (int i = 0; i < SECTORS; i++) {
            if (sectorCount[i] >= MIN_SECTOR_SAMPLES && sectorVario[i] > bestVario) {
                bestVario = sectorVario[i];
                bestSector = i;
            }
        }

        if (bestSector < 0) return null;

        float bestHeading = sectorCenterHeading(bestSector);

        // Если известен ветер и достоверность > 2 спиралей
        if (windFromDeg >= 0 && windConfidence > 1) {
            float diff = bestHeading - windFromDeg;
            if (diff < 0) diff += 360f;

            if (diff < 45f || diff >= 315f) {
                return "ядро на ветер";
            } else if (diff >= 135f && diff < 225f) {
                return "ядро по ветру";
            }
        }

        switch (bestSector) {
            case 0:  return "Ядро север";
            case 1:  return "Ядро восток";
            case 2:  return "Ядро юг";
            case 3:  return "Ядро запад";
            default: return null;
        }
    }

    // ========================================================================
    // Секторы
    // ========================================================================

    private static int getSectorFromHeading(float heading) {
        if (heading >= 315f || heading < 45f) return 0;
        if (heading >= 45f && heading < 135f) return 1;
        if (heading >= 135f && heading < 225f) return 2;
        return 3;
    }

    private static float sectorCenterHeading(int sector) {
        switch (sector) {
            case 0:  return 0f;
            case 1:  return 90f;
            case 2:  return 180f;
            case 3:  return 270f;
            default: return 0f;
        }
    }

    // ========================================================================
    // Геодезические вычисления
    // ========================================================================

    /** Haversine distance in meters between two GPS points */
    private static double haversineMeters(double lat1, double lon1,
                                          double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /** Bearing (degrees) from point A to point B */
    private static float bearingDeg(double lat1, double lon1,
                                    double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                 - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float) ((bearing + 360.0) % 360.0);
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /** Текущее состояние state machine */
    public CirclingState getCirclingState() {
        synchronized (stateLock) { return state; }
    }

    /** True: уверенная крутка или выход из крутки */
    public boolean isCircling() {
        synchronized (stateLock) {
            return state == CirclingState.CLIMB
                || state == CirclingState.SUSPECT_CRUISE;
        }
    }

    /** True: крутим термик >540° */
    public boolean isShowThermalLabel() {
        synchronized (stateLock) { return showLabel; }
    }

    /** True: только что начали крутку (первые 4с, SUSPECT_CLIMB) */
    public boolean isEnteringCircling() {
        synchronized (stateLock) {
            return state == CirclingState.SUSPECT_CLIMB;
        }
    }

    // --- Wind ---

    public float getWindFromDeg() {
        synchronized (stateLock) { return windFromDeg; }
    }

    public float getWindSpeedMs() {
        synchronized (stateLock) { return windSpeedMs; }
    }

    public int getWindConfidence() {
        synchronized (stateLock) { return windConfidence; }
    }

    public float getDisplayWindSpeed() {
        synchronized (stateLock) {
            if (windSpeedMs > 0) return windSpeedMs;
            return -1f;
        }
    }

    public WindStore getWindStore() {
        return windStore;
    }

    public WindStore.WindMeasurement getWindAtAltitude(float altMsl, long nowMs) {
        return windStore.getWindAt(altMsl, nowMs);
    }

    public WindEKF getWindEKF() {
        return windEKF;
    }

    // --- Turn rates ---

    public float getTurnRateDegS() {
        synchronized (stateLock) { return turnRateDegS; }
    }

    public float getTurnRateHeadingDegS() {
        synchronized (stateLock) { return turnRateHeadingDegS; }
    }

    // --- Climb stats ---

    /** Высота на момент начала крутки */
    public float getTurnStartAltitude() {
        synchronized (stateLock) { return turnStartAltitude; }
    }

    /** Высота на момент подтверждения CLIMB */
    public float getClimbStartAltitude() {
        synchronized (stateLock) { return climbStartAltitude; }
    }

    /** Время начала крутки */
    public long getClimbStartMs() {
        synchronized (stateLock) { return climbStartMs; }
    }

    // --- Circle tracking ---

    public float getTotalAngle() {
        synchronized (stateLock) { return totalAngle; }
    }

    public int getFullCirclesCompleted() {
        synchronized (stateLock) { return fullCirclesCompleted; }
    }

    /** Средний набор высоты за всё время крутки (м/c) */
    public float getClimbAverage() {
        synchronized (stateLock) {
            if (climbStartMs <= 0 || state != CirclingState.CLIMB) return 0f;
            return 0f; // рассчитывается извне по vario
        }
    }

    /** Установить воздушную скорость (м/с) */
    public void setAirspeed(float ms) {
        this.airspeedMs = Math.max(8f, Math.min(15f, ms));
    }

    /** Полный сброс с очисткой WindStore и WindEKF */
    public void fullReset() {
        reset();
        windEKF.reset();
        windStore.reset();
        synchronized (stateLock) {
            windFromDeg = -1f;
            windSpeedMs = -1f;
            windConfidence = 0;
        }
    }
}
