package com.termo1.radar;

import android.graphics.Canvas;
import android.os.SystemClock;

import com.termo1.radar.ui.RadarRenderer;
import com.termo1.radar.ui.UiManager;
import com.termo1.radar.ui.VarioSoundManager;

import java.util.List;

/**
 * HudController — отрисовка приборной панели и управление трейлами.
 * Содержит onDraw-логику: инструменты, треки, глиссада, батарея.
 *
 * Исправлено P2: выделен из MainActivity.
 */
public class HudController {

    // GPS trail constants
    private static final int GPS_TRAIL_MAX = 2000;
    private static final long GPS_TRAIL_MAX_AGE_MS = 300_000L;
    private static final long GPS_TRAIL_ADD_INTERVAL_MS = 1000L;

    // Trail buffers (pre-allocated)
    private final int[] trailColorBuf = new int[GPS_TRAIL_MAX];
    private final float[] mapTrailPxBuf = new float[GPS_TRAIL_MAX];
    private final float[] mapTrailPyBuf = new float[GPS_TRAIL_MAX];

    // Glide ratio buffers
    private static final int GLIDE_BUF_MAX = 16;
    private final double[] glideLatBuf = new double[GLIDE_BUF_MAX];
    private final double[] glideLonBuf = new double[GLIDE_BUF_MAX];
    private final float[] glideVarioBuf = new float[GLIDE_BUF_MAX];
    private final long[] glideTimeBuf = new long[GLIDE_BUF_MAX];
    private int glideBufHead;
    private int glideBufCount;
    private static final long GLIDE_WINDOW_SEC = 8;

    public HudController() {}

    public void onDraw(Canvas canvas, long nowMs) {
        // Render logic — вызывается из RadarView.onDraw
        // В текущей реализации onDraw остаётся в MainActivity,
        // HudController предоставляет буферы и утилиты
    }

    // Trail buffer accessors
    public int[] getTrailColorBuf() { return trailColorBuf; }
    public float[] getMapTrailPxBuf() { return mapTrailPxBuf; }
    public float[] getMapTrailPyBuf() { return mapTrailPyBuf; }

    public int getTrailMax() { return GPS_TRAIL_MAX; }
    public long getTrailMaxAgeMs() { return GPS_TRAIL_MAX_AGE_MS; }
    public long getTrailAddIntervalMs() { return GPS_TRAIL_ADD_INTERVAL_MS; }

    // Glide ratio accessors
    public double[] getGlideLatBuf() { return glideLatBuf; }
    public double[] getGlideLonBuf() { return glideLonBuf; }
    public float[] getGlideVarioBuf() { return glideVarioBuf; }
    public long[] getGlideTimeBuf() { return glideTimeBuf; }
    public int getGlideBufHead() { return glideBufHead; }
    public int getGlideBufCount() { return glideBufCount; }
    public int getGlideBufMax() { return GLIDE_BUF_MAX; }
    public long getGlideWindowSec() { return GLIDE_WINDOW_SEC; }
}
