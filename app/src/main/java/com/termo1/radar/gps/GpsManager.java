package com.termo1.radar.gps;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.GpsStatus;
import android.location.GpsSatellite;
import android.location.GnssStatus;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import com.termo1.radar.sensors.SensorController;

/**
 * GpsManager — управление GPS-локацией.
 *
 * Предоставляет скорость, курс, высоту, координаты.
 * Запоминает стартовую высоту для расчёта AGL.
 */
public class GpsManager {

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private volatile float gpsSpeed;
    private volatile float gpsHeading;
    private volatile boolean gpsReady;
    private volatile float gpsAltitude;
    private volatile float startAltitude;
    private volatile boolean altitudeInitialized;
    private volatile double gpsLat;
    private volatile double gpsLon;
    private volatile float gpsAccuracy = 999f;
    private volatile long lastFixMs;
    private volatile int satelliteCount = 0;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private SensorController sensorController; // для баро-калибровки (C-08)

    public GpsManager(Context context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            gnssCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    int count = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) count++;
                    }
                    satelliteCount = count;
                }
            };
        }
    }

    public void startGps() {
        if (fusedLocationClient == null) return;

        LocationRequest locationRequest = LocationRequest.create()
                .setInterval(1000L)
                .setFastestInterval(500L)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    gpsSpeed = location.hasSpeed() ? location.getSpeed() : 0.0f;
                    gpsHeading = location.hasBearing() ? location.getBearing() : 0.0f;
                    gpsReady = true;
                    gpsLat = location.getLatitude();
                    gpsLon = location.getLongitude();
                    gpsAccuracy = location.hasAccuracy() ? location.getAccuracy() : 999f;
                    lastFixMs = SystemClock.elapsedRealtime();
                    // Отсев неточных фиксов для стартовой высоты — исправлено GPS-2: accuracy < 10м (было 25)
                                if (location.hasAltitude() && gpsAccuracy < 10f
                                        && (SystemClock.elapsedRealtime() - lastFixMs) < 3000) {
                        gpsAltitude = (float) location.getAltitude();
                        if (!altitudeInitialized) {
                            startAltitude = gpsAltitude;
                            altitudeInitialized = true;
                        }
                        // Калибровка баро по GPS-высоте (исправлено C-08)
                        if (sensorController != null) {
                            sensorController.calibrateBaroFromGps(gpsAltitude);
                        }
                    }
                }
            }
        };
    }

    /** Запросить обновления локации (требуется permission check снаружи) */
    public void requestUpdates(Looper looper) {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.requestLocationUpdates(
                    LocationRequest.create()
                            .setInterval(1000L)
                            .setFastestInterval(500L)
                            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY),
                    locationCallback, looper);
        }
        // Register satellite status callback
        if (locationManager != null && gnssCallback != null) {
            locationManager.registerGnssStatusCallback(gnssCallback, null);
        }
    }

    public void stopGps() {
        if (locationManager != null && gnssCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
        if (fusedLocationClient != null && locationCallback != null) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback);
            } catch (SecurityException e) {
                // Ignore
            }
        }
        locationCallback = null;
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public float getSpeed() { return gpsSpeed; }
    public float getHeading() { return gpsHeading; }
    public boolean isReady() { return gpsReady && (SystemClock.elapsedRealtime() - lastFixMs) < 5000; }
    public float getAltitude() { return gpsAltitude; }
    public float getStartAltitude() { return startAltitude; }
    public boolean isAltitudeInitialized() { return altitudeInitialized; }
    public double getLat() { return gpsLat; }
    public double getLon() { return gpsLon; }
    public float getAccuracy() { return gpsAccuracy; }
    /** Возраст последнего fix (мс) — растёт, если GPS потерян (исправлено C-09: elapsedRealtime) */
    public long getFixAgeMs() { return SystemClock.elapsedRealtime() - lastFixMs; }

    /** Установить SensorController для баро-калибровки (C-08) */
    public void setSensorController(SensorController sc) { this.sensorController = sc; }

    /** Количество спутников, используемых в fix */
    public int getSatelliteCount() { return satelliteCount; }

    // ========================================================================
    // Инжекция данных для реплея IGC (аналог FlyMe o/j.a)
    // ========================================================================
    /**
     * Подать IGC-точку в GpsManager как если бы это был живой GPS-фикс.
     * Используется TrackReplayer в режиме simulation.
     * Устанавливает volatile поля напрямую, без FusedLocationProviderClient.
     */
    public void injectLocation(double lat, double lon, float altMsl,
                               float speedMs, float headingDeg, long timestampMs) {
        gpsLat = lat;
        gpsLon = lon;
        gpsAltitude = altMsl;
        gpsSpeed = speedMs;
        gpsHeading = headingDeg;
        gpsAccuracy = 5f; // симуляция: точный GPS
        lastFixMs = SystemClock.elapsedRealtime();
        gpsReady = true;

        if (!altitudeInitialized) {
            startAltitude = altMsl;
            altitudeInitialized = true;
        }
        // Калибровка баро по GPS-высоте (исправлено C-08)
        if (sensorController != null) {
            sensorController.calibrateBaroFromGps(gpsAltitude);
        }
    }
}
