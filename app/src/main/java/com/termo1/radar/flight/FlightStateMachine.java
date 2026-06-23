package com.termo1.radar.flight;

import android.util.Log;

/**
 * FlightStateMachine — детекция начала и окончания полёта.
 *
 * Комбинированный подход (свой + XCSoar):
 *   1. Altitude-based: взлёт +10м за 10с, посадка ±2м за 5мин (существующий)
 *   2. Speed-based (XCSoar): скорость >5 м/с 10с → flying, <2.5 м/с 30с → landed
 *
 * Состояния:
 *   ON_GROUND → FLYING → FINISHED
 *
 * Сигнализирует LogManager через коллбэк.
 */
public class FlightStateMachine {

    private static final String TAG = "TERMO1_FLIGHT";

    // ========================================================================
    // Altitude-based detection (существующий)
    // ========================================================================
    private static final float START_ALTITUDE_DELTA = 10.0f;
    private static final long START_WINDOW_MS = 10_000;
    private static final float STOP_ALTITUDE_DELTA = 2.0f;
    private static final long STOP_WINDOW_MS = 5 * 60_000;

    // ========================================================================
    // Speed-based detection (из XCSoar FlyingComputer)
    // ========================================================================
    /** Скорость для детекции взлёта (м/с) */
    private static final float TAKEOFF_SPEED_MS = 5.0f;
    /** Половина TAKEOFF_SPEED — порог для посадки (м/с) */
    private static final float LANDING_SPEED_MS = TAKEOFF_SPEED_MS / 2.0f;
    /** Время непрерывного движения для подтверждения взлёта (с) */
    private static final long TAKEOFF_CONFIRM_MS = 10_000;  // 10 секунд
    /** Время непрерывной неподвижности для подтверждения посадки (с) */
    private static final long LANDING_CONFIRM_MS = 30_000;  // 30 секунд

    // ========================================================================
    // Состояния
    // ========================================================================
    public static final int STATE_ON_GROUND = 0;
    public static final int STATE_FLYING = 1;
    public static final int STATE_FINISHED = 2;

    private int state = STATE_ON_GROUND;

    // ========================================================================
    // Altitude history (кольцевой буфер)
    // ========================================================================
    private static final int ALT_HISTORY_SIZE = 300;
    private final float[] altitudeHistory = new float[ALT_HISTORY_SIZE];
    private final long[] altitudeTimes = new long[ALT_HISTORY_SIZE];
    private int histHead;
    private int histFill;
    private long lastCheckMs;

    // ========================================================================
    // Speed-based detection state (как XCSoar: moving/stationary clocks)
    // ========================================================================
    private long movingSinceMs;
    private double movingLat, movingLon;
    private float movingAlt;
    private boolean movingClockActive;
    private long stationarySinceMs;
    private boolean stationaryClockActive;

    // ========================================================================
    // Callback
    // ========================================================================
    public interface FlightStateListener {
        void onFlightStarted();
        void onFlightFinished();
    }

    private FlightStateListener listener;

    public void setListener(FlightStateListener listener) {
        this.listener = listener;
    }

    // ========================================================================
    // Update — вызывать раз в секунду
    // ========================================================================

    public void update(float altitudeMsl, long nowMs) {
        if (state == STATE_FINISHED) {
            state = STATE_ON_GROUND;
            lastCheckMs = 0;
        }

        // Сохраняем в историю (для altitude-based)
        altitudeHistory[histHead] = altitudeMsl;
        altitudeTimes[histHead] = nowMs;
        histHead = (histHead + 1) % ALT_HISTORY_SIZE;
        if (histFill < ALT_HISTORY_SIZE) histFill++;

        // Проверка раз в секунду
        if (nowMs - lastCheckMs < 1000) return;
        lastCheckMs = nowMs;

        switch (state) {
            case STATE_ON_GROUND:
                checkStart(nowMs);
                break;
            case STATE_FLYING:
                checkStop(nowMs);
                break;
        }
    }

