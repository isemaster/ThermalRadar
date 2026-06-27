package com.termo1.radar.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.termo1.radar.core.TrackReplayer;
import com.termo1.radar.flight.CirclingManager;
import com.termo1.radar.sensors.VarioManager;
import com.termo1.radar.sensors.SensorController;

import java.util.Locale;

/**
 * ThermalHelperPainter — отрисовка помощника термиков (стиль FlyMe).
 *
 * Компоненты (аналоги FlyMe):
 *   1. Vario-хелперы (showVarioHelpers) — цветные точки на GPS треке по силе варио
 *   2. 3D термик-круг (drawThermal3d)   — концентрические круги при крутке
 *   3. HUD-стрелка (BoxNearThermal)     — указатель на ближайший термик + дистанция
 *
 * Вызывается из RadarView.onDraw() после отрисовки трека.
 */
public class ThermalHelperPainter {

    // ========================================================================
    // Конфигурация
    // ========================================================================
    private static final float VARIO_HELPER_RADIUS_PX = 12f;
    private static final float THERMAL_CIRCLE_RADIUS_PX = 50f;
    private static final int THERMAL_3D_LAYERS = 7;
    private static final float THERMAL_CIRCLE_STROKE_PX = 2.5f;
    private static final int THERMAL_ALPHA_START = 0xFA; // 250
    private static final int THERMAL_ALPHA_STEP = 0x1F;  // 31

    // Цвета
    private static final int COLOR_LIFT = Color.argb(200, 0, 230, 118);      // зелёный
    private static final int COLOR_STRONG_LIFT = Color.argb(220, 255, 235, 59); // жёлтый
    private static final int COLOR_SINK = Color.argb(200, 255, 82, 82);      // красный
    private static final int COLOR_THERMAL_CIRCLE = Color.argb(180, 255, 152, 0); // оранжевый
    private static final int COLOR_HUD_THERMAL = Color.argb(220, 255, 193, 7); // янтарный

    // ========================================================================
    // Paint objects (reused, no alloc in draw)
    // ========================================================================
    private final Paint varioDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thermalCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudArrowHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    public ThermalHelperPainter() {
        varioDotPaint.setStyle(Paint.Style.FILL);

        thermalCirclePaint.setStyle(Paint.Style.STROKE);
        thermalCirclePaint.setStrokeWidth(THERMAL_CIRCLE_STROKE_PX);

        hudArrowPaint.setStyle(Paint.Style.STROKE);
        hudArrowPaint.setStrokeWidth(3f);
        hudArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        hudArrowPaint.setStrokeJoin(Paint.Join.ROUND);
        hudArrowPaint.setColor(COLOR_HUD_THERMAL);

        hudArrowHeadPaint.setStyle(Paint.Style.FILL);
        hudArrowHeadPaint.setColor(COLOR_HUD_THERMAL);

        hudTextPaint.setAntiAlias(true);
        hudTextPaint.setColor(COLOR_HUD_THERMAL);
        hudTextPaint.setTextSize(26f);
        hudTextPaint.setTypeface(Typeface.MONOSPACE);
        hudTextPaint.setTextAlign(Paint.Align.LEFT);

        hudBgPaint.setStyle(Paint.Style.FILL);
        hudBgPaint.setColor(Color.argb(60, 0, 0, 0));

        // Стрелка-указатель (равнобедренный треугольник)
        arrowPath.moveTo(0, -18);
        arrowPath.lineTo(10, 14);
        arrowPath.lineTo(-10, 14);
        arrowPath.close();
    }

