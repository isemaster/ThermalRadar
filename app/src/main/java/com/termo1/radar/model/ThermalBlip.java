package com.termo1.radar.model;

/** A detected thermal shown on the radar */
public class ThermalBlip {
    public long bornMs;
    public float angle;      // absolute bearing in degrees (0=N, 90=E)
    public float strength;   // 1.0 to 8.0
    public float distance;   // meters from pilot
    public float px, py;     // canvas pixel coords, set by RadarRenderer
    public String source;    // "skew" or "spiral"
    public float sizeFactor; // 0.5–1.0, от частоты колебаний (частота→узкий→меньше)

    public static final long LIFE_MS = 6667; // visible for ~6.7 seconds max

    // Яркость по времени жизни (все времена ×1.5 короче):
    //   0–2 с: 100%
    //   2–4 с:  спад со 100% до 30%
    //   4–6.7 с: спад с 30% до 10%
    //   6.7+ с:  погас
    private static final long T_FULL  = 2000;
    private static final long T_DIM   = 4000;
    private static final long T_GONE  = 6667;
    private static final float BRIGHT_FULL   = 1.0f;
    private static final float BRIGHT_DIM    = 0.3f;
    private static final float BRIGHT_GHOST  = 0.1f;

    public ThermalBlip() {}

    public ThermalBlip(float angle, float strength, float distance, String source, long nowMs) {
        this.angle = angle;
        this.strength = Math.min(strength, 8f);
        this.distance = distance;
        this.source = source;
        this.bornMs = nowMs;
        this.px = 0;
        this.py = 0;
        this.sizeFactor = 1.0f;
    }

    public boolean isAlive(long nowMs) { return (nowMs - bornMs) < T_GONE; }

    /** @deprecated используйте getBrightness() */
    @Deprecated
    public float lifeLeft(long nowMs) {
        return 1f - (float)(nowMs - bornMs) / LIFE_MS;
    }

    /**
     * Яркость blip на радаре в зависимости от возраста.
     * Ступенчатое затухание: 100% → 30% → 10% → 0%
     */
    public float getBrightness(long nowMs) {
        long age = nowMs - bornMs;
        if (age < 0) return BRIGHT_FULL;
        if (age < T_FULL) return BRIGHT_FULL;
        if (age < T_DIM) {
            // Плавный спад от 100% до 30% за 3 секунды
            float t = (float)(age - T_FULL) / (T_DIM - T_FULL);
            return BRIGHT_FULL - (BRIGHT_FULL - BRIGHT_DIM) * t;
        }
        if (age < T_GONE) {
            // Плавный спад от 30% до 10% за 4 секунды
            float t = (float)(age - T_DIM) / (T_GONE - T_DIM);
            return BRIGHT_DIM - (BRIGHT_DIM - BRIGHT_GHOST) * t;
        }
        return 0f;
    }
}
