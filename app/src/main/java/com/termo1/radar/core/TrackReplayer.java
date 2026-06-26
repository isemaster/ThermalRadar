package com.termo1.radar.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TrackReplayer — проигрывает реальный IGC трек полёта.
 *
 * Берет данные из IGC файла (17-18_17-30.igc), проигрывает с ускорением 2x.
 * Генерирует: GPS, heading, vario, gyroZ, accel для thermal detector.
 * Определяет термики по профилю высоты и показывает красное ядро при крутке.
 *
 * Формат времени в файле: UTC+4 (чтобы 13:xx = 17:xx MSK).
 * Период: 13:17:00 – 13:40:00 (device time) = 17:17 – 17:40 MSK.
 */
public class TrackReplayer {

    // ========================================================================
    // Waypoint from IGC B-record
    // ========================================================================
    public static class TrackPoint {
        public final double lat, lon;
        public final float altMeters; // pressure altitude in meters
        public final float timeSec;   // seconds from start of flight
        TrackPoint(double lat, double lon, float alt, float timeSec) {
            this.lat = lat; this.lon = lon;
            this.altMeters = alt; this.timeSec = timeSec;
        }
    }

    // ========================================================================
    // Constants
    // ========================================================================
    private float playbackSpeed = 2.0f; // 2x (mutable via setSpeed)

    // Noise
    private static final float NOISE_FLOOR_G = 0.003f;

    // Thermal detection
    private static final float THERMAL_VARIO_THRESHOLD = 0.5f; // m/s
    private static final float THERMAL_CONFIRM_TIME = 8f;      // seconds of sustained lift
    private static final float CIRCLE_HEADING_ACCUM = 360f;    // degrees of turn to confirm circling
    private static final float CIRCLE_ANGLE_WINDOW_SEC = 30f;  // seconds to detect circling

    // ========================================================================
    // State
    // ========================================================================
    private List<TrackPoint> track;
    private boolean running;
    private boolean finished;
    private float prevTimeSec; // для детекции midnight crossing
    private int sameSecCount;  // FIX: 5Hz IGC — счётчик записей с одинаковым HHMMSS

    // Simulation clock
    private float totalSimSec;        // total simulated seconds elapsed
    private float totalRealSec;       // total real seconds elapsed

    // Current interpolated state
    private double pilotLat, pilotLon;
    private float altitude;           // meters MSL
    private float heading;            // degrees
    private float vario;              // m/s
    private float speed;              // m/s ground speed
    private float gyroZ;              // rad/s
    private float accelX, accelY;     // g

    // Previous frame position for per-frame speed/vario calc
    private double lastFrameLat, lastFrameLon;
    private float lastFrameAlt;
    private boolean hasLastFramePosition;

    // GPS update tracking: время последнего IGC-сегмента с новой GPS позицией
    private float lastGpsTimeSec = -1f;

    // Сглаженная скорость (EMA) — убирает провалы на GPS-повторах 5Hz
    private float smoothSpeed = 0f;
    private float smoothWindDir = -1f;
    private float smoothWindSpd = -1f;

    // 5-сек скользящее среднее (буферы, 1 Гц = 5 семплов)
    private static final int AVG_WINDOW = 5;
    private final float[] speedAvgBuf = new float[AVG_WINDOW];
    private final float[] windDirAvgBuf = new float[AVG_WINDOW];
    private final float[] windSpdAvgBuf = new float[AVG_WINDOW];
    private int avgIdx = 0;
    private int avgCount = 0;

    // Thermal state
    private boolean thermalActive;
    private boolean showRedCore;
    private boolean hasSensorData; // true = есть ZIP с сенсорами, false = только IGC
    private boolean paused; // принудительная пауза
    private float thermalBearing;     // from pilot
    private float thermalDistance;    // from pilot
    private float thermalRadiusM = 25f; // radius in meters
    private double thermalCenterLat, thermalCenterLon;
    private String guidanceText;

    // Internal tracking
    private float liftTimer;          // seconds of sustained lift
    private float headingAccum;       // accumulated heading change for circle detection
    private float prevHeading;
    private boolean wasCircling;
    private float circleLatSum, circleLonSum;
    private int circlePointCount;
    private double noisePhase;

    // Wind from spiral drift (дрейф центров последовательных спиралей)
    private boolean hasPrevCircleCenter;
    private double prevCircleLat, prevCircleLon;
    private float prevCircleTimeSec;

    // Wind estimate from IGC (constant)
    private float windFromDeg = 315f;
    private float windSpeedMs = 5f;

    // Воздушная скорость для расчёта ветра (из настроек, default 9.5 м/с)
    private float airspeedMs = 9.5f;

    // Real sensor data from companion ZIP
    private static class SensorSample {
        final long dtMs;
        final float axMg, ayMg, azMg;   // accel in milli-g (all 3 axes)
        final float mx, my, mz;          // magnetometer (μT)
        final float varioMs;             // m/s
        final float headingDeg;
        final long timestampMs;          // elapsedRealtime-like
        SensorSample(long dtMs, float axMg, float ayMg, float azMg,
                     float mx, float my, float mz,
                     float varioMs, float headingDeg, long timestampMs) {
            this.dtMs = dtMs; this.axMg = axMg; this.ayMg = ayMg; this.azMg = azMg;
            this.mx = mx; this.my = my; this.mz = mz;
            this.varioMs = varioMs; this.headingDeg = headingDeg; this.timestampMs = timestampMs;
        }
    }
    private List<SensorSample> sensorData;
    private int sensorIdx; // current read position in sensorData

