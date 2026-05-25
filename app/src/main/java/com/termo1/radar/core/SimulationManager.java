package com.termo1.radar.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SimulationManager — генерация синтезированного сигнала акселерометра
 * с термическими "пыхами" (thermal puffs) для тестирования полного тракта
 * SignalProcessor → ThermalDetector.
 *
 * Сценарий (75 секунд):
 *   0–20s: полёт на NORTH (0°), 9 м/с
 *  20–40s: полёт на EAST (90°), 9 м/с
 *  40–50s: полный круг 360° (крен 30°, 36°/с)
 *  50–55s: поворот налево 90° (→ NORTH)
 *  55–75s: полёт прямо на NORTH, 9 м/с
 *
 * Каждые 5 секунд — новый ThermalPuff в случайной позиции вблизи траектории.
 * Чем ближе пилот к pufu — тем сильнее сигнал на частоте pufa.
 */
public class SimulationManager {

    // ========================================================================
    // Thermal puff — вихрь, создающий микрораскачку
    // ========================================================================
    public static class ThermalPuff {
        public double x, y;          // метры (compass: +Y=N, +X=E)
        public float strength;       // 0.3–1.5 (g)
        public long bornMs;          // sim elapsed ms
        public float freq;           // Гц (0.5–2.5)
        public float lifetimeMs;     // сколько живёт (мс)
    }

    // ========================================================================
    // Constants
    // ========================================================================
    private static final float NOISE_FLOOR_G = 0.003f;       // g, базовый шум
    private static final float SPEED = 9.0f;                 // m/s
    private static final float PRESSURE = 1013.25f;          // hPa
    private static final float INITIAL_ALT_MSL = 500.0f;     // m

    // Timeline
    private static final float T_NORTH_END = 20.0f;
    private static final float T_EAST_END = 40.0f;
    private static final float T_CIRCLE_END = 50.0f;        // 10s circle
    private static final float T_TURN_END = 55.0f;          // 5s turn left
    private static final float SIM_END_SEC = 75.0f;         // 55-75: straight

    // Circle: 360° in 10s = 36°/s
    private static final float CIRCLE_RATE = 360.0f / 10.0f;

    // Thermal puffs
    private static final long PUFF_INTERVAL_MS = 5000;      // каждые 5 сек
    private static final long PUFF_LIFETIME_MS = 15000;     // живут 15 сек
    private static final int MAX_PUFTS = 30;

    // ========================================================================
    // State
    // ========================================================================
    private boolean running;
    private long elapsedMs;
    private float heading;                // current heading (degrees)
    private float vario;                  // m/s
    private float altMsl;                 // m MSL
    private double pilotX, pilotY;        // meters (compass: +Y=N, +X=E)
    private long simulationRealStartMs;

    // Accelerometer output (g units)
    private float simAx, simAy;

    // Phase for noise generation
    private double noisePhase;

    // Thermal puffs
    private final List<ThermalPuff> puffs = new ArrayList<>();
    private long lastPuffMs = 0;
    private final Random random = new Random(42);  // детерминированный seed

    // ========================================================================
    // Lifecycle
    // ========================================================================

    public SimulationManager() {}

