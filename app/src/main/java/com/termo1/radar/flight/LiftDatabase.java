package com.termo1.radar.flight;

/**
 * LiftDatabase — карта подъёма по 36 секторам (10° каждый).
 *
 * По образу XCSoar LiftDatabaseComputer:
 * - 36 секторов, каждый покрывает 10° диапазона heading
 * - Сектор = ((heading + 5°) % 360) / 10
 * - Во время крутки записываем текущий варио в сектор
 * - Отслеживаем сектор с максимальным подъёмом
 * - Сброс на каждом новом термике (при входе в крутку)
 *
 * Используется для голосовых подсказок и визуализации на радаре.
 */
public class LiftDatabase {

    // ========================================================================
    // Константы
    // ========================================================================

    /** Количество секторов */
    public static final int SECTOR_COUNT = 36;

    /** Ширина одного сектора (градусы) */
    private static final double SECTOR_WIDTH = 360.0 / SECTOR_COUNT; // 10°

    /** Половина ширины сектора (для округления) */
    private static final double HALF_SECTOR = SECTOR_WIDTH / 2.0; // 5°

    /** EMA alpha для сглаживания варио в секторе */
    private static final float SMOOTH_ALPHA = 0.3f;

    /** Минимальное количество сэмплов в секторе для достоверности */
    public static final int MIN_SAMPLES_PER_SECTOR = 5;

    /** Коэффициент обновления лучшего сектора (если новый > old × factor) */
    private static final float BEST_SECTOR_UPDATE_FACTOR = 1.1f;

    // ========================================================================
    // Данные
    // ========================================================================

    /** Среднее варио в каждом секторе (м/с) */
    private final float[] liftValues = new float[SECTOR_COUNT];

    /** Количество сэмплов в каждом секторе */
    private final int[] sampleCount = new int[SECTOR_COUNT];

    /** Флаг: сектор инициализирован (есть хотя бы один сэмпл) */
    private final boolean[] initialized = new boolean[SECTOR_COUNT];

    /** Лучший сектор (индекс, -1 если неизвестно) */
    private int bestSectorIndex = -1;

    /** Значение подъёма в лучшем секторе */
    private float bestSectorLift = 0f;

    /** Общий счётчик сэмплов с последнего сброса */
    private int totalSamples;

    // ========================================================================
    // Lifecycle
    // ========================================================================

    public LiftDatabase() {
        clear();
    }

    /**
     * Очистить все данные (вызывать при входе в новый термик).
     */
    public void clear() {
        for (int i = 0; i < SECTOR_COUNT; i++) {
            liftValues[i] = 0f;
            sampleCount[i] = 0;
            initialized[i] = false;
        }
        bestSectorIndex = -1;
        bestSectorLift = 0f;
        totalSamples = 0;
    }

    // ========================================================================
    // Обновление
    // ========================================================================

    /**
     * Записать значение варио в сектор, соответствующий текущему heading.
     * Вызывать на каждом сэмпле во время крутки.
     *
     * @param heading  текущий курс (градусы 0-360)
     * @param vario    текущий варио (м/с)
     */
    public void recordLift(float heading, float vario) {
        int idx = headingToSector(heading);

        totalSamples++;

        if (!initialized[idx]) {
            liftValues[idx] = vario;
            sampleCount[idx] = 1;
            initialized[idx] = true;

            // Первый сэмпл в секторе — сразу обновляем лучший
            if (bestSectorIndex < 0 || vario > bestSectorLift) {
                bestSectorIndex = idx;
                bestSectorLift = vario;
            }
        } else {
            // EMA-сглаживание
            sampleCount[idx]++;
            float alpha = (float) (1.0 / Math.min(sampleCount[idx], 50));
            liftValues[idx] += alpha * (vario - liftValues[idx]);

            // BUG-22: при каждом recordLift пересчитываем bestSectorIndex
            // заново, чтобы bestSectorLift мог уменьшаться
            bestSectorIndex = -1;
            bestSectorLift = Float.NEGATIVE_INFINITY;
            for (int s = 0; s < SECTOR_COUNT; s++) {
                if (initialized[s] && liftValues[s] > bestSectorLift) {
                    bestSectorLift = liftValues[s];
                    bestSectorIndex = s;
                }
            }
        }
    }

    // ========================================================================
    // Получение данных
    // ========================================================================

    /**
     * Получить значение подъёма в секторе.
     * @param sector  индекс сектора (0-35)
     * @return варио (м/с) или 0 если данных нет
     */
    public float getLift(int sector) {
        if (sector < 0 || sector >= SECTOR_COUNT) return 0f;
        return initialized[sector] ? liftValues[sector] : 0f;
    }

