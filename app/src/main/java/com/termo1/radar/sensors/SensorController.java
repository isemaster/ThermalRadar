package com.termo1.radar.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * SensorController — управление всеми датчиками телефона.
 *
 * Регистрирует сенсоры, обрабатывает события, держит актуальные значения.
 * Компасная fusion: акселерометр + магнитометр → rotationMatrix → heading/pitch/roll.
 * Барометрический вариометр: давление → высота → производная + скользящее среднее.
 *
 * Thread-safe: все поля volatile или final.
 */
public class SensorController implements SensorEventListener {

    // ========================================================================
    // Константы
    // ========================================================================

    private static final int BARO_CALIB_SAMPLES = 50;
    private static final int VARIO_BUF_SIZE = 64;
    private static final long VARIO_WINDOW_MS = 750;
    private static final float NOISE_FLOOR_FIXED = 3.5f;
    private static final float HEADING_EMA_ALPHA = 0.15f; // было 0.3 — больше сглаживание
    private static final int HP_BUF_SIZE = 64;

    // ========================================================================
    // System services
    // ========================================================================

    private SensorManager sensorManager;
    private Sensor barometer;
    private Sensor accelerometerLinear;   // TYPE_LINEAR_ACCELERATION
    private Sensor accelerometerGravity;  // TYPE_ACCELEROMETER
    private Sensor gyroscope;
    private Sensor magnetometer;

    // ========================================================================
    // Latest sensor values (volatile — читаются из UI-треда)
    // ========================================================================

    // Linear accel (g) — для детектора
    private volatile float latestAccelX;
    private volatile float latestAccelY;
    private volatile float latestAccelZ;

    // Raw accel (m/s²) — для компаса
    private volatile float gravityAccelX;
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
    private volatile boolean hasPressureReading;

    // ===== VarioManager =====
    private VarioManager varioManager;

    public VarioManager getVarioManager() { return varioManager; }
    public void setVarioManager(VarioManager vm) { this.varioManager = vm; }

    // ========================================================================
    // Компасная fusion
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationVals = new float[3];
    private final float[] gravBuf = new float[3];
    private final float[] geomBuf = new float[3];
    private final float[] worldAccelOut = new float[3];
    private volatile float heading = 0.0f;
    private volatile float pitch = 0.0f;
    private volatile float roll = 0.0f;
    private volatile float rawHeading = 0.0f;
    private volatile boolean compassReady = false;
    // Heading filter (Медиана 3 + Alpha-Beta, α=0.75, β=0.3)
    private final HeadingFilter headingFilter = new HeadingFilter();
    // Время последней калибровки компаса (для логов)
    private long headingCalibratedAtMs = 0;
    // Счётчик последовательных отказов getRotationMatrix
    private int compassFailureStreak = 0;
    private static final int COMPASS_FAILURE_THRESHOLD = 30; // ~1 сек при 50 Гц
    // Магнитное склонение (обновляется по GPS)
    private float magneticDeclination = 0f;
    private long lastDeclinationUpdateMs = 0;

    // ========================================================================
    // Barometer / variometer
    // ========================================================================

    private float baselinePressure = 1013.25f;
    private int baroCalibCount = 0;
    private volatile float vario = 0.0f;
    private volatile float altFiltered = 0.0f;
    private volatile float altRaw = 0.0f;
    private volatile float prevAltRaw = 0.0f;
    private long lastBaroNanos = 0;

    // Кольцевой буфер варио (скользящее среднее 750 мс)
    private final float[] varioBuf = new float[VARIO_BUF_SIZE];
    private final long[] varioTimeBuf = new long[VARIO_BUF_SIZE];
    private int varioHead;
    private int varioTail;

    // HPF буфер для RMS
    private final float[] hpBuf = new float[HP_BUF_SIZE];
    private int hpBufIdx;
    private int hpBufFill;

    private volatile float recentSnr;
    private volatile float maxSnr;

    // ========================================================================
    // Настройки
    // ========================================================================

    private int varioProfile = 1;
    private int varioSmoothSamples = 30;

