package com.termo1.radar.flight;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * Thread safety: все переходы состояния защищены общим lock'ом,
 * чтобы update() и updateSpeedBased() не конфликтовали.
 */
public class FlightStateMachine {

    private static final String TAG = "TERMO1_FLIGHT";

    // ========================================================================
    // Lock для синхронизации update() и updateSpeedBased()
    // ========================================================================
    private final Object stateLock = new Object();

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
    /** Порог посадки (м/с) — гистерезис: взлёт при >5, посадка при <2.5 */
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

    private final AtomicInteger state = new AtomicInteger(STATE_ON_GROUND);

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
        synchronized (stateLock) {
            if (state.get() == STATE_FINISHED) {
                state.set(STATE_ON_GROUND);
                lastCheckMs = 0;
            }
        }

        // Сохраняем в историю (для altitude-based)
        altitudeHistory[histHead] = altitudeMsl;
        altitudeTimes[histHead] = nowMs;
        histHead = (histHead + 1) % ALT_HISTORY_SIZE;
        if (histFill < ALT_HISTORY_SIZE) histFill++;

        // Проверка раз в секунду
        if (nowMs - lastCheckMs < 1000) return;
        lastCheckMs = nowMs;

        synchronized (stateLock) {
            switch (state.get()) {
                case STATE_ON_GROUND:
                    checkStart(nowMs);
                    break;
                case STATE_FLYING:
                    checkStop(nowMs);
                    break;
            }
        }
    }

    /**
     * Speed-based update. Вызывать на каждом сэмпле GPS (1 Гц).
     * Дополняет altitude-based детекцию.
     *
     * Гистерезис скорости (BUG-09):
     *   > 5.0 м/с → взлёт (moving)
     *   < 2.5 м/с → посадка (stationary)
     *   2.5–5.0 м/с → сохранять текущее состояние (таймеры НЕ сбрасываются)
     *
     * @param gpsSpeed  скорость по GPS (м/с)
     * @param gpsLat    широта
     * @param gpsLon    долгота
     * @param altitude  высота MSL (м)
     * @param nowMs     время
     */
    public void updateSpeedBased(float gpsSpeed, double gpsLat, double gpsLon,
                                 float altitude, long nowMs) {
        synchronized (stateLock) {
            if (state.get() == STATE_FINISHED) return;

            if (gpsSpeed >= TAKEOFF_SPEED_MS) {
                // Moving — увеличиваем moving_clock, сбрасываем stationary
                if (!movingClockActive) {
                    movingSinceMs = nowMs;
                    movingClockActive = true;
                }
                stationaryClockActive = false;
                stationarySinceMs = 0;

                // Если не летим, но движемся 10с → взлёт
                if (state.get() == STATE_ON_GROUND && movingClockActive
                        && (nowMs - movingSinceMs) >= TAKEOFF_CONFIRM_MS) {
                    Log.i(TAG, "Flight START (speed-based): speed=" + gpsSpeed
                            + " m/s for " + TAKEOFF_CONFIRM_MS / 1000 + "s");
                    state.set(STATE_FLYING);
                    if (listener != null) listener.onFlightStarted();
                }

            } else if (gpsSpeed < LANDING_SPEED_MS && gpsSpeed >= 0) {
                // Stationary
                if (!stationaryClockActive) {
                    stationarySinceMs = nowMs;
                    stationaryClockActive = true;
                }
                movingClockActive = false;
                movingSinceMs = 0;

                // Если летим, но стоим 30с → посадка
                if (state.get() == STATE_FLYING && stationaryClockActive
                        && (nowMs - stationarySinceMs) >= LANDING_CONFIRM_MS) {
                    Log.i(TAG, "Flight FINISH (speed-based): speed=" + gpsSpeed
                            + " m/s for " + LANDING_CONFIRM_MS / 1000 + "s");
                    state.set(STATE_FINISHED);
                    if (listener != null) listener.onFlightFinished();
                }

            } else {
                // ГИСТЕРЕЗИС (BUG-09): 2.5–5.0 м/с — неопределённо,
                // сохраняем текущее состояние, таймеры НЕ сбрасываем
                // (счётчики сами продолжают тикать)
            }
        }
    }

    // ========================================================================
    // Altitude-based checks
    // ========================================================================

    /**
     * Вызывается ТОЛЬКО внутри synchronized(stateLock).
     */
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
                    state.set(STATE_FLYING);
                    if (listener != null) listener.onFlightStarted();
                }
                return;
            }
        }
    }

    /**
     * Вызывается ТОЛЬКО внутри synchronized(stateLock).
     * BUG-08: fullWindow = true если есть хотя бы одна точка старше cutoff.
     */
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
            // BUG-08: полное окно = есть точка старше cutoff
            boolean fullWindow = false;
            for (int i = 0; i < histFill; i++) {
                int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
                if (altitudeTimes[idx] <= cutoff && altitudeTimes[idx] > 0) {
                    fullWindow = true;
                    break;
                }
            }
            if (fullWindow) {
                Log.i(TAG, "Flight FINISH (altitude): delta=" + delta + "m over 5min");
                state.set(STATE_FINISHED);
                if (listener != null) listener.onFlightFinished();
            }
        }
    }

    // ========================================================================
    // Управление
    // ========================================================================

    public void reset() {
        synchronized (stateLock) {
            state.set(STATE_ON_GROUND);
        }
        histHead = 0;
        histFill = 0;
        lastCheckMs = 0;
        movingClockActive = false;
        stationaryClockActive = false;
        movingSinceMs = 0;
        stationarySinceMs = 0;
    }

    public void setStateFlying() {
        state.set(STATE_FLYING);
    }

    public int getState() { return state.get(); }

    public boolean isFlying() { return state.get() == STATE_FLYING; }

    public boolean isFinished() { return state.get() == STATE_FINISHED; }
}
