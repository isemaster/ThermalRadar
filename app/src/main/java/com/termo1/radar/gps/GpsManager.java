package com.termo1.radar.gps;

import android.content.Context;
import android.location.Location;
import android.os.Looper;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

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

    public GpsManager(Context context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
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
                    lastFixMs = System.currentTimeMillis();
                    // Отсев неточных фиксов для стартовой высоты
                    if (location.hasAltitude() && gpsAccuracy < 25f) {
                        gpsAltitude = (float) location.getAltitude();
                        if (!altitudeInitialized) {
                            startAltitude = gpsAltitude;
                            altitudeInitialized = true;
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
    }

    public void stopGps() {
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
    public boolean isReady() { return gpsReady; }
    public float getAltitude() { return gpsAltitude; }
    public float getStartAltitude() { return startAltitude; }
    public boolean isAltitudeInitialized() { return altitudeInitialized; }
    public double getLat() { return gpsLat; }
    public double getLon() { return gpsLon; }
    public float getAccuracy() { return gpsAccuracy; }
    /** Возраст последнего fix (мс) — растёт, если GPS потерян */
    public long getFixAgeMs() { return System.currentTimeMillis() - lastFixMs; }
}
