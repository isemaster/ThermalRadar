package com.termo1.radar.igc;

/**
 * Точка трека (IGC B-record).
 *
 * Единое представление для всех источников: чужой IGC, живой полёт.
 * Содержит как pressure altitude (baro), так и GPS altitude.
 * displayAltM = gpsAltM > 0 ? gpsAltM : pressAltM
 */
public class TrackPoint {
    public final double lat, lon;
    public final float pressAltM;     // barometric altitude (m)
    public final float gpsAltM;       // GNSS altitude (m)
    public final float timeSec;       // seconds from start of flight
    public final boolean fixValid;    // 'A' = valid, 'V' = invalid

    /** Высота для отображения: GPS preferred, fallback pressure */
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
        this.displayAltM = (gpsAltM > 0f) ? gpsAltM : pressAltM;
    }

    /** Высота для расчёта vario: pressure (baro — гладкая) */
    public float getVarioAltM() {
        return (pressAltM > 0f) ? pressAltM : gpsAltM;
    }
}
