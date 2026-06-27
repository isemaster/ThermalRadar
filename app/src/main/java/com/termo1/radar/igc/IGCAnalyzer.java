package com.termo1.radar.igc;

import com.termo1.radar.model.ThermalBlip;
import java.util.ArrayList;
import java.util.List;

/**
 * IGCAnalyzer — анализирует TrackPoint[] и выдаёт DisplayFrame для каждой позиции.
 *
 * Заменяет: TrackReplayer, CirclingManager, VarioThermalDetector,
 *           ThermalLocator, LiftDatabase, WindStore.
 *
 * Принцип работы:
 * - Весь трек загружен в TrackPoint[]
 * - scrollTo(timeSec) — перематывает на позицию
 * - Скользящие окна для: speed, vario, L/D, circling, wind
 * - Выдаёт DisplayFrame с пред-рассчитанными полями
 *
 * Использование:
 *   IGCAnalyzer analyzer = new IGCAnalyzer(track);
 *   analyzer.scrollTo(0); // старт
 *   DisplayFrame frame = analyzer.getCurrentFrame();
 */
public class IGCAnalyzer {

    private final TrackPoint[] track;
    private final float totalTimeSec;
    private final float launchAltitude;

    // ========================================================================
    // Константы
    // ========================================================================
    private static final float VARIO_EMA_ALPHA = 0.25f;
    private static final int L_D_WINDOW_SEC = 30;
    private static final float CIRCLE_HEADING_ACCUM = 360f;
    private static final float CIRCLE_ANGLE_WINDOW_SEC = 30f;
    private static final float THERMAL_VARIO_THRESHOLD = 0.5f;
    private static final float THERMAL_CONFIRM_TIME = 8f;
    private static final int AVG_WINDOW = 3;
    private static final float VARIO_AVG_WINDOW_SEC = 30f;
    private static final int WIND_BUF_MAX = 100; // 100s sliding window
    private static final float AIRSPEED_MS = 9.5f;

    // ========================================================================
    // Текущая позиция (scroll состояние)
    // ========================================================================
    private int currentIdx;
    private float currentTimeSec;
    private float pilotLat, pilotLon;
    private float altitude;      // display altitude
    private float varioAlt;      // altitude for vario calculation (pressure preferred)
    private float headingDeg;

    // Скользящие окна
    private float smoothVario = 0f;
    private float smoothSpeed = 0f;
    private float smoothWindDir = -1f;
    private float smoothWindSpd = -1f;

    // Скорость из GPS-сегментов (1Гц обновление)
    private float lastGpsTimeSec = -1f;
    private float lastFrameSpeed = 0f;
    private boolean hasLastFrame;
    private float lastFrameAlt;

    // 5-сек rolling avg for speed
    private final float[] speedAvgBuf = new float[AVG_WINDOW];
    private int avgIdx = 0;
    private int avgCount = 0;

    // Wind 100s sliding window (ring buffer, ~1Гц)
    private final float[] windDirBuf = new float[WIND_BUF_MAX];
    private final float[] windSpdBuf = new float[WIND_BUF_MAX];
    private final float[] windTimeBuf = new float[WIND_BUF_MAX];
    private int windHead = 0;
    private int windCount = 0;

    // L/D буфер (8 сек, ~1Гц)
    private static final int LD_BUF_MAX = 32;
    private final double[] ldLatBuf = new double[LD_BUF_MAX];
    private final double[] ldLonBuf = new double[LD_BUF_MAX];
    private final float[] ldVarioBuf = new float[LD_BUF_MAX]; // baro vario integral
    private final float[] ldTimeBuf = new float[LD_BUF_MAX];
    private int ldHead = 0;
    private int ldCount = 0;
    private float lastLDTime = -10f; // 1Hz sampling guard

    // Circling detection
    private float headingAccum = 0f;
    private float prevHeading = -1f;
    private boolean wasCircling = false;

    // Spiral centroids for wind from drift
    private double circleLatSum, circleLonSum;
    private int circlePointCount;
    private double thermalCenterLat, thermalCenterLon;
    private boolean hasPrevCircleCenter;
    private double prevCircleLat, prevCircleLon;
    private float prevCircleTimeSec;

