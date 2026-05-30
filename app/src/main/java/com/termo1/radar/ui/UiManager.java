package com.termo1.radar.ui;

import android.graphics.*;
import com.termo1.radar.model.ThermalBlip;
import java.util.List;

/**
 * HUD overlay drawn on top of the radar canvas.
 * Variometer, status pill, info panel.
 * Supports multiple color schemes.
 */
public class UiManager {

    // === COLOR SCHEMES ===
    public static final int SCHEME_DARK          = 0; // black bg, green lines (current)
    public static final int SCHEME_LIGHT         = 1; // white bg, dark lines (sun readability)
    public static final int SCHEME_HIGH_CONTRAST = 2; // max contrast

    private int colorScheme = SCHEME_DARK;
    private float density = 1.0f;

    /** Установить плотность экрана для масштабирования шрифтов */
    public void setDensity(float d) {
        this.density = d;
        applyTextSizes();
    }

    /** Пересчитать размеры шрифтов под текущую плотность */
    private void applyTextSizes() {
        varioPaint.setTextSize(110f * density);
        varioUnitPaint.setTextSize(33f * density);
        altMslPaint.setTextSize(90f * density);
        altAglPaint.setTextSize(90f * density);
        flightTimePaint.setTextSize(75f * density);
        sysTimePaint.setTextSize(65f * density);
    }

    // === VARIOMETER ===
    private final Paint varioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint varioUnitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint varioGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // === FLIGHT TIME ===
    private final Paint flightTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // === SYSTEM TIME ===
    private final Paint sysTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // === ALTITUDE ===
    private final Paint altMslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altAglPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // === STATUS PILL ===
    private final Paint statusBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF statusBgRect = new RectF();

    // === INFO PANEL ===
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // status definitions
    public static final String STATUS_SEARCH  = "ПОИСК";
    public static final String STATUS_THERMAL = "ТЕРМИК РЯДОМ";
    public static final String STATUS_CLIMB   = "НАБОР";
    public static final String STATUS_SINK    = "СНИЖЕНИЕ";
    public static final String STATUS_SPIRAL  = "СПИРАЛЬ";

    // scheme-adapted color constants
    private int colorGreen;
    private int colorYellow;
    private int colorRed;
    private int colorBlue;
    private int colorTextMuted;

    public UiManager() {
        varioPaint.setAntiAlias(true);
        varioPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        varioPaint.setTextAlign(Paint.Align.CENTER);

        varioUnitPaint.setAntiAlias(true);
        varioUnitPaint.setTypeface(Typeface.MONOSPACE);
        varioUnitPaint.setTextAlign(Paint.Align.LEFT);

        varioGlowPaint.setAntiAlias(true);
        varioGlowPaint.setStyle(Paint.Style.FILL);

        // altitude — MSL (right-aligned) 90dp bold monospace green
        altMslPaint.setAntiAlias(true);
        altMslPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        altMslPaint.setTextAlign(Paint.Align.RIGHT);
        altMslPaint.setColor(Color.argb(220, 76, 175, 80));
        altMslPaint.setShadowLayer(8, 0, 0, Color.argb(120, 76, 175, 80));

        // altitude — AGL (left-aligned) 90dp bold monospace green
        altAglPaint.setAntiAlias(true);
        altAglPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        altAglPaint.setTextAlign(Paint.Align.LEFT);
        altAglPaint.setColor(Color.argb(220, 76, 175, 80));
        altAglPaint.setShadowLayer(8, 0, 0, Color.argb(120, 76, 175, 80));

        // flight time — 75dp bold monospace green with shadow
        flightTimePaint.setAntiAlias(true);
        flightTimePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        flightTimePaint.setTextAlign(Paint.Align.CENTER);
        flightTimePaint.setColor(Color.argb(220, 76, 175, 80));
        flightTimePaint.setShadowLayer(8, 0, 0, Color.argb(120, 76, 175, 80));

        // system time — 65dp bold monospace, dim green (alpha ~0.4), no shadow
        sysTimePaint.setAntiAlias(true);
        sysTimePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        sysTimePaint.setTextAlign(Paint.Align.CENTER);
        sysTimePaint.setColor(Color.argb(100, 76, 175, 80));

        // status pill
        statusTextPaint.setAntiAlias(true);
        statusTextPaint.setTextSize(28);
        statusTextPaint.setTypeface(Typeface.MONOSPACE);
        statusTextPaint.setTextAlign(Paint.Align.CENTER);

        statusBgPaint.setStyle(Paint.Style.FILL);
        statusBgPaint.setAntiAlias(true);

        // info panel
        infoPaint.setAntiAlias(true);
        infoPaint.setTextSize(22);
        infoPaint.setTypeface(Typeface.MONOSPACE);

        applySchemeColors();
    }

