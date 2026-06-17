package com.termo1.radar.flight;

/**
 * CirclingManager — детекция вращения в термике, сбор варио по секторам,
 * оценка ветра по дрейфу центров спиралей, голосовые подсказки.
 *
 * Состояния:
 * - IDLE: не вращаемся
 * - CIRCLING: вращаемся > 4 с (подтверждённая крутка)
 * - LABEL_SHOWN: накрутили 540° — показываем "крутим термик"
 *
 * Оценка ветра (основной метод):
 * - Во время крутки трекаем GPS-центр каждой спирали
 * - После 2+ спиралей: дрейф центра прошлой → новой = направление ветра ПО
 * - Ветер ОТКУДА = обратное направление
 * - Скорость = расстояние / время круга
 *
 * Дополнительно (прямолинейный полёт):
 * - GPS-скорость vs воздушная 9.5 м/с: разница даёт компоненту ветра
 *
 * На каждом полном обороте (360°):
 * 1. Найти сектор (N/E/S/W) с максимальным средним варио
 * 2. Если известен ветер — сравнить: "ядро на ветер" / "ядро по ветру"
 * 3. Иначе — "Ядро север/юг/запад/восток"
 */
public class CirclingManager {

    // ========================================================================
    // Константы
    // ========================================================================

    /** Порог гироскопа (рад/с) — ~18°/с */
    private static final float GYRO_THRESHOLD_RAD = (float) Math.toRadians(18.0);
    /** Сколько ms вращения для подтверждения крутки */
    private static final long CIRCLING_CONFIRM_MS = 4000;
    /** Гистерезис: сколько ms без поворота для выхода из крутки */
    private static final long CIRCLING_HYSTERESIS_MS = 5000;
    /** Суммарный угол для показа "крутим термик" (360+180) */
    private static final float LABEL_ANGLE = 540f;
    /** Полный круг */
    private static final float FULL_CIRCLE = 360f;
    /** Коэффициент EMA для гироскопа */
    private static final float GYRO_EMA_ALPHA = 0.15f;
    /** Дебаунс голоса (мс) */
    private static final long VOICE_DEBOUNCE_MS = 8000;
    /** Минимальное количество сэмплов в секторе для достоверности */
    private static final int MIN_SECTOR_SAMPLES = 20;
    /** Минимальное количество GPS точек в спирали */
    private static final int MIN_GPS_CIRCLE_SAMPLES = 3;
    /** Коэффициент EMA для оценки скорости ветра */
    private static final float WIND_SPEED_EMA_ALPHA = 0.3f;
    /** Коэффициент EMA для направления ветра */
    private static final float WIND_DIR_EMA_ALPHA = 0.3f;
    /** Воздушная скорость параплана в ламинаре (м/с) */
    private static final float AIRSPEED_MS = 9.5f;

    // ========================================================================
    // Состояние крутки
    // ========================================================================

    private float gyroEma;
    private long circlingSinceMs;
    private boolean circlingConfirmed;
    private long lastTurnMs;

    // ========================================================================
    // Трекинг угла
    // ========================================================================

    private float lastHeading;
    private boolean headingInitialized;
    private float totalAngle;
    private float circleOffset;
    private int fullCirclesCompleted;
    private boolean showLabel;

    // ========================================================================
    // Секторный анализ (4 сектора по 90°: N/E/S/W)
    // ========================================================================

    private static final int SECTORS = 4;
    private final float[] sectorVario = new float[SECTORS];
    private final int[] sectorCount = new int[SECTORS];

    // ========================================================================
    // GPS-центры спиралей (для оценки ветра по дрейфу)
    // ========================================================================

    // Накопление GPS для текущей (неполной) спирали
    private double circleLatSum;
    private double circleLonSum;
    private int circleGpsCount;
    private long circleStartMs;

    // Предыдущий завершённый центр спирали
    private boolean hasPrevCenter;
    private double prevCircleLat;
    private double prevCircleLon;
    private long prevCircleEndMs;

