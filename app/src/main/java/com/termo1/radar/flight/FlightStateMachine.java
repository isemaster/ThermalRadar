package com.termo1.radar.flight;

import android.util.Log;

/**
 * FlightStateMachine — детекция начала и окончания полёта по высоте.
 *
 * Автостарт: высота увеличилась на 10 м за 10 секунд.
 * Автостоп: высота не меняется (±2 м) в течение 5 минут.
 *
 * Состояния:
 * - ON_GROUND — на земле, полёт не начат
 * - FLYING — в полёте
 * - FINISHED — полёт завершён
 *
 * Сигнализирует LogManager через коллбэк.
 */
public class FlightStateMachine {

    private static final String TAG = "TERMO1_FLIGHT";

    // ========================================================================
    // Константы
    // ========================================================================

    /** Автостарт: изменение высоты на 10 м за это время */
    private static final float START_ALTITUDE_DELTA = 10.0f;  // метров
    private static final long START_WINDOW_MS = 10_000;       // 10 секунд

    /** Автостоп: высота не меняется больше чем на ±2 м за 5 мин */
    private static final float STOP_ALTITUDE_DELTA = 2.0f;
    private static final long STOP_WINDOW_MS = 5 * 60_000;   // 5 минут

    /** Интервал проверки */
    private static final long CHECK_INTERVAL_MS = 1_000;     // раз в секунду

    // ========================================================================
    // Состояния
    // ========================================================================

    public static final int STATE_ON_GROUND = 0;
    public static final int STATE_FLYING = 1;
    public static final int STATE_FINISHED = 2;

    private int state = STATE_ON_GROUND;

    // ========================================================================
    // Кольцевой буфер высоты (10 секунд для старта, 5 минут для стопа)
    // ========================================================================

    private static final int ALT_HISTORY_SIZE = 300; // 5 мин × 1 с/точка
    private final float[] altitudeHistory = new float[ALT_HISTORY_SIZE];
    private final long[] altitudeTimes = new long[ALT_HISTORY_SIZE];
    private int histHead;
    private int histFill;

    private long lastCheckMs;

    // ========================================================================
    // Коллбэк
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
    // Update — вызывать раз в секунду из processSample
    // ========================================================================

    /**
     * Обновить состояние автомата.
     * @param altitudeMsl текущая высота MSL в метрах
     * @param nowMs текущее время
     */
    public void update(float altitudeMsl, long nowMs) {
        if (state == STATE_FINISHED) {
            // Из FINISHED можно снова взлететь — переходим в ON_GROUND и проверяем старт
            state = STATE_ON_GROUND;
            lastCheckMs = 0;
            // не return — выполняем checkStart ниже
        }

        // Добавляем точку в историю
        altitudeHistory[histHead] = altitudeMsl;
        altitudeTimes[histHead] = nowMs;
        histHead = (histHead + 1) % ALT_HISTORY_SIZE;
        if (histFill < ALT_HISTORY_SIZE) histFill++;

        // Проверка раз в секунду
        if (nowMs - lastCheckMs < CHECK_INTERVAL_MS) return;
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

    private void checkStart(long nowMs) {
        if (histFill < 2) return;

        // Ищем точку 10 секунд назад
        float altNow = altitudeHistory[(histHead - 1 + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE];
        long cutoff = nowMs - START_WINDOW_MS;

        for (int i = 0; i < histFill; i++) {
            int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
            if (altitudeTimes[idx] <= cutoff) {
                float delta = altNow - altitudeHistory[idx];
                if (delta >= START_ALTITUDE_DELTA) {
                    Log.i(TAG, "Flight START detected: +" + delta + "m in " + (nowMs - altitudeTimes[idx]) / 1000 + "s");
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

        // Ищем самую старую точку в окне 5 минут
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
            long windowStart = nowMs - STOP_WINDOW_MS;
            boolean fullWindow = true;
            for (int i = 0; i < histFill; i++) {
                int idx = (histHead - 1 - i + ALT_HISTORY_SIZE) % ALT_HISTORY_SIZE;
                if (altitudeTimes[idx] <= windowStart && altitudeTimes[idx] > 0) {
                    break;
                }
                if (i == histFill - 1) {
                    fullWindow = false;
                }
            }
            if (fullWindow) {
                Log.i(TAG, "Flight FINISH detected: delta=" + delta + "m over 5min");
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
    }

    public void setStateFlying() {
        state = STATE_FLYING;
    }

    public int getState() {
        return state;
    }

    public boolean isFlying() {
        return state == STATE_FLYING;
    }

    public boolean isFinished() {
        return state == STATE_FINISHED;
    }
}