    /** Set the color scheme and refresh all paint colors. */
    public void setColorScheme(int scheme) {
        if (scheme < SCHEME_DARK || scheme > SCHEME_HIGH_CONTRAST) {
            scheme = SCHEME_DARK;
        }
        colorScheme = scheme;
        applySchemeColors();
    }

    public int getColorScheme() {
        return colorScheme;
    }

    /** Apply colors for the current scheme. */
    private void applySchemeColors() {
        switch (colorScheme) {
            case SCHEME_LIGHT:
                // white bg, dark lines (sun readability)
                colorGreen      = Color.argb(255, 0, 100, 0);   // dark green
                colorYellow     = Color.argb(255, 180, 120, 0); // dark amber
                colorRed        = Color.argb(255, 180, 0, 0);   // dark red
                colorBlue       = Color.argb(255, 0, 70, 180);  // dark blue
                colorTextMuted  = Color.argb(120, 50, 50, 50);  // grey
                varioUnitPaint.setColor(Color.argb(120, 50, 50, 50));
                infoPaint.setColor(Color.argb(120, 50, 50, 50));
                altMslPaint.setColor(colorGreen);
                altMslPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                altAglPaint.setColor(colorGreen);
                altAglPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                break;
            case SCHEME_HIGH_CONTRAST:
                // black bg, bright green, thick lines, bold text
                colorGreen      = Color.argb(255, 0, 255, 0);   // full green
                colorYellow     = Color.argb(255, 255, 255, 0); // full yellow
                colorRed        = Color.argb(255, 255, 0, 0);   // full red
                colorBlue       = Color.argb(255, 0, 150, 255); // bright blue
                colorTextMuted  = Color.argb(200, 0, 255, 0);   // green
                varioUnitPaint.setColor(Color.argb(200, 0, 255, 0));
                infoPaint.setColor(Color.argb(200, 0, 255, 0));
                altMslPaint.setColor(colorGreen);
                altMslPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                altAglPaint.setColor(colorGreen);
                altAglPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                varioPaint.setFakeBoldText(true);
                statusTextPaint.setFakeBoldText(true);
                infoPaint.setFakeBoldText(true);
                break;
            default: // SCHEME_DARK
                // black bg, green lines (current default)
                colorGreen      = Color.argb(200, 76, 175, 80);
                colorYellow     = Color.argb(200, 255, 193, 7);
                colorRed        = Color.argb(200, 244, 67, 54);
                colorBlue       = Color.argb(200, 33, 150, 243);
                colorTextMuted  = Color.argb(70, 255, 255, 255);
                varioUnitPaint.setColor(Color.argb(50, 255, 255, 255));
                infoPaint.setColor(Color.argb(70, 255, 255, 255));
                altMslPaint.setColor(colorGreen);
                altMslPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                altAglPaint.setColor(colorGreen);
                altAglPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
                varioPaint.setFakeBoldText(false);
                statusTextPaint.setFakeBoldText(false);
                infoPaint.setFakeBoldText(false);
                break;
        }
    }

    /** Draw everything that's not the radar. */
    public void drawFrame(Canvas c, int w, int h) {
        // nothing global needed — individual draw methods called by MainActivity
    }

    /**
     * Draw variometer value centered at (cx, y).
     * Green (+) for climb, red (-) for sink.
     * @param y  visual center Y for the vario text
     */
    public void drawVario(Canvas c, float cx, float y, float vario) {
        if (Float.isNaN(vario) || Float.isInfinite(vario)) vario = 0f;

        boolean isUp = vario >= 0;
        int color = isUp ? colorGreen : colorRed;

        String text = String.format("%s%.1f", isUp ? "+" : "", vario);

        // glow centered on 220px text
        varioGlowPaint.setShadowLayer(15, 0, 0, color);
        varioGlowPaint.setColor(Color.TRANSPARENT);
        c.drawCircle(cx, y + 15, 20, varioGlowPaint);

        // text — baseline offset for 110px font
        varioPaint.setColor(color);
        c.drawText(text, cx, y + 28, varioPaint);

        // "м/с" label
        c.drawText("м/с", cx + 140, y + 30, varioUnitPaint);
    }

    /**
     * Draw a colored status pill at top center.
     *   ПОИСК      — green
     *   ТЕРМИК РЯДОМ — yellow
     *   НАБОР      — green
     *   СНИЖЕНИЕ   — red
     *   СПИРАЛЬ    — blue
     */
    public void drawStatus(Canvas c, float cx, String status) {
        int bgColor;
        if (STATUS_SEARCH.equals(status)) {
            bgColor = colorGreen;
        } else if (STATUS_THERMAL.equals(status)) {
            bgColor = colorYellow;
        } else if (STATUS_CLIMB.equals(status)) {
            bgColor = colorGreen;
        } else if (STATUS_SINK.equals(status)) {
            bgColor = colorRed;
        } else if (STATUS_SPIRAL.equals(status)) {
            bgColor = colorBlue;
        } else {
            bgColor = colorTextMuted;
        }
        // make bg semi-transparent
        bgColor = Color.argb(60, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor));

