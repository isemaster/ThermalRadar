package com.termo1.radar.sensors;

import java.util.ArrayDeque;

/**
 * HeadingFilter — полный пайплайн сглаживания курса (heading) для отображения.
 *
 * Пайплайн (из algo.md раздел 12):
 *   Raw heading → CLAMP выбросов (если |Δheading| > 50°/с → limit)
 *     → MEDIAN FILTER (окно 3) → убирает импульсный шум
 *     → Alpha-Beta filter (α=0.6, β=0.2) → плавная кривая с предсказанием
 *     → DEADBAND (1.5°) → не обновлять если изменение незначительно
 *
 * Рекомендация: Медиана(3) + Alpha-Beta(α=0.6, β=0.2)
 */
public class HeadingFilter {

    // === CLAMP ===
    private static final float MAX_DELTA_DEG_PER_SEC = 70f; // 70°/с — физический лимит поворота параплана

    // === MEDIAN FILTER ===
    private static final int MEDIAN_WINDOW = 3;
    private final ArrayDeque<Double> medianBuffer = new ArrayDeque<>(MEDIAN_WINDOW);

    // === ALPHA-BETA ===
    private final double alpha;   // вес позиции
    private final double beta;    // вес скорости (turn rate)
    private double xk;            // filtered heading (в unwrapped space)
    private double vk;            // turn rate (°/с)
    private long lastTimeMs;      // время последнего обновления
    private double unwrapped;     // монотонная шкала без скачков 359→0

    // === DEADBAND ===
    private static final double DEADBAND_DEG = 4.0; // не обновлять если < 4° (масляный демпфер)
    private double lastOutputDeg;  // последнее выведенное значение (0-360)

    // === Состояние ===
    private boolean initialized;

    /**
     * @param alpha вес позиции (0.6 — рекомендуется)
     * @param beta  вес скорости поворота (0.2 — рекомендуется)
     */
    public HeadingFilter(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
        reset();
    }

    /** Конструктор с рекомендуемыми значениями Медиана(3) + Alpha-Beta(α=0.75, β=0.3) */
    public HeadingFilter() {
        this(0.75, 0.3);
    }

    /**
     * Обработать сырой heading из сенсора/компаса.
     *
     * @param rawHeading сырой курс в градусах 0-360
     * @param timeMs     монотонное время (SystemClock.elapsedRealtime())
     * @return отфильтрованный heading в градусах 0-360, или NaN если deadband сработал
     */
    public double update(double rawHeading, long timeMs) {
        if (!initialized) {
            xk = rawHeading;
            unwrapped = rawHeading;
            lastOutputDeg = rawHeading;
            lastTimeMs = timeMs;
            initialized = true;
            medianBuffer.clear();
            medianBuffer.addLast(rawHeading);
            return rawHeading;
        }

        long dtMs = timeMs - lastTimeMs;
        if (dtMs <= 0) dtMs = 1;
        if (dtMs > 2000) dtMs = 2000; // cap — слишком большой разрыв
        double dt = dtMs / 1000.0;
        lastTimeMs = timeMs;

        // ============================================================
        // 1. CLAMP — ограничение скорости поворота (физический лимит)
        // ============================================================
        double clamped = rawHeading;
        if (initialized) {
            double diff = rawHeading - lastOutputDeg;
            // нормализация в [-180, 180]
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            double maxDelta = MAX_DELTA_DEG_PER_SEC * dt;
            if (Math.abs(diff) > maxDelta) {
                clamped = lastOutputDeg + Math.signum(diff) * maxDelta;
                // Wrap обратно в 0-360
                clamped = (clamped % 360 + 360) % 360;
            }
        }

        // ============================================================
        // 2. MEDIAN FILTER — убирает импульсные помехи
        // ============================================================
        medianBuffer.addLast(clamped);
        if (medianBuffer.size() > MEDIAN_WINDOW) {
            medianBuffer.removeFirst();
        }
        double median = computeMedian();

        // ============================================================
        // 3. ALPHA-BETA FILTER (с unwrap)
        // ============================================================

        // Unwrap: разворачиваем угол в монотонную шкалу
        double diff = median - unwrapped;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        unwrapped += diff;

        // PREDICT: куда будем через dt
        double xkPred = xk + vk * dt;
        double vkPred = vk;

        // INNOVATION: на сколько ошиблись
        double innovation = unwrapped - xkPred;

        // UPDATE
        xk = xkPred + alpha * innovation;
        vk = vkPred + beta * innovation / dt;

        // Wrap в 0-360 для отображения
        double outputDeg = (xk % 360 + 360) % 360;

        // ============================================================
        // 4. DEADBAND — не обновлять если изменение незначительно
        // ============================================================
        double displayDiff = Math.abs(outputDeg - lastOutputDeg);
        if (displayDiff > 180) displayDiff = 360 - displayDiff;

        if (displayDiff < DEADBAND_DEG) {
            return Double.NaN; // сигнал: не обновлять отображение
        }

        lastOutputDeg = outputDeg;
        return outputDeg;
    }

    /**
     * Быстрая медиана для окна 3 (без сортировки — inline).
     */
    private double computeMedian() {
        if (medianBuffer.size() < 3) {
            // Меньше 3 точек — возвращаем последнюю
            return medianBuffer.getLast();
        }
        // Для окна 3: медиана — среднее из трёх
        Double[] vals = medianBuffer.toArray(new Double[0]);
        double a = vals[0], b = vals[1], c = vals[2];
        // Медиана трёх чисел без сортировки:
        // среднее по значению (не по индексу)
        if ((a >= b && a <= c) || (a >= c && a <= b)) return a;
        if ((b >= a && b <= c) || (b >= c && b <= a)) return b;
        return c;
    }

    /**
     * Получить текущую оценку скорости поворота (°/с).
     */
    public double getTurnRate() {
        return vk;
    }

    /**
     * Сброс состояния фильтра.
     */
    public void reset() {
        xk = 0;
        vk = 0;
        unwrapped = 0;
        lastTimeMs = 0;
        initialized = false;
        lastOutputDeg = 0;
        medianBuffer.clear();
    }
}
