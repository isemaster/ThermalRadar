package com.termo1.radar.ui;

import android.graphics.*;
import com.termo1.radar.model.ThermalBlip;
import java.util.List;

/**
 * Renders the radar compass view on Canvas.
 * Black background, green lines, yellow thermal markers.
 * The entire radar rotates by -heading so N always points north.
 */
public class RadarRenderer {

    // geometry — set via onSizeChanged
    private int baseW, baseH;
    private float cx, cy, r;

    // paints
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pilotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pilotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG); // shadow cached once
    private final Paint pilotPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG); // shadow cached once
    private final Paint thermalStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nsewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Wind arrow (cached paints — не создаём в onDraw)
    private final Paint windLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float windFromDeg = -1f;
    private float windSpeedMs = -1f;

    // GPS trail — тонкая жёлтая линия траектории
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Прозрачная тень для траектории (свечение)
    private final Paint trailGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Thermal core — красный круг при крутке термика
    private final Paint thermalCoreFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalCoreStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalCoreGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showThermalCore;
    private float thermalCoreBearing;
    private float thermalCoreDistance;
    private float thermalCoreRadiusPx; // radius in pixels

    private final DashPathEffect dash125 = new DashPathEffect(new float[]{6, 8}, 0);
    private final DashPathEffect thermalDash = new DashPathEffect(new float[]{3, 5}, 0);

    // thermal max distance in pixels (5/6 r ≈ 125m ring)
    private float thermalMaxDist;

    public RadarRenderer() {
        // border — white frame alpha 0.45
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);
        borderPaint.setColor(Color.argb(115, 255, 255, 255));

        // rings
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1);

        // dashed ring (125m)
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(1);
        dashPaint.setPathEffect(dash125);

        // cross
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1);
        crossPaint.setColor(Color.argb(12, 0, 255, 0));

        // pilot
        pilotFillPaint.setStyle(Paint.Style.FILL);
        pilotFillPaint.setColor(Color.GREEN);

        pilotGlowPaint.setStyle(Paint.Style.FILL);
        pilotGlowPaint.setColor(Color.GREEN);
        pilotGlowPaint.setShadowLayer(18, 0, 0, Color.GREEN);

        pilotPulsePaint.setStyle(Paint.Style.STROKE);
        pilotPulsePaint.setStrokeWidth(1.5f);

        // thermal glow
        thermalGlowPaint.setStyle(Paint.Style.FILL);
        thermalGlowPaint.setShadowLayer(40, 0, 0, Color.argb(255, 255, 193, 7));

        // thermal fill
        thermalFillPaint.setStyle(Paint.Style.FILL);

        // thermal stroke
        thermalStrokePaint.setStyle(Paint.Style.STROKE);
        thermalStrokePaint.setStrokeWidth(2);

        // dashed line to thermal
        dashedLinePaint.setStyle(Paint.Style.STROKE);
        dashedLinePaint.setStrokeWidth(1);
        dashedLinePaint.setPathEffect(thermalDash);

        // card text (labels on rings)
        cardTextPaint.setAntiAlias(true);
        cardTextPaint.setTextSize(22);
        cardTextPaint.setTypeface(Typeface.MONOSPACE);

        // N/S/W/E — 72px bold monospace (×3 от 24)
        nsewPaint.setAntiAlias(true);
        nsewPaint.setTextSize(72);
        nsewPaint.setTextAlign(Paint.Align.CENTER);
        nsewPaint.setTypeface(Typeface.MONOSPACE);
        nsewPaint.setFakeBoldText(true);

        // wind arrow paints
        windLinePaint.setStyle(Paint.Style.STROKE);
        windLinePaint.setStrokeWidth(15);
        windLinePaint.setColor(Color.argb(200, 100, 200, 255));
        windLinePaint.setStrokeCap(Paint.Cap.ROUND);
        windLinePaint.setStrokeJoin(Paint.Join.ROUND);
        windLinePaint.setShadowLayer(6, 0, 0, Color.argb(80, 100, 200, 255));

        windFillPaint.setStyle(Paint.Style.FILL);
        windFillPaint.setColor(Color.argb(200, 100, 200, 255));

        windLabelPaint.setAntiAlias(true);
        windLabelPaint.setColor(Color.argb(200, 100, 200, 255));
        windLabelPaint.setTextSize(22);
        windLabelPaint.setTypeface(Typeface.MONOSPACE);
        windLabelPaint.setTextAlign(Paint.Align.CENTER);

        // GPS trail paint — тонкая жёлтая линия
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(2.5f);
        trailPaint.setColor(Color.argb(180, 255, 235, 59)); // жёлтый (Amber 500)
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        // Свечение для трека
        trailGlowPaint.setStyle(Paint.Style.STROKE);
        trailGlowPaint.setStrokeWidth(5f);
        trailGlowPaint.setColor(Color.argb(60, 255, 235, 59));
        trailGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        // Thermal core — красный круг (ядро термика)
        thermalCoreGlowPaint.setStyle(Paint.Style.FILL);
        thermalCoreGlowPaint.setColor(Color.RED);
        thermalCoreGlowPaint.setShadowLayer(60, 0, 0, Color.RED);

        thermalCoreFillPaint.setStyle(Paint.Style.FILL);
        thermalCoreFillPaint.setColor(Color.argb(80, 255, 50, 50));

        thermalCoreStrokePaint.setStyle(Paint.Style.STROKE);
        thermalCoreStrokePaint.setStrokeWidth(3);
        thermalCoreStrokePaint.setColor(Color.argb(220, 255, 50, 50));
        thermalCoreStrokePaint.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));
    }

    /** Called when view size changes. */
    public void onSizeChanged(int w, int h) {
        baseW = w;
        baseH = h;
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(cx, cy) - 4;
        thermalMaxDist = r * 5f / 6f;
    }

    /** Set wind data for the wind arrow. Call before draw(). */
    public void setWindData(float fromDeg, float speedMs) {
        this.windFromDeg = fromDeg;
        this.windSpeedMs = speedMs;
    }

    /** Set thermal core data for the red circle. Call before draw(). */
    public void setThermalCore(boolean show, float bearing, float distance, float radiusMeters) {
        this.showThermalCore = show;
        this.thermalCoreBearing = bearing;
        this.thermalCoreDistance = distance;
        // convert meters to pixels: 150m = r px
        this.thermalCoreRadiusPx = (radiusMeters / 150f) * r;
    }

    /**
     * Main draw call. Rotates entire canvas by -heading.
     * @param canvas  the Canvas to draw on
     * @param nowMs   current System.currentTimeMillis()
     * @param thermals list of active ThermalBlip
     * @param heading compass heading in degrees (0=N, 90=E)
     * @param vario   vertical speed in m/s
     * @param status  status string for debugging
     * @param maxSnr  current max SNR
     * @param count   thermal count
     * @param trailPx  X pixel positions of GPS trail (in radar coordinates)
     * @param trailPy  Y pixel positions of GPS trail
     * @param trailBright  brightness 0..1 for each trail point (by age)
     * @param trailCount  number of valid trail points
     */
    public void draw(Canvas canvas, long nowMs, List<ThermalBlip> thermals,
                     float heading, float vario, String status,
                     float maxSnr, int count,
                     float[] trailPx, float[] trailPy, float[] trailBright, int trailCount) {
        if (baseW <= 0 || baseH <= 0) return;

        // --- black background ---
        canvas.drawColor(Color.rgb(10, 10, 10));

        // --- rotate around pilot (-heading so N always north) ---
        canvas.save();
        canvas.rotate(-heading, cx, cy);

        drawRings(canvas);
        drawCross(canvas);
        drawCardinalPoints(canvas);
        drawTickMarks(canvas);
        drawThermalCore(canvas);
        drawTrail(canvas, trailPx, trailPy, trailBright, trailCount);
        drawThermals(canvas, nowMs, thermals);
        drawPilot(canvas, nowMs);
        drawWindArrow(canvas);

        canvas.restore(); // restore un-rotated state

        // --- border frame (un-rotated) ---
        canvas.drawRect(1, 1, baseW - 2, baseH - 2, borderPaint);
    }

    // ===== PRIVATE HELPERS =====

    private void drawRings(Canvas c) {
        // 1/3 r (50m) — толщина как внешний круг
        ringPaint.setColor(Color.argb(20, 0, 255, 0));
        ringPaint.setStrokeWidth(2);
        c.drawCircle(cx, cy, r / 3f, ringPaint);
        cardTextPaint.setColor(Color.argb(70, 0, 255, 0));
        c.drawText("50м", cx + 5, cy - r / 3f - 5, cardTextPaint);

        // 2/3 r (100m)
        c.drawCircle(cx, cy, r * 2f / 3f, ringPaint);
        c.drawText("100м", cx + 5, cy - r * 2f / 3f - 5, cardTextPaint);

        // 5/6 r (125m) — dashed
        dashPaint.setStrokeWidth(2);
        dashPaint.setColor(Color.argb(15, 0, 255, 0));
        c.drawCircle(cx, cy, thermalMaxDist, dashPaint);
        c.drawText("125м", cx + 5, cy - thermalMaxDist - 5, cardTextPaint);

        // outer boundary r (150m) — solid
        ringPaint.setColor(Color.argb(90, 0, 255, 0));
        ringPaint.setStrokeWidth(2);
        c.drawCircle(cx, cy, r, ringPaint);
    }

    private void drawCross(Canvas c) {
        // Линии N-S и W-E — толщина как у круга
        crossPaint.setStrokeWidth(2);
        crossPaint.setColor(Color.argb(40, 0, 255, 0));
        c.drawLine(cx - r, cy, cx + r, cy, crossPaint);          // W-E
        c.drawLine(cx, cy - r, cx, cy + r, crossPaint);          // N-S
        // Диагонали 45°
        float d = (float) (r * 0.70710678118); // r / sqrt(2)
        c.drawLine(cx - d, cy - d, cx + d, cy + d, crossPaint); // NW-SE
        c.drawLine(cx - d, cy + d, cx + d, cy - d, crossPaint); // NE-SW
        crossPaint.setStrokeWidth(1);
    }

    private void drawCardinalPoints(Canvas c) {
        float offset = 70; // увеличен для 72px шрифта
        nsewPaint.setColor(Color.argb(70, 0, 255, 0));
        c.drawText("N", cx, cy - r + offset, nsewPaint);
        c.drawText("S", cx, cy + r - 10, nsewPaint);
        c.drawText("W", cx - r + offset - 10, cy + 8, nsewPaint);
        c.drawText("E", cx + r - offset + 10, cy + 8, nsewPaint);
    }

    /** Градусные метки каждые 15° на внешнем круге. */
    private void drawTickMarks(Canvas c) {
        ringPaint.setColor(Color.argb(40, 0, 255, 0));
        ringPaint.setStrokeWidth(1);
        cardTextPaint.setTextAlign(Paint.Align.CENTER);
        cardTextPaint.setTextSize(32);
        cardTextPaint.setColor(Color.argb(60, 0, 255, 0));
        for (int deg = 0; deg < 360; deg += 15) {
            float rad = (float) Math.toRadians(deg);
            float innerR = r - 8f;
            float x1 = cx + (float) Math.sin(rad) * innerR;
            float y1 = cy - (float) Math.cos(rad) * innerR;
            float x2 = cx + (float) Math.sin(rad) * r;
            float y2 = cy - (float) Math.cos(rad) * r;
            c.drawLine(x1, y1, x2, y2, ringPaint);
            // Числа каждые 30°, кроме кратных 90 (там N/S/W/E)
            if (deg % 30 == 0 && deg % 90 != 0) {
                float labelR = r + 14f;
                float lx = cx + (float) Math.sin(rad) * labelR;
                float ly = cy - (float) Math.cos(rad) * labelR;
                c.drawText(String.valueOf(deg), lx, ly + 5, cardTextPaint);
            }
        }
        cardTextPaint.setTextAlign(Paint.Align.LEFT);
        cardTextPaint.setTextSize(22);
    }

    private void drawPilot(Canvas c, long nowMs) {
        // glow (shadow cached — не переустанавливаем каждый кадр)
        c.drawCircle(cx, cy, 10, pilotGlowPaint);
        // solid dot (без shadow — отдельный Paint)
        c.drawCircle(cx, cy, 7, pilotFillPaint);

        // pulse ring: 7 + 3*sin(t/300)
        float pulse = 7 + (float) Math.sin(nowMs / 300.0) * 3;
        pilotPulsePaint.setColor(Color.argb(50, 0, 255, 0));
        c.drawCircle(cx, cy, pulse, pilotPulsePaint);
    }

    private void drawWindArrow(Canvas c) {
        if (windFromDeg < 0 || windSpeedMs <= 0) return;

        double aRad = Math.toRadians(windFromDeg);
        // Точка на краю радара в направлении, ОТКУДА дует ветер
        float ex = cx + (float)(r * Math.sin(aRad));
        float ey = cy - (float)(r * Math.cos(aRad));
        // Внутренняя точка (короче в 3 раза: 0.7r/3 ≈ 0.23r от края)
        // 0.767r от центра = r - 0.23r
        float innerDist = r * 0.767f;
        float ix = cx + (float)(innerDist * Math.sin(aRad));
        float iy = cy - (float)(innerDist * Math.cos(aRad));

        c.drawLine(ex, ey, ix, iy, windLinePaint);

        // Наконечник стрелки (толще пропорционально)
        float tipAngle = 0.6f;
        float tipLen = 20f;
        float dx = cx - ex;
        float dy = cy - ey;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len > 1f) {
            float ux = dx / len;
            float uy = dy / len;
            float px = ix;
            float py = iy;
            float ax = px + tipLen * (float)(Math.cos(Math.PI - tipAngle) * ux - Math.sin(Math.PI - tipAngle) * uy);
            float ay = py + tipLen * (float)(Math.sin(Math.PI - tipAngle) * ux + Math.cos(Math.PI - tipAngle) * uy);
            float bx = px + tipLen * (float)(Math.cos(Math.PI + tipAngle) * ux - Math.sin(Math.PI + tipAngle) * uy);
            float by = py + tipLen * (float)(Math.sin(Math.PI + tipAngle) * ux + Math.cos(Math.PI + tipAngle) * uy);

            Path arrowPath = new Path();
            arrowPath.moveTo(px, py);
            arrowPath.lineTo(ax, ay);
            arrowPath.lineTo(bx, by);
            arrowPath.close();
            c.drawPath(arrowPath, windFillPaint);
        }

        // Подпись скорости ветра снаружи радара (в rotated системе!)
        float lx = cx + (float)((r + 50) * Math.sin(aRad));
        float ly = cy - (float)((r + 50) * Math.cos(aRad));
        // Не даём вылезти за край экрана
        if (lx < 30) lx = 30; if (lx > baseW - 30) lx = baseW - 30;
        if (ly < 30) ly = 30; if (ly > baseH - 10) ly = baseH - 10;
        c.drawText(String.format(java.util.Locale.US, "ветер %.1fм/с", windSpeedMs), lx, ly, windLabelPaint);
    }

    /** Рисование трека полёта — тонкая жёлтая линия по точкам GPS. */
    /** Рисование ядра термика — красный круг с пульсацией. */
    private void drawThermalCore(Canvas c) {
        if (!showThermalCore || thermalCoreDistance < 0) return;

        // Pixel position from bearing/distance
        float distPx = Math.min(thermalCoreDistance / 150f, 1f) * r;
        float rad = (float) Math.toRadians(thermalCoreBearing);
        float px = cx + (float) Math.sin(rad) * distPx;
        float py = cy - (float) Math.cos(rad) * distPx;

        if (distPx > r) return; // outside radar

        float radius = Math.max(thermalCoreRadiusPx, 10f);

        // Glow
        thermalCoreGlowPaint.setAlpha(120);
        c.drawCircle(px, py, radius * 2.5f, thermalCoreGlowPaint);

        // Fill (semi-transparent red)
        c.drawCircle(px, py, radius, thermalCoreFillPaint);

        // Dashed border
        c.drawCircle(px, py, radius, thermalCoreStrokePaint);

        // Central dot
        thermalCoreStrokePaint.setPathEffect(null);
        thermalCoreStrokePaint.setStrokeWidth(2);
        thermalCoreStrokePaint.setColor(Color.argb(200, 255, 50, 50));
        c.drawCircle(px, py, 4, thermalCoreStrokePaint);
    }

    private void drawTrail(Canvas c, float[] px, float[] py, float[] bright, int count) {
        if (count < 2) return;

        // Сначала свечение (более толстая прозрачная линия под основной)
        for (int i = 1; i < count; i++) {
            // Яркость сегмента = меньшая из двух точек (старая тусклее)
            float b = Math.min(bright[i-1], bright[i]);
            if (b < 0.02f) continue;
            int a = (int) (b * 60); // свечение: alpha до 60
            trailGlowPaint.setAlpha(a);
            c.drawLine(px[i-1], py[i-1], px[i], py[i], trailGlowPaint);
        }

        // Основная жёлтая линия — яркость от возраста точки
        for (int i = 1; i < count; i++) {
            float b = Math.min(bright[i-1], bright[i]);
            if (b < 0.02f) continue;
            int a = (int) (b * 200); // основная: alpha до 200
            trailPaint.setAlpha(Math.min(255, a));
            c.drawLine(px[i-1], py[i-1], px[i], py[i], trailPaint);
        }
    }

    private void drawThermals(Canvas c, long nowMs, List<ThermalBlip> thermals) {
        for (ThermalBlip t : thermals) {
            float brightness = t.getBrightness(nowMs);
            if (brightness <= 0f) continue;

            // calculate pixel position from angle and distance
            // map distance [0..150m] to [0..thermalMaxDist] pixels
            float distPx = Math.min(t.distance / 150f, 1f) * thermalMaxDist;
            // ensure minimum offset so it's not in the center
            if (distPx < 15) distPx = 15;

            float rad = (float) Math.toRadians(t.angle);
            t.px = cx + (float) Math.sin(rad) * distPx;
            t.py = cy - (float) Math.cos(rad) * distPx;

            // Distance-based visual scaling: closer = bigger & brighter
            // Monotonically decreasing to avoid growing when exiting the thermal
            float dist = t.distance;
            float distFactor;
            if (dist < 30f) {
                distFactor = 0.85f;                  // in core: bright
            } else if (dist < 80f) {
                distFactor = 0.85f - (dist - 30f) * 0.35f / 50f; // 0.85 → 0.50
            } else if (dist < 150f) {
                distFactor = 0.50f - (dist - 80f) * 0.40f / 70f; // 0.50 → 0.10
            } else {
                distFactor = 0.10f;                   // far: barely visible
            }
            distFactor = Math.max(0.05f, Math.min(1.0f, distFactor));

            float alpha = brightness * distFactor
                        * (0.6f + 0.4f * (float) Math.sin(nowMs / 200.0 + t.strength));

            // Size: monotonic with distance (closer = bigger) × frequency factor
            float size = Math.max(8f, 42f - dist * 0.25f) * t.sizeFactor;
            // At 0m: 42px, at 50m: 29.5px, at 100m: 17px, at 150m: 8px
            // Keep minimum size for visibility

            // glow (shadow cached в конструкторе — не переустанавливаем)
            thermalGlowPaint.setColor(Color.argb((int) (alpha * 40), 255, 193, 7));
            c.drawCircle(t.px, t.py, size * 2, thermalGlowPaint);

            // filled circle #FFC107
            thermalFillPaint.setColor(Color.argb((int) (alpha * 230), 255, 193, 7));
            c.drawCircle(t.px, t.py, size, thermalFillPaint);

            // stroke
            thermalStrokePaint.setColor(Color.argb((int) (alpha * 120), 255, 193, 7));
            c.drawCircle(t.px, t.py, size, thermalStrokePaint);

            // strength label
            cardTextPaint.setTextSize(20);
            cardTextPaint.setTextAlign(Paint.Align.CENTER);
            cardTextPaint.setColor(Color.argb((int) (alpha * 180), 255, 193, 7));
            c.drawText(String.format("+%.1f", t.strength), t.px, t.py - size - 10, cardTextPaint);
            cardTextPaint.setTextAlign(Paint.Align.LEFT);

            // dashed line from pilot to thermal
            dashedLinePaint.setColor(Color.argb((int) (alpha * 15), 255, 193, 7));
            c.drawLine(cx, cy, t.px, t.py, dashedLinePaint);
        }
    }
}
