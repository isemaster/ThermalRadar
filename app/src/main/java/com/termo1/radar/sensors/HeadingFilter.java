package com.termo1.radar.sensors;

/**
 * HeadingFilter — полный пайплайн сглаживания курса (heading) для отображения.
 *
 * Пайплайн:
 *   Raw heading → CLAMP выбросов (если |Δheading| > 40°/с → limit по lastOutputDeg)
 *     → MEDIAN FILTER (окно 5) → без аллокаций
 *     → Alpha-Beta filter (α=0.15, β=0.02) → плавная кривая с предсказанием
 *     → DEADBAND (адаптивный: 1.0° в статике, 0.3° в движении)
 *
 * α=0.15, β=0.02 — пересчитано для параплана по ревью:
 *   - В статике σ_out ≈ σ·√(α²+(1-α)²) ≈ 0.6° (было 2° при α=0.75)
 *   - В динамике vk (turn rate) доминирует, шум незаметен
 *
 * @see <a href="https://github.com/isemaster/ThermalRadar">ThermalRadar</a>
 */
public class HeadingFilter {

    // === CLAMP ===
    private static final float MAX_DELTA_DEG_PER_SEC = 40f;

    // === MEDIAN FILTER ===
    private static final int MEDIAN_WINDOW = 5;
    private final double[] medianBuf = new double[MEDIAN_WINDOW];
    private int medianCount;

    // === ALPHA-BETA ===
    private final double alpha;   // вес позиции (0.15 — статика)
    private final double beta;    // вес скорости (0.02)
    /** Максимальная скорость поворота для vk (°/с) — защита от выбросов при малых dt */
    private static final double MAX_TURN_RATE = 50.0;
    private double xk;            // filtered heading (в unwrapped space)
    private double vk;            // turn rate (°/с)
    private long lastTimeMs;      // время последнего обновления
    private double unwrapped;     // монотонная шкала без скачков 359→0

    // === DEADBAND (адаптивный) ===
    /** В статике (|vk| < 2°/с) — не дёргать UI, достаточно точно для центровки термика */
    private static final double DEADBAND_STATIC_DEG = 1.0;
    /** В движении — быстрая реакция */
    private static final double DEADBAND_MOVING_DEG = 0.3;
    /** Порог turn-rate для переключения режимов */
    private static final double TURN_RATE_THRESHOLD = 2.0;
    private double lastOutputDeg;  // последнее выведенное значение (0-360)
    /** Накопленная разница в deadband (исправлено HF-4) */
    private double pendingDiff;

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
        // Исправлено HF-2/3: при dtMs <= 0 или dtMs > 2000 — пропускаем update
        // (clock skew или app backgrounded), возвращаем lastOutputDeg.
        // Следующий валидный кадр применит CLAMP к lastOutputDeg, скачка не будет.
        if (dtMs <= 0 || dtMs > 2000) {
            lastTimeMs = timeMs;
            return lastOutputDeg;
        }
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
        // Используем реальный dt для β (исправлено по T12 §2.3 — NOMINAL_DT не совпадает
        // с реальной частотой сенсора 25 Гц). vk clamp защищает от взрыва при малых dt.
        xk = xkPred + alpha * innovation;
        vk = vkPred + beta * innovation / dt;
        if (vk > MAX_TURN_RATE) vk = MAX_TURN_RATE;
        if (vk < -MAX_TURN_RATE) vk = -MAX_TURN_RATE;

        // Wrap в 0-360
        double outputDeg = (xk % 360 + 360) % 360;

        // ============================================================
        // 4. DEADBAND — адаптивный (1.0° в статике, 0.3° в движении)
        //    Исправлено по T12 §2.3: xk НЕ подтягивается к lastOutputDeg,
        //    т.к. xk в unwrapped space, а lastOutputDeg в wrapped (0-360).
        // ============================================================
        double displayDiff = Math.abs(outputDeg - lastOutputDeg);
        if (displayDiff > 180) displayDiff = 360 - displayDiff;

        double deadband = (Math.abs(vk) < TURN_RATE_THRESHOLD)
                ? DEADBAND_STATIC_DEG
                : DEADBAND_MOVING_DEG;

        // Исправлено HF-4: накопление pendingDiff в deadband, чтобы не было скачков
        if (displayDiff < deadband) {
            pendingDiff += displayDiff * Math.signum(outputDeg - lastOutputDeg);
            return lastOutputDeg;
        }
        // Применяем накопленную разницу + текущую
        double totalDiff = pendingDiff + (outputDeg - lastOutputDeg);
        pendingDiff = 0;
        double newOutput = (lastOutputDeg + totalDiff + 360) % 360;
        lastOutputDeg = newOutput;
        return newOutput;
    }

    /** Копия буфера для сортировки (чтобы не разрушать временной ряд) */
    private final double[] medianSortBuf = new double[MEDIAN_WINDOW];

    /** Медиана для окна 5 (сортировка вставками для малого n) */
    private double computeMedian() {
        // Исправлено HF-1: сортируем КОПИЮ medianBuf, не оригинал
        // Иначе System.arraycopy в update() удаляет наименьший элемент, а не самый старый
        System.arraycopy(medianBuf, 0, medianSortBuf, 0, MEDIAN_WINDOW);
        double[] b = medianSortBuf;
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
        pendingDiff = 0;
    }
}
