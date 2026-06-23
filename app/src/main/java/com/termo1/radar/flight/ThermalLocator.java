package com.termo1.radar.flight;

/**
 * ThermalLocator — взвешенный центроид с дрейфом по ветру.
 *
 * По образу XCSoar ThermalLocator:
 * - Буфер точек при крутке (координаты, время, lift_weight)
 * - Weight = vario_above_baseline × recency_weight
 * - Дрейф точек по вектору ветра
 * - Центр = взвешенный центроид дрейфованных точек
 * - Recency: exp(-0.2/N × t^1.5), где N=60, t = возраст в сэмплах
 * - Сброс при выходе из крутки
 *
 * Использует плоскую проекцию (equirectangular) для расчёта центроида.
 */
public class ThermalLocator {

    // ========================================================================
    // Константы
    // ========================================================================

    /** Размер буфера (как XCSoar: 60 точек) */
    private static final int BUFFER_SIZE = 60;

    /** Минимальное количество точек для оценки */
    private static final int MIN_POINTS = 5;

    /** Recency: exp(-0.2/60 × t^1.5) */
    private static final double RECENCY_SCALE = 0.2 / BUFFER_SIZE;
    private static final double RECENCY_EXP = 1.5;

    // ========================================================================
    // Наблюдение (точка в буфере)
    // ========================================================================

    private static class Observation {
        double lat, lon;           // исходные GPS-координаты
        double projX, projY;       // спроецированные координаты (метры)
        long timeMs;               // время наблюдения
        double liftWeight;         // вес подъёма (vario above baseline)
        double recencyWeight;      // вес свежести
        double driftedX, driftedY; // координаты после дрейфа по ветру
    }

    // ========================================================================
    // Состояние
    // ========================================================================

    private final Observation[] buffer = new Observation[BUFFER_SIZE];
    private int head;       // индекс для записи
    private int count;      // сколько точек в буфере

    // Референсная точка проекции (первая точка при крутке)
    private double refLat;
    private double refLon;
    private boolean refSet;

    // Результат
    private double thermalLat;
    private double thermalLon;
    private float thermalBearing;    // от пилота к центру
    private float thermalDistance;   // от пилота до центра (м)
    private boolean estimateValid;
    private float estimateQuality;   // 0..1

    // ========================================================================
    // Init
    // ========================================================================

