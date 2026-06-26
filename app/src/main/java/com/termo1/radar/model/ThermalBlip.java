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

    /** Время жизни блипа (мс). Устанавливается при создании */
    public long lifeMs = 6667;

    public ThermalBlip() {}

    /** Copy constructor — deep copy for thread-safe rendering */
    public ThermalBlip(ThermalBlip other) {
        this.bornMs = other.bornMs;
        this.angle = other.angle;
        this.strength = other.strength;
        this.distance = other.distance;
        this.px = other.px;
        this.py = other.py;
        this.source = other.source;
        this.sizeFactor = other.sizeFactor;
        this.lifeMs = other.lifeMs;
    }

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

    public boolean isAlive(long nowMs) { return (nowMs - bornMs) < lifeMs; }

    /** Set all fields from another blip — для переиспользования объектов без new (MA-5) */
    public void set(ThermalBlip other) {
        this.bornMs = other.bornMs;
        this.angle = other.angle;
        this.strength = other.strength;
        this.distance = other.distance;
        this.px = other.px;
        this.py = other.py;
        this.source = other.source;
        this.sizeFactor = other.sizeFactor;
        this.lifeMs = other.lifeMs;
    }

    /**
     * Яркость blip на радаре в зависимости от возраста.
     * Времена масштабируются пропорционально lifeMs.
     */
    public float getBrightness(long nowMs) {
        long age = nowMs - bornMs;
        if (age < 0) return 1.0f;
        if (age >= lifeMs) return 0f;
        float t = (float)age / lifeMs;
        // 0-30% жизни: полная яркость
        if (t < 0.3f) return 1.0f;
        // 30-60%: спад от 1.0 до 0.3
        if (t < 0.6f) {
            float p = (t - 0.3f) / 0.3f;
            return 1.0f - (1.0f - 0.3f) * p;
        }
        // 60-100%: спад от 0.3 до 0.0
        float p = (t - 0.6f) / 0.4f;
        return 0.3f * (1.0f - p);
    }
}
