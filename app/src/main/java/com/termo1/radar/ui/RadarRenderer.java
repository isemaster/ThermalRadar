package com.termo1.radar.ui;

import android.graphics.*;

import com.termo1.radar.model.ThermalBlip;

import com.termo1.radar.flight.LiftDatabase;

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
    private final Paint pilotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pilotPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nsewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Кешированные геометрические объекты — без new в onDraw!
    private final RectF sectorRect = new RectF();
    private final RectF bestSectorRect = new RectF();
    private final RectF outerRect = new RectF();

    // Wind arrow
    private final Paint windLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float windFromDeg = -1f;
    private float windSpeedMs = -1f;

    // Sector diagram (Phase 6 — 36 цветных сегментов)
    private final Paint sectorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] sectorLiftValues;
    private boolean sectorDataValid;

    // Best lift sector (Phase 2)
    private final Paint bestSectorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bestSectorIndex = -1;
    private float bestSectorLift = 0f;
    private String bestSectorDirection = "";

    // Climb stats
    private float climbAverageMs = 0f;
    private float thermalAltitude = 0f;
    private float thermalBaseHeight = 0f;
    private String thermalCoreText = "";
    private boolean showThermalStats;

    // GPS trail — жёлтая/оранжевая линия траектории
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailCirclingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailCirclingGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Map trail — синяя линия на карте (3×3 км)
    private final Paint mapTrailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Entry/exit markers
    private final Paint entryMarkerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entryMarkerGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint exitMarkerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] markerPx, markerPy;
    private boolean[] markerIsEntry; // true=entry, false=exit
    private int markerCount;

    // Thermal core
    private final Paint thermalCoreFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalCoreStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalCoreGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showThermalCore;
    private float thermalCoreBearing;
    private float thermalCoreDistance;
    private float thermalCoreRadiusPx;

    private final DashPathEffect dash125 = new DashPathEffect(new float[]{6, 8}, 0);
    private final DashPathEffect thermalDash = new DashPathEffect(new float[]{3, 5}, 0);
    private float thermalMaxDist;

    // Background map (Phase 6 — OSM статическая карта под радаром)
    private Bitmap backgroundMap;
    private double mapCenterLat, mapCenterLon;
    private int mapZoom;
    private boolean mapValid;
    // Background map paint (75% opacity)
    private static final int MAP_ALPHA = 191; // 191/255 ≈ 75%
    private final Paint mapAlphaPaint = new Paint();
    // Плавный сдвиг: текущая позиция пилота (обновляется каждый кадр)
    private double pilotLat, pilotLon;
    private boolean pilotPositionValid;
    // Порог обновления: если смещение >30% от размера карты — пора грузить новую
    private static final float MAP_REFRESH_THRESHOLD = 0.30f;
    // Реальный размер карты на местности: 5 тайлов zoom 14 на 55°N
    /** Отображаемая область карты в метрах (7×7 тайлов, полный экран 2220×1080) */
    private static final double MAP_METERS_TOTAL = 6200.0;

    // Кэш для haversine при вычислении сдвига карты
    private final float[] mapDistRes = new float[2];

    // Цветовые константы
    private static final int COLOR_GREEN_LIFT = Color.argb(200, 0, 230, 118);
    private static final int COLOR_RED_SINK = Color.argb(200, 255, 82, 82);
    private static final int COLOR_YELLOW_NEUTRAL = Color.argb(200, 255, 235, 59);
    private static final int COLOR_ENTRY = Color.argb(220, 76, 175, 80);  // зелёный
    private static final int COLOR_EXIT = Color.argb(220, 255, 82, 82);   // красный
    private static final int COLOR_CIRCLING = Color.argb(180, 255, 152, 0); // оранжевый
    private static final int COLOR_CIRCLING_GLOW = Color.argb(60, 255, 152, 0);

    public RadarRenderer() {
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(6);
        borderPaint.setColor(Color.argb(180, 0, 120, 255));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3);

        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(3);
        dashPaint.setPathEffect(dash125);

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(3);
        crossPaint.setColor(Color.argb(60, 40, 140, 255));

        pilotFillPaint.setStyle(Paint.Style.FILL);
        pilotFillPaint.setColor(Color.GREEN);
        pilotGlowPaint.setStyle(Paint.Style.FILL);
        pilotGlowPaint.setColor(Color.GREEN);
        pilotGlowPaint.setShadowLayer(18, 0, 0, Color.GREEN);
        pilotPulsePaint.setStyle(Paint.Style.STROKE);
        pilotPulsePaint.setStrokeWidth(1.5f);

        thermalGlowPaint.setStyle(Paint.Style.FILL);
        thermalGlowPaint.setShadowLayer(40, 0, 0, Color.argb(255, 255, 193, 7));
        thermalFillPaint.setStyle(Paint.Style.FILL);
        thermalStrokePaint.setStyle(Paint.Style.STROKE);
        thermalStrokePaint.setStrokeWidth(2);

        dashedLinePaint.setStyle(Paint.Style.STROKE);
        dashedLinePaint.setStrokeWidth(1);
        dashedLinePaint.setPathEffect(thermalDash);

        cardTextPaint.setAntiAlias(true);
        cardTextPaint.setTextSize(22);
        cardTextPaint.setTypeface(Typeface.MONOSPACE);

        nsewPaint.setAntiAlias(true);
        nsewPaint.setTextSize(144);
        nsewPaint.setTextAlign(Paint.Align.CENTER);
        nsewPaint.setTypeface(Typeface.MONOSPACE);
        nsewPaint.setFakeBoldText(true);

        // Wind
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

        // Trail (cruise — жёлтый)
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(2.5f);
        trailPaint.setColor(Color.argb(180, 255, 235, 59));
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailGlowPaint.setStyle(Paint.Style.STROKE);
        trailGlowPaint.setStrokeWidth(5f);
        trailGlowPaint.setColor(Color.argb(60, 255, 235, 59));
        trailGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        // Trail (circling — оранжевый)
        trailCirclingPaint.setStyle(Paint.Style.STROKE);
        trailCirclingPaint.setStrokeWidth(2.5f);
        trailCirclingPaint.setColor(COLOR_CIRCLING);
        trailCirclingPaint.setStrokeCap(Paint.Cap.ROUND);
        trailCirclingPaint.setStrokeJoin(Paint.Join.ROUND);
        trailCirclingGlowPaint.setStyle(Paint.Style.STROKE);
        trailCirclingGlowPaint.setStrokeWidth(5f);
        trailCirclingGlowPaint.setColor(COLOR_CIRCLING_GLOW);
        trailCirclingGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailCirclingGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        // Map trail — синий 4px
        mapTrailPaint.setStyle(Paint.Style.STROKE);
        mapTrailPaint.setStrokeWidth(4f);
        mapTrailPaint.setColor(Color.argb(160, 50, 150, 255));
        mapTrailPaint.setStrokeCap(Paint.Cap.ROUND);
        mapTrailPaint.setStrokeJoin(Paint.Join.ROUND);

        // Entry/exit markers
        entryMarkerFill.setStyle(Paint.Style.FILL);
        entryMarkerFill.setColor(COLOR_ENTRY);
        entryMarkerGlow.setStyle(Paint.Style.FILL);
        entryMarkerGlow.setColor(Color.argb(120, 76, 175, 80));
        entryMarkerGlow.setShadowLayer(12, 0, 0, COLOR_ENTRY);
        exitMarkerStroke.setStyle(Paint.Style.STROKE);
        exitMarkerStroke.setStrokeWidth(3);
        exitMarkerStroke.setColor(COLOR_EXIT);

        // Best lift sector
        bestSectorStrokePaint.setStyle(Paint.Style.STROKE);
        bestSectorStrokePaint.setStrokeWidth(3);
        bestSectorStrokePaint.setColor(COLOR_GREEN_LIFT);

        // Sector diagram paint
        sectorFillPaint.setStyle(Paint.Style.FILL);

        // Background map paint (75% alpha)
        mapAlphaPaint.setAlpha(MAP_ALPHA);

        // Thermal core
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

    public void onSizeChanged(int w, int h) {
        baseW = w;
        baseH = h;
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(cx, cy) - 4;
        thermalMaxDist = r * 5f / 6f;
    }

    // ===== DATA SETTERS =====

    public void setWindData(float fromDeg, float speedMs) {
        this.windFromDeg = fromDeg;
        this.windSpeedMs = speedMs;
    }

    public void setBestLiftSector(int sectorIndex, float liftValue, String direction) {
        this.bestSectorIndex = sectorIndex;
        this.bestSectorLift = liftValue;
        this.bestSectorDirection = direction != null ? direction : "";
    }

    public void setThermalStats(float climbAvgMs, float altitudeMsl, float baseHeightMsl,
                                String coreText, boolean showStats) {
        this.climbAverageMs = climbAvgMs;
        this.thermalAltitude = altitudeMsl;
        this.thermalBaseHeight = baseHeightMsl;
        this.thermalCoreText = coreText != null ? coreText : "";
        this.showThermalStats = showStats;
    }

    public void setThermalCore(boolean show, float bearing, float distance, float radiusMeters) {
        this.showThermalCore = show;
        this.thermalCoreBearing = bearing;
        this.thermalCoreDistance = distance;
        this.thermalCoreRadiusPx = (radiusMeters / 150f) * r;
    }

    /** Set full 36-sector lift values for sector diagram. */
    public void setSectorLiftData(float[] liftValues) {
        this.sectorLiftValues = liftValues;
        this.sectorDataValid = (liftValues != null && liftValues.length == 36);
    }

    /** Set background map bitmap (OSM static). */
    public void setBackgroundMap(Bitmap bitmap, double centerLat, double centerLon, int zoom) {
        this.backgroundMap = bitmap;
        this.mapCenterLat = centerLat;
        this.mapCenterLon = centerLon;
        this.mapZoom = zoom;
        this.mapValid = (bitmap != null && !bitmap.isRecycled());
    }

    /** Set current pilot position (каждый кадр, для плавного сдвига карты). */
    public void setPilotPosition(double lat, double lon) {
        this.pilotLat = lat;
        this.pilotLon = lon;
        this.pilotPositionValid = (lat != 0.0 && lon != 0.0);
    }

    /**
     * Проверить, пора ли обновить карту.
     * @return true если смещение >30% от размера Bitmap
     */
    public boolean isMapRefreshNeeded() {
        if (!mapValid || !pilotPositionValid) return true;
        float[] offsetPx = computeMapOffsetPx();
        float bitmapHalf = (backgroundMap != null ? backgroundMap.getWidth() : 512) / 2f;
        float limit = bitmapHalf * MAP_REFRESH_THRESHOLD;
        return Math.abs(offsetPx[0]) > limit || Math.abs(offsetPx[1]) > limit;
    }

    /** Set entry/exit markers on the trail. */
    public void setTrailMarkers(float[] markerPx, float[] markerPy,
                                boolean[] isEntry, int count) {
        this.markerPx = markerPx;
        this.markerPy = markerPy;
        this.markerIsEntry = isEntry;
        this.markerCount = count;
    }

    // ===== MAIN DRAW =====

    public void draw(Canvas canvas, long nowMs, List<ThermalBlip> thermals,
                     float heading, float vario, String status,
                     float maxSnr, int count,
                     float[] trailPx, float[] trailPy, int[] trailColors, int trailCount,
                     float[] mapTrailPx, float[] mapTrailPy, int mapTrailCount) {
        if (baseW <= 0 || baseH <= 0) return;
        // --- black background ---
        canvas.drawColor(Color.rgb(10, 10, 10));

        // --- rotate around pilot (-heading so N always north) ---
        canvas.save();
        canvas.rotate(-heading, cx, cy);

        // --- background OSM map (semi-transparent, вращается с компасом) ---
        if (mapValid && backgroundMap != null) {
            float[] offset = computeMapOffsetPx();

            // Карта 1280×1280, масштаб: край радара r = 1500 м
            // 5×5 тайлов, отображается 3000 м, mapSize = 3000/1500 × r = 2.0 × r
            float mapSize = r * (float)(MAP_METERS_TOTAL / 1500.0);
            float mapLeft = cx - mapSize / 2f - offset[0];
            float mapTop = cy - mapSize / 2f + offset[1];
            Rect dst = new Rect((int) mapLeft, (int) mapTop,
                                (int) (mapLeft + mapSize), (int) (mapTop + mapSize));
            canvas.drawBitmap(backgroundMap, null, dst, mapAlphaPaint);
        }

        drawSectorDiagram(canvas);
        drawRings(canvas);
        drawCross(canvas);
        drawCardinalPoints(canvas);
        drawTickMarks(canvas);
        drawBestLiftSector(canvas);
        drawThermalCore(canvas);
        drawMapTrail(canvas, mapTrailPx, mapTrailPy, mapTrailCount);
        drawTrail(canvas, trailPx, trailPy, trailColors, trailCount);
        drawTrailMarkers(canvas);
        drawThermals(canvas, nowMs, thermals);
        drawPilot(canvas, nowMs);
        drawWindArrow(canvas);

        canvas.restore();

        canvas.drawRect(1, 1, baseW - 2, baseH - 2, borderPaint);

        if (showThermalStats) {
            cardTextPaint.setTextSize(20);
            cardTextPaint.setTextAlign(Paint.Align.LEFT);
            cardTextPaint.setColor(Color.argb(180, 0, 255, 0));
            float statsY = 40;
            if (climbAverageMs > 0) {
                canvas.drawText(String.format(java.util.Locale.US, "Ср: %+.1f м/с", climbAverageMs),
                        10, statsY, cardTextPaint);
                statsY += 24;
            }
            if (thermalCoreText.length() > 0) {
                canvas.drawText(thermalCoreText, 10, statsY, cardTextPaint);
                statsY += 24;
            }
            if (thermalBaseHeight > 0) {
                canvas.drawText(String.format(java.util.Locale.US, "База: %.0fм", thermalBaseHeight),
                        10, statsY, cardTextPaint);
            }
        }
    }

    // ===== SECTOR DIAGRAM (12 цветных сегментов) =====

    private void drawSectorDiagram(Canvas c) {
        if (!sectorDataValid) return;

        float outerR = r - 2f;          // внешний край
        float innerR = r * 0.80f;       // внутренний край

        // Используем LiftDatabase.SECTOR_COUNT вместо хардкода
        int nSectors = LiftDatabase.SECTOR_COUNT;
        float sectorDeg = 360f / nSectors;

        // Определяем диапазон значений для шкалы
        float maxLift = 0.001f, minLift = -0.001f;
        for (int i = 0; i < nSectors; i++) {
            if (sectorLiftValues[i] > maxLift) maxLift = sectorLiftValues[i];
            if (sectorLiftValues[i] < minLift) minLift = sectorLiftValues[i];
        }
        float range = Math.max(maxLift - minLift, 0.5f);

        // Кешированный RectF (без аллокации в onDraw)
        sectorRect.set(cx - outerR, cy - outerR,
                       cx + outerR, cy + outerR);

        for (int i = 0; i < nSectors; i++) {
            float val = sectorLiftValues[i];
            if (val == 0f) continue; // нет данных — не рисуем

            // Цвет: зелёный (подъём) → жёлтый (нейтрально) → красный (снижение)
            int color;
            if (val > 0) {
                float t = Math.min(val / maxLift, 1f);
                color = lerpColor(COLOR_YELLOW_NEUTRAL, COLOR_GREEN_LIFT, t);
            } else {
                float t = Math.min(-val / Math.max(-minLift, 0.1f), 1f);
                color = lerpColor(COLOR_YELLOW_NEUTRAL, COLOR_RED_SINK, t);
            }

            sectorFillPaint.setColor(color);

            // BUG-23: сектор 0 с центром ровно на 0° (N)
            // startAngle = -(i * 10) — без смещения на пол-сектора
            float startAngle = -(i * sectorDeg);
            c.drawArc(sectorRect, startAngle - sectorDeg / 2f, sectorDeg, true, sectorFillPaint);
        }
    }

    /** Линейная интерполяция между двумя цветами ARGB. */
    private static int lerpColor(int from, int to, float t) {
        int a = (int)(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int)(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int)(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int)(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    // ===== RINGS, CROSS, CARDINALS, TICKS =====

    private void drawRings(Canvas c) {
        ringPaint.setColor(Color.argb(40, 30, 150, 255));
        ringPaint.setStrokeWidth(6);
        c.drawCircle(cx, cy, r / 3f, ringPaint);
        cardTextPaint.setColor(Color.argb(180, 30, 150, 255));
        c.drawText("50м", cx + 5, cy - r / 3f - 5, cardTextPaint);

        c.drawCircle(cx, cy, r * 2f / 3f, ringPaint);
        c.drawText("100м", cx + 5, cy - r * 2f / 3f - 5, cardTextPaint);

        dashPaint.setStrokeWidth(6);
        dashPaint.setColor(Color.argb(30, 30, 150, 255));
        c.drawCircle(cx, cy, thermalMaxDist, dashPaint);
        c.drawText("125м", cx + 5, cy - thermalMaxDist - 5, cardTextPaint);

        ringPaint.setColor(Color.argb(180, 0, 120, 255));
        ringPaint.setStrokeWidth(6);
        c.drawCircle(cx, cy, r, ringPaint);
    }

    private void drawCross(Canvas c) {
        crossPaint.setStrokeWidth(6);
        crossPaint.setColor(Color.argb(60, 40, 140, 255));
        c.drawLine(cx - r, cy, cx + r, cy, crossPaint);
        c.drawLine(cx, cy - r, cx, cy + r, crossPaint);
        float d = (float) (r * 0.70710678118);
        c.drawLine(cx - d, cy - d, cx + d, cy + d, crossPaint);
        c.drawLine(cx - d, cy + d, cx + d, cy - d, crossPaint);
        crossPaint.setStrokeWidth(3);
    }

    private void drawCardinalPoints(Canvas c) {
        float offset = 100;
        nsewPaint.setColor(Color.argb(200, 0, 130, 255));
        c.drawText("N", cx, cy - r + offset, nsewPaint);
        c.drawText("S", cx, cy + r - 20, nsewPaint);
        c.drawText("W", cx - r + offset - 10, cy + 18, nsewPaint);
        c.drawText("E", cx + r - offset + 10, cy + 18, nsewPaint);
    }

    private void drawTickMarks(Canvas c) {
        ringPaint.setColor(Color.argb(80, 30, 150, 255));
        ringPaint.setStrokeWidth(3);
        cardTextPaint.setTextAlign(Paint.Align.CENTER);
        cardTextPaint.setTextSize(32);
        cardTextPaint.setColor(Color.argb(130, 30, 150, 255));
        for (int deg = 0; deg < 360; deg += 15) {
            float rad = (float) Math.toRadians(deg);
            float innerR = r - 8f;
            float x1 = cx + (float) Math.sin(rad) * innerR;
            float y1 = cy - (float) Math.cos(rad) * innerR;
            float x2 = cx + (float) Math.sin(rad) * r;
            float y2 = cy - (float) Math.cos(rad) * r;
            c.drawLine(x1, y1, x2, y2, ringPaint);
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

    // ===== PILOT =====

    private void drawPilot(Canvas c, long nowMs) {
        c.drawCircle(cx, cy, 10, pilotGlowPaint);
        c.drawCircle(cx, cy, 7, pilotFillPaint);
        float pulse = 7 + (float) Math.sin(nowMs / 300.0) * 3;
        pilotPulsePaint.setColor(Color.argb(50, 0, 255, 0));
        c.drawCircle(cx, cy, pulse, pilotPulsePaint);
    }

    // ===== WIND ARROW =====

    private void drawWindArrow(Canvas c) {
        if (windFromDeg < 0 || windSpeedMs <= 0) return;

        double aRad = Math.toRadians(windFromDeg);
        float ex = cx + (float)(r * Math.sin(aRad));
        float ey = cy - (float)(r * Math.cos(aRad));
        float innerDist = r * 0.767f;
        float ix = cx + (float)(innerDist * Math.sin(aRad));
        float iy = cy - (float)(innerDist * Math.cos(aRad));

        c.drawLine(ex, ey, ix, iy, windLinePaint);

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

        float lx = cx + (float)((r + 50) * Math.sin(aRad));
        float ly = cy - (float)((r + 50) * Math.cos(aRad));
        if (lx < 30) lx = 30; if (lx > baseW - 30) lx = baseW - 30;
        if (ly < 30) ly = 30; if (ly > baseH - 10) ly = baseH - 10;
        c.drawText(String.format(java.util.Locale.US, "ветер %.1fм/с", windSpeedMs), lx, ly, windLabelPaint);
    }

    // ===== MAP TRAIL — синий трек на карте (3×3 км, 4px) =====

    private void drawMapTrail(Canvas c, float[] px, float[] py, int count) {
        if (count < 2) return;
        for (int i = 1; i < count; i++) {
            c.drawLine(px[i-1], py[i-1], px[i], py[i], mapTrailPaint);
        }
    }

    // ===== TRAIL (жёлтый круиз / оранжевый крутка) =====

    private void drawTrail(Canvas c, float[] px, float[] py, int[] colors, int count) {
        if (count < 2) return;

        // Свечение (glow — широкая полупрозрачная линия под основной)
        for (int i = 1; i < count; i++) {
            int col = colors[i];
            int a = (col >>> 24) & 0xFF;
            if (a < 8) continue;
            // Glow: alpha примерно 30% от основной
            int glowA = a / 3;
            if (glowA < 8) continue;
            trailGlowPaint.setColor((glowA << 24) | (col & 0xFFFFFF));
            c.drawLine(px[i-1], py[i-1], px[i], py[i], trailGlowPaint);
        }

        // Основная линия — цвет от варио, alpha от возраста
        for (int i = 1; i < count; i++) {
            int col = colors[i];
            int a = (col >>> 24) & 0xFF;
            if (a < 8) continue;
            trailPaint.setColor(col);
            c.drawLine(px[i-1], py[i-1], px[i], py[i], trailPaint);
        }
    }

    // ===== ENTRY/EXIT MARKERS =====

    private void drawTrailMarkers(Canvas c) {
        if (markerCount <= 0 || markerPx == null || markerPy == null) return;

        for (int i = 0; i < markerCount && i < markerPx.length && i < markerPy.length; i++) {
            if (i >= markerIsEntry.length) break;

            float mx = markerPx[i];
            float my = markerPy[i];

            if (markerIsEntry[i]) {
                // Entry marker: зелёный заполненный круг
                c.drawCircle(mx, my, 10, entryMarkerGlow);
                c.drawCircle(mx, my, 6, entryMarkerFill);
            } else {
                // Exit marker: красный круг с обводкой (полый)
                c.drawCircle(mx, my, 8, exitMarkerStroke);
                // Маленькая красная точка внутри
                exitMarkerStroke.setStyle(Paint.Style.FILL);
                exitMarkerStroke.setColor(Color.argb(150, 255, 82, 82));
                c.drawCircle(mx, my, 3, exitMarkerStroke);
                exitMarkerStroke.setStyle(Paint.Style.STROKE);
                exitMarkerStroke.setColor(COLOR_EXIT);
            }
        }
    }

    // ===== THERMAL CORE =====

    private void drawThermalCore(Canvas c) {
        if (!showThermalCore || thermalCoreDistance < 0) return;

        float distPx = Math.min(thermalCoreDistance / 150f, 1f) * r;
        float rad = (float) Math.toRadians(thermalCoreBearing);
        float px = cx + (float) Math.sin(rad) * distPx;
        float py = cy - (float) Math.cos(rad) * distPx;

        if (distPx > r) return;

        float radius = Math.max(thermalCoreRadiusPx, 10f);

        thermalCoreGlowPaint.setAlpha(120);
        c.drawCircle(px, py, radius * 2.5f, thermalCoreGlowPaint);
        c.drawCircle(px, py, radius, thermalCoreFillPaint);
        c.drawCircle(px, py, radius, thermalCoreStrokePaint);

        thermalCoreStrokePaint.setPathEffect(null);
        thermalCoreStrokePaint.setStrokeWidth(2);
        thermalCoreStrokePaint.setColor(Color.argb(200, 255, 50, 50));
        c.drawCircle(px, py, 4, thermalCoreStrokePaint);
    }

    // ===== THERMALS (blips) =====

    private void drawThermals(Canvas c, long nowMs, List<ThermalBlip> thermals) {
        for (ThermalBlip t : thermals) {
            float brightness = t.getBrightness(nowMs);
            if (brightness <= 0f) continue;

            float distPx = Math.min(t.distance / 150f, 1f) * thermalMaxDist;
            if (distPx < 15) distPx = 15;

            float rad = (float) Math.toRadians(t.angle);
            // NEW-08: локальные переменные вместо мутации ThermalBlip
            float px = cx + (float) Math.sin(rad) * distPx;
            float py = cy - (float) Math.cos(rad) * distPx;

            float dist = t.distance;
            float distFactor;
            if (dist < 30f) {
                distFactor = 0.85f;
            } else if (dist < 80f) {
                distFactor = 0.85f - (dist - 30f) * 0.35f / 50f;
            } else if (dist < 150f) {
                distFactor = 0.50f - (dist - 80f) * 0.40f / 70f;
            } else {
                distFactor = 0.10f;
            }
            distFactor = Math.max(0.05f, Math.min(1.0f, distFactor));

            float alpha = brightness * distFactor
                        * (0.6f + 0.4f * (float) Math.sin(nowMs / 200.0 + t.strength));

            float size = Math.max(8f, 42f - dist * 0.25f) * t.sizeFactor
                        * (0.5f + 0.5f * Math.min(t.strength, 8f) / 8f);

            thermalGlowPaint.setColor(Color.argb((int) (alpha * 40), 255, 193, 7));
            c.drawCircle(px, py, size * 2, thermalGlowPaint);

            thermalFillPaint.setColor(Color.argb((int) (alpha * 230), 255, 193, 7));
            c.drawCircle(px, py, size, thermalFillPaint);

            thermalStrokePaint.setColor(Color.argb((int) (alpha * 120), 255, 193, 7));
            c.drawCircle(px, py, size, thermalStrokePaint);

            cardTextPaint.setTextSize(20);
            cardTextPaint.setTextAlign(Paint.Align.CENTER);
            cardTextPaint.setColor(Color.argb((int) (alpha * 180), 255, 193, 7));
            c.drawText(String.format("+%.1f", t.strength), px, py - size - 10, cardTextPaint);
            cardTextPaint.setTextAlign(Paint.Align.LEFT);

            dashedLinePaint.setColor(Color.argb((int) (alpha * 15), 255, 193, 7));
            c.drawLine(cx, cy, px, py, dashedLinePaint);
        }
    }

    // ===== MAP OFFSET (плавный сдвиг карты за пилотом) =====

    /** Сдвиг карты в пикселях: [offsetX, offsetY].
     *  Положительный offsetX = карта смещена ВЛЕВО (пилот улетел на восток).
     *  Положительный offsetY = карта смещена ВНИЗ (пилот улетел на север). */
    private float[] computeMapOffsetPx() {
        if (!mapValid || !pilotPositionValid) {
            return new float[]{0, 0};
        }
        // Haversine: distance AND bearing from map center to pilot
        android.location.Location.distanceBetween(
                mapCenterLat, mapCenterLon,
                pilotLat, pilotLon,
                mapDistRes);
        float distM = mapDistRes[0];
        float bearing = mapDistRes[1]; // градусы от центра карты к пилоту

        // Разлагаем на east и north компоненты
        double rad = Math.toRadians(bearing);
        double eastM = distM * Math.sin(rad);  // положительный = пилот восточнее центра
        double northM = distM * Math.cos(rad); // положительный = пилот севернее центра

        // Динамический m/px: край радара (r пикселей) = 1500 м
        double mpp = 1500.0 / r;
        float offsetX = (float) (eastM / mpp);
        float offsetY = (float) (northM / mpp);

        return new float[]{offsetX, offsetY};
    }

    // ===== BEST LIFT SECTOR =====

    private void drawBestLiftSector(Canvas c) {
        if (bestSectorIndex < 0) return;

        float sectorWidth = 360f / LiftDatabase.SECTOR_COUNT;
        float sectorHalf = sectorWidth / 2f;
        float sectorCenterDeg = bestSectorIndex * sectorWidth + sectorHalf;
        float outerR = r - 2f;

        // BUG-A18: кешированный RectF (без аллокации в onDraw)
        bestSectorRect.set(cx - outerR, cy - outerR,
                           cx + outerR, cy + outerR);
        c.drawArc(bestSectorRect, -sectorCenterDeg - sectorHalf, sectorWidth, false,
                  bestSectorStrokePaint);

        if (bestSectorDirection.length() > 0) {
            cardTextPaint.setTextSize(18);
            cardTextPaint.setTextAlign(Paint.Align.CENTER);
            cardTextPaint.setColor(Color.argb(200, 0, 230, 118));

            double rad = Math.toRadians(sectorCenterDeg);
            float labelR = r + 30f;
            float lx = cx + (float) Math.sin(rad) * labelR;
            float ly = cy - (float) Math.cos(rad) * labelR;

            if (lx < 20) lx = 20;
            if (lx > baseW - 20) lx = baseW - 20;
            if (ly < 20) ly = 20;
            if (ly > baseH - 10) ly = baseH - 10;

            c.drawText(bestSectorDirection + " +" + String.format(java.util.Locale.US, "%.1f", bestSectorLift),
                       lx, ly + 5, cardTextPaint);
            cardTextPaint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
