package com.termo1.radar;

import android.graphics.Color;

/**
 * UiStateManager — расчёт состояния UI: статус полёта, L/D, vario avg, vario→цвет.
 * Не рисует — только считает то, что показывать.
 *
 * Выделен из inner class RadarView в MainActivity.
 */
public class UiStateManager {

    public static final String STATUS_SEARCH = "ПОИСК";
    public static final String STATUS_CLIMB = "ТЕРМИК!";
    public static final String STATUS_SINK = "СНИЖЕНИЕ";

    // Glide ratio buffer (8 sec window, 1 Hz)
    private static final int GLIDE_BUF_MAX = 16;
    private static final long GLIDE_WINDOW_SEC = 8;
    private final double[] glideLatBuf = new double[GLIDE_BUF_MAX];
    private final double[] glideLonBuf = new double[GLIDE_BUF_MAX];
    private final float[] glideVarioBuf = new float[GLIDE_BUF_MAX];
    private final long[] glideTimeBuf = new long[GLIDE_BUF_MAX];
    private int glideBufHead;
    private int glideBufCount;

    // Vario average buffer (30 samples = 30 sec)
    private static final int VARIO_AVG_COUNT = 30;
    private final float[] varioBuf = new float[VARIO_AVG_COUNT];
    private int varioBufIdx;
    private int varioBufLen;

    // Status
    private String currentStatus = "ПОИСК";
    private String previousStatus = "";

    public UiStateManager() {}

    // ========================================================================
    // Vario average (30 sec)
    // ========================================================================

    public void pushVarioSample(float vario) {
        varioBuf[varioBufIdx] = vario;
        varioBufIdx = (varioBufIdx + 1) % VARIO_AVG_COUNT;
        if (varioBufLen < VARIO_AVG_COUNT) varioBufLen++;
    }

    public float getAvgVario30() {
        if (varioBufLen == 0) return 0f;
        float sum = 0;
        for (int i = 0; i < varioBufLen; i++) sum += varioBuf[i];
        return sum / varioBufLen;
    }

    // ========================================================================
    // Glide ratio (8 sec window)
    // ========================================================================

    public void pushGlideSample(double lat, double lon, float vario, long nowMs, double pilotLat, double pilotLon) {
        // GPS spike check
        boolean isNewGlideSample = (glideBufCount == 0);
        if (!isNewGlideSample) {
            int lastGi = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;
            isNewGlideSample = (nowMs - glideTimeBuf[lastGi]) >= 1000;
        }
        if (!isNewGlideSample) return;

        if (glideBufCount > 0) {
            int lastGi = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;
            float[] distRes = new float[2];
            android.location.Location.distanceBetween(
                    glideLatBuf[lastGi], glideLonBuf[lastGi],
                    pilotLat, pilotLon, distRes);
            float dtSec = (nowMs - glideTimeBuf[lastGi]) / 1000f;
            float speedMs = (dtSec > 0) ? distRes[0] / dtSec : 0;
            if (speedMs > 30f) return; // spike
        }

        glideLatBuf[glideBufHead] = lat;
        glideLonBuf[glideBufHead] = lon;
        glideVarioBuf[glideBufHead] = vario;
        glideTimeBuf[glideBufHead] = nowMs;
        glideBufHead = (glideBufHead + 1) % GLIDE_BUF_MAX;
        if (glideBufCount < GLIDE_BUF_MAX) glideBufCount++;
    }

    public float calcGlideRatio(float heading, float compassHeading) {
        if (glideBufCount < 2) return 0;

        int newest = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;
        int oldest = newest;

        long newestTime = glideTimeBuf[newest];
        for (int i = 0; i < glideBufCount; i++) {
            int idx = (glideBufHead - 1 - i + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;
            if (newestTime - glideTimeBuf[idx] >= GLIDE_WINDOW_SEC * 1000) {
                oldest = idx;
                break;
            }
        }

        // net displacement
        float[] distBearing = new float[2];
        android.location.Location.distanceBetween(
                glideLatBuf[oldest], glideLonBuf[oldest],
                glideLatBuf[newest], glideLonBuf[newest], distBearing);
        float netDist = distBearing[0];
        float netBearing = distBearing[1];

        // Vario height diff (baro)
        float altDiffVario = 0;
        int count = 0;
        int idx = oldest;
        while (idx != newest) {
            int next = (idx + 1) % GLIDE_BUF_MAX;
            if (next == newest) break;
            float dt = (glideTimeBuf[next] - glideTimeBuf[idx]) / 1000f;
            altDiffVario += (-glideVarioBuf[idx]) * dt;
            idx = next;
            count++;
        }

        if (netDist < 5) return 0;
        if (altDiffVario <= 0.5f) return 99.0f;

        // going backward check
        boolean goingBackward = false;
        float bearingDiff = Math.abs(netBearing - compassHeading);
        if (bearingDiff > 180) bearingDiff = 360 - bearingDiff;
        if (bearingDiff > 120) goingBackward = true;

        float rawRatio = netDist / altDiffVario;
        if (goingBackward) rawRatio = -rawRatio;
        return Math.max(-99.0f, Math.min(99.0f, rawRatio));
    }

    public float calcRange(float glideRatio, float agl) {
        if (glideRatio >= 99f) return 99.9f;
        float range = agl * glideRatio / 1000f;
        return Math.max(-99.9f, Math.min(99.9f, range));
    }

    // ========================================================================
    // Vario → Color (trail gradient)
    // ========================================================================

    public static int varioToColor(float vario, float alpha) {
        final float[] keys = {-5f, -2f, -0.3f, 0f, 0.5f, 1.5f, 3f, 5f, 8f};
        final int[] colors = {
            0x001A00, 0x006600, 0x99CC66, 0xFFFF33,
            0xFFAA00, 0xFF4400, 0xFF0000, 0x660000, 0x330000
        };
        int hi = 1;
        while (hi < keys.length - 1 && vario > keys[hi]) hi++;
        int lo = hi - 1;
        float t = (keys[hi] - keys[lo] == 0f) ? 0f
                : (vario - keys[lo]) / (keys[hi] - keys[lo]);
        t = Math.max(0f, Math.min(1f, t));
        int r = (int)(((colors[lo] >> 16) & 0xFF) * (1f - t) + ((colors[hi] >> 16) & 0xFF) * t);
        int g = (int)(((colors[lo] >> 8) & 0xFF) * (1f - t) + ((colors[hi] >> 8) & 0xFF) * t);
        int b = (int)((colors[lo] & 0xFF) * (1f - t) + (colors[hi] & 0xFF) * t);
        int a = (int)(alpha * 200);
        if (a < 8) a = 8;
        if (a > 255) a = 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ========================================================================
    // Status
    // ========================================================================

    public String getStatus() { return currentStatus; }
    public String getPreviousStatus() { return previousStatus; }

    public void setStatus(String status) {
        if (!status.equals(currentStatus)) {
            previousStatus = currentStatus;
        }
        this.currentStatus = status;
    }

    public int getGlideBufCount() { return glideBufCount; }
    public int getGlideBufMax() { return GLIDE_BUF_MAX; }
    public double[] getGlideLatBuf() { return glideLatBuf; }
    public double[] getGlideLonBuf() { return glideLonBuf; }
    public float[] getGlideVarioBuf() { return glideVarioBuf; }
    public long[] getGlideTimeBuf() { return glideTimeBuf; }
    public int getGlideBufHead() { return glideBufHead; }
}
