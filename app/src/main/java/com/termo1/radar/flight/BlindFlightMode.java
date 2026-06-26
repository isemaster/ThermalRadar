package com.termo1.radar.flight;

import com.termo1.radar.core.SignalProcessor;
import com.termo1.radar.core.ThermalDetector;

/**
 * BlindFlightMode — режим полёта «вслепую».
 *
 * Телефон в кармане в любом положении (0–150° наклона).
 * Требуется только верная ориентация лево-право (ось X телефона = лево-право пилота).
 *
 * Особенности:
 * - Все оси акселерометра проецируются на горизонтальную плоскость через gravity
 * - Лево-право (ось X) = физическая ось X телефона (пользователь должен сориентировать верно)
 * - Вперёд-назад (ось Y_horiz) = проекция Y и Z на плоскость, ортогональную gravity
 * - Пороги детекции адаптируются под наклон (tilt correction)
 * - Направление термика: в системе лево/право/вперёд/назад относительно тела пилота
 * - Компас НЕ используется (работает без магнитометра)
 *
 * Сектора (относительно тела пилота):
 *   Впереди       — +67.5°..-67.5° по Y_horiz
 *   Слева         — X < -threshold
 *   Справа        — X > +threshold
 *   Спереди слева — X < -threshold × 0.5 && Y_horiz > 0
 *   Спереди справа — X > +threshold × 0.5 && Y_horiz > 0
 *   Сзади — молчим (не поддерживается без компаса)
 */
public class BlindFlightMode {

    // ========================================================================
    // Пороги (g)
    // ========================================================================

    private static final float TH_SUSPECT = 0.010f;
    private static final float TH_THERMAL = 0.020f;
    private static final float TH_INSIDE  = 0.080f;
    private static final int DIR_STABLE_COUNT = 25; // ~0.5 с при 50 Гц

    // ========================================================================
    // Состояние
    // ========================================================================

    private int aboveThresholdCount;
    private boolean directionReady;
    private String lastDirection = "";
    private int directionStableCount;
    private static final int DIRECTION_REPEAT_MIN = 10; // сэмплов подряд один сектор

    // ========================================================================
    // Gravity-based projection (tilt compensation)
    // ========================================================================

    private float gravityX, gravityY, gravityZ;
    private boolean hasGravity;

    /** Обновить gravity vector (вызывать из TYPE_ACCELEROMETER) */
    public void setGravity(float gx, float gy, float gz) {
        gravityX = gx;
        gravityY = gy;
        gravityZ = gz;
        hasGravity = Math.abs(gx) + Math.abs(gy) + Math.abs(gz) > 0.1f;
    }

    // ========================================================================
    // Обработка сэмпла
    // ========================================================================

    private final float[] horizY = new float[1];   // forward-backward component
    private final float[] magnitude = new float[1]; // overall oscillation magnitude

    /**
     * Обработать сэмпл LINEAR_ACCELERATION.
     * @param ax ускорение X (g) — лево-право
     * @param ay ускорение Y (g)
     * @param az ускорение Z (g)
     * @return направление термика или null если неопределено
     */
    public String processSample(float ax, float ay, float az) {
        if (!hasGravity) return null;

        // === Gravity-based tilt compensation ===
        // Нормализуем gravity вектор
        float gLen = (float) Math.sqrt(gravityX*gravityX + gravityY*gravityY + gravityZ*gravityZ);
        if (gLen < 0.1f) return null;
        float gxN = gravityX / gLen;
        float gyN = gravityY / gLen;
        float gzN = gravityZ / gLen;

        // Проекция ускорения на горизонтальную плоскость:
        // 1. X остаётся как есть (лево-право пилота — пользователь обязан сориентировать верно)
        // 2. Y_horiz = (Y,Z) спроецированные на плоскость, ортогональную gravity
        //    Вычитаем из (ay,az) компоненту вдоль gravity:
        //    proj = (ay*gyN + az*gzN) — скалярная проекция на gravity
        //    ay_horiz = ay - proj * gyN (Y компонента в горизонтальной плоскости)
        //    az_horiz = az - proj * gzN (Z компонента в горизонтальной плоскости)
        //    Итоговый Y_horiz = ay_horiz + az_horiz (forward-backward в плоскости)
        float proj = ay * gyN + az * gzN;
        float ayHoriz = ay - proj * gyN;
        float azHoriz = az - proj * gzN;

        // Y_horiz — компонента вперёд-назад (сумма горизонтальных Y и Z)
        float yHoriz = ayHoriz + azHoriz;

        // === RMS magnitude (для порогов) ===
        float x = ax;
        float y = yHoriz;
        float level = (float) Math.sqrt(x*x + y*y);

        // === Пороги ===
        if (level < TH_SUSPECT) {
            aboveThresholdCount = 0;
            directionReady = false;
            return null;
        }

        aboveThresholdCount++;
        if (aboveThresholdCount >= DIR_STABLE_COUNT) {
            directionReady = true;
        }
        if (!directionReady) return null;

        // === Определение направления (8 секторов, относительно тела) ===
        float absX = Math.abs(x);
        float absY = Math.abs(y);
        String dir;

        // Задние сектора (Y_horiz сильно отрицательный) — молчим
        if (y < -0.015f && absX < absY * 0.5f) {
            return null; // сзади — молчок
        }

        // Передняя полусфера: 180° впереди
        if (absX > absY * 1.5f) {
            // Доминирует X
            dir = (x > 0) ? "справа" : "слева";
        } else if (absY > absX * 1.5f) {
            // Доминирует Y
            dir = (y > 0) ? "спереди" : null;
            if (dir == null) return null; // сзади
        } else {
            // Смешанный сектор
            if (x > 0 && y > 0) dir = "спереди справа";
            else if (x < 0 && y > 0) dir = "спереди слева";
            else if (x > 0 && y < 0) dir = null; // сзади справа
            else dir = null; // сзади слева
            if (dir == null) return null;
        }

        // Стабилизация: один сектор N сэмплов подряд
        if (dir.equals(lastDirection)) {
            directionStableCount++;
        } else {
            lastDirection = dir;
            directionStableCount = 1;
        }

        if (directionStableCount < DIRECTION_REPEAT_MIN) return null;

        // Дистанция (оценка)
        float rmsMs2 = level * 9.81f;
        float dist = 150f * (float) Math.sqrt(0.05f / Math.max(rmsMs2, 0.01f));
        dist = Math.max(10f, Math.min(150f, dist));
        int distRounded = ((int)Math.round(dist / 10f)) * 10;
        if (distRounded < 10) distRounded = 10;

        return "Термик " + dir + " " + distRounded + " метров";
    }

    /** Сброс состояния */
    public void reset() {
        aboveThresholdCount = 0;
        directionReady = false;
        lastDirection = "";
        directionStableCount = 0;
    }
}
