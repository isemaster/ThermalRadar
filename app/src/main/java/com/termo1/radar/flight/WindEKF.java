package com.termo1.radar.flight;

/**
 * WindEKF — расширенный фильтр Калмана для оценки ветра на прямых участках полёта.
 *
 * Из algo.md раздел 11.2:
 * State: [wind_u, wind_v, scale_factor]
 *   wind_u      — ветер по оси X (м/с)
 *   wind_v      — ветер по оси Y (м/с)
 *   scale_factor — поправка на точность TAS (0.5-1.5)
 *
 * Update: каждый GPS fix на прямом участке сравнивает GPS-вектор с воздушной скоростью.
 */
public class WindEKF {

    // State
    private double windU;       // ветер по оси X (м/с)
    private double windV;       // ветер по оси Y (м/с)
    private double scaleFactor; // поправка TAS (0.5-1.5)

    // Adaptive gain
    private double k;

    // Счётчик обновлений для качества
    private int updateCount;

    public WindEKF() {
        reset();
    }

    /**
     * Обновить оценку ветра на основе GPS fix и воздушной скорости.
     *
     * @param airspeed воздушная скорость (м/с), обычно ~9.5 м/с для параплана
     * @param gpsVx    компонента GPS-скорости по оси X (м/с)
     * @param gpsVy    компонента GPS-скорости по оси Y (м/с)
     */
    public void update(double airspeed, double gpsVx, double gpsVy) {
        double dx = gpsVx - windU;
        double dy = gpsVy - windV;
        double mag = Math.sqrt(dx * dx + dy * dy);

        if (mag < 0.1 || airspeed < 1.0) return; // нет данных

        // Kalman gains
        double k0 = -scaleFactor * dx / mag * k;
        double k1 = -scaleFactor * dy / mag * k;
        double k2 = mag * 1e-5;

        // Innovation: невязка измерения
        double error = airspeed - scaleFactor * mag;

        // Update state
        windU += k0 * error;
        windV += k1 * error;
        scaleFactor += k2 * error;

        // Clamp scale factor
        if (scaleFactor < 0.5) scaleFactor = 0.5;
        if (scaleFactor > 1.5) scaleFactor = 1.5;

        // Adaptive gain decay
        k = k + 0.01 * (0.01 - k);

        updateCount++;
    }

    /** Скорость ветра (м/с) */
    public double getWindSpeed() {
        return Math.sqrt(windU * windU + windV * windV);
    }

    /**
     * Направление ветра, ОТКУДА дует (градусы, 0-360).
     * atan2(-windU, -windV): отрицание потому что windU/windV — это куда дует,
     * а нужно откуда.
     */
    public double getWindDirectionDeg() {
        double dir = Math.toDegrees(Math.atan2(-windU, -windV));
        return (dir + 360) % 360;
    }

    /** Компонента ветра по X (м/с) */
    public double getWindU() { return windU; }

    /** Компонента ветра по Y (м/с) */
    public double getWindV() { return windV; }

    /** Поправка TAS */
    public double getScaleFactor() { return scaleFactor; }

    /** Качество оценки (0-3) */
    public int getQuality() {
        if (updateCount < 5) return 0;      // недостаточно данных
        if (k < 0.02) return 3;             // устоялось
        if (k < 0.05) return 2;
        if (k < 0.1) return 1;
        return 0;
    }

    /** Количество обновлений */
    public int getUpdateCount() { return updateCount; }

    /** Сброс фильтра */
    public void reset() {
        windU = 0;
        windV = 0;
        scaleFactor = 1.0;
        k = 0.04;
        updateCount = 0;
    }
}
