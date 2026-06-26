package com.termo1.radar.flight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * WindStore — хранение и агрегация измерений ветра по слоям высоты.
 *
 * Из algo.md раздел 11.3:
 * - Новое измерение → сохраняется в слой по высоте
 * - При запросе: взвешенное среднее по quality × 1/age в текущем/соседних слоях
 * - Старые измерения (> 5 мин) удаляются
 */
public class WindStore {

    /** Структура одного измерения ветра */
    public static class WindMeasurement {
        public final double bearing;     // откуда дует, градусы
        public final double speed;       // м/с
        public final int quality;        // 0-5
        public final double altitude;    // м
        public final long timestampMs;   // монотонное время

        public WindMeasurement(double bearing, double speed, int quality,
                               double altitude, long timestampMs) {
            this.bearing = bearing;
            this.speed = speed;
            this.quality = quality;
            this.altitude = altitude;
            this.timestampMs = timestampMs;
        }
    }

    /** Слой высоты (м) */
    private final double layerHeight;

    /** Хранилище: слой → список измерений */
    private final Map<Integer, List<WindMeasurement>> measurements = new HashMap<>();

    /** Максимальный возраст измерения (мс) */
    private static final long MAX_AGE_MS = 300_000L; // 5 минут

    /** Максимальный возраст перед очисткой (мс) */
    private static final long CLEANUP_AGE_MS = 600_000L; // 10 минут

    /** Последнее время очистки */
    private long lastCleanupMs;

    public WindStore() {
        this(100.0); // 100м слои по умолчанию
    }

    /**
     * @param layerHeight высота слоя в метрах (100м = слои 0-100, 100-200, ...)
     */
    public WindStore(double layerHeight) {
        this.layerHeight = layerHeight;
    }

    /**
     * Добавить измерение ветра.
     */
    public void addMeasurement(double bearing, double speed, int quality,
                               double altitude, long nowMs) {
        int layer = (int) (altitude / layerHeight);
        synchronized (measurements) {
            measurements.computeIfAbsent(layer, k -> new ArrayList<>())
                    .add(new WindMeasurement(bearing, speed, quality, altitude, nowMs));
        }
        cleanup(nowMs);
    }

    /**
     * Добавить измерение из WindGradientDescent (бывший WindEKF).
     */
    public void addEKFMeasurement(WindGradientDescent ekf, double altitude, long nowMs) {
        if (ekf.getQuality() <= 0) return;
        addMeasurement(
                ekf.getWindDirectionDeg(),
                ekf.getWindSpeed(),
                ekf.getQuality(),
                altitude,
                nowMs
        );
    }

    /**
     * Получить ветер на заданной высоте.
     *
     * @param altitude высота (м MSL)
     * @param nowMs    текущее монотонное время
     * @return WindMeasurement или null если данных нет
     */
    public WindMeasurement getWindAt(double altitude, long nowMs) {
        int layer = (int) (altitude / layerHeight);
        long cutoff = nowMs - MAX_AGE_MS;

        synchronized (measurements) {
            // Собираем кандидатов из текущего и соседних слоёв
            List<WindMeasurement> candidates = new ArrayList<>();
            for (int l = layer - 1; l <= layer + 1; l++) {
                List<WindMeasurement> list = measurements.get(l);
                if (list != null) {
                    for (WindMeasurement m : list) {
                        if (m.timestampMs >= cutoff) {
                            candidates.add(m);
                        }
                    }
                }
            }

            if (candidates.isEmpty()) return null;

            // Исправлено WS-1: векторное усреднение подшипов через sin/cos
            // Линейное усреднение ломается при переходе через 0/360 (350°+10°=180°)
            double sumSin = 0, sumCos = 0;
            double totalWeight = 0;
            double weightedSpeed = 0;

            for (WindMeasurement m : candidates) {
                long age = nowMs - m.timestampMs;
                double ageFactor = Math.max(0.1, 1.0 - (double) age / MAX_AGE_MS);
                double w = m.quality * ageFactor;
                totalWeight += w;
                double rad = Math.toRadians(m.bearing);
                sumSin += Math.sin(rad) * w;
                sumCos += Math.cos(rad) * w;
                weightedSpeed += m.speed * w;
            }

            if (totalWeight <= 0) return null;

            double avgBearing = Math.toDegrees(Math.atan2(sumSin, sumCos));
            if (avgBearing < 0) avgBearing += 360;
            double avgSpeed = weightedSpeed / totalWeight;
            int avgQuality = 0;
            for (WindMeasurement m : candidates) {
                avgQuality = Math.max(avgQuality, m.quality);
            }

            return new WindMeasurement(
                    avgBearing % 360,
                    avgSpeed,
                    avgQuality,
                    altitude,
                    nowMs
            );
        }
    }

    /** Очистка старых измерений */
    private void cleanup(long nowMs) {
        if (nowMs - lastCleanupMs < 60_000L) return; // не чаще раза в минуту
        lastCleanupMs = nowMs;
        long cutoff = nowMs - CLEANUP_AGE_MS;

        synchronized (measurements) {
            Iterator<List<WindMeasurement>> it = measurements.values().iterator();
            while (it.hasNext()) {
                List<WindMeasurement> list = it.next();
                list.removeIf(m -> m.timestampMs < cutoff);
                if (list.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    /** Полный сброс */
    public void reset() {
        synchronized (measurements) {
            measurements.clear();
        }
    }
}
