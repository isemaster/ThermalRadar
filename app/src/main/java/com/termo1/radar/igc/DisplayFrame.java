package com.termo1.radar.igc;

import com.termo1.radar.model.ThermalBlip;
import java.util.List;

/**
 * DisplayFrame — immutable snapshot всех данных для одного кадра рендера.
 *
 * Содержит всё, что нужно RadarView для отрисовки:
 * - Позиция, высота, скорость, варио
 * - Ветер, L/D, статус крутки
 * - Термики (блипы + центроид)
 * - Полилиния трека (предрассчитанная)
 * - Трейл (накопленные точки)
 * - HUD-строки (пред-форматированные, zero-alloc в onDraw)
 */
public class DisplayFrame {

    // ===== Позиция =====
    public final double pilotLat, pilotLon;

    // ===== Высоты =====
    public final float altitudeMsl;     // m над уровнем моря
    public final float altitudeAgl;     // m над точкой старта
    public final float launchAltitude;  // m — первая точка трека

    // ===== Скорость =====
    public final float speedMs;          // m/s (ground speed)
    public final float speedKmh;         // km/h
    public final float varioMs;          // m/s (EMA smoothed)
    public final float avgVario30;       // m/s (30s среднее)

    // ===== Направление =====
    public final float headingDeg;       // направление движения (GPS track)

    // ===== Ветер =====
    public final float windFromDeg;      // meteo FROM
    public final float windSpeedMs;      // m/s

    // ===== L/D =====
    public final float glideRatio;       // ±99.0 кап
    public final float glideRangeKm;     // AGL × L/D / 1000
    public final boolean goingBack;

    // ===== Прогресс (для реплея) =====
    public final float progress;         // 0..1

    // ===== Статус полёта =====
    public final String statusText;      // "ПОИСК", "СПИРАЛЬ", "ТЕРМИК РЯДОМ", ...

    // ===== Термики (крутка) =====
    public final boolean isCircling;
    public final float thermalBearingDeg; // пеленг на центроид
    public final float thermalDistM;      // дистанция до центроида
    public final boolean showRedCore;     // красное ядро термика
    public final String guidanceText;     // "Крути! Ядро в X м"

    // ===== Термики (блипы) =====
    public final List<ThermalBlip> blips;

    // ===== Трек (полилиния, весь IGC) =====
    public final float[] trackPolyPx;
    public final float[] trackPolyPy;
    public final int trackPolyCount;

    // ===== Трейл (кольцевой буфер, 1Гц) =====
    public final float[] trailPx, trailPy;
    public final int[] trailColors;
    public final int trailCount;

    // ===== HUD (пред-форматированные строки) =====
    public final String speedStr;
    public final String varioStr;
    public final String avgVarioStr;
    public final String windStr;
    public final String altMslStr;
    public final String altAglStr;
    public final String flightTimeStr;   // "00:00"
    public final String glideRatioStr;
    public final String glideRangeStr;

    // ===== Батарея =====
    public final int batteryPct;
    public final int satelliteCount;

    // ===== Флаги =====
    public final boolean isReplay;
    public final boolean isRunning;

    // ========================================================================
    // Пустой frame (для инициализации)
    // ========================================================================
    public static final DisplayFrame EMPTY = new DisplayFrame(
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, false,
        0, "", false,
        0, 0, false, "",
        new java.util.ArrayList<>(),
        null, null, 0,
        null, null, null, 0,
        "", "", "", "", "", "", "", "", "",
        0, 0, false, false
    );

    // ========================================================================
    // Полный конструктор (все поля)
    // ========================================================================
    public DisplayFrame(double pilotLat, double pilotLon,
                        float altitudeMsl, float altitudeAgl, float launchAltitude,
                        float speedMs, float speedKmh, float varioMs, float avgVario30,
                        float headingDeg,
                        float windFromDeg, float windSpeedMs,
                        float glideRatio, float glideRangeKm, boolean goingBack,
                        float progress, String statusText,
                        boolean isCircling, float thermalBearingDeg, float thermalDistM,
                        boolean showRedCore, String guidanceText,
                        List<ThermalBlip> blips,
                        float[] trackPolyPx, float[] trackPolyPy, int trackPolyCount,
                        float[] trailPx, float[] trailPy, int[] trailColors, int trailCount,
                        String speedStr, String varioStr, String avgVarioStr,
                        String windStr, String altMslStr, String altAglStr,
                        String flightTimeStr, String glideRatioStr, String glideRangeStr,
                        int batteryPct, int satelliteCount,
                        boolean isReplay, boolean isRunning) {
        this.pilotLat = pilotLat;
        this.pilotLon = pilotLon;
        this.altitudeMsl = altitudeMsl;
        this.altitudeAgl = altitudeAgl;
        this.launchAltitude = launchAltitude;
        this.speedMs = speedMs;
        this.speedKmh = speedKmh;
        this.varioMs = varioMs;
        this.avgVario30 = avgVario30;
        this.headingDeg = headingDeg;
        this.windFromDeg = windFromDeg;
        this.windSpeedMs = windSpeedMs;
        this.glideRatio = glideRatio;
        this.glideRangeKm = glideRangeKm;
        this.goingBack = goingBack;
        this.progress = progress;
        this.statusText = statusText;
        this.isCircling = isCircling;
        this.thermalBearingDeg = thermalBearingDeg;
        this.thermalDistM = thermalDistM;
        this.showRedCore = showRedCore;
        this.guidanceText = guidanceText;
        this.blips = blips;
        this.trackPolyPx = trackPolyPx;
        this.trackPolyPy = trackPolyPy;
        this.trackPolyCount = trackPolyCount;
        this.trailPx = trailPx;
        this.trailPy = trailPy;
        this.trailColors = trailColors;
        this.trailCount = trailCount;
        this.speedStr = speedStr;
        this.varioStr = varioStr;
        this.avgVarioStr = avgVarioStr;
        this.windStr = windStr;
        this.altMslStr = altMslStr;
        this.altAglStr = altAglStr;
        this.flightTimeStr = flightTimeStr;
        this.glideRatioStr = glideRatioStr;
        this.glideRangeStr = glideRangeStr;
        this.batteryPct = batteryPct;
        this.satelliteCount = satelliteCount;
        this.isReplay = isReplay;
        this.isRunning = isRunning;
    }
}
