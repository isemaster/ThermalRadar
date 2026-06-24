package com.termo1.radar.flight;

/**
 * ThermalBaseEstimator — оценка базы термика (где он достигает земли).
 *
 * По образу XCSoar ThermalBase.cpp:
 *   base_lat = pilot_lat - (windV * descent_time) / 111320
 *   base_lon = pilot_lon - (windU * descent_time) / (111320 * cos(lat))
 *
 * База термика находится с наветренной стороны от пилота
 * (столб дрейфует по ветру, его основание — против ветра).
 *
 * Упрощение для Tradar: без рельефа (RasterTerrain), возвращает высоту 0 MSL.
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
        /** Высота базы над рельефом (м AGL) */
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
        double windDirRad = Math.toRadians(windBearing + 180);
        double windU = windSpeedMs * Math.sin(windDirRad);  // east component
        double windV = windSpeedMs * Math.cos(windDirRad);  // north component

        // Время подъёма термика от земли до текущей высоты пилота
        double t = Math.min(altitudeMsl / averageClimb, MAX_DESCENT_TIME);

        // База термика — на наветренной стороне
        double locLat = pilotLat - (windV * t) / 111320.0;
        double locLon = pilotLon - (windU * t) / (111320.0 * Math.cos(Math.toRadians(locLat)));

        double dist = haversineMeters(pilotLat, pilotLon, locLat, locLon);
        float bearing = bearingDeg(pilotLat, pilotLon, locLat, locLon);

        return new ThermalBaseResult(
                locLat, locLon, 0, 0,
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
