package com.termo1.radar.core;

/**
 * FlightSimulator — полный сценарий тестового полёта на 2 минуты.
 *
 * Сценарий:
 *   0-10s:  Лебёдка на СЕВЕР, 3 м/с вперёд, 5 м/с набор → 500м
 *  10-20s:  Свободный полёт на ЮВ (135°), снос ветром 5 м/с с СЗ
 *  20-30s:  Термик-блип на 100м справа спереди, поворот к нему
 *  30-90s:  Крутка вокруг термика (Ø50м), асимметричный подъём 1-3 м/с
 *           Постепенная центровка → стабильные 3 м/с
 *  90-100s: Покидание термика, полёт на ЮВ
 *
 * Ветер: 5 м/с с СЗ (315°), сносит термик на ЮВ.
 * Координаты: +Y=N, +X=E, метры от старта.
 * Конвертация в lat/lon: BASE_LAT=55.0, BASE_LON=37.0
 */
public class FlightSimulator {

    // ========================================================================
    // Constants
    // ========================================================================
    private static final double BASE_LAT = 55.0;
    private static final double BASE_LON = 37.0;
    private static final double M_PER_DEG_LAT = 111319.0;
    private static final double M_PER_DEG_LON = 111319.0 * 0.5736; // cos(55°)

    // Wind: 5 m/s from 315° (NW) → drift SE
    private static final float WIND_FROM_DEG = 315f;
    private static final float WIND_SPEED_MS = 5f;
    // Wind vector: +X=East, +Y=North (toward 135° = SE)
    private static final double WIND_X = WIND_SPEED_MS * Math.sin(Math.toRadians(135));  // +3.54
    private static final double WIND_Y = WIND_SPEED_MS * Math.cos(Math.toRadians(135));  // -3.54

    // Phase timing (seconds)
    private static final float T_TOW_END = 10f;
    private static final float T_FREE_END = 20f;
    private static final float T_APPROACH_END = 30f;
    private static final float T_CIRCLE_END = 90f;
    private static final float SIM_END_SEC = 100f;

    // Tow
    private static final float TOW_SPEED = 3f;
    private static final float TOW_VARIO = 5f;

    // Free flight
    private static final float FREE_HEADING = 135f;  // SE
    private static final float FREE_AIRSPEED = 9f;

    // Thermal
    private static final float THERMAL_RADIUS = 25f;      // 50m diameter
    private static final float THERMAL_LIFT_CORE = 3.0f;
    private static final float THERMAL_LIFT_EDGE = 1.0f;
    private static final float THERMAL_INIT_DIST = 100f;
    private static final float THERMAL_INIT_BEARING = 45f; // right of heading

    // Circling
    private static final float CIRCLE_RADIUS = 25f;
    private static final float CIRCLE_SPEED = 9f;
    // Circumference = 157m, period = 17.45s
    private static final float CIRCLE_PERIOD_SEC = 17.45f;
    private static final float CIRCLE_RATE_RAD_S = (float)(2 * Math.PI / CIRCLE_PERIOD_SEC);

    // Noise
    private static final float NOISE_FLOOR_G = 0.003f;

    // ========================================================================
    // State
    // ========================================================================
    private boolean running;
    private long elapsedMs;
    private float tSec;                      // current time in seconds

    // Pilot (meters, +Y=N, +X=E)
    private double pilotX, pilotY;
    private float heading;                   // degrees
    private float vario;                     // m/s
    private float speed;                     // m/s ground speed
    private float altMsl;                    // m
    private float gyroZ;                     // rad/s
    private float accelX, accelY;            // g

    // GPS
    private double gpsLat, gpsLon;

    // Thermal
    private double thermalX, thermalY;       // center (meters)
    private boolean thermalVisible;
    private float thermalBearing;            // from pilot
    private float thermalDistance;
    private long thermalBornMs;

    // Circling
    private boolean isCircling;
    private float circleAngle;               // rad, around thermal
    private float liftAtPilot;
    private boolean showRedCore;
    private String guidanceText;

    // Centering progress
    private float centeringProgress;
    private float currentOffset;

