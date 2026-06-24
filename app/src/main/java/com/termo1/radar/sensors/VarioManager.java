package com.termo1.radar.sensors;

import android.os.SystemClock;

import com.termo1.radar.core.ThermalDetector;

/**
 * VarioManager — баровариометр на основе давления.
 *
 * - Калибровка baseline (первые 50 сэмплов)
 * - Барометрическая высота из формулы ISA
 * - Вертикальная скорость (vario) через конечную разность + скользящее среднее 750 мс
 * - Адаптивное сглаживание: быстрее при турбулентности
 * - HPF + RMS для SNR
 *
 * Выделен из SensorController для SRP.
 */
public class VarioManager {

    private static final int BARO_CALIB_SAMPLES = 50;
    private static final int VARIO_BUF_SIZE = 64;
    private static final long VARIO_WINDOW_MS = 750;
    private static final float NOISE_FLOOR_FIXED = 3.5f;
    private static final int HP_BUF_SIZE = 64;

    // ISA: h = 44330 · (1 - (p/p0)^0.190263), где 0.190263 = (R·L)/(g·M) = 1/5.255
    private static final double ISA_HEIGHT_FACTOR = 0.190263072286998;
    private static final double ISA_HEIGHT_SCALE_M = 44330.7692307692;

    /** Vario deadband — минимальное изменение для отображения (исправлено: 0.05→0.2 м/с) */
    private static final float VARIO_DEADBAND = 0.2f;

    // Baro calibration
    private float baselinePressure = 1013.25f;
    private int baroCalibCount;

    // Altitude / vario
    private volatile float vario;
    private volatile float altFiltered;
    private volatile float altRaw;
    private volatile float prevAltRaw;
    private long lastBaroNanos;

    // Smoothing
    private int varioSmoothSamples = 30;

    // Ring buffer
    private final float[] varioBuf = new float[VARIO_BUF_SIZE];
    private final long[] varioTimeBuf = new long[VARIO_BUF_SIZE];
    private int varioHead;
    private int varioTail;

    // HPF + RMS
    private final float[] hpBuf = new float[HP_BUF_SIZE];
    private int hpBufIdx;
    private int hpBufFill;
    private volatile float recentSnr;
    private volatile float maxSnr;

    // Thermal detector for adaptive alpha
    private ThermalDetector thermalDetector;

    public VarioManager() {
        reset();
    }

    public void setThermalDetector(ThermalDetector detector) {
        this.thermalDetector = detector;
    }

    public void setVarioSmoothSamples(int samples) {
        this.varioSmoothSamples = Math.max(5, Math.min(100, samples));
    }

    public void reset() {
        baselinePressure = 1013.25f;
        baroCalibCount = 0;
        vario = 0f;
        altFiltered = 0f;
        altRaw = 0f;
        prevAltRaw = 0f;
        lastBaroNanos = 0;
        varioHead = 0;
        varioTail = 0;
        hpBufIdx = 0;
        hpBufFill = 0;
        recentSnr = 0f;
        maxSnr = 0f;
    }

    public void resetMaxSnr() {
        maxSnr = 0f;
    }

    /**
     * Обработать сэмпл давления.
     * @param pressure давление в гПа
     * @return true если vario/SNR обновлены (каждые 64 сэмпла)
     */
    public boolean processPressureSample(float pressure) {
        if (pressure < 300f || pressure > 1100f) return false;

        // Калибровка: первые 50 сэмплов
        if (baroCalibCount < BARO_CALIB_SAMPLES) {
            baselinePressure += (pressure - baselinePressure) / (baroCalibCount + 1);
            baroCalibCount++;
            return false;
        }

        float ratio = pressure / baselinePressure;
        altRaw = (float) (ISA_HEIGHT_SCALE_M * (1.0 - Math.pow(ratio, ISA_HEIGHT_FACTOR)));

        long nowNanos = SystemClock.elapsedRealtimeNanos();
        if (baroCalibCount == BARO_CALIB_SAMPLES) {
            altFiltered = altRaw;
            prevAltRaw = altRaw;
            lastBaroNanos = nowNanos;
            baroCalibCount++;
            return false;
        }

        // EMA с адаптивным alpha
        float alpha = getVarioAlpha();
        altFiltered = alpha * altFiltered + (1f - alpha) * altRaw;

        // Vario = конечная разность по времени
        long dtNanos = nowNanos - lastBaroNanos;
        lastBaroNanos = nowNanos;
        long dtMs = dtNanos / 1_000_000;
        if (dtMs > 1) {
            float rawVario = (altRaw - prevAltRaw) * 1000f / (float) dtMs;
            prevAltRaw = altRaw;

            long nowMs = SystemClock.elapsedRealtime();
            varioBuf[varioHead] = rawVario;
            varioTimeBuf[varioHead] = nowMs;
            varioHead = (varioHead + 1) % VARIO_BUF_SIZE;
            if (varioHead == varioTail) {
                varioTail = (varioTail + 1) % VARIO_BUF_SIZE;
            }

            // Скользящее среднее за 750 мс
            long cutoff = nowMs - VARIO_WINDOW_MS;
            float sum = 0f;
            int count = 0;
            int idx = varioTail;
            while (idx != varioHead) {
                if (varioTimeBuf[idx] >= cutoff) {
                    sum += varioBuf[idx];
                    count++;
                }
                idx = (idx + 1) % VARIO_BUF_SIZE;
            }
            vario = (count > 0) ? sum / count : 0f;
            // Deadband для варио (исправлено: 0.05→0.2 м/с)
            if (Math.abs(vario) < VARIO_DEADBAND) vario = 0f;
        }

        // HPF + RMS для SNR
        float hp = altRaw - altFiltered;
        hpBuf[hpBufIdx] = hp;
        hpBufIdx = (hpBufIdx + 1) & (HP_BUF_SIZE - 1);
        if (hpBufFill < HP_BUF_SIZE) hpBufFill++;

        if (hpBufFill >= HP_BUF_SIZE) {
            float sum = 0.0f;
            for (int i = 0; i < HP_BUF_SIZE; i++) {
                sum += hpBuf[i] * hpBuf[i];
            }
            float rms = (float) Math.sqrt(sum / HP_BUF_SIZE);
            recentSnr = rms / NOISE_FLOOR_FIXED;
            if (recentSnr > maxSnr) {
                maxSnr = recentSnr;
            }
            return true;
        }
        return false;
    }

    /** Адаптивный alpha — быстрее при турбулентности.
     *  Исправлено по ревью §5.4: нижний clamp 0.9→0.8, чтобы настройка n=5 работала. */
    private float getVarioAlpha() {
        int n = Math.max(5, Math.min(100, varioSmoothSamples));
        if (thermalDetector != null) {
            float turb = thermalDetector.getSignalProcessor().getTurbulenceLevel();
            int reduction = (int) (turb * 80f);
            reduction = Math.min(reduction, n / 2);
            n = n - reduction;
            if (n < 1) n = 1;
        }
        float alpha = 1f - 1f / n;
        // Нижний порог снижен с 0.9 до 0.8, чтобы настройки пользователя (n=5→α=0.8) работали
        return Math.max(0.8f, Math.min(0.9999f, alpha));
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public float getVario() { return vario; }
    public float getAltRaw() { return altRaw; }
    public float getAltFiltered() { return altFiltered; }
    public float getBaselinePressure() { return baselinePressure; }
    public float getRecentSnr() { return recentSnr; }
    public float getMaxSnr() { return maxSnr; }
    public boolean isCalibrated() { return baroCalibCount > BARO_CALIB_SAMPLES; }
}