    /**
     * Получить количество сэмплов в секторе.
     */
    public int getSampleCount(int sector) {
        if (sector < 0 || sector >= SECTOR_COUNT) return 0;
        return sampleCount[sector];
    }

    /**
     * Достоверны ли данные в секторе (достаточно сэмплов).
     */
    public boolean isSectorValid(int sector) {
        return sector >= 0 && sector < SECTOR_COUNT
                && sampleCount[sector] >= MIN_SAMPLES_PER_SECTOR;
    }

    /**
     * Индекс лучшего сектора (с максимальным подъёмом).
     * @return 0-35, или -1 если данных нет
     */
    public int getBestSectorIndex() {
        return bestSectorIndex;
    }

    /**
     * Значение подъёма в лучшем секторе (м/с).
     */
    public float getBestSectorLift() {
        return bestSectorLift;
    }

    /**
     * Центральный heading лучшего сектора (градусы).
     * @return heading в градусах 0-360, или -1
     */
    public float getBestSectorHeading() {
        if (bestSectorIndex < 0) return -1f;
        return sectorToHeading(bestSectorIndex);
    }

    /**
     * Общий счётчик сэмплов.
     */
    public int getTotalSamples() {
        return totalSamples;
    }

    /**
     * Количество секторов с данными.
     */
    public int getActiveSectors() {
        int count = 0;
        for (boolean init : initialized) {
            if (init) count++;
        }
        return count;
    }

    // ========================================================================
    // Визуализация
    // ========================================================================

    /**
     * Получить массив значений подъёма для рендеринга.
     * @return float[36], где entry = lift в м/с (0 если данных нет)
     */
    public float[] getLiftValues() {
        float[] result = new float[SECTOR_COUNT];
        for (int i = 0; i < SECTOR_COUNT; i++) {
            result[i] = initialized[i] ? liftValues[i] : 0f;
        }
        return result;
    }

    // ========================================================================
    // Преобразование heading ↔ sector
    // ========================================================================

    /**
     * Преобразовать heading в индекс сектора (как XCSoar heading_to_index).
     * Сектор 0 = 350°..10°, сектор 1 = 10°..20°, и т.д.
     *
     * @param heading курс в градусах 0-360
     * @return индекс 0-35
     */
    public static int headingToSector(float heading) {
        // Нормализуем: добавляем полсектора, чтобы 0° попадало в сектор 0
        double h = heading + HALF_SECTOR;
        while (h >= 360) h -= 360;
        while (h < 0) h += 360;
        int idx = (int) (h / SECTOR_WIDTH);
        if (idx >= SECTOR_COUNT) idx = SECTOR_COUNT - 1;
        return idx;
    }

    /**
     * Преобразовать индекс сектора в центральный heading.
     *
     * @param sector  индекс 0-35
     * @return центральный heading (градусы)
     */
    public static float sectorToHeading(int sector) {
        if (sector < 0 || sector >= SECTOR_COUNT) return 0f;
        return sector * (float) SECTOR_WIDTH + (float) HALF_SECTOR;
    }

    /**
     * Диапазон heading для сектора (начало, конец).
     *
     * @param sector  индекс 0-35
     * @return float[]{startDeg, endDeg}
     */
    public static float[] sectorRange(int sector) {
        if (sector < 0 || sector >= SECTOR_COUNT) return new float[]{0, 0};
        float start = sector * (float) SECTOR_WIDTH;
        float end = start + (float) SECTOR_WIDTH;
        return new float[]{start, end};
    }

    // ========================================================================
    // Текстовое описание
    // ========================================================================

    /**
     * Человекочитаемое направление лучшего сектора.
     * @return "N", "NE", "E", ... или пустая строка
     */
    public String getBestSectorDirection() {
        if (bestSectorIndex < 0) return "";
        float h = sectorToHeading(bestSectorIndex);
        return headingToDirection(h);
    }

    /**
     * Преобразовать heading в направление (N/NE/E/SE/S/SW/W/NW).
     */
    public static String headingToDirection(float heading) {
        String[] dirs = {"N", "NNE", "NE", "ENE",
                         "E", "ESE", "SE", "SSE",
                         "S", "SSW", "SW", "WSW",
                         "W", "WNW", "NW", "NNW"};
        int idx = (int) Math.round(heading / 22.5f) % 16;
        return dirs[idx];
    }
}
