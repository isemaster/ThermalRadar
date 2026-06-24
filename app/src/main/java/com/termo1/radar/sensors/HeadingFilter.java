package com.termo1.radar.sensors;

/**
 * HeadingFilter — полный пайплайн сглаживания курса (heading) для отображения.
 *
 * Пайплайн:
 *   Raw heading → CLAMP выбросов (если |Δheading| > 70°/с → limit по lastOutputDeg)
 *     → MEDIAN FILTER (окно 5) → без аллокаций
 *     → Alpha-Beta filter (α=0.15, β=0.02) → плавная кривая с предсказанием
 *     → DEADBAND (адаптивный: 2.5° в статике, 0.5° в движении)
 *
 * α=0.15, β=0.02 — пересчитано для параплана по ревью:
 *   - В статике σ_out ≈ σ·√(α²+(1-α)²) ≈ 0.6° (было 2° при α=0.75)
 *   - В динамике vk (turn rate) доминирует, шум незаметен
 *
 * @see <a href="https://github.com/isemaster/ThermalRadar">ThermalRadar</a>
 */
public class HeadingFilter {

    // === CLAMP ===
    private static final float MAX_DELTA_DEG_PER_SEC = 70f;

    // === MEDIAN FILTER ===
    private static final int MEDIAN_WINDOW = 5;
    private final double[] medianBuf = new double[MEDIAN_WINDOW];
    private int medianCount;

    // === ALPHA-BETA ===
    private final double alpha;   // вес позиции (0.15 — статика)
    private final double beta;    // вес скорости (0.02)
    private static final double NOMINAL_DT = 1.0 / 50.0; // 50 Гц — ожидаемая частота
    private double xk;            // filtered heading (в unwrapped space)
    private double vk;            // turn rate (°/с)
    private long lastTimeMs;      // время последнего обновления
    private double unwrapped;     // монотонная шкала без скачков 359→0

    // === DEADBAND (адаптивный) ===
    /** В статике (|vk| < 1°/с) — не дёргать UI */
    private static final double DEADBAND_STATIC_DEG = 2.5;
    /** В движении — быстрая реакция */
    private static final double DEADBAND_MOVING_DEG = 0.5;
    /** Порог turn-rate для переключения режимов */
    private static final double TURN_RATE_THRESHOLD = 1.0;
    private double lastOutputDeg;  // последнее выведенное значение (0-360)

    // === Состояние ===
    private boolean initialized;

    public HeadingFilter(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
        reset();
    }

    /** α=0.15, β=0.02 — пересчитано по ревью (было 0.75, 0.3) */
    public HeadingFilter() {
        this(0.15, 0.02);
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
        // 1. CLAMP — ограничение относительно lastOutputDeg (что видит пользователь),
        //    а не xk (внутреннее состояние α-β). Исправлено по ревью §3.2.
        // ============================================================
        double clamped = rawHeading;
        double diff = rawHeading - lastOutputDeg;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        double maxDelta = MAX_DELTA_DEG_PER_SEC * dt;
        if (Math.abs(diff) > maxDelta) {
            clamped = lastOutputDeg + Math.signum(diff) * maxDelta;
            clamped = (clamped % 360 + 360) % 360;
        }

        // ============================================================
        // 2. MEDIAN FILTER — окно 5 (было 3) для лучшего подавления шума
        // ============================================================
        if (medianCount < MEDIAN_WINDOW) {
            medianBuf[medianCount++] = clamped;
        } else {
            // Сдвиг — без аллокаций
            System.arraycopy(medianBuf, 1, medianBuf, 0, MEDIAN_WINDOW - 1);
            medianBuf[MEDIAN_WINDOW - 1] = clamped;
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
        // dt для β зафиксирован на номинале 1/50, чтобы избежать раздувания
        // vk при коротких всплесках dt (RC-6 в ревью)
        xk = xkPred + alpha * innovation;
        vk = vkPred + beta * innovation / NOMINAL_DT;

        // Wrap в 0-360
        double outputDeg = (xk % 360 + 360) % 360;

        // ============================================================
        // 4. DEADBAND — адаптивный (2.5° в статике, 0.5° в движении)
        //    Исправлено по ревью §3.5: xk подтягивается к lastOutputDeg
        // ============================================================
        double displayDiff = Math.abs(outputDeg - lastOutputDeg);
        if (displayDiff > 180) displayDiff = 360 - displayDiff;

        double deadband = (Math.abs(vk) < TURN_RATE_THRESHOLD)
                ? DEADBAND_STATIC_DEG
                : DEADBAND_MOVING_DEG;

        if (displayDiff < deadband) {
            // Подтягиваем состояние фильтра к видимому — не накапливаем отставание
            xk = lastOutputDeg;
            return lastOutputDeg;
        }

        lastOutputDeg = outputDeg;
        return outputDeg;
    }

    /** Медиана для окна 5 (сортировка вставками для малого n) — без аллокаций */
    private double computeMedian() {
        // Сортируем первые 3 из 5 (медиана окна 5 — третий элемент после сортировки)
        // Используем insertion sort на месте, n=5 — копеечная операция
        double[] b = medianBuf;
        // Сортировка вставками первых 5 элементов
        for (int i = 1; i < MEDIAN_WINDOW; i++) {
            double key = b[i];
            int j = i - 1;
            while (j >= 0 && b[j] > key) {
                b[j + 1] = b[j];
                j--;
            }
            b[j + 1] = key;
        }
        // Медиана — средний элемент (индекс 2 для окна 5)
        return b[2];
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