    // Thermal state (baro-based)
    private float liftTimer = 0f;
    private boolean thermalActive = false;
    private boolean showRedCore = false;

    // ========================================================================
    // Трейл (кольцевой буфер)
    // ========================================================================
    private static final int TRAIL_MAX = 2000;
    private final float[] trailLats = new float[TRAIL_MAX];
    private final float[] trailLons = new float[TRAIL_MAX];
    private final float[] trailVarios = new float[TRAIL_MAX];
    private final int[] trailColors = new int[TRAIL_MAX];
    private final long[] trailTimes = new long[TRAIL_MAX];
    private int trailHead = 0;
    private int trailCount = 0;
    private long lastTrailAddSimMs = 0;

    // ========================================================================
    // Pre-calculated track polyline
    // ========================================================================
    private float[] cachedPolyPx = null;
    private float[] cachedPolyPy = null;
    private int cachedPolyCount = 0;

    // ========================================================================
    // HUD string cache
    // ========================================================================
    private float lastSpeedKmh = -1f;
    private String lastSpeedStr = "";
    private float lastVarioVal = -999f;
    private String lastVarioStr = "";
    private float lastAvgVario = -999f;
    private String lastAvgVarioStr = "";
    private float lastWindSpd = -1f;
    private String lastWindStr = "";
    private float lastAltMsl = -1f;
    private String lastAltMslStr = "";
    private float lastAgl = -1f;
    private String lastAglStr = "";
    private int lastFtSec = -1;
    private String lastFtStr = "";

    // ========================================================================
    // Конструктор
    // ========================================================================
    public IGCAnalyzer(TrackPoint[] track) {
        this.track = track;
        if (track == null || track.length < 2) {
            this.totalTimeSec = 0f;
            this.launchAltitude = 0f;
            return;
        }
        this.totalTimeSec = track[track.length - 1].timeSec - track[0].timeSec;
        this.launchAltitude = track[0].displayAltM;
    }

    public float getTotalTime() { return totalTimeSec; }
    public float getLaunchAltitude() { return launchAltitude; }
    public TrackPoint[] getTrack() { return track; }
    public boolean isReady() { return track != null && track.length >= 2; }

    // ========================================================================
    // Навигация
    // ========================================================================

