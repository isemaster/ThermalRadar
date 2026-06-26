package com.termo1.radar.flight;

import java.util.Locale;

/**
 * WindDriftCalculator — расчёт сноса ветром.
 *
 * Из XCSoar (algo.md раздел 11.4):
 *   drift_angle = asin(wind_speed × sin(wind_bearing - track) / airspeed)
 *   effective_heading = track - drift_angle
 *   ground_speed = airspeed × cos(drift_angle) + wind_speed × cos(wind_bearing - track)
 *
 * Используется для навигационных подсказок: куда реально летим с учётом ветра.
 */
public class WindDriftCalculator {

    // ========================================================================
    // Результат
    // ========================================================================

    public static class WindCorrected {
        /** Курс, который нужно держать для компенсации сноса (градусы) */
        public final float heading;
        /** Скорость относительно земли (м/с) */
        public final float groundSpeed;
        /** Угол сноса (положительный = сносит вправо) */
        public final float driftAngle;
        /** Смещение: метры вправо (+) / влево (-) за 1 км пути */
        public final float driftPerKm;
        /** Направление курса словесно */
        public final String guidanceText;

        public WindCorrected(float heading, float groundSpeed,
                             float driftAngle, float driftPerKm,
                             String guidanceText) {
            this.heading = heading;
            this.groundSpeed = groundSpeed;
            this.driftAngle = driftAngle;
            this.driftPerKm = driftPerKm;
            this.guidanceText = guidanceText;
        }
    }

    // ========================================================================
    // Расчёт
    // ========================================================================

    /**
     * Рассчитать поправку на ветер.
     *
     * @param desiredTrack  желаемый курс (градусы 0-360)
     * @param airspeed      воздушная скорость (м/с)
     * @param windBearing   направление ветра, ОТКУДА дует (градусы 0-360)
     * @param windSpeed     скорость ветра (м/с)
     * @return WindCorrected или null если ветер слишком сильный для расчёта
     */
    public static WindCorrected calculate(
            float desiredTrack,
            float airspeed,
            float windBearing,
            float windSpeed) {

        if (airspeed < 1.0f || windSpeed < 0.1f) {
            // Нет ветра или нет скорости — сноса нет
            return new WindCorrected(
                    desiredTrack, airspeed, 0f, 0f, "");
        }

        // Угол между ветром и курсом (в радианах)
        // windBearing - откуда дует, track - куда летим
        double windAngleRad = Math.toRadians(windBearing - desiredTrack);

        // Проверка: не слишком ли сильный боковой ветер?
        double sinArg = windSpeed * Math.sin(windAngleRad) / airspeed;
        if (Math.abs(sinArg) > 0.99) {
            // Ветер слишком сильный для данного курса — не компенсировать
            return new WindCorrected(
                    desiredTrack, airspeed, 0f, 0f, "Ветер слишком сильный");
        }

        // Угол сноса
        double driftRad = Math.asin(sinArg);
        float driftDeg = (float) Math.toDegrees(driftRad);

        // Курс для компенсации + WCA (исправлено WC-1..3)
        float heading = (desiredTrack + driftDeg + 360f) % 360f;

        // Путевая скорость: headwind вычитается
        // Исправлено WC-2: было +windSpeed (headwind увеличивал gs)
        double gs = airspeed * Math.cos(driftRad)
                  - windSpeed * Math.cos(windAngleRad);
        float groundSpeed = Math.max(0.1f, (float) gs);

        // BUG-24: drift per km — tan (боковое смещение на км ПУТЕВОГО пути)
        // sin даёт смещение на км воздушного пути, что неверно при сильном сносе
        float driftPerKm = (float) Math.abs(Math.tan(driftRad) * 1000);

        // Текстовое описание
        String guidance = getGuidance(driftDeg, driftPerKm);

        return new WindCorrected(
                heading, groundSpeed, driftDeg, driftPerKm, guidance);
    }

    // ========================================================================
    // Голосовая подсказка
    // ========================================================================

    /**
     * Текстовое описание сноса.
     */
    private static String getGuidance(float driftDeg, float driftPerKm) {
        if (Math.abs(driftDeg) < 2f) return "";
        if (Math.abs(driftDeg) < 5f) return "Лёгкий снос";

        // Исправлено WC-3: после WC-1 driftDeg > 0 = нос вправо = снос ВЛЕВО
        String dir = driftDeg > 0 ? "влево" : "вправо";
        return String.format(Locale.US, "Снос %.0f° %s (%.0fм/км)",
                Math.abs(driftDeg), dir, (float) driftPerKm);
    }

    /**
     * Простая подсказка направления: куда сносит относительно курса.
     */
    public static String getDriftDirection(float driftDeg) {
        if (Math.abs(driftDeg) < 2f) return "";
        return driftDeg > 0 ? "сносит вправо" : "сносит влево";
    }

}
