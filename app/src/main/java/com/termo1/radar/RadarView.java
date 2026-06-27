package com.termo1.radar;



import android.content.Context;

import android.graphics.Canvas;

import android.graphics.Color;

import android.graphics.DashPathEffect;

import android.graphics.Paint;

import android.graphics.Path;

import android.graphics.RectF;

import android.graphics.Bitmap;

import android.graphics.Typeface;

import android.os.SystemClock;

import android.view.MotionEvent;

import android.view.View;



import com.termo1.radar.core.TrackReplayer;

import com.termo1.radar.flight.CirclingManager;

import com.termo1.radar.flight.LiftDatabase;

import com.termo1.radar.flight.ThermalLocator;

import com.termo1.radar.gps.GpsManager;

import com.termo1.radar.logging.IgcLogger;

import com.termo1.radar.logging.LogManager;

import com.termo1.radar.map.StaticMapLoader;

import com.termo1.radar.model.ThermalBlip;

import com.termo1.radar.sensors.SensorController;

import com.termo1.radar.sensors.VarioManager;

import com.termo1.radar.core.ThermalDetector;

import com.termo1.radar.flight.VarioThermalDetector;

import com.termo1.radar.ThermalRadarService;

import com.termo1.radar.ui.VarioSoundManager;

import com.termo1.radar.ui.HudController;

import com.termo1.radar.ui.RadarRenderer;

import com.termo1.radar.ui.StatusManager;

import com.termo1.radar.ui.UiManager;

import com.termo1.radar.ui.VoiceController;



import java.util.ArrayList;

import java.util.List;

import java.util.Locale;

import android.content.Intent;

import android.location.Location;

import com.termo1.radar.ui.SettingsActivity;

import com.termo1.radar.core.SignalProcessor;

import java.util.Iterator;



public class RadarView extends View implements View.OnClickListener {



    /** Reference to MainActivity (package-private fields accessible in same package) */

    private final MainActivity a;





    // Gear button

    private final Paint gearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF gearRect = new RectF();

    private final Paint gearInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint gearToothPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // Exit button

    private final Paint exitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint exitXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF exitRect = new RectF();



    // Blind mode exit button

    private final Paint blindExitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF blindExitRect = new RectF();



    // SIM badge

    private final Paint simPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint simBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // Navigation buttons

    private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF calibBtnRect = new RectF();

    private final RectF startBtnRect = new RectF();



    // Test button

    private final Paint testBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint testBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF testBtnRect = new RectF();



    // Test overlay

    private final Paint testOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint testTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint testBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint testBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint testBarBgPaint = new Paint();

    private final Paint testBarFillPaint = new Paint();



    // Blind mode paints (reusable, не аллоцировать в onDraw)

    private final Paint blindBgPaint = new Paint();

    private final Paint blindTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint blindDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint blindVarioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // REC indicator paints (мигающая красная точка + текст)

    private final Paint recDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint recTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Battery level

    private final Paint batteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // Logging label

    private final Paint logLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Thermal label "крутим термик" (cached)

    private final Paint thermalLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Sensor data

    private final Paint sensorDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // === NEW: Instrument panel paints ===

    private final Paint varioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint flightTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint instrValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint instrLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // === NEW: Glide bar paint ===

    private final Paint glideBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // === NEW: Bottom separator paint ===

    private final Paint bottomSepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // Section heights (recalculated in onSizeChanged / onDraw)

    private float instrH, radarH, glideH, bottomH;

    private float radarCx, radarCy, radarR;



    private long lastAddedBlipBornMs = -1;

    private float touchX, touchY;

    private long touchDownTime;



    // Playback controls geometry (track mode)

    private final RectF pbSeekbarRect = new RectF();

    private final RectF pbPlayBtn = new RectF();

    private final RectF pbSpeedBtn = new RectF();

    private boolean pbDragging;

