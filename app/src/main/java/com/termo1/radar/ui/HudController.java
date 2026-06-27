package com.termo1.radar.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.Locale;

/**
 * HudController — отрисовка приборной панели (скорость, варио, ветер, высоты, время).
 * Содержит собственные Paint'ы и кеш строк — без аллокаций в onDraw.
 * Выделен из MainActivity (рефакторинг).
 */
public class HudController {

    // ========================================================================
    // Data class
    // ========================================================================

    public static class HudData {
        public float displaySpeedKmh, gpsSpeed;
        public boolean goingBack;
        public float gpsAltVal, aglVal;
        public float varioVal, avgVario30;
        public float windDeg, windSpdMs;
        public long flightTimeMs;
        // Layout from MainActivity (set each frame)
        public float colX_left, colX_center, colX_right;
        public float valueRowY, instrLabelY;
    }

    // ========================================================================
    // Paints
    // ========================================================================

    private final Paint instrValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint instrLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint varioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flightTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ========================================================================
    // Cached HUD strings (MA-5: избегаем String.format в onDraw)
    // ========================================================================

    private float lastHudSpeedKmh = -1f;
    private String lastHudSpeedStr = "";
    private float lastHudVarioVal = -999f;
    private String lastHudVarioStr = "";
    private float lastHudWindSpd = -1f;
    private String lastHudWindStr = "";
    private float lastHudAltMsl = -1f;
    private String lastHudAltMslStr = "";
    private float lastHudAgl = -1f;
    private String lastHudAglStr = "";
    private float lastHudAvgVario = -999f;
    private String lastHudAvgVarioStr = "";
    private int lastFtSec = -1;
    private String lastFtStr = "";
    private final StringBuilder hudSb = new StringBuilder(32);

    // ========================================================================
    // Init
    // ========================================================================

    public HudController() {
        instrValuePaint.setAntiAlias(true);
        instrValuePaint.setTextSize(112);
        instrValuePaint.setTextAlign(Paint.Align.CENTER);
        instrValuePaint.setTypeface(Typeface.MONOSPACE);
        instrValuePaint.setFakeBoldText(true);

        instrLabelPaint.setAntiAlias(true);
        instrLabelPaint.setTextSize(32);
        instrLabelPaint.setTextAlign(Paint.Align.CENTER);
        instrLabelPaint.setTypeface(Typeface.MONOSPACE);

        varioPaint.setAntiAlias(true);
        varioPaint.setTextSize(200);
        varioPaint.setTextAlign(Paint.Align.CENTER);
        varioPaint.setTypeface(Typeface.MONOSPACE);
        varioPaint.setFakeBoldText(true);

        flightTimePaint.setAntiAlias(true);
        flightTimePaint.setTextSize(88);
        flightTimePaint.setTextAlign(Paint.Align.CENTER);
        flightTimePaint.setTypeface(Typeface.MONOSPACE);
        flightTimePaint.setFakeBoldText(true);
    }

    // ========================================================================
    // Drawing
    // ========================================================================

