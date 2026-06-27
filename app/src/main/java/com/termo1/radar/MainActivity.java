package com.termo1.radar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.termo1.radar.core.FlightSimulator;
import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.core.TrackReplayer;
import com.termo1.radar.core.SignalProcessor;
import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.igc.IGCParser;
import com.termo1.radar.igc.IGCAnalyzer;
import com.termo1.radar.igc.DisplayFrame;
import com.termo1.radar.flight.FlightStateMachine;
import com.termo1.radar.flight.BlindFlightMode;
import com.termo1.radar.flight.VarioThermalDetector;
import com.termo1.radar.flight.WindDriftCalculator;
import com.termo1.radar.flight.WindDriftCalculator.WindCorrected;
import com.termo1.radar.flight.CirclingManager;
import com.termo1.radar.flight.CirclingManager.CirclingState;
import com.termo1.radar.flight.LiftDatabase;
import com.termo1.radar.flight.ThermalBaseEstimator;
import com.termo1.radar.flight.ThermalBaseEstimator.ThermalBaseResult;
import com.termo1.radar.flight.ThermalLocator;
import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.logging.IgcLogger;
import com.termo1.radar.logging.LogManager;
import com.termo1.radar.map.StaticMapLoader;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;
import com.termo1.radar.sensors.VarioManager;
import com.termo1.radar.ui.RadarRenderer;
import com.termo1.radar.ui.HudController;
import com.termo1.radar.ui.StatusManager;
import com.termo1.radar.ui.SettingsActivity;
import com.termo1.radar.ui.UiManager;
import com.termo1.radar.ui.VarioSoundManager;
import com.termo1.radar.RadarView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * TERMO1 Radar — MainActivity.
 *
 * Интегрирует модули:
 * - SensorController — датчики, компас, вариометр
 * - GpsManager — GPS
 * - LogManager — логирование 25 Гц, автостарт/стоп, нарезка
 * - FlightStateMachine — детекция полёта по высоте
 * - ThermalDetector — анализ микрораскачки
 * - RadarView — рендеринг UI
 */
public class MainActivity extends Activity {

    static final int SAMPLE_RATE_HZ = 50;
    static final int RENDER_FPS = 30;
    static final long RENDER_INTERVAL_MS = 33L;
    static final int THERMAL_LIMIT = 12;
    static final float MAX_DISTANCE_M = 150.0f;

    // GPS trail
    static final int GPS_TRAIL_MAX = 2000;
    static final long GPS_TRAIL_MAX_AGE_MS = 300_000L; // 5 минут
    static final long GPS_TRAIL_ADD_INTERVAL_MS = 1000L; // добавлять не чаще 1 раз/сек

    // ========================================================================
    // Modules
    // ========================================================================

    SensorController sensorController;
    GpsManager gpsManager;
    LogManager logManager;
    IgcLogger igcLogger;
    StaticMapLoader staticMapLoader;
    FlightStateMachine flightStateMachine;
    BlindFlightMode blindFlightMode;
    boolean blindModeEnabled;
    boolean voicePromptsEnabled;
    CirclingManager circlingManager;
    VarioThermalDetector varioThermalDetector;
    float varioThreshold = 0.5f;

    // Phase 2: ThermalLocator + LiftDatabase
    ThermalLocator thermalLocator;
    LiftDatabase liftDatabase;
    float prevThermalLiftBaseline;
    long lastThermalBaseCalcMs;
    static final long THERMAL_BASE_INTERVAL_MS = 5000; // раз в 5с
    ThermalBaseResult lastThermalBaseResult;

    // ThermalRadarService (singleton, без binding)
    ThermalRadarService radarService;

    // ========================================================================
    // Core
    // ========================================================================

    ThermalDetector thermalDetector;
    SharedPreferences prefs;

    // Tracking for event logging state transitions
    boolean prevCirclingState;
    boolean prevLabelState;
    float prevWindFrom = -2f;
    float prevWindSpd = -2f;
    WindCorrected lastDrift;  // Phase 5: последний расчёт сноса

    // ===== Модули (рефакторинг) =====
    com.termo1.radar.ui.VoiceController voiceController;
    SensorCoordinator sensorCoordinator;
    HudController hudController;
    StatusManager statusManager = new StatusManager();
    RadarViewController radarViewController;
    FlightController flightController;

    // ========================================================================
    // Thermals
    // ========================================================================

    final List<ThermalBlip> thermals = new ArrayList<>();
    final List<ThermalBlip> thermalsCopy = new ArrayList<>();
    final Object thermalLock = new Object();

    // GPS trail storage: [lat, lon, timeMs, vario]
    // MA-5: ring buffer вместо ArrayList<double[]> для устранения аллокаций
    static final class TrailPoint {
        double lat, lon;
        long timeMs;
        float vario;
    }
    final TrailPoint[] trailBuf = new TrailPoint[GPS_TRAIL_MAX];
    int trailHead = 0;     // next write position
    int trailCount = 0;    // number of valid entries

    // Phase 6: точки входа/выхода из термиков
    static final int MAX_MARKERS = 100;
    final List<double[]> entryMarkers = new ArrayList<>();  // [lat, lon]
    final List<double[]> exitMarkers = new ArrayList<>();   // [lat, lon]

    // ===== Reusable buffers для onDraw (устранение аллокаций, T13) =====
    final float[] distanceResult = new float[2];
    final float[] markerPxBuf = new float[MAX_MARKERS * 2];
    final float[] markerPyBuf = new float[MAX_MARKERS * 2];
    final boolean[] markerIsEntry = new boolean[MAX_MARKERS * 2];
    /** Буфер цветов трека (vario → цвет, возраст → alpha) */
    final int[] trailColorBuf = new int[GPS_TRAIL_MAX];
    /** Буферы для map-трека (синий, масштаб карты 3×3 км, без клипа 150м) */
    final float[] mapTrailPxBuf = new float[GPS_TRAIL_MAX];
    final float[] mapTrailPyBuf = new float[GPS_TRAIL_MAX];

    // MA-5: кешированные строки HUD (избегаем String.format в onDraw при неизменных значениях)
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
    // UI
    // ========================================================================

    RadarView radarView;
    RadarRenderer radarRenderer;
    UiManager uiManager;
    VarioSoundManager varioSoundManager;
    AlertDialog exitDialog;
    volatile String currentStatus = "ПОИСК";
    String previousStatus = "";

    // ===== TTS голосовые подсказки =====
    android.speech.tts.TextToSpeech tts;
    boolean ttsReady;
    String lastTtsPhrase;
    long lastTtsSpeakMs;

    // ===== UI-сглаживание heading (Fix C — time-based SLERP) =====
    float headingDisplaySmoothed = 0f;
    boolean headingDisplayInitialized = false;
    long lastHeadingFrameMs = 0;

