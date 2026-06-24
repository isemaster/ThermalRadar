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
 * Компасная fusion: PRIMARY — TYPE_ROTATION_VECTOR (встроенный fusion ядра Android),
 *   FALLBACK — TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD.
 * Барометрический вариометр делегирован VarioManager.
 *
 * Thread-safe:
 *   - rotationMatrix через snapshot (copy-on-read с блокировкой записи)
 *   - headingFilter под headingLock для защиты от race с updateGpsHeading
 *   - volatile для простых полей, читаемых из UI-треда
 */
public class SensorController implements SensorEventListener {

    // ========================================================================
    // Константы
    // ========================================================================

    /** Явная задержка сенсоров в микросекундах (50 Гц = 20000 мкс) */
    private static final int SENSOR_DELAY_US = 20000;
    /** Барометр: 10 Гц явно (большинство телефонов не быстрее) */
    private static final int BARO_DELAY_US = 100000;
    /** Компас fusion: 25 Гц достаточно для параплана */
    private static final int COMPASS_DELAY_US = 40000;

    private static final int COMPASS_FAILURE_THRESHOLD = 30;

    // ========================================================================
    // System services
    // ========================================================================

    private SensorManager sensorManager;
    private Sensor barometer;
    private Sensor accelerometerLinear;   // TYPE_LINEAR_ACCELERATION
    private Sensor accelerometerGravity;  // TYPE_ACCELEROMETER / TYPE_GRAVITY
    private Sensor gyroscope;
    private Sensor rotationVector;        // PRIMARY compass source (API 26+)
    private Sensor magnetometer;          // FALLBACK compass source

    // ========================================================================
    // Latest sensor values (volatile — читаются из UI-треда)
    // ========================================================================

    // Linear accel (g) — для детектора
    private volatile float latestAccelX;
    private volatile float latestAccelY;
    private volatile float latestAccelZ;

    // Raw accel (m/s²) — для fallback компаса
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
    // ========================================================================

    // PRIVATE internal matrix (только sensor thread пишет)
    private final float[] rotationMatrixInternal = new float[9];
    // SNAPSHOT — атомарная замена при каждом обновлении (читают UI, sensor thd)
    private volatile float[] rotationMatrixSnapshot = new float[9];
    private final float[] orientationVals = new float[3];
    private final float[] gravBuf = new float[3];
    private final float[] geomBuf = new float[3];
    private final Object rotMatrixLock = new Object();

    private volatile float heading = 0.0f;
    private volatile float pitch = 0.0f;
    private volatile float roll = 0.0f;
    private volatile boolean compassReady = false;

    // Heading filter (α=0.15, β=0.02 — исправлено по ревью)
    private final HeadingFilter headingFilter = new HeadingFilter();
    // Защита headingFilter от одновременного доступа из sensor thread и UI thread
    private final Object headingLock = new Object();

    private long headingCalibratedAtMs = 0;
    private int compassFailureStreak = 0;
    private float magneticDeclination = 0f;
    private long lastDeclinationUpdateMs = 0;

    // ========================================================================
    // Barometer — делегирован VarioManager, поля только для fallback
    // ========================================================================

    private float baselinePressure = 1013.25f;
    private int baroCalibCount;

    // ========================================================================
    // Настройки
    // ========================================================================

    private int varioProfile = 1;
    private int varioSmoothSamples = 30;

    // ========================================================================
    // Наклон телефона (tilt calibration)
    // ========================================================================

    private float mountTiltDeg = 0f;
    private volatile float currentTiltDeg = 0f;
    private float tiltCorrection = 1f;

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

    private volatile boolean sensorAccelAvailable;
    private volatile boolean sensorLinearAccelAvailable;
    private volatile boolean sensorBaroAvailable;
    private volatile boolean sensorGyroAvailable;
    private volatile boolean sensorMagAvailable;
    private volatile boolean sensorRotVecAvailable;
    private volatile int magAccuracy = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;