    // ========================================================================
    // 1. Vario-хелперы на треке (аналог FlyMe ThermalPainter.showVarioHelpers)
    // ========================================================================
    /**
     * Рисует цветные точки на треке: зелёные (подъём), красные (снижение),
     * жёлтые (сильный подъём). Радиус точки пропорционален |vario|.
     */
    public void drawVarioHelpers(Canvas canvas,
                                  float[] trailPx, float[] trailPy, int trailCount,
                                  SensorController sensorCtrl) {
        if (trailCount < 2 || trailPx == null || trailPy == null) return;

        float varioMax = 0.01f, varioMin = -0.01f;

        // Собираем vario для каждого сегмента трека (по последним N точкам)
        // Используем буфер варио из SensorController если доступен
        if (sensorCtrl != null && sensorCtrl.getVarioManager() != null) {
            VarioManager vm = sensorCtrl.getVarioManager();
            float currentVario = Math.abs(vm.getVario());
            varioMax = Math.max(varioMax, currentVario);
            varioMin = Math.min(varioMin, -currentVario);
        }

        // Рисуем точки на каждой 3-й позиции трека
        int step = Math.max(1, trailCount / 60); // ~60 точек максимум
        for (int i = 0; i < trailCount; i += step) {
            float x = trailPx[i];
            float y = trailPy[i];
            if (Float.isNaN(x) || Float.isNaN(y)) continue;

            // Симулируем vario от позиции в треке (при реплее — из TrackReplayer)
            // В реальном режиме — из VarioManager
            float v = 0f;
            if (sensorCtrl != null) {
                v = sensorCtrl.getVario();
            }

            // Определяем цвет по знаку и силе варио
            int color;
            float radius = VARIO_HELPER_RADIUS_PX * 0.4f;
            if (v > 0.3f) {
                // Подъём: зелёный, радиус растёт с силой
                float intensity = Math.min(v / 3.0f, 1.0f);
                color = lerpColor(Color.argb(100, 0, 230, 118), COLOR_LIFT, intensity);
                radius = VARIO_HELPER_RADIUS_PX * (0.4f + intensity * 0.6f);
            } else if (v < -0.3f) {
                // Снижение: красный
                float intensity = Math.min(-v / 3.0f, 1.0f);
                color = lerpColor(Color.argb(80, 255, 82, 82), COLOR_SINK, intensity);
                radius = VARIO_HELPER_RADIUS_PX * (0.4f + intensity * 0.6f);
            } else {
                // Нейтрально: серый, мелкая точка
                color = Color.argb(50, 150, 150, 150);
                radius = VARIO_HELPER_RADIUS_PX * 0.3f;
            }

            varioDotPaint.setColor(color);
            int alpha = 60 + (int)((float)i / trailCount * 140); // fade in
            varioDotPaint.setAlpha(Math.min(alpha, 200));
            canvas.drawCircle(x, y, radius, varioDotPaint);
        }
    }

    // ========================================================================
    // 2. 3D термик-круг (аналог FlyMe ThermalPainter.drawThermal3d)
    // ========================================================================
    /**
     * Рисует концентрические круги в центре текущей спирали.
     * Затухание alpha от края к центру.
     */
    public void drawThermalCircle3d(Canvas canvas,
                                     float centerX, float centerY,
                                     boolean isCircling) {
        if (!isCircling || Float.isNaN(centerX) || Float.isNaN(centerY)) return;

        float radius = THERMAL_CIRCLE_RADIUS_PX;
        int alpha = THERMAL_ALPHA_START;
        float stepR = radius / THERMAL_3D_LAYERS;

        for (int i = 0; i < THERMAL_3D_LAYERS; i++) {
            thermalCirclePaint.setColor(COLOR_THERMAL_CIRCLE);
            thermalCirclePaint.setAlpha(Math.max(alpha, 20));
            float r = radius - stepR * i;
            if (r < 2) break;
            canvas.drawCircle(centerX, centerY, r, thermalCirclePaint);
            alpha -= THERMAL_ALPHA_STEP;
        }

        // Внешний яркий контур
        thermalCirclePaint.setColor(Color.argb(200, 255, 152, 0));
        thermalCirclePaint.setStrokeWidth(2f);
        canvas.drawCircle(centerX, centerY, radius, thermalCirclePaint);
    }

    // ========================================================================
    // 3. HUD-стрелка ближайшего термика (аналог FlyMe BoxNearThermal)
    // ========================================================================
    /**
     * Рисует стрелку-указатель на ближайший термик + дистанцию.
     * Позиция: правый нижний угол радара.
     */
    public void drawThermalHud(Canvas canvas,
                                float headingDeg,
                                float thermalBearing,
                                float thermalDistanceM,
                                float hudX, float hudY) {
        if (Float.isNaN(thermalBearing) || thermalBearing < 0) return;
        if (thermalDistanceM <= 0) return;

        // Направление: bearing относительно курса пилота
        float relBearing = thermalBearing - headingDeg;
        while (relBearing > 180) relBearing -= 360;
        while (relBearing < -180) relBearing += 360;

        // Фон
        hudBgPaint.setAlpha(120);
        canvas.drawCircle(hudX, hudY, 40, hudBgPaint);

        // Стрелка
        canvas.save();
        canvas.translate(hudX, hudY);
        canvas.rotate(relBearing);
        canvas.drawPath(arrowPath, hudArrowPaint);
        canvas.drawPath(arrowPath, hudArrowHeadPaint);
        canvas.restore();

        // Текст дистанции
        String distText;
        if (thermalDistanceM < 1000) {
            distText = String.format(Locale.US, "%.0fм", thermalDistanceM);
        } else {
            distText = String.format(Locale.US, "%.1fкм", thermalDistanceM / 1000f);
        }
        hudTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(distText, hudX, hudY + 56, hudTextPaint);
    }

    // ========================================================================
    // Utility
    // ========================================================================
    private static int lerpColor(int from, int to, float t) {
        int a = (int)(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int)(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int)(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int)(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}