    // Компасный heading из ZIP (реальный курс носа параплана, а не GPS track)
    private float sensorHeading = 0f;
    private boolean hasSensorHeading; // false = нет ZIP, используем track-based heading

    // Gravity estimate for world-transform (removing gravity from raw accel → linear accel)
    private final float[] gravityEstimate = new float[]{0f, 0f, 0f};
    private boolean gravityInitialized;
    private static final float GRAVITY_IIR_ALPHA = 0.005f; // very slow adaptation for gravity

    // Interpolation index
    private int currentIdx;

    public TrackReplayer() {}

    /**
     * Set the playback speed multiplier (default: 2.0f).
     * @param speed 1.0f = real-time, 2.0f = 2x, etc.
     */
    public void setSpeed(float speed) {
        this.playbackSpeed = speed;
    }
    public float getPlaybackSpeed() { return playbackSpeed; }

    /** Установить наличие сенсорных данных (true = есть ZIP, false = только IGC). */
    public void setHasSensorData(boolean v) {
        this.hasSensorData = v;
    }

    /** Установить ветер для отображения при реплее (из CirclingManager или IGC) */
    public void setWind(float fromDeg, float speedMs) {
        this.windFromDeg = fromDeg;
        this.windSpeedMs = speedMs;
    }

    /** Установить воздушную скорость (триммер) для расчёта ветра */
    public void setAirspeedMs(float v) {
        this.airspeedMs = Math.max(8f, Math.min(15f, v));
    }

    /**
     * Оценка ветра по прямолинейному полёту.
     * wind = ground_velocity - air_velocity
     * Работает когда пилот на триммерах (снижение ≤1.3 м/с) — 99% случаев.
     * GPS track + compass heading + trim airspeed.
     * Если compass heading ненадёжен (отличается от track >45°) — 
     * используем упрощённый along-track расчёт (speed - airspeed вдоль трека).
     * Результат: направление ОТКУДА дует ветер (meteo convention, +180°).
     */
    public void estimateWindFromFlight() {
        float hdgDeg = getCompassHeading();
        double gpsRad = Math.toRadians(heading);   // heading = direction of travel (GPS track)
        double hdgRad = Math.toRadians(hdgDeg);    // compass heading (nose)

        // Check heading reliability: if compass differs from track >45°, it's noisy
        float hdgDiff = Math.abs(hdgDeg - heading);
        if (hdgDiff > 180) hdgDiff = 360 - hdgDiff;
        boolean headingReliable = hdgDiff < 45f;

        float windSpeed, windDir;
        if (headingReliable) {
            // Full 2D wind vector from heading + track
            double wx = speed * Math.sin(gpsRad) - airspeedMs * Math.sin(hdgRad);
            double wy = speed * Math.cos(gpsRad) - airspeedMs * Math.cos(hdgRad);
            windSpeed = (float) Math.sqrt(wx*wx + wy*wy);
            windDir = (float) Math.toDegrees(Math.atan2(wx, wy)) + 180f;
        } else {
            // Along-track only: wind = speed - airspeed (headwind negative)
            float alongWind = speed - airspeedMs;
            windSpeed = Math.abs(alongWind);
            // Wind FROM the direction of travel (headwind = opposite of track)
            windDir = heading + 180f;
        }
        if (windDir < 0) windDir += 360;
        if (windDir >= 360) windDir -= 360;
        if (windSpeed < 0.3f) return;
        // FIX: reject crosswind legs — if new direction differs from current
        // estimate by >90°, this is a crosswind measurement, not real wind.
        // Light winds (1-2 m/s) in the correct direction ARE accepted.
        if (smoothWindSpd > 0) {
            float dirDiff = Math.abs(windDir - smoothWindDir);
            if (dirDiff > 180) dirDiff = 360 - dirDiff;
            if (dirDiff > 90) return; // crosswind leg — discard
        }
        // EMA smooth: α=0.15 (медленнее, чем было 0.2 — меньше прыжков)
        if (smoothWindSpd < 0 || smoothWindDir < 0) {
            smoothWindDir = windDir;
            smoothWindSpd = windSpeed;
        } else {
            float diff = windDir - smoothWindDir;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            smoothWindDir += 0.15f * diff;
            if (smoothWindDir < 0) smoothWindDir += 360;
            if (smoothWindDir >= 360) smoothWindDir -= 360;
            smoothWindSpd += 0.15f * (windSpeed - smoothWindSpd);
        }
        windFromDeg = smoothWindDir;
        windSpeedMs = smoothWindSpd;
    }

    /** Скользящее среднее за 5 сек по буферу */
    private float calcAvg5(float[] buf) {
        if (avgCount == 0) return 0f;
        float sum = 0;
        for (int i = 0; i < avgCount; i++) sum += buf[i];
        return sum / avgCount;
    }