    /** Перемотать на позицию (сек от начала) */
    public void scrollTo(float timeSec) {
        if (!isReady()) return;
        timeSec = Math.max(0, Math.min(timeSec, totalTimeSec));
        currentTimeSec = timeSec;

        float target = track[0].timeSec + timeSec;
        currentIdx = findSegment(target);

        TrackPoint a = track[currentIdx];
        TrackPoint b = track[Math.min(currentIdx + 1, track.length - 1)];
        float segStart = a.timeSec;
        float segEnd = b.timeSec;
        float t = (segEnd > segStart) ? (target - segStart) / (segEnd - segStart) : 0;

        double[] interp = IGCParser.interpolateGreatCircle(a.lat, a.lon, b.lat, b.lon, t);
        pilotLat = (float) interp[0];
        pilotLon = (float) interp[1];
        altitude = a.displayAltM + (b.displayAltM - a.displayAltM) * t;
        varioAlt = a.getVarioAltM() + (b.getVarioAltM() - a.getVarioAltM()) * t;

        // Heading
        double dLat = b.lat - a.lat;
        double dLon = b.lon - a.lon;
        double dLonAdj = dLon * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0));
        headingDeg = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
        if (headingDeg < 0) headingDeg += 360;

        hasLastFrame = false;
        lastGpsTimeSec = -1f;
        lastFrameAlt = altitude;
        lastLDTime = currentTimeSec - 2f; // reset L/D buffer (recalc in 2s)
    }

    /** Продвинуться на dt секунд */
    public void advance(float dt) {
        if (!isReady() || dt <= 0) return;
        if (currentTimeSec >= totalTimeSec) return;

        currentTimeSec += dt;
        if (currentTimeSec > totalTimeSec) currentTimeSec = totalTimeSec;

        float target = track[0].timeSec + currentTimeSec;
        currentIdx = findSegment(target);

        TrackPoint a = track[currentIdx];
        TrackPoint b = track[Math.min(currentIdx + 1, track.length - 1)];
        float segStart = a.timeSec;
        float segEnd = b.timeSec;
        float t = (segEnd > segStart) ? (target - segStart) / (segEnd - segStart) : 0;

        // Position
        double[] interp = IGCParser.interpolateGreatCircle(a.lat, a.lon, b.lat, b.lon, t);
        pilotLat = (float) interp[0];
        pilotLon = (float) interp[1];

        // Altitude
        float newAlt = a.displayAltM + (b.displayAltM - a.displayAltM) * t;
        float newVarioAlt = a.getVarioAltM() + (b.getVarioAltM() - a.getVarioAltM()) * t;

        // Vario (EMA smoothed) with TE compensation
        if (hasLastFrame) {
            float altDelta = newVarioAlt - lastFrameAlt;
            float rawVario = altDelta / Math.max(dt, 0.01f);
            // TE compensation: subtract kinetic energy change
            // TE = (v2^2 - v1^2) / (2*g), vario_TE = dh/dt + TE/dt
            float speed = getSpeedMs();
            float teVario = rawVario;
            if (dt > 0.001f) {
                float keDelta = (speed * speed - lastFrameSpeed * lastFrameSpeed) / (2f * 9.81f);
                teVario += keDelta / dt;
            }
            smoothVario = smoothVario * (1f - VARIO_EMA_ALPHA) + teVario * VARIO_EMA_ALPHA;
            lastFrameSpeed = speed;
        } else {
            smoothVario = 0f;
        }
        lastFrameAlt = newVarioAlt;
        altitude = newAlt;
        varioAlt = newVarioAlt;

        // Heading
        double dLat = pilotLat - a.lat;
        double dLon = pilotLon - a.lon;
        double dLonAdj = dLon * Math.cos(Math.toRadians((a.lat + pilotLat) / 2.0));
        double dist = Math.sqrt(dLat * dLat + dLonAdj * dLonAdj);
        if (dist > 0.0000001) {
            float newHdg = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
            if (newHdg < 0) newHdg += 360;
            headingDeg = newHdg;

            // Heading accumulation for circling
            if (prevHeading >= 0) {
                float delta = headingDeg - prevHeading;
                while (delta > 180) delta -= 360;
                while (delta < -180) delta += 360;
                headingAccum += Math.abs(delta);
                if (Math.abs(delta) < 2) headingAccum *= 0.9f;
            }
            prevHeading = headingDeg;
        }

        // Speed from GPS segment
        double segDist = IGCParser.haversineMeters(a.lat, a.lon, b.lat, b.lon);
        boolean gpsChanged = segDist > 1.0;
        if (gpsChanged && a != null && b != null && lastGpsTimeSec != b.timeSec) {
            float gpsInterval = 1.0f;
            if (lastGpsTimeSec > 0) {
                gpsInterval = Math.max(b.timeSec - lastGpsTimeSec, 0.001f);
            }
            lastGpsTimeSec = b.timeSec;
            float segSpeed = (float)(segDist / gpsInterval);
            smoothSpeed = smoothSpeed * 0.3f + segSpeed * 0.7f;

            // Speed: 3-second SMA for display, raw segSpeed pushed
            speedAvgBuf[avgIdx] = segSpeed;
            avgIdx = (avgIdx + 1) % AVG_WINDOW;
            if (avgCount < AVG_WINDOW) avgCount++;
            // Wind ring buffer (100s sliding window)
            if (smoothWindDir >= 0) {
                windDirBuf[windHead] = smoothWindDir;
                windSpdBuf[windHead] = smoothWindSpd;
                windTimeBuf[windHead] = currentTimeSec;
                windHead = (windHead + 1) % WIND_BUF_MAX;
                if (windCount < WIND_BUF_MAX) windCount++;
            }
        }

        hasLastFrame = true;

        // === L/D ===
        updateLDBuffer();

        // === Wind ===
        if (Math.abs(smoothVario) <= 1.3f && gpsChanged) {
            estimateWind();
        }

        // === Circling ===
        updateCircling();

        // === Thermal ===
        updateThermalState(dt);

        // === Trail ===
        updateTrail();

        // === Reset circling decay ===
        if (!wasCircling && headingAccum > 0) {
            headingAccum *= 0.98f;
            if (headingAccum < 10) headingAccum = 0;
        }
    }

    /** Получить текущий кадр */
    public DisplayFrame getCurrentFrame() {
        if (!isReady()) return DisplayFrame.EMPTY;

        float displaySpeed = getSpeedMs();
        float displayVario = smoothVario;
        float displayWindDir = getWindFromDeg();
        float displayWindSpd = getWindSpeedMs();

        // L/D
        boolean goingBack = false;
        float glideRatio = 0f;
        float glideRange = 0f;
        float[] ldResult = computeLD();
        glideRatio = ldResult[0];
        goingBack = ldResult[1] > 0.5f;
        float agl = Math.max(0, altitude - launchAltitude);
        glideRange = (glideRatio >= 99f || glideRatio <= -99f)
                ? 99.9f * (goingBack ? -1 : 1)
                : agl * glideRatio / 1000f;
        glideRange = Math.max(-99.9f, Math.min(99.9f, glideRange));

        // Progress
        float progress = totalTimeSec > 0 ? currentTimeSec / totalTimeSec : 0;

        // Status
        String status = computeStatus();

        // Alt AGL
        float aglVal = Math.max(0, altitude - launchAltitude);

        // HUD strings
        float speedKmh = displaySpeed * 3.6f;

        return new DisplayFrame(
            pilotLat, pilotLon,
            altitude, aglVal, launchAltitude,
            displaySpeed, speedKmh, displayVario, getAvgVario30(),
            headingDeg,
            displayWindDir, displayWindSpd,
            glideRatio, glideRange, goingBack,
            progress, status,
            wasCircling, thermalBearing, thermalDistM, showRedCore,
            getGuidanceText(),
            getBlips(),
            cachedPolyPx, cachedPolyPy, cachedPolyCount,
            getTrailPxBuf(), getTrailPyBuf(), getTrailColorBuf(), trailCount,
            formatSpeed(speedKmh),
            formatVario(displayVario),
            formatAvgVario(getAvgVario30()),
            formatWind(displayWindSpd),
            formatAlt(altitude),
            formatAlt(aglVal),
            formatFlightTime(),
            formatLDRatio(glideRatio),
            formatRange(glideRange),
            0, 0,  // battery/sat — из живых источников
            true,   // isReplay
            currentTimeSec < totalTimeSec  // isRunning
        );
    }

    // ========================================================================
    // L/D
    // ========================================================================
    private void updateLDBuffer() {
        // 1Hz sampling: только раз в секунду sim-времени
        if (currentTimeSec - lastLDTime < 0.9f) return;
        lastLDTime = currentTimeSec;
        ldLatBuf[ldHead] = pilotLat;
        ldLonBuf[ldHead] = pilotLon;
        ldVarioBuf[ldHead] = smoothVario;
        ldTimeBuf[ldHead] = currentTimeSec;
        ldHead = (ldHead + 1) % LD_BUF_MAX;
        if (ldCount < LD_BUF_MAX) ldCount++;
    }

    private float[] computeLD() {
        if (ldCount < 2) return new float[]{0f, 0f};

        int newest = (ldHead - 1 + LD_BUF_MAX) % LD_BUF_MAX;
        float now = ldTimeBuf[newest];

        // Найти точку на ≥8с раньше
        int oldest = -1;
        for (int i = 0; i < ldCount; i++) {
            int idx = (ldHead - 1 - i + LD_BUF_MAX) % LD_BUF_MAX;
            if (now - ldTimeBuf[idx] >= L_D_WINDOW_SEC) {
                oldest = idx;
                break;
            }
        }
        if (oldest < 0) return new float[]{0f, 0f};

        double dist = IGCParser.haversineMeters(
                ldLatBuf[oldest], ldLonBuf[oldest],
                ldLatBuf[newest], ldLonBuf[newest]);

        // Vario integral over window
        float varioSum = 0;
        float dtSum = 0;
        int cur = oldest;
        while (cur != newest) {
            int next = (cur + 1) % LD_BUF_MAX;
            float dt = ldTimeBuf[next] - ldTimeBuf[cur];
            if (dt > 0 && dt < 3f) {
                varioSum += ldVarioBuf[cur] * dt;
                dtSum += dt;
            }
            cur = next;
        }
        float altDiff = -varioSum; // снижение = положительное

        float ratio;
        boolean goingBack = false;
        if (dist < 5) {
            ratio = 0f;
        } else if (altDiff <= 0.5f) {
            ratio = 99f;
        } else {
            ratio = (float)(dist / altDiff);
            // backward check
            float bearing = IGCParser.haversineBearing(
                    ldLatBuf[oldest], ldLonBuf[oldest],
                    ldLatBuf[newest], ldLonBuf[newest]);
            float hdgDiff = Math.abs(bearing - headingDeg);
            if (hdgDiff > 180) hdgDiff = 360 - hdgDiff;
            if (hdgDiff > 120f) {
                goingBack = true;
                ratio = -ratio;
            }
        }

        ratio = Math.max(-99f, Math.min(99f, ratio));
        return new float[]{ratio, goingBack ? 1f : 0f};
    }

    // ========================================================================
    // Wind
    // ========================================================================
    private void estimateWind() {
        float speed = getSpeedMs();
        if (speed < 0.5f) return;

        // Along-track wind (no compass needed)
        float alongWind = speed - AIRSPEED_MS;
        float windSpd = Math.abs(alongWind);
        if (windSpd < 0.3f) return;

        float windDir = headingDeg + 180f;
        if (windDir < 0) windDir += 360;
        if (windDir >= 360) windDir -= 360;

        // Crosswind rejection
        if (windCount > 0) {
            float avgDir = getWindFromDeg();
            if (avgDir >= 0) {
                float dirDiff = Math.abs(windDir - avgDir);
                if (dirDiff > 180) dirDiff = 360 - dirDiff;
                if (dirDiff > 90) return;
            }
        }

        // Просто сохраняем в smoothWindDir/Spd для внутренних расчётов
        // (getWindFromDeg/getWindSpeedMs используют кольцевой буфер)
        if (smoothWindSpd < 0 || smoothWindDir < 0) {
            smoothWindDir = windDir;
            smoothWindSpd = windSpd;
        } else {
            smoothWindDir = windDir;
            smoothWindSpd = windSpd;
        }
    }

    // ========================================================================
    // Circling
    // ========================================================================
    private void updateCircling() {
        boolean isCircling = headingAccum > CIRCLE_HEADING_ACCUM;

        if (isCircling && !wasCircling) {
            // Wind from spiral drift
            if (hasPrevCircleCenter) {
                double drift = IGCParser.haversineMeters(
                        prevCircleLat, prevCircleLon,
                        thermalCenterLat, thermalCenterLon);
                float dtSec = currentTimeSec - prevCircleTimeSec;
                if (drift > 5.0 && dtSec > 10f) {
                    float driftSpeed = (float)(drift / dtSec);
                    if (driftSpeed > 0.3f) {
                        float driftBearing = IGCParser.haversineBearing(
                                prevCircleLat, prevCircleLon,
                                thermalCenterLat, thermalCenterLon);
                        float windFrom = driftBearing + 180f;
                        if (windFrom >= 360f) windFrom -= 360f;
                        if (smoothWindSpd < 0) {
                            smoothWindDir = windFrom;
                            smoothWindSpd = driftSpeed;
                        } else {
                            // Напрямую — сглаживание через кольцевой буфер
                            smoothWindDir = windFrom;
                            smoothWindSpd = driftSpeed;
                        }
                    }
                }
            }
            circleLatSum = pilotLat;
            circleLonSum = pilotLon;
            circlePointCount = 1;
        }

        if (!isCircling && wasCircling) {
            // Save completed spiral centroid
            prevCircleLat = thermalCenterLat;
            prevCircleLon = thermalCenterLon;
            prevCircleTimeSec = currentTimeSec;
            hasPrevCircleCenter = true;
        }

        if (isCircling) {
            circleLatSum += pilotLat;
            circleLonSum += pilotLon;
            circlePointCount++;
            thermalCenterLat = circleLatSum / circlePointCount;
            thermalCenterLon = circleLonSum / circlePointCount;
        }

        wasCircling = isCircling;
    }

    // ========================================================================
    // Thermal (baro-based)
    // ========================================================================
    private float thermalBearing = 0f;
    private float thermalDistM = 50f;
    private final List<ThermalBlip> blips = new ArrayList<>();

    private void updateThermalState(float dt) {
        if (smoothVario > THERMAL_VARIO_THRESHOLD) {
            liftTimer += dt;
        } else {
            liftTimer = Math.max(0, liftTimer - dt * 0.5f);
        }

        if (liftTimer > THERMAL_CONFIRM_TIME || wasCircling) {
            thermalActive = true;
            if (wasCircling) {
                showRedCore = true;
                float[] db = {0f, 0f};
                db[0] = (float) IGCParser.haversineMeters(
                        pilotLat, pilotLon, thermalCenterLat, thermalCenterLon);
                db[1] = IGCParser.haversineBearing(
                        pilotLat, pilotLon, thermalCenterLat, thermalCenterLon);
                thermalDistM = db[0];
                thermalBearing = db[1];
            } else {
                showRedCore = false;
                thermalBearing = headingDeg;
                thermalDistM = 50f + (1f - liftTimer / 20f) * 100f;
                if (thermalDistM > 200) thermalDistM = 200;
                if (thermalDistM < 30) thermalDistM = 30;
            }
        } else if (liftTimer > 3f) {
            thermalActive = true;
            showRedCore = false;
            thermalBearing = headingDeg + 10f;
            thermalDistM = 100f;
        } else {
            thermalActive = false;
            showRedCore = false;
        }

        // Push blip
        if (thermalActive) {
            long nowMs = System.currentTimeMillis();
            boolean found = false;
            for (ThermalBlip b : blips) {
                if (Math.abs(b.angle - thermalBearing) < 20f
                        && Math.abs(b.distance - thermalDistM) < 30f) {
                    b.distance = thermalDistM;
                    b.angle = thermalBearing;
                    found = true;
                    break;
                }
            }
            if (!found) {
                float strength = showRedCore ? 8f : 4f;
                ThermalBlip tb = new ThermalBlip(thermalBearing, strength,
                        thermalDistM, "igc", nowMs);
                tb.lifeMs = showRedCore ? 12000L : (strength > 3f ? 8000L : 3000L);
                blips.add(tb);
                while (blips.size() > 12) blips.remove(0);
            }
        }

        // Clean dead blips
        long nowMs = System.currentTimeMillis();
        blips.removeIf(tb -> !tb.isAlive(nowMs));
    }

    // ========================================================================
    // Trail
    // ========================================================================
    private void updateTrail() {
        long simMs = (long)(currentTimeSec * 1000);
        if (simMs - lastTrailAddSimMs > 1000) {
            trailLats[trailHead] = pilotLat;
            trailLons[trailHead] = pilotLon;
            trailVarios[trailHead] = smoothVario;
            trailTimes[trailHead] = simMs;
            // Color computed on demand in getTrailColorBuf
            trailHead = (trailHead + 1) % TRAIL_MAX;
            if (trailCount < TRAIL_MAX) trailCount++;
            lastTrailAddSimMs = simMs;
        }
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public float getSpeedMs() {
        return avgCount > 0 ? calcAvg5(speedAvgBuf) : smoothSpeed;
    }

    public float getWindFromDeg() {
        if (windCount < 2) return smoothWindDir;
        float now = currentTimeSec;
        float minTime = now - 100f;
        double sumSin = 0, sumCos = 0;
        int cnt = 0;
        for (int i = 0; i < windCount; i++) {
            int idx = (windHead - 1 - i + WIND_BUF_MAX) % WIND_BUF_MAX;
            if (windTimeBuf[idx] < minTime) break;
            double rad = Math.toRadians(windDirBuf[idx]);
            sumSin += Math.sin(rad);
            sumCos += Math.cos(rad);
            cnt++;
        }
        if (cnt < 2) return smoothWindDir;
        float avg = (float) Math.toDegrees(Math.atan2(sumSin / cnt, sumCos / cnt));
        if (avg < 0) avg += 360;
        return avg;
    }

    public float getWindSpeedMs() {
        if (windCount < 2) return smoothWindSpd;
        float now = currentTimeSec;
        float minTime = now - 100f;
        float sum = 0;
        int cnt = 0;
        for (int i = 0; i < windCount; i++) {
            int idx = (windHead - 1 - i + WIND_BUF_MAX) % WIND_BUF_MAX;
            if (windTimeBuf[idx] < minTime) break;
            sum += windSpdBuf[idx];
            cnt++;
        }
        if (cnt < 2) return smoothWindSpd;
        return sum / cnt;
    }

    public float getAvgVario30() {
        // Vario EMA уже сглажен, не нужен отдельный 30s avg
        return smoothVario;
    }

    public float getCurrentTime() { return currentTimeSec; }
    public float getPilotLat() { return pilotLat; }
    public float getPilotLon() { return pilotLon; }
    public float getAltitude() { return altitude; }
    public float getHeading() { return headingDeg; }

    // ========================================================================
    // Trail buffers (export for DisplayFrame)
    // ========================================================================
    private float[] getTrailPxBuf() {
        float[] buf = new float[trailCount];
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - trailCount + i + TRAIL_MAX) % TRAIL_MAX;
            buf[i] = trailLons[idx];
        }
        return buf;
    }

    private float[] getTrailPyBuf() {
        float[] buf = new float[trailCount];
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - trailCount + i + TRAIL_MAX) % TRAIL_MAX;
            buf[i] = trailLats[idx];
        }
        return buf;
    }

    private int[] getTrailColorBuf() {
        int[] buf = new int[trailCount];
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - trailCount + i + TRAIL_MAX) % TRAIL_MAX;
            float vario = trailVarios[idx];
            float age = 1f; // full opacity for trail
            buf[i] = varioToColor(vario, age);
        }
        return buf;
    }

    private List<ThermalBlip> getBlips() {
        return new ArrayList<>(blips);
    }

    // ========================================================================
    // Status
    // ========================================================================
    private String computeStatus() {
        if (wasCircling && thermalActive) {
            return "ЯДРО ТЕРМИКА";
        }
        if (wasCircling) {
            return "СПИРАЛЬ";
        }
        if (thermalActive) {
            return "ТЕРМИК РЯДОМ — " + (int) thermalDistM + "м";
        }
        if (smoothVario > 0.5f) {
            return "НАБОР +" + String.format("%.1f", smoothVario);
        }
        return "ПОИСК";
    }

    private String getGuidanceText() {
        if (showRedCore && thermalActive) {
            return "Крути! Ядро в " + (int) thermalDistM + "м";
        }
        if (thermalActive) {
            return "Термик впереди — " + (int) thermalDistM + "м";
        }
        return "";
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private int findSegment(float targetTime) {
        for (int i = currentIdx; i < track.length - 1; i++) {
            if (targetTime >= track[i].timeSec && targetTime < track[i + 1].timeSec) {
                return i;
            }
        }
        return track.length - 2;
    }

    private float calcAvg5(float[] buf) {
        if (avgCount == 0) return 0f;
        float sum = 0;
        for (int i = 0; i < avgCount; i++) sum += buf[i];
        return sum / avgCount;
    }



    // ========================================================================
    // HUD formatting (zero-allocation cache)
    // ========================================================================

    private String formatSpeed(float kmh) {
        if (Math.abs(kmh - lastSpeedKmh) < 0.1f) return lastSpeedStr;
        lastSpeedKmh = kmh;
        lastSpeedStr = String.format("%.0f", kmh);
        return lastSpeedStr;
    }

    private String formatVario(float vario) {
        if (Math.abs(vario - lastVarioVal) < 0.05f) return lastVarioStr;
        lastVarioVal = vario;
        if (vario >= 0) {
            lastVarioStr = String.format("+%.1f", vario);
        } else {
            lastVarioStr = String.format("%.1f", vario);
        }
        return lastVarioStr;
    }

    private String formatAvgVario(float avg) {
        if (Math.abs(avg - lastAvgVario) < 0.05f) return lastAvgVarioStr;
        lastAvgVario = avg;
        lastAvgVarioStr = String.format("avg %+.1f", avg);
        return lastAvgVarioStr;
    }

    private String formatWind(float spd) {
        if (Math.abs(spd - lastWindSpd) < 0.1f) return lastWindStr;
        lastWindSpd = spd;
        lastWindStr = String.format("%.1f", spd);
        return lastWindStr;
    }

    private String formatAlt(float alt) {
        if (Math.abs(alt - lastAltMsl) < 0.5f) return lastAltMslStr;
        lastAltMsl = alt;
        lastAltMslStr = String.format("%.0f", alt);
        return lastAltMslStr;
    }

    private String formatFlightTime() {
        int sec = (int) currentTimeSec;
        if (sec == lastFtSec) return lastFtStr;
        lastFtSec = sec;
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        lastFtStr = String.format("%02d:%02d", h, m);
        return lastFtStr;
    }

    private String formatLDRatio(float ratio) {
        if (ratio >= 99f) return "99";
        if (ratio <= -99f) return "-99";
        return String.format("%.1f", ratio);
    }

    private String formatRange(float range) {
        if (range >= 99.9f) return "99.9";
        if (range <= -99.9f) return "-99.9";
        return String.format("%.1f", range);
    }

    // ========================================================================
    // Vario-to-color (from MainActivity, static)
    // ========================================================================
    private static int varioToColor(float vario, float alpha) {
        final float[] keys = {-5f, -2f, -0.3f, 0f, 0.5f, 1.5f, 3f, 5f, 8f};
        final int[] colors = {
            0x001A00, 0x006600, 0x99CC66, 0xFFFF33,
            0xFFAA00, 0xFF4400, 0xFF0000, 0x660000, 0x330000
        };
        int hi = 1;
        while (hi < keys.length - 1 && vario > keys[hi]) hi++;
        int lo = hi - 1;
        float t = (keys[hi] - keys[lo] == 0f) ? 0f
                : (vario - keys[lo]) / (keys[hi] - keys[lo]);
        t = Math.max(0f, Math.min(1f, t));
        int r = (int)(((colors[lo] >> 16) & 0xFF) * (1f - t) + ((colors[hi] >> 16) & 0xFF) * t);
        int g = (int)(((colors[lo] >> 8) & 0xFF) * (1f - t) + ((colors[hi] >> 8) & 0xFF) * t);
        int b = (int)((colors[lo] & 0xFF) * (1f - t) + (colors[hi] & 0xFF) * t);
        int a = (int)(alpha * 200);
        if (a < 8) a = 8;
        if (a > 255) a = 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ========================================================================
    // Track polyline (pre-calculate once)
    // ========================================================================
    public void precalculateTrackPoly(float centerLat, float centerLon, float radiusPx) {
        if (track == null || track.length < 2) return;
        float[] px = new float[track.length];
        float[] py = new float[track.length];
        int count = 0;
        for (int i = 0; i < track.length; i++) {
            float dist = (float) IGCParser.haversineMeters(
                    centerLat, centerLon, track[i].lat, track[i].lon);
            float bearing = IGCParser.haversineBearing(
                    centerLat, centerLon, track[i].lat, track[i].lon);
            float bearingRad = (float) Math.toRadians(bearing);
            // Map to screen coords (centered, radiusPx = trailR)
            px[count] = (float) Math.sin(bearingRad) * (dist / 1500f * radiusPx);
            py[count] = -(float) Math.cos(bearingRad) * (dist / 1500f * radiusPx);
            count++;
        }
        cachedPolyPx = px;
        cachedPolyPy = py;
        cachedPolyCount = count;
    }

    /** Mark track poly as dirty (recalc on next frame) */
    public void invalidateTrackPoly() {
        cachedPolyCount = 0;
    }
}