    // Noise phase
    private double noisePhase;

    // ========================================================================
    // API
    // ========================================================================

    public void start() {
        running = true;
        elapsedMs = 0;
        tSec = 0;
        noisePhase = 0;

        pilotX = 0; pilotY = 0;
        heading = 0f;
        vario = TOW_VARIO;
        speed = TOW_SPEED;
        altMsl = 0f;
        gyroZ = 0f;
        accelX = 0f; accelY = 0f;

        gpsLat = BASE_LAT; gpsLon = BASE_LON;

        thermalX = 0; thermalY = 0;
        thermalVisible = false;
        thermalBearing = 0;
        thermalDistance = 0;
        thermalBornMs = 0;

        isCircling = false;
        circleAngle = 0;
        liftAtPilot = 0;
        showRedCore = false;
        guidanceText = "";
        centeringProgress = 0;
        currentOffset = 0;
    }

    public void stop() { running = false; }
    public boolean isRunning() { return running; }

    public void update(long totalElapsedMs) {
        if (!running) return;
        long prevMs = elapsedMs;
        elapsedMs = totalElapsedMs;
        if (prevMs <= 0) { tSec = 0; return; }

        tSec = elapsedMs / 1000f;
        if (tSec >= SIM_END_SEC) { running = false; return; }

        float dt = Math.min((elapsedMs - prevMs) / 1000f, 0.05f);
        if (dt <= 0) return;

        noisePhase += dt * 50;

        updatePhase(dt);
        updatePosition(dt);
        updateAltitude(dt);
        updateThermal(dt);
        updateGps();
        updateAccel(dt);
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public float getHeading() { return heading; }
    public float getVario() { return vario; }
    public float getSpeed() { return speed; }
    public float getAltitudeMsl() { return altMsl; }
    public float getGyroZ() { return gyroZ; }
    public float getAccelX() { return accelX; }
    public float getAccelY() { return accelY; }
    public double getLat() { return gpsLat; }
    public double getLon() { return gpsLon; }
    public long getElapsedMs() { return elapsedMs; }

    public boolean isThermalVisible() { return thermalVisible; }
    public float getThermalBearing() { return thermalBearing; }
    public float getThermalDistance() { return thermalDistance; }
    public long getThermalBornMs() { return thermalBornMs; }

    public boolean isCircling() { return isCircling; }
    public float getLiftAtPilot() { return liftAtPilot; }
    public boolean isShowRedCore() { return showRedCore; }

    /** Pilot X in meters from start (+X=East) */
    public double getPilotX() { return pilotX; }
    /** Pilot Y in meters from start (+Y=North) */
    public double getPilotY() { return pilotY; }
    public double getThermalX() { return thermalX; }
    public double getThermalY() { return thermalY; }
    public float getThermalRadius() { return THERMAL_RADIUS; }
    public float getWindFromDeg() { return WIND_FROM_DEG; }
    public float getWindSpeedMs() { return WIND_SPEED_MS; }
    public String getGuidanceText() { return guidanceText; }

    // ========================================================================
    // Phase logic (heading, vario, speed, gyroZ)
    // ========================================================================

    private void updatePhase(float dt) {
        float t = tSec;

        if (t < T_TOW_END) {
            heading = 0f;
            vario = TOW_VARIO;
            speed = TOW_SPEED;
            gyroZ = 0f;
            isCircling = false;
            showRedCore = false;
            guidanceText = "Взлёт на лебёдке...";

        } else if (t < T_FREE_END) {
            heading = FREE_HEADING;
            float p = (t - T_TOW_END) / (T_FREE_END - T_TOW_END);
            vario = -0.5f + 0.8f * p; // transition to slight sink
            double wAngle = Math.toRadians(FREE_HEADING - (WIND_FROM_DEG + 180) % 360);
            speed = (float) Math.sqrt(FREE_AIRSPEED*FREE_AIRSPEED + WIND_SPEED_MS*WIND_SPEED_MS
                    + 2*FREE_AIRSPEED*WIND_SPEED_MS*Math.cos(wAngle));
            gyroZ = 0f;
            isCircling = false;
            showRedCore = false;
            guidanceText = "Поиск термиков...";

            // Thermal appears at 18s
            if (t >= 18f && !thermalVisible) {
                thermalVisible = true;
                double dirRad = Math.toRadians(heading + THERMAL_INIT_BEARING);
                thermalX = pilotX + THERMAL_INIT_DIST * Math.sin(dirRad);
                thermalY = pilotY + THERMAL_INIT_DIST * Math.cos(dirRad);
                thermalBornMs = elapsedMs;
            }

        } else if (t < T_APPROACH_END) {
            updateThermalRelative();
            isCircling = false;
            showRedCore = false;

            // Turn toward thermal
            float target = thermalBearing;
            float diff = target - heading;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            float turnRate = Math.signum(diff) * Math.min(Math.abs(diff), 25f * dt);
            heading += turnRate;
            if (heading < 0) heading += 360;
            if (heading >= 360) heading -= 360;

            speed = FREE_AIRSPEED;
            vario = 0.5f + 0.3f * (t - T_FREE_END) / (T_APPROACH_END - T_FREE_END);
            gyroZ = (float) Math.toRadians(turnRate / dt);
            guidanceText = "Термик! distance=" + (int)thermalDistance + "м";

        } else if (t < T_CIRCLE_END) {
            isCircling = true;
            showRedCore = true;

            float circTime = t - T_APPROACH_END;
            centeringProgress = Math.min(circTime / 25f, 1f);

            // Offset: 20m initially → ~2m after centering
            if (centeringProgress < 0.3f) {
                currentOffset = 18f * (1f - centeringProgress / 0.3f * 0.4f);
                guidanceText = "Сместись к ядру! Подъём усилится";
            } else if (centeringProgress < 0.6f) {
                currentOffset = 10.8f * (1f - (centeringProgress - 0.3f) / 0.3f * 0.5f);
                guidanceText = "Хорошо, ближе к центру!";
            } else {
                currentOffset = 5.4f * (1f - (centeringProgress - 0.6f) / 0.4f);
                if (currentOffset < 1.5f) currentOffset = 1.5f;
                guidanceText = "Отлично! 3 м/с стабильно";
            }

            // Circle angle advances
            circleAngle += CIRCLE_RATE_RAD_S * dt;
            if (circleAngle > (float)(2 * Math.PI)) circleAngle -= (float)(2 * Math.PI);

            // Heading follows the circle tangent (right turn = decreasing heading)
            heading = (float) Math.toDegrees(
                    Math.atan2(Math.cos(circleAngle), -Math.sin(circleAngle))
            ) + 90f;
            while (heading < 0) heading += 360;
            while (heading >= 360) heading -= 360;

            speed = CIRCLE_SPEED;
            gyroZ = CIRCLE_RATE_RAD_S;

        } else {
            // Exit phase
            isCircling = false;
            showRedCore = false;
            float exTime = t - T_CIRCLE_END;
            float exDur = SIM_END_SEC - T_CIRCLE_END;
            float p = exTime / exDur;

            heading = FREE_HEADING;
            speed = FREE_AIRSPEED * (1f - 0.2f * p);
            vario = 0.5f * (1f - p);
            gyroZ = 0f;
            guidanceText = "Покидаю термик...";
        }
    }

    // ========================================================================
    // Position update
    // ========================================================================

    private void updatePosition(float dt) {
        float t = tSec;

        if (t < T_TOW_END) {
            // Tow north with slight wind drift
            double hRad = Math.toRadians(heading);
            pilotX += speed * dt * Math.sin(hRad) + WIND_X * 0.3 * dt;
            pilotY += speed * dt * Math.cos(hRad) + WIND_Y * 0.3 * dt;

        } else if (t < T_FREE_END) {
            // Free flight SE with full wind drift
            double hRad = Math.toRadians(heading);
            pilotX += FREE_AIRSPEED * dt * Math.sin(hRad) + WIND_X * dt;
            pilotY += FREE_AIRSPEED * dt * Math.cos(hRad) + WIND_Y * dt;

        } else if (t < T_APPROACH_END) {
            // Approach: heading + speed, thermal wind drift handled in updateThermal
            double hRad = Math.toRadians(heading);
            pilotX += speed * dt * Math.sin(hRad);
            pilotY += speed * dt * Math.cos(hRad);

        } else if (t < T_CIRCLE_END) {
            // Circle around thermal center + wind drift
            // Thermal center drifts with wind
            double cX = thermalX + CIRCLE_RADIUS * Math.sin(circleAngle);
            double cY = thermalY + CIRCLE_RADIUS * Math.cos(circleAngle);
            // Add offset (pilot isn't perfectly centered)
            pilotX = cX + currentOffset * Math.sin(circleAngle + Math.PI / 2);
            pilotY = cY + currentOffset * Math.cos(circleAngle + Math.PI / 2);

        } else {
            // Exit SE
            double hRad = Math.toRadians(heading);
            pilotX += speed * dt * Math.sin(hRad) + WIND_X * dt;
            pilotY += speed * dt * Math.cos(hRad) + WIND_Y * dt;
        }
    }

    private void updateAltitude(float dt) {
        altMsl += vario * dt;
        if (altMsl < 0) altMsl = 0;
    }

    // ========================================================================
    // Thermal
    // ========================================================================

    private void updateThermal(float dt) {
        if (!thermalVisible) return;

        // Thermal drifts with wind toward SE
        thermalX += WIND_X * dt;
        thermalY += WIND_Y * dt;

        if (isCircling) {
            // Compute lift at pilot position
            double dx = pilotX - thermalX;
            double dy = pilotY - thermalY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 1) dist = 1;

            float liftRatio = (float) Math.max(0, Math.min(1,
                    1.0 - (dist / THERMAL_RADIUS)
            ));
            liftAtPilot = THERMAL_LIFT_EDGE + (THERMAL_LIFT_CORE - THERMAL_LIFT_EDGE) * liftRatio;

            // Small oscillation
            float osc = 0.15f * (float) Math.sin(circleAngle * 2 + noisePhase * 0.1);
            osc *= centeringProgress; // more oscillation early on
            liftAtPilot = Math.max(THERMAL_LIFT_EDGE, Math.min(THERMAL_LIFT_CORE, liftAtPilot + osc));

            vario = liftAtPilot;
        }

        updateThermalRelative();
    }

