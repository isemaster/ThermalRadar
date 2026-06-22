package com.termo1.radar.core;

import android.os.SystemClock;

/**
 * SignalProcessor — детекция микрораскачки параплана.
 *
 * Алгоритм:
 * 1. Полосовой фильтр Баттерворта 2-го порядка (0.25–2.5 Гц)
 *    — убирает дрейф датчика, медленные повороты пилота, шаги, шум двигателя
 * 2. RMS за скользящее окно 64 отсчёта (~1.3 с при 50 Гц)
 * 3. TurbulenceLevel = sqrt(RMS_X² + RMS_Y²)
 * 4. Zero-crossing detection для определения доминирующей частоты
 *
 * Пороги калибруются на первых 100 сэмплах.
 */
public class SignalProcessor {

    // ========================================================================
    // Полосовой фильтр Баттерворта 2-го порядка (0.25–2.5 Гц при 50 Гц)
    // Каскад: ФВЧ 0.25 Гц → ФНЧ 2.5 Гц, Q = 1/√2
    // ========================================================================

    // ФНЧ 2.5 Гц — нормированные коэффициенты
    private static final float LP_B0 = 0.02007f;
    private static final float LP_B1 = 0.04014f;
    private static final float LP_B2 = 0.02007f;
    private static final float LP_A1 = -1.5613f;
    private static final float LP_A2 =  0.6415f;

    // ФВЧ 0.25 Гц — нормированные коэффициенты
    private static final float HP_B0 = 0.9780f;
    private static final float HP_B1 = -1.9560f;
    private static final float HP_B2 = 0.9780f;
    private static final float HP_A1 = -1.9555f;
    private static final float HP_A2 =  0.9565f;

    // Состояние фильтров (X и Y каналы, каждая секция)
    // [x1, x2, y1, y2] — packed в float[4] для передачи в biquad
    private final float[] hpX_state = new float[4];
    private final float[] lpX_state = new float[4];
    private final float[] hpY_state = new float[4];
    private final float[] lpY_state = new float[4];

    // === RMS (скользящее окно) ===
    private static final int WINDOW = 64; // ~1.3 с при 50 Гц
    private final float[] bufX = new float[WINDOW];
    private final float[] bufY = new float[WINDOW];
    private int idx = 0;
    private int fill = 0;

    private float rmsX, rmsY;
    private float meanX, meanY;          // средние за окно (для направления)
    private float turbulenceLevel;       // sqrt(rmsX² + rmsY²)
    private float turbulenceDir;         // atan2(meanY, meanX)

    // === Адаптивный шумовой порог ===
    private float noiseFloor = 0.002f;   // g, начальное значение
    private int calibCount = 0;
    private static final int CALIB_SAMPLES = 100;

    // === Фильтр/частотные константы ===
    private static final float BP_MIN_FREQ = 0.25f;        // Гц, нижняя граница полосового фильтра
    private static final float BP_MAX_FREQ = 2.5f;         // Гц, верхняя граница полосового фильтра
    private static final float TURBULENCE_DIR_THRESHOLD = 0.001f; // g, мин. уровень для расчёта направления
    private static final float NOISE_FLOOR_MIN = 0.0001f;  // g, нижняя граница шумового порога
    private static final float NOISE_TRACK_MULTIPLIER = 2.5f;  // множитель noiseFloor для отслеживания шума
    private static final float NOISE_TRACK_ALPHA = 0.0005f;     // скорость адаптации шума (τ ≈ 40 с при 50 Гц)
    private static final float FREQ_CENTER = 0.5f;          // Гц, центральная частота для size factor
    private static final float FREQ_RANGE = 2.0f;           // Гц, диапазон от центра до макс
    private static final float FREQ_SIZE_SCALE = 0.5f;      // диапазон size factor [0.5..1.0]

    // === Zero-crossing детектор частоты ===
    private float prevBpX = 0f;          // предыдущее значение bandpass X (для ZC)
    private float prevBpY = 0f;          // предыдущее значение bandpass Y (для ZC)
    private int zcCountX = 0;            // crossing-счётчик X
    private int zcCountY = 0;            // crossing-счётчик Y
    private long zcTimerMs;              // таймер обновления частоты
    private float dominantFreq = 1.0f;   // последняя измеренная частота (Гц)
    private static final long ZC_WINDOW_MS = 1000; // окно подсчёта 1 секунда

