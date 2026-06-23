package com.termo1.radar.flight;

/**
 * ThermalBaseEstimator — оценка базы термика (где он достигает земли).
 *
 * По образу XCSoar ThermalBase.cpp:
 * - Использует текущую высоту и средний набор (average climb)
 * - Итеративный спуск на 10 шагов с учётом ветра
 * - На каждом шаге: снос позиции по ветру (wind_drift = wind_speed × время спуска)
 * - Если известна высота рельефа — учитывает её
 * - Результат: координата на земле и высота базы
 *
 * Упрощение для Tradar: без рельефа (RasterTerrain) — возвращает высоту 0 MSL,
 * но с корректным сносом по ветру.
 */
public class ThermalBaseEstimator {

    // ========================================================================
    // Результат
    // ========================================================================

    public static class ThermalBaseResult {
        /** Широта базы термика (где термик достигает земли) */
        public final double groundLat;
        /** Долгота базы термика */
        public final double groundLon;
        /** Высота базы над землёй (м MSL) */
        public final double groundAltitude;
        /** Высота базы над рельефом (м AGL) — сколько метров до земли */
        public final double groundAltAgl;
        /** Расстояние от пилота до базы (м) */
        public final double distanceFromPilot;
        /** Пеленг от пилота к базе (градусы) */
        public final float bearingFromPilot;
        /** Валидность оценки */
        public final boolean valid;

        public ThermalBaseResult(double groundLat, double groundLon,
                                 double groundAltitude, double groundAltAgl,
                                 double distanceFromPilot, float bearingFromPilot,
                                 boolean valid) {
            this.groundLat = groundLat;
            this.groundLon = groundLon;
            this.groundAltitude = groundAltitude;
            this.groundAltAgl = groundAltAgl;
            this.distanceFromPilot = distanceFromPilot;
            this.bearingFromPilot = bearingFromPilot;
            this.valid = valid;
        }
    }

    // ========================================================================
    // Константы
    // ========================================================================

    /** Количество итераций при спуске (как XCSoar: 10 шагов) */
    private static final int DESCENT_STEPS = 10;

    /** Минимальный средний подъём для расчёта (м/с) */
    private static final double MIN_AVERAGE_CLIMB = 0.2;

    /** Максимальное время спуска для учёта (сек) — предохранитель */
    private static final double MAX_DESCENT_TIME = 300.0; // 5 минут

    // ========================================================================
    // Расчёт
    // ========================================================================

    /**
     * Оценить базу термика.
     *
     * @param pilotLat       широта пилота
     * @param pilotLon       долгота пилота
     * @param altitudeMsl    текущая высота пилота (м MSL)
     * @param averageClimb   средний подъём за время крутки (м/с)
     * @param windBearing    направление ветра, ОТКУДА дует (градусы 0-360)
     * @param windSpeedMs    скорость ветра (м/с)
     * @return ThermalBaseResult или default невалидный
     */
    public static ThermalBaseResult estimate(
            double pilotLat, double pilotLon,
            double altitudeMsl, double averageClimb,
            double windBearing, double windSpeedMs) {

        // Проверка входных данных
        if (averageClimb < MIN_AVERAGE_CLIMB || altitudeMsl < 10) {
            return new ThermalBaseResult(
                    pilotLat, pilotLon, 0, 0, 0, 0, false);
        }

        // Вектор ветра (куда дует, а не откуда)
        double windDirRad = Math.toRadians(windBearing + 180); // wind bearing = откуда, +180 = куда
        double windU = windSpeedMs * Math.sin(windDirRad);  // east component
        double windV = windSpeedMs * Math.cos(windDirRad);  // north component

        // Максимальное время подъёма (от земли до текущей высоты)
        double maxTime = altitudeMsl / averageClimb;
        if (maxTime > MAX_DESCENT_TIME) maxTime = MAX_DESCENT_TIME;

        // Итеративный спуск (как XCSoar: 10 шагов)
        double dh = altitudeMsl / DESCENT_STEPS;
        double locLat = pilotLat;
        double locLon = pilotLon;

        for (int step = 1; step <= DESCENT_STEPS; step++) {
            double h = altitudeMsl - step * dh;  // высота на этом шаге
            double t = (altitudeMsl - h) / averageClimb;  // время спуска до этой высоты

            // Снос по ветру: позиция смещается ПРОТИВ ветра (вверх по потоку)
            // База термика — где столб касается земли. Столб наклонён ПО ветру,
            // поэтому база находится С НАВЕТРЕННОЙ стороны от пилота.
            double driftDist = windSpeedMs * t;
            locLat = pilotLat - (windV * t) / 111320.0;
            locLon = pilotLon - (windU * t) / (111320.0 * Math.cos(Math.toRadians(locLat)));

            if (h <= 0) {
                // Достигли земли
                double exactT = altitudeMsl / averageClimb;
                locLat = pilotLat - (windV * exactT) / 111320.0;
                locLon = pilotLon - (windU * exactT) / (111320.0 * Math.cos(Math.toRadians(locLat)));

                double dist = haversineMeters(pilotLat, pilotLon, locLat, locLon);
                float bearing = bearingDeg(pilotLat, pilotLon, locLat, locLon);

                return new ThermalBaseResult(
                        locLat, locLon, 0, 0,
                        dist, bearing, true);
            }
        }

        // Не достигли земли за 10 шагов
        double dist = haversineMeters(pilotLat, pilotLon, locLat, locLon);
        float bearing = bearingDeg(pilotLat, pilotLon, locLat, locLon);

        return new ThermalBaseResult(
                locLat, locLon, altitudeMsl - DESCENT_STEPS * dh,
                altitudeMsl - DESCENT_STEPS * dh,
                dist, bearing, true);
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
