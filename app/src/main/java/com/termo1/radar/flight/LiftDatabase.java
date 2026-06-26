package com.termo1.radar.flight;

/**
 * LiftDatabase — карта подъёма по 12 секторам (30° каждый).
 *
 * По образу XCSoar LiftDatabaseComputer:
 * - 12 секторов, каждый покрывает 30° диапазона heading
 * - Сектор = ((heading + 15°) % 360) / 30
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

    /** Количество секторов (12 по 30° — параплан: круг ~17 с при 50 Гц → ~72 сэмпла на сектор) */
    public static final int SECTOR_COUNT = 12;

    /** Ширина одного сектора (градусы) — исправлено LD-1: 30° (было 10°) */
    private static final double SECTOR_WIDTH = 30.0;

    /** Половина ширины сектора (для округления) — исправлено LD-1: 15° (было 5°) */
    private static final double HALF_SECTOR = 15.0;

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
    public synchronized void clear() {
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
    public synchronized void recordLift(float heading, float vario) {
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

            // BUG-A21: гистерезис — меняем лучший сектор, только если новый уверенно лучше
            int newBest = -1;
            float newBestLift = Float.NEGATIVE_INFINITY;
            for (int s = 0; s < SECTOR_COUNT; s++) {
                if (initialized[s] && liftValues[s] > newBestLift) {
                    newBestLift = liftValues[s];
                    newBest = s;
                }
            }
            if (newBest != bestSectorIndex
                    && (bestSectorIndex < 0 || newBestLift >= bestSectorLift * BEST_SECTOR_UPDATE_FACTOR)) {
                bestSectorIndex = newBest;
                bestSectorLift = newBestLift;
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
    public synchronized int getBestSectorIndex() {
        return bestSectorIndex;
    }

    /**
     * Значение подъёма в лучшем секторе (м/с).
     */
    public synchronized float getBestSectorLift() {
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
    public synchronized float[] getLiftValues() {
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