    /**
     * Speed-based update. Вызывать на каждом сэмпле GPS (1 Гц).
     * Дополняет altitude-based детекцию.
     *
     * @param gpsSpeed  скорость по GPS (м/с)
     * @param gpsLat    широта
     * @param gpsLon    долгота
     * @param altitude  высота MSL (м)
     * @param nowMs     время
     */
    public void updateSpeedBased(float gpsSpeed, double gpsLat, double gpsLon,
                                 float altitude, long nowMs) {
        if (state == STATE_FINISHED) return;

        if (gpsSpeed >= TAKEOFF_SPEED_MS) {
            // Moving — как XCSoar: увеличиваем moving_clock, сбрасываем stationary
            if (!movingClockActive) {
                movingSinceMs = nowMs;
                movingLat = gpsLat;
                movingLon = gpsLon;
                movingAlt = altitude;
                movingClockActive = true;
            }
            stationaryClockActive = false;
            stationarySinceMs = 0;

            // Если не летим, но движемся 10с → взлёт
            if (state == STATE_ON_GROUND && movingClockActive
                    && (nowMs - movingSinceMs) >= TAKEOFF_CONFIRM_MS) {
                Log.i(TAG, "Flight START (speed-based): speed=" + gpsSpeed
                        + " m/s for " + TAKEOFF_CONFIRM_MS / 1000 + "s");
                state = STATE_FLYING;
                if (listener != null) listener.onFlightStarted();
            }

        } else if (gpsSpeed < LANDING_SPEED_MS && gpsSpeed >= 0) {
            // Stationary — как XCSoar: уменьшаем moving_clock, увеличиваем stationary
            if (movingClockActive && (nowMs - movingSinceMs) >= 2000) {
                // Если движемся >2с, начинаем slowly убывать (как XCSoar subtract)
                // Упрощённо: просто сбрасываем moving после 30с stationary
            }

            if (!stationaryClockActive) {
                stationarySinceMs = nowMs;
                stationaryClockActive = true;
            }

            // Если летим, но стоим 30с → посадка
            if (state == STATE_FLYING && stationaryClockActive
                    && (nowMs - stationarySinceMs) >= LANDING_CONFIRM_MS) {
                Log.i(TAG, "Flight FINISH (speed-based): speed=" + gpsSpeed
                        + " m/s for " + LANDING_CONFIRM_MS / 1000 + "s");
                state = STATE_FINISHED;
                if (listener != null) listener.onFlightFinished();
            }

        } else {
            // Между порогами — неопределённо, таймеры сбрасываются
            movingClockActive = false;
            stationaryClockActive = false;
        }
    }

    // ========================================================================
    // Altitude-based checks
    // ========================================================================

    private void checkStart(long nowMs) {
        if (histFill < 2) return;

        float altNow = altitudeHistory[(histHead - 1 + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE];
        long cutoff = nowMs - START_WINDOW_MS;

        for (int i = 0; i < histFill; i++) {
            int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
            if (altitudeTimes[idx] <= cutoff) {
                float delta = altNow - altitudeHistory[idx];
                if (delta >= START_ALTITUDE_DELTA) {
                    Log.i(TAG, "Flight START (altitude): +" + delta
                            + "m in " + (nowMs - altitudeTimes[idx]) / 1000 + "s");
                    state = STATE_FLYING;
                    if (listener != null) listener.onFlightStarted();
                }
                return;
            }
        }
    }

    private void checkStop(long nowMs) {
        if (histFill < 2) return;

        float altNow = altitudeHistory[(histHead - 1 + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE];
        long cutoff = nowMs - STOP_WINDOW_MS;

        float minAlt = altNow;
        float maxAlt = altNow;

        for (int i = 0; i < histFill; i++) {
            int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
            if (altitudeTimes[idx] < cutoff) break;
            float a = altitudeHistory[idx];
            if (a < minAlt) minAlt = a;
            if (a > maxAlt) maxAlt = a;
        }

        float delta = maxAlt - minAlt;
        if (delta <= STOP_ALTITUDE_DELTA * 2) {
            boolean fullWindow = true;
            for (int i = 0; i < histFill; i++) {
                int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
                if (altitudeTimes[idx] <= cutoff && altitudeTimes[idx] > 0) break;
                if (i == histFill - 1) fullWindow = false;
            }
            if (fullWindow) {
                Log.i(TAG, "Flight FINISH (altitude): delta=" + delta + "m over 5min");
                state = STATE_FINISHED;
                if (listener != null) listener.onFlightFinished();
            }
        }
    }

    // ========================================================================
    // Управление
    // ========================================================================

    public void reset() {
        state = STATE_ON_GROUND;
        histHead = 0;
        histFill = 0;
        lastCheckMs = 0;
        movingClockActive = false;
        stationaryClockActive = false;
        movingSinceMs = 0;
        stationarySinceMs = 0;
    }

    public void setStateFlying() {
        state = STATE_FLYING;
    }

    public int getState() { return state; }

    public boolean isFlying() { return state == STATE_FLYING; }

    public boolean isFinished() { return state == STATE_FINISHED; }
}