    // ========================================================================
    // Наклон телефона (tilt calibration)
    // ========================================================================

    /** Угол крепления телефона (сохранённый, градусов от вертикали) */
    private float mountTiltDeg = 0f;
    /** Текущий угол наклона (меняется в полёте, градусов) */
    private volatile float currentTiltDeg = 0f;
    /** Коэффициент коррекции порогов (1/cos(mountTilt), 1.0..3.0) */
    private float tiltCorrection = 1f;

    // Калибровка наклона: накопление gravity
    private float gravitySumX, gravitySumY, gravitySumZ;
    private int gravityCalibCount;
    private boolean tiltCalibrationPending;

    public float getMountTiltDeg() { return mountTiltDeg; }
    public float getCurrentTiltDeg() { return currentTiltDeg; }
    public float getTiltCorrection() { return tiltCorrection; }

    /** Запустить калибровку наклона (накопление gravity на 2 сек) */
    public void startTiltCalibration() {
        gravitySumX = gravitySumY = gravitySumZ = 0f;
        gravityCalibCount = 0;
        tiltCalibrationPending = true;
    }

    /** Сохранить угол крепления в SharedPreferences */
    public void saveMountTilt(SharedPreferences prefs) {
        prefs.edit()
            .putFloat("mount_tilt_deg", mountTiltDeg)
            .putFloat("tilt_correction", tiltCorrection)
            .apply();
    }

    /** Загрузить сохранённый угол крепления */
    public void loadMountTilt(SharedPreferences prefs) {
        mountTiltDeg = prefs.getFloat("mount_tilt_deg", 0f);
        tiltCorrection = prefs.getFloat("tilt_correction", 1f);
        if (tiltCorrection < 1f) { mountTiltDeg = 0f; tiltCorrection = 1f; }
    }

    /** true если датчик был найден и зарегистрирован */
    private volatile boolean sensorAccelAvailable;
    private volatile boolean sensorLinearAccelAvailable;
    private volatile boolean sensorBaroAvailable;
    private volatile boolean sensorGyroAvailable;
    private volatile boolean sensorMagAvailable;
    /** Точность магнитометра (SENSOR_STATUS_ACCURACY_*) */
    private volatile int magAccuracy = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;

    // ========================================================================
    // Callback для оповещения о новых данных (thermal detector etc.)
    // ========================================================================

    private SensorDataListener dataListener;

    public interface SensorDataListener {
        /** Вызывается на каждом новом сэмпле LINEAR_ACCEL (сенсорный поток) */
        void onLinearAccelSample(float axG, float ayG, float azG,
                                  float headingDeg, float[] rotMatrix,
                                  boolean compassReady);

        /** Вызывается на новом сэмпле TYPE_ACCELEROMETER (для детектора, если нет LINEAR) */
        void onGravityAccelSample(float axMs2, float ayMs2, float azMs2,
                                   float headingDeg, float[] rotMatrix,
                                   boolean compassReady);
    }

    public void setDataListener(SensorDataListener listener) {
        this.dataListener = listener;
    }

    // ========================================================================
    // Init
    // ========================================================================

