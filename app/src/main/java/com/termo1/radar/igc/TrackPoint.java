package com.termo1.radar.igc;

/**
 * Точка трека (IGC B-record).
 *
 * Единое представление для всех источников: чужой IGC, живой полёт.
 * Содержит как pressure altitude (baro), так и GPS altitude.
 *
 * Приоритет высоты для отображения (исправлено ALT-1):
 *   1. Pressure altitude (P_ALT) — гладкая, в 30-50× стабильнее GPS
 *   2. GPS altitude (G_ALT) — fallback когда P_ALT == 0 ("00000" в IGC)
 *
 * QNH-коррекция не применяется на уровне TrackPoint — прокидывается
 * через IGCAnalyzer.postProcessDisplayFrame().
 */
public class TrackPoint {
    public final double lat, lon;
    public final float pressAltM;     // barometric altitude (m)
    public final float gpsAltM;       // GNSS altitude (m)
    public final float timeSec;       // seconds from start of flight
    public final boolean fixValid;    // 'A' = valid, 'V' = invalid

    /** Высота для отображения: баро приоритет (гладкая), GPS fallback (шумная) */
    public final float displayAltM;

    public TrackPoint(double lat, double lon,
                      float pressAltM, float gpsAltM,
                      float timeSec, boolean fixValid) {
        this.lat = lat;
        this.lon = lon;
        this.pressAltM = pressAltM;
        this.gpsAltM = gpsAltM;
        this.timeSec = timeSec;
        this.fixValid = fixValid;
        // ALT-1: Баро должна быть приоритет — она в 30-50 раз глаже GPS.
        // Было: (gpsAltM > 0f) ? gpsAltM : pressAltM
        this.displayAltM = (pressAltM > 0f) ? pressAltM : gpsAltM;
    }

    /** Высота для расчёта vario: та же логика — баро приоритет */
    public float getVarioAltM() {
        return (pressAltM > 0f) ? pressAltM : gpsAltM;
    }
}
