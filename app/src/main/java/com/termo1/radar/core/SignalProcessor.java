package com.termo1.radar.core;

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
    private float hpX_x1, hpX_x2, hpX_y1, hpX_y2;  // ФВЧ, канал X
    private float lpX_x1, lpX_x2, lpX_y1, lpX_y2;  // ФНЧ, канал X
    private float hpY_x1, hpY_x2, hpY_y1, hpY_y2;  // ФВЧ, канал Y
    private float lpY_x1, lpY_x2, lpY_y1, lpY_y2;  // ФНЧ, канал Y

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
        zcTimerMs = System.currentTimeMillis();
    }

    /**
     * Biquad-секция ФНЧ.
     */
    private float lowpass(float x, float[] state) {
        // state: [x1, x2, y1, y2]
        float y = LP_B0 * x + LP_B1 * state[0] + LP_B2 * state[1]
                - LP_A1 * state[2] - LP_A2 * state[3];
        state[1] = state[0];
        state[0] = x;
        state[3] = state[2];
        state[2] = y;
        return y;
    }

    /**
     * Biquad-секция ФВЧ.
     */
    private float highpass(float x, float[] state) {
        float y = HP_B0 * x + HP_B1 * state[0] + HP_B2 * state[1]
                - HP_A1 * state[2] - HP_A2 * state[3];
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
        // ФВЧ 0.25 Гц
        float[] hpState = {hpX_x1, hpX_x2, hpX_y1, hpX_y2};
        float hpOut = highpass(x, hpState);
        hpX_x1 = hpState[0]; hpX_x2 = hpState[1]; hpX_y1 = hpState[2]; hpX_y2 = hpState[3];
        // ФНЧ 2.5 Гц
        float[] lpState = {lpX_x1, lpX_x2, lpX_y1, lpX_y2};
        float lpOut = lowpass(hpOut, lpState);
        lpX_x1 = lpState[0]; lpX_x2 = lpState[1]; lpX_y1 = lpState[2]; lpX_y2 = lpState[3];
        return lpOut;
    }

    private float bandpassY(float y) {
        float[] hpState = {hpY_x1, hpY_x2, hpY_y1, hpY_y2};
        float hpOut = highpass(y, hpState);
        hpY_x1 = hpState[0]; hpY_x2 = hpState[1]; hpY_y1 = hpState[2]; hpY_y2 = hpState[3];
        float[] lpState = {lpY_x1, lpY_x2, lpY_y1, lpY_y2};
        float lpOut = lowpass(hpOut, lpState);
        lpY_x1 = lpState[0]; lpY_x2 = lpState[1]; lpY_y1 = lpState[2]; lpY_y2 = lpState[3];
        return lpOut;
    }

    public void reset() {
        // Сброс состояния фильтров
        hpX_x1 = hpX_x2 = hpX_y1 = hpX_y2 = 0f;
        lpX_x1 = lpX_x2 = lpX_y1 = lpX_y2 = 0f;
        hpY_x1 = hpY_x2 = hpY_y1 = hpY_y2 = 0f;
        lpY_x1 = lpY_x2 = lpY_y1 = lpY_y2 = 0f;
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
        zcTimerMs = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
        if (now - zcTimerMs >= ZC_WINDOW_MS) {
            int totalZc = zcCountX + zcCountY;
            // Каждое пересечение = половина периода. Частота = crossings / 2 / время(с)
            float measuredFreq = (totalZc / 2f) / ((now - zcTimerMs) / 1000f);
            // Ограничиваем полосой фильтра
            if (measuredFreq < 0.25f) measuredFreq = 0.25f;
            if (measuredFreq > 2.5f) measuredFreq = 2.5f;
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
        if (absBp < noiseFloor * 2.5f) {
            noiseFloor += (absBp - noiseFloor) * 0.0005f; // τ ≈ 40 с при 50 Гц
            if (noiseFloor < 0.0001f) noiseFloor = 0.0001f; // нижняя граница
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
            if (turbulenceLevel > 0.001f) {
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
        float factor = 1f - (dominantFreq - 0.5f) / 2.0f * 0.5f;
        return Math.max(0.5f, Math.min(1f, factor));
    }

    public float getStableDirDeg() {
        return (float) Math.toDegrees(turbulenceDir);
    }
}
