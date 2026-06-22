package com.termo1.radar.flight;

/**
 * VarioThermalDetector — детекция термика по вариометру (баровысоте).
 *
 * Идея (из algo.md):
 * Параплан обычно снижается со скоростью 1-1.5 м/с.
 * Если скорость снижения вдруг становится МЕНЬШЕ на threshold м/с
 * (например, с -1.5 до -0.5 — разница 1.0 м/с) — это признак термика.
 *
 * Пилот настраивает threshold ползунком в настройках (-1..+2 м/с).
 * При vario > baseline + threshold — сигнал "Vario термик".
 *
 * Baseline — скользящее среднее варио за последние 30 секунд
 * (когда не в крутке), чтобы адаптироваться к разным условиям.
 */
public class VarioThermalDetector {

    // ========================================================================
    // Конфигурация
    // ========================================================================

    /** Окно для baseline: 30 секунд = 300 сэмплов при 50 Гц (фактически ~10 Гц) */
    private static final int BASELINE_WINDOW = 300;
    /** Минимальное время для стабилизации baseline (сек) */
    private static final int MIN_BASELINE_SEC = 15;
    /** Порог по умолчанию (м/с): если vario поднялся на 0.5 выше baseline */
    private static final float DEFAULT_THRESHOLD = 0.5f;
    /** Минимальный порог */
    private static final float THRESHOLD_MIN = -1.0f;
    /** Максимальный порог */
    private static final float THRESHOLD_MAX = 2.0f;

    // ========================================================================
    // Состояние
    // ========================================================================

    private final float[] varioBuffer;
    private int bufferIdx;
    private int bufferFill;
    private float baseline;          // среднее варио за окно (м/с)
    private float threshold;         // порог выше baseline (м/с)
    private long startTimeMs;        // время первого семпла
    private boolean baselineReady;   // достаточно данных для baseline
    private boolean thermalDetected; // текущий статус
    private float currentVario;      // последнее значение варио

    public VarioThermalDetector() {
        this.varioBuffer = new float[BASELINE_WINDOW];
        this.threshold = DEFAULT_THRESHOLD;
        reset();
    }

    /**
     * Обновить детектор новым значением вариометра.
     *
     * @param varioMs текущее значение варио (м/с)
     * @param nowMs   монотонное время
     * @return true если обнаружен термик по варио
     */
    public boolean update(float varioMs, long nowMs) {
        currentVario = varioMs;

        if (startTimeMs == 0) startTimeMs = nowMs;

        // Заполняем буфер
        varioBuffer[bufferIdx] = varioMs;
        bufferIdx = (bufferIdx + 1) % BASELINE_WINDOW;
        if (bufferFill < BASELINE_WINDOW) bufferFill++;

        // Пересчитываем baseline
        if (bufferFill >= BASELINE_WINDOW ||
            (nowMs - startTimeMs) >= MIN_BASELINE_SEC * 1000L) {
            baselineReady = true;
            float sum = 0;
            int count = Math.min(bufferFill, BASELINE_WINDOW);
            for (int i = 0; i < count; i++) {
                sum += varioBuffer[i];
            }
            baseline = sum / count;
        }

        // Детекция
        if (baselineReady) {
            float effectiveThreshold = baseline + threshold;
            thermalDetected = varioMs > effectiveThreshold;
        } else {
            thermalDetected = false;
        }

        return thermalDetected;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    /** Текущий статус детекции */
    public boolean isThermalDetected() {
        return thermalDetected;
    }

    /** Текущее значение варио (м/с) */
    public float getCurrentVario() {
        return currentVario;
    }

    /** Базовое снижение (среднее за окно, м/с) */
    public float getBaseline() {
        return baseline;
    }

    /** Эффективный порог = baseline + threshold (м/с) */
    public float getEffectiveThreshold() {
        return baseline + threshold;
    }

    /** Установить порог (м/с). Clamp в [-1, +2] */
    public void setThreshold(float thresh) {
        this.threshold = Math.max(THRESHOLD_MIN, Math.min(THRESHOLD_MAX, thresh));
    }

    /** Получить порог (м/с) */
    public float getThreshold() {
        return threshold;
    }

    /** Готов ли baseline */
    public boolean isBaselineReady() {
        return baselineReady;
    }

    /**
     * Текстовое описание статуса для UI.
     */
    public String getStatusText() {
        if (!baselineReady) return "КАЛИБРОВКА ВАРИО";
        if (thermalDetected) {
            return String.format(java.util.Locale.US,
                    "ВАРИО+%.1f", currentVario - baseline);
        }
        return "";
    }

    /** Сброс */
    public void reset() {
        bufferIdx = 0;
        bufferFill = 0;
        baseline = 0;
        startTimeMs = 0;
        baselineReady = false;
        thermalDetected = false;
        currentVario = 0;
    }
}
