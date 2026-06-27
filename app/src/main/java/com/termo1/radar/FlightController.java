package com.termo1.radar;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.flight.CirclingManager;
import com.termo1.radar.flight.FlightStateMachine;
import com.termo1.radar.flight.LiftDatabase;
import com.termo1.radar.flight.ThermalBaseEstimator;
import com.termo1.radar.flight.ThermalBaseEstimator.ThermalBaseResult;
import com.termo1.radar.flight.ThermalLocator;
import com.termo1.radar.flight.VarioThermalDetector;
import com.termo1.radar.flight.WindDriftCalculator;
import com.termo1.radar.flight.WindDriftCalculator.WindCorrected;
import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.logging.IgcLogger;
import com.termo1.radar.logging.LogManager;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;
import com.termo1.radar.ui.VarioSoundManager;
import com.termo1.radar.ui.VoiceController;

import java.util.List;

/**
 * FlightController — полётная логика: термо-детекция, крутка, ветер, логгеры.
 * Содержит bgTask (10 Гц), ThermalDetector, CirclingManager, LiftDatabase,
 * FlightStateMachine, ThermalLocator, LogManager, IgcLogger.
 *
 * Исправлено: выделен из MainActivity.
 */
public class FlightController {

    public interface Callback {
        void onFlightStarted();
        void onFlightFinished();
        void onThermalBlip(ThermalBlip blip);
        float getCompassHeading();
    }

    private static final long BG_INTERVAL_MS = 100L;
    private static final long THERMAL_BASE_INTERVAL_MS = 5000;

    private volatile boolean running;
    private volatile boolean trackMode;
    private volatile boolean testMode;
    private volatile boolean simMode;
    private volatile boolean scenarioMode;
    private volatile boolean userPaused;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Runnable bgTask;

    private final ThermalDetector thermalDetector;
    private final CirclingManager circlingManager;
    private final LiftDatabase liftDatabase;
    private final ThermalLocator thermalLocator;
    private final VarioThermalDetector varioThermalDetector;
    private final FlightStateMachine flightStateMachine;
    private final LogManager logManager;
    private final IgcLogger igcLogger;
    private final SensorController sensorController;
    private final GpsManager gpsManager;
    private final SharedPreferences prefs;
    private final Callback callback;

    private final List<ThermalBlip> thermals;
    private final Object thermalLock = new Object();

    private VarioSoundManager varioSoundManager;
    private float varioThreshold = 0.5f;

    private float prevThermalLiftBaseline = -1.5f;
    private long lastThermalBaseCalcMs;
    private ThermalBaseResult lastThermalBaseResult;
    private WindCorrected lastDrift;

    private static final int THERMAL_LIMIT = 12;
    private static final long THERMAL_BEEP_INTERVAL_MS = 6000L;
    private long lastThermalBeepRealMs;
    private long lastThermalBeepMs;

    public void setVarioSoundManager(VarioSoundManager vsm) { this.varioSoundManager = vsm; }
    public void setVarioThreshold(float t) { this.varioThreshold = t; }

    public FlightController(ThermalDetector thermalDetector,
                            CirclingManager circlingManager,
                            LiftDatabase liftDatabase,
                            ThermalLocator thermalLocator,
                            VarioThermalDetector varioThermalDetector,
                            FlightStateMachine flightStateMachine,
                            LogManager logManager,
                            IgcLogger igcLogger,
                            SensorController sensorController,
                            GpsManager gpsManager,
                            SharedPreferences prefs,
                            List<ThermalBlip> thermals,
                            Callback callback) {
        this.thermalDetector = thermalDetector;
        this.circlingManager = circlingManager;
        this.liftDatabase = liftDatabase;
        this.thermalLocator = thermalLocator;
        this.varioThermalDetector = varioThermalDetector;
        this.flightStateMachine = flightStateMachine;
        this.logManager = logManager;
        this.igcLogger = igcLogger;
        this.sensorController = sensorController;
        this.gpsManager = gpsManager;
        this.prefs = prefs;
        this.thermals = thermals;
        this.callback = callback;
    }

    public void setFlags(boolean trackMode, boolean testMode, boolean simMode, boolean scenarioMode) {
        this.trackMode = trackMode;
        this.testMode = testMode;
        this.simMode = simMode;
        this.scenarioMode = scenarioMode;
    }

    public void setUserPaused(boolean paused) {
        this.userPaused = paused;
    }