    /**
     * Цвет трека по скорости набора/снижения.
     * @param vario  скорость по вертикали (м/с, +набор, -снижение)
     * @param alpha  затухание от возраста (0..1, 1 = полностью непрозрачный)
     * @return ARGB int
     *
     * Схема цветов:
     *   ≤ -5 м/с  тёмно-зелёный
     *    -2 м/с  зелёный
     *    -0.3 м/с  салатовый
     *     0 м/с    ярко-жёлтый
     *    +0.5 м/с  оранжевый
     *    +1.5 м/с  красно-оранжевый
     *    +3 м/с    красный
     *    +5 м/с    бордовый
     *    ≥ +8 м/с  тёмно-бордовый
     */
    static int varioToColor(float vario, float alpha) {
        // Опорные точки: варио → (R,G,B)
        final float[] keys = {-5f, -2f, -0.3f, 0f, 0.5f, 1.5f, 3f, 5f, 8f};
        final int[] colors = {
            0x001A00, 0x006600, 0x99CC66, 0xFFFF33,
            0xFFAA00, 0xFF4400, 0xFF0000, 0x660000, 0x330000
        };

        // Поиск интервала
        int hi = 1;
        while (hi < keys.length - 1 && vario > keys[hi]) hi++;
        int lo = hi - 1;

        float t = (keys[hi] - keys[lo] == 0f) ? 0f
                : (vario - keys[lo]) / (keys[hi] - keys[lo]);
        t = Math.max(0f, Math.min(1f, t));

        int r = (int)(((colors[lo] >> 16) & 0xFF) * (1f - t) + ((colors[hi] >> 16) & 0xFF) * t);
        int g = (int)(((colors[lo] >> 8) & 0xFF) * (1f - t) + ((colors[hi] >> 8) & 0xFF) * t);
        int b = (int)((colors[lo] & 0xFF) * (1f - t) + (colors[hi] & 0xFF) * t);

        int a = (int)(alpha * 200); // макс 200 как и было
        if (a < 8) a = 8;
        if (a > 255) a = 255;

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ========================================================================
    // Simulation
    // ========================================================================

    boolean simMode;
    boolean scenarioMode;
    boolean trackMode;
    SimulationManager simulation;
    FlightSimulator flightSim;
    TrackReplayer trackReplayer;
    /** IGC pipeline: display frame (replay & live) */
    volatile DisplayFrame currentDisplayFrame;
    /** IGC pipeline: analyzer (replay mode) */
    IGCAnalyzer igcAnalyzer;
    /** IGC pipeline: analyzer (live mode, пересоздаётся каждые 500ms) */
    private IGCAnalyzer liveAnalyzer;
    /** IGC pipeline: время последнего live анализа */
    private long lastLiveIgcAnalyzeMs;

    /**
     * Обёртка TrackReplayer для IGC pipeline.
     * Позволяет RadarView читать данные из DisplayFrame без изменения RadarView.
     * RadarView видит a.trackReplayer != null → работает как раньше.
     */
    class TrackReplayerDisplayBridge extends TrackReplayer {
        @Override public double getLat() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.pilotLat : 0;
        }
        @Override public double getLon() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.pilotLon : 0;
        }
        @Override public float getAltitude() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.altitudeMsl : 0;
        }
        @Override public float getLaunchAltitude() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.launchAltitude : 0;
        }
        @Override public float getSpeed() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.speedMs : 0;
        }
        @Override public float getVario() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.varioMs : 0;
        }
        @Override public float getHeading() {
            return 0f; // North-up для реплея
        }
        @Override public float getWindFromDeg() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.windFromDeg : -1;
        }
        @Override public float getWindSpeedMs() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.windSpeedMs : 0;
        }
        @Override public float getCompassHeading() {
            return 0f; // North-up для реплея
        }
        @Override public boolean isRunning() { return trackMode; }
        @Override public boolean isFinished() {
            DisplayFrame df = currentDisplayFrame;
            return df == null || !df.isRunning;
        }
        @Override public boolean isPaused() { return false; }
        @Override public void setPaused(boolean v) {}
        @Override public float getCurrentTime() {
            IGCAnalyzer a = igcAnalyzer;
            return a != null ? a.getCurrentTime() : 0;
        }
        @Override public float getTotalTime() {
            IGCAnalyzer a = igcAnalyzer;
            return a != null ? a.getTotalTime() : 0;
        }
        @Override public float getProgress() {
            IGCAnalyzer a = igcAnalyzer;
            if (a == null || a.getTotalTime() <= 0) return 0;
            return Math.min(1, a.getCurrentTime() / a.getTotalTime());
        }
        @Override public List<com.termo1.radar.core.TrackReplayer.TrackPoint> getTrack() {
            IGCAnalyzer a = igcAnalyzer;
            if (a == null) return null;
            com.termo1.radar.igc.TrackPoint[] igcTrack = a.getTrack();
            if (igcTrack == null || igcTrack.length < 2) return null;
            List<com.termo1.radar.core.TrackReplayer.TrackPoint> result =
                    new ArrayList<>(igcTrack.length);
            for (com.termo1.radar.igc.TrackPoint tp : igcTrack) {
                result.add(new com.termo1.radar.core.TrackReplayer.TrackPoint(
                        tp.lat, tp.lon, tp.displayAltM, tp.timeSec));
            }
            return result;
        }
        @Override public boolean isThermalActive() {
            DisplayFrame df = currentDisplayFrame;
            return df != null && df.showRedCore;
        }
        @Override public boolean isShowRedCore() {
            DisplayFrame df = currentDisplayFrame;
            return df != null && df.showRedCore;
        }
        @Override public float getThermalBearing() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.thermalBearingDeg : 0;
        }
        @Override public float getThermalDistance() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.thermalDistM : 0;
        }
        @Override public float getThermalRadius() { return 25f; }
        @Override public String getGuidanceText() {
            DisplayFrame df = currentDisplayFrame;
            return df != null ? df.guidanceText : "";
        }
        @Override public boolean hasRealAccel() { return false; }
        @Override public float getAccelX() { return 0; }
        @Override public float getAccelY() { return 0; }
        @Override public void setSpeed(float v) {}
        @Override public void setHasSensorData(boolean v) {}
        @Override public void setAirspeedMs(float v) {}
        @Override public void setWind(float fromDeg, float speedMs) {}
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void update(long realDeltaMs) {}
        @Override public void seekTo(float timeSec) {}
        @Override public boolean loadFile(String filePath) { return false; }
        @Override public void loadEmbeddedDemoTrack(android.content.Context context) {}
        @Override public boolean loadSensorZip(String zipPath) { return false; }
    }
    long simStartMs;
    long lastThermalBeepMs;
    long lastMaxSnrResetMs;
    Handler simHandler = new Handler(Looper.getMainLooper());
    Runnable simTask;

    // ========================================================================
    // Processing / rendering
    // ========================================================================

    volatile boolean running;
    Handler renderHandler = new Handler(Looper.getMainLooper());
    Runnable renderTask;
    // Фоновый таймер 10 Гц для обработки (работает при выкл экране)
    HandlerThread bgThread;
    Handler bgHandler;
    Runnable bgTask;
    static final long BG_INTERVAL_MS = 100L;

    // Track replay
    static final float[] PLAYBACK_SPEEDS = {1f, 2f, 5f, 10f};
    int trackSpeedIdx;
    String trackFileName;

    // ========================================================================
    // Power
    // ========================================================================

    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    // ========================================================================
    // Test mode
    // ========================================================================

    boolean testMode;
    int testStep;
    String testInstruction = "";
    String testFeedback = "";
    int testFeedbackColor;
    boolean testStepCorrect;
    long testLastBeepMs;
    long testCorrectStartMs;
    long testStepStartMs;
    boolean testBeepPlaying;
    // Термик-бип: не чаще раза в 6 секунд (было ~2с, уменьшили в 3 раза)
    static final long THERMAL_BEEP_INTERVAL_MS = 6000L;
    long lastThermalBeepRealMs;
    Handler testHandler = new Handler(Looper.getMainLooper());
    Runnable testTask;

    // Test window
    static final int TEST_WINDOW_FRAMES = 45;
    static final int TEST_CORRECT_RATIO = 4; // 40% как int для window
    final boolean[] testWindowBuffer = new boolean[TEST_WINDOW_FRAMES];
    int testWindowIdx;
    int testWindowFill;
    long testFeedbackLastUpdate;

    // Heading window
    static final int HEADING_WINDOW_FRAMES = 150;
    final float[] headingWindow = new float[HEADING_WINDOW_FRAMES];
    int headingWindowIdx;
    int headingWindowFill;

    // ========================================================================
    // Activity lifecycle
    // ========================================================================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // ← чтобы getIntent() вернул новый интент
        handleIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        java.util.Locale.setDefault(java.util.Locale.US);

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        // Runtime permission for GPS
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION},
                    1001);
        }

        // POST_NOTIFICATIONS для Android 13+ (L-1, foreground service notification)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        1002);
            }
        }

        // Preferences
        prefs = getSharedPreferences("termo1_settings", MODE_PRIVATE);

        // Modules
        sensorController = new SensorController(this);
        sensorController.loadPreferences(prefs);
        sensorController.setDataListener(sensorDataListener);

        VarioManager varioManager = new VarioManager();
        varioManager.setVarioSmoothSamples(prefs.getInt("vario_smooth", 30));
        sensorController.setVarioManager(varioManager);

        gpsManager = new GpsManager(this);

        logManager = new LogManager();
        logManager.setDataProvider(logDataProvider);
        logManager.setExternalLogDir(getExternalFilesDir(null) != null
                ? getExternalFilesDir(null).getAbsolutePath() : null);

        // IGC логгер (1 Гц, параллельно с CSV)
        igcLogger = new IgcLogger();
        igcLogger.setLogDir(getExternalFilesDir(null) != null
                ? getExternalFilesDir(null).getAbsolutePath() : null);

        // Static map loader (OSM под радаром)
        staticMapLoader = new StaticMapLoader(getCacheDir().getAbsolutePath());
        staticMapLoader.setCallback((bitmap, lat, lon, zoom) -> {
            runOnUiThread(() -> {
                if (radarRenderer != null) {
                    radarRenderer.setBackgroundMap(bitmap, lat, lon, zoom);
                }
            });
        });

        flightStateMachine = new FlightStateMachine();
        flightStateMachine.setListener(flightListener);

        blindFlightMode = new BlindFlightMode();
        blindModeEnabled = prefs.getBoolean("blind_mode", false);
        voicePromptsEnabled = prefs.getBoolean("voice_prompts", true);

        // Circling manager
        circlingManager = new CirclingManager();
        circlingManager.setAirspeed(prefs.getFloat("airspeed_ms", 9.5f));
        circlingManager.setVoiceCallback(text -> {
            if (ttsReady && voicePromptsEnabled) {
                long now = System.currentTimeMillis();
                if (now - lastTtsSpeakMs > 8000) {
                    lastTtsPhrase = text;
                    lastTtsSpeakMs = now;
                    logManager.recordEvent("CIRCLE_GUIDANCE", text);
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
                    } else {
                        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            }
        });

        // Phase 2: ThermalLocator + LiftDatabase
        thermalLocator = new ThermalLocator();
        liftDatabase = new LiftDatabase();
        prevThermalLiftBaseline = -1.5f; // типичное снижение параплана

        // VarioThermal detector
        varioThermalDetector = new VarioThermalDetector();
        varioThreshold = prefs.getFloat("vario_threshold", 0.5f);
        varioThermalDetector.setThreshold(varioThreshold);

        // Renderer + UI
        radarRenderer = new RadarRenderer();
        uiManager = new UiManager();
        uiManager.setDensity(getResources().getDisplayMetrics().density);
        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        if (prefs.getBoolean("sunlight_mode", false)) {
            uiManager.setColorScheme(UiManager.SCHEME_HIGH_CONTRAST);
        } else {
            uiManager.setColorScheme(prefs.getInt("color_scheme", 0));
        }

        radarView = new RadarView(this);
        setContentView(radarView);

        // System services
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Sound
        varioSoundManager = new VarioSoundManager();
        varioSoundManager.setDeadBandHigh(varioThreshold);

        // Автокалибровка сенсоров при старте (через 1с, чтобы сенсоры успели инициализироваться)
        new android.os.Handler().postDelayed(() -> {
            resetCalibration();
            android.util.Log.i("TERMO1", "Auto calibration at startup");
        }, 1000);

        // TTS голосовые подсказки
        tts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.setLanguage(java.util.Locale.forLanguageTag("ru"));
                ttsReady = true;
            }
        });

        // Thermal detector
        thermalDetector = new ThermalDetector();

        // Wire VarioManager to thermal detector for adaptive alpha
        varioManager.setThermalDetector(thermalDetector);

        // ===== Модульная архитектура (рефакторинг) =====
        voiceController = new com.termo1.radar.ui.VoiceController(this);
        voiceController.init();

        sensorCoordinator = new SensorCoordinator(this, sensorController, gpsManager, varioManager);

        hudController = new HudController();

        radarViewController = new RadarViewController(radarRenderer, thermalLock);

        flightController = new FlightController(
                thermalDetector, circlingManager, liftDatabase, thermalLocator,
                varioThermalDetector, flightStateMachine, logManager, igcLogger,
                sensorController, gpsManager, prefs,
                thermals, flightCallback);
        flightController.setVarioSoundManager(varioSoundManager);
        flightController.setVarioThreshold(varioThreshold);
        // =====

        // Start foreground service (WakeLock + notification)
        startForegroundService(new Intent(this, ThermalRadarService.class));

        // Simulation mode
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;

        // Фикс 3: если в режиме реплея — не запускаем GPS и сенсоры
        if (trackMode) {
            Log.i("TERMO1_REPLAY", "onResume: trackMode=true, skipping GPS/sensor start");
            uiManager.setNightMode(prefs.getBoolean("night_mode", false));
            if (prefs.getBoolean("sunlight_mode", false)) {
                uiManager.setColorScheme(UiManager.SCHEME_HIGH_CONTRAST);
            } else {
                uiManager.setColorScheme(prefs.getInt("color_scheme", 0));
            }
            sensorController.loadPreferences(prefs);
            acquireWakeLock();
            // Настройки звука
            if (varioSoundManager != null) {
                varioSoundManager.setSoundEnabled(prefs.getBoolean("sound_enabled", true));
            }
            // Обновить настройки из SharedPreferences
            blindModeEnabled = prefs.getBoolean("blind_mode", false);
            voicePromptsEnabled = prefs.getBoolean("voice_prompts", true);
            prefs.edit().remove("tilt_calibration_requested").apply();
            startRendering();
            if (varioSoundManager != null) varioSoundManager.start();
            return;
        }

        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        if (prefs.getBoolean("sunlight_mode", false)) {
            uiManager.setColorScheme(UiManager.SCHEME_HIGH_CONTRAST);
        } else {
            uiManager.setColorScheme(prefs.getInt("color_scheme", 0));
        }
        sensorController.loadPreferences(prefs);

        sensorController.registerSensors();
        acquireWakeLock();

        // Обновить настройки из SharedPreferences (могли измениться в Settings)
        blindModeEnabled = prefs.getBoolean("blind_mode", false);
        voicePromptsEnabled = prefs.getBoolean("voice_prompts", true);

        // Airspeed update
        if (circlingManager != null) {
            circlingManager.setAirspeed(prefs.getFloat("airspeed_ms", 9.5f));
        }

        // Sound + vibration settings
        boolean soundEnabled = prefs.getBoolean("sound_enabled", true);
        if (varioSoundManager != null) {
            varioSoundManager.setSoundEnabled(soundEnabled);
        }

        // Pilot/glider config (H-03) — читаем из pref, передаём в IgcLogger
        if (igcLogger != null) {
            igcLogger.setPilotName(prefs.getString("pilot_name", "UNKNOWN"));
            igcLogger.setGliderType(prefs.getString("glider_type", "Paraglider"));
            igcLogger.setGliderId(prefs.getString("glider_id", "UNKNOWN"));
        }
        boolean vibrateEnabled = prefs.getBoolean("vibrate_enabled", true);
        // сохраняем для использования в коде вибрации

        // GPS: всегда включён для логирования координат в лог
        gpsManager.startGps();
        gpsManager.setSensorController(sensorController); // C-08
        try {
            gpsManager.requestUpdates(Looper.getMainLooper());
        } catch (SecurityException e) {
            // Игнорируем — permission уже запрошено при старте
        }

        // Обработать запрос калибровки из настроек (tilt_calibration_requested)
        if (prefs.getBoolean("tilt_calibration_requested", false)) {
            prefs.edit().remove("tilt_calibration_requested").apply();
            // Запустить калибровку наклона через Handler (нужно время для датчиков)
            new android.os.Handler().postDelayed(() -> {
                sensorController.resetCalibration();
                // После завершения калибровки (2 сек) сохранить угол
                new android.os.Handler().postDelayed(() -> {
                    float angle = sensorController.getMountTiltDeg();
                    prefs.edit().putFloat("mount_tilt_deg", angle).apply();
                }, 2500);
            }, 500);
        }

        // Яркость экрана: из настроек (исправлено по ревью §7.10)
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (prefs.getBoolean("sunlight_mode", false)) {
            lp.screenBrightness = 1.0f;
        } else if (prefs.getBoolean("blind_mode", false)) {
            lp.screenBrightness = 0.15f;
        } else {
            lp.screenBrightness = -1.0f;  // system default
        }
        getWindow().setAttributes(lp);

        startRendering();
        startBgProcessing(); // фоновая обработка (лог, FSM, circling) — не останавливается при выкл экране
        if (varioSoundManager != null) varioSoundManager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        stopRendering();
        // Сенсоры/GPS/WakeLock НЕ трогаем (C-02 fix: иначе автостарт не сработает
        // при выключенном экране до взлёта).
        // ThermalRadarService держит foreground и WakeLock.
        stopTestMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (staticMapLoader != null) staticMapLoader.destroy();
        if (simHandler != null && simTask != null) {
            simHandler.removeCallbacks(simTask);
        }
        logManager.destroy();
        if (igcLogger != null) igcLogger.destroy();
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(1000); } catch (InterruptedException ignored) {}
            bgThread = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (simulation != null) {
            simulation.stop();
            simulation = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0
                    && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this,
                        "GPS недоступен без разрешения на геолокацию",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    // ========================================================================
    // Intent handling (вызывается из onCreate и onNewIntent)
    // ========================================================================

    void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("simulate", false)) {
            simMode = true;
            simulation = new SimulationManager();
            simulation.start();
            simStartMs = SystemClock.elapsedRealtime();
            lastThermalBeepMs = simStartMs;
            startSimLoop();
        }
        if (intent.getBooleanExtra("test_mode", false)) {
            startTestMode();
        }
        if (intent.getBooleanExtra("flight_test", false)) {
            radarView.post(() -> startFlightScenario());
        }
        if (intent.getBooleanExtra("track_replay", false)) {
            final String trackFile = intent.hasExtra("track_file")
                    ? intent.getStringExtra("track_file") : null;
            radarView.post(() -> startTrackReplay(trackFile));
        }
    }

    // ========================================================================
    // Sensor data callback — вызывается из сенсорного потока
    // ========================================================================

    final SensorController.SensorDataListener sensorDataListener =
            new SensorController.SensorDataListener() {

        private final float[] worldAccelOut = new float[3];

        @Override
        public void onLinearAccelSample(float axG, float ayG, float azG,
                                        float headingDeg, float[] rotMatrix,
                                        boolean compassReady) {
            if (blindModeEnabled && flightStateMachine.isFlying() && voicePromptsEnabled) {
                // Blind mode: передаём gravity + accel в BlindFlightMode
                blindFlightMode.setGravity(
                        sensorController.getGravityX(),
                        sensorController.getGravityY(),
                        sensorController.getGravityZ());
                String blindDir = blindFlightMode.processSample(axG, ayG, azG);
                if (blindDir != null && ttsReady) {
                    long now = System.currentTimeMillis();
                    if (!blindDir.equals(lastTtsPhrase) || now - lastTtsSpeakMs > 8000) {
                        lastTtsPhrase = blindDir;
                        lastTtsSpeakMs = now;
                        if (android.os.Build.VERSION.SDK_INT >= 21) {
                            tts.speak(blindDir, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
                        } else {
                            tts.speak(blindDir, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                }
                return; // в слепом режиме не кормим обычный детектор
            }

            if (thermalDetector != null && !simMode && !scenarioMode) {
                // Выбор источника rotation для преобразования accel в мировые координаты
                float[] effectiveRot = rotMatrix;
                boolean useWorldTransform = false;

                if (compassReady && sensorController.isMagAccurate()) {
                    // Штатный режим: магнитометр + гравитация (точный компас)
                    useWorldTransform = true;
                } else if (gpsManager.isReady() && gpsManager.getSpeed() > 2f
                        && gpsManager.getFixAgeMs() < 5000) {
                    // Fallback: магнитометр не калиброван, но есть GPS-курс
                    // Строим rotation из GPS heading + gravity (с учётом наклона)
                    SensorController.buildRotationFromGpsHeading(
                            gpsManager.getHeading(),
                            sensorController.getGravityX(),
                            sensorController.getGravityY(),
                            sensorController.getGravityZ(),
                            worldAccelOut);  // переиспользуем массив как временный
                    // Копируем в локальный массив для transform
                    float[] gpsRot = new float[9];
                    System.arraycopy(worldAccelOut, 0, gpsRot, 0, 9);
                    effectiveRot = gpsRot;
                    useWorldTransform = true;
                }

                if (useWorldTransform) {
                    worldAccelOut[0] = effectiveRot[0] * axG * 9.81f
                            + effectiveRot[1] * ayG * 9.81f
                            + effectiveRot[2] * azG * 9.81f;
                    worldAccelOut[1] = effectiveRot[3] * axG * 9.81f
                            + effectiveRot[4] * ayG * 9.81f
                            + effectiveRot[5] * azG * 9.81f;
                    thermalDetector.processSample(worldAccelOut[0] / 9.81f,
                            worldAccelOut[1] / 9.81f);
                } else {
                    // Нет компаса и нет GPS — используем raw (телефонные координаты)
                    thermalDetector.processSample(axG, ayG);
                }
            }
            // Logging 25 Гц (через децимацию внутри LogManager)
            logManager.recordSample();
        }

        @Override
        public void onGravityAccelSample(float axMs2, float ayMs2, float azMs2,
                                         float headingDeg, float[] rotMatrix,
                                         boolean compassReady) {
            if (!sensorController.hasLinearAccel() && thermalDetector != null && !simMode && !scenarioMode) {
                // Выбор источника rotation (как в onLinearAccelSample)
                float[] effectiveRot = rotMatrix;
                boolean useWorldTransform = false;

                if (compassReady && sensorController.isMagAccurate()) {
                    useWorldTransform = true;
                } else if (gpsManager.isReady() && gpsManager.getSpeed() > 2f
                        && gpsManager.getFixAgeMs() < 5000) {
                    SensorController.buildRotationFromGpsHeading(
                            gpsManager.getHeading(),
                            sensorController.getGravityX(),
                            sensorController.getGravityY(),
                            sensorController.getGravityZ(),
                            worldAccelOut);
                    float[] gpsRot = new float[9];
                    System.arraycopy(worldAccelOut, 0, gpsRot, 0, 9);
                    effectiveRot = gpsRot;
                    useWorldTransform = true;
                }

                if (useWorldTransform) {
                    worldAccelOut[0] = effectiveRot[0] * axMs2 + effectiveRot[1] * ayMs2 + effectiveRot[2] * azMs2;
                    worldAccelOut[1] = effectiveRot[3] * axMs2 + effectiveRot[4] * ayMs2 + effectiveRot[5] * azMs2;
                    thermalDetector.processSample(worldAccelOut[0] / 9.81f,
                            worldAccelOut[1] / 9.81f);
                } else {
                    thermalDetector.processSample(axMs2 / 9.81f, ayMs2 / 9.81f);
                }
            }
        }
    };

    // ========================================================================
    // Log data provider — для LogManager
    // ========================================================================

    final LogManager.LogDataProvider logDataProvider = new LogManager.LogDataProvider() {
        @Override
        public float getAccelX() { return sensorController.getAccelX(); }
        @Override
        public float getAccelY() { return sensorController.getAccelY(); }
        @Override
        public float getAccelZ() { return sensorController.getAccelZ(); }
        @Override
        public float getGyroX() { return sensorController.getGyroX(); }
        @Override
        public float getGyroY() { return sensorController.getGyroY(); }
        @Override
        public float getGyroZ() { return sensorController.getGyroZ(); }
        @Override
        public float getMagX() { return sensorController.getMagX(); }
        @Override
        public float getMagY() { return sensorController.getMagY(); }
        @Override
        public float getMagZ() { return sensorController.getMagZ(); }
        @Override
        public float getPressure() { return sensorController.getPressure(); }
        @Override
        public float getPitch() { return sensorController.getPitch(); }
        @Override
        public float getRoll() { return sensorController.getRoll(); }
        @Override
        public float getLogHeading() {
            return sensorController.getLogHeading(gpsManager.getHeading());
        }
        @Override
        public float getVario() {
            return sensorController.getVario();
        }
        @Override
        public int getDetectStatus() {
            if (thermalDetector != null) return thermalDetector.getStatus();
            return 0;
        }
        @Override
        public String getThermalLogSuffix() {
            if (thermalDetector != null) {
                ThermalBlip blip = thermalDetector.getCurrentBlip();
                if (blip != null) {
                    float snr = 0f, noiseFloor = 0f;
                    SignalProcessor sp = thermalDetector.getSignalProcessor();
                    if (sp != null) {
                        snr = sp.getSnr();
                        noiseFloor = sp.getNoiseFloor();
                    }
                    return ","
                            + blip.angle
                            + "," + String.format(java.util.Locale.US, "%.1f", blip.strength)
                            + "," + (int) blip.distance
                            + "," + blip.source
                            + "," + String.format(java.util.Locale.US, "%.2f", snr)
                            + "," + String.format(java.util.Locale.US, "%.4f", noiseFloor);
                }
            }
            return ",,,,,,";
        }
    };

    // ========================================================================
    // Flight state listener
    // ========================================================================

    final FlightStateMachine.FlightStateListener flightListener =
            new FlightStateMachine.FlightStateListener() {
        @Override
        public void onFlightStarted() {
            if (trackMode) return; // не пишем лог при реплее
            // Единое имя файла для IGC и sensor companion
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
            String baseName = "Flight_" + sdf.format(new java.util.Date());
            igcLogger.setBaseFileName(baseName);
            logManager.setBaseFileName(baseName);
            logManager.startLogging();
            igcLogger.startLogging();
            igcLogger.clearLiveTrack(); // IGC pipeline: чистый старт
            // C-11: убрал resetCalibration() — баро калибруется на земле,
            // а GPS-калибровка (baroOffset) происходит на первом точном фиксе
            sensorController.calibrateHeading();
            android.util.Log.i("TERMO1", "Flight START: " + baseName);
            runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                    "Полёт обнаружен, запись", android.widget.Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onFlightFinished() {
            igcLogger.stopLogging();
            igcLogger.clearLiveTrack(); // IGC pipeline: стоп
            logManager.stopLogging();
            android.widget.Toast.makeText(MainActivity.this,
                    "Полёт завершён, лог сохранён", android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    // ===== FlightController callback =====
    final FlightController.Callback flightCallback = new FlightController.Callback() {
        @Override
        public void onFlightStarted() {
            flightListener.onFlightStarted();
        }
        @Override
        public void onFlightFinished() {
            flightListener.onFlightFinished();
        }
        @Override
        public void onThermalBlip(ThermalBlip blip) {
            long nowMs = System.currentTimeMillis();
            synchronized (thermalLock) {
                thermals.removeIf(tb -> !tb.isAlive(nowMs));
                thermals.add(blip);
                while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
            }
        }
        @Override
        public float getCompassHeading() {
            return MainActivity.this.getCompassHeading();
        }
    };

    // ========================================================================
    // Process sample (vario sound, flight state)
    // ========================================================================

    void processSample() {
        if (varioSoundManager != null) {
            varioSoundManager.update(sensorController.getVario());
        }

        // Flight state machine (на основе барометрической высоты, C-08 fix)
        if (!testMode && !simMode && !scenarioMode && !trackMode) {
            float alt = sensorController.getAltitudeRaw();
            flightStateMachine.update(alt, SystemClock.elapsedRealtime());
        }
    }

    // ========================================================================
    // Thermal management
    // ========================================================================

    void addThermal(float angle, float strength, float distance, String source) {
        long now = System.currentTimeMillis();
        synchronized (thermalLock) {
            Iterator<ThermalBlip> it = thermals.iterator();
            while (it.hasNext()) {
                if (!it.next().isAlive(now)) it.remove();
            }
            thermals.add(new ThermalBlip(angle, strength, distance, source, now));
            while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
        }
    }

    // ========================================================================
    // Status
    // ========================================================================

    void updateStatus() {
        statusManager.update(trackMode, trackReplayer,
                simMode, thermalDetector,
                scenarioMode, flightSim,
                sensorController,
                varioThermalDetector,
                varioThreshold, prefs,
                varioSoundManager);
        currentStatus = statusManager.getStatus();
    }

    // ========================================================================
    // Calibration
    // ========================================================================

    public void resetCalibration() {
        sensorController.resetCalibration();
        if (thermalDetector != null) thermalDetector.resetCalibration();
        android.widget.Toast.makeText(this,
                "Калибровка сброшена. Ждите 2с...",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ========================================================================
    // Manual logging buttons
    // ========================================================================

    public void startManualLogging() {
        if (logManager.isLogging() || trackMode) return;
        // Единое имя файла для IGC и sensor companion
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String baseName = "Flight_" + sdf.format(new java.util.Date());
        igcLogger.setBaseFileName(baseName);
        logManager.setBaseFileName(baseName);
        logManager.startLogging();
        igcLogger.startLogging();
        // H-06: переводим FSM в полёт при ручном старте
        flightStateMachine.setStateFlying();
        // Калибровка компаса: считаем направление стабильным 2 сек
        sensorController.calibrateHeading();
        android.widget.Toast.makeText(this,
                "Запись лога начата", android.widget.Toast.LENGTH_SHORT).show();
    }

    public void confirmStopLogging() {
        if (!logManager.isLogging()) return;
        new AlertDialog.Builder(this)
                .setTitle("Остановить запись?")
                .setMessage("Лог будет сохранён. Вы уверены?")
                .setPositiveButton("Да", (d, w) -> {
                    flightStateMachine.reset();
                        igcLogger.stopLogging();    // ← C-03 fix: забыт!
                        logManager.stopLogging();
                    android.widget.Toast.makeText(MainActivity.this,
                            "Лог сохранён", android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Нет", null)
                .show();
    }

    // ========================================================================
    // WakeLock
    // ========================================================================

    static final long WAKE_LOCK_TIMEOUT_MS = 3600000L;
    long lastWakeLockRefreshMs;

    void acquireWakeLock() {
        if (powerManager != null && wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "TERMO1:RadarLock");
            if (wakeLock != null) {
                if (logManager != null && logManager.isLogging()) {
                    wakeLock.acquire(); // без таймаута — на весь полёт
                } else {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                }
                lastWakeLockRefreshMs = System.currentTimeMillis();
            }
        }
    }

    void refreshWakeLock() {
        long now = System.currentTimeMillis();
        if (wakeLock != null && (now - lastWakeLockRefreshMs > WAKE_LOCK_TIMEOUT_MS / 2)) {
            releaseWakeLock();
            acquireWakeLock();
        }
    }

    void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    // ========================================================================
    // Render loop
    // ========================================================================

    void startRendering() {
        renderTask = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                updateLiveDisplayFrame(); // IGC pipeline: live DisplayFrame
                radarView.invalidate();
                renderHandler.postDelayed(this, RENDER_INTERVAL_MS);
            }
        };
        renderHandler.postDelayed(renderTask, RENDER_INTERVAL_MS);
    }

    void stopRendering() {
        if (renderTask != null) {
            renderHandler.removeCallbacks(renderTask);
            renderTask = null;
        }
    }

    void startBgProcessing() {
        flightController.setFlags(trackMode, testMode, simMode, scenarioMode);
        flightController.start();
    }

    void stopBgProcessing() {
        flightController.stop();
    }

    // ========================================================================
    // Simulation loop
    // ========================================================================

    void startSimLoop() {
        simTask = new Runnable() {
            @Override
            public void run() {
                if (!simMode || simulation == null) return;
                if (!simulation.isRunning()) {
                    Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long elapsed = now - simStartMs;
                if (elapsed > 75000) {
                    simulation.stop();
                    Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                    return;
                }
                simulation.update(elapsed);

                // Feed simulation data through thermal detector
                if (thermalDetector != null) {
                    thermalDetector.processSample(simulation.getAccelX(), simulation.getAccelY());
                    if (thermalDetector.isBlipConfirmed()) {
                        if (now - lastThermalBeepMs > 3000) {
                            if (varioSoundManager != null && SystemClock.elapsedRealtime() - lastThermalBeepRealMs >= THERMAL_BEEP_INTERVAL_MS) {
                                lastThermalBeepRealMs = SystemClock.elapsedRealtime();
                                varioSoundManager.playThermalBeep();
                            }
                            lastThermalBeepMs = now;
                        }
                    }
                }

                updateStatus();
                processSample();
                simHandler.postDelayed(this, 20); // 50 Гц симуляция
            }
        };
        simHandler.postDelayed(simTask, 20);
    }

    // ========================================================================
    // Test mode
    // ========================================================================

    static final float TEST_AMP_THRESHOLD = 0.05f;
    static final float TEST_STRONG_THRESHOLD = 0.15f;
    static final float TEST_AXIS_RATIO = 2.5f;
    static final float TEST_HEADING_RANGE = 50f;
    static final float TEST_FAST_RMS = 0.3f;
    static final float TEST_CORRECT_RATIO_FLOAT = 0.4f;
    static final int TEST_MAX_STEP_MS = 60000;
    static final int TEST_CORRECT_WINDOW_MS = 1500;

    void startTestMode() {
        testMode = true;
        testStep = 0;
        testStepStartMs = System.currentTimeMillis();
        testStepCorrect = false;
        testFeedback = "";
        testFeedbackColor = Color.argb(255, 255, 255, 255);
        testCorrectStartMs = 0;
        testLastBeepMs = 0;
        // Test log — используем manual logging
        logManager.startLogging();
        nextTestStep();
    }

    void nextTestStep() {
        testStep++;
        testStepStartMs = System.currentTimeMillis();
        testCorrectCount = 0;
        testWindowIdx = 0;
        testWindowFill = 0;
        java.util.Arrays.fill(testWindowBuffer, false);
        headingWindowIdx = 0;
        headingWindowFill = 0;
        java.util.Arrays.fill(headingWindow, 0f);
        testStepCorrect = false;
        testCorrectStartMs = 0;
        testFeedback = "";

        switch (testStep) {
            case 1:
                setTestInstruction("ШАГ 1/6: КАЛИБРОВКА",
                        "Положите телефон НЕПОДВИЖНО на стол",
                        "Датчики калибруются... 5 секунд");
                break;
            case 2:
                setTestInstruction("ШАГ 2/6: ОСЬ X (ВЛЕВО-ВПРАВО)",
                        "Качайте телефон строго ВЛЕВО-ВПРАВО",
                        "Амплитуда: ±3-5 см | Частота: 1-2 Гц");
                break;
            case 3:
                setTestInstruction("ШАГ 3/6: ОСЬ Y (ВПЕРЁД-НАЗАД)",
                        "Качайте телефон строго ВПЕРЁД-НАЗАД",
                        "Амплитуда: ±3-5 см | Частота: 1-2 Гц");
                break;
            case 4:
                setTestInstruction("ШАГ 4/6: ПОВОРОТ (YAW)",
                        "Поверните телефон на 90° ВЛЕВО",
                        "Затем обратно — проверка компаса");
                break;
            case 5:
                setTestInstruction("ШАГ 5/6: СПИРАЛЬ",
                        "Крутите телефон плавно на 360°",
                        "Крен ±30° | 1 оборот за 5-7 секунд");
                break;
            case 6:
                setTestInstruction("ШАГ 6/6: ТРЯСКА",
                        "Трясите телефон БЫСТРО",
                        "Частота: 3-5 Гц | Амплитуда ±5 см");
                break;
            case 7:
                testMode = false;
                logManager.stopLogging();
                showTestCompleteDialog();
                return;
            default:
                testMode = false;
                logManager.stopLogging();
                return;
        }
    }

    int testCorrectCount;

    void setTestInstruction(String title, String line1, String line2) {
        testInstruction = title + "\n" + line1 + "\n" + line2;
    }

    void updateTestFeedback() {
        if (!testMode || testStepCorrect || thermalDetector == null) return;

        SignalProcessor sp = thermalDetector.getSignalProcessor();
        if (sp == null) return;
        float hpX = sp.getBpX();
        float hpY = sp.getBpY();
        float level = sp.getTurbulenceLevel();
        float levelMs2 = level * 9.81f;
        float headingNow = getCompassHeading();
        long now = System.currentTimeMillis();
        long stepElapsed = now - testStepStartMs;

        if (stepElapsed > TEST_MAX_STEP_MS) {
            testFeedbackColor = Color.argb(255, 255, 100, 100);
            testFeedback = "⏱ Время вышло. Переход к следующему шагу...";
            nextTestStep();
            return;
        }

        int secLeft = (int)((TEST_MAX_STEP_MS - stepElapsed) / 1000);
        String timeStr = "Осталось: " + secLeft + "с";

        boolean correct = false;
        String feedback = "";
        int color = Color.argb(255, 255, 255, 200);

        switch (testStep) {
            case 1: {
                if (stepElapsed > 5000) {
                    correct = true;
                    feedback = "✅ Калибровка завершена";
                    color = Color.argb(255, 0, 255, 0);
                } else {
                    feedback = "⏳ Калибровка... " + (stepElapsed / 1000 + 1) + "/5 с";
                    color = Color.argb(200, 200, 200, 200);
                }
                break;
            }
            case 2: {
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                boolean frameCorrect = absX > TEST_AMP_THRESHOLD && absY < absX / TEST_AXIS_RATIO;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();
                float mixRatio = (absX + absY > 0.001f) ? absX / (absX + absY) : 0;

                if (windowRatio > TEST_CORRECT_RATIO_FLOAT) {
                    correct = (stepElapsed > 5000);
                    if (correct) {
                        feedback = "✅ ОСЬ X: отлично! X/Y=" + String.format("%.1f", mixRatio);
                        color = Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "⬅️➡️ Продолжайте качать влево-вправо";
                        color = Color.argb(255, 0, 255, 0);
                    }
                } else if (absY > absX * 1.5f && absY > 0.002f) {
                    feedback = "❌ Качайте ВЛЕВО-ВПРАВО, а не вперёд-назад!";
                    color = Color.argb(255, 255, 100, 100);
                } else if (absX < 0.001f) {
                    feedback = "⏳ Качайте СИЛЬНЕЕ! Амплитуда ±3-5 см, частота 1-2 Гц";
                    color = Color.argb(200, 200, 200, 0);
                } else if (absX < TEST_AMP_THRESHOLD) {
                    feedback = "⬆️ Увеличьте амплитуду, качайте размашистее!";
                    color = Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "⬅️➡️ Хорошо, держите ритм!";
                    color = Color.argb(255, 0, 255, 0);
                }
                feedback += " | X:" + String.format("%.0f", absX*1000) + "mG Y:" + String.format("%.0f", absY*1000) + "mG " + timeStr;
                break;
            }
            case 3: {
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                boolean frameCorrect = absY > TEST_AMP_THRESHOLD && absX < absY / TEST_AXIS_RATIO;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO_FLOAT) {
                    correct = (stepElapsed > 5000);
                    if (correct) {
                        feedback = "✅ ОСЬ Y: отлично!";
                        color = Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "↕️ Продолжайте качать вперёд-назад";
                        color = Color.argb(255, 0, 255, 0);
                    }
                } else if (absX > absY * 1.5f && absX > 0.002f) {
                    feedback = "❌ Качайте ВПЕРЁД-НАЗАД, не в стороны!";
                    color = Color.argb(255, 255, 100, 100);
                } else if (absY < 0.001f) {
                    feedback = "⏳ Качайте СИЛЬНЕЕ по оси Y! Амплитуда ±3-5 см";
                    color = Color.argb(200, 200, 200, 0);
                } else if (absY < TEST_AMP_THRESHOLD) {
                    feedback = "⬆️ Добавьте амплитуды, качайте размашистее!";
                    color = Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "↕️ Хорошо, продолжайте!";
                    color = Color.argb(255, 0, 255, 0);
                }
                feedback += " | X:" + String.format("%.0f", absX*1000) + "mG Y:" + String.format("%.0f", absY*1000) + "mG " + timeStr;
                break;
            }
            case 4: {
                if (!sensorController.isCompassReady()) {
                    feedback = "⏳ Компас калибруется...";
                    color = Color.argb(200, 200, 200, 0);
                    break;
                }
                updateHeadingWindow(headingNow);
                float headingRange = getHeadingRange();
                boolean frameCorrect = headingRange > 50f;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO_FLOAT) {
                    correct = true;
                    feedback = "✅ Компас работает! Размах " + String.format("%.0f°", headingRange);
                    color = Color.argb(255, 0, 255, 0);
                } else if (headingRange > 30f) {
                    feedback = "🔄 Ещё поворот! Нужно не менее 50° разворота";
                    color = Color.argb(255, 255, 200, 0);
                } else if (headingRange > 10f) {
                    feedback = "🔄 Поверните телефон на 90° влево (больше! h=" + String.format("%.0f°", headingNow) + ")";
                    color = Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "🔄 Поверните телефон на 90° ВЛЕВО от текущего положения!";
                    color = Color.argb(200, 200, 200, 0);
                }
                feedback += " | range=" + String.format("%.0f°", headingRange) + " " + timeStr;
                break;
            }
            case 5: {
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                float bothActive = absX + absY;
                updateHeadingWindow(headingNow);
                float headingRange = getHeadingRange();

                boolean frameCorrect = bothActive > TEST_AMP_THRESHOLD && headingRange > 20f;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO_FLOAT) {
                    correct = (stepElapsed > 7000);
                    if (correct) {
                        feedback = "✅ СПИРАЛЬ: отлично! (" + String.format("%.0f", bothActive*9.81f*100) + " см/с²)";
                        color = Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "🌀 Крутите + трясите, уже хорошо";
                        color = Color.argb(255, 0, 255, 0);
                    }
                } else if (bothActive > TEST_AMP_THRESHOLD && headingRange < 10f) {
                    feedback = "🌀 Крутите телефон (поворачивайте!), не только трясите!";
                    color = Color.argb(255, 255, 200, 0);
                } else if (headingRange > 20f && bothActive < TEST_AMP_THRESHOLD) {
                    feedback = "🌀 Трясите активнее во время поворота!";
                    color = Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "🌀 Трясите + крутите одновременно!";
                    color = Color.argb(200, 200, 200, 0);
                }
                feedback += " " + timeStr;
                break;
            }
            case 6: {
                boolean frameCorrect = level > TEST_STRONG_THRESHOLD;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO_FLOAT) {
                    correct = (stepElapsed > 3000);
                    if (correct) {
                        feedback = "✅ ТРЯСКА: SNR=" + String.format("%.1f", sp.getSnr());
                        color = Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "💨 Продолжайте трясти! " + String.format("%.0f", levelMs2*100) + " см/с²";
                        color = Color.argb(255, 0, 255, 0);
                    }
                } else if (level > 0.01f) {
                    feedback = "💨 БЫСТРЕЕ! Нужно 3-5 Гц (качков в секунду)";
                    color = Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "💨 Трясите БЫСТРЕЕ И СИЛЬНЕЕ! Частота 3-5 Гц";
                    color = Color.argb(200, 200, 200, 0);
                }
                feedback += " | " + String.format("%.0f%%", windowRatio*100) + " " + timeStr;
                break;
            }
        }

        if (now - testFeedbackLastUpdate > 100 || correct != testStepCorrect) {
            testFeedback = feedback;
            testFeedbackColor = color;
            testFeedbackLastUpdate = now;
        }

        if (correct && !testStepCorrect) {
            testStepCorrect = true;
            if (varioSoundManager != null && (now - testLastBeepMs > 2000)) {
                varioSoundManager.playTestBeep();
                testLastBeepMs = now;
            }
            float angleDeg = sp.getStableDirDeg();
            if (angleDeg < 0) angleDeg += 360f;
            float strength = Math.min(sp.getSnr(), 8f);
            addThermal(angleDeg, strength, 30f, "test_step" + testStep);
            testHandler.postDelayed(() -> {
                if (testMode) nextTestStep();
            }, 2000);
        }
    }

    void addToWindow(boolean correct) {
        testWindowBuffer[testWindowIdx] = correct;
        testWindowIdx = (testWindowIdx + 1) % TEST_WINDOW_FRAMES;
        if (testWindowFill < TEST_WINDOW_FRAMES) testWindowFill++;
    }

    float getWindowCorrectRatio() {
        if (testWindowFill == 0) return 0f;
        int count = 0;
        for (int i = 0; i < testWindowFill; i++) {
            if (testWindowBuffer[i]) count++;
        }
        return (float) count / testWindowFill;
    }

    void updateHeadingWindow(float heading) {
        headingWindow[headingWindowIdx] = heading;
        headingWindowIdx = (headingWindowIdx + 1) % HEADING_WINDOW_FRAMES;
        if (headingWindowFill < HEADING_WINDOW_FRAMES) headingWindowFill++;
    }

    float getHeadingRange() {
        if (headingWindowFill < 10) return 0f;
        float[] hdgs = new float[headingWindowFill];
        System.arraycopy(headingWindow, 0, hdgs, 0, headingWindowFill);
        java.util.Arrays.sort(hdgs);
        float maxGap = hdgs[0] + 360f - hdgs[hdgs.length - 1];
        for (int i = 1; i < hdgs.length; i++) {
            float gap = hdgs[i] - hdgs[i - 1];
            if (gap > maxGap) maxGap = gap;
        }
        return 360f - maxGap;
    }

    void stopTestMode() {
        testMode = false;
        if (testTask != null) {
            testHandler.removeCallbacks(testTask);
        }
    }

    // ========================================================================
    // Flight Scenario Test
    // ========================================================================

    Handler scenarioHandler = new Handler(Looper.getMainLooper());
    Runnable scenarioTask;
    long scenarioStartMs;

    void startFlightScenario() {
        scenarioMode = true;
        scenarioStartMs = SystemClock.elapsedRealtime();

        // Clear old state
        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }

        flightSim = new FlightSimulator();
        flightSim.start();

        // Start scenario update loop (50 Hz)
        scenarioTask = new Runnable() {
            @Override
            public void run() {
                if (!scenarioMode || flightSim == null) return;
                if (!flightSim.isRunning()) {
                    stopFlightScenario();
                    return;
                }
                long elapsed = SystemClock.elapsedRealtime() - scenarioStartMs;
                flightSim.update(elapsed);

                // Feed accel through thermal detector
                if (thermalDetector != null && !testMode) {
                    thermalDetector.processSample(flightSim.getAccelX(), flightSim.getAccelY());
                    ThermalBlip detBlip = thermalDetector.getCurrentBlip();
                    if (detBlip != null) {
                        long now = System.currentTimeMillis();
                        if (now - detBlip.bornMs < detBlip.lifeMs) {
                            synchronized (thermalLock) {
                                boolean found = false;
                                for (ThermalBlip tb : thermals) {
                                    if (tb.bornMs == detBlip.bornMs) {
                                        tb.distance = detBlip.distance;
                                        tb.strength = detBlip.strength;
                                        tb.angle = detBlip.angle;
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    thermals.add(detBlip);
                                    while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                                }
                            }
                        }
                    }
                }

                // Also add a persistent thermal blip from FlightSimulator
                if (flightSim.isThermalVisible()) {
                    long now = System.currentTimeMillis();
                    synchronized (thermalLock) {
                        // Remove expired
                        Iterator<ThermalBlip> it = thermals.iterator();
                        while (it.hasNext()) {
                            if (!it.next().isAlive(now)) it.remove();
                        }
                        // Check if we already have a blip at similar bearing
                        boolean hasBlip = false;
                        for (ThermalBlip tb : thermals) {
                            if (Math.abs(tb.angle - flightSim.getThermalBearing()) < 20f
                                    && Math.abs(tb.distance - flightSim.getThermalDistance()) < 30f) {
                                tb.distance = flightSim.getThermalDistance();
                                tb.angle = flightSim.getThermalBearing();
                                hasBlip = true;
                                break;
                            }
                        }
                        if (!hasBlip) {
                            float strength = flightSim.isCircling() ? 8f : 4f;
                            thermals.add(new ThermalBlip(
                                    flightSim.getThermalBearing(),
                                    strength,
                                    flightSim.getThermalDistance(),
                                    "scenario",
                                    now
                            ));
                            while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                        }
                    }
                }

                scenarioHandler.postDelayed(this, 20);
            }
        };
        scenarioHandler.postDelayed(scenarioTask, 20);

        android.widget.Toast.makeText(MainActivity.this,
                "🧪 Сценарий полёта запущен (100с)", android.widget.Toast.LENGTH_SHORT).show();
    }

    void stopFlightScenario() {
        scenarioMode = false;
        if (scenarioTask != null) {
            scenarioHandler.removeCallbacks(scenarioTask);
            scenarioTask = null;
        }
        if (flightSim != null) {
            flightSim.stop();
            flightSim = null;
        }
        // Clean thermal blips
        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }
        android.widget.Toast.makeText(MainActivity.this,
                "✈️ Сценарий завершён", android.widget.Toast.LENGTH_SHORT).show();
    }

    // ========================================================================
    // Track replay (сим2)
    // ========================================================================

    Handler trackHandler = new Handler(Looper.getMainLooper());
    Runnable trackTask;
    long trackStartMs;
    long trackPrevFrameMs;

    void startTrackReplay(String filePath) {
        Log.i("TERMO1_REPLAY", "startTrackReplay (IGC pipeline): filePath=" + filePath);
        // Останавливаем запись если активна
        if (logManager.isLogging() || igcLogger.isLogging()) {
            igcLogger.stopLogging();
            logManager.stopLogging();
        }
        trackMode = true;
        trackStartMs = SystemClock.elapsedRealtime();

        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }
        trailHead = 0;
        trailCount = 0;

        // ===== IGC PIPELINE: парсим трек =====
        IGCParser.ParseResult result = null;
        if (filePath != null) {
            result = IGCParser.parse(filePath);
            if (result == null || result.track.length < 2) {
                android.widget.Toast.makeText(MainActivity.this,
                        "Не удалось загрузить трек: " + filePath,
                        android.widget.Toast.LENGTH_LONG).show();
                trackMode = false;
                return;
            }
            Log.i("TERMO1_REPLAY", "IGC parsed: " + result.track.length + " points, "
                    + "QNH=" + result.qnhHpa + ", CRC=" + (result.crcValid ? "OK" : "MISSING"));
        } else {
            // Встроенный демо-трек
            try {
                result = IGCParser.parse(getResources().openRawResource(
                        com.termo1.radar.R.raw.track_replay));
            } catch (Exception e) {
                android.util.Log.e("TERMO1_REPLAY", "Failed to load demo track", e);
            }
            if (result == null || result.track.length < 2) {
                android.widget.Toast.makeText(MainActivity.this,
                        "Не удалось загрузить встроенный трек",
                        android.widget.Toast.LENGTH_LONG).show();
                Log.e("TERMO1_REPLAY", "Embedded track load returned < 2 points");
                trackMode = false;
                return;
            }
        }

        // Создаём анализатор
        igcAnalyzer = new IGCAnalyzer(result.track);
        igcAnalyzer.scrollTo(0);
        currentDisplayFrame = igcAnalyzer.getCurrentFrame();

        // Bridge для RadarView (делегирует DisplayFrame)
        trackReplayer = new TrackReplayerDisplayBridge();

        Log.i("TERMO1_REPLAY", "IGC analyzer ready: totalTime=" + igcAnalyzer.getTotalTime()
                + "s, launchAlt=" + igcAnalyzer.getLaunchAltitude() + "m");

        trackPrevFrameMs = 0;

        // Останавливаем GPS и сенсоры — при реплее нужны только данные из IGC
        gpsManager.stopGps();
        sensorController.unregisterSensors();

        // Загружаем companion ZIP для accel-блипов
        boolean hasSensorZip = false;
        if (filePath != null) {
            String zipPath = filePath.replace(".igc", ".zip");
            hasSensorZip = new java.io.File(zipPath).exists();
        }
        boolean finalHasSensorZip = hasSensorZip;
        String finalFilePath = filePath;

        trackTask = new Runnable() {
            @Override
            public void run() {
                IGCAnalyzer analyzer = igcAnalyzer;
                if (!trackMode || analyzer == null) return;

                float progress = analyzer.getCurrentTime() / Math.max(analyzer.getTotalTime(), 1f);
                if (progress >= 1f) {
                    stopTrackReplay();
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long realDeltaMs;
                if (trackPrevFrameMs == 0) {
                    realDeltaMs = 0;
                } else {
                    realDeltaMs = now - trackPrevFrameMs;
                    if (realDeltaMs > 100) realDeltaMs = 50;
                }
                trackPrevFrameMs = now;

                // Продвигаем анализатор
                float simDt = (realDeltaMs / 1000f) * 1.0f; // 1x speed default
                analyzer.advance(simDt);

                // Публикуем DisplayFrame (thread-safe: volatile write)
                currentDisplayFrame = analyzer.getCurrentFrame();

                // Accel blips из companion ZIP (add-on)
                if (finalHasSensorZip && thermalDetector != null && finalFilePath != null) {
                    feedAccelFromZip(analyzer, finalFilePath);
                }

                // Продолжаем
                if (!trackHandler.postDelayed(this, 20)) {
                    Log.e("TERMO1_REPLAY", "Failed to post trackTask");
                }
            }
        };
        trackHandler.postDelayed(trackTask, 20);

        // Статус и подгрузка карты под трек
        trackFileName = filePath != null ? new java.io.File(filePath).getName() : "встроенный";
        trackSpeedIdx = 0;
        String trackName = trackFileName;
        currentStatus = "▶ IGC Проигрываем: " + trackName;
        android.widget.Toast.makeText(MainActivity.this,
                "▶ " + trackName + " (1x)", android.widget.Toast.LENGTH_SHORT).show();

        // Форсированная загрузка карты в координатах трека
        DisplayFrame frame = currentDisplayFrame;
        if (frame != null && frame.pilotLat != 0.0 && frame.pilotLon != 0.0) {
            staticMapLoader.forceUpdate(frame.pilotLat, frame.pilotLon, 14);
        }
    }

    /**
     * Accel блипы из companion ZIP (add-on к IGC pipeline).
     * Читает из ZIP в соответствии с текущей позицией IGCAnalyzer.
     */
    private void feedAccelFromZip(IGCAnalyzer analyzer, String filePath) {
        String zipPath = filePath.replace(".igc", ".zip");
        java.io.File zipFile = new java.io.File(zipPath);
        if (!zipFile.exists()) return;

        // Простейшая реализация: открываем ZIP, ищем CSV, парсим построчно
        // до позиции analyzer.getCurrentTime()
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipPath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".csv")) {
                    zis.closeEntry();
                    continue;
                }
                java.io.BufferedReader br =
                        new java.io.BufferedReader(new java.io.InputStreamReader(zis));
                br.readLine(); // skip header
                float trackTimeSec = analyzer.getCurrentTime();
                long targetDtMs = (long)(trackTimeSec * 1000f);

                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 10) continue;
                    try {
                        long dtMs = Long.parseLong(parts[0]);
                        if (dtMs > targetDtMs + 100) break; // past our position
                        if (Math.abs(dtMs - targetDtMs) < 200) {
                            float axMg = Float.parseFloat(parts[9]);
                            float ayMg = Float.parseFloat(parts[10]);
                            // Feed to thermal detector
                            if (thermalDetector != null) {
                                float axG = axMg / 1000f;
                                float ayG = ayMg / 1000f;
                                thermalDetector.processSample(axG, ayG);
                                ThermalBlip detBlip = thermalDetector.getCurrentBlip();
                                if (detBlip != null) {
                                    long nowMs = System.currentTimeMillis();
                                    if (nowMs - detBlip.bornMs < detBlip.lifeMs) {
                                        synchronized (thermalLock) {
                                            boolean found = false;
                                            for (ThermalBlip tb : thermals) {
                                                if (tb.bornMs == detBlip.bornMs) {
                                                    tb.distance = detBlip.distance;
                                                    tb.strength = detBlip.strength;
                                                    tb.angle = detBlip.angle;
                                                    found = true;
                                                    break;
                                                }
                                            }
                                            if (!found) {
                                                thermals.add(detBlip);
                                                while (thermals.size() > THERMAL_LIMIT)
                                                    thermals.remove(0);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                zis.closeEntry();
                break;
            }
        } catch (Exception e) {
            android.util.Log.e("TERMO1_REPLAY", "Accel feed error: " + zipPath, e);
        }
    }

    void stopTrackReplay() {
        trackMode = false;
        // Исправлено MA-3: возвращаем GPS и сенсоры к жизни
        gpsManager.startGps();
        gpsManager.setSensorController(sensorController);
        try {
            gpsManager.requestUpdates(Looper.getMainLooper());
        } catch (SecurityException e) { }
        sensorController.registerSensors();
        if (trackTask != null) {
            trackHandler.removeCallbacks(trackTask);
            trackTask = null;
        }
        // IGC pipeline cleanup
        igcAnalyzer = null;
        currentDisplayFrame = null;
        if (trackReplayer != null) {
            trackReplayer.stop();
            trackReplayer = null;
        }
        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }
        android.widget.Toast.makeText(MainActivity.this,
                "Воспроизведение трека завершено", android.widget.Toast.LENGTH_SHORT).show();
    }

    void showTestCompleteDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Тест завершён!")
                .setMessage("Все 6 шагов выполнены.\n"
                        + "Лог сохранён.\n\n"
                        + "Зайдите в настройки (⚙️),\n"
                        + "нажмите 'Отправить логи'.\n"
                        + "Выберите тестовый файл и отправьте.")
                .setPositiveButton("OK", null)
                .show();
    }

    public String getTestInstruction() { return testInstruction; }
    public boolean isTestMode() { return testMode; }
    public String getTestFeedback() { return testFeedback; }
    public int getTestFeedbackColor() { return testFeedbackColor; }

    // ========================================================================
    // Compass heading (для UI и test mode)
    // ========================================================================

    float getCompassHeading() {
        boolean magReliable = sensorController.isCompassReady()
                && sensorController.isMagAccurate();
        if (magReliable) {
            return sensorController.getHeading();
        }
        if (gpsManager.isReady() && gpsManager.getSpeed() > 2f
                && gpsManager.getFixAgeMs() < 5000) {
            sensorController.updateGpsHeading(gpsManager.getHeading());
            return sensorController.getHeading();
        }
        // Нет источника — держим последнее известное значение, НЕ 0
        return sensorController.getHeading();
    }

    // ========================================================================
    // TTS голосовые подсказки
    // ========================================================================

    /** Произнести направление на термик (только передние сектора 45°) */
    void speakThermalDirection(float thermalAngle, float distanceMeters) {
        float heading = getCompassHeading();
        float rel = (thermalAngle - heading + 540f) % 360f - 180f; // [-180, 180]

        String phrase;
        if (rel < -112.5f || rel > 112.5f) {
            // Задние сектора — молчим, но логируем
            logManager.recordEvent("VOICE_SKIP",
                    "rear_sector angle=" + (int)thermalAngle + " rel=" + (int)rel);
            return;
        }
        if (rel < -67.5f)       phrase = "Термик слева";
        else if (rel < -22.5f)  phrase = "Термик спереди слева";
        else if (rel < 22.5f)   phrase = "Термик спереди";
        else if (rel < 67.5f)   phrase = "Термик спереди справа";
        else                    phrase = "Термик справа";

        // Округление до 10 метров
        int distRounded = ((int)Math.round(distanceMeters / 10f)) * 10;
        if (distRounded < 10) distRounded = 10;
        if (distRounded > 150) distRounded = 150;
        phrase += " " + distRounded + " метров";

        // Дебаунс: не повторять ту же фразу чаще чем раз в 8 секунд
        long now = System.currentTimeMillis();
        if (phrase.equals(lastTtsPhrase) && now - lastTtsSpeakMs < 8000) {
            logManager.recordEvent("VOICE_SKIP",
                    "debounce phrase=" + phrase);
            return;
        }

        lastTtsPhrase = phrase;
        lastTtsSpeakMs = now;

        logManager.recordEvent("VOICE_SPOKEN",
                phrase + " angle=" + (int)thermalAngle + " dist=" + (int)distanceMeters);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            tts.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
        } else {
            tts.speak(phrase, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /**
     * Обновить live DisplayFrame из IGC-логгера (1.5Hz, каждые 666ms).
     * Вызывается из renderTask.
     */
    void updateLiveDisplayFrame() {
        // Только если идёт запись IGC и не в режиме реплея
        if (trackMode || igcLogger == null || !igcLogger.isLogging()) return;

        long now = System.currentTimeMillis();
        if (now - lastLiveIgcAnalyzeMs < 666) return; // 1.5Hz
        lastLiveIgcAnalyzeMs = now;

        // Получить live трек из IGC-логгера
        com.termo1.radar.igc.TrackPoint[] liveTrack = igcLogger.getLiveTrackArray();
        if (liveTrack == null || liveTrack.length < 2) return;

        // Создать/обновить анализатор
        liveAnalyzer = new IGCAnalyzer(liveTrack);
        liveAnalyzer.scrollTo(liveAnalyzer.getTotalTime()); // на последнюю позицию

        // Публикуем DisplayFrame
        currentDisplayFrame = liveAnalyzer.getCurrentFrame();

        // Bridge: trackReplayer читает из DisplayFrame для RadarView
        // (trackReplayer уже установлен в onCreate как TrackReplayerDisplayBridge)
    }

    /**
     * Доступ к текущему DisplayFrame для RadarView.
     * Работает в обоих режимах: реплей (IGC pipeline) и live.
     */
    public DisplayFrame displayFrame() {
        return currentDisplayFrame;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.exit_dialog_title))
                .setPositiveButton(getString(R.string.exit_dialog_yes),
                        (d, w) -> finishAffinity())
                .setNegativeButton(getString(R.string.exit_dialog_no), null)
                .show();
    }

    void showExitOnScreenDialog() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("Завершить TERMO1?")
                .setPositiveButton("Да", (d, w) -> finishAffinity())
                .setNegativeButton("Нет", null)
                .show();
    }

    // ========================================================================
    // RadarView — inner class
    // ========================================================================
}

