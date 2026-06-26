package com.termo1.radar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.PowerManager;

import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.sensors.SensorController;
import com.termo1.radar.sensors.VarioManager;

/**
 * SensorCoordinator — управление жизненным циклом сенсоров и GPS.
 * Позволяет включать/выключать сенсоры (при реплее) и GPS.
 *
 * Исправлено P2: выделен из MainActivity, объединяет SensorController + GpsManager lifecycle.
 */
public class SensorCoordinator {

    private final SensorController sensorController;
    private final GpsManager gpsManager;
    private final VarioManager varioManager;
    private final Context context;
    private PowerManager.WakeLock wakeLock;

    public SensorCoordinator(Context context, SensorController sensorController,
                             GpsManager gpsManager, VarioManager varioManager) {
        this.context = context;
        this.sensorController = sensorController;
        this.gpsManager = gpsManager;
        this.varioManager = varioManager;
    }

    /** Запустить сенсоры и GPS. Вызвать в onResume. */
    public void start(SharedPreferences prefs) {
        sensorController.loadPreferences(prefs);
        sensorController.registerSensors();

        gpsManager.startGps();
        gpsManager.setSensorController(sensorController);
        try {
            gpsManager.requestUpdates(Looper.getMainLooper());
        } catch (SecurityException e) {
            // permission already checked on startup
        }
        acquireWakeLock();
    }

    /** Остановить сенсоры и GPS (для реплея). */
    public void stop() {
        gpsManager.stopGps();
        sensorController.unregisterSensors();
    }

    /** Возобновить сенсоры и GPS (после реплея). */
    public void restart() {
        gpsManager.startGps();
        gpsManager.setSensorController(sensorController);
        try {
            gpsManager.requestUpdates(Looper.getMainLooper());
        } catch (SecurityException e) {}
        sensorController.registerSensors();
    }

    /** Загрузить настройки из SharedPreferences. */
    public void loadPreferences(SharedPreferences prefs) {
        sensorController.loadPreferences(prefs);
    }

    public SensorController getSensorController() { return sensorController; }
    public GpsManager getGpsManager() { return gpsManager; }
    public VarioManager getVarioManager() { return varioManager; }

    // ========================================================================
    // WakeLock
    // ========================================================================

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TERMO1:RadarSvc");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
    }

    public void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
