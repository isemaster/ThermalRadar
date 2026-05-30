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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.core.SignalProcessor;
import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.flight.FlightStateMachine;
import com.termo1.radar.flight.BlindFlightMode;
import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.logging.LogManager;
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

    // ========================================================================
    // Modules
    // ========================================================================

    private SensorController sensorController;
    private GpsManager gpsManager;
    private LogManager logManager;
    private FlightStateMachine flightStateMachine;
    private BlindFlightMode blindFlightMode;
    private boolean blindModeEnabled;
    private boolean voicePromptsEnabled;

    // ========================================================================
    // Core
    // ========================================================================

    private ThermalDetector thermalDetector;
    private SharedPreferences prefs;

    // ========================================================================
    // Thermals
    // ========================================================================

    private final List<ThermalBlip> thermals = new ArrayList<>();
    private final List<ThermalBlip> thermalsCopy = new ArrayList<>();
    private final Object thermalLock = new Object();

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

    // ========================================================================
    // Simulation
    // ========================================================================

    private boolean simMode;
    private SimulationManager simulation;
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
    private long testStepStartMs;
    private long testCorrectStartMs;
    private long testLastBeepMs;
    private boolean testBeepPlaying;
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

        flightStateMachine = new FlightStateMachine();
        flightStateMachine.setListener(flightListener);

        blindFlightMode = new BlindFlightMode();
        blindModeEnabled = prefs.getBoolean("blind_mode", false);
        voicePromptsEnabled = prefs.getBoolean("voice_prompts", true);

        // Renderer + UI
        radarRenderer = new RadarRenderer();
        uiManager = new UiManager();
        uiManager.setDensity(getResources().getDisplayMetrics().density);
        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        uiManager.setColorScheme(prefs.getInt("color_scheme", 0));

        radarView = new RadarView(this);
        setContentView(radarView);

        // System services
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Sound
        varioSoundManager = new VarioSoundManager();

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

        // Simulation mode
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("simulate", false)) {
            simMode = true;
            simulation = new SimulationManager();
            simulation.start();
            simStartMs = System.currentTimeMillis();
            lastThermalBeepMs = simStartMs;
            startSimLoop();
        }

        // Test mode
        if (intent != null && intent.getBooleanExtra("test_mode", false)) {
            startTestMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;

        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        uiManager.setColorScheme(prefs.getInt("color_scheme", 0));
        sensorController.loadPreferences(prefs);

        sensorController.registerSensors();
        acquireWakeLock();

        // Обновить настройки из SharedPreferences (могли измениться в Settings)
        blindModeEnabled = prefs.getBoolean("blind_mode", false);
        voicePromptsEnabled = prefs.getBoolean("voice_prompts", true);

        // GPS: структура готова, но запрос не включаем (экономия энергии)
        // Значения GPS в логе будут 0 — колонки сохраняются для совместимости
        // gpsManager.startGps();

        // Яркость экрана: средняя (0.5) по умолчанию
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.5f;
        getWindow().setAttributes(lp);

        startRendering();
        if (varioSoundManager != null) varioSoundManager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        stopRendering();
        sensorController.unregisterSensors();
        releaseWakeLock();
        gpsManager.stopGps();
        if (varioSoundManager != null) varioSoundManager.stop();
        stopTestMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (simHandler != null && simTask != null) {
            simHandler.removeCallbacks(simTask);
        }
        logManager.destroy();
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

            if (thermalDetector != null && !simMode) {
                if (compassReady) {
                    worldAccelOut[0] = rotMatrix[0] * axG * 9.81f
                            + rotMatrix[1] * ayG * 9.81f
                            + rotMatrix[2] * azG * 9.81f;
                    worldAccelOut[1] = rotMatrix[3] * axG * 9.81f
                            + rotMatrix[4] * ayG * 9.81f
                            + rotMatrix[5] * azG * 9.81f;
                    thermalDetector.processSample(worldAccelOut[0] / 9.81f,
                            worldAccelOut[1] / 9.81f);
                } else {
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
            if (!sensorController.hasLinearAccel() && thermalDetector != null && !simMode) {
                if (compassReady) {
                    worldAccelOut[0] = rotMatrix[0] * axMs2 + rotMatrix[1] * ayMs2 + rotMatrix[2] * azMs2;
                    worldAccelOut[1] = rotMatrix[3] * axMs2 + rotMatrix[4] * ayMs2 + rotMatrix[5] * azMs2;
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
        public String getThermalLogSuffix() {
            if (thermalDetector != null) {
                ThermalBlip blip = thermalDetector.getCurrentBlip();
                if (blip != null) {
                    return "," + blip.angle
                            + "," + String.format(java.util.Locale.US, "%.1f", blip.strength)
                            + "," + (int) blip.distance
                            + "," + blip.source
                            + "," + String.format(java.util.Locale.US, "%.2f",
                                    thermalDetector.getSignalProcessor().getSnr())
                            + "," + String.format(java.util.Locale.US, "%.4f",
                                    thermalDetector.getSignalProcessor().getNoiseFloor());
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
            android.widget.Toast.makeText(MainActivity.this,
                    "Полёт обнаружен, запись лога", android.widget.Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onFlightFinished() {
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

        // Flight state machine (на основе высоты MSL)
        if (!testMode && !simMode) {
            float alt = gpsManager.isAltitudeInitialized()
                    ? gpsManager.getAltitude() : sensorController.getAltitudeRaw();
            flightStateMachine.update(alt, System.currentTimeMillis());
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

        float vario = sensorController.getVario();
        float level = thermalDetector.getSignalProcessor().getTurbulenceMs2();

        if (vario > 1.0f && level > 0.3f) {
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

    public void toggleManualLogging() {
        if (logManager.isLogging()) {
            flightStateMachine.reset();
            logManager.stopLogging();
            android.widget.Toast.makeText(this,
                    "Лог сохранён", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            logManager.startLogging();
            android.widget.Toast.makeText(this,
                    "Запись лога начата", android.widget.Toast.LENGTH_SHORT).show();
        }
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
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
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
                radarView.postInvalidateOnAnimation();
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
                long now = System.currentTimeMillis();
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
                            if (varioSoundManager != null) varioSoundManager.playThermalBeep();
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
        if (sensorController.isCompassReady()) return sensorController.getHeading();
        if (gpsManager.isReady()) return gpsManager.getHeading();
        return 0.0f;
    }

    // ========================================================================
    // TTS голосовые подсказки
    // ========================================================================

    /** Произнести направление на термик (только передние сектора 45°) */
    private void speakThermalDirection(float thermalAngle, float distanceMeters) {
        float heading = getCompassHeading();
        float rel = (thermalAngle - heading + 540f) % 360f - 180f; // [-180, 180]

        String phrase;
        if (rel < -112.5f || rel > 112.5f) return; // задние сектора — молчим
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
        if (phrase.equals(lastTtsPhrase) && now - lastTtsSpeakMs < 8000) return;

        lastTtsPhrase = phrase;
        lastTtsSpeakMs = now;

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
        // Sensor data
        private final Paint sensorDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private long lastAddedBlipBornMs = -1;
        private float touchX, touchY;
        private long touchDownTime;

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

            // Blind mode: чёрный экран, минимум UI, только голосовые подсказки
            if (blindModeEnabled) {
                canvas.drawColor(Color.BLACK);
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColor(Color.argb(40, 0, 255, 0));
                bp.setTextSize(32);
                bp.setTypeface(android.graphics.Typeface.MONOSPACE);
                bp.setTextAlign(Paint.Align.CENTER);
                String status = flightStateMachine.isFlying() ? "Слепой полёт" : "Ожидание...";
                canvas.drawText(status, w/2f, h/2f, bp);
                return;
            }

            // Sound + WakeLock refresh
            if (varioSoundManager != null) {
                varioSoundManager.update(sensorController.getVario());
            }
            refreshWakeLock();

            // Process sample for flight state + sound
            processSample();

            // Push GPS cache to LogManager (1 Гц данные, пишутся в каждом сэмпле)
            logManager.updateGpsCache(
                gpsManager.getLat(), gpsManager.getLon(),
                gpsManager.getAltitude(), gpsManager.getSpeed(), gpsManager.getHeading());

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

            // Яркость: макс в полёте при записи лога, средняя в остальное время
            if (flightStateMachine.isFlying() && logManager.isLogging()) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (lp.screenBrightness < 1.0f) {
                    lp.screenBrightness = 1.0f;
                    getWindow().setAttributes(lp);
                }
            } else if (blindModeEnabled) {
                // Blind mode: экран почти погашен
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (lp.screenBrightness > 0.02f) {
                    lp.screenBrightness = 0.01f;
                    getWindow().setAttributes(lp);
                }
            }

            // Update status
            updateStatus();

            // Вибрация при смене статуса на термик/набор
            if (!currentStatus.equals(previousStatus)) {
                previousStatus = currentStatus;
                if (currentStatus.equals(UiManager.STATUS_THERMAL)
                        || currentStatus.equals(UiManager.STATUS_CLIMB)) {
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
            if (!simMode && thermalDetector != null && !testMode) {
                ThermalBlip detBlip = thermalDetector.getCurrentBlip();
                if (detBlip != null) {
                    long now = System.currentTimeMillis();
                    if (now - detBlip.bornMs < ThermalBlip.LIFE_MS) {
                        synchronized (thermalLock) {
                            Iterator<ThermalBlip> it = thermals.iterator();
                            while (it.hasNext()) {
                                if (!it.next().isAlive(now)) it.remove();
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
                                // TTS: голосовая подсказка направления на новый термик
                                if (voicePromptsEnabled && ttsReady) {
                                    speakThermalDirection(detBlip.angle, detBlip.distance);
                                }
                                while (thermals.size() > THERMAL_LIMIT) thermals.remove(0);
                            }
                        }
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastThermalBeepMs > 3000) {
                            if (varioSoundManager != null) varioSoundManager.playThermalBeep();
                            lastThermalBeepMs = nowMs;
                        }
                    }
                }
            }

            // Draw radar
            long nowMs = System.currentTimeMillis();
            synchronized (thermalLock) {
                thermalsCopy.clear();
                thermalsCopy.addAll(thermals);
            }
            radarRenderer.draw(canvas, nowMs, thermalsCopy,
                    getCompassHeading(), sensorController.getVario(), currentStatus,
                    sensorController.getMaxSnr(), thermalsCopy.size());

            // HUD
            float cx = w / 2f;
            uiManager.drawVario(canvas, cx, 260, sensorController.getVario());
            uiManager.drawStatus(canvas, cx, currentStatus);

            if (testMode) updateTestFeedback();

            // Info panel
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
                uiManager.drawInfo(canvas, 0, h, sp.getTurbulenceMs2(), sp.getSnr(),
                        thermalsCopy.size(), sp.getBpX(), sp.getBpY(),
                        sp.getStableDirDeg(), thermalDetector.getStatusText());
            } else {
                uiManager.drawInfo(canvas, 0, h,
                        sensorController.getVario(), sensorController.getRecentSnr(),
                        thermalsCopy.size(), 0, 0, 0, currentStatus);
            }

            // Altitude
            float radarCy = h / 2f;
            float radarR = Math.min(w / 2f, radarCy) - 4;
            float gpsAlt = gpsManager.getAltitude();
            float startAlt = gpsManager.getStartAltitude();
            float altAgl = gpsManager.isAltitudeInitialized() ? (gpsAlt - startAlt) : 0f;
            float altY = radarCy + radarR + 80;
            float leftMargin = w * 0.05f;
            float rightMargin = w * 0.95f;
            uiManager.drawAltitude(canvas, leftMargin, rightMargin, altY, gpsAlt, altAgl);

            // Test button
            canvas.drawRoundRect(testBtnRect, 12, 12, testBtnBgPaint);
            testBtnTextPaint.setTextSize(28);
            testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            if (testMode) {
                float blink = (float) Math.sin(nowMs / 300.0) * 0.3f + 0.7f;
                testBtnTextPaint.setColor(Color.argb((int)(blink*220), 255, 193, 7));
            }
            canvas.drawText("ТЕСТ", testBtnRect.centerX(), testBtnRect.centerY() + 10, testBtnTextPaint);

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
                float amp = sp.getTurbulenceMs2();
                float freq = Math.max(0.5f, Math.min(2.5f, sp.getDominantFrequency()));
                float dir = sp.getStableDirDeg();
                if (dir < 0) dir += 360f;
                if (dir >= 360f) dir -= 360f;

                float dataX = calibBtnRect.left;
                float dataY = calibBtnRect.bottom + 28;
                sensorDataPaint.setColor(Color.argb(160, 0, 255, 0));
                float density = getResources().getDisplayMetrics().density;
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

                // Строка наклона (мелко, под данными датчиков)
                float tiltY = dataY + 20;
                sensorDataPaint.setTextSize(16f * getResources().getDisplayMetrics().density);
                sensorDataPaint.setColor(Color.argb(120, 0, 255, 0));
                float mountTilt = sensorController.getMountTiltDeg();
                float currTilt = sensorController.getCurrentTiltDeg();
                if (mountTilt > 1f) {
                    canvas.drawText(String.format(java.util.Locale.US,
                            "\u041A\u0440\u0435\u043F\u043B\u0435\u043D\u0438\u0435: %.0f\u00B0 | \u041A\u0440\u0435\u043D: %.0f\u00B0",
                            mountTilt, currTilt), dataX, tiltY, sensorDataPaint);
                }
            }

            // Logging label — красный в полёте
            if (logManager.isLogging()) {
                float labelY = calibBtnRect.centerY() + 9;
                float labelCx = (calibBtnRect.right + startBtnRect.left) / 2f;
                if (flightStateMachine.isFlying()) {
                    logLabelPaint.setColor(Color.argb(220, 255, 80, 80));
                } else {
                    logLabelPaint.setColor(Color.argb(200, 33, 150, 243));
                }
                canvas.drawText("пишем лог", labelCx, labelY, logLabelPaint);
            }

            // Стоп (справа) — всегда виден
            canvas.drawRoundRect(startBtnRect, 10, 10, btnBgPaint);
            if (logManager.isLogging()) {
                btnTextPaint.setColor(Color.argb(255, 255, 80, 80));
            } else {
                btnTextPaint.setColor(Color.argb(60, 255, 80, 80));
            }
            canvas.drawText("СТОП", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);

            // Flight time
            long flightTimeMs;
            if (simMode) {
                flightTimeMs = System.currentTimeMillis() - simStartMs;
            } else if (logManager.isLogging()) {
                flightTimeMs = System.currentTimeMillis() - logManager.getFlightStartMs();
            } else {
                flightTimeMs = 0;
            }
            uiManager.drawFlightTime(canvas, cx, altY + 90 + 10, flightTimeMs / 1000);
            uiManager.drawSystemTime(canvas, cx, altY + 90 + 10 + 75 + 5);

            // Night filter
            uiManager.drawNightFilter(canvas, w, h);

            // SIM badge
            if (simMode) {
                float badgeX = 12f;
                float badgeY = 190f;
                float textW = simPaint.measureText("SIM");
                float pad = 8f;
                canvas.drawRoundRect(badgeX, badgeY, badgeX + textW + pad * 2,
                        badgeY + 32 + pad, 8, 8, simBgPaint);
                canvas.drawText("SIM", badgeX + pad, badgeY + 28, simPaint);
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
                // СТАРТ: тап = старт лога, долгое нажатие = сброс калибровки
                if (calibBtnRect.contains(touchX, touchY)) {
                    if (touchDuration > 600) {
                        resetCalibration();
                        android.widget.Toast.makeText(MainActivity.this,
                                "Калибровка сброшена (долгое нажатие)",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        toggleManualLogging();
                    }
                    return true;
                }
                if (startBtnRect.contains(touchX, touchY)) {
                    if (logManager.isLogging()) toggleManualLogging();
                    return true;
                }
                if (testBtnRect.contains(touchX, touchY)) {
                    if (!testMode) startTestMode();
                    else {
                        stopTestMode();
                        testMode = false;
                        testInstruction = "";
                        android.widget.Toast.makeText(MainActivity.this,
                                "Тест отменён", android.widget.Toast.LENGTH_SHORT).show();
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
