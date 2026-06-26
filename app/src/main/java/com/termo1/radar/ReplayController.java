package com.termo1.radar;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.termo1.radar.core.TrackReplayer;
import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;

import java.util.List;

/**
 * ReplayController — управление реплеем IGC-треков.
 * Создаёт TrackReplayer, управляет trackTask (20ms), seek/speed/pause.
 * Взаимоисключение с записью (startManualLogging проверяет trackMode).
 *
 * Исправлено P2: выделен из MainActivity.
 */
public class ReplayController {

    public interface Callback {
        void onReplayStarted(String trackName);
        void onReplayFinished();
        void onThermalBlip(ThermalBlip blip);
    }

    private static final float[] PLAYBACK_SPEEDS = {1f, 2f, 5f, 10f};

    private TrackReplayer trackReplayer;
    private boolean trackMode;
    private String trackFileName;
    private int trackSpeedIdx;
    private long trackStartMs;
    private long trackPrevFrameMs;

    private final Handler trackHandler = new Handler(Looper.getMainLooper());
    private Runnable trackTask;
    private final Callback callback;

    public ReplayController(Callback callback) {
        this.callback = callback;
    }

    public boolean isTrackMode() { return trackMode; }
    public TrackReplayer getTrackReplayer() { return trackReplayer; }
    public int getTrackSpeedIdx() { return trackSpeedIdx; }
    public float getPlaybackSpeed() { return trackReplayer != null ? trackReplayer.getPlaybackSpeed() : 1f; }

    /**
     * Запустить реплей IGC-файла.
     * @param filePath путь к IGC-файлу, или null для встроенного демо-трека
     * @param prefs SharedPreferences для airspeed_ms
     * @param gpsManager для остановки GPS при реплее
     * @param sensorCtrl для остановки сенсоров при реплее
     * @return true если реплей запущен
     */
    public boolean start(String filePath, android.content.SharedPreferences prefs,
                         GpsManager gpsManager, SensorController sensorCtrl) {
        trackMode = true;
        trackStartMs = SystemClock.elapsedRealtime();

        trackReplayer = new TrackReplayer();
        trackReplayer.setSpeed(1.0f);

        boolean hasSensorZip = false;
        if (filePath != null) {
            boolean loaded = trackReplayer.loadFile(filePath);
            if (!loaded) {
                trackMode = false;
                trackReplayer = null;
                return false;
            }
            String zipPath = filePath.replace(".igc", ".zip");
            hasSensorZip = new java.io.File(zipPath).exists();
        } else {
            trackReplayer.loadEmbeddedDemoTrack();
        }
        trackReplayer.setHasSensorData(hasSensorZip);
        trackReplayer.setAirspeedMs(prefs.getFloat("airspeed_ms", 9.5f));
        trackReplayer.start();
        trackPrevFrameMs = 0;

        // Останавливаем GPS и сенсоры
        gpsManager.stopGps();
        sensorCtrl.unregisterSensors();

        trackFileName = filePath != null ? new java.io.File(filePath).getName() : "встроенный";
        trackSpeedIdx = 0;

        trackTask = new Runnable() {
            @Override
            public void run() {
                TrackReplayer tr = trackReplayer;
                if (!trackMode || tr == null) return;
                if (tr.isFinished()) {
                    stop(gpsManager, sensorCtrl);
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
                tr.update(realDeltaMs);

                // Feed accel through thermal detector (только с реальными ZIP-данными)
                // Вызывается через callback — внешний код кормит ThermalDetector

                // Thermal blips from replayer
                if (tr.isThermalActive() && callback != null) {
                    long nowMs = System.currentTimeMillis();
                    float strength = tr.isShowRedCore() ? 8f : 4f;
                    ThermalBlip tb = new ThermalBlip(
                            tr.getThermalBearing(),
                            strength,
                            tr.getThermalDistance(),
                            "track", nowMs);
                    tb.lifeMs = tr.isShowRedCore() ? 12000L : (strength > 3f ? 8000L : 3000L);
                    callback.onThermalBlip(tb);
                }

                if (!tr.isPaused()) {
                    trackHandler.postDelayed(this, 20);
                }
            }
        };
        trackHandler.postDelayed(trackTask, 20);

        callback.onReplayStarted(trackFileName);
        return true;
    }

    /** Остановить реплей. */
    public void stop(GpsManager gpsManager, SensorController sensorCtrl) {
        trackMode = false;
        // Возвращаем GPS и сенсоры к жизни
        gpsManager.startGps();
        gpsManager.setSensorController(sensorCtrl);
        try {
            gpsManager.requestUpdates(Looper.getMainLooper());
        } catch (SecurityException e) {}
        sensorCtrl.registerSensors();

        if (trackTask != null) {
            trackHandler.removeCallbacks(trackTask);
            trackTask = null;
        }
        if (trackReplayer != null) {
            trackReplayer.stop();
            trackReplayer = null;
        }
        callback.onReplayFinished();
    }

    /** Переключить скорость воспроизведения. */
    public void cycleSpeed() {
        if (trackReplayer == null) return;
        trackSpeedIdx = (trackSpeedIdx + 1) % PLAYBACK_SPEEDS.length;
        trackReplayer.setSpeed(PLAYBACK_SPEEDS[trackSpeedIdx]);
    }

    public float getCurrentSpeed() {
        return PLAYBACK_SPEEDS[trackSpeedIdx];
    }

    public void seekTo(float timeSec) {
        if (trackReplayer != null) trackReplayer.seekTo(timeSec);
    }

    public void setPaused(boolean paused) {
        if (trackReplayer != null) trackReplayer.setPaused(paused);
    }

    public boolean isPaused() {
        return trackReplayer != null && trackReplayer.isPaused();
    }

    public void clear() {
        trackMode = false;
        if (trackTask != null) {
            trackHandler.removeCallbacks(trackTask);
            trackTask = null;
        }
        trackReplayer = null;
    }

    /** Получить имя файла трека. */
    public String getTrackFileName() { return trackFileName; }
}