    // Guard для registerSensors — не идемпотентный без него
    private boolean registered = false;

    // ========================================================================
    // Callback для оповещения о новых данных (thermal detector etc.)
    // ========================================================================

    private SensorDataListener dataListener;

    public interface SensorDataListener {
        void onLinearAccelSample(float axG, float ayG, float azG,
                                  float headingDeg, float[] rotMatrix,
                                  boolean compassReady);

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

    public synchronized void registerSensors() {
        if (registered) return; // guard — не идемпотентный (исправлено по ревью)

        if (sensorManager == null) return; // guard от NPE

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

        // PRIMARY: TYPE_ROTATION_VECTOR — встроенный fusion ядра Android (Fix A)
        // Использует gyro + accel + mag, сглаженный Android SensorFusion.
        // Доступен на API 26+ (наш minSdk).
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        sensorAccelAvailable = accelerometerGravity != null;
        sensorLinearAccelAvailable = accelerometerLinear != null;
        sensorBaroAvailable = barometer != null;
        sensorGyroAvailable = gyroscope != null;
        sensorMagAvailable = magnetometer != null;
        sensorRotVecAvailable = rotationVector != null;

        // Register с явными задержками (не SENSOR_DELAY_GAME)
        if (barometer != null)
            sensorManager.registerListener(this, barometer, BARO_DELAY_US);
        if (accelerometerLinear != null)
            sensorManager.registerListener(this, accelerometerLinear, SENSOR_DELAY_US);
        if (accelerometerGravity != null)
            sensorManager.registerListener(this, accelerometerGravity, SENSOR_DELAY_US);
        if (gyroscope != null)
            sensorManager.registerListener(this, gyroscope, SENSOR_DELAY_US);
        // ROTATION_VECTOR — более низкая частота (25 Гц), т.к. уже сглажен ядром
        if (rotationVector != null)
            sensorManager.registerListener(this, rotationVector, COMPASS_DELAY_US);
        // Магнитометр — только для fallback
        if (magnetometer != null)
            sensorManager.registerListener(this, magnetometer, COMPASS_DELAY_US);

        registered = true;
    }

