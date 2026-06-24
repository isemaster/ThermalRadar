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

    // GPS trail storage: [lat, lon, timeMs]
    private final List<double[]> gpsTrail = new ArrayList<>();

    // Phase 6: точки входа/выхода из термиков
    private static final int MAX_MARKERS = 100;
    private final List<double[]> entryMarkers = new ArrayList<>();  // [lat, lon]
    private final List<double[]> exitMarkers = new ArrayList<>();   // [lat, lon]

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
    private Handler bgHandler = new Handler(Looper.getMainLooper());
    private Runnable bgTask;
    private static final long BG_INTERVAL_MS = 100L;

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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION},
                        1001);
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
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("simulate", false)) {
            simMode = true;
            simulation = new SimulationManager();
            simulation.start();
            simStartMs = SystemClock.elapsedRealtime();
            lastThermalBeepMs = simStartMs;
            startSimLoop();
        }

        // Test mode
        if (intent != null && intent.getBooleanExtra("test_mode", false)) {
            startTestMode();
        }

        // Flight scenario from settings
        if (intent != null && intent.getBooleanExtra("flight_test", false)) {
            radarView.post(() -> startFlightScenario());
        }

        // Track replay from settings (сим2)
        if (intent != null && intent.getBooleanExtra("track_replay", false)) {
            radarView.post(() -> startTrackReplay());
        }
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
        boolean vibrateEnabled = prefs.getBoolean("vibrate_enabled", true);
        // сохраняем для использования в коде вибрации

        // GPS: всегда включён для логирования координат в лог
        gpsManager.startGps();
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
        // При активной записи лога — не убиваем сенсоры/GPS/ваклок
        // чтобы прибор продолжал работать при выключенном экране
        if (!logManager.isLogging()) {
            sensorController.unregisterSensors();
            releaseWakeLock();
            gpsManager.stopGps();
            if (varioSoundManager != null) varioSoundManager.stop();
        }
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
            logManager.startLogging();
            igcLogger.startLogging();
            // Автокалибровка при старте полёта (сенсоры уже работают)
            resetCalibration();
            sensorController.calibrateHeading(); // компас — стабилен 2 сек прямого полёта
            android.util.Log.i("TERMO1", "Auto calibration on flight start");
            android.widget.Toast.makeText(MainActivity.this,
                    "Полёт обнаружен, запись лога", android.widget.Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onFlightFinished() {
            // Логи НЕ останавливаем — остановка только по кнопке с подтверждением
            android.widget.Toast.makeText(MainActivity.this,
                    "Полёт завершён, лог продолжается", android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    // ========================================================================
    // Process sample (vario sound, flight state)
    // ========================================================================

    private void processSample() {
        if (varioSoundManager != null) {
            varioSoundManager.update(sensorController.getVario());
        }

        // Flight state machine (на основе высоты MSL)
        if (!testMode && !simMode && !scenarioMode && !trackMode) {
            float alt = gpsManager.isAltitudeInitialized()
                    ? gpsManager.getAltitude() : sensorController.getAltitudeRaw();
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
        if (simMode) {
            currentStatus = thermalDetector != null
                    ? thermalDetector.getStatusText() : UiManager.STATUS_SEARCH;
            return;
        }

        if (thermalDetector == null) {
            currentStatus = UiManager.STATUS_SEARCH;
            return;
        }

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

        // VarioThermal detector
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
            currentStatus = UiManager.STATUS_THERMAL;
        } else if (vario < -1.0f) {
            currentStatus = UiManager.STATUS_SINK;
        } else if (thermalDetector.getStatus() == ThermalDetector.STATUS_SUSPECT) {
            currentStatus = UiManager.STATUS_SPIRAL;
        } else {
            currentStatus = UiManager.STATUS_SEARCH;
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
        if (logManager.isLogging()) return;
        logManager.startLogging();
        igcLogger.startLogging();
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
        bgTask = new Runnable() {
            @Override
            public void run() {
                // Обновляем FSM, circlingManager, лог — всё что нужно для TTS/лога
                processSample();
                long bgNow = SystemClock.elapsedRealtime();
                circlingManager.update(
                    sensorController.getGyroZ(),
                    getCompassHeading(),
                    gpsManager.getHeading(),  // GPS track
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
                        if (now - detBlip.bornMs < ThermalBlip.LIFE_MS) {
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

    private void startTrackReplay() {
        trackMode = true;
        trackStartMs = SystemClock.elapsedRealtime();

        synchronized (thermalLock) {
            thermals.clear();
            thermalsCopy.clear();
        }

        trackReplayer = new TrackReplayer();
        trackReplayer.loadFromIGC(getResources().openRawResource(R.raw.track_replay));
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
                        if (nowMs - detBlip.bornMs < ThermalBlip.LIFE_MS) {
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
                            thermals.add(new ThermalBlip(
                                    trackReplayer.getThermalBearing(),
                                    strength,
                                    trackReplayer.getThermalDistance(),
                                    "track",
                                    nowMs
                            ));
                            while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                        }
                    }
                }

                trackHandler.postDelayed(this, 20);
            }
        };
        trackHandler.postDelayed(trackTask, 20);

        android.widget.Toast.makeText(MainActivity.this,
                "Сим2: трек полёта (2x)", android.widget.Toast.LENGTH_SHORT).show();
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

        // Logging label
        private final Paint logLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Thermal label "крутим термик" (cached)
        private final Paint thermalLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Sensor data
        private final Paint sensorDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private long lastAddedBlipBornMs = -1;
        private float touchX, touchY;
        private long touchDownTime;

        // GPS trail render buffers (reused each frame)
        private final float[] trailPxBuf = new float[GPS_TRAIL_MAX];
        private final float[] trailPyBuf = new float[GPS_TRAIL_MAX];
        private final float[] trailBrightBuf = new float[GPS_TRAIL_MAX]; // 0..1 по возрасту

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
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                radarRenderer.onSizeChanged(w, h);
                exitRect.set(8, 8, 8 + 84, 8 + 84);
                float gearSize = 84f;
                gearRect.set(w - gearSize - 24, 24, w - 24, gearSize + 24);

                float btnY = 180f;
                float btnW = w * 0.28f;
                float btnH = 60f;
                calibBtnRect.set(w * 0.03f, btnY, w * 0.03f + btnW, btnY + btnH);
                startBtnRect.set(w * 0.97f - btnW, btnY, w * 0.97f, btnY + btnH);

                float radarCy = h / 2f;
                float radarR = Math.min(w / 2f, radarCy) - 4;
                float altY = radarCy + radarR + 80;
                float testBtnW = 160f;
                float testBtnH = 54f;
                testBtnRect.set(w / 2f - testBtnW / 2f, altY - 24,
                        w / 2f + testBtnW / 2f, altY - 24 + testBtnH);
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
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColor(Color.argb(180, 0, 255, 0)); // ярче
                bp.setTextSize(48);
                bp.setTypeface(android.graphics.Typeface.MONOSPACE);
                bp.setTextAlign(Paint.Align.CENTER);
                bp.setFakeBoldText(true);
                String status = flightStateMachine.isFlying() ? "Слепой полёт" : "Ожидание...";
                canvas.drawText(status, w/2f, h/3f, bp);

                // В слепом режиме показываем ключевые данные: варио, высоту, время
                bp.setTextSize(36);
                bp.setFakeBoldText(false);
                bp.setColor(Color.argb(160, 100, 200, 255)); // голубой
                float varioVal = sensorController.getVario();
                String varioStr = String.format(java.util.Locale.US, "%s%.1f м/с", varioVal >= 0 ? "+" : "", varioVal);
                canvas.drawText(varioStr, w/2f, h/2f, bp);

                bp.setColor(Color.argb(120, 255, 255, 255));
                bp.setTextSize(28);
                float alt = gpsManager.getAltitude();
                float startAlt = gpsManager.getStartAltitude();
                float agl = gpsManager.isAltitudeInitialized() ? (alt - startAlt) : 0f;
                canvas.drawText(String.format(java.util.Locale.US, "%.0f м MSL  +%.0f м AGL", alt, agl), w/2f, h/2f + 60, bp);

                // Время полёта
                if (logManager.isLogging()) {
                    long flightSec = (SystemClock.elapsedRealtime() - logManager.getFlightStartMs()) / 1000;
                    long hh = flightSec / 3600, mm = (flightSec % 3600) / 60, ss = flightSec % 60;
                    bp.setColor(Color.argb(100, 255, 255, 255));
                    canvas.drawText(String.format("%02d:%02d:%02d", hh, mm, ss), w/2f, h/2f + 100, bp);
                }
                return;
            }

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
            // GPS cache for loggers
            logManager.updateGpsCache(
                gpsManager.getLat(), gpsManager.getLon(),
                gpsManager.getAltitude(), gpsManager.getSpeed(), gpsManager.getHeading(),
                gpsManager.getAccuracy(), gpsManager.getFixAgeMs());

            // Static map: обновить если сместились >500м
            if (gpsManager.isReady() && gpsManager.getLat() != 0.0 && gpsManager.getLon() != 0.0) {
                staticMapLoader.updateIfNeeded(gpsManager.getLat(), gpsManager.getLon(), 14);
                // Принудительное обновление, если карта почти за краем
                if (radarRenderer.isMapRefreshNeeded()) {
                    staticMapLoader.forceUpdate(gpsManager.getLat(), gpsManager.getLon(), 14);
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
                // Точка входа в термик
                double lat = gpsManager.getLat();
                double lon = gpsManager.getLon();
                if (lat != 0.0 && lon != 0.0 && entryMarkers.size() < MAX_MARKERS) {
                    entryMarkers.add(new double[]{lat, lon});
                }
            } else if (!nowCircling && prevCirclingState) {
                logManager.recordEvent("CIRCLING_END", "circling stopped");
                igcLogger.recordEvent("C", "circling_stop");
                // Точка выхода из термика
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
                // Blind mode: 15% яркости для экономии заряда
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
                    if (now - detBlip.bornMs < ThermalBlip.LIFE_MS) {
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
                                // TTS: голосовая подсказка направления на новый термик
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
            // GPS trail update + precompute pixel positions
            // ========================================================================
            float trailCy = h / 2f;
            float trailR = Math.min(w / 2f, trailCy) - 4;
            int trailCount = 0;
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

                // Добавляем точку с dedup: не чаще 1 раз/сек
                if (gpsTrail.isEmpty()) {
                    gpsTrail.add(new double[]{pilotLat, pilotLon, now});
                } else {
                    double[] last = gpsTrail.get(gpsTrail.size() - 1);
                    long lastAge = now - (long) last[2];
                    if (lastAge >= GPS_TRAIL_ADD_INTERVAL_MS) {
                        gpsTrail.add(new double[]{pilotLat, pilotLon, now});
                    }
                }

                // Фильтр: удаляем только точки старше 5 минут
                // Точки НЕ удаляются по дистанции — при возвращении пилота они снова видны
                Iterator<double[]> it = gpsTrail.iterator();
                while (it.hasNext()) {
                    double[] pt = it.next();
                    long age = now - (long) pt[2];
                    if (age > GPS_TRAIL_MAX_AGE_MS) { it.remove(); }
                }
                // Лимит буфера
                while (gpsTrail.size() > GPS_TRAIL_MAX) gpsTrail.remove(0);

                // Конвертация в пиксели радара Яркость от возраста (5 мин = 0 → 1.0)
                for (double[] pt : gpsTrail) {
                    long age = now - (long) pt[2];
                    float brightness = 1.0f - (float) age / (float) GPS_TRAIL_MAX_AGE_MS;
                    if (brightness < 0.01f) continue; // совсем старые — пропускаем

                    float[] res = new float[2];
                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], res);
                    float dist = res[0];
                    float bearingRad = (float) Math.toRadians(res[1]);

                    // Масштаб: 150м = trailR пикселей
                    float distPx = (dist / 150f) * trailR;
                    if (distPx > trailR) continue; // за краем радара — не рисуем, но точку храним

                    trailPxBuf[trailCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;
                    trailPyBuf[trailCount] = trailCy - (float) Math.cos(bearingRad) * distPx;
                    trailBrightBuf[trailCount] = brightness;
                    trailCount++;
                }

                // Конвертация entry/exit markers в пиксели радара
                float[] markerPxBuf = new float[entryMarkers.size() + exitMarkers.size()];
                float[] markerPyBuf = new float[entryMarkers.size() + exitMarkers.size()];
                boolean[] markerIsEntry = new boolean[entryMarkers.size() + exitMarkers.size()];
                int markerCount = 0;

                for (double[] pt : entryMarkers) {
                    float[] res = new float[2];
                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], res);
                    float dist = res[0];
                    float bearingRad = (float) Math.toRadians(res[1]);
                    float distPx = (dist / 150f) * trailR;
                    if (distPx > trailR) continue;
                    markerPxBuf[markerCount] = (w / 2f) + (float) Math.sin(bearingRad) * distPx;
                    markerPyBuf[markerCount] = trailCy - (float) Math.cos(bearingRad) * distPx;
                    markerIsEntry[markerCount] = true;
                    markerCount++;
                }

                for (double[] pt : exitMarkers) {
                    float[] res = new float[2];
                    Location.distanceBetween(pilotLat, pilotLon, pt[0], pt[1], res);
                    float dist = res[0];
                    float bearingRad = (float) Math.toRadians(res[1]);
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
                // GPS lost — очищаем трек
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
            // Thermal core + wind for radar renderer
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

            // Phase 2: передаём LiftDatabase и ThermalLocator в RadarRenderer
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
            // Добавляем снос в статистику, если есть
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
            // UI-сглаживание heading (Fix C — time-based SLERP, T12 §2.4.3)
            long headingFrameMs = SystemClock.elapsedRealtime();
            if (!headingDisplayInitialized) {
                headingDisplaySmoothed = headingDisplay;
                headingDisplayInitialized = true;
                lastHeadingFrameMs = headingFrameMs;
            } else {
                double dtSec = (headingFrameMs - lastHeadingFrameMs) / 1000.0;
                lastHeadingFrameMs = headingFrameMs;
                // Time-based: за 100 мс проходим 90% расстояния
                double smoothingFactor = 1.0 - Math.pow(0.1, dtSec / 0.1);
                if (smoothingFactor > 1.0) smoothingFactor = 1.0;
                if (smoothingFactor < 0.01) smoothingFactor = 0.01;
                // SLERP-like для углов: учитываем wrap 0/360
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
            }

            // Позиция пилота для плавного сдвига карты
            radarRenderer.setPilotPosition(gpsManager.getLat(), gpsManager.getLon());

            radarRenderer.draw(canvas, nowMs, thermalsCopy,
                    headingDisplayFinal, varioDisplay, currentStatus,
                    sensorController.getMaxSnr(), thermalsCopy.size(),
                    trailPxBuf, trailPyBuf, trailBrightBuf, trailCount);

            // HUD
            float cx = w / 2f;
            uiManager.drawVario(canvas, cx, 130, varioDisplay);
            uiManager.drawStatus(canvas, cx, currentStatus);

            // "крутим термик" — по центру, если накрутили 540°
            if (circlingManager.isShowThermalLabel()) {
                float labelY = h / 2f;
                canvas.drawText("крутим термик", cx, labelY, thermalLabelPaint);
            }

            // Guidance text from flight scenario
            boolean showGuide = (scenarioMode && flightSim != null && flightSim.isRunning())
                    || (trackMode && trackReplayer != null && trackReplayer.isRunning());
            if (showGuide) {
                String guide = scenarioMode ? flightSim.getGuidanceText() : trackReplayer.getGuidanceText();
                if (guide != null && guide.length() > 0) {
                    float guideY = h / 2f + (scenarioMode && flightSim != null && flightSim.isCircling() ? 0 : -80);
                    thermalLabelPaint.setColor(Color.argb(200, 255, 235, 59));
                    thermalLabelPaint.setTextSize(36);
                    canvas.drawText(guide, cx, guideY, thermalLabelPaint);
                    thermalLabelPaint.setColor(Color.argb(220, 255, 193, 7));
                    thermalLabelPaint.setTextSize(42);
                }
            }

            if (testMode) updateTestFeedback();

            // Info panel
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
                if (sp != null) {
                    uiManager.drawInfo(canvas, 0, h, sp.getTurbulenceMs2(), sp.getSnr(),
                            thermalsCopy.size(), sp.getBpX(), sp.getBpY(),
                            sp.getStableDirDeg(), thermalDetector.getStatusText());
                } else {
                    uiManager.drawInfo(canvas, 0, h,
                            sensorController.getVario(), sensorController.getRecentSnr(),
                            thermalsCopy.size(), 0, 0, 0, currentStatus);
                }
            } else {
                uiManager.drawInfo(canvas, 0, h,
                        sensorController.getVario(), sensorController.getRecentSnr(),
                        thermalsCopy.size(), 0, 0, 0, currentStatus);
            }

            // Altitude
            float radarCy = h / 2f;
            float radarR = Math.min(w / 2f, radarCy) - 4;
            float gpsAlt, startAlt, altAgl;
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                gpsAlt = flightSim.getAltitudeMsl();
                startAlt = 0f;
                altAgl = gpsAlt;
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                gpsAlt = trackReplayer.getAltitude();
                startAlt = 0f;
                altAgl = gpsAlt;
            } else {
                gpsAlt = gpsManager.getAltitude();
                startAlt = gpsManager.getStartAltitude();
                altAgl = gpsManager.isAltitudeInitialized() ? (gpsAlt - startAlt) : 0f;
            }
            float altY = radarCy + radarR + 80;
            float leftMargin = w * 0.05f;
            float rightMargin = w * 0.95f;
            uiManager.drawAltitude(canvas, leftMargin, rightMargin, altY, gpsAlt, altAgl);

            // Stop buttons — показываем при активном сценарии или треке
            if (!scenarioMode && !trackMode) {
                testBtnBgPaint.setColor(Color.TRANSPARENT);
            } else {
                testBtnBgPaint.setColor(Color.argb(50, 255, 80, 80));
            }
            canvas.drawRoundRect(testBtnRect, 12, 12, testBtnBgPaint);
            if (scenarioMode || trackMode) {
                testBtnTextPaint.setTextSize(26);
                testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
                testBtnTextPaint.setColor(Color.argb(220, 255, 80, 80));
                canvas.drawText("СТОП ТЕСТ", testBtnRect.centerX(), testBtnRect.centerY() + 10, testBtnTextPaint);
            }

            // Кнопки СТАРТ (слева) и СТОП (справа)
            btnTextPaint.setTextSize(26);
            btnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            // Старт (слева)
            canvas.drawRoundRect(calibBtnRect, 10, 10, btnBgPaint);
            btnTextPaint.setColor(Color.argb(200, 0, 255, 0));
            canvas.drawText("СТАРТ", calibBtnRect.centerX(), calibBtnRect.centerY() + 9, btnTextPaint);

            // Sensor data below start button
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

                float dataX = calibBtnRect.left;
                float density = getResources().getDisplayMetrics().density;
                float dataY = calibBtnRect.bottom + 58 + 27f * density;
                sensorDataPaint.setColor(Color.argb(160, 0, 255, 0));
                sensorDataPaint.setTextSize(27f * density);
                sensorDataPaint.setTextAlign(Paint.Align.LEFT);

                String labAmp = "\u00B1" + String.format(java.util.Locale.US, "%.2f", amp);
                canvas.drawText(labAmp, dataX, dataY, sensorDataPaint);
                float advX = sensorDataPaint.measureText(labAmp) + 24;

                String labFreq = String.format(java.util.Locale.US, "%.2f\u0413\u0446", freq);
                canvas.drawText(labFreq, dataX + advX, dataY, sensorDataPaint);
                advX += sensorDataPaint.measureText(labFreq) + 24;

                String labDir = String.format(java.util.Locale.US, "%.0f\u00B0", dir);
                canvas.drawText(labDir, dataX + advX, dataY, sensorDataPaint);

                // Строка наклона (всегда, 0° если не откалибровано)
                float tiltY = dataY + 20 + 27f * density;
                sensorDataPaint.setTextSize(16f * getResources().getDisplayMetrics().density);
                sensorDataPaint.setColor(Color.argb(120, 0, 255, 0));
                {
                    float mountTilt = sensorController.getMountTiltDeg();
                    float currTilt = sensorController.getCurrentTiltDeg();
                    if (mountTilt > 0.5f) {
                        canvas.drawText(String.format(java.util.Locale.US,
                                "\u041A\u0440\u0435\u043F\u043B\u0435\u043D\u0438\u0435: %.0f\u00B0 | \u041A\u0440\u0435\u043D: %.0f\u00B0",
                                mountTilt, currTilt), dataX, tiltY, sensorDataPaint);
                    } else {
                        canvas.drawText(String.format(java.util.Locale.US,
                                "\u041A\u0440\u0435\u043D: %.0f\u00B0 (\u043D\u0435\u0442 \u043A\u0430\u043B\u0438\u0431\u0440\u043E\u0432\u043A\u0438)",
                                currTilt), dataX, tiltY, sensorDataPaint);
                    }
                }

            // Logging label — над дебаг строкой
                if (logManager.isLogging()) {
                    logLabelPaint.setTextSize(27f * density);
                    float labelY = dataY - 35f * density;
                    logLabelPaint.setColor(Color.argb(220, 33, 150, 243)); // всегда синяя
                    float shiftRight = logLabelPaint.measureText("пише");
                    canvas.drawText("пишем лог", dataX + 5f + shiftRight, labelY, logLabelPaint);
                }
            }

            // Стоп (справа) — всегда виден
            canvas.drawRoundRect(startBtnRect, 10, 10, btnBgPaint);
            if (logManager.isLogging()) {
                btnTextPaint.setColor(Color.argb(255, 255, 80, 80));
            } else {
                btnTextPaint.setColor(Color.argb(60, 255, 80, 80));
            }
            canvas.drawText("СТОП", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);

            // Ветер м/с под кнопкой СТОП
            float windDeg = circlingManager.getWindFromDeg();
            float windSpdMs = circlingManager.getDisplayWindSpeed();
            if (windDeg >= 0 && windSpdMs > 0) {
                sensorDataPaint.setColor(Color.argb(160, 100, 200, 255));
                sensorDataPaint.setTextSize(16f * getResources().getDisplayMetrics().density);
                sensorDataPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.format(java.util.Locale.US, "ветер %.1f м/с", windSpdMs),
                        startBtnRect.centerX(), startBtnRect.bottom + 22, sensorDataPaint);
            }

            // Flight time
            long flightTimeMs;
            if (simMode) {
                flightTimeMs = SystemClock.elapsedRealtime() - simStartMs;
            } else if (scenarioMode) {
                flightTimeMs = SystemClock.elapsedRealtime() - scenarioStartMs;
            } else if (trackMode) {
                flightTimeMs = SystemClock.elapsedRealtime() - trackStartMs;
            } else if (logManager.isLogging()) {
                flightTimeMs = System.currentTimeMillis() - logManager.getFlightStartMs();
            } else {
                flightTimeMs = 0;
            }
            uiManager.drawFlightTime(canvas, cx, altY + 100 + 10 + 35f * getResources().getDisplayMetrics().density, flightTimeMs / 1000);
            uiManager.drawSystemTime(canvas, cx, altY + 100 + 10 + 75 + 5 + 70f * getResources().getDisplayMetrics().density);

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
            drawGearButton(canvas);

            // Test mode overlay
            if (testMode) {
                String instr = getTestInstruction();
                String fb = getTestFeedback();
                if (instr != null && instr.length() > 0) {
                    float radarCx = w / 2f;
                    float radarCy2 = h / 2f;
                    float radarR2 = Math.min(w / 2f, radarCy2) - 4;
                    testBgPaint.setColor(Color.argb(180, 5, 5, 5));
                    canvas.drawCircle(radarCx, radarCy2, radarR2 * 0.55f, testBgPaint);
                    testBorderPaint.setStyle(Paint.Style.STROKE);
                    testBorderPaint.setStrokeWidth(1);
                    testBorderPaint.setColor(Color.argb(40, 0, 255, 0));
                    canvas.drawCircle(radarCx, radarCy2, radarR2 * 0.55f, testBorderPaint);

                    String[] lines = instr.split("\n");
                    float textY = radarCy2 - radarR2 * 0.35f;
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
                        canvas.drawText(line, radarCx, textY, testTextPaint);
                        textY += 38;
                    }

                    if (fb != null && fb.length() > 0) {
                        float fbY = radarCy2 + radarR2 * 0.08f;
                        testTextPaint.setColor(getTestFeedbackColor());
                        testTextPaint.setTextSize(28);
                        testTextPaint.setFakeBoldText(true);
                        canvas.drawText(fb, radarCx, fbY, testTextPaint);
                    }

                    float barY = radarCy2 + radarR2 * 0.28f;
                    float barW = radarR2 * 0.8f;
                    float barH = 10f;
                    float barLeft = radarCx - barW / 2f;
                    testBarBgPaint.setColor(Color.argb(30, 0, 255, 0));
                    canvas.drawRoundRect(barLeft, barY, barLeft + barW, barY + barH, 5, 5, testBarBgPaint);
                    testBarFillPaint.setColor(Color.argb(180, 76, 175, 80));
                    float progress = testStep / 6.0f;
                    if (testStep > 6) progress = 1f;
                    canvas.drawRoundRect(barLeft, barY, barLeft + barW * progress, barY + barH, 5, 5, testBarFillPaint);
                }
            }
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

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            touchX = event.getX();
            touchY = event.getY();

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Запоминаем время для детекции долгого нажатия
                touchDownTime = System.currentTimeMillis();
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_UP) {
                long touchDuration = System.currentTimeMillis() - touchDownTime;

                if (exitRect.contains(touchX, touchY)) {
                    showExitOnScreenDialog();
                    return true;
                }
                if (gearRect.contains(touchX, touchY)) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
                // СТАРТ: тап = старт лога (только старт, не стоп!), долгое нажатие = сброс калибровки
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
                    if (logManager.isLogging()) confirmStopLogging();
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