        float textWidth = statusTextPaint.measureText(status);
        float padH = 24;
        float padV = 10;
        float rectW = textWidth + padH * 2;
        float rectH = 36 + padV * 2;
        float left = cx - rectW / 2f;
        float top = 30;

        statusBgRect.set(left, top, left + rectW, top + rectH);
        statusBgPaint.setColor(bgColor);
        c.drawRoundRect(statusBgRect, 18, 18, statusBgPaint);

        int textColor;
        switch (status) {
            case STATUS_THERMAL: textColor = colorYellow; break;
            case STATUS_SINK:    textColor = colorRed;    break;
            case STATUS_SPIRAL:  textColor = colorBlue;   break;
            default:             textColor = colorGreen;  break;
        }
        statusTextPaint.setColor(textColor);
        c.drawText(status, cx, top + rectH / 2f + 12, statusTextPaint);
    }

    /**
     * Draw info panel at bottom-left.
     * Format: '±X.X м/с² | SNR: X.X | N' (basic)
     */
    public void drawInfo(Canvas c, float left, float bottom,
                         float vario, float snr, int count) {
        if (Float.isNaN(vario)) vario = 0f;
        if (Float.isNaN(snr)) snr = 0f;

        String info = String.format("%s%.1f м/с² | SNR: %.1f | %d",
                vario >= 0 ? "+" : "", vario, snr, count);
        c.drawText(info, left + 12, bottom - 18, infoPaint);
    }

    /**
     * Draw extended info panel (for real-time detector mode).
     * Shows: turbulence level, SNR, count, HPX/HPY, direction, status.
     */
    public void drawInfo(Canvas c, float left, float bottom,
                         float turbulenceMs2, float snr, int count,
                         float hpX, float hpY, float dirDeg, String status) {
        if (Float.isNaN(turbulenceMs2)) turbulenceMs2 = 0f;
        if (Float.isNaN(snr)) snr = 0f;

        // First line: status + turbulence level
        String line1 = String.format("%s | %.2f м/с² | SNR: %.1f | %d",
                status, turbulenceMs2, snr, count);
        c.drawText(line1, left + 12, bottom - 42, infoPaint);

        // Second line: HPX, HPY, direction
        if (!Float.isNaN(hpX) && !Float.isNaN(hpY) && !Float.isNaN(dirDeg)) {
            String line2 = String.format("HP: %.3f/%.3f | %03.0f°",
                    hpX, hpY, dirDeg);
            c.drawText(line2, left + 12, bottom - 18, infoPaint);
        }
    }

    /**
     * Draw elapsed flight time at the given position in format "+HH:MM:SS".
     * Green monospace 150px bold with shadow.
     * @param elapsedSec  elapsed time in seconds
     */
    public void drawFlightTime(Canvas c, float cx, float y, long elapsedSec) {
        long hours = elapsedSec / 3600;
        long minutes = (elapsedSec % 3600) / 60;
        long seconds = elapsedSec % 60;
        String text = String.format("+%02d:%02d:%02d", hours, minutes, seconds);
        c.drawText(text, cx, y, flightTimePaint);
    }

    /**
     * Draw current system time at the given position in format "HH:MM:SS".
     * Dim green monospace 130px bold, no shadow.
     */
    public void drawSystemTime(Canvas c, float cx, float y) {
        String text = String.format("%tT", new java.util.Date());
        c.drawText(text, cx, y, sysTimePaint);
    }

    /**
     * Draw altitude info left/right.
     * Left: AGL "XXXm" (above ground level, left-aligned at leftX)
     * Right: MSL "XXXm" (above sea level, right-aligned at rightX)
     * Bold monospace 180px green with shadow glow.
     */
    public void drawAltitude(Canvas c, float leftX, float rightX, float centerY, float altMsl, float altAgl) {
        // AGL on the left (left-aligned)
        altAglPaint.setColor(colorGreen);
        altAglPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
        c.drawText(String.format("%.0fм", altAgl), leftX, centerY, altAglPaint);

        // MSL on the right (right-aligned)
        altMslPaint.setColor(colorGreen);
        altMslPaint.setShadowLayer(8, 0, 0, Color.argb(120, Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)));
        c.drawText(String.format("%.0fм", altMsl), rightX, centerY, altMslPaint);
    }

    // === NIGHT MODE ===
    private final Paint nightFilterPaint = new Paint();
    private boolean nightMode = false;

    /** Set night mode (red filter overlay). */
    public void setNightMode(boolean on) {
        nightMode = on;
    }

    /**
     * Draw a night mode red-tint overlay over the entire canvas.
     * Call this last, after everything else.
     */
    public void drawNightFilter(Canvas c, int w, int h) {
        if (!nightMode) return;
        nightFilterPaint.setColor(Color.argb(60, 255, 0, 0));
        c.drawRect(0, 0, w, h, nightFilterPaint);
    }
}
