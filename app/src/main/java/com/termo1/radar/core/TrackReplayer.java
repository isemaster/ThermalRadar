package com.termo1.radar.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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

    // Thermal state
    private boolean thermalActive;
    private boolean showRedCore;
    private boolean hasSensorData; // true = есть ZIP с сенсорами, false = только IGC
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

    // Wind estimate from IGC (constant)
    private float windFromDeg = 315f;
    private float windSpeedMs = 5f;

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

    /** Установить наличие сенсорных данных (true = есть ZIP, false = только IGC). */
    public void setHasSensorData(boolean v) {
        this.hasSensorData = v;
    }

    /**
     * Load IGC data from a specific file path.
     * Calls loadFromIGC(InputStream) internally.
     */
    public void loadFile(String filePath) {
        try {
            loadFromIGC(new java.io.FileInputStream(filePath), false);
        } catch (Exception e) {
            e.printStackTrace();
            // Fallback to synthetic track if file load fails
            if (track == null || track.isEmpty()) {
                generateFallbackTrack();
            }
        }
    }

    /**
     * Load IGC data from the embedded resource.
     * Parses B-records from 13:17:00 to 13:40:00 device time.
     */
    public void loadFromIGC(InputStream inputStream) {
        loadFromIGC(inputStream, true);
    }

    /**
     * Load IGC data from an InputStream.
     * @param inputStream source of IGC data
     * @param applyTimeFilter если true, применяет фильтр по времени (только для built-in трека)
     */
    private void loadFromIGC(InputStream inputStream, boolean applyTimeFilter) {
        track = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) != 'B') continue;
                try {
                    if (line.length() < 30) continue;
                    String t = line.substring(1, 7);
                    int hh = Integer.parseInt(t.substring(0, 2));
                    int mm = Integer.parseInt(t.substring(2, 4));
                    int ss = Integer.parseInt(t.substring(4, 6));
                    float timeSec = hh * 3600f + mm * 60f + ss;
                    if (prevTimeSec > 0 && timeSec < prevTimeSec) timeSec += 86400f;
                    prevTimeSec = timeSec;
                    if (applyTimeFilter) {
                        if (timeSec < 13 * 3600f + 17 * 60f) continue;
                        if (timeSec > 13 * 3600f + 40 * 60f) continue;
                    }
                    float latDeg = Float.parseFloat(line.substring(7, 9));
                    float latMin = Float.parseFloat(line.substring(9, 14)) / 1000f;
                    double lat = latDeg + latMin / 60.0;
                    if (line.charAt(14) == 'S') lat = -lat;
                    float lonDeg = Float.parseFloat(line.substring(15, 18));
                    float lonMin = Float.parseFloat(line.substring(18, 23)) / 1000f;
                    double lon = lonDeg + lonMin / 60.0;
                    if (line.charAt(23) == 'W') lon = -lon;
                    int alt = 0;
                    try { alt = Integer.parseInt(line.substring(25, 30).trim()); } catch (Exception ignored) {}
                    if (alt == 0 && line.length() >= 35) {
                        try { alt = Integer.parseInt(line.substring(30, 35).trim()); } catch (Exception ignored) {}
                    }
                    track.add(new TrackPoint(lat, lon, Math.max(alt, 0), timeSec));
                } catch (Exception e) {
                    // skip bad records
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (track.isEmpty()) {
            generateFallbackTrack();
        }
    }

    private void generateFallbackTrack() {
        track = new ArrayList<>();
        // Simple synthetic track for debugging
        double baseLat = 59.4095, baseLon = 29.3037;
        for (int i = 0; i < 600; i++) {
            float t = i * 3f; // 3 sec intervals = 30 min
            double lat = baseLat + i * 0.00001 * Math.sin(i * 0.01);
            double lon = baseLon + i * 0.00002;
            float alt = 57 + 500 * (float) Math.sin(i * 0.01);
            track.add(new TrackPoint(lat, lon, alt, t));
        }
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

        float dt = realDeltaMs / 1000f;
        if (dt <= 0) return;
        if (dt > 0.05f) dt = 0.05f;

        totalRealSec += dt;
        // 2x speed
        float simDt = dt * playbackSpeed;
        totalSimSec += simDt;
        noisePhase += simDt * 50;

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

        // Interpolate position
        TrackPoint prevPoint = p0;
        TrackPoint nextPoint = p1;

        double prevLat = prevPoint.lat;
        double prevLon = prevPoint.lon;
        double nextLat = nextPoint.lat;
        double nextLon = nextPoint.lon;

        pilotLat = prevLat + (nextLat - prevLat) * frac;
        pilotLon = prevLon + (nextLon - prevLon) * frac;

        float prevAlt = prevPoint.altMeters;
        float nextAlt = nextPoint.altMeters;
        float newAlt = prevAlt + (nextAlt - prevAlt) * frac;

        // Vario from altitude change
        float altDelta = newAlt - altitude;
        vario = altDelta / Math.max(simDt, 0.01f);

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

        // Speed
        double totalDist = haversineMeters(prevLat, prevLon, pilotLat, pilotLon);
        speed = (float) (totalDist / Math.max(simDt, 0.01f));

        // === Thermal detection ===
        updateThermalState(simDt);

        // === Accel generation ===
        generateAccel(simDt);
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
            // Start of circling
            circleLatSum = (float) pilotLat;
            circleLonSum = (float) pilotLon;
            circlePointCount = 1;
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

    // ========================================================================
    // Getters
    // ========================================================================

    public double getLat() { return pilotLat; }
    public double getLon() { return pilotLon; }
    public float getAltitude() { return altitude; }
    public float getHeading() { return heading; }
    public float getVario() { return vario; }
    public float getSpeed() { return speed; }
    public float getGyroZ() { return gyroZ; }
    public float getAccelX() { return hasSensorData ? accelX : 0f; }
    public float getAccelY() { return hasSensorData ? accelY : 0f; }

    public boolean isThermalActive() { return thermalActive; }
    public boolean isShowRedCore() { return showRedCore; }
    public float getThermalBearing() { return thermalBearing; }
    public float getThermalDistance() { return thermalDistance; }
    public float getThermalRadius() { return thermalRadiusM; }
    public String getGuidanceText() { return guidanceText; }

    public float getWindFromDeg() { return windFromDeg; }
    public float getWindSpeedMs() { return windSpeedMs; }

    /** Progress 0..1 */
    public float getProgress() {
        if (track == null || track.size() < 2) return 0;
        float totalDur = track.get(track.size() - 1).timeSec - track.get(0).timeSec;
        if (totalDur <= 0) return 0;
        return Math.min(1, totalSimSec / totalDur);
    }
}