    public void start() {
        running = true;
        bgThread = new HandlerThread("termo1-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgTask = this::processBg;
        bgHandler.postDelayed(bgTask, BG_INTERVAL_MS);
    }

    public void stop() {
        running = false;
        if (bgHandler != null) bgHandler.removeCallbacks(bgTask);
        if (bgThread != null) { bgThread.quitSafely(); bgThread = null; }
    }

    private void processBg() {
        if (!running) return;
        long bgNow = SystemClock.elapsedRealtime();

        // Vario sound (live only)
        if (!trackMode && varioSoundManager != null) {
            varioSoundManager.update(sensorController.getVario());
        }

        // AUTO-3/4: разрешаем FSM только если UI активен ИЛИ уже летим
        boolean allowAutostart = !userPaused || flightStateMachine.isFlying();

        // Flight state machine (live only, baro-based)
        if (allowAutostart && !testMode && !simMode && !scenarioMode && !trackMode) {
            flightStateMachine.update(
                    sensorController.getAltitudeRaw(), bgNow);
        }

        // Speed-based flight detection
        if (allowAutostart && !testMode && !simMode && !scenarioMode && !trackMode
                && gpsManager.isReady()
                && gpsManager.getFixAgeMs() < 5000) {
            flightStateMachine.updateSpeedBased(
                    gpsManager.getSpeed(),
                    gpsManager.getLat(),
                    gpsManager.getLon(),
                    sensorController.getAltitudeRaw(),
                    bgNow);
        }

        if (!trackMode) {
            // Circling manager
            circlingManager.update(
                sensorController.getGyroX(),
                sensorController.getGyroY(),
                sensorController.getGyroZ(),
                sensorController.getGravityX(),
                sensorController.getGravityY(),
                sensorController.getGravityZ(),
                callback.getCompassHeading(),
                gpsManager.getHeading(),
                sensorController.getVario(),
                (double) gpsManager.getLat(),
                (double) gpsManager.getLon(),
                gpsManager.getSpeed(),
                gpsManager.getHeading(),
                gpsManager.getAltitude(),
                bgNow);

            // ThermalLocator + LiftDatabase
            if (circlingManager.isCircling()) {
                float varioVal = sensorController.getVario();
                float altMsl = gpsManager.getAltitude();
                if (!Float.isNaN(varioVal) && !Float.isInfinite(varioVal)) {
                    liftDatabase.recordLift(callback.getCompassHeading(), varioVal);
                }
                double baseline = varioThermalDetector != null
                        ? varioThermalDetector.getBaseline() : prevThermalLiftBaseline;
                thermalLocator.addPoint(
                        gpsManager.getLat(), gpsManager.getLon(),
                        varioVal, baseline, bgNow);

                if (circlingManager.getWindFromDeg() >= 0) {
                    double windRad = Math.toRadians(circlingManager.getWindFromDeg() + 180);
                    double windU = circlingManager.getWindSpeedMs() * Math.sin(windRad);
                    double windV = circlingManager.getWindSpeedMs() * Math.cos(windRad);
                    thermalLocator.update(gpsManager.getLat(), gpsManager.getLon(),
                            windU, windV, bgNow);
                } else {
                    thermalLocator.update(gpsManager.getLat(), gpsManager.getLon(),
                            0, 0, bgNow);
                }

                if (bgNow - lastThermalBaseCalcMs > THERMAL_BASE_INTERVAL_MS) {
                    lastThermalBaseCalcMs = bgNow;
                    float climbAvg = varioVal > 0 ? varioVal : 0f;
                    if (circlingManager.getWindFromDeg() >= 0 && climbAvg > 0.2f) {
                        lastThermalBaseResult = ThermalBaseEstimator.estimate(
                                gpsManager.getLat(), gpsManager.getLon(),
                                altMsl, climbAvg,
                                circlingManager.getWindFromDeg(),
                                circlingManager.getWindSpeedMs());
                    }
                }
            } else {
                thermalLocator.reset();
                liftDatabase.clear();
                lastThermalBaseResult = null;
            }

            // WindDriftCalculator
            if (circlingManager.getWindFromDeg() >= 0 && circlingManager.getWindSpeedMs() > 0.3f) {
                float airspeed = prefs.getFloat("airspeed_ms", 9.5f);
                lastDrift = WindDriftCalculator.calculate(
                        gpsManager.getHeading(), airspeed,
                        circlingManager.getWindFromDeg(),
                        circlingManager.getWindSpeedMs());
            } else {
                lastDrift = null;
            }

            // Wind estimation from straight flight
            if (!circlingManager.isCircling()
                    && Math.abs(sensorController.getVario()) <= 1.3f
                    && gpsManager.isReady() && gpsManager.getSpeed() > 0.5f
                    && gpsManager.getFixAgeMs() < 5000) {
                float heading = callback.getCompassHeading();
                circlingManager.estimateWindFromGps(
                        heading, gpsManager.getHeading(),
                        gpsManager.getSpeed(),
                        prefs.getFloat("airspeed_ms", 9.5f));
            }
        }

        bgHandler.postDelayed(bgTask, BG_INTERVAL_MS);
    }

    /** processSample — сборка данных thermal detector + thermals */
    public void processSample(float axG, float ayG) {
        if (thermalDetector != null) {
            thermalDetector.processSample(axG, ayG);
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
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public ThermalDetector getThermalDetector() { return thermalDetector; }
    public CirclingManager getCirclingManager() { return circlingManager; }
    public LiftDatabase getLiftDatabase() { return liftDatabase; }
    public ThermalLocator getThermalLocator() { return thermalLocator; }
    public VarioThermalDetector getVarioThermalDetector() { return varioThermalDetector; }
    public FlightStateMachine getFlightStateMachine() { return flightStateMachine; }
    public LogManager getLogManager() { return logManager; }
    public IgcLogger getIgcLogger() { return igcLogger; }
    public List<ThermalBlip> getThermals() { return thermals; }
    public Object getThermalLock() { return thermalLock; }
    public ThermalBaseResult getLastThermalBaseResult() { return lastThermalBaseResult; }
    public WindCorrected getLastDrift() { return lastDrift; }
    public boolean isRunning() { return running; }
}