    public synchronized void unregisterSensors() {
        if (!registered) return;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        registered = false;
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
                    dataListener.onLinearAccelSample(ax, ay, az, heading, rotationMatrixSnapshot, compassReady);
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
                // Исправлено по ревью §4.2: проверка стационарности (gMag ≈ 9.81)
                if (tiltCalibrationPending) {
                    if (Math.abs(gMag - 9.81f) < 0.3f) { // только если нет линейного ускорения
                        gravitySumX += event.values[0];
                        gravitySumY += event.values[1];
                        gravitySumZ += event.values[2];
                        gravityCalibCount++;
                    }
                    if (gravityCalibCount >= 100) {
                        float ax = gravitySumX / gravityCalibCount;
                        float ay = gravitySumY / gravityCalibCount;
                        float az = gravitySumZ / gravityCalibCount;
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
                            heading, rotationMatrixSnapshot, compassReady);
                }
                break;
            }
            case Sensor.TYPE_GYROSCOPE: {
                latestGyroX = event.values[0];
                latestGyroY = event.values[1];
                latestGyroZ = event.values[2];
                break;
            }
            case Sensor.TYPE_ROTATION_VECTOR: {
                // PRIMARY compass path — встроенный fusion ядра Android (Fix A)
                SensorManager.getRotationMatrixFromVector(rotationMatrixInternal, event.values);
                // Снимаем snapshot для UI-треда
                publishRotationMatrixSnapshot();
                SensorManager.getOrientation(rotationMatrixInternal, orientationVals);
                float newHeading = (float) Math.toDegrees(orientationVals[0]);
                if (newHeading < 0.0f) newHeading += 360.0f;
                rawHeading = newHeading;
                pitch = orientationVals[1];
                roll = orientationVals[2];
                updateFilteredHeading(newHeading);
                break;
            }
            case Sensor.TYPE_MAGNETIC_FIELD: {
                latestMagX = event.values[0];
                latestMagY = event.values[1];
                latestMagZ = event.values[2];
                // FALLBACK compass: только если нет ROTATION_VECTOR
                if (rotationVector == null) {
                    updateCompassFallback();
                }
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
    // Rotation matrix snapshot — thread safety (исправлено по ревью §10.3)
    // ========================================================================

    private void publishRotationMatrixSnapshot() {
        synchronized (rotMatrixLock) {
            float[] snapshot = new float[9];
            System.arraycopy(rotationMatrixInternal, 0, snapshot, 0, 9);
            rotationMatrixSnapshot = snapshot;
        }
    }

    // ========================================================================
    // Heading filter с блокировкой (исправлено по ревью §10.4)
    // ========================================================================

    /** Вызвать из sensor thread с новым heading.
     *  При TYPE_ROTATION_VECTOR — пропускаем HeadingFilter,
     *  т.к. ROTATION_VECTOR уже отфильтрован встроенным Kalman filter ядра Android.
     *  Фильтр остаётся только для fallback (accel+mag или GPS). */
    private void updateFilteredHeading(float newHeading) {
        synchronized (headingLock) {
            if (!compassReady) {
                heading = newHeading;
                headingFilter.reset();
                compassReady = true;
            } else if (rotationVector != null) {
                // TYPE_ROTATION_VECTOR — уже отфильтрован, идёт напрямую
                heading = newHeading;
            } else {
                // Fallback (accel+mag) — применяем HeadingFilter
                heading = (float) headingFilter.update(newHeading, SystemClock.elapsedRealtime());
            }
        }
    }

    // ========================================================================
    // Compass fallback (accel + mag, только если нет ROTATION_VECTOR)
    // ========================================================================

    private volatile float rawHeading = 0.0f;

    private void updateCompassFallback() {
        if (!hasGravityAccel) return;

        gravBuf[0] = gravityAccelX;
        gravBuf[1] = gravityAccelY;
        gravBuf[2] = gravityAccelZ;
        geomBuf[0] = latestMagX;
        geomBuf[1] = latestMagY;
        geomBuf[2] = latestMagZ;

        if (SensorManager.getRotationMatrix(rotationMatrixInternal, null, gravBuf, geomBuf)) {
            compassFailureStreak = 0;
            publishRotationMatrixSnapshot();
            SensorManager.getOrientation(rotationMatrixInternal, orientationVals);
            float newHeading = (float) Math.toDegrees(orientationVals[0]);
            rawHeading = newHeading;
            pitch = orientationVals[1];
            roll = orientationVals[2];
            if (newHeading < 0.0f) newHeading += 360.0f;
            updateFilteredHeading(newHeading);
        } else {
            // getRotationMatrix отказал
            compassFailureStreak++;
            pitch = (float) Math.atan2(-gravityAccelX,
                    Math.sqrt(gravityAccelY * gravityAccelY + gravityAccelZ * gravityAccelZ));
            roll = (float) Math.atan2(gravityAccelY, gravityAccelZ);

            if (compassFailureStreak > COMPASS_FAILURE_THRESHOLD) {
                compassReady = false;
            }
            // heading НЕ трогаем — держим последнее хорошее значение
        }
    }

    // ========================================================================
    // Calibration
    // ========================================================================

    public void resetCalibration() {
        if (varioManager != null) varioManager.reset();
        else {
            baroCalibCount = 0;
            baselinePressure = latestPressure;
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
    public boolean isCompassReady() { return compassReady; }
    /** Возвращает snapshot rotationMatrix — атомарная ссылка.
     *  Возвращается копия массива для защиты от partial reads (T12 §3.1). */
    public float[] getRotationMatrix() {
        float[] snapshot = rotationMatrixSnapshot;
        float[] copy = new float[9];
        System.arraycopy(snapshot, 0, copy, 0, 9);
        return copy;
    }

    /** Для лога: heading с fallback, если нет магнитометра.
     *  Исправлено по ревью §4.4: теперь пишет filtered heading, не raw. */
    public float getLogHeading(float gpsHeading) {
        if (compassReady) {
            return heading; // filtered heading — то же, что видит UI
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
    public boolean isRotVecAvailable() { return sensorRotVecAvailable; }

    public boolean hasBarometer() { return barometer != null; }
    public boolean hasMagnetometer() { return magnetometer != null; }
    public boolean hasRotationVector() { return rotationVector != null; }

    /** Обновить магнитное склонение по GPS-координатам */
    public void updateDeclination(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (now - lastDeclinationUpdateMs < 60000) return;
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
     */
    public void calibrateHeading() {
        headingCalibratedAtMs = SystemClock.elapsedRealtime();
    }

    /**
     * Подать GPS-курс в фильтр компаса (fallback при ненадёжном магнитометре).
     * Вызывать с gpsManager.getHeading() на каждом GPS-фиксе.
     * Исправлено по ревью §10.4: синхронизирован headingLock.
     */
    public void updateGpsHeading(float gpsHeading) {
        boolean compassUnreliable = !compassReady
                || (rotationVector == null && magnetometer == null)
                || (rotationVector == null && magAccuracy < android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
                || compassFailureStreak > COMPASS_FAILURE_THRESHOLD;
        if (compassUnreliable) {
            synchronized (headingLock) {
                heading = (float) headingFilter.update(gpsHeading, SystemClock.elapsedRealtime());
            }
        }
    }

    /** Магнитометр достаточно точен для компаса? */
    public boolean isMagAccurate() {
        return magAccuracy >= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    }

    /**
     * Построить rotation matrix из GPS-курса + гравитации.
     * Используется как fallback, когда магнитометр не откалиброван.
     */
    public static void buildRotationFromGpsHeading(float gpsHeadingDeg,
                                                     float gravX, float gravY, float gravZ,
                                                     float[] rotOut) {
        float g = (float) Math.sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ);
        if (g < 0.1f) {
            float rad = (float) Math.toRadians(gpsHeadingDeg);
            float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            rotOut[0] = c; rotOut[1] = -s; rotOut[2] = 0;
            rotOut[3] = s; rotOut[4] = c;  rotOut[5] = 0;
            rotOut[6] = 0; rotOut[7] = 0;  rotOut[8] = 1;
            return;
        }

        float gx = gravX / g, gy = gravY / g, gz = gravZ / g;

        float upX = -gx, upY = -gy, upZ = -gz;

        float headingRad = (float) Math.toRadians(gpsHeadingDeg);
        float northX = (float) Math.sin(headingRad);
        float northY = (float) Math.cos(headingRad);
        float northZ = 0f;

        float dot = northX * upX + northY * upY + northZ * upZ;
        float fwdX = northX - dot * upX;
        float fwdY = northY - dot * upY;
        float fwdZ = northZ - dot * upZ;

        float f = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ);
        if (f < 0.01f) {
            fwdX = (float) Math.sin(headingRad);
            fwdY = (float) Math.cos(headingRad);
            fwdZ = 0;
            f = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY);
        }
        fwdX /= f; fwdY /= f; fwdZ /= f;

        float rightX = fwdY * upZ - fwdZ * upY;
        float rightY = fwdZ * upX - fwdX * upZ;
        float rightZ = fwdX * upY - fwdY * upX;

        rotOut[0] = rightX; rotOut[1] = rightY; rotOut[2] = rightZ;
        rotOut[3] = fwdX;   rotOut[4] = fwdY;   rotOut[5] = fwdZ;
        rotOut[6] = upX;    rotOut[7] = upY;    rotOut[8] = upZ;
    }
}