    // ========================================================================
    // Оценка ветра (результат)
    // ========================================================================

    /** Направление, ОТКУДА дует ветер (градусы, магнитный). -1 = неизвестно */
    private float windFromDeg = -1f;
    /** Скорость ветра (м/с). -1 = неизвестно */
    private float windSpeedMs = -1f;
    /** Достоверность (число измерений) */
    private int windConfidence;
    /** Скорость ветра, оценённая по GPS-скорости на прямой (м/с) */
    private float windSpeedFromGps = -1f;

    // ========================================================================
    // Коллбэк для голоса
    // ========================================================================

    public interface VoiceCallback {
        void speak(String text);
    }

    private VoiceCallback voiceCallback;
    private long lastVoiceMs;

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
        gyroEma = 0f;
        circlingSinceMs = 0;
        circlingConfirmed = false;
        lastTurnMs = 0;
        headingInitialized = false;
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

    // ========================================================================
    // Главное обновление
    // ========================================================================

    public void update(float gyroZ, float heading, float vario,
                       double gpsLat, double gpsLon,
                       float gpsSpeed, float gpsCourse,
                       long nowMs) {

        // ================================================================
        // 1. Детекция крутки по гироскопу Z
        // ================================================================
        gyroEma += GYRO_EMA_ALPHA * (gyroZ - gyroEma);
        boolean turning = Math.abs(gyroEma) > GYRO_THRESHOLD_RAD;

        if (turning) {
            lastTurnMs = nowMs;
            if (circlingSinceMs == 0) {
                circlingSinceMs = nowMs;
            }
        }

        // Подтверждение: вращаемся > 4 с
        if (!circlingConfirmed && circlingSinceMs > 0
                && (nowMs - circlingSinceMs) >= CIRCLING_CONFIRM_MS) {
            circlingConfirmed = true;
            headingInitialized = false;
            totalAngle = 0f;
            circleOffset = 0f;
            fullCirclesCompleted = 0;
            showLabel = false;
            // Начинаем сбор GPS для первой спирали
            circleLatSum = gpsLat;
            circleLonSum = gpsLon;
            circleGpsCount = 1;
            circleStartMs = nowMs;
        }

        // Выход: 5 с без поворота
        if (circlingConfirmed && (nowMs - lastTurnMs) > CIRCLING_HYSTERESIS_MS) {
            circlingConfirmed = false;
            circlingSinceMs = 0;
            showLabel = false;
            for (int i = 0; i < SECTORS; i++) {
                sectorVario[i] = 0f;
                sectorCount[i] = 0;
            }
            fullCirclesCompleted = 0;
            hasPrevCenter = false; // следующий раз начнём с чистого листа
        }

        // ================================================================
        // 2. Трекинг угла + сбор GPS спирали (только в крутке)
        // ================================================================
        if (circlingConfirmed) {
            // 2a. Угол поворота
            if (!headingInitialized) {
                lastHeading = heading;
                headingInitialized = true;
            }

            float delta = heading - lastHeading;
            if (delta > 180f) delta -= 360f;
            else if (delta < -180f) delta += 360f;

            totalAngle += delta;
            circleOffset += delta;
            lastHeading = heading;

            // Показать "крутим термик" после 540°
            if (!showLabel && Math.abs(totalAngle) >= LABEL_ANGLE) {
                showLabel = true;
            }

            // 2b. Сбор GPS для центра текущей спирали
            if (gpsLat != 0.0 && gpsLon != 0.0) {
                circleLatSum += gpsLat;
                circleLonSum += gpsLon;
                circleGpsCount++;
            }

            // 2c. Секторный анализ варио
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
            // 3. Полный круг завершён
            // ============================================================
            if (Math.abs(circleOffset) >= FULL_CIRCLE) {
                fullCirclesCompleted++;
                circleOffset -= Math.signum(circleOffset) * FULL_CIRCLE;

                // 3a. Вычислить центр завершённой спирали
                if (circleGpsCount >= MIN_GPS_CIRCLE_SAMPLES) {
                    double newCenterLat = circleLatSum / circleGpsCount;
                    double newCenterLon = circleLonSum / circleGpsCount;

                    if (hasPrevCenter) {
                        // Дрейф: от prevCenter к newCenter
                        double dist = haversineMeters(prevCircleLat, prevCircleLon, newCenterLat, newCenterLon);
                        long dtMs = nowMs - prevCircleEndMs;
                        float driftSpeedMs = dtMs > 0 ? (float)(dist / (dtMs / 1000.0)) : 0f;

                        if (dist > 5.0 && driftSpeedMs > 0.3f) {
                            // Направление дрейфа = куда сносит (downwind)
                            float driftBearing = bearingDeg(prevCircleLat, prevCircleLon, newCenterLat, newCenterLon);
                            // Ветер ОТКУДА = обратное направление
                            float newWindFrom = driftBearing + 180f;
                            if (newWindFrom >= 360f) newWindFrom -= 360f;

                            // EMA направления ветра
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
                        }
                    }

                    // Сохраняем как предыдущий центр
                    prevCircleLat = newCenterLat;
                    prevCircleLon = newCenterLon;
                    prevCircleEndMs = nowMs;
                    hasPrevCenter = true;
                }

                // Сброс накопления для следующей спирали
                circleLatSum = gpsLat;
                circleLonSum = gpsLon;
                circleGpsCount = 1;
                circleStartMs = nowMs;

                // 3b. Голосовая подсказка
                if (voiceCallback != null && (nowMs - lastVoiceMs) >= VOICE_DEBOUNCE_MS) {
                    String guidance = getCircleGuidance();
                    if (guidance != null) {
                        voiceCallback.speak(guidance);
                        lastVoiceMs = nowMs;
                    }
                }

                // 3c. Сброс секторов для следующего круга
                for (int i = 0; i < SECTORS; i++) {
                    sectorVario[i] = 0f;
                    sectorCount[i] = 0;
                }
            }
        }

        // ================================================================
        // 4. Оценка ветра по GPS-скорости на прямой (дополнительно)
        // ================================================================
        if (!circlingConfirmed && gpsSpeed > 3f) {
            // По разнице GPS-скорости и воздушной скорости оцениваем компоненту ветра вдоль курса
            float windComponent = gpsSpeed - AIRSPEED_MS;
            if (Math.abs(windComponent) > 0.5f) {
                // Если дрейфуем — уточняем
                float drift = heading - gpsCourse;
                if (drift > 180f) drift -= 360f;
                else if (drift < -180f) drift += 360f;

                if (Math.abs(drift) < 20f) {
                    // Летим почти прямо по курсу — ветер вдоль или против
                    windSpeedFromGps = Math.abs(windComponent);
                }
            }
        }
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
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /** Bearing (degrees) from point A to point B */
    private static float bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                 - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float) ((bearing + 360.0) % 360.0);
    }

    // ========================================================================
    // Состояние для UI
    // ========================================================================

    public boolean isCircling() {
        return circlingConfirmed;
    }

    public boolean isShowThermalLabel() {
        return showLabel;
    }

    /** Направление ветра ОТКУДА (градусы, 0-360), -1 если неизвестно */
    public float getWindFromDeg() {
        return windFromDeg;
    }

    /** Скорость ветра (м/с), -1 если неизвестна */
    public float getWindSpeedMs() {
        return windSpeedMs;
    }

    /** Достоверность оценки ветра (число замеров по спиралям) */
    public int getWindConfidence() {
        return windConfidence;
    }

    /** Скорость ветра по GPS-скорости на прямой (м/с), -1 если недоступна */
    public float getWindSpeedFromGps() {
        return windSpeedFromGps;
    }

    /** Итоговая скорость ветра для отображения */
    public float getDisplayWindSpeed() {
        if (windSpeedMs > 0) return windSpeedMs;
        if (windSpeedFromGps > 0) return windSpeedFromGps;
        return -1f;
    }
}