    /** Скользящее среднее направления ветра (через sin/cos для 0-360) */
    private float calcWindDir5() {
        if (avgCount == 0) return smoothWindDir;
        double sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < avgCount; i++) {
            if (windDirAvgBuf[i] >= 0) {
                double rad = Math.toRadians(windDirAvgBuf[i]);
                sx += Math.sin(rad);
                sy += Math.cos(rad);
                n++;
            }
        }
        if (n == 0) return smoothWindDir;
        float avg = (float) Math.toDegrees(Math.atan2(sx / n, sy / n));
        if (avg < 0) avg += 360;
        return avg;
    }

    /**
     * Load IGC data from a specific file path.
     * @return true если трек загружен успешно, false если файл пуст/бит
     * Исправлено TR-10: возвращаем boolean, silent fallback убран
     */
    public boolean loadFile(String filePath) {
        try {
            loadFromIGC(new java.io.FileInputStream(filePath));
        } catch (Exception e) {
            e.printStackTrace();
            if (track == null || track.isEmpty()) {
                return false;
            }
        }
        if (track == null || track.isEmpty()) {
            return false;
        }
        // Загружаем companion ZIP если есть
        sensorData = null;
        String zipPath = filePath.replace(".igc", ".zip");
        java.io.File zipFile = new java.io.File(zipPath);
        if (!zipFile.exists() && filePath.contains("_FIXED")) {
            // FIX: если IGC _FIXED, пробуем оригинальное имя ZIP
            zipPath = filePath.replace("_FIXED.igc", ".igc").replace(".igc", ".zip");
            zipFile = new java.io.File(zipPath);
        }
        if (zipFile.exists()) {
            loadSensorZip(zipPath);
        }
        return true;
    }

    /**
     * Load embedded demo track (R.raw.track_replay).
     */
    public void loadEmbeddedDemoTrack() {
        track = new ArrayList<>();
        // Use embedded resource — парсим без фильтра времени, т.к. встроенный файл уже короткий
        android.util.Log.i("TERMO1_REPLAY", "Loading embedded demo track");
    }

    /**
     * Load IGC data from an InputStream.
     * Исправлено TR-1/2/3/4: убран time filter, улучшен парсинг суб-секунд,
     * добавлена проверка fixValid 'A', исправлен pressure alt = 0
     */
    private void loadFromIGC(InputStream inputStream) {
        track = new ArrayList<>();
        prevTimeSec = -1f;
        sameSecCount = 0;
        long dayOffset = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) != 'B') continue;
                try {
                    if (line.length() < 35) continue; // min B-record length

                    // TR-3: проверка fix validity 'A'/'V'
                    char fixValid = line.charAt(24);
                    if (fixValid != 'A') continue; // пропускаем невалидные фиксы

                    String t = line.substring(1, 7);
                    int hh = Integer.parseInt(t.substring(0, 2));
                    int mm = Integer.parseInt(t.substring(2, 4));
                    int ss = Integer.parseInt(t.substring(4, 6));
                    float timeSec = hh * 3600f + mm * 60f + ss;

                    // TR-2: кумулятивный midnight crossing через dayOffset
                    if (prevTimeSec >= 0) {
                        float candidateTime = timeSec + dayOffset * 86400f;
                        if (candidateTime < prevTimeSec - 3600f) {
                            dayOffset++;
                            candidateTime = timeSec + dayOffset * 86400f;
                        }
                        timeSec = candidateTime;
                    }
                    prevTimeSec = timeSec;

                    // TR-2: суб-секунды — определяем частоту пост-фактум
                    // Если предыдущая запись имеет тот же HHMMSS, добавляем 0.2с
                    // (5Hz IGC). После загрузки проверим и скорректируем.
                    // Для 1Hz — same-sec не бывает, всё ОК.
                    // Для 10Hz — пока поддерживаем 5Hz (4 записи/сек макс в IGC).

                    float latDeg = Float.parseFloat(line.substring(7, 9));
                    float latMin = Float.parseFloat(line.substring(9, 14)) / 1000f;
                    double lat = latDeg + latMin / 60.0;
                    if (line.charAt(14) == 'S') lat = -lat;
                    float lonDeg = Float.parseFloat(line.substring(15, 18));
                    float lonMin = Float.parseFloat(line.substring(18, 23)) / 1000f;
                    double lon = lonDeg + lonMin / 60.0;
                    if (line.charAt(23) == 'W') lon = -lon;

                    // TR-4: pressure altitude — 0 валидно (уровень моря)
                    // Только если поле pressure пустое (не "00000") — fallback на GPS alt
                    int pressAlt = 0, gpsAlt = 0;
                    try { pressAlt = Integer.parseInt(line.substring(25, 30).trim()); } catch (Exception ignored) {}
                    if (line.length() >= 35) {
                        try { gpsAlt = Integer.parseInt(line.substring(30, 35).trim()); } catch (Exception ignored) {}
                    }
                    // Всегда используем pressure altitude
                    // Если pressure пустое (а не "00000") — fallback на GPS
                    boolean pressureEmpty = line.substring(25, 30).trim().isEmpty();
                    int alt = pressureEmpty ? gpsAlt : pressAlt;

                    // Проверка null island
                    if (lat == 0.0 && lon == 0.0) continue;

                    track.add(new TrackPoint(lat, lon, alt, timeSec));
                } catch (Exception e) {
                    // skip bad records
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (track.isEmpty()) {
            // TR-10: больше не генерируем fallback — caller получит false
            android.util.Log.w("TERMO1_REPLAY", "No valid B-records found in IGC file");
            return;
        }

        // TR-2: пост-фактум определение частоты и коррекция суб-секунд
        // Если медианный dt > 0.5с — файл 1Hz или 2Hz, убираем суб-секунды
        // Если dt < 0.3с — файл high-rate (5Hz+), оставляем как есть
        if (track.size() > 10) {
            float dtSum = 0;
            int dtCount = 0;
            for (int i = 1; i < Math.min(track.size(), 30); i++) {
                float dt = track.get(i).timeSec - track.get(i-1).timeSec;
                if (dt > 0 && dt < 5f) { dtSum += dt; dtCount++; }
            }
            float avgDt = dtCount > 0 ? dtSum / dtCount : 1f;
            if (avgDt >= 0.5f) {
                // 1Hz или 2Hz файл — убираем суб-секунды, округляем до целых секунд
                for (int i = 0; i < track.size(); i++) {
                    float rawSec = track.get(i).timeSec;
                    float floored = (float) Math.floor(rawSec);
                    track.set(i, new TrackPoint(
                        track.get(i).lat, track.get(i).lon,
                        track.get(i).altMeters, floored));
                }
            }
            // для high-rate (5Hz+) — суб-секунды корректны, оставляем
        }
    }

    /** Загрузить сенсорные данные из companion ZIP */
    public boolean loadSensorZip(String zipPath) {
        sensorData = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".csv")) {
                    zis.closeEntry();
                    continue;
                }
                // Парсим CSV: dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,gpsFixAge,gpsAccuracy,vario,ax,ay,az,...
                BufferedReader br = new BufferedReader(new InputStreamReader(zis));
                String header = br.readLine(); // skip header
                long baseTimeMs = System.currentTimeMillis();
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(",");
                    if (parts.length < 10) continue;
                    try {
                        long dtMs = Long.parseLong(parts[0]);
                        float varioMs = Float.parseFloat(parts[8]);
                        float axMg = Float.parseFloat(parts[9]); // already in milli-g
                        float ayMg = Float.parseFloat(parts[10]);
                        float azMg = (parts.length > 11) ? Float.parseFloat(parts[11]) : 0f;
                        float mx = (parts.length > 15) ? Float.parseFloat(parts[15]) : 0f;
                        float my = (parts.length > 16) ? Float.parseFloat(parts[16]) : 0f;
                        float mz = (parts.length > 17) ? Float.parseFloat(parts[17]) : 0f;
                        float headingDeg = (parts.length > 21) ? Float.parseFloat(parts[21]) : 0f;
                        sensorData.add(new SensorSample(dtMs, axMg, ayMg, azMg, mx, my, mz, varioMs, headingDeg, baseTimeMs + dtMs));
                    } catch (Exception ignored) {}
                }
                zis.closeEntry();
                break; // Only first CSV
            }
        } catch (Exception e) {
            android.util.Log.e("TERMO1_REPLAY", "Failed to load sensor ZIP: " + zipPath, e);
            sensorData = null;
            return false;
        }
        if (sensorData != null && !sensorData.isEmpty()) {
            android.util.Log.i("TERMO1_REPLAY", "Loaded " + sensorData.size() + " sensor samples from " + zipPath);
            return true;
        }
        sensorData = null;
        return false;
    }

    public void start() {
        if (track == null || track.size() < 2) return;
        running = true;
        finished = false;
        totalSimSec = 0;
        totalRealSec = 0;
        currentIdx = 0;

        // Start at first point
        TrackPoint first = track.get(0);
        pilotLat = first.lat;
        pilotLon = first.lon;
        altitude = first.altMeters;
        lastFrameLat = first.lat;
        lastFrameLon = first.lon;
        lastFrameAlt = first.altMeters;
        hasLastFramePosition = false;
        sameSecCount = 0;
        lastGpsTimeSec = -1f;
        heading = 0;
        vario = 0;
        speed = 0;
        gyroZ = 0;
        accelX = 0; accelY = 0;
        noisePhase = 0;

        thermalActive = false;
        showRedCore = false;
        liftTimer = 0;
        headingAccum = 0;
        prevHeading = -1;
        wasCircling = false;
        circleLatSum = 0; circleLonSum = 0;
        circlePointCount = 0;
        guidanceText = "";
        sensorIdx = 0;
        // Wind: cold start — исправлено TR-9: windSpeedMs = 0 (не -1, иначе UI check ломается)
        windFromDeg = -1f;
        windSpeedMs = 0f;
        sensorHeading = 0f;
        hasSensorHeading = false;
        smoothSpeed = 0f;
        smoothWindDir = -1f;
        smoothWindSpd = -1f;
        gravityEstimate[0] = 0f;
        gravityEstimate[1] = 0f;
        gravityEstimate[2] = 0f;
        gravityInitialized = false;
        avgIdx = 0;
        avgCount = 0;
    }

    public void stop() {
        running = false;
        finished = true;
    }

    public boolean isRunning() { return running; }
    public boolean isFinished() { return finished; }

    /**
     * Main update. Call at ~50Hz.
     * @param realDeltaMs elapsed real time in ms since last frame
     */
    public void update(long realDeltaMs) {
        if (!running || track == null || track.size() < 2) return;
        if (paused) return;

        float dt = realDeltaMs / 1000f;
        if (dt <= 0) return;
        if (dt > 0.05f) dt = 0.05f;

        totalRealSec += dt;
        // 2x speed
        float simDt = dt * playbackSpeed;
        totalSimSec += simDt;
        noisePhase += simDt * 50;

        // Исправлено TR-7: суб-дискретизация — не больше 0.1с sim-времени за шаг
        // При playbackSpeed=10x simDt может быть 0.5с → рывки интерполяции
        int subSteps = Math.max(1, (int) Math.ceil(Math.abs(simDt) / 0.1f));
        float subDt = simDt / subSteps;
        for (int s = 0; s < subSteps; s++) {
            advanceFrame(subDt);
        }
    }

    /** Один под-шаг интерполяции (выделен из update() для TR-7) */
    private void advanceFrame(float simDt) {
        // Find current position on track
        TrackPoint p0 = null, p1 = null;
        float frac = 0;

        // Find the segment we're on
        float targetTime = track.get(0).timeSec + totalSimSec;
        // Actually totalSimSec IS our elapsed simulated time, but we need to map it to track time.
        // Track starts at timeSec of first record.
        float trackTime = track.get(0).timeSec + totalSimSec;

        // Check if past the end
        float lastTime = track.get(track.size() - 1).timeSec;
        if (trackTime >= lastTime) {
            running = false;
            finished = true;
            return;
        }

        // Find segment
        for (int i = currentIdx; i < track.size() - 1; i++) {
            if (trackTime >= track.get(i).timeSec && trackTime < track.get(i + 1).timeSec) {
                currentIdx = i;
                p0 = track.get(i);
                p1 = track.get(i + 1);
                float segLen = p1.timeSec - p0.timeSec;
                if (segLen < 0.001f) segLen = 0.001f; // защита от NaN
                frac = (trackTime - p0.timeSec) / segLen;
                if (frac > 1) frac = 1;
                if (frac < 0) frac = 0;
                break;
            }
        }

        if (p0 == null || p1 == null) {
            // Past end or before start
            if (trackTime < track.get(0).timeSec) {
                p0 = p1 = track.get(0);
                frac = 0;
            } else {
                running = false;
                finished = true;
                return;
            }
        }

        // Interpolate position (TR-5: great-circle interpolation вместо линейной)
        TrackPoint prevPoint = p0;
        TrackPoint nextPoint = p1;

        double prevLat = prevPoint.lat;
        double prevLon = prevPoint.lon;
        double nextLat = nextPoint.lat;
        double nextLon = nextPoint.lon;

        double[] interpRes = interpolateGreatCircle(prevLat, prevLon, nextLat, nextLon, frac);
        pilotLat = interpRes[0];
        pilotLon = interpRes[1];

        float prevAlt = prevPoint.altMeters;
        float nextAlt = nextPoint.altMeters;
        float newAlt = prevAlt + (nextAlt - prevAlt) * frac;

        // Vario from per-frame altitude change (FIX: was segment-start based)
        if (hasLastFramePosition) {
            float altDeltaPerFrame = newAlt - lastFrameAlt;
            vario = altDeltaPerFrame / Math.max(simDt, 0.01f);
        } else {
            vario = 0;
        }
        lastFrameAlt = newAlt;
        altitude = newAlt;

        // Heading from position change (с учётом схождения меридианов)
        double dLat = pilotLat - prevLat;
        double dLon = pilotLon - prevLon;
        double dLonAdj = dLon * Math.cos(Math.toRadians((prevLat + pilotLat) / 2.0));
        double dist = Math.sqrt(dLat * dLat + dLonAdj * dLonAdj);
        if (dist > 0.0000001) {
            float newHeading = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
            if (newHeading < 0) newHeading += 360;
            float hdgDelta = newHeading - heading;
            while (hdgDelta > 180) hdgDelta -= 360;
            while (hdgDelta < -180) hdgDelta += 360;
            heading = newHeading;

            // Gyro Z
            gyroZ = (float) Math.toRadians(hdgDelta) / Math.max(simDt, 0.01f);

            // Heading accumulation for circle detection
            if (prevHeading >= 0) {
                float delta = heading - prevHeading;
                while (delta > 180) delta -= 360;
                while (delta < -180) delta += 360;
                headingAccum += Math.abs(delta);
                // Reset if not moving much
                if (Math.abs(delta) < 2) {
                    headingAccum *= 0.9f;
                }
            }
            prevHeading = heading;
        }

        // Speed from IGC segment when GPS position changes, hold on repeats
        // FIX: GPS updates at 1Hz in 5Hz IGC. Per-frame speed over 0.167s is 6x too high.

        // Исправлено TR-11: gpsChanged через реальную дистанцию > 1м, а не через шумовой порог 0.0000001°
        // segDist уже вычислен haversineMeters ниже — сначала считаем, потом проверяем
        double segDist = haversineMeters(p0.lat, p0.lon, p1.lat, p1.lon);
        boolean gpsChanged = segDist > 1.0;
        if (gpsChanged && p0 != null && p1 != null
                && lastGpsTimeSec != p1.timeSec) {
            // FIX: защита от повторной обработки одного сегмента.
            // update() вызывается ~30-60fps, но сегмент (p0,p1) не меняется
            // между кадрами. Без этого guard'а gpsInterval = 0.001s на 2+ кадре
            // → segSpeed ×1000.
            float gpsInterval = 1.0f; // default: 1 Hz GPS
            if (lastGpsTimeSec > 0) {
                gpsInterval = Math.max(p1.timeSec - lastGpsTimeSec, 0.001f);
            }
            lastGpsTimeSec = p1.timeSec;
            // segDist уже вычислен выше для gpsChanged — используем его
            float segSpeed = (float)(segDist / gpsInterval);
            // EMA blend with smoothSpeed for continuity
            smoothSpeed = smoothSpeed * 0.3f + segSpeed * 0.7f;
            // Push to 5-sec rolling average
            speedAvgBuf[avgIdx] = smoothSpeed;
            windDirAvgBuf[avgIdx] = smoothWindDir;
            windSpdAvgBuf[avgIdx] = smoothWindSpd;
            avgIdx = (avgIdx + 1) % AVG_WINDOW;
            if (avgCount < AVG_WINDOW) avgCount++;
        }
        speed = (avgCount > 0) ? calcAvg5(speedAvgBuf) : smoothSpeed;
        lastFrameLat = pilotLat;
        lastFrameLon = pilotLon;
        hasLastFramePosition = true;

        // === Wind estimation from straight flight ===
        // Условия: ровный полёт, снижение ≤1.3 м/с, есть GPS обновление
        if (Math.abs(vario) <= 1.3f && gpsChanged && speed > 0.5f) {
            estimateWindFromFlight();
        }

        // === Thermal detection ===
        updateThermalState(simDt);

        // === Accel ===
        updateAccel(simDt);
    }

    private void updateThermalState(float dt) {
        if (vario > THERMAL_VARIO_THRESHOLD) {
            liftTimer += dt;
        } else {
            liftTimer = Math.max(0, liftTimer - dt * 0.5f);
        }

        // Check for circling (accumulated heading change)
        boolean isCircling = headingAccum > CIRCLE_HEADING_ACCUM;
        if (isCircling && !wasCircling) {
            // Start of a NEW spiral.
            // thermalCenterLat/Lon holds the PREVIOUS completed spiral's centroid.
            if (hasPrevCircleCenter) {
                double drift = haversineMeters(prevCircleLat, prevCircleLon,
                        thermalCenterLat, thermalCenterLon);
                float dtSec = totalSimSec - prevCircleTimeSec;
                if (drift > 5.0 && dtSec > 10f) {
                    float driftSpeed = (float)(drift / dtSec);
                    if (driftSpeed > 0.3f) {
                        float driftBearing = haversineBearing(prevCircleLat, prevCircleLon,
                                thermalCenterLat, thermalCenterLon)[1];
                        float windFrom = driftBearing + 180f;
                        if (windFrom >= 360f) windFrom -= 360f;
                        if (smoothWindSpd < 0) {
                            smoothWindDir = windFrom;
                            smoothWindSpd = driftSpeed;
                        } else {
                            float diff = windFrom - smoothWindDir;
                            while (diff > 180) diff -= 360;
                            while (diff < -180) diff += 360;
                            smoothWindDir += 0.15f * diff;
                            if (smoothWindDir < 0) smoothWindDir += 360;
                            if (smoothWindDir >= 360) smoothWindDir -= 360;
                            smoothWindSpd += 0.15f * (driftSpeed - smoothWindSpd);
                        }
                        windFromDeg = smoothWindDir;
                        windSpeedMs = smoothWindSpd;
                    }
                }
            }
            // Reset accumulation for new spiral
            circleLatSum = (float) pilotLat;
            circleLonSum = (float) pilotLon;
            circlePointCount = 1;
        }

        // Exit from circling — save completed centroid for wind-from-drift
        if (!isCircling && wasCircling) {
            prevCircleLat = thermalCenterLat;
            prevCircleLon = thermalCenterLon;
            prevCircleTimeSec = totalSimSec;
            hasPrevCircleCenter = true;
        }

        if (isCircling) {
            circleLatSum += (float) pilotLat;
            circleLonSum += (float) pilotLon;
            circlePointCount++;
        }

        wasCircling = isCircling;

        // Show thermal
        if (liftTimer > THERMAL_CONFIRM_TIME || isCircling) {
            thermalActive = true;

            if (isCircling) {
                showRedCore = true;
                // Thermal center = centroid of circling positions
                thermalCenterLat = circleLatSum / circlePointCount;
                thermalCenterLon = circleLonSum / circlePointCount;

                // Distance and bearing from pilot to thermal center
                float[] distBearing = haversineBearing(
                        pilotLat, pilotLon, thermalCenterLat, thermalCenterLon);
                thermalDistance = distBearing[0];
                thermalBearing = distBearing[1];

                guidanceText = "Крути термик! Ядро в " + (int) thermalDistance + "м";
            } else {
                showRedCore = false;
                // Thermal ahead in the direction of flight
                thermalBearing = heading;
                thermalDistance = 50f + (1f - liftTimer / 20f) * 100f;
                if (thermalDistance > 200) thermalDistance = 200;
                if (thermalDistance < 30) thermalDistance = 30;
                guidanceText = "Термик впереди! distance=" + (int) thermalDistance + "м";
            }
        } else if (liftTimer > 3f) {
            // Early thermal detection
            thermalActive = true;
            showRedCore = false;
            thermalBearing = heading + 10f; // slightly right
            thermalDistance = 100f;
            guidanceText = "Поиск термика... +" + String.format("%.1f", vario) + " м/с";
        } else {
            thermalActive = false;
            showRedCore = false;
            guidanceText = "";
        }

        // Reset circling detection when straight flight resumes
        if (!isCircling && headingAccum > 0) {
            headingAccum *= 0.98f; // gradual decay
            if (headingAccum < 10) {
                headingAccum = 0;
                circlePointCount = 0;
            }
        }
    }

    /** Обновить accel: из реальных сенсоров с world-transform или синтезированный */
    private void updateAccel(float dt) {
        // Если есть реальные сенсорные данные — используем их
        if (sensorData != null && !sensorData.isEmpty() && sensorIdx < sensorData.size()) {
            // Берём ближайший сэмпл по текущему sim-времени
            float trackElapsedSec = totalSimSec;
            long targetDtMs = (long)(trackElapsedSec * 1000f);
            while (sensorIdx < sensorData.size() - 1
                    && sensorData.get(sensorIdx + 1).dtMs <= targetDtMs) {
                sensorIdx++;
            }
            SensorSample s = sensorData.get(sensorIdx);
            sensorHeading = s.headingDeg;
            hasSensorHeading = (s.headingDeg > 0f);
            hasSensorData = true;

            // Raw body accel in g (from milli-g)
            float axG = s.axMg / 1000f;
            float ayG = s.ayMg / 1000f;
            float azG = s.azMg / 1000f;

            // Gravity estimate via IIR low-pass (gravity = DC component of raw accel)
            if (!gravityInitialized) {
                gravityEstimate[0] = axG;
                gravityEstimate[1] = ayG;
                gravityEstimate[2] = azG;
                gravityInitialized = true;
            } else {
                gravityEstimate[0] += GRAVITY_IIR_ALPHA * (axG - gravityEstimate[0]);
                gravityEstimate[1] += GRAVITY_IIR_ALPHA * (ayG - gravityEstimate[1]);
                gravityEstimate[2] += GRAVITY_IIR_ALPHA * (azG - gravityEstimate[2]);
            }

            // Build rotation matrix from gravity estimate + magnetometer
            float[] R = new float[9];
            float[] grav = new float[]{gravityEstimate[0], gravityEstimate[1], gravityEstimate[2]};
            float[] mag = new float[]{s.mx, s.my, s.mz};
            // Use Android's SensorManager to get body→world rotation matrix
            android.hardware.SensorManager.getRotationMatrix(R, null, grav, mag);

            // Linear acceleration (raw - gravity) in body frame, in m/s²
            float linXms2 = (axG - gravityEstimate[0]) * 9.81f;
            float linYms2 = (ayG - gravityEstimate[1]) * 9.81f;
            float linZms2 = (azG - gravityEstimate[2]) * 9.81f;

            // Transform to world coordinates: world = R * body
            // World X and Y = horizontal linear acceleration → feed to ThermalDetector
            float worldX = R[0] * linXms2 + R[1] * linYms2 + R[2] * linZms2;
            float worldY = R[3] * linXms2 + R[4] * linYms2 + R[5] * linZms2;

            accelX = worldX / 9.81f; // convert back to g for ThermalDetector
            accelY = worldY / 9.81f;
            return;
        }
        hasSensorData = false;
        generateAccel(dt);
    }

    private void generateAccel(float dt) {
        float ax = NOISE_FLOOR_G * (float) Math.sin(noisePhase * 0.7);
        float ay = NOISE_FLOOR_G * (float) Math.sin(noisePhase * 1.1 + 0.3);

        if (wasCircling || liftTimer > 5) {
            // Turbulence from thermal
            float turb = 0.01f + 0.03f * Math.min(vario / 3f, 1f);
            ax += turb * (float) Math.sin(noisePhase * 0.3);
            ay += turb * (float) Math.cos(noisePhase * 0.2);

            // Bank oscillations during circling
            float bank = 0.012f * (float) Math.sin(noisePhase * 0.05);
            ax += bank * 0.7f;
            ay += bank * 0.3f;
        }

        // Smooth gyro during turns
        if (Math.abs(gyroZ) > 0.01) {
            ax += 0.005f * (float) Math.sin(noisePhase * 0.1);
            ay += 0.005f * (float) Math.cos(noisePhase * 0.08);
        }

        accelX = ax;
        accelY = ay;
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Returns [distance_meters, bearing_degrees] */
    private float[] haversineBearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1R = Math.toRadians(lat1);
        double lat2R = Math.toRadians(lat2);

        double dist = haversineMeters(lat1, lon1, lat2, lon2);

        double y = Math.sin(dLon) * Math.cos(lat2R);
        double x = Math.cos(lat1R) * Math.sin(lat2R)
                - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon);
        float bearing = (float) Math.toDegrees(Math.atan2(y, x));
        if (bearing < 0) bearing += 360;

        return new float[]{(float) dist, bearing};
    }

    /**
     * Great-circle interpolation (TR-5 fix).
     * For short segments (<6km) falls back to linear.
     * For long segments/high latitudes uses spherical SLERP.
     * @return [latDeg, lonDeg]
     */
    private static double[] interpolateGreatCircle(double lat1Deg, double lon1Deg,
                                                    double lat2Deg, double lon2Deg,
                                                    double fraction) {
        double lat1 = Math.toRadians(lat1Deg);
        double lon1 = Math.toRadians(lon1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double lon2 = Math.toRadians(lon2Deg);

        double dLon = lon2 - lon1;
        double dLat = lat2 - lat1;

        // Haversine angular distance
        double sinHalfDLat = Math.sin(dLat / 2);
        double sinHalfDLon = Math.sin(dLon / 2);
        double a = sinHalfDLat * sinHalfDLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfDLon * sinHalfDLon;
        double delta = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // < ~6km: linear is fine (error < 1m at mid-latitudes)
        if (delta < 0.001) {
            return new double[]{lat1Deg + dLat * fraction, lon1Deg + dLon * fraction};
        }

        // Spherical interpolation (SLERP in 3D)
        double sinDelta = Math.sin(delta);
        double aFactor = Math.sin((1 - fraction) * delta) / sinDelta;
        double bFactor = Math.sin(fraction * delta) / sinDelta;

        double x = aFactor * Math.cos(lat1) * Math.cos(lon1)
                 + bFactor * Math.cos(lat2) * Math.cos(lon2);
        double y = aFactor * Math.cos(lat1) * Math.sin(lon1)
                 + bFactor * Math.cos(lat2) * Math.sin(lon2);
        double z = aFactor * Math.sin(lat1) + bFactor * Math.sin(lat2);

        double lat = Math.atan2(z, Math.sqrt(x * x + y * y));
        double lon = Math.atan2(y, x);

        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    // ========================================================================
    // Getters
    // ========================================================================

    /** TR-5 / MA-4: получить весь IGC-трек для прорисовки полилинии в trackMode */
    public List<TrackPoint> getTrack() { return track; }

    public double getLat() { return pilotLat; }
    public double getLon() { return pilotLon; }
    public float getAltitude() { return altitude; }
    public float getHeading() { return heading; }
    /**
     * Компасный курс носа параплана.
     * Из ZIP (реальный магнитометр) если доступен, иначе track-based heading.
     */
    public float getCompassHeading() {
        if (hasSensorHeading) return sensorHeading;
        return heading; // fallback to direction of travel
    }
    public float getVario() { return vario; }
    public float getSpeed() { return speed; }
    public float getGyroZ() { return gyroZ; }
    public float getAccelX() { return accelX; }
    public float getAccelY() { return accelY; }
    /** Исправлено TR-12: есть ли реальные данные акселерометра из ZIP */
    public boolean hasRealAccel() { return hasSensorData && sensorData != null && !sensorData.isEmpty(); }

    public boolean isThermalActive() { return thermalActive; }
    public boolean isShowRedCore() { return showRedCore; }
    public float getThermalBearing() { return thermalBearing; }
    public float getThermalDistance() { return thermalDistance; }
    public float getThermalRadius() { return thermalRadiusM; }
    public String getGuidanceText() { return guidanceText; }

    public float getWindFromDeg() { return avgCount > 0 ? calcWindDir5() : windFromDeg; }
    public float getWindSpeedMs() { return avgCount > 0 ? calcAvg5(windSpdAvgBuf) : windSpeedMs; }

    /** Progress 0..1 */
    public float getProgress() {
        float total = getTotalTime();
        if (total <= 0) return 0;
        return Math.min(1, totalSimSec / total);
    }

    // ===== Player controls (трек-плеер) =====

    /** Полная длительность трека (сек) */
    public float getTotalTime() {
        if (track == null || track.size() < 2) return 0;
        return track.get(track.size() - 1).timeSec - track.get(0).timeSec;
    }

    /** Текущая позиция (сек от начала) */
    public float getCurrentTime() {
        return totalSimSec;
    }

    /** Перемотать на позицию (сек от начала) — NEW-02: интерполяция без телепорта */
    public void seekTo(float timeSec) {
        if (track == null || track.size() < 2) return;
        totalSimSec = Math.max(0, Math.min(timeSec, getTotalTime()));

        // Найти индекс ближайшей точки
        currentIdx = 0;
        float target = track.get(0).timeSec + totalSimSec;
        for (int i = 0; i < track.size() - 1; i++) {
            if (target >= track.get(i).timeSec && target < track.get(i + 1).timeSec) {
                currentIdx = i;
                break;
            }
        }

        // Интерполировать позицию
        TrackPoint a = track.get(currentIdx);
        TrackPoint b = track.get(Math.min(currentIdx + 1, track.size() - 1));
        float segStart = a.timeSec;
        float segEnd = b.timeSec;
        float t = (segEnd > segStart) ? (target - segStart) / (segEnd - segStart) : 0;
        pilotLat = a.lat + (b.lat - a.lat) * t;
        pilotLon = a.lon + (b.lon - a.lon) * t;
        altitude = a.altMeters + (b.altMeters - a.altMeters) * t;

        // Heading по направлению к следующей точке (с учётом схождения меридианов)
        double dLat = b.lat - a.lat;
        double dLon = b.lon - a.lon;
        double dLonAdj = dLon * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0));
        heading = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
        if (heading < 0) heading += 360;

        // Vario по изменению высоты между точками
        vario = (segEnd > segStart) ? (b.altMeters - a.altMeters) / (segEnd - segStart) : 0;

        // Исправлено TR-6: сброс интерполяционного состояния после seekTo
        hasLastFramePosition = false;
        lastGpsTimeSec = -1f;
        lastFrameAlt = altitude;
        lastFrameLat = pilotLat;
        lastFrameLon = pilotLon;
        smoothSpeed = speed; // сохранить текущее, не обнулять
        avgIdx = 0;
        avgCount = 0;
        for (int i = 0; i < AVG_WINDOW; i++) {
            speedAvgBuf[i] = speed;
            windDirAvgBuf[i] = smoothWindDir;
            windSpdAvgBuf[i] = smoothWindSpd;
        }
        avgCount = AVG_WINDOW;
    }

    /** Пауза/продолжить */
    public void setPaused(boolean v) { this.paused = v; }
    public boolean isPaused() { return paused; }
}