    // === Выходные значения ===
    private float bpOutX, bpOutY;        // bandpass output (для доступа извне)

    public SignalProcessor() {
        reset();
        zcTimerMs = SystemClock.elapsedRealtime();
    }

    /**
     * Biquad-секция ФНЧ с anti-windup (clamp выхода).
     */
    private float lowpass(float x, float[] state) {
        // state: [x1, x2, y1, y2]
        float y = LP_B0 * x + LP_B1 * state[0] + LP_B2 * state[1]
                - LP_A1 * state[2] - LP_A2 * state[3];
        // Anti-windup: IIR может раздуть state при DC-составляющей
        if (y >  100f) y =  100f;
        if (y < -100f) y = -100f;
        state[1] = state[0];
        state[0] = x;
        state[3] = state[2];
        state[2] = y;
        return y;
    }

    /**
     * Biquad-секция ФВЧ с anti-windup (clamp выхода).
     */
    private float highpass(float x, float[] state) {
        float y = HP_B0 * x + HP_B1 * state[0] + HP_B2 * state[1]
                - HP_A1 * state[2] - HP_A2 * state[3];
        // Anti-windup
        if (y >  100f) y =  100f;
        if (y < -100f) y = -100f;
        state[1] = state[0];
        state[0] = x;
        state[3] = state[2];
        state[2] = y;
        return y;
    }

    /**
     * Пропустить сэмпл через полосовой фильтр (ФВЧ + ФНЧ).
     */
    private float bandpassX(float x) {
        float hpOut = highpass(x, hpX_state);
        return lowpass(hpOut, lpX_state);
    }

    private float bandpassY(float y) {
        float hpOut = highpass(y, hpY_state);
        return lowpass(hpOut, lpY_state);
    }

    public void reset() {
        // Сброс состояния фильтров
        for (int i = 0; i < 4; i++) {
            hpX_state[i] = lpX_state[i] = hpY_state[i] = lpY_state[i] = 0f;
        }
        // Сброс RMS
        idx = 0;
        fill = 0;
        rmsX = rmsY = 0f;
        meanX = meanY = 0f;
        turbulenceLevel = 0f;
        turbulenceDir = 0f;
        noiseFloor = 0.002f;
        calibCount = 0;
        // Сброс zero-crossing
        prevBpX = prevBpY = 0f;
        zcCountX = zcCountY = 0;
        dominantFreq = 1.0f;
        zcTimerMs = SystemClock.elapsedRealtime();
        bpOutX = bpOutY = 0f;
    }