    private final Paint pbTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pbBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pbTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pbBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pbThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pbBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);



    // GPS trail render buffers (reused each frame)

    private final float[] trailPxBuf = new float[GPS_TRAIL_MAX];

    private final float[] trailPyBuf = new float[GPS_TRAIL_MAX];



    // Glide ratio buffer (8 sec window, 1 Hz samples)

    private static final int GLIDE_WINDOW_SEC = 8;

    private static final int GLIDE_BUF_MAX = 16;

    private final double[] glideLatBuf = new double[GLIDE_BUF_MAX];

    private final double[] glideLonBuf = new double[GLIDE_BUF_MAX];

    private final float[] glideVarioBuf = new float[GLIDE_BUF_MAX];

    private final long[] glideTimeBuf = new long[GLIDE_BUF_MAX];

    private int glideBufHead = 0;

    private int glideBufCount = 0;



    // Constants for ring buffer (originally from MainActivity)

    private static final int GPS_TRAIL_MAX = 2000;

    private static final long GPS_TRAIL_MAX_AGE_MS = 300_000L; // 5 минут



    // Vario average buffer (30 samples at 1 Hz = 30 sec)

    private static final int VARIO_AVG_COUNT = 30;

    private final float[] varioBuf = new float[VARIO_AVG_COUNT];

    private int varioBufIdx = 0;

    private int varioBufLen = 0;



    public RadarView(MainActivity activity) {

        super(activity);

        this.a = activity;

        setFocusable(true);

        setClickable(true);

        setOnClickListener(this);



        gearPaint.setStyle(Paint.Style.STROKE);

        gearPaint.setStrokeWidth(4);

        gearPaint.setColor(Color.argb(180, 255, 255, 255));

        gearInnerPaint.setStyle(Paint.Style.STROKE);

        gearInnerPaint.setStrokeWidth(3);

        gearInnerPaint.setColor(Color.argb(180, 255, 255, 255));

        gearToothPaint.setStyle(Paint.Style.FILL);

        gearToothPaint.setColor(Color.argb(180, 255, 255, 255));



        exitPaint.setStyle(Paint.Style.STROKE);

        exitPaint.setStrokeWidth(4);

        exitPaint.setColor(Color.argb(180, 255, 100, 100));

        exitXPaint.setStyle(Paint.Style.STROKE);

        exitXPaint.setStrokeWidth(5);

        exitXPaint.setColor(Color.argb(180, 255, 100, 100));

        exitXPaint.setStrokeCap(Paint.Cap.ROUND);



        simBgPaint.setStyle(Paint.Style.FILL);

        simBgPaint.setColor(Color.argb(80, 255, 193, 7));

        simPaint.setAntiAlias(true);

        simPaint.setTextSize(26);

        simPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        simPaint.setColor(Color.argb(220, 255, 193, 7));

        simPaint.setTextAlign(Paint.Align.LEFT);



        btnTextPaint.setAntiAlias(true);

        btnTextPaint.setColor(Color.argb(200, 0, 255, 0));

        btnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        btnTextPaint.setTextAlign(Paint.Align.CENTER);



        btnBgPaint.setStyle(Paint.Style.FILL);

        btnBgPaint.setColor(Color.argb(40, 0, 255, 0));

        btnBgPaint.setAntiAlias(true);



        testBtnBgPaint.setStyle(Paint.Style.FILL);

        testBtnBgPaint.setColor(Color.argb(50, 255, 193, 7));

        testBtnBgPaint.setAntiAlias(true);

        testBtnTextPaint.setAntiAlias(true);

        testBtnTextPaint.setColor(Color.argb(220, 255, 193, 7));

        testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        testBtnTextPaint.setTextAlign(Paint.Align.CENTER);



        testBgPaint.setStyle(Paint.Style.FILL);

        testBgPaint.setColor(Color.argb(200, 10, 10, 10));

        testOverlayPaint.setAntiAlias(true);

        testOverlayPaint.setColor(Color.argb(180, 0, 255, 0));

        testOverlayPaint.setTextSize(28);

        testOverlayPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        testOverlayPaint.setTextAlign(Paint.Align.LEFT);

        testTextPaint.setAntiAlias(true);

        testTextPaint.setColor(Color.argb(220, 255, 255, 255));

        testTextPaint.setTextSize(32);

        testTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        testTextPaint.setTextAlign(Paint.Align.LEFT);



        logLabelPaint.setTextSize(24);

        logLabelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        logLabelPaint.setTextAlign(Paint.Align.CENTER);

        logLabelPaint.setColor(Color.argb(200, 33, 150, 243));



        thermalLabelPaint.setColor(Color.argb(220, 255, 193, 7));

        thermalLabelPaint.setTextSize(42);

        thermalLabelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        thermalLabelPaint.setTextAlign(Paint.Align.CENTER);



        sensorDataPaint.setAntiAlias(true);

        sensorDataPaint.setTextSize(20);

        sensorDataPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        sensorDataPaint.setTextAlign(Paint.Align.LEFT);



        // === Instrument panel paints ===

        varioPaint.setAntiAlias(true);

        varioPaint.setTextSize(200); // 4x от 50

        varioPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        varioPaint.setTextAlign(Paint.Align.CENTER);



        flightTimePaint.setAntiAlias(true);

        flightTimePaint.setTextSize(88); // 4x

        flightTimePaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        flightTimePaint.setTextAlign(Paint.Align.CENTER);

        flightTimePaint.setColor(Color.argb(200, 0, 255, 255));



        instrValuePaint.setAntiAlias(true);

        instrValuePaint.setTextSize(24);

        instrValuePaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        instrValuePaint.setTextAlign(Paint.Align.CENTER);

        instrValuePaint.setColor(Color.argb(200, 0, 255, 0));



        instrLabelPaint.setAntiAlias(true);

        instrLabelPaint.setTextSize(16);

        instrLabelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        instrLabelPaint.setTextAlign(Paint.Align.CENTER);

        instrLabelPaint.setColor(Color.argb(140, 0, 255, 0));



        // === Glide bar paint ===

        glideBarPaint.setAntiAlias(true);

        glideBarPaint.setTextSize(112); // шрифт как у высот

        glideBarPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        glideBarPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        glideBarPaint.setTextAlign(Paint.Align.LEFT);

        glideBarPaint.setColor(Color.argb(200, 0, 255, 255));



        // === Bottom separator paint ===

        bottomSepPaint.setStyle(Paint.Style.FILL);

        bottomSepPaint.setColor(Color.argb(255, 30, 150, 255));

        bottomSepPaint.setStrokeWidth(2);



        // REC indicator

        recDotPaint.setStyle(Paint.Style.FILL);

        recDotPaint.setColor(Color.argb(220, 255, 50, 50));

        recTextPaint.setAntiAlias(true);

        recTextPaint.setColor(Color.argb(220, 255, 50, 50));

        recTextPaint.setTextSize(32);

        recTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        recTextPaint.setTextAlign(Paint.Align.LEFT);



        // Battery level paint

        batteryPaint.setAntiAlias(true);

        batteryPaint.setColor(Color.argb(200, 0, 255, 0));

        batteryPaint.setTextSize(22);

        batteryPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        batteryPaint.setTextAlign(Paint.Align.RIGHT);



        // Playback controls

        pbTrackPaint.setAntiAlias(true);

        pbTrackPaint.setColor(Color.argb(220, 50, 150, 255));

        pbTrackPaint.setTextSize(26);

        pbTrackPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        pbTrackPaint.setTextAlign(Paint.Align.LEFT);

        pbBgPaint.setStyle(Paint.Style.FILL);

        pbBgPaint.setColor(Color.argb(180, 10, 10, 10));

        pbTextPaint.setAntiAlias(true);

        pbTextPaint.setColor(Color.argb(200, 200, 200, 200));

        pbTextPaint.setTextSize(22);

        pbTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        pbTextPaint.setTextAlign(Paint.Align.CENTER);

        pbBarPaint.setStyle(Paint.Style.FILL);

        pbBarPaint.setColor(Color.argb(100, 100, 100, 100));

        pbThumbPaint.setStyle(Paint.Style.FILL);

        pbThumbPaint.setColor(Color.argb(220, 50, 150, 255));

        pbBtnPaint.setStyle(Paint.Style.STROKE);

        pbBtnPaint.setStrokeWidth(3);

        pbBtnPaint.setColor(Color.argb(200, 200, 200, 200));

    }



    @Override

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {

        super.onSizeChanged(w, h, oldw, oldh);

        if (w > 0 && h > 0) {

            // Calculate section heights

            instrH = h * 0.20f;

            radarH = h * 0.65f;

            glideH = h * 0.02f;   // small gap for glide bar

            float remainder = h - (instrH + radarH + glideH);

            bottomH = Math.max(remainder, h * 0.10f);



            radarCx = w / 2f;

            radarCy = instrH + radarH / 2f;

            radarR = Math.min(w / 2f, radarH / 2f) - 4;



            // Tell a.radarRenderer about reduced height so it centers correctly

            a.radarRenderer.onSizeChanged(w, (int)radarH);

            exitRect.set(8, 8, 8 + 84, 8 + 84);

            float gearSize = 84f;

            gearRect.set(w - gearSize - 24, 24, w - 24, gearSize + 24);



            // ЗАПИСЬ button in bottom panel

            float btnH = 50f;

            float btnW = w * 0.28f;

            float bottomPanelY = h - bottomH;

            float btnRowY = bottomPanelY + 12;



            calibBtnRect.set(w * 0.03f, btnRowY, w * 0.03f + btnW, btnRowY + btnH);

            startBtnRect.set(w * 0.97f - btnW, btnRowY, w * 0.97f, btnRowY + btnH);



            // Test/stop button in bottom panel (below ЗАПИСЬ/СТОП)

            float testBtnW = 160f;

            float testBtnH = 44f;

            testBtnRect.set(w / 2f - testBtnW / 2f, btnRowY + btnH + 8,

                    w / 2f + testBtnW / 2f, btnRowY + btnH + 8 + testBtnH);

        }

    }



    @Override

    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        int w = getWidth();

        int h = getHeight();

        if (w <= 0 || h <= 0) return;



        // Blind mode: минимальный UI, минимум яркости, но видно

        if (a.blindModeEnabled) {

            canvas.drawColor(Color.rgb(5, 5, 5));

            blindTextPaint.setColor(Color.argb(180, 0, 255, 0));

            blindTextPaint.setTextSize(48);

            blindTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            blindTextPaint.setTextAlign(Paint.Align.CENTER);

            blindTextPaint.setFakeBoldText(true);

            String status = a.flightStateMachine.isFlying() ? "Слепой полёт" : "Ожидание...";

            canvas.drawText(status, w/2f, h/3f, blindTextPaint);



            // В слепом режиме показываем ключевые данные: варио, высоту, время

            blindDataPaint.setTextSize(36);

            blindDataPaint.setFakeBoldText(false);

            blindDataPaint.setColor(Color.argb(160, 100, 200, 255));

            float varioVal2 = a.sensorController.getVario();

            String varioStr = String.format(java.util.Locale.US, "%s%.1f м/с", varioVal2 >= 0 ? "+" : "", varioVal2);

            canvas.drawText(varioStr, w/2f, h/2f, blindDataPaint);



            blindDataPaint.setColor(Color.argb(120, 255, 255, 255));

            blindDataPaint.setTextSize(28);

            float alt2 = a.gpsManager.getAltitude();

            float startAlt2 = a.gpsManager.getStartAltitude();

            float agl2 = a.gpsManager.isAltitudeInitialized() ? (alt2 - startAlt2) : 0f;

            canvas.drawText(String.format(java.util.Locale.US, "%.0f м MSL  +%.0f м AGL", alt2, agl2), w/2f, h/2f + 60, blindDataPaint);



            // Время полёта

            if (a.logManager.isLogging()) {

                long flightSec2 = (SystemClock.elapsedRealtime() - a.logManager.getFlightStartMs()) / 1000;

                long hh2 = flightSec2 / 3600, mm2 = (flightSec2 % 3600) / 60, ss2 = flightSec2 % 60;

                blindDataPaint.setColor(Color.argb(100, 255, 255, 255));

                canvas.drawText(String.format("%02d:%02d:%02d", hh2, mm2, ss2), w/2f, h/2f + 100, blindDataPaint);

            }

            return;

        }



        // ===== Calculate section boundaries =====

        float localInstrH = h * 0.20f;

        float localRadarH = h * 0.65f;

        float localGlideH = h * 0.02f;

        float localBottomSectionH = h - (localInstrH + localRadarH + localGlideH);

        if (localBottomSectionH < h * 0.10f) localBottomSectionH = h * 0.10f;

        float localRadarCy2 = localRadarH / 2f; // center within radar section

        float localRadarR2 = Math.min(w / 2f, localRadarH / 2f) - 4;



        // Sound + WakeLock refresh

        if (a.varioSoundManager != null) {

            a.varioSoundManager.update(a.sensorController.getVario());

        }

        a.refreshWakeLock();



        // Update foreground service notification

        ThermalRadarService svc = ThermalRadarService.getInstance();

        if (svc != null) {

            svc.updateNotification(a.logManager.isLogging(), a.sensorController.getVario());

        }



        // Push GPS cache to LogManager (1 Гц данные, пишутся в каждом сэмпле)

        a.logManager.updateGpsCache(

            a.gpsManager.getLat(), a.gpsManager.getLon(),

            a.gpsManager.getAltitude(), a.gpsManager.getSpeed(), a.gpsManager.getHeading(),

            a.gpsManager.getAccuracy(), a.gpsManager.getFixAgeMs());



        // Static map: обновить если сместились >500м

        double mapLat, mapLon;

        boolean mapHasPosition = false;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            mapLat = a.trackReplayer.getLat();

            mapLon = a.trackReplayer.getLon();

            mapHasPosition = (mapLat != 0.0 && mapLon != 0.0);

        } else {

            mapLat = a.gpsManager.getLat();

            mapLon = a.gpsManager.getLon();

            mapHasPosition = a.gpsManager.isReady() && mapLat != 0.0 && mapLon != 0.0;

        }

        if (mapHasPosition) {

            a.staticMapLoader.updateIfNeeded(mapLat, mapLon, 14);

            if (a.radarRenderer.isMapRefreshNeeded()) {

                a.staticMapLoader.forceUpdate(mapLat, mapLon, 14);

            }

        }



        // IGC logger: 1 Гц GPS + сэмпл

        a.igcLogger.updateGps(

            a.gpsManager.getLat(), a.gpsManager.getLon(),

            a.gpsManager.getAltitude(),

            a.sensorController.getAltitudeRaw(),

            a.gpsManager.getSpeed(), a.gpsManager.getHeading(),

            a.gpsManager.getAccuracy(), a.gpsManager.getFixAgeMs());

        a.igcLogger.recordSample();



        // Track circling/label/wind state transitions for event log

        boolean nowCircling = a.circlingManager.isCircling();

        boolean nowLabel = a.circlingManager.isShowThermalLabel();

        if (nowCircling && !a.prevCirclingState) {

            a.logManager.recordEvent("CIRCLING_START", "circling confirmed");

            a.igcLogger.recordEvent("C", "circling_start");

            double lat = a.gpsManager.getLat();

            double lon = a.gpsManager.getLon();

            if (lat != 0.0 && lon != 0.0 && a.entryMarkers.size() < MainActivity.MAX_MARKERS) {

                a.entryMarkers.add(new double[]{lat, lon});

            }

        } else if (!nowCircling && a.prevCirclingState) {

            a.logManager.recordEvent("CIRCLING_END", "circling stopped");

            a.igcLogger.recordEvent("C", "circling_stop");

            double lat = a.gpsManager.getLat();

            double lon = a.gpsManager.getLon();

            if (lat != 0.0 && lon != 0.0 && a.exitMarkers.size() < MainActivity.MAX_MARKERS) {

                a.exitMarkers.add(new double[]{lat, lon});

            }

        }

        a.prevCirclingState = nowCircling;



        if (nowLabel && !a.prevLabelState) {

            a.logManager.recordEvent("THERMAL_LABEL_ON", "540 deg reached");

            a.igcLogger.recordEvent("T", "thermal_label_on");

        } else if (!nowLabel && a.prevLabelState) {

            a.logManager.recordEvent("THERMAL_LABEL_OFF", "label hidden");

            a.igcLogger.recordEvent("T", "thermal_label_off");

        }

        a.prevLabelState = nowLabel;



        float wf = a.circlingManager.getWindFromDeg();

        float ws = a.circlingManager.getDisplayWindSpeed();

        if (wf >= 0 && ws > 0) {

            if (Math.abs(wf - a.prevWindFrom) > 10f || Math.abs(ws - a.prevWindSpd) > 0.5f) {

                a.logManager.recordEvent("WIND_UPDATE",

                        String.format(java.util.Locale.US, "%.0fdeg %.1fm/s", wf, ws));

                a.igcLogger.recordEvent("W",

                        String.format(java.util.Locale.US, "%.0f %.1f", wf, ws));

                a.prevWindFrom = wf;

                a.prevWindSpd = ws;

            }

        }



        // MaxSnr periodic reset (каждые 5 мин)

        long nowMs2 = System.currentTimeMillis();

        if (nowMs2 - a.lastMaxSnrResetMs > 300_000) {

            a.sensorController.resetMaxSnr();

            a.lastMaxSnrResetMs = nowMs2;

        }



        // Сохранить калибровку наклона после завершения (однократно)

        if (a.sensorController.getMountTiltDeg() > 0.1f

                && a.prefs.getFloat("mount_tilt_deg", -1f) != a.sensorController.getMountTiltDeg()) {

            a.sensorController.saveMountTilt(a.prefs);

            android.util.Log.i("TERMO1", "Tilt calibration saved: "

                    + a.sensorController.getMountTiltDeg() + "°");

        }



        // Яркость: макс в полёте при записи лога или в солнечном режиме

        boolean sunMode = a.prefs.getBoolean("sunlight_mode", false);

        if ((a.flightStateMachine.isFlying() && a.logManager.isLogging()) || sunMode) {

            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();

            if (lp.screenBrightness < 1.0f) {

                lp.screenBrightness = 1.0f;

                a.getWindow().setAttributes(lp);

            }

        } else if (a.blindModeEnabled) {

            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();

            if (lp.screenBrightness > 0.16f || lp.screenBrightness < 0.14f) {

                lp.screenBrightness = 0.15f;

                a.getWindow().setAttributes(lp);

            }

        }



        // Update status

        a.updateStatus();



        // Вибрация при смене статуса на термик/набор

        if (!a.currentStatus.equals(a.previousStatus)) {

            a.previousStatus = a.currentStatus;

            if ((a.currentStatus.equals(UiManager.STATUS_THERMAL)

                    || a.currentStatus.equals(UiManager.STATUS_CLIMB))

                    && a.prefs.getBoolean("vibrate_enabled", true)) {

                android.os.Vibrator vib = (android.os.Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);

                if (vib != null && vib.hasVibrator()) {

                    if (android.os.Build.VERSION.SDK_INT >= 26) {

                        vib.vibrate(android.os.VibrationEffect.createOneShot(300,

                                android.os.VibrationEffect.DEFAULT_AMPLITUDE));

                    } else {

                        vib.vibrate(300);

                    }

                }

            }

        }



        // Thermal blips from detector

        if (a.thermalDetector != null && !a.simMode && !a.scenarioMode && !a.trackMode) {

            ThermalBlip detBlip = a.thermalDetector.getCurrentBlip();

            if (detBlip != null) {

                long now = System.currentTimeMillis();

                if (now - detBlip.bornMs < detBlip.lifeMs) {

                    synchronized (a.thermalLock) {

                        Iterator<ThermalBlip> it = a.thermals.iterator();

                        while (it.hasNext()) {

                            ThermalBlip expired = it.next();

                            if (!expired.isAlive(now)) {

                                it.remove();

                                a.logManager.recordEvent("THERMAL_REMOVED",

                                        "bornMs=" + expired.bornMs

                                        + " angle=" + (int)expired.angle

                                        + " dist=" + (int)expired.distance

                                        + " strength=" + String.format(java.util.Locale.US, "%.1f", expired.strength));

                            }

                        }

                        boolean found = false;

                        for (ThermalBlip tb : a.thermals) {

                            if (tb.bornMs == detBlip.bornMs) {

                                tb.distance = detBlip.distance;

                                tb.strength = detBlip.strength;

                                tb.sizeFactor = detBlip.sizeFactor;

                                tb.angle = detBlip.angle;

                                found = true;

                                break;

                            }

                        }

                        if (!found && detBlip.bornMs != lastAddedBlipBornMs) {

                            lastAddedBlipBornMs = detBlip.bornMs;

                            a.thermals.add(detBlip);

                            a.logManager.recordEvent("THERMAL_NEW",

                                    "bornMs=" + detBlip.bornMs

                                    + " angle=" + (int)detBlip.angle

                                    + " dist=" + (int)detBlip.distance

                                    + " strength=" + String.format(java.util.Locale.US, "%.1f", detBlip.strength));

                            if (a.voicePromptsEnabled && a.ttsReady) {

                                a.speakThermalDirection(detBlip.angle, detBlip.distance);

                            }

                            while (a.thermals.size() > MainActivity.THERMAL_LIMIT) a.thermals.remove(0);

                        }

                    }

                    long nowMs = System.currentTimeMillis();

                    if (nowMs - a.lastThermalBeepMs > 3000) {

                        if (a.varioSoundManager != null && SystemClock.elapsedRealtime() - a.lastThermalBeepRealMs >= MainActivity.THERMAL_BEEP_INTERVAL_MS) {

                            a.lastThermalBeepRealMs = SystemClock.elapsedRealtime();

                            a.varioSoundManager.playThermalBeep();

                        }

                        a.lastThermalBeepMs = nowMs;

                    }

                }

            }

        }



        // ========================================================================

        // INSTRUMENT PANEL (top 20%)

        // ========================================================================

        // Status text at the VERY TOP line

        Paint.FontMetrics fm = instrLabelPaint.getFontMetrics();

        float topStatusH = 28;

        instrLabelPaint.setColor(Color.argb(220, 255, 193, 7));

        instrLabelPaint.setTextSize(topStatusH);

        instrLabelPaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText(a.currentStatus, w / 2f, topStatusH, instrLabelPaint);

        instrLabelPaint.setTextSize(32);

        instrLabelPaint.setTextAlign(Paint.Align.CENTER);



        float instrMidY = localInstrH / 2f;

        float colX_left = w * 0.12f;

        float colX_center = w / 2f;

        float colX_right = w * 0.88f;



        // Speed: из реплеера или GPS

        float gpsSpeed;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            gpsSpeed = a.trackReplayer.getSpeed();

        } else {

            gpsSpeed = a.gpsManager.getSpeed(); // м/с

        }

        float gpsAlt, startAlt, gpsAgl;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            gpsAlt = a.trackReplayer.getAltitude();

            startAlt = a.trackReplayer.getLaunchAltitude();

            gpsAgl = Math.max(0, gpsAlt - startAlt);

        } else {

            gpsAlt = a.gpsManager.getAltitude();

            startAlt = a.gpsManager.getStartAltitude();

            gpsAgl = a.gpsManager.isAltitudeInitialized() ? (gpsAlt - startAlt) : 0f;

        }



        instrValuePaint.setTextAlign(Paint.Align.CENTER);

        instrValuePaint.setTextSize(112); // 4x

        instrLabelPaint.setTextAlign(Paint.Align.CENTER);

        instrLabelPaint.setTextSize(32); // labels чуть крупнее



        // Top row (values): all values aligned horizontally

        float valueRowY = instrMidY + 10; // подняли на строку выше



        // Below values — labels for speed/wind (ABOVE their values on next row)

        float instrLabelY = valueRowY + 20;



        // ========================================================================

        // INSTRUMENTS: собрать данные, отрисовать через HudController

        // ========================================================================



        // Speed + backward detection

        float compHdg, gpsTrk;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            compHdg = a.trackReplayer.getCompassHeading();

            gpsTrk = a.trackReplayer.getHeading();

        } else {

            compHdg = a.getCompassHeading();

            gpsTrk = a.gpsManager.getHeading();

        }

        float hdgDiff = Math.abs(compHdg - gpsTrk);

        if (hdgDiff > 180f) hdgDiff = 360f - hdgDiff;

        boolean goingBack = hdgDiff > 120f;

        float displaySpeedKmh = gpsSpeed * 3.6f;

        if (goingBack) displaySpeedKmh = -displaySpeedKmh;



        // MSL + AGL

        float gpsAltVal, aglVal;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            gpsAltVal = a.trackReplayer.getAltitude();

            aglVal = Math.max(0, gpsAltVal - a.trackReplayer.getLaunchAltitude());

        } else {

            gpsAltVal = a.gpsManager.getAltitude();

            float startAltVal = a.gpsManager.getStartAltitude();

            aglVal = a.gpsManager.isAltitudeInitialized() ? (gpsAltVal - startAltVal) : 0f;

        }



        // Vario

        float varioVal = a.sensorController.getVario();

        if (a.scenarioMode && a.flightSim != null && a.flightSim.isRunning()) {

            varioVal = a.flightSim.getVario();

        } else if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            varioVal = a.trackReplayer.getVario();

        }

        pushVarioSample(varioVal);

        float avgVario30 = getAvgVario30();



        // Flight time

        long flightTimeMs;

        if (a.simMode) {

            flightTimeMs = SystemClock.elapsedRealtime() - a.simStartMs;

        } else if (a.scenarioMode) {

            flightTimeMs = SystemClock.elapsedRealtime() - a.scenarioStartMs;

        } else if (a.trackMode && a.trackReplayer != null) {

            flightTimeMs = (long)(a.trackReplayer.getCurrentTime() * 1000);

        } else if (a.logManager.isLogging()) {

            flightTimeMs = System.currentTimeMillis() - a.logManager.getFlightStartMs();

        } else {

            flightTimeMs = 0;

        }



        // Wind

        float windDeg, windSpdMs;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            windDeg = a.trackReplayer.getWindFromDeg();

            windSpdMs = a.trackReplayer.getWindSpeedMs();

        } else {

            windDeg = a.circlingManager.getWindFromDeg();

            windSpdMs = a.circlingManager.getDisplayWindSpeed();

        }



        // Build HudData and render

        HudController.HudData hudData = new HudController.HudData();

        hudData.displaySpeedKmh = displaySpeedKmh;

        hudData.gpsSpeed = gpsSpeed;

        hudData.goingBack = goingBack;

        hudData.gpsAltVal = gpsAltVal;

        hudData.aglVal = aglVal;

        hudData.varioVal = varioVal;

        hudData.avgVario30 = avgVario30;

        hudData.windDeg = windDeg;

        hudData.windSpdMs = windSpdMs;

        hudData.flightTimeMs = flightTimeMs;

        hudData.colX_left = colX_left;

        hudData.colX_center = colX_center;

        hudData.colX_right = colX_right;

        hudData.valueRowY = valueRowY;

        hudData.instrLabelY = instrLabelY;

        a.hudController.drawInstruments(canvas, hudData);



        // ========================================================================

        // RADAR SECTION (middle 65%), drawn in translated canvas

        // RADAR SECTION (middle 65%), drawn in translated canvas

        canvas.save();

        canvas.translate(0, localInstrH);

        // Clip radar area so drawColor doesn't paint over instruments

        canvas.clipRect(0, 0, w, localRadarH);

        // Draw black background for radar area

        canvas.drawColor(Color.rgb(0, 0, 0));



        // GPS trail update + precompute pixel positions (using radar-local coordinates)

        float trailCy = localRadarH / 2f;

        float trailR = Math.min(w / 2f, trailCy) - 4;

        int trailCount = 0;

        int mapTrailCount = 0;

        boolean gpsOk;

        double pilotLat, pilotLon;

        if (a.scenarioMode && a.flightSim != null && a.flightSim.isRunning()) {

            gpsOk = true;

            pilotLat = a.flightSim.getLat();

            pilotLon = a.flightSim.getLon();

        } else if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            gpsOk = true;

            pilotLat = a.trackReplayer.getLat();

            pilotLon = a.trackReplayer.getLon();

        } else {

            gpsOk = a.gpsManager.isReady() && a.gpsManager.getFixAgeMs() < 5000;

            pilotLat = a.gpsManager.getLat();

            pilotLon = a.gpsManager.getLon();

        }



        if (gpsOk) {

            // Исправлено MA-4: в a.trackMode используем sim-время, не wall-clock

            long now;

            if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

                now = (long)(a.trackReplayer.getCurrentTime() * 1000);

            } else {

                now = System.currentTimeMillis();

            }



            float currentVario;

            if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

                currentVario = a.trackReplayer.getVario();

            } else {

                currentVario = a.sensorController.getVario();

            }

            // MA-5: ring buffer TrailPoint[] вместо ArrayList<double[]> — без аллокаций

            {

                MainActivity.TrailPoint lastPt = null;

                if (trailCount > 0) {

                    int lastIdx = (a.trailHead - 1 + GPS_TRAIL_MAX) % GPS_TRAIL_MAX;

                    lastPt = a.trailBuf[lastIdx];

                }

                if (lastPt == null || (now - lastPt.timeMs) >= MainActivity.GPS_TRAIL_ADD_INTERVAL_MS) {

                    // Фильтр GPS-спиков — в a.trackMode отключаем

                    boolean isSpike = false;

                    if (lastPt != null

                            && !(a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning())) {

                        android.location.Location.distanceBetween(

                                lastPt.lat, lastPt.lon, pilotLat, pilotLon, a.distanceResult);

                        float speedMs = a.distanceResult[0] / ((now - lastPt.timeMs) / 1000f);

                        if (speedMs > 30f) {

                            isSpike = true;

                        }

                    }

                    if (isSpike && lastPt != null) {

                        pilotLat = lastPt.lat;

                        pilotLon = lastPt.lon;

                    }

                    if (a.trailBuf[a.trailHead] == null) a.trailBuf[a.trailHead] = new MainActivity.TrailPoint();

                    a.trailBuf[a.trailHead].lat = pilotLat;

                    a.trailBuf[a.trailHead].lon = pilotLon;

                    a.trailBuf[a.trailHead].timeMs = now;

                    a.trailBuf[a.trailHead].vario = currentVario;

                    a.trailHead = (a.trailHead + 1) % GPS_TRAIL_MAX;

                    if (trailCount < GPS_TRAIL_MAX) trailCount++;

                }

            }



            // Push to glide ratio buffer (same 1 Hz rate)

            boolean isNewGlideSample = (glideBufCount == 0);

            if (!isNewGlideSample) {

                int lastGi = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;

                isNewGlideSample = (now - glideTimeBuf[lastGi]) >= 1000;

            }

            if (isNewGlideSample) {

                // Для буфера L/D тоже фильтруем спики

                if (glideBufCount > 0) {

                    int lastGi = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;

                    // Исправлено MA-5: используем pre-allocated a.distanceResult вместо new float[2]

                    android.location.Location.distanceBetween(

                            glideLatBuf[lastGi], glideLonBuf[lastGi],

                            pilotLat, pilotLon, a.distanceResult);

                    float dtSec = (now - glideTimeBuf[lastGi]) / 1000f;

                    float speedMs = (dtSec > 0) ? a.distanceResult[0] / dtSec : 0;

                    if (speedMs > 30f) {

                        // Спик — повторяем предыдущую позицию

                        pilotLat = glideLatBuf[lastGi];

                        pilotLon = glideLonBuf[lastGi];

                    }

                }

                glideLatBuf[glideBufHead] = pilotLat;

                glideLonBuf[glideBufHead] = pilotLon;

                glideVarioBuf[glideBufHead] = currentVario;

                glideTimeBuf[glideBufHead] = now;

                glideBufHead = (glideBufHead + 1) % GLIDE_BUF_MAX;

                if (glideBufCount < GLIDE_BUF_MAX) glideBufCount++;

            }



            // MA-5: ring buffer iteration — age filter inline, без ArrayList.remove()

            {

                int start = (trailCount < GPS_TRAIL_MAX) ? 0

                        : (a.trailHead) % GPS_TRAIL_MAX;

                int n = Math.min(trailCount, GPS_TRAIL_MAX);

                int idx = start;

                for (int i = 0; i < n; i++) {

                    MainActivity.TrailPoint pt = a.trailBuf[idx];

                    if (pt == null) { idx = (idx + 1) % GPS_TRAIL_MAX; continue; }

                    long age = now - pt.timeMs;

                    if (age > GPS_TRAIL_MAX_AGE_MS || age < 0) {

                        idx = (idx + 1) % GPS_TRAIL_MAX;

                        continue;

                    }

                    float brightness = 1.0f - (float) age / (float) GPS_TRAIL_MAX_AGE_MS;

                    if (brightness < 0.01f) { idx = (idx + 1) % GPS_TRAIL_MAX; continue; }



                    android.location.Location.distanceBetween(pilotLat, pilotLon, pt.lat, pt.lon, a.distanceResult);

                    float dist = a.distanceResult[0];

                    float bearingRad = (float) Math.toRadians(a.distanceResult[1]);



                    float trailRadiusM = (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning())

                            ? 1500f : 150f;

                    float distPx = (dist / trailRadiusM) * trailR;

                    if (distPx <= trailR) {

                        trailPxBuf[trailCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;

                        trailPyBuf[trailCount] = trailCy - (float) Math.cos(bearingRad) * distPx;

                        a.trailColorBuf[trailCount] = MainActivity.varioToColor(pt.vario, brightness);

                        trailCount++;

                    }



                    float mapDistPx = (dist / 1500f) * trailR;

                    float mapHalf = (3671f / 1500f) * trailR / 2f;

                    if (mapDistPx <= mapHalf) {

                        a.mapTrailPxBuf[mapTrailCount] = (w / 2f) + (float) Math.sin(bearingRad) * mapDistPx;

                        a.mapTrailPyBuf[mapTrailCount] = trailCy - (float) Math.cos(bearingRad) * mapDistPx;

                        mapTrailCount++;

                    }



                    idx = (idx + 1) % GPS_TRAIL_MAX;

                }

            }



            int markerCount = 0;

            for (double[] pt : a.entryMarkers) {

                android.location.Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], a.distanceResult);

                float dist = a.distanceResult[0];

                float bearingRad = (float) Math.toRadians(a.distanceResult[1]);

                float distPx = (dist / 150f) * trailR;

                if (distPx > trailR) continue;

                a.markerPxBuf[markerCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;

                a.markerPyBuf[markerCount] = trailCy - (float) Math.cos(bearingRad) * distPx;

                a.markerIsEntry[markerCount] = true;

                markerCount++;

            }



            for (double[] pt : a.exitMarkers) {

                android.location.Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], a.distanceResult);

                float dist = a.distanceResult[0];

                float bearingRad = (float) Math.toRadians(a.distanceResult[1]);

                float distPx = (dist / 150f) * trailR;

                if (distPx > trailR) continue;

                a.markerPxBuf[markerCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;

                a.markerPyBuf[markerCount] = trailCy - (float) Math.cos(bearingRad) * distPx;

                a.markerIsEntry[markerCount] = false;

                markerCount++;

            }



            a.radarRenderer.setTrailMarkers(a.markerPxBuf, a.markerPyBuf, a.markerIsEntry, markerCount);



            float[] liftValues = a.liftDatabase.getLiftValues();

            a.radarRenderer.setSectorLiftData(liftValues);

        } else {

            a.trailHead = 0;

            trailCount = 0;

        }



        // Draw radar

        long nowMs = System.currentTimeMillis();

        // Thermals copy через RadarViewController (уже работает)

        a.radarViewController.syncThermals(a.thermals);

        a.thermalsCopy.clear();

        for (ThermalBlip t : a.radarViewController.getThermalsCopy()) {

            a.thermalsCopy.add(t);

        }

        if (a.scenarioMode && a.flightSim != null && a.flightSim.isRunning()) {

            a.radarRenderer.setThermalCore(

                a.flightSim.isShowRedCore(),

                a.flightSim.getThermalBearing(),

                a.flightSim.getThermalDistance(),

                a.flightSim.getThermalRadius());

            a.radarRenderer.setWindData(

                a.flightSim.getWindFromDeg(),

                a.flightSim.getWindSpeedMs());

        } else if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            a.radarRenderer.setThermalCore(

                a.trackReplayer.isShowRedCore(),

                a.trackReplayer.getThermalBearing(),

                a.trackReplayer.getThermalDistance(),

                a.trackReplayer.getThermalRadius());

            a.radarRenderer.setWindData(

                a.trackReplayer.getWindFromDeg(),

                a.trackReplayer.getWindSpeedMs());

        } else {

            a.radarRenderer.setThermalCore(false, 0, 0, 0);

            a.radarRenderer.setWindData(

                a.circlingManager.getWindFromDeg(),

                a.circlingManager.getDisplayWindSpeed());

        }



        int bestSector = a.liftDatabase.getBestSectorIndex();

        if (bestSector >= 0) {

            a.radarRenderer.setBestLiftSector(

                    bestSector,

                    a.liftDatabase.getBestSectorLift(),

                    a.liftDatabase.getBestSectorDirection());

        } else {

            a.radarRenderer.setBestLiftSector(-1, 0, "");

        }



        boolean showStats = (!a.trackMode && (a.circlingManager.isCircling() || a.circlingManager.isShowThermalLabel()))

                || (a.trackMode && a.trackReplayer != null && a.trackReplayer.isShowRedCore());

        String coreText = "";

        if (a.thermalLocator.isEstimateValid()) {

            coreText = String.format(java.util.Locale.US,

                    "Центр: %.0f° %.0fм",

                    a.thermalLocator.getThermalBearing(),

                    a.thermalLocator.getThermalDistance());

        }

        if (a.lastDrift != null && Math.abs(a.lastDrift.driftAngle) > 2f) {

            if (coreText.length() > 0) coreText += " | ";

            coreText += a.lastDrift.guidanceText;

        }

        float thermalBaseMsl = 0;

        if (a.lastThermalBaseResult != null && a.lastThermalBaseResult.valid) {

            thermalBaseMsl = (float) a.lastThermalBaseResult.groundAltitude;

        }

        a.radarRenderer.setThermalStats(

                a.circlingManager.isCircling() ? a.sensorController.getVario() : 0,

                a.gpsManager.getAltitude(),

                thermalBaseMsl,

                coreText,

                showStats);



        float headingDisplay = a.getCompassHeading();

        long headingFrameMs = SystemClock.elapsedRealtime();

        if (!a.headingDisplayInitialized) {

            a.headingDisplaySmoothed = headingDisplay;

            a.headingDisplayInitialized = true;

            a.lastHeadingFrameMs = headingFrameMs;

        } else {

            double dtSec = (headingFrameMs - a.lastHeadingFrameMs) / 1000.0;

            a.lastHeadingFrameMs = headingFrameMs;

            double smoothingFactor = 1.0 - Math.pow(0.1, dtSec / 0.1);

            if (smoothingFactor > 1.0) smoothingFactor = 1.0;

            if (smoothingFactor < 0.01) smoothingFactor = 0.01;

            float diff = ((headingDisplay - a.headingDisplaySmoothed + 540f) % 360f) - 180f;

            a.headingDisplaySmoothed = (float)((a.headingDisplaySmoothed + diff * smoothingFactor + 360f) % 360f);

        }

        float headingDisplayFinal = a.headingDisplaySmoothed;

        float varioDisplay = a.sensorController.getVario();

        if (a.scenarioMode && a.flightSim != null && a.flightSim.isRunning()) {

            headingDisplay = a.flightSim.getHeading();

            varioDisplay = a.flightSim.getVario();

        } else if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            headingDisplay = a.trackReplayer.getHeading();

            varioDisplay = a.trackReplayer.getVario();

            // Исправлено MA-7: не зануляем heading в a.trackMode — радар track-up

        }



        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            a.radarRenderer.setPilotPosition(a.trackReplayer.getLat(), a.trackReplayer.getLon());

        } else {

            a.radarRenderer.setPilotPosition(a.gpsManager.getLat(), a.gpsManager.getLon());

        }



        // MA-4: IGC track polyline for a.trackMode — весь трек из файла, не только накопленный gpsTrail

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            java.util.List<TrackReplayer.TrackPoint> track = a.trackReplayer.getTrack();

            if (track != null && track.size() > 1) {

                int n = track.size();

                double[] trLats = new double[n];

                double[] trLons = new double[n];

                for (int i = 0; i < n; i++) {

                    trLats[i] = track.get(i).lat;

                    trLons[i] = track.get(i).lon;

                }

                a.radarRenderer.setTrackPolyline(trLats, trLons, n);

            }

        } else {

            a.radarRenderer.setTrackPolyline(null, null, 0);

        }



        a.radarRenderer.draw(canvas, nowMs, a.thermalsCopy,

                headingDisplayFinal, varioDisplay, a.currentStatus,

                a.sensorController.getMaxSnr(), a.thermalsCopy.size(),

                trailPxBuf, trailPyBuf, a.trailColorBuf, trailCount,

                a.mapTrailPxBuf, a.mapTrailPyBuf, mapTrailCount);



        // HUD on radar

        float radarCxLocal = w / 2f;

        a.uiManager.drawVario(canvas, radarCxLocal, 130, varioDisplay);

        a.uiManager.drawStatus(canvas, radarCxLocal, a.currentStatus);



        // "крутим термик" — по центру радара (исправлено MA-9: a.trackMode → a.trackReplayer)

        boolean showCirclingLabel = (!a.trackMode && a.circlingManager.isShowThermalLabel())

                || (a.trackMode && a.trackReplayer != null && a.trackReplayer.isShowRedCore());

        if (showCirclingLabel) {

            float labelY = trailCy;

            canvas.drawText("крутим термик", radarCxLocal, labelY, thermalLabelPaint);

        }



        // Guidance text from flight scenario (on radar)

        boolean showGuide = (a.scenarioMode && a.flightSim != null && a.flightSim.isRunning())

                || (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning());

        if (showGuide) {

            String guide = a.scenarioMode ? a.flightSim.getGuidanceText() : a.trackReplayer.getGuidanceText();

            if (guide != null && guide.length() > 0) {

                float guideY = trailCy + (a.scenarioMode && a.flightSim != null && a.flightSim.isCircling() ? 0 : -80);

                thermalLabelPaint.setColor(Color.argb(200, 255, 235, 59));

                thermalLabelPaint.setTextSize(36);

                canvas.drawText(guide, radarCxLocal, guideY, thermalLabelPaint);

                thermalLabelPaint.setColor(Color.argb(220, 255, 193, 7));

                thermalLabelPaint.setTextSize(42);

            }

        }



        if (a.testMode) a.updateTestFeedback();



        // Info panel (moved to bottom panel under ЗАПИСЬ button)

        // (actual drawing is now in the bottom panel section)



        canvas.restore();



        // === Track player controls (под кругом радара, на карте) ===

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            float pbY = localInstrH + localRadarH - 90; // bottom of radar area

            drawPlaybackPanel(canvas, w * 0.05f, pbY);

        }



        // ========================================================================

        // GLIDE BAR — L/D слева, координаты центр, дальность справа

        // ========================================================================

        float bottomPanelY = localInstrH + localRadarH + localGlideH + h * 0.05f - 20f;

        float btnAreaTop = bottomPanelY - (50 + 24);

        float glideBarY2 = btnAreaTop + 30;

        boolean glideBackward = false;



        // L/D — из буфера GPS + поляра как fallback

        float glideRatio = 0f;

        boolean glideValid = false;

        if (glideBufCount >= 2) {

            int newestIdx = (glideBufHead - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;

            // Ищем точку минимум за GLIDE_WINDOW_SEC секунд до текущей

            int oldestIdx = newestIdx;

            int checked = 0;

            long targetTime = glideTimeBuf[newestIdx] - GLIDE_WINDOW_SEC * 1000L;

            while (checked < glideBufCount - 1) {

                int prev = (oldestIdx - 1 + GLIDE_BUF_MAX) % GLIDE_BUF_MAX;

                if (glideTimeBuf[prev] <= targetTime) break;

                oldestIdx = prev;

                checked++;

            }

            // NET displacement: прямое расстояние от oldest до newest

            float[] netRes = new float[2];

            android.location.Location.distanceBetween(

                    glideLatBuf[oldestIdx], glideLonBuf[oldestIdx],

                    glideLatBuf[newestIdx], glideLonBuf[newestIdx], netRes);

            float netDist = netRes[0];  // метры, всегда ≥0

            float netBearing = netRes[1]; // пеленг от oldest к newest



            // Высота по бароварио: интегрируем vario за окно

            float altDiffVario = 0f;

            int vi = oldestIdx;

            int vSteps = 0;

            while (vi != newestIdx && vSteps < GLIDE_BUF_MAX) {

                int vNext = (vi + 1) % GLIDE_BUF_MAX;

                if (glideTimeBuf[vNext] == 0) break;

                float dt = (glideTimeBuf[vNext] - glideTimeBuf[vi]) / 1000f;

                // vario положительный = набор = отнимаем от снижения

                altDiffVario += -glideVarioBuf[vi] * dt; // + = снижение

                vi = vNext;

                vSteps++;

            }



            // Текущий курс пилота: по КОМПАСУ (куда смотрит нос)

            float pilotTrack = a.getCompassHeading();



            // Определяем знак: если движемся вбок/назад относительно курса

            float angleDiff = Math.abs(netBearing - pilotTrack);

            if (angleDiff > 180f) angleDiff = 360f - angleDiff;

            boolean goingBackward = angleDiff > 120f;



            if (altDiffVario > 0.5f) {

                // Снижаемся — считаем L/D

                float rawRatio = netDist / altDiffVario;

                if (netDist < 5f) {

                    // Стоим на месте (вертикальное снижение в сильный встречный)

                    glideRatio = 0f;

                } else if (goingBackward) {

                    // Летим хвостом вперёд — отрицательное L/D

                    glideRatio = Math.max(-99.0f, -rawRatio);

                    glideBackward = true;

                    a.currentStatus = String.format(java.util.Locale.US, "СНОС НАЗАД — %.1f м/с", netDist / 8f);

                    a.previousStatus = "";

                } else {

                    // Нормальное планирование

                    glideRatio = Math.min(99.0f, rawRatio);

                }

                glideValid = true;

            } else {

                // Набираем или ровно — бесконечное качество, кап 99.0

                glideRatio = 99.0f;

                glideValid = true;

            }

        }



        // Поляра как fallback: если GPS-расчёт не дал результата

        if (!glideValid && a.gpsManager.isReady()) {

            float speedMs = a.gpsManager.getSpeed();

            // Три точки поляры: (скорость км/ч → L/D)

            // Триммер 35км/ч→8.7, пол-акселя 45км/ч→7.25, полный 52.5км/ч→6.0

            float speedKmh = speedMs * 3.6f;

            float polarLD;

            if (speedKmh <= 35f) {

                polarLD = 8.7f; // триммер

            } else if (speedKmh <= 45f) {

                // Интерполяция: 35→8.7, 45→7.25

                float t = (speedKmh - 35f) / 10f;

                polarLD = 8.7f - (8.7f - 7.25f) * t;

            } else if (speedKmh <= 52.5f) {

                // Интерполяция: 45→7.25, 52.5→6.0

                float t = (speedKmh - 45f) / 7.5f;

                polarLD = 7.25f - (7.25f - 6.0f) * t;

            } else {

                polarLD = 6.0f; // полный аксель

            }

            glideRatio = Math.min(99.0f, polarLD);

            glideValid = true;

        }



        // Range = AGL × L/D (кап 99.9 км, может быть отрицательным)

        float aglForRange;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            aglForRange = a.trackReplayer.getAltitude();

        } else {

            aglForRange = a.gpsManager.isAltitudeInitialized()

                    ? (a.gpsManager.getAltitude() - a.gpsManager.getStartAltitude()) : 0f;

        }

        float rangeKm = 0f;

        if (glideValid && aglForRange > 0) {

            // FIX: при L/D=99 (бесконечное качество/набор) показываем кап 99.9 км

            if (glideRatio >= 99.0f) {

                rangeKm = 99.9f;

            } else {

                rangeKm = aglForRange * glideRatio / 1000f;

                rangeKm = Math.max(-99.9f, Math.min(99.9f, rangeKm));

            }

        } else if (aglForRange > 0) {

            rangeKm = Math.min(99.9f, aglForRange * 8f / 1000f);

        }



        // LEFT: дальность планирования — число крупно, "км" мелко

        glideBarPaint.setTextAlign(Paint.Align.LEFT);

        glideBarPaint.setColor(rangeKm >= 0 ? Color.argb(200, 0, 255, 255) : Color.argb(220, 255, 80, 80));

        canvas.drawText(String.format(java.util.Locale.US, "%.1f", rangeKm),

                w * 0.04f, glideBarY2, glideBarPaint);

        instrLabelPaint.setColor(rangeKm >= 0 ? Color.argb(140, 0, 200, 255) : Color.argb(160, 255, 80, 80));

        instrLabelPaint.setTextSize(32);

        instrLabelPaint.setTextAlign(Paint.Align.LEFT);

        canvas.drawText("км", w * 0.04f + glideBarPaint.measureText(String.format(java.util.Locale.US, "%.1f", rangeKm)) + 4,

                glideBarY2 - 4, instrLabelPaint);

        instrLabelPaint.setTextSize(32);



        // CENTER: координаты (Google Maps format: lat, lon)

        double coordLat = 0.0, coordLon = 0.0;

        if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

            coordLat = a.trackReplayer.getLat();

            coordLon = a.trackReplayer.getLon();

        } else {

            coordLat = a.gpsManager.getLat();

            coordLon = a.gpsManager.getLon();

        }

        if (coordLat != 0.0 && coordLon != 0.0) {

            // Satellites count above coordinates

            int satCount = a.gpsManager.getSatelliteCount();

            glideBarPaint.setTextAlign(Paint.Align.CENTER);

            glideBarPaint.setTextSize(24);

            glideBarPaint.setColor(Color.argb(120, 0, 200, 100));

            canvas.drawText("видно " + satCount + " спутников", w / 2f, glideBarY2 - 50, glideBarPaint);

            // Coordinates

            glideBarPaint.setTextAlign(Paint.Align.CENTER);

            glideBarPaint.setColor(Color.argb(180, 100, 200, 255));

            glideBarPaint.setTextSize(36);

            canvas.drawText(String.format(java.util.Locale.US, "%.5f, %.5f", coordLat, coordLon),

                    w / 2f, glideBarY2, glideBarPaint);

        }



        // RIGHT: "L/D" мелко, число крупно (1 десятичный знак)

        glideBarPaint.setTextAlign(Paint.Align.RIGHT);

        glideBarPaint.setTextSize(112);

        String ldStr = glideValid ? String.format(java.util.Locale.US, "%.1f", glideRatio) : "--";

        // Сначала рисуем "L/D" мелко, смещая влево от числа

        float ldNumWidth = glideBarPaint.measureText(ldStr);

        float ldRightX = w * 0.96f;

        glideBarPaint.setColor(glideRatio >= 0 ? Color.argb(200, 0, 255, 255) : Color.argb(220, 255, 80, 80));

        canvas.drawText(ldStr, ldRightX, glideBarY2, glideBarPaint);

        instrLabelPaint.setColor(glideRatio >= 0 ? Color.argb(140, 0, 200, 255) : Color.argb(160, 255, 80, 80));

        instrLabelPaint.setTextSize(32);

        instrLabelPaint.setTextAlign(Paint.Align.RIGHT);

        canvas.drawText("L/D", ldRightX - ldNumWidth - 6, glideBarY2 - 4, instrLabelPaint);

        instrLabelPaint.setTextSize(32);



        // ========================================================================

        // BOTTOM PANEL (~13%)

        // ========================================================================

        // Blue separator line

        canvas.drawRect(0, bottomPanelY, w, bottomPanelY + 2, bottomSepPaint);



        // Buttons: ЗАПИСЬ (left) and СТОП (right) — ПОД синей линией

        float btnH = 50f;

        float btnW = w * 0.28f;

        float btnRowY = bottomPanelY + 10;

        calibBtnRect.set(w * 0.03f, btnRowY, w * 0.03f + btnW, btnRowY + btnH);

        startBtnRect.set(w * 0.97f - btnW, btnRowY, w * 0.97f, btnRowY + btnH);



        // Test/stop button (below ЗАПИСЬ/СТОП)

        float testBtnW = 160f;

        float testBtnH = 44f;

        testBtnRect.set(w / 2f - testBtnW / 2f, btnRowY + btnH + 8,

                w / 2f + testBtnW / 2f, btnRowY + btnH + 8 + testBtnH);



        btnTextPaint.setTextSize(24);

        btnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);



        // Старт записи (слева)

        canvas.drawRoundRect(calibBtnRect, 10, 10, btnBgPaint);

        btnTextPaint.setColor(Color.argb(200, 0, 255, 0));

        canvas.drawText("ЗАПИСЬ", calibBtnRect.centerX(), calibBtnRect.centerY() + 9, btnTextPaint);



        // Debug data in bottom panel (between buttons)

        if (a.thermalDetector != null) {

            SignalProcessor sp = a.thermalDetector.getSignalProcessor();

            float amp = 0f, freq = 0f, dir = 0f;

            boolean spOk = (sp != null);

            if (spOk) {

                amp = sp.getTurbulenceMs2();

                freq = Math.max(0.5f, Math.min(2.5f, sp.getDominantFrequency()));

                dir = sp.getStableDirDeg();

            }

            if (dir < 0) dir += 360f;

            if (dir >= 360f) dir -= 360f;



            float density = getResources().getDisplayMetrics().density;

            float dataX = calibBtnRect.right + 8;

            float dataY = bottomPanelY + 20;

            sensorDataPaint.setColor(Color.argb(160, 0, 255, 0));

            sensorDataPaint.setTextSize(20);

            sensorDataPaint.setTextAlign(Paint.Align.LEFT);



            String labAmp = "\u00B1" + String.format(java.util.Locale.US, "%.2f", amp);

            canvas.drawText(labAmp, dataX, dataY, sensorDataPaint);

            float advX = sensorDataPaint.measureText(labAmp) + 16;



            String labFreq = String.format(java.util.Locale.US, "%.2f\u0413\u0446", freq);

            canvas.drawText(labFreq, dataX + advX, dataY, sensorDataPaint);

            advX += sensorDataPaint.measureText(labFreq) + 16;



            String labDir = String.format(java.util.Locale.US, "%.0f\u00B0", dir);

            canvas.drawText(labDir, dataX + advX, dataY, sensorDataPaint);



            // Вторая строка: наклон + GPS статус

            float tiltY = dataY + 22;

            sensorDataPaint.setTextSize(15);

            sensorDataPaint.setColor(Color.argb(120, 0, 255, 0));

            float mountTilt = a.sensorController.getMountTiltDeg();

            float currTilt = a.sensorController.getCurrentTiltDeg();

            String gpsLabel = (a.gpsManager.isReady() && a.gpsManager.getFixAgeMs() < 5000)

                    ? " | gps OK" : " | gps OFF";

            String tiltTxt;

            if (mountTilt > 0.5f) {

                tiltTxt = String.format("Крепление: %.0f° | Крен: %.0f°", mountTilt, currTilt);

            } else {

                tiltTxt = String.format("Крен: %.0f° (нет калибровки)", currTilt);

            }

            sensorDataPaint.setColor(Color.argb(120, 0, 255, 0));

            canvas.drawText(tiltTxt + gpsLabel, dataX, tiltY, sensorDataPaint);



            // Ветер под debug строкой

            if (windDeg >= 0 && windSpdMs > 0) {

                float windY2 = tiltY + 20;

                sensorDataPaint.setColor(Color.argb(160, 100, 200, 255));

                sensorDataPaint.setTextSize(15);

                canvas.drawText(String.format(java.util.Locale.US, "ветер %.1f м/с %d°", windSpdMs, (int)windDeg),

                        dataX, windY2, sensorDataPaint);

            }



            // Logging label

            if (a.logManager.isLogging()) {

                logLabelPaint.setTextSize(20);

                float labelY = dataY - 28;

                logLabelPaint.setColor(Color.argb(220, 33, 150, 243));

                canvas.drawText("пишем лог", dataX, labelY, logLabelPaint);

            }

        }



        // Стоп (справа)

        if (a.logManager.isLogging()) {

            btnTextPaint.setColor(Color.argb(255, 255, 80, 80));

        } else {

            btnTextPaint.setColor(Color.argb(60, 255, 80, 80));

        }

        btnTextPaint.setTextSize(24);

        canvas.drawText("СТОП", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);



        // Stop test button

        if (!a.scenarioMode && !a.trackMode) {

            testBtnBgPaint.setColor(Color.TRANSPARENT);

        } else {

            testBtnBgPaint.setColor(Color.argb(50, 255, 80, 80));

        }

        canvas.drawRoundRect(testBtnRect, 12, 12, testBtnBgPaint);

        if (a.scenarioMode || a.trackMode) {

            testBtnTextPaint.setTextSize(24);

            testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            testBtnTextPaint.setColor(Color.argb(220, 255, 80, 80));

            canvas.drawText("СТОП ТЕСТ", testBtnRect.centerX(), testBtnRect.centerY() + 9, testBtnTextPaint);

        }



        // ========================================================================

        // OVERLAYS (drawn on top of everything)

        // ========================================================================



        // Night filter

        a.uiManager.drawNightFilter(canvas, w, h);



        // SIM badge

        if (a.simMode || a.scenarioMode || a.trackMode) {

            float badgeX = 12f;

            float badgeY = 190f;

            String badgeText = a.scenarioMode ? "TEST" : (a.trackMode ? "TPEK" : "SIM");

            float pad = 8f;

            float textW = simPaint.measureText(badgeText);

            canvas.drawRoundRect(badgeX, badgeY, badgeX + textW + pad * 2,

                    badgeY + 32 + pad, 8, 8, simBgPaint);

            canvas.drawText(badgeText, badgeX + pad, badgeY + 28, simPaint);

        }



        drawExitButton(canvas);

        drawRecIndicator(canvas);

        drawGearButton(canvas);



        // Battery level (top-right, near gear)

        android.os.BatteryManager bm = (android.os.BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);

        int batteryPct = 0;

        if (bm != null) {

            batteryPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);

        }

        String batStr = batteryPct + "%";

        batteryPaint.setColor(batteryPct > 20 ? Color.argb(200, 0, 255, 0) : Color.argb(220, 255, 80, 80));

        canvas.drawText(batStr, gearRect.left - 12, gearRect.centerY() + 8, batteryPaint);



        // Test mode overlay

        if (a.testMode) {

            String instr = a.getTestInstruction();

            String fb = a.getTestFeedback();

            if (instr != null && instr.length() > 0) {

                float radarCx2 = w / 2f;

                float radarCy3 = localInstrH + localRadarCy2;

                float radarR3 = localRadarR2;

                testBgPaint.setColor(Color.argb(180, 5, 5, 5));

                canvas.drawCircle(radarCx2, radarCy3, radarR3 * 0.55f, testBgPaint);

                testBorderPaint.setStyle(Paint.Style.STROKE);

                testBorderPaint.setStrokeWidth(1);

                testBorderPaint.setColor(Color.argb(40, 0, 255, 0));

                canvas.drawCircle(radarCx2, radarCy3, radarR3 * 0.55f, testBorderPaint);



                String[] lines = instr.split("\n");

                float textY = radarCy3 - radarR3 * 0.35f;

                testTextPaint.setTextAlign(Paint.Align.CENTER);

                for (String line : lines) {

                    if (line.startsWith("ШАГ")) {

                        testTextPaint.setColor(Color.argb(255, 255, 193, 7));

                        testTextPaint.setTextSize(34);

                        testTextPaint.setFakeBoldText(true);

                    } else {

                        testTextPaint.setColor(Color.argb(220, 0, 255, 0));

                        testTextPaint.setTextSize(26);

                        testTextPaint.setFakeBoldText(false);

                    }

                    canvas.drawText(line, radarCx2, textY, testTextPaint);

                    textY += 38;

                }



                if (fb != null && fb.length() > 0) {

                    float fbY2 = radarCy3 + radarR3 * 0.08f;

                    testTextPaint.setColor(a.getTestFeedbackColor());

                    testTextPaint.setTextSize(28);

                    testTextPaint.setFakeBoldText(true);

                    canvas.drawText(fb, radarCx2, fbY2, testTextPaint);

                }



                float barY = radarCy3 + radarR3 * 0.28f;

                float barW = radarR3 * 0.8f;

                float barH = 10f;

                float barLeft = radarCx2 - barW / 2f;

                testBarBgPaint.setColor(Color.argb(30, 0, 255, 0));

                canvas.drawRoundRect(barLeft, barY, barLeft + barW, barY + barH, 5, 5, testBarBgPaint);

                testBarFillPaint.setColor(Color.argb(180, 76, 175, 80));

                float progress = a.testStep / 6.0f;

                if (a.testStep > 6) progress = 1f;

                canvas.drawRoundRect(barLeft, barY, barLeft + barW * progress, barY + barH, 5, 5, testBarFillPaint);

            }

        }

    }



    // Vario average helpers

    private void pushVarioSample(float val) {

        varioBuf[varioBufIdx] = val;

        varioBufIdx = (varioBufIdx + 1) % VARIO_AVG_COUNT;

        if (varioBufLen < VARIO_AVG_COUNT) varioBufLen++;

    }

    private float getAvgVario30() {

        if (varioBufLen == 0) return 0f;

        float sum = 0;

        for (int i = 0; i < varioBufLen; i++) sum += varioBuf[i];

        return sum / varioBufLen;

    }



    private void drawGearButton(Canvas canvas) {

        float left = gearRect.left, top = gearRect.top, right = gearRect.right, bottom = gearRect.bottom;

        float cx = (left + right) / 2f, cy = (top + bottom) / 2f;

        float radius = (right - left) / 2f - 4;

        canvas.drawCircle(cx, cy, radius, gearPaint);

        canvas.drawCircle(cx, cy, radius * 0.45f, gearInnerPaint);

        int teeth = 8;

        float toothLen = radius * 0.3f;

        for (int i = 0; i < teeth; i++) {

            double angle = Math.toRadians(i * (360 / teeth));

            float sx = cx + (float) Math.cos(angle) * (radius - toothLen);

            float sy = cy + (float) Math.sin(angle) * (radius - toothLen);

            float ex = cx + (float) Math.cos(angle) * (radius + 2);

            float ey = cy + (float) Math.sin(angle) * (radius + 2);

            canvas.drawLine(sx, sy, ex, ey, gearToothPaint);

        }

    }



    private void drawExitButton(Canvas canvas) {

        float cx = exitRect.centerX(), cy = exitRect.centerY();

        float radius = (exitRect.right - exitRect.left) / 2f - 4;

        canvas.drawCircle(cx, cy, radius, exitPaint);

        float inset = radius * 0.30f;

        canvas.drawLine(cx - inset, cy - inset, cx + inset, cy + inset, exitXPaint);

        canvas.drawLine(cx + inset, cy - inset, cx - inset, cy + inset, exitXPaint);

    }



    /** Панель управления трек-плеером */

    private void drawPlaybackPanel(Canvas canvas, float x, float y) {

        int w = getWidth();

        float panelW = w - x - 16;



        // Строка 1: синее имя трека

        pbTrackPaint.setTextSize(20);

        String name = a.trackFileName;

        if (name.length() > 24) name = name.substring(0, 21) + "...";

        canvas.drawText("Трек: " + name, x, y, pbTrackPaint);



        // Строка 2: seekbar

        float barY = y + 6; // +10px выше

        float barH = 6;

        float barLeft = x, barRight = w - 16;

        float barMidY = barY + barH / 2f;



        canvas.drawRect(barLeft, barY, barRight, barY + barH, pbBarPaint);

        float progress = a.trackReplayer != null ? a.trackReplayer.getProgress() : 0;

        float thumbX = barLeft + (barRight - barLeft) * progress;

        canvas.drawRect(barLeft, barY, thumbX, barY + barH, pbThumbPaint);

        canvas.drawCircle(thumbX, barMidY, 10, pbThumbPaint);



        // Время

        String curTime = formatTime(a.trackReplayer != null ? (long)a.trackReplayer.getCurrentTime() : 0);

        String totalTime = formatTime(a.trackReplayer != null ? (long)a.trackReplayer.getTotalTime() : 0);

        pbTextPaint.setTextSize(18);

        canvas.drawText(curTime, barLeft, barY + barH + 18, pbTextPaint);

        canvas.drawText(totalTime, barRight, barY + barH + 18, pbTextPaint);



        pbSeekbarRect.set(barLeft - 16, barY - 16, barRight + 16, barY + barH + 32);



        // Строка 3: кнопки

        float btnY = barY + barH + 18; // +10px выше

        float btnW = 80, btnH = 44;

        pbBtnPaint.setStrokeWidth(5);

        pbBtnPaint.setColor(Color.argb(255, 255, 60, 60)); // красные кнопки



        pbPlayBtn.set(x, btnY, x + btnW, btnY + btnH);

        canvas.drawRoundRect(pbPlayBtn, 8, 8, pbBtnPaint);

        boolean isPaused = a.trackReplayer != null && a.trackReplayer.isPaused();

        pbTextPaint.setTextSize(30);

        pbTextPaint.setColor(Color.argb(255, 255, 60, 60));

        canvas.drawText(isPaused ? "▶" : "❚❚", pbPlayBtn.centerX(), pbPlayBtn.centerY() + 10, pbTextPaint);



        float speedBtnLeft = pbPlayBtn.right + 12;

        float speedBtnW = 80;

        pbSpeedBtn.set(speedBtnLeft, btnY, speedBtnLeft + speedBtnW, btnY + btnH);

        canvas.drawRoundRect(pbSpeedBtn, 8, 8, pbBtnPaint);

        float speed = (a.trackSpeedIdx >= 0 && a.trackSpeedIdx < MainActivity.PLAYBACK_SPEEDS.length)

                ? MainActivity.PLAYBACK_SPEEDS[a.trackSpeedIdx] : 1f;

        pbTextPaint.setTextSize(30);

        canvas.drawText((int)speed + "x", pbSpeedBtn.centerX(), pbSpeedBtn.centerY() + 10, pbTextPaint);

    }



    /** Форматировать секунды в MM:SS */

    private String formatTime(long sec) {

        long m = sec / 60, s = sec % 60;

        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;

    }



    /** Обновить позицию плеера по касанию seekbar */

    private void updateSeekFromTouch(float x) {

        if (a.trackReplayer == null) return;

        float barLeft = pbSeekbarRect.left + 10;

        float barRight = pbSeekbarRect.right - 10;

        float frac = (x - barLeft) / (barRight - barLeft);

        frac = Math.max(0, Math.min(1, frac));

        float total = a.trackReplayer.getTotalTime();

        a.trackReplayer.seekTo(frac * total);

    }



    private void drawRecIndicator(Canvas canvas) {

        boolean isLogging = (a.logManager != null && a.logManager.isLogging())

                || (a.igcLogger != null && a.igcLogger.isLogging());

        if (!isLogging) return;

        // Мигание: 500 мс вкл, 500 мс выкл

        long now = SystemClock.elapsedRealtime();

        if ((now / 500) % 2 == 0) return;

        float dotX = gearRect.left - 16;

        float cy = gearRect.centerY();

        float dotR = 14;

        // Защита от off-screen при неинициализированном gearRect

        if (dotX < 10 || cy < 10) {

            dotX = 50; cy = 50;

        }

        canvas.drawCircle(dotX - 26, cy, dotR, recDotPaint);

        // REC сдвинут на 2 символа влево

        canvas.drawText("REC", dotX - 48, cy + 12, recTextPaint);

    }



    @Override

    public boolean onTouchEvent(MotionEvent event) {

        touchX = event.getX();

        touchY = event.getY();



        if (event.getAction() == MotionEvent.ACTION_DOWN) {

            // Запоминаем время для детекции долгого нажатия

            touchDownTime = System.currentTimeMillis();

            // Seekbar drag start

            if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()

                    && pbSeekbarRect.contains(touchX, touchY)) {

                pbDragging = true;

                a.trackReplayer.setPaused(true);

                updateSeekFromTouch(touchX);

                return true;

            }

            return true;

        }



        if (event.getAction() == MotionEvent.ACTION_MOVE && pbDragging) {

            updateSeekFromTouch(touchX);

            return true;

        }



        if (event.getAction() == MotionEvent.ACTION_UP) {

            long touchDuration = System.currentTimeMillis() - touchDownTime;

            pbDragging = false;



            // Playback controls (track mode)

            if (a.trackMode && a.trackReplayer != null && a.trackReplayer.isRunning()) {

                if (pbPlayBtn.contains(touchX, touchY)) {

                    a.trackReplayer.setPaused(!a.trackReplayer.isPaused());

                    return true;

                }

                if (pbSpeedBtn.contains(touchX, touchY)) {

                    a.trackSpeedIdx = (a.trackSpeedIdx + 1) % MainActivity.PLAYBACK_SPEEDS.length;

                    a.trackReplayer.setSpeed(MainActivity.PLAYBACK_SPEEDS[a.trackSpeedIdx]);

                    return true;

                }

            }



            if (exitRect.contains(touchX, touchY)) {

                a.showExitOnScreenDialog();

                return true;

            }

            if (gearRect.contains(touchX, touchY)) {

                a.startActivity(new Intent(a, SettingsActivity.class));

                return true;

            }

            // СТАРТ: тап = старт лога, долгое нажатие = сброс калибровки

            if (calibBtnRect.contains(touchX, touchY)) {

                if (touchDuration > 600) {

                    a.resetCalibration();

                    android.widget.Toast.makeText(a,

                            "Калибровка сброшена (долгое нажатие)",

                            android.widget.Toast.LENGTH_SHORT).show();

                } else {

                    a.startManualLogging();

                }

                return true;

            }

            if (startBtnRect.contains(touchX, touchY)) {

                if (a.logManager.isLogging() || a.igcLogger.isLogging()) a.confirmStopLogging();

                return true;

            }

            if (testBtnRect.contains(touchX, touchY)) {

                if (a.scenarioMode) {

                    a.stopFlightScenario();

                } else if (a.trackMode) {

                    a.stopTrackReplay();

                } else if (!a.scenarioMode) {

                    a.startFlightScenario();

                }

                return true;

            }

        }

        return super.onTouchEvent(event);

    }



    @Override

    public void onClick(View v) {}

}