    public void drawInstruments(Canvas canvas, HudData d) {
        // === LEFT: Speed ===
        // Speed (км/ч) — cached
        hudSb.setLength(0);
        if (d.displaySpeedKmh != lastHudSpeedKmh) {
            hudSb.setLength(0);
            hudSb.append(String.format(Locale.US, "%.0f", d.displaySpeedKmh));
            lastHudSpeedStr = hudSb.toString();
            lastHudSpeedKmh = d.displaySpeedKmh;
        }
        instrValuePaint.setColor(d.goingBack
                ? ((System.currentTimeMillis() / 500) % 2 == 0
                    ? Color.argb(220, 255, 80, 80) : Color.argb(50, 255, 80, 80))
                : Color.argb(220, 0, 255, 0));
        canvas.drawText(lastHudSpeedStr, d.colX_left, d.valueRowY + 20, instrValuePaint);

        // m/s мелко под км/ч
        instrLabelPaint.setTextSize(20);
        instrLabelPaint.setColor(Color.argb(120, 0, 255, 0));
        hudSb.setLength(0);
        hudSb.append(String.format(Locale.US, "%.1f м/с", d.gpsSpeed));
        canvas.drawText(hudSb.toString(), d.colX_left, d.valueRowY + 20 + 28, instrLabelPaint);
        instrLabelPaint.setTextSize(32);

        // label "скорость, км/ч"
        instrLabelPaint.setColor(Color.argb(140, 0, 255, 0));
        canvas.drawText("скорость, км/ч", d.colX_left, d.valueRowY - 70, instrLabelPaint);

        // MSL
        instrValuePaint.setColor(Color.argb(200, 0, 200, 255));
        hudSb.setLength(0);
        if (d.gpsAltVal != lastHudAltMsl) {
            hudSb.setLength(0);
            hudSb.append(String.format(Locale.US, "%.0f", d.gpsAltVal));
            lastHudAltMslStr = hudSb.toString();
            lastHudAltMsl = d.gpsAltVal;
        }
        canvas.drawText(lastHudAltMslStr, d.colX_left, d.instrLabelY + 130, instrValuePaint);
        instrLabelPaint.setColor(Color.argb(140, 0, 200, 255));
        canvas.drawText("высота MSL", d.colX_left, d.instrLabelY + 162, instrLabelPaint);

        // === CENTER: Vario ===
        // Vario value
        varioPaint.setColor(d.varioVal > 0.5f ? Color.argb(255, 255, 80, 80)
                : d.varioVal < -0.5f ? Color.argb(255, 100, 200, 100)
                : Color.argb(200, 255, 180, 50));
        hudSb.setLength(0);
        if (d.varioVal != lastHudVarioVal) {
            hudSb.setLength(0);
            String sign = d.varioVal >= 0 ? "+" : "";
            hudSb.append(String.format(Locale.US, "%s%.1f", sign, d.varioVal));
            lastHudVarioStr = hudSb.toString();
            lastHudVarioVal = d.varioVal;
        }
        canvas.drawText(lastHudVarioStr, d.colX_center, d.valueRowY, varioPaint);

        // Avg vario 30с
        instrLabelPaint.setColor(Color.argb(140, 255, 180, 50));
        instrLabelPaint.setTextSize(28);
        instrLabelPaint.setTextAlign(Paint.Align.CENTER);
        hudSb.setLength(0);
        if (d.avgVario30 != lastHudAvgVario) {
            hudSb.setLength(0);
            hudSb.append(String.format(Locale.US, "avg %s%.1f", d.avgVario30 >= 0 ? "+" : "", d.avgVario30));
            lastHudAvgVarioStr = hudSb.toString();
            lastHudAvgVario = d.avgVario30;
        }
        canvas.drawText(lastHudAvgVarioStr, d.colX_center, d.valueRowY + 40, instrLabelPaint);

        // Flight time
        long ftSec = d.flightTimeMs / 1000;
        hudSb.setLength(0);
        if (ftSec != lastFtSec) {
            hudSb.setLength(0);
            hudSb.append(String.format("%02d:%02d:%02d", ftSec / 3600, (ftSec % 3600) / 60, ftSec % 60));
            lastFtStr = hudSb.toString();
            lastFtSec = (int) ftSec;
        }
        flightTimePaint.setColor(Color.argb(200, 0, 255, 255));
        canvas.drawText(lastFtStr, d.colX_center, d.valueRowY + 150, flightTimePaint);

        // === RIGHT: Wind + AGL ===
        if (d.windDeg >= 0 && d.windSpdMs > 0) {
            instrLabelPaint.setColor(Color.argb(160, 100, 200, 255));
            canvas.drawText("ветер, м/с", d.colX_right, d.valueRowY - 70, instrLabelPaint);
            instrValuePaint.setColor(d.windSpdMs > 12f
                    ? Color.argb(220, 255, 80, 80) : Color.argb(220, 100, 200, 255));
            hudSb.setLength(0);
            if (d.windSpdMs != lastHudWindSpd) {
                hudSb.setLength(0);
                hudSb.append(String.format(Locale.US, "%.1f", d.windSpdMs));
                lastHudWindStr = hudSb.toString();
                lastHudWindSpd = d.windSpdMs;
            }
            canvas.drawText(lastHudWindStr, d.colX_right, d.valueRowY + 20, instrValuePaint);
        } else {
            instrLabelPaint.setColor(Color.argb(120, 100, 200, 255));
            canvas.drawText("ветер, м/с", d.colX_right, d.valueRowY - 70, instrLabelPaint);
            instrValuePaint.setColor(Color.argb(120, 100, 200, 255));
            canvas.drawText("--", d.colX_right, d.valueRowY + 20, instrValuePaint);
        }

        // AGL
        instrValuePaint.setColor(Color.argb(200, 0, 200, 255));
        hudSb.setLength(0);
        float aglClamped = Math.max(0, d.aglVal);
        if (aglClamped != lastHudAgl) {
            hudSb.setLength(0);
            hudSb.append(String.format(Locale.US, "+%.0f", aglClamped));
            lastHudAglStr = hudSb.toString();
            lastHudAgl = aglClamped;
        }
        canvas.drawText(lastHudAglStr, d.colX_right, d.instrLabelY + 130, instrValuePaint);
        instrLabelPaint.setColor(Color.argb(140, 0, 200, 255));
        canvas.drawText("AGL", d.colX_right, d.instrLabelY + 162, instrLabelPaint);
    }
}