    public ThermalLocator() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            buffer[i] = new Observation();
        }
        reset();
    }

    /**
     * Полный сброс (при выходе из крутки).
     */
    public void reset() {
        head = 0;
        count = 0;
        refSet = false;
        estimateValid = false;
        thermalLat = 0;
        thermalLon = 0;
        thermalBearing = 0;
        thermalDistance = 0;
        estimateQuality = 0;
    }

    // ========================================================================
    // Добавление точки при крутке
    // ========================================================================

    /**
     * Добавить точку наблюдения во время крутки.
     * Synchronized для потокобезопасности.
     */
    public synchronized void addPoint(double lat, double lon, double vario,
                         double baseline, long nowMs) {
        // Устанавливаем референс проекции при первом добавлении
        if (!refSet) {
            refLat = lat;
            refLon = lon;
            refSet = true;
        }

        Observation obs = buffer[head];

        // Исходные координаты
        obs.lat = lat;
        obs.lon = lon;
        obs.timeMs = nowMs;

        // Lift weight: насколько варио выше baseline (не может быть отрицательным)
        double lift = vario - baseline;
        obs.liftWeight = Math.max(0.0, lift);

        // Проекция в метры (equirectangular, относительно ref)
        obs.projX = (lon - refLon) * Math.cos(Math.toRadians((lat + refLat) / 2.0)) * 111320.0;
        obs.projY = (lat - refLat) * 111320.0;

        // Recency weight — будет пересчитана при дрейфе
        obs.recencyWeight = 1.0;
        obs.driftedX = obs.projX;
        obs.driftedY = obs.projY;

        head = (head + 1) % BUFFER_SIZE;
        if (count < BUFFER_SIZE) count++;
    }

    // ========================================================================
    // Обновление оценки (дрейф + центроид)
    // ========================================================================

    /**
     * Обновить оценку центра термика.
     * Вызывать на каждой итерации обработки во время крутки.
     *
     * @param pilotLat    широта пилота
     * @param pilotLon    долгота пилота
     * @param windU       компонента ветра по X (м/с) — положительный = на восток
     * @param windV       компонента ветра по Y (м/с) — положительный = на север
     * @param nowMs       время
     */
    public synchronized void update(double pilotLat, double pilotLon,
                       double windU, double windV, long nowMs) {
        if (count < MIN_POINTS) {
            estimateValid = false;
            return;
        }

        // 1. Дрейф точек по ветру
        for (int i = 0; i < count; i++) {
            Observation obs = buffer[i];
            double dtSec = Math.max(0, (nowMs - obs.timeMs) / 1000.0);  // BUG-19: защита от отрицательного dt

            // Recency weight: чем старше, тем меньше вес
            double t = Math.min(dtSec, 60.0); // cap at 60 sec
            obs.recencyWeight = Math.exp(-RECENCY_SCALE * Math.pow(t, RECENCY_EXP));

            // Дрейф: точка смещается по ветру
            obs.driftedX = obs.projX + windU * dtSec;
            obs.driftedY = obs.projY + windV * dtSec;
        }

        // 2. Взвешенный центроид
        double sumWeight = 0;
        double sumX = 0;
        double sumY = 0;

        for (int i = 0; i < count; i++) {
            Observation obs = buffer[i];
            double w = obs.liftWeight * obs.recencyWeight;
            if (w > 0) {
                sumX += obs.driftedX * w;
                sumY += obs.driftedY * w;
                sumWeight += w;
            }
        }

        if (sumWeight <= 0) {
            estimateValid = false;
            return;
        }

        double centerX = sumX / sumWeight;
        double centerY = sumY / sumWeight;

        // 3. Конвертируем центроид обратно в GPS
        thermalLon = refLon + centerX / (Math.cos(Math.toRadians((refLat + pilotLat) / 2.0)) * 111320.0);
        thermalLat = refLat + centerY / 111320.0;

        // 4. Расстояние и пеленг от пилота
        thermalDistance = (float) haversineMeters(pilotLat, pilotLon, thermalLat, thermalLon);
        thermalBearing = bearingDeg(pilotLat, pilotLon, thermalLat, thermalLon);

        // 5. Качество: концентрация точек вокруг центроида
        estimateQuality = computeQuality(centerX, centerY);

        estimateValid = true;
    }

    /**
     * Качество оценки: среднеквадратичное расстояние точек от центроида.
     * 0 = размазано, 1 = сконцентрировано.
     */
    private float computeQuality(double cx, double cy) {
        double sumDist = 0;
        double sumWeight = 0;

        for (int i = 0; i < count; i++) {
            Observation obs = buffer[i];
            double w = obs.liftWeight * obs.recencyWeight;
            if (w > 0) {
                double dx = obs.driftedX - cx;
                double dy = obs.driftedY - cy;
                double dist = Math.sqrt(dx * dx + dy * dy);
                sumDist += dist * w;
                sumWeight += w;
            }
        }

        if (sumWeight <= 0) return 0;

        double avgDist = sumDist / sumWeight;
        // 50м = хорошая концентрация (quality=1), 200м = плохая (quality=0)
        float q = (float) (1.0 - Math.min(1.0, avgDist / 100.0));
        return Math.max(0, Math.min(1, q));
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /** Есть ли валидная оценка */
    public boolean isEstimateValid() {
        return estimateValid;
    }

    /** Широта центра термика */
    public double getThermalLat() {
        return thermalLat;
    }

    /** Долгота центра термика */
    public double getThermalLon() {
        return thermalLon;
    }

    /** Пеленг от пилота к центру термика (градусы 0-360) */
    public float getThermalBearing() {
        return thermalBearing;
    }

    /** Расстояние от пилота до центра термика (м) */
    public float getThermalDistance() {
        return thermalDistance;
    }

    /** Качество оценки (0..1) */
    public float getEstimateQuality() {
        return estimateQuality;
    }

    /** Количество точек в буфере */
    public int getPointCount() {
        return count;
    }

    // ========================================================================
    // Геодезические вычисления
    // ========================================================================

    private static double haversineMeters(double lat1, double lon1,
                                          double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static float bearingDeg(double lat1, double lon1,
                                    double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                 - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float) ((bearing + 360.0) % 360.0);
    }
}