    public SensorController(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    /** Загрузить настройки из SharedPreferences */
    public void loadPreferences(SharedPreferences prefs) {
        varioProfile = prefs.getInt("vario_profile", 1);
        varioSmoothSamples = prefs.getInt("vario_smooth", 30);
        loadMountTilt(prefs);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    public void registerSensors() {
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        accelerometerGravity = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerLinear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (accelerometerLinear != null) {
            hasLinearAccel = true;
        } else {
            accelerometerLinear = accelerometerGravity;
            hasLinearAccel = false;
        }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        sensorAccelAvailable = accelerometerGravity != null;
        sensorLinearAccelAvailable = accelerometerLinear != null;
        sensorBaroAvailable = barometer != null;
        sensorGyroAvailable = gyroscope != null;
        sensorMagAvailable = magnetometer != null;

        // Register
        if (barometer != null)
            sensorManager.registerListener(this, barometer, SensorManager.SENSOR_DELAY_GAME);
        if (accelerometerLinear != null)
            sensorManager.registerListener(this, accelerometerLinear, SensorManager.SENSOR_DELAY_GAME);
        if (accelerometerGravity != null)
            sensorManager.registerListener(this, accelerometerGravity, SensorManager.SENSOR_DELAY_GAME);
        if (gyroscope != null)
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        if (magnetometer != null)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
    }

    public void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
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
                if (varioManager != null) {
                    varioManager.processPressureSample(event.values[0]);
                }
                break;
            }
            case Sensor.TYPE_LINEAR_ACCELERATION: {
                float ax = event.values[0] / 9.81f;
                float ay = event.values[1] / 9.81f;
                float az = event.values[2] / 9.81f;
                latestAccelX = ax;
                latestAccelY = ay;
                latestAccelZ = az;
                hasLinearAccel = true;
                if (dataListener != null) {
                    dataListener.onLinearAccelSample(ax, ay, az, heading, rotationMatrix, compassReady);
                }
                break;
            }
            case Sensor.TYPE_ACCELEROMETER: {
                gravityAccelX = event.values[0];
                gravityAccelY = event.values[1];
                gravityAccelZ = event.values[2];
                hasGravityAccel = true;

                // Текущий угол наклона (каждый сэмпл, для индикации)
                float gMag = (float) Math.sqrt(
                        event.values[0]*event.values[0]
                        + event.values[1]*event.values[1]
                        + event.values[2]*event.values[2]);
                if (gMag > 0.1f) {
                    float cosTilt = Math.abs(event.values[2]) / gMag;
                    currentTiltDeg = (float) Math.toDegrees(Math.acos(
                            Math.min(1f, Math.max(-1f, cosTilt))));
                }

                // Калибровка: накопление gravity (первые 100 сэмплов после старта)
                if (tiltCalibrationPending) {
                    gravitySumX += event.values[0];
                    gravitySumY += event.values[1];
                    gravitySumZ += event.values[2];
                    gravityCalibCount++;
                    if (gravityCalibCount >= 100) {
                        float ax = gravitySumX / 100f;
                        float ay = gravitySumY / 100f;
                        float az = gravitySumZ / 100f;
                        float amag = (float) Math.sqrt(ax*ax + ay*ay + az*az);
                        if (amag > 0.1f) {
                            mountTiltDeg = (float) Math.toDegrees(Math.acos(
                                    Math.min(1f, Math.max(-1f, Math.abs(az) / amag))));
                            tiltCorrection = 1f / (float) Math.cos(
                                    Math.toRadians(mountTiltDeg));
                            if (tiltCorrection > 3f) tiltCorrection = 3f;
                            if (tiltCorrection < 1f) tiltCorrection = 1f;
                        }
                        tiltCalibrationPending = false;
                    }
                }

                if (!hasLinearAccel && dataListener != null) {
                    dataListener.onGravityAccelSample(
                            event.values[0], event.values[1], event.values[2],
                            heading, rotationMatrix, compassReady);
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
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            magAccuracy = accuracy;
        }
    }

    // ========================================================================
    // Compass
    // ========================================================================

    private void updateCompass() {
        if (!hasGravityAccel) return;

        gravBuf[0] = gravityAccelX;
        gravBuf[1] = gravityAccelY;
        gravBuf[2] = gravityAccelZ;
        geomBuf[0] = latestMagX;
        geomBuf[1] = latestMagY;
        geomBuf[2] = latestMagZ;

        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravBuf, geomBuf)) {
            compassFailureStreak = 0; // успех — сбрасываем счётчик
            SensorManager.getOrientation(rotationMatrix, orientationVals);
            float newHeading = (float) Math.toDegrees(orientationVals[0]);
            rawHeading = newHeading;
            pitch = orientationVals[1];
            roll = orientationVals[2];
            if (newHeading < 0.0f) newHeading += 360.0f;

            if (!compassReady) {
                heading = newHeading;
                headingFilter.reset();
                compassReady = true;
            } else {
                // HeadingFilter ВСЕГДА возвращает число (не NaN)
                heading = (float) headingFilter.update(newHeading, SystemClock.elapsedRealtime());
            }
        } else if (magnetometer == null) {
            // Нет магнитометра — pitch/roll из гравитации
            compassFailureStreak = 0;
            pitch = (float) Math.atan2(-gravityAccelX,
                    Math.sqrt(gravityAccelY * gravityAccelY + gravityAccelZ * gravityAccelZ));
            roll = (float) Math.atan2(gravityAccelY, gravityAccelZ);
        } else {
            // getRotationMatrix отказал — считаем до COMPASS_FAILURE_THRESHOLD
            compassFailureStreak++;
            pitch = (float) Math.atan2(-gravityAccelX,
                    Math.sqrt(gravityAccelY * gravityAccelY + gravityAccelZ * gravityAccelZ));
            roll = (float) Math.atan2(gravityAccelY, gravityAccelZ);

            // Только при устойчивом отказе сбрасываем compassReady
            if (compassFailureStreak > COMPASS_FAILURE_THRESHOLD) {
                compassReady = false;
            }
            // heading НЕ трогаем — держим последнее хорошее значение
        }
    }

    // ========================================================================
    // Barometric variometer
    // ========================================================================

    private void onPressureSample() {
        float p = latestPressure;
        if (p < 300f || p > 1100f) return;

        // Калибровка: первые 50 сэмплов
        if (baroCalibCount < BARO_CALIB_SAMPLES) {
            baselinePressure += (p - baselinePressure) / (baroCalibCount + 1);
            baroCalibCount++;
            return;
        }

        float ratio = p / baselinePressure;
        altRaw = 44330.7692307692f * (1.0f - (float) Math.pow(ratio, 0.190263072286998f));
        // Vario = производная высоты
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        if (baroCalibCount == BARO_CALIB_SAMPLES) {
            altFiltered = altRaw;
            prevAltRaw = altRaw;
            lastBaroNanos = nowNanos;
            baroCalibCount++;
            return;
        }

        // EMA на высоте
        float alpha = getVarioAlpha();
        altFiltered = alpha * altFiltered + (1f - alpha) * altRaw;

        // rawVario = (altRaw - prevAltRaw) * 1_000_000 / dtNanos
        long dtNanos = nowNanos - lastBaroNanos;
        lastBaroNanos = nowNanos;
        long dtMs = dtNanos / 1_000_000;
        if (dtMs > 1) {
            float rawVario = (altRaw - prevAltRaw) * 1000f / (float) dtMs;
            prevAltRaw = altRaw;

            // Добавляем в кольцевой буфер
            long nowMs = System.currentTimeMillis();
            varioBuf[varioHead] = rawVario;
            varioTimeBuf[varioHead] = nowMs;
            varioHead = (varioHead + 1) % VARIO_BUF_SIZE;
            if (varioHead == varioTail) {
                varioTail = (varioTail + 1) % VARIO_BUF_SIZE;
            }

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

        // HPF для RMS
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
            if (recentSnr > maxSnr) maxSnr = recentSnr;
        }
    }

    private float getVarioAlpha() {
        int n = Math.max(5, Math.min(100, varioSmoothSamples));
        float alpha = 1f - 1f / n;
        return Math.max(0.9f, Math.min(0.9999f, alpha));
    }

    // ========================================================================
    // Calibration reset
    // ========================================================================

    public void resetCalibration() {
        if (varioManager != null) varioManager.reset();
        else {
            baroCalibCount = 0;
            baselinePressure = latestPressure;
            hpBufFill = 0;
            recentSnr = 0f;
        }
        startTiltCalibration();
    }

    /** Сбросить maxSnr для адаптации радара */
    public void resetMaxSnr() {
        if (varioManager != null) varioManager.resetMaxSnr();
    }

    // ========================================================================
    // Getters (thread-safe)
    // ========================================================================

    // --- Accelerometer ---
    public float getAccelX() { return latestAccelX; }
    public float getAccelY() { return latestAccelY; }
    public float getAccelZ() { return latestAccelZ; }
    public boolean hasLinearAccel() { return hasLinearAccel; }

    // --- Gravity ---
    public float getGravityX() { return gravityAccelX; }
    public float getGravityY() { return gravityAccelY; }
    public float getGravityZ() { return gravityAccelZ; }

    // --- Gyro ---
    public float getGyroX() { return latestGyroX; }
    public float getGyroY() { return latestGyroY; }
    public float getGyroZ() { return latestGyroZ; }

    // --- Magnetometer ---
    public float getMagX() { return latestMagX; }
    public float getMagY() { return latestMagY; }
    public float getMagZ() { return latestMagZ; }

    // --- Pressure ---
    public float getPressure() { return latestPressure; }
    public boolean hasPressureReading() { return hasPressureReading; }

    // --- Compass ---
    public float getHeading() { return heading; }
    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    public float getRawHeading() { return rawHeading; }
    public boolean isCompassReady() { return compassReady; }
    public float[] getRotationMatrix() { return rotationMatrix; }

    /** Для лога: heading с fallback, если нет магнитометра */
    public float getLogHeading(float gpsHeading) {
        if (magnetometer != null && compassReady) {
            return rawHeading;
        }
        return gpsHeading;
    }

    // --- Variometer (делегировано VarioManager) ---
    public float getVario() { return varioManager != null ? varioManager.getVario() : 0f; }
    public float getAltitudeRaw() { return varioManager != null ? varioManager.getAltRaw() : 0f; }
    public float getAltitudeFiltered() { return varioManager != null ? varioManager.getAltFiltered() : 0f; }
    public float getRecentSnr() { return varioManager != null ? varioManager.getRecentSnr() : 0f; }
    public float getMaxSnr() { return varioManager != null ? varioManager.getMaxSnr() : 0f; }

    // --- Sensor availability ---
    public boolean isAccelAvailable() { return sensorAccelAvailable; }
    public boolean isLinearAccelAvailable() { return sensorLinearAccelAvailable; }
    public boolean isBaroAvailable() { return sensorBaroAvailable; }
    public boolean isGyroAvailable() { return sensorGyroAvailable; }
    public boolean isMagAvailable() { return sensorMagAvailable; }

    public boolean hasBarometer() { return barometer != null; }
    public boolean hasMagnetometer() { return magnetometer != null; }

    /** Обновить магнитное склонение по GPS-координатам */
    public void updateDeclination(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (now - lastDeclinationUpdateMs < 60000) return; // раз в минуту
        lastDeclinationUpdateMs = now;
        try {
            android.hardware.GeomagneticField gf = new android.hardware.GeomagneticField(
                    (float) lat, (float) lon, 0f, now);
            magneticDeclination = gf.getDeclination();
        } catch (Exception e) {
            // ignore
        }
    }

    /** Магнитное склонение (градусы) */
    public float getMagneticDeclination() { return magneticDeclination; }

    /** Истинный курс (магнитный heading + склонение) */
    public float getTrueHeading() {
        float h = heading + magneticDeclination;
        if (h < 0) h += 360f;
        if (h >= 360f) h -= 360f;
        return h;
    }

    /**
     * Калибровка компаса: просто помечает время калибровки.
     * Без заморозки heading и без сброса фильтра — накопленная
     * история α-β продолжает работать, плавность не теряется.
     * Вызывать при старте лога или обнаружении старта полёта.
     */
    public void calibrateHeading() {
        headingCalibratedAtMs = SystemClock.elapsedRealtime();
    }


    /**
     * Подать GPS-курс в фильтр компаса (fallback при ненадёжном магнитометре).
     * Вызывать с gpsManager.getHeading() на каждом GPS-фиксе.
     */
    public void updateGpsHeading(float gpsHeading) {
        boolean compassUnreliable = !compassReady
                || magnetometer == null
                || magAccuracy < android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                || compassFailureStreak > COMPASS_FAILURE_THRESHOLD;
        if (compassUnreliable) {
            heading = (float) headingFilter.update(gpsHeading, SystemClock.elapsedRealtime());
        }
    }

    /** Магнитометр достаточно точен для компаса? */
    public boolean isMagAccurate() {
        return magAccuracy >= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    }

    /**
     * Построить rotation matrix из GPS-курса + гравитации.
     * Используется как fallback, когда магнитометр не откалиброван.
     * Матрица: right (X=East), up (Z=up), forward (Y=North) с учётом pitch/roll от gravity.
     *
     * @param gpsHeadingDeg курс по GPS (градусы, 0=N, 90=E)
     * @param gravX гравитация X (raw accel, m/s²)
     * @param gravY гравитация Y
     * @param gravZ гравитация Z
     * @param rotOut выходной массив float[9]
     */
    public static void buildRotationFromGpsHeading(float gpsHeadingDeg,
                                                     float gravX, float gravY, float gravZ,
                                                     float[] rotOut) {
        // Нормализуем gravity
        float g = (float) Math.sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ);
        if (g < 0.1f) {
            // Нет гравитации — единичная матрица (без коррекции наклона)
            float rad = (float) Math.toRadians(gpsHeadingDeg);
            float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            rotOut[0] = c; rotOut[1] = -s; rotOut[2] = 0;
            rotOut[3] = s; rotOut[4] = c;  rotOut[5] = 0;
            rotOut[6] = 0; rotOut[7] = 0;  rotOut[8] = 1;
            return;
        }

        // G = нормализованный вектор гравитации (указывает вниз)
        float gx = gravX / g, gy = gravY / g, gz = gravZ / g;

        // В мировых координатах гравитация указывает в -Z (вниз).
        // Наклон телефона (pitch/roll) — это отклонение gravity от оси Z телефона.
        // Строим ортонормированную матрицу: Y = forward (North), X = right (East), Z = up

        // Сначала ось Z (up) = -gravity (телефон смотрит вверх)
        float upX = -gx, upY = -gy, upZ = -gz;

        // Ось Y (North) = проекция GPS-курса на горизонтальную плоскость
        float headingRad = (float) Math.toRadians(gpsHeadingDeg);
        // В магнитных координатах: North = (0,1,0), East = (1,0,0)
        // Вращение heading: East = sin(h), North = cos(h)
        float northX = (float) Math.sin(headingRad);
        float northY = (float) Math.cos(headingRad);
        float northZ = 0f;

        // Проецируем north на плоскость, перпендикулярную up
        // forward = north - (north · up) × up
        float dot = northX * upX + northY * upY + northZ * upZ;
        float fwdX = northX - dot * upX;
        float fwdY = northY - dot * upY;
        float fwdZ = northZ - dot * upZ;

        // Нормализуем forward
        float f = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ);
        if (f < 0.01f) {
            // Смотрим прямо вверх — fallback
            fwdX = (float) Math.sin(headingRad);
            fwdY = (float) Math.cos(headingRad);
            fwdZ = 0;
            f = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY);
        }
        fwdX /= f; fwdY /= f; fwdZ /= f;

        // Ось X (East/right) = forward × up (right-hand system)
        float rightX = fwdY * upZ - fwdZ * upY;
        float rightY = fwdZ * upX - fwdX * upZ;
        float rightZ = fwdX * upY - fwdY * upX;

        // Заполняем rotationMatrix (row-major: R[0..2]=X, R[3..5]=Y, R[6..8]=Z)
        // getRotationMatrix формат: [0]=Xx, [1]=Xy, [2]=Xz, [3]=Yx, [4]=Yy, [5]=Yz, [6]=Zx, [7]=Zy, [8]=Zz
        // где X=East, Y=North, Z=up
        rotOut[0] = rightX; rotOut[1] = rightY; rotOut[2] = rightZ;
        rotOut[3] = fwdX;   rotOut[4] = fwdY;   rotOut[5] = fwdZ;
        rotOut[6] = upX;    rotOut[7] = upY;    rotOut[8] = upZ;
    }
}
