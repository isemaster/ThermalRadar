package com.termo1.radar.sensors;

import java.util.ArrayDeque;

/**
 * HeadingFilter — полный пайплайн сглаживания курса (heading) для отображения.
 *
 * Пайплайн:
 *   Raw heading → CLAMP выбросов (если |Δheading| > 70°/с → limit по xk)
 *     → MEDIAN FILTER (окно 3) → без аллокаций
 *     → Alpha-Beta filter (α=0.75, β=0.3) → плавная кривая с предсказанием
 *     → DEADBAND (0.5°) → не дёргать UI на микро-колебаниях
 *
 * α=0.75, β=0.3 — эмпирически подобрано для параплана
 * (частота сенсора ~50 Гц, типичная угловая скорость в спирали ~20°/с).
 */
public class HeadingFilter {

    // === CLAMP ===
    private static final float MAX_DELTA_DEG_PER_SEC = 70f;

    // === MEDIAN FILTER ===
    private static final int MEDIAN_WINDOW = 3;
    private final double[] medianBuf = new double[MEDIAN_WINDOW];
    private int medianCount;

    // === ALPHA-BETA ===
    private final double alpha;   // вес позиции
    private final double beta;    // вес скорости (turn rate)
    private double xk;            // filtered heading (в unwrapped space)
    private double vk;            // turn rate (°/с)
    private long lastTimeMs;      // время последнего обновления
    private double unwrapped;     // монотонная шкала без скачков 359→0

    // === DEADBAND ===
    private static final double DEADBAND_DEG = 0.5;
    private double lastOutputDeg;  // последнее выведенное значение (0-360)

    // === Состояние ===
    private boolean initialized;

    public HeadingFilter(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
        reset();
    }

    /** α=0.75, β=0.3 — эмпирически подобрано для параплана (50 Гц, спираль ~20°/с) */
    public HeadingFilter() {
        this(0.75, 0.3);
    }

    /**
     * Обработать сырой heading из сенсора/компаса.
     * Всегда возвращает число (никогда NaN).
     *
     * @param rawHeading сырой курс в градусах 0-360
     * @param timeMs     монотонное время (SystemClock.elapsedRealtime())
     * @return отфильтрованный heading в градусах 0-360
     */
    public double update(double rawHeading, long timeMs) {
        if (!initialized) {
            xk = rawHeading;
            unwrapped = rawHeading;
            lastOutputDeg = rawHeading;
            lastTimeMs = timeMs;
            initialized = true;
            medianCount = 0;
            return rawHeading;
        }

        long dtMs = timeMs - lastTimeMs;
        if (dtMs <= 0) dtMs = 1;
        if (dtMs > 2000) dtMs = 2000;
        double dt = dtMs / 1000.0;
        lastTimeMs = timeMs;

        // ============================================================
        // 1. CLAMP — ограничение по xk (внутреннее состояние α-β), а не по lastOutputDeg
        // ============================================================
        double clamped = rawHeading;
        double diff = rawHeading - xk;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        double maxDelta = MAX_DELTA_DEG_PER_SEC * dt;
        if (Math.abs(diff) > maxDelta) {
            clamped = xk + Math.signum(diff) * maxDelta;
            clamped = (clamped % 360 + 360) % 360;
        }

        // ============================================================
        // 2. MEDIAN FILTER — без аллокаций (double[3], итератор вручную)
        // ============================================================
        if (medianCount < MEDIAN_WINDOW) {
            medianBuf[medianCount++] = clamped;
        } else {
            // Сдвиг — без аллокаций
            medianBuf[0] = medianBuf[1];
            medianBuf[1] = medianBuf[2];
            medianBuf[2] = clamped;
        }
        double median = (medianCount >= MEDIAN_WINDOW) ? computeMedian() : clamped;

        // ============================================================
        // 3. ALPHA-BETA FILTER (с unwrap)
        // ============================================================

        // Unwrap
        double uDiff = median - unwrapped;
        while (uDiff > 180) uDiff -= 360;
        while (uDiff < -180) uDiff += 360;
        unwrapped += uDiff;

        // Predict
        double xkPred = xk + vk * dt;
        double vkPred = vk;

        // Innovation
        double innovation = unwrapped - xkPred;

        // Update
        xk = xkPred + alpha * innovation;
        vk = vkPred + beta * innovation / dt;

        // Wrap в 0-360
        double outputDeg = (xk % 360 + 360) % 360;

        // ============================================================
        // 4. DEADBAND — всегда возвращаем число (никакого NaN)
        // ============================================================
        double displayDiff = Math.abs(outputDeg - lastOutputDeg);
        if (displayDiff > 180) displayDiff = 360 - displayDiff;

        if (displayDiff < DEADBAND_DEG) {
            return lastOutputDeg; // возвращаем предыдущее показанное
        }

        lastOutputDeg = outputDeg;
        return outputDeg;
    }

    /** Быстрая медиана для окна 3 — без аллокаций */
    private double computeMedian() {
        double a = medianBuf[0], b = medianBuf[1], c = medianBuf[2];
        if ((a >= b && a <= c) || (a >= c && a <= b)) return a;
        if ((b >= a && b <= c) || (b >= c && b <= a)) return b;
        return c;
    }

    public double getTurnRate() {
        return vk;
    }

    public void reset() {
        xk = 0;
        vk = 0;
        unwrapped = 0;
        lastTimeMs = 0;
        initialized = false;
        lastOutputDeg = 0;
        medianCount = 0;
    }
}
