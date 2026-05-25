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
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.ui.RadarRenderer;
import com.termo1.radar.ui.SettingsActivity;
import com.termo1.radar.ui.UiManager;
import com.termo1.radar.ui.VarioSoundManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {

    private static final int SAMPLE_RATE_HZ = 50;
    private static final int RENDER_FPS = 30;
    private static final long SAMPLE_INTERVAL_MS = 20L;
    private static final long RENDER_INTERVAL_MS = 33L;
    private static final int THERMAL_LIMIT = 12;
    private static final float MAX_DISTANCE_M = 150.0f;

    // ---- Sensors (android.hardware) ----
    private SensorManager sensorManager;
    private Sensor barometer;
    private Sensor accelerometerLinear;    // TYPE_LINEAR_ACCELERATION — для детектора
    private Sensor accelerometerGravity;   // TYPE_ACCELEROMETER — для компаса (гравитация)
    private Sensor gyroscope;
    private Sensor magnetometer;

    // ---- Latest sensor values ----
    private volatile float latestAccelX;  // linear accel (g) — для детектора
    private volatile float latestAccelY;
    private volatile float latestAccelZ;
    private volatile float gravityAccelX; // raw accel (м/с²) — для компаса
    private volatile float gravityAccelY;
    private volatile float gravityAccelZ;
    private volatile boolean hasGravityAccel;
    private volatile float latestGyroX;
    private volatile float latestGyroY;
    private volatile float latestGyroZ;
    private volatile float latestMagX;
    private volatile float latestMagY;
    private volatile float latestMagZ;
    private volatile float latestPressure = 1013.25f;
    private volatile boolean hasLinearAccel;

    // ---- Compass (fusion) ----
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationVals = new float[3];
    private volatile float heading = 0.0f;
    private volatile float pitch = 0.0f;    // наклон вперёд/назад (радианы)
    private volatile float roll = 0.0f;     // крен влево/вправо (радианы)
    private volatile float rawHeading = 0.0f; // сырой heading до EMA
    private volatile boolean compassReady = false;

    // ---- Barometer / variometer ----
    private float baselinePressure = 1013.25f;
    private int baroCalibCount = 0;
    private static final int BARO_CALIB_SAMPLES = 50;
    private volatile float vario = 0.0f;
    private volatile float altFiltered = 0.0f;     // EMA-smoothed altitude (m) — для HPF
    private volatile float altRaw = 0.0f;           // raw altitude from baro formula
    private volatile float prevAltRaw = 0.0f;       // предыдущий altRaw для vario = (-curr+prev)*1000/dt
    private long lastBaroMs = 0;                     // timestamp последнего барометра (мс)
    private volatile float maxSnr = 0.0f;
    private volatile float recentSnr = 0.0f;
    private static final float VARIO_ALPHA_CALM = 0.95f;      // штиль: сильное сглаживание
    private static final float VARIO_ALPHA_NORMAL = 0.85f;    // норма: умеренное
    private static final float VARIO_ALPHA_TURBULENT = 0.60f; // горы: быстрый отклик
    private int varioProfile = 1;            // 0=Calm, 1=Normal, 2=Turbulent (из prefs)
    private static final String KEY_VARIO_PROFILE = "vario_profile";
    private int varioSmoothSamples = 30;    // усреднение варио (5-100 отсчётов)
    private static final String KEY_VARIO_SMOOTH = "vario_smooth";
    private final float[] hpBuf = new float[64];
    private int hpBufIdx = 0;
    private int hpBufFill = 0;

    // ---- Скользящее среднее варио 0.75 сек (кольцевой буфер) ----
    private static final int VARIO_BUF_SIZE = 64;
    private static final long VARIO_WINDOW_MS = 750;
    private final float[] varioBuf = new float[VARIO_BUF_SIZE];
    private final long[] varioTimeBuf = new long[VARIO_BUF_SIZE];
    private int varioHead = 0;  // индекс для записи
    private int varioTail = 0;  // индекс самой старой записи

    // ---- Thermals ----
    private final List<ThermalBlip> thermals = new ArrayList<ThermalBlip>();
    private final Object thermalLock = new Object();

    // ---- GPS ----
    private LocationManager locationManager;
    private volatile float gpsSpeed = 0.0f;
    private volatile float gpsHeading = 0.0f;
    private boolean gpsReady = false;
    private float gpsAltitude = 0.0f;
    private float startAltitude = 0.0f;
    private java.io.FileWriter gpsWriter = null;
    private boolean altitudeInitialized = false;
    private double gpsLat = 0.0;
    private double gpsLon = 0.0;

    // ---- Power ----
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    // ---- Logging ----
    private boolean isLogging = false;
    private StringBuilder logBuffer;
    private long flightStartMs;
    private long sampleCount = 0L;

    // ---- Flight auto-log (vario-based) ----
    private boolean flightLogEnabled = false;
    private int flightLogState = 0;
    private long flightLogLastActiveMs;
    private static final long FLIGHT_LOG_TIMEOUT_MS = 300000;
    private boolean manualStopRequested = false; // ручной стоп блокирует автолог

    // ---- GPS track file ----
    private StringBuilder gpsTrackBuffer;
    private boolean gpsTracking = false;
    private long lastGpsLogMs;

    // ---- UI ----
    private RadarView radarView;
    private RadarRenderer radarRenderer;
    private UiManager uiManager;
    private SharedPreferences prefs;
    private VarioSoundManager varioSoundManager;
    private AlertDialog exitDialog;

    // ---- Simulation ----
    private boolean simMode = false;
    private SimulationManager simulation;
    private long simStartMs;
    private long lastThermalBeepMs;
    private final Handler simHandler = new Handler(Looper.getMainLooper());
    private Runnable simTask;

    // ---- IO thread (файловые операции в фоне) ----
    private android.os.HandlerThread ioThread;
    private android.os.Handler ioHandler;

    // ---- Processing / rendering threads ----
    private volatile boolean running = false;
    private final Handler renderHandler = new Handler(Looper.getMainLooper());
    private Runnable renderTask;
    private volatile String currentStatus = "ПОИСК";

    // ---- Thermal detector ----
    private ThermalDetector thermalDetector;
    private boolean usingLinearAccel = false;

    // ---- Test mode ----
    private boolean testMode = false;
    private int testStep = 0;
    private String testInstruction = "";
    private String testFeedback = "";         // real-time feedback text
    private int testFeedbackColor = 0;        // argb color for feedback
    private boolean testStepCorrect = false;  // true when current step done correctly
    private long testStepStartMs;
    private long testCorrectStartMs;          // when correct signal was first detected
    private long testLastBeepMs;              // cooldown for test beep
    private boolean testBeepPlaying = false;  // prevent double beep
    private Handler testHandler = new Handler(Looper.getMainLooper());
    private Runnable testTask;

    // ---- Button state ----
    private boolean calibRequested = false;

    // ---- Pressure reading flag ----
    private boolean hasPressureReading = false;

    // ========================================================================
    // Activity lifecycle
    // ========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Единый формат чисел: точка как десятичный разделитель
        // (по умолчанию на телефонах с русской локалью — запятая, ломающая CSV)
        java.util.Locale.setDefault(java.util.Locale.US);

        // Fullscreen, keep screen on, immersive sticky
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

        // Runtime permission request for GPS (Android 6+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        1001);
            }
        }

        // Preferences
        prefs = getSharedPreferences("termo1_settings", MODE_PRIVATE);
        varioProfile = prefs.getInt(KEY_VARIO_PROFILE, 1);
        varioSmoothSamples = prefs.getInt(KEY_VARIO_SMOOTH, 30);
 
         // Radar renderer
        radarRenderer = new RadarRenderer();

        // UI manager
        uiManager = new UiManager();
        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        int colorScheme = prefs.getInt("color_scheme", 0);
        uiManager.setColorScheme(colorScheme);

        // Set content view with RadarView
        radarView = new RadarView(this);
        setContentView(radarView);

        // System services
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // Log buffer
        logBuffer = new StringBuilder();
        gpsTrackBuffer = new StringBuilder();

        // Background I/O thread для записи логов на диск
        ioThread = new android.os.HandlerThread("termo1-io");
        ioThread.start();
        ioHandler = new android.os.Handler(ioThread.getLooper());

        // Vario sound manager
        varioSoundManager = new VarioSoundManager();

        // Thermal detector
        thermalDetector = new ThermalDetector();

        // Simulation mode check
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("simulate", false)) {
            simMode = true;
            simulation = new SimulationManager();
            simulation.start();
            simStartMs = System.currentTimeMillis();
            heading = 0f;
            compassReady = true;
            altitudeInitialized = true;
            startAltitude = 500f;
            gpsAltitude = 500f;
            flightStartMs = simStartMs;
            lastThermalBeepMs = simStartMs;
            startSimLoop();
        }

        // Check if test mode requested
        if (intent != null && intent.getBooleanExtra("test_mode", false)) {
            startTestMode();
        }

        // Flight auto-log mode
        flightLogEnabled = prefs.getBoolean("flight_log_enabled", false);
        if (intent != null && intent.getBooleanExtra("stop_flight_log", false)) {
            // Stop was requested from settings
            flightLogEnabled = false;
            prefs.edit().putBoolean("flight_log_enabled", false).apply();
            if (isLogging || flightLogState == FLIGHT_LOG_ACTIVE) {
                stopFlightLog();
            }
        }
        if (flightLogEnabled) {
            manualStopRequested = false;
            flightLogState = FLIGHT_LOG_IDLE;
            flightLogLastActiveMs = System.currentTimeMillis();
            // startGpsTrack() вызывается в onResume после startGps()
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;

        uiManager.setNightMode(prefs.getBoolean("night_mode", false));
        int colorScheme = prefs.getInt("color_scheme", 0);
        uiManager.setColorScheme(colorScheme);
        varioProfile = prefs.getInt(KEY_VARIO_PROFILE, 1);
        varioSmoothSamples = prefs.getInt(KEY_VARIO_SMOOTH, 30);
 
         registerSensors();
        acquireWakeLock();
        startGps();
        if (flightLogEnabled) startGpsTrack();
        startRendering();
        if (varioSoundManager != null) {
            varioSoundManager.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        stopRendering();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        releaseWakeLock();
        stopGps();
        if (varioSoundManager != null) {
            varioSoundManager.stop();
        }
        stopTestMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Удаляем pending callbacks симуляции, чтобы simTask не выполнился после onDestroy
        if (simHandler != null && simTask != null) {
            simHandler.removeCallbacks(simTask);
        }
        stopFlightLog();
        stopLogging();
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
            ioHandler = null;
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
    // Sensor registration — TWO accelerometers for compass + detector
    // ========================================================================

    private void registerSensors() {
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        accelerometerGravity = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerLinear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (accelerometerLinear != null) {
            usingLinearAccel = true;
        } else {
            accelerometerLinear = accelerometerGravity;
            usingLinearAccel = false;
        }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Register all available sensors
        if (barometer != null)
            sensorManager.registerListener(this, barometer, SensorManager.SENSOR_DELAY_GAME);
        if (accelerometerLinear != null)
            sensorManager.registerListener(this, accelerometerLinear, SensorManager.SENSOR_DELAY_GAME);
        if (accelerometerGravity != null)
            sensorManager.registerListener(this, accelerometerGravity, SensorManager.SENSOR_DELAY_GAME);
        if (gyroscope != null)
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        if (magnetometer != null)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);

        // Build missing sensors message
        StringBuilder missing = new StringBuilder();
        if (accelerometerGravity == null) {
            missing.append("❌ Акселерометр — приложение не может работать\n");
        }
        if (accelerometerLinear == null) {
            missing.append("⚠️ LINEAR_ACCEL — будет использован TYPE_ACCELEROMETER\n");
        }
        if (barometer == null) {
            missing.append("⚠️ Барометр — нет вариометра, автолог по GPS\n");
        }
        if (gyroscope == null) {
            missing.append("⚠️ Гироскоп — нет детекции вращения\n");
        }
        if (magnetometer == null) {
            missing.append("⚠️ Магнитометр — нет компаса, heading = GPS курс\n");
        }

        if (missing.length() > 0) {
            String msg = "Доступны не все датчики:\n" + missing.toString()
                    + "\nЛоги будут неполными, но акселерометр — основа — работает.";
            new AlertDialog.Builder(this)
                    .setTitle("Датчики")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show();
        }

        android.util.Log.i("TERMO1", "Sensors: BARO=" + (barometer!=null)
                + " LINEAR_ACCEL=" + (accelerometerLinear!=null)
                + " GRAVITY_ACCEL=" + (accelerometerGravity!=null)
                + " GYRO=" + (gyroscope!=null)
                + " MAGNET=" + (magnetometer!=null));
    }

    // ========================================================================
    // SensorEventListener
    // ========================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_PRESSURE: {
                latestPressure = event.values[0];
                hasPressureReading = true;
                onPressureSample();
                break;
            }
            case Sensor.TYPE_LINEAR_ACCELERATION: {
                // LINEAR — без гравитации, для детектора микрораскачки
                float ax = event.values[0] / 9.81f;
                float ay = event.values[1] / 9.81f;
                float az = event.values[2] / 9.81f;
                latestAccelX = ax;
                latestAccelY = ay;
                latestAccelZ = az;
                hasLinearAccel = true;
                if (thermalDetector != null && !simMode) {
                    // Поворот из осей телефона в мировые координаты (E=X, N=Y)
                    // Используем rotationMatrix из компаса
                    if (compassReady) {
                        float[] in = event.values;
                        float[] out = new float[3];
                        out[0] = rotationMatrix[0] * in[0] + rotationMatrix[1] * in[1] + rotationMatrix[2] * in[2];
                        out[1] = rotationMatrix[3] * in[0] + rotationMatrix[4] * in[1] + rotationMatrix[5] * in[2];
                        // worldAccel[0]=восток, worldAccel[1]=север, z нам не нужен
                        thermalDetector.processSample(out[0] / 9.81f, out[1] / 9.81f);
                    } else {
                        thermalDetector.processSample(ax, ay);
                    }
                }
                // 50 Гц лог во время полёта — каждый сэмпл акселерометра
                if (isLogging) {
                    long now = System.currentTimeMillis();
                    long dtMs = now - flightStartMs;
                    logBuffer.append(dtMs).append(",");
                    logBuffer.append(ax * 1000f).append(",");  // X (mG)
                    logBuffer.append(ay * 1000f).append(",");  // Y (mG)
                    logBuffer.append(az * 1000f).append(",");  // Z (mG)
                    logBuffer.append(latestGyroX * 1000f).append(","); // gyro X (mdps)
                    logBuffer.append(latestGyroY * 1000f).append(","); // gyro Y (mdps)
                    logBuffer.append(latestGyroZ * 1000f).append(","); // gyro Z (mdps)
                    logBuffer.append(latestMagX).append(",");   // mag X (μT)
                    logBuffer.append(latestMagY).append(",");   // mag Y (μT)
                    logBuffer.append(latestMagZ).append(",");   // mag Z (μT)
                    logBuffer.append(latestPressure).append(","); // pressure (hPa)
                    logBuffer.append(pitch).append(",");          // pitch (rad)
                    logBuffer.append(roll).append(",");           // roll (rad)
                    logBuffer.append(getLogHeading()).append(","); // heading (°, fallback to GPS)
                    logBuffer.append(gpsSpeed).append(",");       // GPS speed (m/s)
                    logBuffer.append(gpsHeading);                 // GPS heading (°)
                    // Thermal blip info
                    appendThermalLog(logBuffer);
                    logBuffer.append("\n");
                }
                break;
            }
            case Sensor.TYPE_ACCELEROMETER: {
                // GRAVITY — с гравитацией, только для компаса
                gravityAccelX = event.values[0];
                gravityAccelY = event.values[1];
                gravityAccelZ = event.values[2];
                hasGravityAccel = true;

                // Если LINEAR_ACCEL недоступен — используем ACCELEROMETER и для детектора
                if (!usingLinearAccel && thermalDetector != null && !simMode) {
                    // Поворот из осей телефона в мировые
                    if (compassReady) {
                        float[] in = event.values;
                        float[] out = new float[3];
                        out[0] = rotationMatrix[0] * in[0] + rotationMatrix[1] * in[1] + rotationMatrix[2] * in[2];
                        out[1] = rotationMatrix[3] * in[0] + rotationMatrix[4] * in[1] + rotationMatrix[5] * in[2];
                        thermalDetector.processSample(out[0] / 9.81f, out[1] / 9.81f);
                    } else {
                        float gx = gravityAccelX / 9.81f;
                        float gy = gravityAccelY / 9.81f;
                        thermalDetector.processSample(gx, gy);
                    }
                }
                break;
            }
            case Sensor.TYPE_GYROSCOPE: {
                latestGyroX = event.values[0];
                latestGyroY = event.values[1];
                latestGyroZ = event.values[2];
                break;
            }
            case Sensor.TYPE_MAGNETIC_FIELD: {
                latestMagX = event.values[0];
                latestMagY = event.values[1];
                latestMagZ = event.values[2];
                updateCompass();
                break;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    // ========================================================================
    // Compass — использует gravityAccel (TYPE_ACCELEROMETER, с гравитацией)
    // ========================================================================

    private void updateCompass() {
        if (!hasGravityAccel) {
            return;
        }
        float[] grav = new float[3];
        float[] geom = new float[3];
        grav[0] = gravityAccelX;
        grav[1] = gravityAccelY;
        grav[2] = gravityAccelZ;
        geom[0] = latestMagX;
        geom[1] = latestMagY;
        geom[2] = latestMagZ;

        if (SensorManager.getRotationMatrix(rotationMatrix, null, grav, geom)) {
            SensorManager.getOrientation(rotationMatrix, orientationVals);
            float newHeading = (float) Math.toDegrees(orientationVals[0]);
            rawHeading = newHeading;
            pitch = orientationVals[1];
            roll = orientationVals[2];
            if (newHeading < 0.0f) {
                newHeading += 360.0f;
            }
            if (!compassReady) {
                heading = newHeading;
                compassReady = true;
            } else {
                float diff = newHeading - heading;
                if (diff > 180f) diff -= 360f;
                else if (diff < -180f) diff += 360f;
                heading = (heading + diff * 0.3f + 360f) % 360f;
            }
        } else if (magnetometer == null) {
            // Нет магнитометра — pitch/roll из гравитации, heading из GPS
            pitch = (float) Math.atan2(-gravityAccelX,
                    Math.sqrt(gravityAccelY * gravityAccelY + gravityAccelZ * gravityAccelZ));
            roll = (float) Math.atan2(gravityAccelY, gravityAccelZ);
            rawHeading = gpsHeading;
            if (gpsReady) {
                compassReady = true;
            }
        } else {
            // Магнитометр есть, но getRotationMatrix вернул false (магнитная интерференция)
            // Не используем старую матрицу — сбрасываем compassReady
            compassReady = false;
            pitch = (float) Math.atan2(-gravityAccelX,
                    Math.sqrt(gravityAccelY * gravityAccelY + gravityAccelZ * gravityAccelZ));
            roll = (float) Math.atan2(gravityAccelY, gravityAccelZ);
        }
    }

    private float getCompassHeading() {
        if (compassReady) {
            return heading;
        }
        if (gpsReady) {
            return gpsHeading;
        }
        return 0.0f;
    }

    /** For logging: heading value with null-safety when mag missing */
    private float getLogHeading() {
        if (magnetometer != null && compassReady) {
            return rawHeading;
        }
        return gpsHeading; // fallback to GPS
    }

    // ========================================================================
    // Pressure-based variometer
    // ========================================================================

    private void onPressureSample() {
        float p = latestPressure;
        if (p < 300f || p > 1100f) return;

        // Калибровка: первые 50 сэмплов — baseline
        if (baroCalibCount < BARO_CALIB_SAMPLES) {
            baselinePressure += (p - baselinePressure) / (baroCalibCount + 1);
            baroCalibCount++;
            return;
        }

        // Барометрическая высота: h = 44330.77 * (1 - (p/p0)^0.190263)
        float ratio = p / baselinePressure;
        altRaw = 44330.7692307692f * (1.0f - (float) Math.pow(ratio, 0.190263072286998f));

        // Vario = производная: (-curr + prev) * 1000 / dt_ms
        long nowMs = System.currentTimeMillis();
        if (baroCalibCount == BARO_CALIB_SAMPLES) {
            altFiltered = altRaw;
            prevAltRaw = altRaw;
            lastBaroMs = nowMs;
            baroCalibCount++;
            return;
        }
        // EMA на высоте (только для HPF)
        float alpha = getVarioAlpha();
        altFiltered = alpha * altFiltered + (1f - alpha) * altRaw;

        // rawVario = (altRaw - prevAltRaw) * 1000 / dtMs
        long dtMs = nowMs - lastBaroMs;
        lastBaroMs = nowMs;
        if (dtMs > 1) {
            float rawVario = (altRaw - prevAltRaw) * 1000f / (float) dtMs;
            prevAltRaw = altRaw;

            // Добавляем в кольцевой буфер
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
            if (Math.abs(vario) < 0.05f) vario = 0f;
        }

        // High-pass для RMS (оставлено для совместимости)
        float hp = altRaw - altFiltered;
        hpBuf[hpBufIdx] = hp;
        hpBufIdx = (hpBufIdx + 1) & 63;
        if (hpBufFill < 64) hpBufFill++;
        // RMS каждые 64 сэмпла
        if (hpBufFill >= 64) {
            float sum = 0.0f;
            for (int i = 0; i < 64; i++) {
                sum += hpBuf[i] * hpBuf[i];
            }
            float rms = (float) Math.sqrt(sum / 64.0f);
            recentSnr = rms / 3.5f; // noiseFloor fixed at 3.5
            if (recentSnr > maxSnr) {
                maxSnr = recentSnr;
            }

            processSample();
            updateStatus();
        }
    }

    // ========================================================================
    // Adaptive vario alpha (based on Vario15 KF approach)
    // ========================================================================

    /** Compute EMA alpha based on smoothing samples [20..200] */
    private float getVarioAlpha() {
        // alpha = 1 - 1/N where N = smoothing samples [5..100]
        int n = Math.max(5, Math.min(100, varioSmoothSamples));
        float alpha = 1f - 1f / n;

        // Adaptive: turbulence reduces alpha (faster response, max -20 samples)
        if (thermalDetector != null) {
            float turb = thermalDetector.getSignalProcessor().getTurbulenceLevel();
            // turb 0..0.5g → reduce samples by 0..40
            int reduction = (int)(turb * 80f);
            reduction = Math.min(reduction, n / 2);
            n = n - reduction;
            alpha = 1f - 1f / n;
        }

        // Принудительное ограничение
        return Math.max(0.9f, Math.min(0.9999f, alpha));
    }

    // ========================================================================
    // Process sample (logging, sound)
    // ========================================================================

    private void processSample() {
        if (varioSoundManager != null) {
            varioSoundManager.update(vario);
        }

        // Flight auto-log state machine (vario-based)
        if (flightLogEnabled && !testMode && !simMode) {
            handleFlightLog();
        }

        // Periodic flush to disk (auto-log mode) — в фоновый поток
        if (flightLogEnabled && isLogging) {
            long now = System.currentTimeMillis();
            if (now - lastFlushMs > FLUSH_INTERVAL_MS) {
                lastFlushMs = now;
                if (ioHandler != null) {
                    ioHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            flushLogBuffer();
                            flushGpsTrack();
                        }
                    });
                }
            }
        }

        // Main sensor log — в реальном режиме пишется 50 Гц в onSensorChanged(TYPE_LINEAR_ACCEL)
        if (simMode && isLogging) {
            sampleCount++;
            long now = System.currentTimeMillis();
            long dtMs = now - flightStartMs;
            logBuffer.append(dtMs).append(",");
            logBuffer.append(latestAccelX * 1000f).append(",");
            logBuffer.append(latestAccelY * 1000f).append(",");
            logBuffer.append(latestAccelZ * 1000f).append(",");
            logBuffer.append(latestGyroX * 1000f).append(",");
            logBuffer.append(latestGyroY * 1000f).append(",");
            logBuffer.append(latestGyroZ * 1000f).append(",");
            logBuffer.append(latestMagX).append(",");
            logBuffer.append(latestMagY).append(",");
            logBuffer.append(latestMagZ).append(",");
            logBuffer.append(latestPressure).append(",");
            logBuffer.append(pitch).append(",");
            logBuffer.append(roll).append(",");
            logBuffer.append(getLogHeading()).append(",");
            logBuffer.append(gpsSpeed).append(",");
            logBuffer.append(gpsHeading);
            // Thermal blip info (что видит пилот)
            appendThermalLog(logBuffer);
            logBuffer.append("\n");
        }

        // GPS track logging (1 Hz, into separate buffer)
        if (gpsTracking && isLogging) {
            long now = System.currentTimeMillis();
            if (now - lastGpsLogMs > 1000) {
                lastGpsLogMs = now;
                long dtMs = now - flightStartMs;
                gpsTrackBuffer.append(dtMs).append(",");
                gpsTrackBuffer.append(gpsLat).append(",");
                gpsTrackBuffer.append(gpsLon).append(",");
                gpsTrackBuffer.append(gpsAltitude).append(",");
                gpsTrackBuffer.append(gpsSpeed).append(",");
                gpsTrackBuffer.append(gpsHeading).append("\n");
            }
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
                if (!it.next().isAlive(now)) {
                    it.remove();
                }
            }
            thermals.add(new ThermalBlip(angle, strength, distance, source, now));
            while (thermals.size() > THERMAL_LIMIT) {
                thermals.remove(0);
            }
        }
    }

    // ========================================================================
    // Status update
    // ========================================================================

    private void updateStatus() {
        if (simMode) {
            if (thermalDetector != null) {
                currentStatus = thermalDetector.getStatusText();
            } else {
                currentStatus = UiManager.STATUS_SEARCH;
            }
            return;
        }

        if (thermalDetector == null) {
            currentStatus = UiManager.STATUS_SEARCH;
            return;
        }

        float level = thermalDetector.getSignalProcessor().getTurbulenceMs2();
        float snr = thermalDetector.getSignalProcessor().getSnr();

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
    // Logging (CSV)
    // ========================================================================

    private void startLogging() {
        if (isLogging) return;
        isLogging = true;
        flightStartMs = System.currentTimeMillis();
        sampleCount = 0;
        logBuffer.setLength(0);

        // Metadata header for flight auto-log
        if (flightLogEnabled) {
            logBuffer.append("# TERMO1 Flight Log\n");
            logBuffer.append("# Device: ").append(android.os.Build.MANUFACTURER)
                    .append(" ").append(android.os.Build.MODEL).append("\n");
            logBuffer.append("# Android: ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            logBuffer.append("# Sensors: ACCEL=").append(accelerometerGravity!=null)
                    .append(" LINEAR_ACCEL=").append(accelerometerLinear!=null && usingLinearAccel)
                    .append(" BARO=").append(barometer!=null)
                    .append(" GYRO=").append(gyroscope!=null)
                    .append(" MAG=").append(magnetometer!=null).append("\n");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            logBuffer.append("# Date: ").append(sdf.format(new java.util.Date())).append("\n");
            logBuffer.append("# Columns: dtMs,ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,gpsSpeed,gpsHeading,thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor\n");
        }

        // Column header line (not commented — parsers read it)
        logBuffer.append("dtMs,ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,gpsSpeed,gpsHeading,thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor\n");
    }

    /** Добавить в StringBuilder информацию о текущем thermal blip (что видит пилот). */
    private void appendThermalLog(StringBuilder sb) {
        if (thermalDetector != null) {
            com.termo1.radar.model.ThermalBlip blip = thermalDetector.getCurrentBlip();
            if (blip != null) {
                sb.append(",").append(blip.angle);
                sb.append(",").append(String.format(java.util.Locale.US, "%.1f", blip.strength));
                sb.append(",").append((int) blip.distance);
                sb.append(",").append(blip.source);
                // SNR и noiseFloor
                sb.append(",").append(String.format(java.util.Locale.US, "%.2f", thermalDetector.getSignalProcessor().getSnr()));
                sb.append(",").append(String.format(java.util.Locale.US, "%.4f", thermalDetector.getSignalProcessor().getNoiseFloor()));
                return;
            }
        }
        // 4 пустых поля: angle,strength,dist,source + snr,noiseFloor = 6 полей
        sb.append(",");
        sb.append(",");
        sb.append(",");
        sb.append(",");
        sb.append(",");
        sb.append(",");
    }

    private void stopLogging() {
        if (!isLogging) return;
        isLogging = false;
        if (logBuffer.length() > 0) {
            try {
                java.io.File extDir = getExternalFilesDir(null);
                if (extDir == null) {
                    android.util.Log.e("TERMO1", "External storage not available, cannot save log");
                    return;
                }
                java.io.File logDir = new java.io.File(extDir, "logs");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                                java.util.Locale.US);
                String fileName;
                if (testMode) {
                    fileName = "test_" + sdf.format(new java.util.Date()) + ".csv";
                } else {
                    fileName = "flight_" + sdf.format(new java.util.Date()) + ".csv";
                }
                java.io.File logFile = new java.io.File(logDir, fileName);
                java.io.FileWriter fw = new java.io.FileWriter(logFile);
                fw.write(logBuffer.toString());
                fw.close();
                android.util.Log.i("TERMO1", "Log saved: " + logFile.getAbsolutePath());
                // Сжатие в ZIP — экономия места в 5-7 раз
                zipLogFile(logFile);
            } catch (java.io.IOException e) {
                android.util.Log.e("TERMO1", "Failed to write log file", e);
            }
        }
        logBuffer.setLength(0);
    }

    /** Сжать CSV в ZIP и удалить оригинал. */
    private void zipLogFile(java.io.File csvFile) {
        if (csvFile == null || !csvFile.exists() || csvFile.length() < 100) return;
        try {
            java.io.File zipFile = new java.io.File(csvFile.getParent(),
                    csvFile.getName().replace(".csv", ".zip"));
            java.io.FileInputStream fis = new java.io.FileInputStream(csvFile);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
            zos.setLevel(9); // максимальное сжатие
            zos.putNextEntry(new java.util.zip.ZipEntry(csvFile.getName()));
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                zos.write(buf, 0, len);
            }
            zos.closeEntry();
            zos.close();
            fis.close();
            long saved = csvFile.length() - zipFile.length();
            android.util.Log.i("TERMO1", "ZIP: " + csvFile.getName()
                    + " " + csvFile.length()/1024 + "K → " + zipFile.length()/1024 + "K"
                    + " (экономия " + (saved * 100 / csvFile.length()) + "%)");
            csvFile.delete();
        } catch (java.io.IOException e) {
            android.util.Log.e("TERMO1", "ZIP failed for " + csvFile.getName(), e);
        }
    }
    // ---- Flight auto-log (vario-based, for unattended pilot logging) ----
    private static final int FLIGHT_LOG_IDLE = 0;
    private static final int FLIGHT_LOG_ACTIVE = 1;
    private static final int FLIGHT_LOG_PAUSED = 2;

    private void handleFlightLog() {
        long now = System.currentTimeMillis();
        float absVario = (barometer != null) ? Math.abs(vario) : 0f;
        // Fallback при отсутствии барометра: турбулентность > 0.05g = полёт
        float turbLevel = (thermalDetector != null)
                ? thermalDetector.getSignalProcessor().getTurbulenceLevel() : 0f;
        float speed = gpsSpeed;
        boolean inFlight = absVario > 1.0f || speed > 3.0f || turbLevel > 0.05f;
        boolean onGround = absVario < 0.2f && speed < 1.0f && turbLevel < 0.02f;

        switch (flightLogState) {
            case FLIGHT_LOG_IDLE:
                if (inFlight && !manualStopRequested) {
                    flightLogState = FLIGHT_LOG_ACTIVE;
                    flightLogLastActiveMs = now;
                    // Первый запуск — открываем файл
                    if (!isLogging) {
                        startLogging();
                    }
                    logBuffer.append(now - flightStartMs)
                            .append(",FLIGHT_START,vario=").append(vario)
                            .append(" speed=").append(speed).append("\n");
                }
                break;

            case FLIGHT_LOG_ACTIVE:
                if (!onGround) {
                    flightLogLastActiveMs = now;
                }
                if (now - flightLogLastActiveMs > FLIGHT_LOG_TIMEOUT_MS) {
                    flightLogState = FLIGHT_LOG_PAUSED;
                    logBuffer.append(now - flightStartMs)
                            .append(",FLIGHT_PAUSE,idle_5min vario=").append(vario)
                            .append(" speed=").append(speed).append("\n");
                    // Flush to disk but keep file open
                    flushLogBuffer();
                }
                break;

            case FLIGHT_LOG_PAUSED:
                if (inFlight && !manualStopRequested) {
                    flightLogState = FLIGHT_LOG_ACTIVE;
                    flightLogLastActiveMs = now;
                    logBuffer.append(now - flightStartMs)
                            .append(",FLIGHT_RESUME,vario=").append(vario)
                            .append(" speed=").append(speed).append("\n");
                }
                break;
        }
    }

    private java.io.FileWriter flightLogWriter = null;
    private String flightLogFileName = null;       // одно имя файла на сессию
    private long lastFlushMs = 0;
    private static final long FLUSH_INTERVAL_MS = 30000; // flush every 30s

    /** Periodically flush log buffer to disk file (keeps file open) */
    private void flushLogBuffer() {
        if (logBuffer.length() == 0) return;
        try {
            if (flightLogWriter == null) {
                java.io.File extDir = getExternalFilesDir(null);
                if (extDir == null) {
                    android.util.Log.e("TERMO1", "External storage not available, cannot flush log");
                    return;
                }
                java.io.File logDir = new java.io.File(extDir, "logs");
                if (!logDir.exists()) logDir.mkdirs();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.US);
                if (flightLogFileName == null) {
                    flightLogFileName = "flight_" + sdf.format(new java.util.Date()) + ".csv";
                }
                flightLogWriter = new java.io.FileWriter(
                        new java.io.File(logDir, flightLogFileName), true);
            }
            flightLogWriter.write(logBuffer.toString());
            flightLogWriter.flush();
            logBuffer.setLength(0);
        } catch (java.io.IOException e) {
            android.util.Log.e("TERMO1", "Flush log failed", e);
        }
    }

    private void flushGpsTrack() {
        if (gpsTrackBuffer.length() == 0) return;
        try {
            if (gpsWriter == null) {
                java.io.File extDir = getExternalFilesDir(null);
                if (extDir == null) {
                    android.util.Log.e("TERMO1", "External storage not available, cannot flush GPS");
                    return;
                }
                java.io.File logDir = new java.io.File(extDir, "logs");
                if (!logDir.exists()) logDir.mkdirs();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.US);
                String fileName = "gps_" + sdf.format(new java.util.Date()) + ".csv";
                gpsWriter = new java.io.FileWriter(
                        new java.io.File(logDir, fileName), true);
            }
            gpsWriter.write(gpsTrackBuffer.toString());
            gpsWriter.flush();
            gpsTrackBuffer.setLength(0);
        } catch (java.io.IOException e) {
            android.util.Log.e("TERMO1", "Flush GPS failed", e);
        }
    }

    private void startGpsTrack() {
        gpsTracking = true;
        gpsTrackBuffer.setLength(0);
        gpsTrackBuffer.append("dtMs,lat,lon,altM,speed,gpsHeading\n");
        lastGpsLogMs = System.currentTimeMillis();
    }

    private void stopFlightLog() {
        // Write end marker
        if (isLogging) {
            logBuffer.append(System.currentTimeMillis() - flightStartMs)
                    .append(",FLIGHT_SESSION_END\n");
        }

        // Final flush of main log
        flushLogBuffer();
        // Close FileWriter
        if (flightLogWriter != null) {
            try {
                flightLogWriter.close();
            } catch (java.io.IOException e) {
                android.util.Log.e("TERMO1", "Close log failed", e);
            }
            flightLogWriter = null;
        }

        // Save any remaining buffer (from non-flushed writes)
        if (logBuffer.length() > 0) {
            try {
                java.io.File logDir = new java.io.File(
                        getExternalFilesDir(null), "logs");
                if (!logDir.exists()) logDir.mkdirs();
                java.io.FileWriter fw = new java.io.FileWriter(
                        new java.io.File(logDir, "flight_remainder.csv"), true);
                fw.write(logBuffer.toString());
                fw.close();
            } catch (java.io.IOException e) {
                android.util.Log.e("TERMO1", "Save remainder failed", e);
            }
            logBuffer.setLength(0);
        }

        // Flush and close GPS track
        flushGpsTrack();
        if (gpsWriter != null) {
            try {
                gpsWriter.close();
            } catch (java.io.IOException e) {
                android.util.Log.e("TERMO1", "Close GPS writer failed", e);
            }
            gpsWriter = null;
        }

        isLogging = false;
        gpsTracking = false;
        flightLogState = FLIGHT_LOG_IDLE;
        flightLogEnabled = false;
    }

    // ========================================================================
    // GPS
    // ========================================================================

    private void startGps() {
        try {
            if (locationManager != null) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1.0f,
                        this);
            }
        } catch (SecurityException e) {
            // Permission not granted
        }
    }

    private void stopGps() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException e) {
                // Ignore
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onLocationChanged(Location location) {
        gpsSpeed = location.hasSpeed() ? location.getSpeed() : 0.0f;
        gpsHeading = location.hasBearing() ? location.getBearing() : 0.0f;
        gpsReady = true;
        gpsLat = location.getLatitude();
        gpsLon = location.getLongitude();
        if (location.hasAltitude()) {
            gpsAltitude = (float) location.getAltitude();
            if (!altitudeInitialized) {
                startAltitude = gpsAltitude;
                altitudeInitialized = true;
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}

    // ========================================================================
    // WakeLock
    // ========================================================================

    private static final long WAKE_LOCK_TIMEOUT_MS = 3600000L; // 1 час
    private long lastWakeLockRefreshMs = 0;

    private void acquireWakeLock() {
        if (powerManager != null && wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TERMO1:RadarLock");
            if (wakeLock != null) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS); // таймаут — если краш, освободится за час
                lastWakeLockRefreshMs = System.currentTimeMillis();
            }
        }
    }

    /** Перезахват WakeLock с новым таймаутом — вызывать каждые 30 мин */
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
                if (!simMode || simulation == null) {
                    return;
                }
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

                latestPressure = simulation.getPressure();
                gpsSpeed = simulation.getSpeed();
                gpsHeading = simulation.getHeading();
                float newSimHeading = simulation.getHeading();
                heading = newSimHeading;
                compassReady = true;
                gpsReady = true;
                vario = simulation.getVario();

                gpsAltitude = simulation.getAltitudeMsl();
                if (!altitudeInitialized) {
                    startAltitude = gpsAltitude;
                    altitudeInitialized = true;
                }
                flightStartMs = simStartMs;

                // Пропускаем синтезированный сигнал через реальный детектор
                if (thermalDetector != null) {
                    thermalDetector.processSample(simulation.getAccelX(), simulation.getAccelY());
                    recentSnr = thermalDetector.getSignalProcessor().getSnr();
                    maxSnr = Math.max(maxSnr, recentSnr);

                    // Звук при появлении blip
                    if (thermalDetector.isBlipConfirmed()) {
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastThermalBeepMs > 3000) {
                            if (varioSoundManager != null) {
                                varioSoundManager.playThermalBeep();
                            }
                            lastThermalBeepMs = nowMs;
                        }
                    }
                }

                updateStatus();
                processSample();

                simHandler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        };
        simHandler.postDelayed(simTask, SAMPLE_INTERVAL_MS);
    }

    // ========================================================================
    // Test mode — пошаговое тестирование с real-time обратной связью
    // ========================================================================

    // Пороги для анализа движения (в g) — для нового DC-blocker + RMS (сигнал ~160x сильнее)
    private static final float TEST_AMP_THRESHOLD = 0.05f;     // мин амплитуда RMS
    private static final float TEST_STRONG_THRESHOLD = 0.15f;  // сильное движение
    private static final float TEST_AXIS_RATIO = 2.5f;         // преобладание оси (X vs Y)
    private static final float TEST_HEADING_RANGE = 50f;       // мин диапазон heading (°)
    private static final float TEST_FAST_RMS = 0.3f;           // RMS для быстрой тряски (g)
    private static final float TEST_CORRECT_RATIO = 0.4f;      // 40% фреймов в окне должны быть правильными
    private static final int TEST_MAX_STEP_MS = 60000;         // макс 1 мин на шаг
    private static final int TEST_CORRECT_WINDOW_MS = 1500;    // окно: 1.5 сек

    // Скользящее окно для определения правильности (не сбрасывается при одиночных ошибках)
    private static final int TEST_WINDOW_FRAMES = 45; // 1.5 сек при 30 FPS
    private final boolean[] testWindowBuffer = new boolean[TEST_WINDOW_FRAMES];
    private int testWindowIdx = 0;
    private int testWindowFill = 0;

    private int testCorrectCount = 0;
    private long testFeedbackLastUpdate;

    private void startTestMode() {
        testMode = true;
        testStep = 0;
        testStepStartMs = System.currentTimeMillis();
        testCorrectCount = 0;
        testStepCorrect = false;
        testFeedback = "";
        testFeedbackColor = android.graphics.Color.argb(255, 255, 255, 255);
        testCorrectStartMs = 0;
        testLastBeepMs = 0;
        startLogging();
        nextTestStep();
    }

    private void nextTestStep() {
        testStep++;
        testStepStartMs = System.currentTimeMillis();
        testCorrectCount = 0;
        testWindowIdx = 0;
        testWindowFill = 0;
        headingWindowIdx = 0;
        headingWindowFill = 0;
        testStepCorrect = false;
        testCorrectStartMs = 0;
        testFeedback = "";

        switch (testStep) {
            case 1:
                setTestInstruction("ШАГ 1/6: КАЛИБРОВКА",
                        "Положите телефон НЕПОДВИЖНО на стол",
                        "Датчики калибруются... 5 секунд");
                testLogLine("STEP1_CALIBRATION");
                break;
            case 2:
                setTestInstruction("ШАГ 2/6: ОСЬ X (ВЛЕВО-ВПРАВО)",
                        "Качайте телефон строго ВЛЕВО-ВПРАВО",
                        "Амплитуда: ±3-5 см | Частота: 1-2 Гц");
                testLogLine("STEP2_START: X axis, amp=3-5cm, freq=1-2Hz");
                break;
            case 3:
                setTestInstruction("ШАГ 3/6: ОСЬ Y (ВПЕРЁД-НАЗАД)",
                        "Качайте телефон строго ВПЕРЁД-НАЗАД",
                        "Амплитуда: ±3-5 см | Частота: 1-2 Гц");
                testLogLine("STEP3_START: Y axis, amp=3-5cm, freq=1-2Hz");
                break;
            case 4:
                setTestInstruction("ШАГ 4/6: ПОВОРОТ (YAW)",
                        "Поверните телефон на 90° ВЛЕВО",
                        "Затем обратно — проверка компаса");
                testLogLine("STEP4_START: rotate 90° left, check compass");
                break;
            case 5:
                setTestInstruction("ШАГ 5/6: СПИРАЛЬ",
                        "Крутите телефон плавно на 360°",
                        "Крен ±30° | 1 оборот за 5-7 секунд");
                testLogLine("STEP5_START: spiral 360°, roll 30°");
                break;
            case 6:
                setTestInstruction("ШАГ 6/6: ТРЯСКА",
                        "Трясите телефон БЫСТРО",
                        "Частота: 3-5 Гц | Амплитуда ±5 см");
                testLogLine("STEP6_START: fast shake, 3-5Hz, amp=±5cm");
                break;
            case 7:
                // Тест завершён
                testMode = false;
                stopLogging();
                showTestCompleteDialog();
                return;
            default:
                testMode = false;
                stopLogging();
                return;
        }
    }

    private void setTestInstruction(String title, String line1, String line2) {
        testInstruction = title + "\n" + line1 + "\n" + line2;
    }

    /**
     * Called from onDraw (30 FPS). Analyzes current sensor data
     * for the current test step and gives real-time feedback.
     */
    private void updateTestFeedback() {
        if (!testMode || testStepCorrect || thermalDetector == null) return;

        SignalProcessor sp = thermalDetector.getSignalProcessor();
        float hpX = sp.getBpX();
        float hpY = sp.getBpY();
        float level = sp.getTurbulenceLevel(); // в g
        float levelMs2 = level * 9.81f;
        float headingDiff = 0f;
        float headingNow = getCompassHeading();

        long now = System.currentTimeMillis();
        long stepElapsed = now - testStepStartMs;

        // Auto-advance if step takes too long (>60s)
        if (stepElapsed > TEST_MAX_STEP_MS) {
            testFeedbackColor = android.graphics.Color.argb(255, 255, 100, 100);
            testFeedback = "⏱ Время вышло. Переход к следующему шагу...";
            nextTestStep();
            return;
        }

        // Time remaining display
        int secLeft = (int)((TEST_MAX_STEP_MS - stepElapsed) / 1000);
        String timeStr = "Осталось: " + secLeft + "с";

        boolean correct = false;
        String feedback = "";
        int color = android.graphics.Color.argb(255, 255, 255, 200);

        switch (testStep) {
            case 1: {
                // Калибровка: ждём 5 секунд
                if (stepElapsed > 5000) {
                    correct = true;
                    feedback = "✅ Калибровка завершена";
                    color = android.graphics.Color.argb(255, 0, 255, 0);
                } else {
                    feedback = "⏳ Калибровка... " + (stepElapsed / 1000 + 1) + "/5 с";
                    color = android.graphics.Color.argb(255, 200, 200, 200);
                }
                break;
            }

            case 2: {
                // X axis shake с динамическими рекомендациями
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                boolean frameCorrect = absX > TEST_AMP_THRESHOLD && absY < absX / TEST_AXIS_RATIO;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();
                float mixRatio = (absX + absY > 0.001f) ? absX / (absX + absY) : 0;

                if (windowRatio > TEST_CORRECT_RATIO) {
                    correct = (stepElapsed > 5000);
                    if (correct) {
                        feedback = "✅ ОСЬ X: отлично! X/Y=" + String.format("%.1f", mixRatio);
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "⬅️➡️ Продолжайте качать влево-вправо";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    }
                } else if (absY > absX * 1.5f && absY > 0.002f) {
                    feedback = "❌ Качайте ВЛЕВО-ВПРАВО, а не вперёд-назад!";
                    color = android.graphics.Color.argb(255, 255, 100, 100);
                } else if (absX < 0.001f) {
                    feedback = "⏳ Качайте СИЛЬНЕЕ! Амплитуда ±3-5 см, частота 1-2 Гц";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                } else if (absX < TEST_AMP_THRESHOLD) {
                    feedback = "⬆️ Увеличьте амплитуду, качайте размашистее!";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "⬅️➡️ Хорошо, держите ритм!";
                    color = android.graphics.Color.argb(255, 0, 255, 0);
                }
                feedback += " | X:" + String.format("%.0f", absX*1000) + "mG Y:" + String.format("%.0f", absY*1000) + "mG " + timeStr;
                break;
            }

            case 3: {
                // Y axis shake с динамическими рекомендациями
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                boolean frameCorrect = absY > TEST_AMP_THRESHOLD && absX < absY / TEST_AXIS_RATIO;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO) {
                    correct = (stepElapsed > 5000);
                    if (correct) {
                        feedback = "✅ ОСЬ Y: отлично!";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "↕️ Продолжайте качать вперёд-назад";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    }
                } else if (absX > absY * 1.5f && absX > 0.002f) {
                    feedback = "❌ Качайте ВПЕРЁД-НАЗАД, не в стороны!";
                    color = android.graphics.Color.argb(255, 255, 100, 100);
                } else if (absY < 0.001f) {
                    feedback = "⏳ Качайте СИЛЬНЕЕ по оси Y! Амплитуда ±3-5 см";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                } else if (absY < TEST_AMP_THRESHOLD) {
                    feedback = "⬆️ Добавьте амплитуды, качайте размашистее!";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "↕️ Хорошо, продолжайте!";
                    color = android.graphics.Color.argb(255, 0, 255, 0);
                }
                feedback += " | X:" + String.format("%.0f", absX*1000) + "mG Y:" + String.format("%.0f", absY*1000) + "mG " + timeStr;
                break;
            }

            case 4: {
                // Yaw rotation
                if (!compassReady) {
                    feedback = "⏳ Компас калибруется...";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                    break;
                }
                updateHeadingWindow(headingNow);
                float headingRange = getHeadingRange();
                boolean frameCorrect = headingRange > 50f;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO) {
                    correct = true;
                    feedback = "✅ Компас работает! Размах " + String.format("%.0f°", headingRange);
                    color = android.graphics.Color.argb(255, 0, 255, 0);
                } else if (headingRange > 30f) {
                    feedback = "🔄 Ещё поворот! Нужно не менее 50° разворота";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else if (headingRange > 10f) {
                    feedback = "🔄 Поверните телефон на 90° влево (больше! h=" + String.format("%.0f°", headingNow) + ")";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "🔄 Поверните телефон на 90° ВЛЕВО от текущего положения!";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                }
                feedback += " | range=" + String.format("%.0f°", headingRange) + " " + timeStr;
                break;
            }

            case 5: {
                // Spiral: both axes active + heading changing
                float absX = Math.abs(hpX);
                float absY = Math.abs(hpY);
                float bothActive = absX + absY;
                updateHeadingWindow(headingNow);
                float headingRange = getHeadingRange();

                boolean frameCorrect = bothActive > TEST_AMP_THRESHOLD && headingRange > 20f;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO) {
                    correct = (stepElapsed > 7000);
                    if (correct) {
                        feedback = "✅ СПИРАЛЬ: отлично! (" + String.format("%.0f", bothActive*9.81f*100) + " см/с²)";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "🌀 Крутите + трясите, уже хорошо";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    }
                } else if (bothActive > TEST_AMP_THRESHOLD && headingRange < 10f) {
                    feedback = "🌀 Крутите телефон (поворачивайте!), не только трясите!";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else if (headingRange > 20f && bothActive < TEST_AMP_THRESHOLD) {
                    feedback = "🌀 Трясите активнее во время поворота!";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "🌀 Трясите + крутите одновременно!";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                }
                feedback += " " + timeStr;
                break;
            }

            case 6: {
                // Fast shake — динамические рекомендации
                boolean frameCorrect = level > TEST_STRONG_THRESHOLD;
                addToWindow(frameCorrect);
                float windowRatio = getWindowCorrectRatio();

                if (windowRatio > TEST_CORRECT_RATIO) {
                    correct = (stepElapsed > 3000);
                    if (correct) {
                        feedback = "✅ ТРЯСКА: SNR=" + String.format("%.1f", sp.getSnr());
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    } else {
                        feedback = "💨 Продолжайте трясти! " + String.format("%.0f", levelMs2*100) + " см/с²";
                        color = android.graphics.Color.argb(255, 0, 255, 0);
                    }
                } else if (level > 0.01f) {
                    feedback = "💨 БЫСТРЕЕ! Нужно 3-5 Гц (качков в секунду)";
                    color = android.graphics.Color.argb(255, 255, 200, 0);
                } else {
                    feedback = "💨 Трясите БЫСТРЕЕ И СИЛЬНЕЕ! Частота 3-5 Гц";
                    color = android.graphics.Color.argb(200, 200, 200, 0);
                }
                feedback += " | " + String.format("%.0f%%", windowRatio*100) + " " + timeStr;
                break;
            }
        }

        // Update feedback text (rate-limited to ~10 FPS)
        if (now - testFeedbackLastUpdate > 100 || correct != testStepCorrect) {
            testFeedback = feedback;
            testFeedbackColor = color;
            testFeedbackLastUpdate = now;
        }

        // When step is done correctly: beep + thermal blip + advance
        if (correct && !testStepCorrect) {
            testStepCorrect = true;
            testLogLine("STEP" + testStep + "_CORRECT");
            // Play success beep (700 Hz × 5)
            if (varioSoundManager != null && (now - testLastBeepMs > 2000)) {
                varioSoundManager.playTestBeep();
                testLastBeepMs = now;
            }
            // Add thermal blip at direction of movement
            float angleDeg = sp.getStableDirDeg();
            if (angleDeg < 0) angleDeg += 360f;
            float strength = Math.min(sp.getSnr(), 8f);
            addThermal(angleDeg, strength, 30f, "test_step" + testStep);
            // Auto-advance after 2 seconds
            testHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (testMode) nextTestStep();
                }
            }, 2000);
        }

        // Log test data point once per second
        if (now - testLastBeepMs > 1000) {
            testLogLine("STEP" + testStep + "_DATA: level=" + String.format("%.4f", level)
                    + " hpX=" + String.format("%.4f", hpX)
                    + " hpY=" + String.format("%.4f", hpY)
                    + " heading=" + String.format("%.1f", headingNow)
                    + " correct=" + testStepCorrect);
            testLastBeepMs = now;
        }
    }

    private float lastHeading = 0f;

    /** Normdiff between two headings in degrees [0,180]. */
    private float headingChange(float a, float b) {
        float d = Math.abs(a - b);
        if (d > 180f) d = 360f - d;
        return d;
    }

    // ---- Sliding window for test feedback ----
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

    // ---- Heading window (min/max over 5s) ----
    private static final int HEADING_WINDOW_FRAMES = 150; // 5s at 30 FPS
    private final float[] headingWindow = new float[HEADING_WINDOW_FRAMES];
    private int headingWindowIdx = 0;
    private int headingWindowFill = 0;

    private void updateHeadingWindow(float heading) {
        headingWindow[headingWindowIdx] = heading;
        headingWindowIdx = (headingWindowIdx + 1) % HEADING_WINDOW_FRAMES;
        if (headingWindowFill < HEADING_WINDOW_FRAMES) headingWindowFill++;
    }

    private float getHeadingRange() {
        if (headingWindowFill < 10) return 0f;
        // Копируем и сортируем heading'и, ищем max разрыв → размах = 360 - max_gap
        float[] hdgs = new float[headingWindowFill];
        System.arraycopy(headingWindow, 0, hdgs, 0, headingWindowFill);
        java.util.Arrays.sort(hdgs);
        float maxGap = hdgs[0] + 360f - hdgs[hdgs.length - 1]; // разрыв через 0
        for (int i = 1; i < hdgs.length; i++) {
            float gap = hdgs[i] - hdgs[i - 1];
            if (gap > maxGap) maxGap = gap;
        }
        return 360f - maxGap; // размах = 360 - самый большой разрыв
    }

    private void stopTestMode() {
        testMode = false;
        if (testTask != null) {
            testHandler.removeCallbacks(testTask);
        }
    }

    private void testLogLine(String line) {
        long now = System.currentTimeMillis();
        long t = (flightStartMs > 0) ? (now - flightStartMs) : 0;
        String logLine = t + "," + line + "\n";
        if (isLogging) {
            logBuffer.append(logLine);
        }
        android.util.Log.i("TERMO1_TEST", line);
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

    public String getTestInstruction() {
        return testInstruction;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public String getTestFeedback() {
        return testFeedback;
    }

    public int getTestFeedbackColor() {
        return testFeedbackColor;
    }

    // ========================================================================
    // Calibration reset
    // ========================================================================

    public void resetCalibration() {
        baroCalibCount = 0;
        baselinePressure = latestPressure;
        hpBufFill = 0;
        recentSnr = 0f;
        if (thermalDetector != null) {
            thermalDetector.resetCalibration();
        }
        android.widget.Toast.makeText(this,
                "Калибровка сброшена. Ждите 2с...",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ========================================================================
    // Back button handler
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
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                finishAffinity();
                            }
                        })
                .setNegativeButton(getString(R.string.exit_dialog_no),
                        (DialogInterface.OnClickListener) null)
                .show();
    }

    private void showExitOnScreenDialog() {
        if (exitDialog != null && exitDialog.isShowing()) {
            return;
        }
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("Завершить TERMO1?")
                .setPositiveButton("Да",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                finishAffinity();
                            }
                        })
                .setNegativeButton("Нет",
                        (DialogInterface.OnClickListener) null)
                .show();
    }

    // ========================================================================
    // RadarView — inner class extending View
    // ========================================================================

    class RadarView extends View implements View.OnClickListener {

        // Gear button (top-right)
        private final Paint gearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF gearRect = new RectF();
        private final Paint gearInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gearToothPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Exit button (top-left)
        private final Paint exitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint exitXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF exitRect = new RectF();

        // SIM badge
        private final Paint simPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint simBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // === НАВИГАЦИОННЫЕ КНОПКИ ===
        // Калибровка (слева под варио), Старт (справа под варио)
        private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF calibBtnRect = new RectF();  // слева
        private final RectF startBtnRect = new RectF();  // справа

        // Тест (между высотами)
        private final Paint testBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint testBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF testBtnRect = new RectF();

        // Test instruction overlay
        private final Paint testOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint testTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint testBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Test border + progress bar (вынесены из onDraw — GC fix)
        private final Paint testBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint testBarBgPaint = new Paint();
        private final Paint testBarFillPaint = new Paint();

        private long lastAddedBlipBornMs = -1;  // для дедупликации blip в onDraw

        // Надпись "пишем лог" между кнопками (только при активной записи)
        private final Paint logLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Данные с датчиков под кнопкой калибровки
        private final Paint sensorDataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float touchX;
        private float touchY;

        public RadarView(Context context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            setOnClickListener(this);

            // Gear
            gearPaint.setStyle(Paint.Style.STROKE);
            gearPaint.setStrokeWidth(4);
            gearPaint.setColor(Color.argb(180, 255, 255, 255));
            gearInnerPaint.setStyle(Paint.Style.STROKE);
            gearInnerPaint.setStrokeWidth(3);
            gearInnerPaint.setColor(Color.argb(180, 255, 255, 255));
            gearToothPaint.setStyle(Paint.Style.FILL);
            gearToothPaint.setColor(Color.argb(180, 255, 255, 255));

            // Exit
            exitPaint.setStyle(Paint.Style.STROKE);
            exitPaint.setStrokeWidth(4);
            exitPaint.setColor(Color.argb(180, 255, 100, 100));
            exitXPaint.setStyle(Paint.Style.STROKE);
            exitXPaint.setStrokeWidth(5);
            exitXPaint.setColor(Color.argb(180, 255, 100, 100));
            exitXPaint.setStrokeCap(Paint.Cap.ROUND);

            // SIM badge
            simBgPaint.setStyle(Paint.Style.FILL);
            simBgPaint.setColor(Color.argb(80, 255, 193, 7));
            simPaint.setAntiAlias(true);
            simPaint.setTextSize(26);
            simPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            simPaint.setColor(Color.argb(220, 255, 193, 7));
            simPaint.setTextAlign(Paint.Align.LEFT);

            // Button text
            btnTextPaint.setAntiAlias(true);
            btnTextPaint.setColor(Color.argb(200, 0, 255, 0));
            btnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            btnTextPaint.setTextAlign(Paint.Align.CENTER);

            btnBgPaint.setStyle(Paint.Style.FILL);
            btnBgPaint.setColor(Color.argb(40, 0, 255, 0));
            btnBgPaint.setAntiAlias(true);

            // Test button
            testBtnBgPaint.setStyle(Paint.Style.FILL);
            testBtnBgPaint.setColor(Color.argb(50, 255, 193, 7));
            testBtnBgPaint.setAntiAlias(true);

            testBtnTextPaint.setAntiAlias(true);
            testBtnTextPaint.setColor(Color.argb(220, 255, 193, 7));
            testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            testBtnTextPaint.setTextAlign(Paint.Align.CENTER);

            // Test overlay
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

            // Log label
            logLabelPaint.setTextSize(24);
            logLabelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            logLabelPaint.setTextAlign(Paint.Align.CENTER);
            logLabelPaint.setColor(Color.argb(200, 33, 150, 243));

            // Sensor data paint — под кнопкой калибровки
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
                // Exit button (top-left) — 168px square
                exitRect.set(12, 12, 12 + 168, 12 + 168);
                // Gear button (top-right)
                float gearSize = 168f;
                gearRect.set(w - gearSize - 24, 24, w - 24, gearSize + 24);

                // Calibration button — left side, below vario
                float btnY = 260f + 100f; // below vario center
                float btnW = w * 0.28f;
                float btnH = 60f;
                calibBtnRect.set(w * 0.03f, btnY, w * 0.03f + btnW, btnY + btnH);
                startBtnRect.set(w * 0.97f - btnW, btnY, w * 0.97f, btnY + btnH);

                // Test button — between altitudes
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

            // Update vario sound at render rate
            if (varioSoundManager != null) {
                varioSoundManager.update(vario);
            }
            // Refresh WakeLock timeout (раз в 30 мин)
            refreshWakeLock();

            // --- Add/update thermal blip from real-time detector ---
            if (!simMode && thermalDetector != null && !testMode) {
                ThermalBlip detBlip = thermalDetector.getCurrentBlip();
                if (detBlip != null) {
                    long now = System.currentTimeMillis();
                    if (now - detBlip.bornMs < ThermalBlip.LIFE_MS) {
                        synchronized (thermalLock) {
                            // Сначала чистим мёртвые blip
                            Iterator<ThermalBlip> it = thermals.iterator();
                            while (it.hasNext()) {
                                if (!it.next().isAlive(now)) it.remove();
                            }
                            // Ищем blip с таким же bornMs — обновляем существующий
                            boolean found = false;
                            for (ThermalBlip tb : thermals) {
                                if (tb.bornMs == detBlip.bornMs) {
                                    // Обновляем координаты существующего blip (distance мог измениться)
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
                                while (thermals.size() > THERMAL_LIMIT) {
                                    thermals.remove(0);
                                }
                            }
                        }
                        if (now - lastThermalBeepMs > 3000) {
                            if (varioSoundManager != null) {
                                varioSoundManager.playThermalBeep();
                            }
                            lastThermalBeepMs = now;
                        }
                    }
                }
            }

            // --- Draw radar ---
            long nowMs = System.currentTimeMillis();
            List<ThermalBlip> thermalsCopy;
            synchronized (thermalLock) {
                thermalsCopy = new ArrayList<ThermalBlip>(thermals);
            }
            radarRenderer.draw(canvas, nowMs, thermalsCopy,
                    getCompassHeading(), vario, currentStatus,
                    maxSnr, thermalsCopy.size());

            // --- Draw HUD via UiManager ---
            float cx = w / 2f;
            uiManager.drawVario(canvas, cx, 260, vario);
            uiManager.drawStatus(canvas, cx, currentStatus);

            // --- Update test feedback (30 FPS) ---
            if (testMode) {
                updateTestFeedback();
            }

            // Info panel at bottom-left
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
                float turbMs2 = sp.getTurbulenceMs2();
                float detSnr = sp.getSnr();
                uiManager.drawInfo(canvas, 0, h, turbMs2, detSnr, thermalsCopy.size(),
                        sp.getBpX(), sp.getBpY(), sp.getStableDirDeg(),
                        thermalDetector.getStatusText());
            } else {
                float varioDisplay = Float.isNaN(vario) ? 0 : vario;
                float snrDisplay = Float.isNaN(recentSnr) ? 0 : recentSnr;
                uiManager.drawInfo(canvas, 0, h, varioDisplay, snrDisplay, thermalsCopy.size(),
                        0, 0, 0, currentStatus);
            }

            // --- Altitude: AGL left, MSL right ---
            float radarCy = h / 2f;
            float radarR = Math.min(w / 2f, radarCy) - 4;
            float altAgl = altitudeInitialized ? (gpsAltitude - startAltitude) : 0f;
            float altY = radarCy + radarR + 80;
            float leftMargin = w * 0.05f;
            float rightMargin = w * 0.95f;
            uiManager.drawAltitude(canvas, leftMargin, rightMargin, altY, gpsAltitude, altAgl);

            // --- Кнопка ТЕСТ на уровне высот (между AGL и MSL) ---
            float testBtnY = altY - 20; // на уровне высот
            canvas.drawRoundRect(testBtnRect, 12, 12, testBtnBgPaint);
            testBtnTextPaint.setTextSize(28);
            testBtnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
            // Мигает если testMode
            if (testMode) {
                float blink = (float) Math.sin(nowMs / 300.0) * 0.3f + 0.7f;
                testBtnTextPaint.setColor(Color.argb((int)(blink*220), 255, 193, 7));
            }
            canvas.drawText("ТЕСТ", testBtnRect.centerX(), testBtnRect.centerY() + 10, testBtnTextPaint);

            // --- Кнопки КАЛИБРОВКА и СТАРТ под варио ---
            btnTextPaint.setTextSize(26);
            btnTextPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            // Калибровка (слева)
            canvas.drawRoundRect(calibBtnRect, 10, 10, btnBgPaint);
            btnTextPaint.setColor(Color.argb(200, 0, 255, 0));
            canvas.drawText("КАЛИБР", calibBtnRect.centerX(), calibBtnRect.centerY() + 9, btnTextPaint);

            // Данные с датчиков под кнопкой калибровки — в одну строку, шрифт ×3
            if (thermalDetector != null) {
                SignalProcessor sp = thermalDetector.getSignalProcessor();
                float amp = sp.getTurbulenceMs2();
                float freq = Math.max(0.5f, Math.min(2.5f, sp.getDominantFrequency()));
                float dir = sp.getStableDirDeg();
                if (dir < 0) dir += 360f;
                if (dir >= 360f) dir -= 360f;

                float dataX = calibBtnRect.left;
                float dataY = calibBtnRect.bottom + 56;
                int dataColor = Color.argb(160, 0, 255, 0);

                sensorDataPaint.setColor(dataColor);
                sensorDataPaint.setTextSize(54);
                sensorDataPaint.setTextAlign(Paint.Align.LEFT);

                // ±0.12
                String labAmp = "\u00B1" + String.format(java.util.Locale.US, "%.2f", amp);
                canvas.drawText(labAmp, dataX, dataY, sensorDataPaint);
                float advX = sensorDataPaint.measureText(labAmp) + 24;

                // 1.35Гц
                String labFreq = String.format(java.util.Locale.US, "%.2f\u0413\u0446", freq);
                canvas.drawText(labFreq, dataX + advX, dataY, sensorDataPaint);
                advX += sensorDataPaint.measureText(labFreq) + 24;

                // 045°
                String labDir = String.format(java.util.Locale.US, "%.0f\u00B0", dir);
                canvas.drawText(labDir, dataX + advX, dataY, sensorDataPaint);
            }

            // Надпись "пишем лог" между кнопками (только при активной записи)
            if (isLogging) {
                float labelY = calibBtnRect.centerY() + 9;
                float labelCx = (calibBtnRect.right + startBtnRect.left) / 2f;
                canvas.drawText("пишем лог", labelCx, labelY, logLabelPaint);
            }

            // Старт (справа) — меняет текст и цвет
            canvas.drawRoundRect(startBtnRect, 10, 10, btnBgPaint);
            if (isLogging) {
                btnTextPaint.setColor(Color.argb(200, 255, 100, 100));
                canvas.drawText("СТОП", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);
            } else {
                btnTextPaint.setColor(Color.argb(200, 0, 255, 0));
                canvas.drawText("СТАРТ", startBtnRect.centerX(), startBtnRect.centerY() + 9, btnTextPaint);
            }

            // Flight time below altitudes
            long flightTimeMs;
            if (simMode) {
                flightTimeMs = System.currentTimeMillis() - simStartMs;
            } else {
                flightTimeMs = (flightStartMs > 0) ? (System.currentTimeMillis() - flightStartMs) : 0;
            }
            uiManager.drawFlightTime(canvas, cx, altY + 180 + 20, flightTimeMs / 1000);
            uiManager.drawSystemTime(canvas, cx, altY + 180 + 20 + 150 + 10);

            // Night mode overlay
            uiManager.drawNightFilter(canvas, w, h);

            // SIM badge
            if (simMode) {
                float badgeX = 12f;
                float badgeY = 190f;
                float textW = simPaint.measureText("SIM");
                float pad = 8f;
                canvas.drawRoundRect(badgeX, badgeY,
                        badgeX + textW + pad * 2,
                        badgeY + 32 + pad, 8, 8, simBgPaint);
                canvas.drawText("SIM", badgeX + pad, badgeY + 28, simPaint);
            }

            // Exit button (top-left)
            drawExitButton(canvas);
            // Gear button (top-right)
            drawGearButton(canvas);

            // --- Test mode overlay: инструкции в центре радара ---
            if (testMode) {
                String instr = getTestInstruction();
                String fb = getTestFeedback();
                if (instr != null && instr.length() > 0) {
                    // Semi-transparent overlay over radar area
                    float radarCx = w / 2f;
                    float radarCy2 = h / 2f;
                    float radarR2 = Math.min(w / 2f, radarCy2) - 4;
                    // Filled circle behind pilot dot — instruction background
                    testBgPaint.setColor(Color.argb(180, 5, 5, 5));
                    canvas.drawCircle(radarCx, radarCy2, radarR2 * 0.55f, testBgPaint);
                    // Border
                    testBorderPaint.setStyle(Paint.Style.STROKE);
                    testBorderPaint.setStrokeWidth(1);
                    testBorderPaint.setColor(Color.argb(40, 0, 255, 0));
                    canvas.drawCircle(radarCx, radarCy2, radarR2 * 0.55f, testBorderPaint);

                    // Instruction title (step header)
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

                    // Feedback line (dynamic)
                    if (fb != null && fb.length() > 0) {
                        float fbY = radarCy2 + radarR2 * 0.08f;
                        testTextPaint.setColor(getTestFeedbackColor());
                        testTextPaint.setTextSize(28);
                        testTextPaint.setFakeBoldText(true);
                        canvas.drawText(fb, radarCx, fbY, testTextPaint);
                    }

                    // Step progress bar
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
            float left = gearRect.left;
            float top = gearRect.top;
            float right = gearRect.right;
            float bottom = gearRect.bottom;
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            float radius = (right - left) / 2f - 4;

            canvas.drawCircle(cx, cy, radius, gearPaint);
            float innerR = radius * 0.45f;
            canvas.drawCircle(cx, cy, innerR, gearInnerPaint);

            int teeth = 8;
            float toothLen = radius * 0.3f;
            for (int i = 0; i < teeth; i++) {
                double angle = Math.toRadians(i * (360 / teeth));
                float startR = radius - toothLen;
                float endR = radius + 2;
                float sx = cx + (float) Math.cos(angle) * startR;
                float sy = cy + (float) Math.sin(angle) * startR;
                float ex = cx + (float) Math.cos(angle) * endR;
                float ey = cy + (float) Math.sin(angle) * endR;
                canvas.drawLine(sx, sy, ex, ey, gearToothPaint);
            }
        }

        private void drawExitButton(Canvas canvas) {
            float left = exitRect.left;
            float top = exitRect.top;
            float right = exitRect.right;
            float bottom = exitRect.bottom;
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            float radius = (right - left) / 2f - 4;

            canvas.drawCircle(cx, cy, radius, exitPaint);
            float inset = radius * 0.30f;
            canvas.drawLine(cx - inset, cy - inset, cx + inset, cy + inset, exitXPaint);
            canvas.drawLine(cx + inset, cy - inset, cx - inset, cy + inset, exitXPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            touchX = event.getX();
            touchY = event.getY();

            if (event.getAction() == MotionEvent.ACTION_UP) {
                // Exit button
                if (exitRect.contains(touchX, touchY)) {
                    showExitOnScreenDialog();
                    return true;
                }
                // Gear button
                if (gearRect.contains(touchX, touchY)) {
                    Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                    startActivity(intent);
                    return true;
                }
                // Calibration button
                if (calibBtnRect.contains(touchX, touchY)) {
                    resetCalibration();
                    return true;
                }
                // Start/Stop button
                if (startBtnRect.contains(touchX, touchY)) {
                    if (isLogging) {
                        manualStopRequested = true;
                        stopLogging();
                        android.widget.Toast.makeText(MainActivity.this,
                                "Лог сохранён", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        manualStopRequested = false;
                        startLogging();
                        android.widget.Toast.makeText(MainActivity.this,
                                "Запись лога начата", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                // Test button
                if (testBtnRect.contains(touchX, touchY)) {
                    if (!testMode) {
                        startTestMode();
                    } else {
                        // Press again during test = cancel
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
        public void onClick(View v) {
            // Click listener for accessibility — handled by onTouchEvent
        }
    }
}