    /**
     * Обработать один отсчёт. Вызывать на каждом сэмпле LINEAR_ACCELERATION.
     *
     * @param ax ускорение X (g)
     * @param ay ускорение Y (g)
     * @return turbulenceLevel (g)
     */
    public float process(float ax, float ay) {

        // ---- Полосовая фильтрация (0.25–2.5 Гц) ----
        bpOutX = bandpassX(ax);
        bpOutY = bandpassY(ay);

        // ---- Zero-crossing детектор (только X канал) ----
        if (prevBpX >= 0f && bpOutX < 0f) {
            zcCountX++;
        }
        if (prevBpY >= 0f && bpOutY < 0f) {
            zcCountY++;
        }
        prevBpX = bpOutX;
        prevBpY = bpOutY;

        // Раз в секунду обновляем частоту
        long now = SystemClock.elapsedRealtime();
        if (now - zcTimerMs >= ZC_WINDOW_MS) {
            int totalZc = zcCountX + zcCountY;
            float measuredFreq = (float) totalZc / ((now - zcTimerMs) / 1000f);
            // Ограничиваем полосой фильтра
            if (measuredFreq < BP_MIN_FREQ) measuredFreq = BP_MIN_FREQ;
            if (measuredFreq > BP_MAX_FREQ) measuredFreq = BP_MAX_FREQ;
            if (totalZc > 0) {
                dominantFreq = measuredFreq;
            }
            zcCountX = 0;
            zcCountY = 0;
            zcTimerMs = now;
        }

        // ---- Калибровка шумового порога (инкрементальное среднее) ----
        if (calibCount < CALIB_SAMPLES) {
            if (calibCount == 0) noiseFloor = 0f;
            float sample = Math.abs(bpOutX) + Math.abs(bpOutY);
            noiseFloor += (sample - noiseFloor) / (calibCount + 1);
            calibCount++;
            return 0f;
        }

        // ---- Непрерывное отслеживание шума (только когда сигнал слабый) ----
        // Если bandpass близок к шуму (< 2.5× noiseFloor) — медленно подстраиваемся
        // Чтобы термики не калибровались как шум
        float absBp = Math.abs(bpOutX) + Math.abs(bpOutY);
        if (absBp < noiseFloor * NOISE_TRACK_MULTIPLIER) {
            noiseFloor += (absBp - noiseFloor) * NOISE_TRACK_ALPHA;
            if (noiseFloor < NOISE_FLOOR_MIN) noiseFloor = NOISE_FLOOR_MIN;
        }

        // ---- RMS окно ----
        bufX[idx] = bpOutX;
        bufY[idx] = bpOutY;
        idx = (idx + 1) % WINDOW;
        if (fill < WINDOW) fill++;

        if (fill == WINDOW) {
            float sumX = 0f, sumY = 0f;
            float sumSqX = 0f, sumSqY = 0f;
            for (int i = 0; i < WINDOW; i++) {
                sumX += bufX[i];
                sumY += bufY[i];
                sumSqX += bufX[i] * bufX[i];
                sumSqY += bufY[i] * bufY[i];
            }
            meanX = sumX / WINDOW;
            meanY = sumY / WINDOW;
            float varX = sumSqX / WINDOW - meanX * meanX;
            float varY = sumSqY / WINDOW - meanY * meanY;
            if (varX < 0) varX = 0;
            if (varY < 0) varY = 0;
            rmsX = (float) Math.sqrt(varX);
            rmsY = (float) Math.sqrt(varY);

            turbulenceLevel = (float) Math.sqrt(rmsX * rmsX + rmsY * rmsY);

            // Направление: знак от среднего BP (4 квадранта), амплитуда от RMS
            // atan2(X, Y): 0°=север (ось Y), 90°=восток (ось X) — соответствует радару
            if (turbulenceLevel > TURBULENCE_DIR_THRESHOLD) {
                float dirY = (meanY >= 0) ? rmsY : -rmsY;
                float dirX = (meanX >= 0) ? rmsX : -rmsX;
                turbulenceDir = (float) Math.atan2(dirX, dirY);
            }
        }

        return turbulenceLevel;
    }

    // ====================================================================
    // ACCESSORS
    // ====================================================================

    public float getTurbulenceLevel() { return turbulenceLevel; }
    public float getTurbulenceMs2() { return turbulenceLevel * 9.81f; }
    public float getTurbulenceDir() { return turbulenceDir; }
    public float getRmsX() { return rmsX; }
    public float getRmsY() { return rmsY; }
    public float getMeanX() { return meanX; }
    public float getMeanY() { return meanY; }
    public float getBpX() { return bpOutX; }
    public float getBpY() { return bpOutY; }
    public float getNoiseFloor() { return noiseFloor; }

    public float getSnr() {
        return (noiseFloor > 0f) ? turbulenceLevel / noiseFloor : 0f;
    }

    /** Доминирующая частота в полосе 0.25–2.5 Гц (zero-crossing). */
    public float getDominantFrequency() {
        return dominantFreq;
    }

    /**
     * Фактор размера от частоты.
     * Низкая частота (0.5 Гц)  → широкий термик → 1.0 (большой шарик)
     * Высокая частота (2.5 Гц) → узкий термик → 0.5 (маленький шарик)
     */
    public float getFreqSizeFactor() {
        // 0.5 Гц → 1.0, 2.5 Гц → 0.5, линейно
        float factor = 1f - (dominantFreq - FREQ_CENTER) / FREQ_RANGE * FREQ_SIZE_SCALE;
        return Math.max(0.5f, Math.min(1f, factor));
    }

    public float getStableDirDeg() {
        return (float) Math.toDegrees(turbulenceDir);
    }
}