    private void updateThermalRelative() {
        if (!thermalVisible) return;
        double dx = thermalX - pilotX;
        double dy = thermalY - pilotY;
        thermalDistance = (float) Math.sqrt(dx * dx + dy * dy);
        thermalBearing = (float) Math.toDegrees(Math.atan2(dx, dy));
        if (thermalBearing < 0) thermalBearing += 360;
    }

    // ========================================================================
    // GPS
    // ========================================================================

    private void updateGps() {
        gpsLat = BASE_LAT + pilotY / M_PER_DEG_LAT;
        gpsLon = BASE_LON + pilotX / M_PER_DEG_LON;
    }

    // ========================================================================
    // Accel (for thermal detector)
    // ========================================================================

    private void updateAccel(float dt) {
        float ax = NOISE_FLOOR_G * (float) Math.sin(noisePhase * 0.7);
        float ay = NOISE_FLOOR_G * (float) Math.sin(noisePhase * 1.1 + 0.3);

        if (isCircling) {
            float bank = 0.015f * (float) Math.sin(circleAngle * 2);
            ax += bank * 0.7f;
            ay += bank * 0.3f;
            float turb = 0.01f + 0.03f * (THERMAL_LIFT_CORE - liftAtPilot);
            ax += turb * (float) Math.sin(circleAngle * 3 + noisePhase * 0.1);
            ay += turb * (float) Math.cos(circleAngle * 3 + noisePhase * 0.07f);
        }

        if (tSec >= T_FREE_END && tSec < T_APPROACH_END) {
            ax += 0.008f * (float) Math.sin(noisePhase * 2);
            ay += 0.008f * (float) Math.cos(noisePhase * 1.5);
        }

        accelX = ax;
        accelY = ay;
    }
}
