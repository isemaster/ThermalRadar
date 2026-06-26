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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.termo1.radar.core.FlightSimulator;
import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.core.TrackReplayer;
import com.termo1.radar.core.SignalProcessor;
import com.termo1.radar.core.ThermalDetector;
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
import com.termo1.radar.ui.SettingsActivity;
import com.termo1.radar.ui.UiManager;
import com.termo1.radar.ui.VarioSoundManager;

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

    private static final int SAMPLE_RATE_HZ = 50;
    private static final int RENDER_FPS = 30;
    private static final long RENDER_INTERVAL_MS = 33L;
    private static final int THERMAL_LIMIT = 12;
    private static final float MAX_DISTANCE_M = 150.0f;

    // GPS trail
    private static final int GPS_TRAIL_MAX = 2000;
    private static final long GPS_TRAIL_MAX_AGE_MS = 300_000L; // 5 минут
    private static final long GPS_TRAIL_ADD_INTERVAL_MS = 1000L; // добавлять не чаще 1 раз/сек

    // ========================================================================
    // Modules
    // ========================================================================

    private SensorController sensorController;
    private GpsManager gpsManager;
    private LogManager logManager;
    private IgcLogger igcLogger;
    private StaticMapLoader staticMapLoader;
    private FlightStateMachine flightStateMachine;
    private BlindFlightMode blindFlightMode;
    private boolean blindModeEnabled;
    private boolean voicePromptsEnabled;
    private CirclingManager circlingManager;
    private VarioThermalDetector varioThermalDetector;
    private float varioThreshold = 0.5f;

    // Phase 2: ThermalLocator + LiftDatabase
    private ThermalLocator thermalLocator;
    private LiftDatabase liftDatabase;
    private float prevThermalLiftBaseline;
    private long lastThermalBaseCalcMs;
    private static final long THERMAL_BASE_INTERVAL_MS = 5000; // раз в 5с
    private ThermalBaseResult lastThermalBaseResult;

    // ThermalRadarService (singleton, без binding)
    private ThermalRadarService radarService;

    // ========================================================================
    // Core
    // ========================================================================

    private ThermalDetector thermalDetector;
    private SharedPreferences prefs;

    // Tracking for event logging state transitions
    private boolean prevCirclingState;
    private boolean prevLabelState;
    private float prevWindFrom = -2f;
    private float prevWindSpd = -2f;
    private WindCorrected lastDrift;  // Phase 5: последний расчёт сноса

    // ========================================================================
    // Thermals
    // ========================================================================

    private final List<ThermalBlip> thermals = new ArrayList<>();
    private final List<ThermalBlip> thermalsCopy = new ArrayList<>();
    private final Object thermalLock = new Object();

    // GPS trail storage: [lat, lon, timeMs, vario]
    private final List<double[]> gpsTrail = new ArrayList<>();

    // Phase 6: точки входа/выхода из термиков
    private static final int MAX_MARKERS = 100;
    private final List<double[]> entryMarkers = new ArrayList<>();  // [lat, lon]
    private final List<double[]> exitMarkers = new ArrayList<>();   // [lat, lon]

    // ===== Reusable buffers для onDraw (устранение аллокаций, T13) =====
    private final float[] distanceResult = new float[2];
    private final float[] markerPxBuf = new float[MAX_MARKERS * 2];
    private final float[] markerPyBuf = new float[MAX_MARKERS * 2];
    private final boolean[] markerIsEntry = new boolean[MAX_MARKERS * 2];
    /** Буфер цветов трека (vario → цвет, возраст → alpha) */
    private final int[] trailColorBuf = new int[GPS_TRAIL_MAX];
    /** Буферы для map-трека (синий, масштаб карты 3×3 км, без клипа 150м) */
    private final float[] mapTrailPxBuf = new float[GPS_TRAIL_MAX];
    private final float[] mapTrailPyBuf = new float[GPS_TRAIL_MAX];

    // ========================================================================
    // UI
    // ========================================================================

    private RadarView radarView;
    private RadarRenderer radarRenderer;
    private UiManager uiManager;
    private VarioSoundManager varioSoundManager;
    private AlertDialog exitDialog;
    private volatile String currentStatus = "ПОИСК";
    private String previousStatus = "";

    // ===== TTS голосовые подсказки =====
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady;
    private String lastTtsPhrase;
    private long lastTtsSpeakMs;

    // ===== UI-сглаживание heading (Fix C — time-based SLERP) =====
    private float headingDisplaySmoothed = 0f;
    private boolean headingDisplayInitialized = false;
    private long lastHeadingFrameMs = 0;

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
    private static int varioToColor(float vario, float alpha) {
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

    private boolean simMode;
    private boolean scenarioMode;
    private boolean trackMode;
    private SimulationManager simulation;
    private FlightSimulator flightSim;
    private TrackReplayer trackReplayer;
    private long simStartMs;
    private long lastThermalBeepMs;
    private long lastMaxSnrResetMs;
    private Handler simHandler = new Handler(Looper.getMainLooper());
    private Runnable simTask;

    // ========================================================================
    // Processing / rendering
    // ========================================================================

    private volatile boolean running;
    private Handler renderHandler = new Handler(Looper.getMainLooper());
    private Runnable renderTask;
    // Фоновый таймер 10 Гц для обработки (работает при выкл экране)
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Runnable bgTask;
    private static final long BG_INTERVAL_MS = 100L;

    // Track replay
    private static final float[] PLAYBACK_SPEEDS = {1f, 2f, 5f, 10f};
    private int trackSpeedIdx;
    private String trackFileName;

    // ========================================================================
    // Power
    // ========================================================================

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    // ========================================================================
    // Test mode
    // ========================================================================

    private boolean testMode;
    private int testStep;
    private String testInstruction = "";
    private String testFeedback = "";
    private int testFeedbackColor;
    private boolean testStepCorrect;
    private long testLastBeepMs;
    private long testCorrectStartMs;
    private long testStepStartMs;
    private boolean testBeepPlaying;
    // Термик-бип: не чаще раза в 6 секунд (было ~2с, уменьшили в 3 раза)
    private static final long THERMAL_BEEP_INTERVAL_MS = 6000L;
    private long lastThermalBeepRealMs;
    private Handler testHandler = new Handler(Looper.getMainLooper());
    private Runnable testTask;

    // Test window
    private static final int TEST_WINDOW_FRAMES = 45;
    private static final int TEST_CORRECT_RATIO = 4; // 40% как int для window
    private final boolean[] testWindowBuffer = new boolean[TEST_WINDOW_FRAMES];
    private int testWindowIdx;
    private int testWindowFill;
    private long testFeedbackLastUpdate;

    // Heading window
    private static final int HEADING_WINDOW_FRAMES = 150;
    private final float[] headingWindow = new float[HEADING_WINDOW_FRAMES];
    private int headingWindowIdx;
    private int headingWindowFill;

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

        // Start foreground service (WakeLock + notification)
        startForegroundService(new Intent(this, ThermalRadarService.class));

        // Simulation mode
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;

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

    private void handleIntent(Intent intent) {
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

    private final SensorController.SensorDataListener sensorDataListener =
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

    private final LogManager.LogDataProvider logDataProvider = new LogManager.LogDataProvider() {
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

    private final FlightStateMachine.FlightStateListener flightListener =
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
            logManager.stopLogging();
            android.widget.Toast.makeText(MainActivity.this,
                    "Полёт завершён, лог сохранён", android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    // ========================================================================
    // Process sample (vario sound, flight state)
    // ========================================================================

    private void processSample() {
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

    private void addThermal(float angle, float strength, float distance, String source) {
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

    private void updateStatus() {
        if (trackMode) {
            // При реплее — статус из TrackReplayer
            if (trackReplayer != null && trackReplayer.isRunning()) {
                float varioVal = trackReplayer.getVario();
                if (trackReplayer.isThermalActive() || varioVal > 1.0f) {
                    currentStatus = UiManager.STATUS_CLIMB;
                } else if (varioVal < -1.0f) {
                    currentStatus = UiManager.STATUS_SINK;
                } else {
                    currentStatus = UiManager.STATUS_SEARCH;
                }
            }
            return;
        }
        if (simMode) {
            currentStatus = thermalDetector != null
                    ? thermalDetector.getStatusText() : UiManager.STATUS_SEARCH;
            return;
        }

        if (thermalDetector == null) {
            currentStatus = UiManager.STATUS_SEARCH;
            return;
        }

        // Проверяем: есть ли ПОДТВЕРЖДЁННЫЙ блип с расстоянием
        ThermalBlip activeBlip = thermalDetector.getCurrentBlip();
        boolean hasConfirmedBlip = (activeBlip != null)
                && thermalDetector.isBlipConfirmed()
                && (SystemClock.elapsedRealtime() - activeBlip.bornMs < activeBlip.lifeMs);

        // Только если реально есть блип — показываем "термик рядом"
        if (hasConfirmedBlip && (thermalDetector.getStatus() == ThermalDetector.STATUS_THERMAL
                || thermalDetector.getStatus() == ThermalDetector.STATUS_INSIDE)) {
            float distM = activeBlip.distance;
            currentStatus = String.format(java.util.Locale.US, "ТЕРМИК РЯДОМ — %.0fм", distM);
            // Вибрация сработает при смене статуса в onDraw
        } else {
            float vario;
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                vario = flightSim.getVario();
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                vario = trackReplayer.getVario();
            } else {
                vario = sensorController.getVario();
            }
            float level = 0f;
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
                if (sp != null) level = sp.getTurbulenceMs2();
            }

            boolean varioThermal = false;
            if (varioThermalDetector != null && !simMode && !scenarioMode && !trackMode) {
                long now = SystemClock.elapsedRealtime();
                varioThermalDetector.update(vario, now);
                varioThreshold = prefs.getFloat("vario_threshold", 0.5f);
                varioThermalDetector.setThreshold(varioThreshold);
                if (varioSoundManager != null) {
                    varioSoundManager.setDeadBandHigh(varioThreshold);
                }
                varioThermal = varioThermalDetector.isThermalDetected();
            }

            if (varioThermal) {
                currentStatus = "ВАРИО ТЕРМИК";
            } else if (vario > 1.0f && level > 0.3f) {
                currentStatus = UiManager.STATUS_CLIMB;
            } else if (thermalDetector.getStatus() == ThermalDetector.STATUS_INSIDE) {
                currentStatus = UiManager.STATUS_CLIMB;
            } else if (thermalDetector.getStatus() == ThermalDetector.STATUS_THERMAL) {
                // thermal status без блипа — показываем как подозрение
                currentStatus = UiManager.STATUS_SPIRAL;
            } else if (vario < -1.0f) {
                currentStatus = UiManager.STATUS_SINK;
            } else if (thermalDetector.getStatus() == ThermalDetector.STATUS_SUSPECT) {
                currentStatus = UiManager.STATUS_SPIRAL;
            } else {
                currentStatus = UiManager.STATUS_SEARCH;
            }
        }
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

    private static final long WAKE_LOCK_TIMEOUT_MS = 3600000L;
    private long lastWakeLockRefreshMs;

    private void acquireWakeLock() {
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

    private void refreshWakeLock() {
        long now = System.currentTimeMillis();
        if (wakeLock != null && (now - lastWakeLockRefreshMs > WAKE_LOCK_TIMEOUT_MS / 2)) {
            releaseWakeLock();
            acquireWakeLock();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    // ========================================================================
    // Render loop
    // ========================================================================

    private void startRendering() {
        renderTask = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                radarView.invalidate();
                renderHandler.postDelayed(this, RENDER_INTERVAL_MS);
            }
        };
        renderHandler.postDelayed(renderTask, RENDER_INTERVAL_MS);
    }

    private void stopRendering() {
        if (renderTask != null) {
            renderHandler.removeCallbacks(renderTask);
            renderTask = null;
        }
    }

    private void startBgProcessing() {
        bgThread = new HandlerThread("termo1-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgTask = new Runnable() {
            @Override
            public void run() {
                // Обновляем FSM, circlingManager, лог — всё что нужно для TTS/лога
                processSample();
                long bgNow = SystemClock.elapsedRealtime();

                // Speed-based flight detection (C-01 fix: was dead code!)
                if (!testMode && !simMode && !scenarioMode && !trackMode
                        && gpsManager.isReady()
                        && gpsManager.getFixAgeMs() < 5000) {
                    flightStateMachine.updateSpeedBased(
                            gpsManager.getSpeed(),
                            gpsManager.getLat(),
                            gpsManager.getLon(),
                            sensorController.getAltitudeRaw(), // C-08: баро, не GPS
                            bgNow);
                }
                if (!trackMode) { // BUG-7 FIX: don't feed circlingManager with live sensors during replay
                circlingManager.update(
                    sensorController.getGyroZ(),
                    getCompassHeading(),
                    gpsManager.getHeading(),
                    sensorController.getVario(),
                    gpsManager.getLat(),
                    gpsManager.getLon(),
                    gpsManager.getSpeed(),
                    gpsManager.getHeading(),
                    gpsManager.getAltitude(),
                    bgNow);

                // Phase 2: ThermalLocator + LiftDatabase update
                if (circlingManager.isCircling()) {
                    float varioVal = sensorController.getVario();
                    float altMsl = gpsManager.getAltitude();

                    // LiftDatabase: записываем варио в сектор
                    if (!Float.isNaN(varioVal) && !Float.isInfinite(varioVal)) {
                        liftDatabase.recordLift(getCompassHeading(), varioVal);
                    }

                    // ThermalLocator: добавляем точки с весом подъёма
                    double baseline = varioThermalDetector != null
                            ? varioThermalDetector.getBaseline() : prevThermalLiftBaseline;
                    thermalLocator.addPoint(
                            gpsManager.getLat(), gpsManager.getLon(),
                            varioVal, baseline, bgNow);

                    // Дрейфуем и вычисляем центр (если есть ветер)
                    if (circlingManager.getWindFromDeg() >= 0) {
                        double windRad = Math.toRadians(circlingManager.getWindFromDeg() + 180);
                        double windU = circlingManager.getWindSpeedMs() * Math.sin(windRad);
                        double windV = circlingManager.getWindSpeedMs() * Math.cos(windRad);
                        thermalLocator.update(
                                gpsManager.getLat(), gpsManager.getLon(),
                                windU, windV, bgNow);
                    } else {
                        thermalLocator.update(
                                gpsManager.getLat(), gpsManager.getLon(),
                                0, 0, bgNow);
                    }

                    // ThermalBaseEstimator: раз в 5с
                    if (bgNow - lastThermalBaseCalcMs > THERMAL_BASE_INTERVAL_MS) {
                        lastThermalBaseCalcMs = bgNow;
                        float climbAvg = 0f;
                        // Simple climb average from vario
                        if (varioVal > 0) {
                            climbAvg = varioVal;
                        }
                        if (circlingManager.getWindFromDeg() >= 0 && climbAvg > 0.2f) {
                            lastThermalBaseResult = ThermalBaseEstimator.estimate(
                                    gpsManager.getLat(), gpsManager.getLon(),
                                    altMsl, climbAvg,
                                    circlingManager.getWindFromDeg(),
                                    circlingManager.getWindSpeedMs());
                        }
                    }
                } else {
                    // Не крутимся — сброс
                    thermalLocator.reset();
                    liftDatabase.clear();
                    lastThermalBaseResult = null;
                }

                // Phase 5: WindDriftCalculator — расчёт сноса (если есть ветер)
                if (circlingManager.getWindFromDeg() >= 0 && circlingManager.getWindSpeedMs() > 0.3f) {
                    float airspeed = prefs.getFloat("airspeed_ms", 9.5f);
                    lastDrift = WindDriftCalculator.calculate(
                            gpsManager.getHeading(),
                            airspeed,
                            circlingManager.getWindFromDeg(),
                            circlingManager.getWindSpeedMs());
                } else {
                    lastDrift = null;
                }
                } // end !trackMode

                // === Wind estimation from straight flight (live) ===
                // GPS speed ± trim airspeed когда летим прямо, снижение ≤1.3 м/с
                if (!trackMode && !circlingManager.isCircling()
                        && Math.abs(sensorController.getVario()) <= 1.3f
                        && gpsManager.isReady() && gpsManager.getSpeed() > 3f
                        && gpsManager.getFixAgeMs() < 5000) {
                    float heading = getCompassHeading();
                    float track = gpsManager.getHeading();
                    float gpsSpeed = gpsManager.getSpeed();
                    float airspeed = prefs.getFloat("airspeed_ms", 9.5f);
                    circlingManager.estimateWindFromGps(heading, track, gpsSpeed, airspeed);
                }

                bgHandler.postDelayed(this, BG_INTERVAL_MS);
            }
        };
        bgHandler.postDelayed(bgTask, BG_INTERVAL_MS);
    }

    private void stopBgProcessing() {
        if (bgTask != null) {
            bgHandler.removeCallbacks(bgTask);
            bgTask = null;
        }
    }

    // ========================================================================
    // Simulation loop
    // ========================================================================

    private void startSimLoop() {
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

    private static final float TEST_AMP_THRESHOLD = 0.05f;
    private static final float TEST_STRONG_THRESHOLD = 0.15f;
    private static final float TEST_AXIS_RATIO = 2.5f;
    private static final float TEST_HEADING_RANGE = 50f;
    private static final float TEST_FAST_RMS = 0.3f;
    private static final float TEST_CORRECT_RATIO_FLOAT = 0.4f;
    private static final int TEST_MAX_STEP_MS = 60000;
    private static final int TEST_CORRECT_WINDOW_MS = 1500;

    private void startTestMode() {
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

    private void nextTestStep() {
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

    private int testCorrectCount;

    private void setTestInstruction(String title, String line1, String line2) {
        testInstruction = title + "\n" + line1 + "\n" + line2;
    }

    private void updateTestFeedback() {
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

    private void addToWindow(boolean correct) {
        testWindowBuffer[testWindowIdx] = correct;
        testWindowIdx = (testWindowIdx + 1) % TEST_WINDOW_FRAMES;
        if (testWindowFill < TEST_WINDOW_FRAMES) testWindowFill++;
    }

    private float getWindowCorrectRatio() {
        if (testWindowFill == 0) return 0f;
        int count = 0;
        for (int i = 0; i < testWindowFill; i++) {
            if (testWindowBuffer[i]) count++;
        }
        return (float) count / testWindowFill;
    }

    private void updateHeadingWindow(float heading) {
        headingWindow[headingWindowIdx] = heading;
        headingWindowIdx = (headingWindowIdx + 1) % HEADING_WINDOW_FRAMES;
        if (headingWindowFill < HEADING_WINDOW_FRAMES) headingWindowFill++;
    }

    private float getHeadingRange() {
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

    private void stopTestMode() {
        testMode = false;
        if (testTask != null) {
            testHandler.removeCallbacks(testTask);
        }
    }

    // ========================================================================
    // Flight Scenario Test
    // ========================================================================

    private Handler scenarioHandler = new Handler(Looper.getMainLooper());
    private Runnable scenarioTask;
    private long scenarioStartMs;

    private void startFlightScenario() {
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

    private void stopFlightScenario() {
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

    private Handler trackHandler = new Handler(Looper.getMainLooper());
    private Runnable trackTask;
    private long trackStartMs;
    private long trackPrevFrameMs;

    private void startTrackReplay(String filePath) {
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
        gpsTrail.clear(); // BUG-5 FIX: clear live GPS trail before replay

        trackReplayer = new TrackReplayer();
        trackReplayer.setSpeed(5.0f);
        // Определяем, есть ли ZIP с сенсорами
        boolean hasSensorZip = false;
        if (filePath != null) {
            trackReplayer.loadFile(filePath);
            String zipPath = filePath.replace(".igc", ".zip");
            hasSensorZip = new java.io.File(zipPath).exists();
        } else {
            trackReplayer.loadFromIGC(getResources().openRawResource(R.raw.track_replay));
        }
        trackReplayer.setHasSensorData(hasSensorZip);
        trackReplayer.setAirspeedMs(prefs.getFloat("airspeed_ms", 9.5f));
        trackReplayer.start();
        trackPrevFrameMs = 0;

        trackTask = new Runnable() {
            @Override
            public void run() {
                if (!trackMode || trackReplayer == null) return;
                if (trackReplayer.isFinished()) {
                    stopTrackReplay();
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                long realDeltaMs;
                if (trackPrevFrameMs == 0) {
                    realDeltaMs = 0;
                } else {
                    realDeltaMs = now - trackPrevFrameMs;
                    if (realDeltaMs > 100) realDeltaMs = 50; // cap for pauses
                }
                trackPrevFrameMs = now;
                trackReplayer.update(realDeltaMs);

                // Feed accel through thermal detector
                if (thermalDetector != null) {
                    thermalDetector.processSample(trackReplayer.getAccelX(), trackReplayer.getAccelY());
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
                                    while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                                }
                            }
                        }
                    }
                }

                // Add persistent thermal blip from replayer
                if (trackReplayer.isThermalActive()) {
                    long nowMs = System.currentTimeMillis();
                    synchronized (thermalLock) {
                        Iterator<ThermalBlip> it = thermals.iterator();
                        while (it.hasNext()) {
                            if (!it.next().isAlive(nowMs)) it.remove();
                        }
                        boolean hasBlip = false;
                        for (ThermalBlip tb : thermals) {
                            if (Math.abs(tb.angle - trackReplayer.getThermalBearing()) < 20f
                                    && Math.abs(tb.distance - trackReplayer.getThermalDistance()) < 30f) {
                                tb.distance = trackReplayer.getThermalDistance();
                                tb.angle = trackReplayer.getThermalBearing();
                                hasBlip = true;
                                break;
                            }
                        }
                        if (!hasBlip) {
                            float strength = trackReplayer.isShowRedCore() ? 8f : 4f;
                            ThermalBlip tb = new ThermalBlip(
                                    trackReplayer.getThermalBearing(),
                                    strength,
                                    trackReplayer.getThermalDistance(),
                                    "track",
                                    nowMs
                            );
                            // BUG-6 FIX: set adaptive lifeMs like ThermalDetector does
                            if (trackReplayer.isShowRedCore()) {
                                tb.lifeMs = 12000L;
                            } else if (strength > 3f) {
                                tb.lifeMs = 8000L;
                            } else {
                                tb.lifeMs = 3000L;
                            }
                            thermals.add(tb);
                            while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                        }
                    }
                }

                // NEW-03: не постить если на паузе
                if (!trackReplayer.isPaused()) {
                    trackHandler.postDelayed(this, 20);
                }
            }
        };
        trackHandler.postDelayed(trackTask, 20);

        // Статус и подгрузка карты под трек
        trackFileName = filePath != null ? new java.io.File(filePath).getName() : "встроенный";
        trackSpeedIdx = 0; // 1x по умолчанию (NEW-01)
        String trackName = trackFileName;
        currentStatus = "▶ Проигрываем: " + trackName;
        android.widget.Toast.makeText(MainActivity.this,
                "▶ " + trackName + " (5x)", android.widget.Toast.LENGTH_SHORT).show();

        // Форсированная загрузка карты в координатах трека
        if (filePath != null && trackReplayer.getLat() != 0.0 && trackReplayer.getLon() != 0.0) {
            staticMapLoader.forceUpdate(trackReplayer.getLat(), trackReplayer.getLon(), 14);
        }
    }

    private void stopTrackReplay() {
        trackMode = false;
        if (trackTask != null) {
            trackHandler.removeCallbacks(trackTask);
            trackTask = null;
        }
        if (trackReplayer != null) {
            trackReplayer.stop();
            trackReplayer = null;
        }
        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }
        android.widget.Toast.makeText(MainActivity.this,
                "Сим2 завершён", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showTestCompleteDialog() {
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

    private float getCompassHeading() {
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
    private void speakThermalDirection(float thermalAngle, float distanceMeters) {
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

    // ========================================================================
    // Back button
    // ========================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.exit_dialog_title))
                .setPositiveButton(getString(R.string.exit_dialog_yes),
                        (d, w) -> finishAffinity())
                .setNegativeButton(getString(R.string.exit_dialog_no), null)
                .show();
    }

    private void showExitOnScreenDialog() {
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

    class RadarView extends View implements View.OnClickListener {

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

        // Vario average buffer (30 samples at 1 Hz = 30 sec)
        private static final int VARIO_AVG_COUNT = 30;
        private final float[] varioBuf = new float[VARIO_AVG_COUNT];
        private int varioBufIdx = 0;
        private int varioBufLen = 0;

        public RadarView(Context context) {
            super(context);
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

                // Tell radarRenderer about reduced height so it centers correctly
                radarRenderer.onSizeChanged(w, (int)radarH);
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
            if (blindModeEnabled) {
                canvas.drawColor(Color.rgb(5, 5, 5));
                blindTextPaint.setColor(Color.argb(180, 0, 255, 0));
                blindTextPaint.setTextSize(48);
                blindTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
                blindTextPaint.setTextAlign(Paint.Align.CENTER);
                blindTextPaint.setFakeBoldText(true);
                String status = flightStateMachine.isFlying() ? "Слепой полёт" : "Ожидание...";
                canvas.drawText(status, w/2f, h/3f, blindTextPaint);

                // В слепом режиме показываем ключевые данные: варио, высоту, время
                blindDataPaint.setTextSize(36);
                blindDataPaint.setFakeBoldText(false);
                blindDataPaint.setColor(Color.argb(160, 100, 200, 255));
                float varioVal2 = sensorController.getVario();
                String varioStr = String.format(java.util.Locale.US, "%s%.1f м/с", varioVal2 >= 0 ? "+" : "", varioVal2);
                canvas.drawText(varioStr, w/2f, h/2f, blindDataPaint);

                blindDataPaint.setColor(Color.argb(120, 255, 255, 255));
                blindDataPaint.setTextSize(28);
                float alt2 = gpsManager.getAltitude();
                float startAlt2 = gpsManager.getStartAltitude();
                float agl2 = gpsManager.isAltitudeInitialized() ? (alt2 - startAlt2) : 0f;
                canvas.drawText(String.format(java.util.Locale.US, "%.0f м MSL  +%.0f м AGL", alt2, agl2), w/2f, h/2f + 60, blindDataPaint);

                // Время полёта
                if (logManager.isLogging()) {
                    long flightSec2 = (SystemClock.elapsedRealtime() - logManager.getFlightStartMs()) / 1000;
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
            if (varioSoundManager != null) {
                varioSoundManager.update(sensorController.getVario());
            }
            refreshWakeLock();

            // Update foreground service notification
            ThermalRadarService svc = ThermalRadarService.getInstance();
            if (svc != null) {
                svc.updateNotification(logManager.isLogging(), sensorController.getVario());
            }

            // Push GPS cache to LogManager (1 Гц данные, пишутся в каждом сэмпле)
            logManager.updateGpsCache(
                gpsManager.getLat(), gpsManager.getLon(),
                gpsManager.getAltitude(), gpsManager.getSpeed(), gpsManager.getHeading(),
                gpsManager.getAccuracy(), gpsManager.getFixAgeMs());

            // Static map: обновить если сместились >500м
            double mapLat, mapLon;
            boolean mapHasPosition = false;
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                mapLat = trackReplayer.getLat();
                mapLon = trackReplayer.getLon();
                mapHasPosition = (mapLat != 0.0 && mapLon != 0.0);
            } else {
                mapLat = gpsManager.getLat();
                mapLon = gpsManager.getLon();
                mapHasPosition = gpsManager.isReady() && mapLat != 0.0 && mapLon != 0.0;
            }
            if (mapHasPosition) {
                staticMapLoader.updateIfNeeded(mapLat, mapLon, 14);
                if (radarRenderer.isMapRefreshNeeded()) {
                    staticMapLoader.forceUpdate(mapLat, mapLon, 14);
                }
            }

            // IGC logger: 1 Гц GPS + сэмпл
            igcLogger.updateGps(
                gpsManager.getLat(), gpsManager.getLon(),
                gpsManager.getAltitude(),
                sensorController.getAltitudeRaw(),
                gpsManager.getSpeed(), gpsManager.getHeading(),
                gpsManager.getAccuracy(), gpsManager.getFixAgeMs());
            igcLogger.recordSample();

            // Track circling/label/wind state transitions for event log
            boolean nowCircling = circlingManager.isCircling();
            boolean nowLabel = circlingManager.isShowThermalLabel();
            if (nowCircling && !prevCirclingState) {
                logManager.recordEvent("CIRCLING_START", "circling confirmed");
                igcLogger.recordEvent("C", "circling_start");
                double lat = gpsManager.getLat();
                double lon = gpsManager.getLon();
                if (lat != 0.0 && lon != 0.0 && entryMarkers.size() < MAX_MARKERS) {
                    entryMarkers.add(new double[]{lat, lon});
                }
            } else if (!nowCircling && prevCirclingState) {
                logManager.recordEvent("CIRCLING_END", "circling stopped");
                igcLogger.recordEvent("C", "circling_stop");
                double lat = gpsManager.getLat();
                double lon = gpsManager.getLon();
                if (lat != 0.0 && lon != 0.0 && exitMarkers.size() < MAX_MARKERS) {
                    exitMarkers.add(new double[]{lat, lon});
                }
            }
            prevCirclingState = nowCircling;

            if (nowLabel && !prevLabelState) {
                logManager.recordEvent("THERMAL_LABEL_ON", "540 deg reached");
                igcLogger.recordEvent("T", "thermal_label_on");
            } else if (!nowLabel && prevLabelState) {
                logManager.recordEvent("THERMAL_LABEL_OFF", "label hidden");
                igcLogger.recordEvent("T", "thermal_label_off");
            }
            prevLabelState = nowLabel;

            float wf = circlingManager.getWindFromDeg();
            float ws = circlingManager.getDisplayWindSpeed();
            if (wf >= 0 && ws > 0) {
                if (Math.abs(wf - prevWindFrom) > 10f || Math.abs(ws - prevWindSpd) > 0.5f) {
                    logManager.recordEvent("WIND_UPDATE",
                            String.format(java.util.Locale.US, "%.0fdeg %.1fm/s", wf, ws));
                    igcLogger.recordEvent("W",
                            String.format(java.util.Locale.US, "%.0f %.1f", wf, ws));
                    prevWindFrom = wf;
                    prevWindSpd = ws;
                }
            }

            // MaxSnr periodic reset (каждые 5 мин)
            long nowMs2 = System.currentTimeMillis();
            if (nowMs2 - lastMaxSnrResetMs > 300_000) {
                sensorController.resetMaxSnr();
                lastMaxSnrResetMs = nowMs2;
            }

            // Сохранить калибровку наклона после завершения (однократно)
            if (sensorController.getMountTiltDeg() > 0.1f
                    && prefs.getFloat("mount_tilt_deg", -1f) != sensorController.getMountTiltDeg()) {
                sensorController.saveMountTilt(prefs);
                android.util.Log.i("TERMO1", "Tilt calibration saved: "
                        + sensorController.getMountTiltDeg() + "°");
            }

            // Яркость: макс в полёте при записи лога или в солнечном режиме
            boolean sunMode = prefs.getBoolean("sunlight_mode", false);
            if ((flightStateMachine.isFlying() && logManager.isLogging()) || sunMode) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (lp.screenBrightness < 1.0f) {
                    lp.screenBrightness = 1.0f;
                    getWindow().setAttributes(lp);
                }
            } else if (blindModeEnabled) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (lp.screenBrightness > 0.16f || lp.screenBrightness < 0.14f) {
                    lp.screenBrightness = 0.15f;
                    getWindow().setAttributes(lp);
                }
            }

            // Update status
            updateStatus();

            // Вибрация при смене статуса на термик/набор
            if (!currentStatus.equals(previousStatus)) {
                previousStatus = currentStatus;
                if ((currentStatus.equals(UiManager.STATUS_THERMAL)
                        || currentStatus.equals(UiManager.STATUS_CLIMB))
                        && prefs.getBoolean("vibrate_enabled", true)) {
                    android.os.Vibrator vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
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
            if (thermalDetector != null && !simMode && !scenarioMode && !trackMode) {
                ThermalBlip detBlip = thermalDetector.getCurrentBlip();
                if (detBlip != null) {
                    long now = System.currentTimeMillis();
                    if (now - detBlip.bornMs < detBlip.lifeMs) {
                        synchronized (thermalLock) {
                            Iterator<ThermalBlip> it = thermals.iterator();
                            while (it.hasNext()) {
                                ThermalBlip expired = it.next();
                                if (!expired.isAlive(now)) {
                                    it.remove();
                                    logManager.recordEvent("THERMAL_REMOVED",
                                            "bornMs=" + expired.bornMs
                                            + " angle=" + (int)expired.angle
                                            + " dist=" + (int)expired.distance
                                            + " strength=" + String.format(java.util.Locale.US, "%.1f", expired.strength));
                                }
                            }
                            boolean found = false;
                            for (ThermalBlip tb : thermals) {
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
                                thermals.add(detBlip);
                                logManager.recordEvent("THERMAL_NEW",
                                        "bornMs=" + detBlip.bornMs
                                        + " angle=" + (int)detBlip.angle
                                        + " dist=" + (int)detBlip.distance
                                        + " strength=" + String.format(java.util.Locale.US, "%.1f", detBlip.strength));
                                if (voicePromptsEnabled && ttsReady) {
                                    speakThermalDirection(detBlip.angle, detBlip.distance);
                                }
                                while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                            }
                        }
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastThermalBeepMs > 3000) {
                            if (varioSoundManager != null && SystemClock.elapsedRealtime() - lastThermalBeepRealMs >= THERMAL_BEEP_INTERVAL_MS) {
                                lastThermalBeepRealMs = SystemClock.elapsedRealtime();
                                varioSoundManager.playThermalBeep();
                            }
                            lastThermalBeepMs = nowMs;
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
            canvas.drawText(currentStatus, w / 2f, topStatusH, instrLabelPaint);
            instrLabelPaint.setTextSize(32);
            instrLabelPaint.setTextAlign(Paint.Align.CENTER);

            float instrMidY = localInstrH / 2f;
            float colX_left = w * 0.12f;
            float colX_center = w / 2f;
            float colX_right = w * 0.88f;

            // Speed: из реплеера или GPS
            float gpsSpeed;
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                gpsSpeed = trackReplayer.getSpeed();
            } else {
                gpsSpeed = gpsManager.getSpeed(); // м/с
            }
            float gpsAlt, startAlt, gpsAgl;
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                gpsAlt = trackReplayer.getAltitude();
                startAlt = 0; // при реплее AGL не считаем
                gpsAgl = gpsAlt;
            } else {
                gpsAlt = gpsManager.getAltitude();
                startAlt = gpsManager.getStartAltitude();
                gpsAgl = gpsManager.isAltitudeInitialized() ? (gpsAlt - startAlt) : 0f;
            }

            instrValuePaint.setTextAlign(Paint.Align.CENTER);
            instrValuePaint.setTextSize(112); // 4x
            instrLabelPaint.setTextAlign(Paint.Align.CENTER);
            instrLabelPaint.setTextSize(32); // labels чуть крупнее

            // Top row (values): all values aligned horizontally
            float valueRowY = instrMidY + 10; // подняли на строку выше

            // Below values — labels for speed/wind (ABOVE their values on next row)
            float instrLabelY = valueRowY + 20;

            // Speed label (км/ч — крупно, м/с — мелко рядом)
            instrLabelPaint.setColor(Color.argb(160, 0, 255, 0));
            canvas.drawText("скорость, км/ч", colX_left, valueRowY - 70, instrLabelPaint);
            // Определяем: летим хвостом вперёд? (компас vs GPS трек)
            float compHdg = getCompassHeading();
            float gpsTrk = gpsManager.getHeading();
            float hdgDiff = Math.abs(compHdg - gpsTrk);
            if (hdgDiff > 180f) hdgDiff = 360f - hdgDiff;
            boolean goingBack = hdgDiff > 120f;
            float displaySpeedKmh = gpsSpeed * 3.6f;
            if (goingBack) displaySpeedKmh = -displaySpeedKmh;
            // Speed value: красное мигание при сносе назад
            if (goingBack) {
                boolean blink = (SystemClock.elapsedRealtime() / 500) % 2 == 0;
                instrValuePaint.setColor(blink ? Color.argb(220, 255, 80, 80) : Color.argb(50, 255, 80, 80));
            } else {
                instrValuePaint.setColor(Color.argb(220, 0, 255, 0));
            }
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", displaySpeedKmh),
                    colX_left, valueRowY + 20, instrValuePaint);
            // m/s мелко под км/ч
            instrLabelPaint.setTextSize(20);
            instrLabelPaint.setColor(Color.argb(120, 0, 255, 0));
            canvas.drawText(String.format(java.util.Locale.US, "%.1f м/с", gpsSpeed), colX_left, valueRowY + 20 + 28, instrLabelPaint);
            instrLabelPaint.setTextSize(32);

            // MSL value and label (below speed)
            float gpsAltVal = gpsManager.getAltitude();
            float startAltVal = gpsManager.getStartAltitude();
            float aglVal = gpsManager.isAltitudeInitialized() ? (gpsAltVal - startAltVal) : 0f;
            instrValuePaint.setColor(Color.argb(200, 0, 200, 255));
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", gpsAltVal), colX_left, instrLabelY + 130, instrValuePaint);
            // MSL label ONE LINE BELOW value
            instrLabelPaint.setColor(Color.argb(140, 0, 200, 255));
            canvas.drawText("высота MSL", colX_left, instrLabelY + 162, instrLabelPaint);

            // Center: Vario (×1.5 larger)
            float varioVal = sensorController.getVario();
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                varioVal = flightSim.getVario();
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                varioVal = trackReplayer.getVario();
            }
            String varioSign = varioVal >= 0 ? "+" : "";
            varioPaint.setColor(varioVal > 0.5f ? Color.argb(255, 255, 80, 80)
                    : varioVal < -0.5f ? Color.argb(255, 100, 200, 100)
                    : Color.argb(200, 255, 180, 50));
            canvas.drawText(String.format(java.util.Locale.US, "%s%.1f", varioSign, varioVal), colX_center, valueRowY, varioPaint);

            // Avg vario за 30с (мелко под варио)
            pushVarioSample(varioVal);
            float avgVario30 = getAvgVario30();
            instrLabelPaint.setColor(Color.argb(140, 255, 180, 50));
            instrLabelPaint.setTextSize(28);
            instrLabelPaint.setTextAlign(Paint.Align.CENTER);
            String avgStr = String.format(java.util.Locale.US, "avg %s%.1f", avgVario30 >= 0 ? "+" : "", avgVario30);
            canvas.drawText(avgStr, colX_center, valueRowY + 40, instrLabelPaint);

            // Flight time below vario, one full line down (чч:мм, до 12 часов)
            long flightTimeMs;
            if (simMode) {
                flightTimeMs = SystemClock.elapsedRealtime() - simStartMs;
            } else if (scenarioMode) {
                flightTimeMs = SystemClock.elapsedRealtime() - scenarioStartMs;
            } else if (trackMode && trackReplayer != null) {
                // BUG-3 FIX: use track time, not wall clock
                flightTimeMs = (long)(trackReplayer.getCurrentTime() * 1000);
            } else if (logManager.isLogging()) {
                flightTimeMs = System.currentTimeMillis() - logManager.getFlightStartMs();
            } else {
                flightTimeMs = 0;
            }
            long ftSec = flightTimeMs / 1000;
            String ftStr = String.format("%02d:%02d", ftSec / 3600, (ftSec % 3600) / 60);
            flightTimePaint.setColor(Color.argb(200, 0, 255, 255));
            canvas.drawText(ftStr, colX_center, valueRowY + 150, flightTimePaint);

            // Right column: Wind label ABOVE value (поднято на 1 строку), AGL below
            float windDeg, windSpdMs;
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                windDeg = trackReplayer.getWindFromDeg();
                windSpdMs = trackReplayer.getWindSpeedMs();
            } else {
                windDeg = circlingManager.getWindFromDeg();
                windSpdMs = circlingManager.getDisplayWindSpeed();
            }
            if (windDeg >= 0 && windSpdMs > 0) {
                instrLabelPaint.setColor(Color.argb(160, 100, 200, 255));
                canvas.drawText("ветер, м/с", colX_right, valueRowY - 70, instrLabelPaint);
                instrValuePaint.setColor(windSpdMs > 12f ? Color.argb(220, 255, 80, 80) : Color.argb(220, 100, 200, 255));
                canvas.drawText(String.format(java.util.Locale.US, "%.1f", windSpdMs), colX_right, valueRowY + 20, instrValuePaint);
            } else {
                instrLabelPaint.setColor(Color.argb(120, 100, 200, 255));
                canvas.drawText("ветер, м/с", colX_right, valueRowY - 70, instrLabelPaint);
                instrValuePaint.setColor(Color.argb(120, 100, 200, 255));
                canvas.drawText("--", colX_right, valueRowY + 20, instrValuePaint);
            }

            // AGL value and label (below wind)
            instrValuePaint.setColor(Color.argb(200, 0, 200, 255));
            canvas.drawText(String.format(java.util.Locale.US, "+%.0f", Math.max(0, aglVal)), colX_right, instrLabelY + 130, instrValuePaint);
            // AGL label ONE LINE BELOW value
            instrLabelPaint.setColor(Color.argb(140, 0, 200, 255));
            canvas.drawText("AGL", colX_right, instrLabelY + 162, instrLabelPaint);

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
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                gpsOk = true;
                pilotLat = flightSim.getLat();
                pilotLon = flightSim.getLon();
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                gpsOk = true;
                pilotLat = trackReplayer.getLat();
                pilotLon = trackReplayer.getLon();
            } else {
                gpsOk = gpsManager.isReady() && gpsManager.getFixAgeMs() < 5000;
                pilotLat = gpsManager.getLat();
                pilotLon = gpsManager.getLon();
            }

            if (gpsOk) {
                long now = System.currentTimeMillis();

                float currentVario;
                if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                    currentVario = trackReplayer.getVario();
                } else {
                    currentVario = sensorController.getVario();
                }
                if (gpsTrail.isEmpty()) {
                    gpsTrail.add(new double[]{pilotLat, pilotLon, now, currentVario});
                } else {
                    double[] last = gpsTrail.get(gpsTrail.size() - 1);
                    long lastAge = now - (long) last[2];
                    if (lastAge >= GPS_TRAIL_ADD_INTERVAL_MS) {
                        // Фильтр GPS-спиков: параплан не может двигаться >30 м/с (15 возд + 15 ветер)
                        float[] spikeCheck = new float[2];
                        android.location.Location.distanceBetween(
                                last[0], last[1], pilotLat, pilotLon, spikeCheck);
                        float speedMs = spikeCheck[0] / (lastAge / 1000f);
                        if (speedMs > 30f) {
                            // Спик — используем предыдущую точку вместо прыжка
                            pilotLat = last[0];
                            pilotLon = last[1];
                        }
                        gpsTrail.add(new double[]{pilotLat, pilotLon, now, currentVario});
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
                        float[] spikeCheck = new float[2];
                        android.location.Location.distanceBetween(
                                glideLatBuf[lastGi], glideLonBuf[lastGi],
                                pilotLat, pilotLon, spikeCheck);
                        float dtSec = (now - glideTimeBuf[lastGi]) / 1000f;
                        float speedMs = (dtSec > 0) ? spikeCheck[0] / dtSec : 0;
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

                Iterator<double[]> it = gpsTrail.iterator();
                while (it.hasNext()) {
                    double[] pt = it.next();
                    long age = now - (long) pt[2];
                    if (age > GPS_TRAIL_MAX_AGE_MS) { it.remove(); }
                }
                while (gpsTrail.size() > GPS_TRAIL_MAX) gpsTrail.remove(0);

                for (double[] pt : gpsTrail) {
                    long age = now - (long) pt[2];
                    float brightness = 1.0f - (float) age / (float) GPS_TRAIL_MAX_AGE_MS;
                    if (brightness < 0.01f) continue;

                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], distanceResult);
                    float dist = distanceResult[0];
                    float bearingRad = (float) Math.toRadians(distanceResult[1]);

                    float distPx = (dist / 150f) * trailR;
                    if (distPx <= trailR) {
                        trailPxBuf[trailCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;
                        trailPyBuf[trailCount] = trailCy - (float) Math.cos(bearingRad) * distPx;
                        float varioVal2 = (pt.length >= 4) ? (float) pt[3] : 0f;
                        trailColorBuf[trailCount] = varioToColor(varioVal2, brightness);
                        trailCount++;
                    }

                    float mapDistPx = (dist / 1500f) * trailR;
                    float mapHalf = (3671f / 1500f) * trailR / 2f;
                    if (mapDistPx <= mapHalf) {
                        mapTrailPxBuf[mapTrailCount] = (w / 2f) + (float) Math.sin(bearingRad) * mapDistPx;
                        mapTrailPyBuf[mapTrailCount] = trailCy - (float) Math.cos(bearingRad) * mapDistPx;
                        mapTrailCount++;
                    }
                }

                int markerCount = 0;
                for (double[] pt : entryMarkers) {
                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], distanceResult);
                    float dist = distanceResult[0];
                    float bearingRad = (float) Math.toRadians(distanceResult[1]);
                    float distPx = (dist / 150f) * trailR;
                    if (distPx > trailR) continue;
                    markerPxBuf[markerCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;
                    markerPyBuf[markerCount] = trailCy - (float) Math.cos(bearingRad) * distPx;
                    markerIsEntry[markerCount] = true;
                    markerCount++;
                }

                for (double[] pt : exitMarkers) {
                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], distanceResult);
                    float dist = distanceResult[0];
                    float bearingRad = (float) Math.toRadians(distanceResult[1]);
                    float distPx = (dist / 150f) * trailR;
                    if (distPx > trailR) continue;
                    markerPxBuf[markerCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;
                    markerPyBuf[markerCount] = trailCy - (float) Math.cos(bearingRad) * distPx;
                    markerIsEntry[markerCount] = false;
                    markerCount++;
                }

                radarRenderer.setTrailMarkers(markerPxBuf, markerPyBuf, markerIsEntry, markerCount);

                float[] liftValues = liftDatabase.getLiftValues();
                radarRenderer.setSectorLiftData(liftValues);
            } else {
                gpsTrail.clear();
            }

            // Draw radar
            long nowMs = System.currentTimeMillis();
            synchronized (thermalLock) {
                thermalsCopy.clear();
                for (ThermalBlip t : thermals) {
                    thermalsCopy.add(new ThermalBlip(t));
                }
            }
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                radarRenderer.setThermalCore(
                    flightSim.isShowRedCore(),
                    flightSim.getThermalBearing(),
                    flightSim.getThermalDistance(),
                    flightSim.getThermalRadius());
                radarRenderer.setWindData(
                    flightSim.getWindFromDeg(),
                    flightSim.getWindSpeedMs());
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                radarRenderer.setThermalCore(
                    trackReplayer.isShowRedCore(),
                    trackReplayer.getThermalBearing(),
                    trackReplayer.getThermalDistance(),
                    trackReplayer.getThermalRadius());
                radarRenderer.setWindData(
                    trackReplayer.getWindFromDeg(),
                    trackReplayer.getWindSpeedMs());
            } else {
                radarRenderer.setThermalCore(false, 0, 0, 0);
                radarRenderer.setWindData(
                    circlingManager.getWindFromDeg(),
                    circlingManager.getDisplayWindSpeed());
            }

            int bestSector = liftDatabase.getBestSectorIndex();
            if (bestSector >= 0) {
                radarRenderer.setBestLiftSector(
                        bestSector,
                        liftDatabase.getBestSectorLift(),
                        liftDatabase.getBestSectorDirection());
            } else {
                radarRenderer.setBestLiftSector(-1, 0, "");
            }

            boolean showStats = circlingManager.isCircling() || circlingManager.isShowThermalLabel();
            String coreText = "";
            if (thermalLocator.isEstimateValid()) {
                coreText = String.format(java.util.Locale.US,
                        "Центр: %.0f° %.0fм",
                        thermalLocator.getThermalBearing(),
                        thermalLocator.getThermalDistance());
            }
            if (lastDrift != null && Math.abs(lastDrift.driftAngle) > 2f) {
                if (coreText.length() > 0) coreText += " | ";
                coreText += lastDrift.guidanceText;
            }
            float thermalBaseMsl = 0;
            if (lastThermalBaseResult != null && lastThermalBaseResult.valid) {
                thermalBaseMsl = (float) lastThermalBaseResult.groundAltitude;
            }
            radarRenderer.setThermalStats(
                    circlingManager.isCircling() ? sensorController.getVario() : 0,
                    gpsManager.getAltitude(),
                    thermalBaseMsl,
                    coreText,
                    showStats);

            float headingDisplay = getCompassHeading();
            long headingFrameMs = SystemClock.elapsedRealtime();
            if (!headingDisplayInitialized) {
                headingDisplaySmoothed = headingDisplay;
                headingDisplayInitialized = true;
                lastHeadingFrameMs = headingFrameMs;
            } else {
                double dtSec = (headingFrameMs - lastHeadingFrameMs) / 1000.0;
                lastHeadingFrameMs = headingFrameMs;
                double smoothingFactor = 1.0 - Math.pow(0.1, dtSec / 0.1);
                if (smoothingFactor > 1.0) smoothingFactor = 1.0;
                if (smoothingFactor < 0.01) smoothingFactor = 0.01;
                float diff = ((headingDisplay - headingDisplaySmoothed + 540f) % 360f) - 180f;
                headingDisplaySmoothed = (float)((headingDisplaySmoothed + diff * smoothingFactor + 360f) % 360f);
            }
            float headingDisplayFinal = headingDisplaySmoothed;
            float varioDisplay = sensorController.getVario();
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                headingDisplay = flightSim.getHeading();
                varioDisplay = flightSim.getVario();
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                headingDisplay = trackReplayer.getHeading();
                varioDisplay = trackReplayer.getVario();
                headingDisplayFinal = 0f;
            }

            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                radarRenderer.setPilotPosition(trackReplayer.getLat(), trackReplayer.getLon());
            } else {
                radarRenderer.setPilotPosition(gpsManager.getLat(), gpsManager.getLon());
            }

            radarRenderer.draw(canvas, nowMs, thermalsCopy,
                    headingDisplayFinal, varioDisplay, currentStatus,
                    sensorController.getMaxSnr(), thermalsCopy.size(),
                    trailPxBuf, trailPyBuf, trailColorBuf, trailCount,
                    mapTrailPxBuf, mapTrailPyBuf, mapTrailCount);

            // HUD on radar
            float radarCxLocal = w / 2f;
            uiManager.drawVario(canvas, radarCxLocal, 130, varioDisplay);
            uiManager.drawStatus(canvas, radarCxLocal, currentStatus);

            // "крутим термик" — по центру радара
            if (circlingManager.isShowThermalLabel()) {
                float labelY = trailCy; // center of radar section
                canvas.drawText("крутим термик", radarCxLocal, labelY, thermalLabelPaint);
            }

            // Guidance text from flight scenario (on radar)
            boolean showGuide = (scenarioMode && flightSim != null && flightSim.isRunning())
                    || (trackMode && trackReplayer != null && trackReplayer.isRunning());
            if (showGuide) {
                String guide = scenarioMode ? flightSim.getGuidanceText() : trackReplayer.getGuidanceText();
                if (guide != null && guide.length() > 0) {
                    float guideY = trailCy + (scenarioMode && flightSim != null && flightSim.isCircling() ? 0 : -80);
                    thermalLabelPaint.setColor(Color.argb(200, 255, 235, 59));
                    thermalLabelPaint.setTextSize(36);
                    canvas.drawText(guide, radarCxLocal, guideY, thermalLabelPaint);
                    thermalLabelPaint.setColor(Color.argb(220, 255, 193, 7));
                    thermalLabelPaint.setTextSize(42);
                }
            }

            if (testMode) updateTestFeedback();

            // Info panel (moved to bottom panel under ЗАПИСЬ button)
            // (actual drawing is now in the bottom panel section)

            canvas.restore();

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
                float pilotTrack = getCompassHeading();

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
                        currentStatus = String.format(java.util.Locale.US, "СНОС НАЗАД — %.1f м/с", netDist / 8f);
                        previousStatus = "";
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
            if (!glideValid && gpsManager.isReady()) {
                float speedMs = gpsManager.getSpeed();
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
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                aglForRange = trackReplayer.getAltitude();
            } else {
                aglForRange = gpsManager.isAltitudeInitialized()
                        ? (gpsManager.getAltitude() - gpsManager.getStartAltitude()) : 0f;
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
            if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                coordLat = trackReplayer.getLat();
                coordLon = trackReplayer.getLon();
            } else {
                coordLat = gpsManager.getLat();
                coordLon = gpsManager.getLon();
            }
            if (coordLat != 0.0 && coordLon != 0.0) {
                // Satellites count above coordinates
                int satCount = gpsManager.getSatelliteCount();
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
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
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
                float mountTilt = sensorController.getMountTiltDeg();
                float currTilt = sensorController.getCurrentTiltDeg();
                String gpsLabel = (gpsManager.isReady() && gpsManager.getFixAgeMs() < 5000)
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
                if (logManager.isLogging()) {
                    logLabelPaint.setTextSize(20);
                    float labelY = dataY - 28;
                    logLabelPaint.setColor(Color.argb(220, 33, 150, 243));
                    canvas.drawText("пишем лог", dataX, labelY, logLabelPaint);
                }

                // Track player panel (if replay)
                if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                    drawPlaybackPanel(canvas, dataX, tiltY + 40);
                }
            }

            // Стоп (справа)
            canvas.drawRoundRect(startBtnRect, 10, 10, btnBgPaint);
            if (logManager.isLogging()) {
                btnTextPaint.setColor(Color.argb(255, 255, 80, 80));
            } else {
                btnTextPaint.setColor(Color.argb(60, 255, 80, 80));
            }
            btnTextPaint.setTextSize(24);
            canvas.drawText("СТОП", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);

            // Stop test button
            if (!scenarioMode && !trackMode) {
                testBtnBgPaint.setColor(Color.TRANSPARENT);
            } else {
                testBtnBgPaint.setColor(Color.argb(50, 255, 80, 80));
            }
            canvas.drawRoundRect(testBtnRect, 12, 12, testBtnBgPaint);
            if (scenarioMode || trackMode) {
                testBtnTextPaint.setTextSize(24);
                testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
                testBtnTextPaint.setColor(Color.argb(220, 255, 80, 80));
                canvas.drawText("СТОП ТЕСТ", testBtnRect.centerX(), testBtnRect.centerY() + 9, testBtnTextPaint);
            }

            // ========================================================================
            // OVERLAYS (drawn on top of everything)
            // ========================================================================

            // Night filter
            uiManager.drawNightFilter(canvas, w, h);

            // SIM badge
            if (simMode || scenarioMode || trackMode) {
                float badgeX = 12f;
                float badgeY = 190f;
                String badgeText = scenarioMode ? "TEST" : (trackMode ? "TPEK" : "SIM");
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
            if (testMode) {
                String instr = getTestInstruction();
                String fb = getTestFeedback();
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
                        testTextPaint.setColor(getTestFeedbackColor());
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
                    float progress = testStep / 6.0f;
                    if (testStep > 6) progress = 1f;
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
            String name = trackFileName;
            if (name.length() > 24) name = name.substring(0, 21) + "...";
            canvas.drawText("Трек: " + name, x, y, pbTrackPaint);

            // Строка 2: seekbar
            float barY = y + 16;
            float barH = 6;
            float barLeft = x, barRight = w - 16;
            float barMidY = barY + barH / 2f;

            canvas.drawRect(barLeft, barY, barRight, barY + barH, pbBarPaint);
            float progress = trackReplayer != null ? trackReplayer.getProgress() : 0;
            float thumbX = barLeft + (barRight - barLeft) * progress;
            canvas.drawRect(barLeft, barY, thumbX, barY + barH, pbThumbPaint);
            canvas.drawCircle(thumbX, barMidY, 10, pbThumbPaint);

            // Время
            String curTime = formatTime(trackReplayer != null ? (long)trackReplayer.getCurrentTime() : 0);
            String totalTime = formatTime(trackReplayer != null ? (long)trackReplayer.getTotalTime() : 0);
            pbTextPaint.setTextSize(18);
            canvas.drawText(curTime, barLeft, barY + barH + 18, pbTextPaint);
            canvas.drawText(totalTime, barRight, barY + barH + 18, pbTextPaint);

            pbSeekbarRect.set(barLeft - 16, barY - 16, barRight + 16, barY + barH + 32);

            // Строка 3: кнопки
            float btnY = barY + barH + 28;
            float btnW = 80, btnH = 44;
            pbBtnPaint.setStrokeWidth(5);

            pbPlayBtn.set(x, btnY, x + btnW, btnY + btnH);
            canvas.drawRoundRect(pbPlayBtn, 8, 8, pbBtnPaint);
            boolean isPaused = trackReplayer != null && trackReplayer.isPaused();
            pbTextPaint.setTextSize(30);
            canvas.drawText(isPaused ? "▶" : "❚❚", pbPlayBtn.centerX(), pbPlayBtn.centerY() + 10, pbTextPaint);

            float speedBtnLeft = pbPlayBtn.right + 12;
            float speedBtnW = 80;
            pbSpeedBtn.set(speedBtnLeft, btnY, speedBtnLeft + speedBtnW, btnY + btnH);
            canvas.drawRoundRect(pbSpeedBtn, 8, 8, pbBtnPaint);
            float speed = (trackSpeedIdx >= 0 && trackSpeedIdx < PLAYBACK_SPEEDS.length)
                    ? PLAYBACK_SPEEDS[trackSpeedIdx] : 1f;
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
            if (trackReplayer == null) return;
            float barLeft = pbSeekbarRect.left + 10;
            float barRight = pbSeekbarRect.right - 10;
            float frac = (x - barLeft) / (barRight - barLeft);
            frac = Math.max(0, Math.min(1, frac));
            float total = trackReplayer.getTotalTime();
            trackReplayer.seekTo(frac * total);
        }

        private void drawRecIndicator(Canvas canvas) {
            boolean isLogging = (logManager != null && logManager.isLogging())
                    || (igcLogger != null && igcLogger.isLogging());
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
                if (trackMode && trackReplayer != null && trackReplayer.isRunning()
                        && pbSeekbarRect.contains(touchX, touchY)) {
                    pbDragging = true;
                    trackReplayer.setPaused(true);
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
                if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                    if (pbPlayBtn.contains(touchX, touchY)) {
                        trackReplayer.setPaused(!trackReplayer.isPaused());
                        return true;
                    }
                    if (pbSpeedBtn.contains(touchX, touchY)) {
                        trackSpeedIdx = (trackSpeedIdx + 1) % PLAYBACK_SPEEDS.length;
                        trackReplayer.setSpeed(PLAYBACK_SPEEDS[trackSpeedIdx]);
                        return true;
                    }
                }

                if (exitRect.contains(touchX, touchY)) {
                    showExitOnScreenDialog();
                    return true;
                }
                if (gearRect.contains(touchX, touchY)) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
                // СТАРТ: тап = старт лога, долгое нажатие = сброс калибровки
                if (calibBtnRect.contains(touchX, touchY)) {
                    if (touchDuration > 600) {
                        resetCalibration();
                        android.widget.Toast.makeText(MainActivity.this,
                                "Калибровка сброшена (долгое нажатие)",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        startManualLogging();
                    }
                    return true;
                }
                if (startBtnRect.contains(touchX, touchY)) {
                    if (logManager.isLogging() || igcLogger.isLogging()) confirmStopLogging();
                    return true;
                }
                if (testBtnRect.contains(touchX, touchY)) {
                    if (scenarioMode) {
                        stopFlightScenario();
                    } else if (trackMode) {
                        stopTrackReplay();
                    } else if (!scenarioMode) {
                        startFlightScenario();
                    }
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        public void onClick(View v) {}
    }
}