    public void start() {
        running = true;
        elapsedMs = 0;
        heading = 0f;
        vario = -0.5f;
        altMsl = INITIAL_ALT_MSL;
        pilotX = 0.0;
        pilotY = 0.0;
        simAx = 0f;
        simAy = 0f;
        noisePhase = 0.0;
        puffs.clear();
        lastPuffMs = 0;
        simulationRealStartMs = System.currentTimeMillis();
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    // ========================================================================
    // Main tick
    // ========================================================================

    public void update(long nowMs) {
        if (!running) return;

        float tSec = nowMs / 1000f;

        if (tSec >= SIM_END_SEC) {
            running = false;
            elapsedMs = nowMs;
            return;
        }

        float prevMs = elapsedMs;
        elapsedMs = nowMs;

        heading = computeHeading(tSec);
        vario = computeVario(tSec);
        updatePosition(tSec, prevMs);

        // Integrate altitude from vario
        float dtSec = (elapsedMs - prevMs) / 1000f;
        if (dtSec > 0.05f) dtSec = 0.02f; // cap
        if (dtSec > 0f) {
            altMsl += vario * dtSec;
        }

        // Generate thermal puffs every 5 seconds
        generatePuffs(nowMs);

        // Generate accelerometer signal
        generateAccel(tSec);
    }

    // ========================================================================
    // Heading timeline
    // ========================================================================

    private float computeHeading(float tSec) {
        if (tSec < T_NORTH_END) {
            return 0f;  // North
        } else if (tSec < T_EAST_END) {
            float p = (tSec - T_NORTH_END) / (T_EAST_END - T_NORTH_END);
            return 0f + p * 90f;  // 0 → 90° (turn right to East)
        } else if (tSec < T_CIRCLE_END) {
            // Full circle, 36°/s, starting from 90°
            float circTime = tSec - T_EAST_END;
            return (90f + circTime * CIRCLE_RATE) % 360f;
        } else if (tSec < T_TURN_END) {
            // Turn left from current heading back toward 0° (North)
            float turnTime = tSec - T_CIRCLE_END;
            float startH = 90f;  // after 360° circle, back at 90°
            float delta = -90f;   // turn left
            float p = turnTime / (T_TURN_END - T_CIRCLE_END);
            float sp = p * p * (3f - 2f * p); // smoothstep
            float h = startH + delta * sp;
            h = h % 360f;
            if (h < 0) h += 360f;
            return h;
        } else {
            return 0f;  // North
        }
    }

    // ========================================================================
    // Vario timeline
    // ========================================================================

    private float computeVario(float tSec) {
        if (tSec < T_NORTH_END) {
            return -0.5f + 1.5f * (tSec / T_NORTH_END); // -0.5 → +1.0
        } else if (tSec < T_EAST_END) {
            return 1.0f;  // steady climb
        } else if (tSec < T_CIRCLE_END) {
            // Circle: some lift variation
            float circTime = tSec - T_EAST_END;
            return 1.5f + 0.5f * (float)Math.sin(circTime * 1.7);
        } else if (tSec < T_TURN_END) {
            return 1.0f;
        } else {
            float exitTime = tSec - T_TURN_END;
            float exitDur = SIM_END_SEC - T_TURN_END;
            float p = exitTime / exitDur;
            return 1.0f * (1f - p);
        }
    }

    // ========================================================================
    // Position (dead-reckoning from heading + speed)
    // ========================================================================

    private void updatePosition(float tSec, float prevMs) {
        if (prevMs <= 0) {
            pilotX = 0.0;
            pilotY = 0.0;
            return;
        }
        float dt = (elapsedMs - prevMs) / 1000f;
        if (dt > 0.05f) dt = 0.02f;
        if (dt < 0.001f) return;

        double headingRad = Math.toRadians(heading);
        pilotX += SPEED * dt * Math.sin(headingRad);
        pilotY += SPEED * dt * Math.cos(headingRad);
    }

    // ========================================================================
    // Thermal puffs — случайные позиции вблизи траектории
    // ========================================================================

    private void generatePuffs(long nowMs) {
        // Чистим мёртвые
        java.util.Iterator<ThermalPuff> it = puffs.iterator();
        while (it.hasNext()) {
            ThermalPuff p = it.next();
            if (nowMs - p.bornMs > p.lifetimeMs) {
                it.remove();
            }
        }

        // Новый puff каждые 5 секунд
        if (nowMs - lastPuffMs >= PUFF_INTERVAL_MS) {
            lastPuffMs = nowMs;

            ThermalPuff puff = new ThermalPuff();
            // Случайная позиция в 50-150м от пилота, в любом направлении
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist = 50.0 + random.nextDouble() * 100.0;
            puff.x = pilotX + dist * Math.sin(angle);
            puff.y = pilotY + dist * Math.cos(angle);
            puff.strength = 0.3f + random.nextFloat() * 1.2f;   // 0.3–1.5g
            puff.freq = 0.5f + random.nextFloat() * 2.0f;       // 0.5–2.5 Гц
            puff.bornMs = nowMs;
            puff.lifetimeMs = PUFF_LIFETIME_MS;
            puffs.add(puff);

            // Не больше MAX_PUFTS
            while (puffs.size() > MAX_PUFTS) {
                puffs.remove(0);
            }
        }
    }

    // ========================================================================
    // Accelerometer signal generation
    // ========================================================================

    private void generateAccel(float tSec) {
        float ax = 0f, ay = 0f;

        // 1. Базовый шум (0.003g RMS)
        noisePhase += 0.05;
        float noiseX = NOISE_FLOOR_G * (float)Math.sin(noisePhase * 0.7 + 0.3);
        float noiseY = NOISE_FLOOR_G * (float)Math.sin(noisePhase * 1.1 + 1.7);
        ax += noiseX;
        ay += noiseY;

        // 2. Thermal puff contributions
        for (ThermalPuff puff : puffs) {
            if (elapsedMs - puff.bornMs > puff.lifetimeMs) continue;

            double dx = puff.x - pilotX;
            double dy = puff.y - pilotY;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist > 200.0) continue;

            // Сила сигнала: обратный квадрат расстояния, clipped
            double distFactor = 1.0 / (1.0 + (dist * dist) / 2500.0); // ~1 на 0м, ~0.5 на 50м, ~0.2 на 100м
            float turbLevel = puff.strength * (float)distFactor;
            turbLevel = Math.min(turbLevel, 0.5f); // cap

            // Направление от пилота к pufu
            double dirAngle = Math.atan2(dy, dx);

            // Сигнал на частоте pufa с направлением
            double w = 2.0 * Math.PI * puff.freq * (tSec - puff.bornMs / 1000.0);
            // X компонента: cos(dirAngle) * sin(w)
            ax += turbLevel * 0.7f * (float)Math.cos(dirAngle) * (float)Math.sin(w);
            // Y компонента: sin(dirAngle) * sin(w + random_phase)
            ay += turbLevel * 0.7f * (float)Math.sin(dirAngle) * (float)Math.sin(w + 1.3);
        }

        // 3. Circle — oscillation from bank
        if (tSec >= T_EAST_END && tSec < T_CIRCLE_END) {
            float circTime = tSec - T_EAST_END;
            // 30° bank → lateral 0.58g DC, AC oscillation ~0.01g
            float osc = 0.012f * (float)Math.sin(circTime * 2.0 * Math.PI * 1.5);
            // Roll oscillation mostly on X axis (phone sideways)
            ax += osc * 0.8f;
            ay += osc * 0.2f;
        }

        // 4. Turn — extra turbulence
        if (tSec >= T_CIRCLE_END && tSec < T_TURN_END) {
            float turnTime = tSec - T_CIRCLE_END;
            ax += 0.008f * (float)Math.sin(turnTime * 2.0 * Math.PI * 1.0);
            ay += 0.008f * (float)Math.sin(turnTime * 2.0 * Math.PI * 1.3 + 0.5);
        }

        simAx = ax;
        simAy = ay;
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /** Синтезированное ускорение X (g) */
    public float getAccelX() { return simAx; }

    /** Синтезированное ускорение Y (g) */
    public float getAccelY() { return simAy; }

    // ---- Accessors (совместимость) ----

    public float getHeading() { return heading; }
    public float getVario() { return vario; }
    public float getAltitudeMsl() { return altMsl; }
    public float getAltitudeAgl() { return altMsl - INITIAL_ALT_MSL; }
    public float getSpeed() { return SPEED; }
    public float getPressure() { return PRESSURE; }
    public long getElapsedMs() { return elapsedMs; }
    public long getSimulationRealStartMs() { return simulationRealStartMs; }

    /** Для обратной совместимости — приблизительный SNR */
    public float getSnr() {
        float rms = (float)Math.sqrt(simAx * simAx + simAy * simAy);
        return rms / NOISE_FLOOR_G;
    }
}
