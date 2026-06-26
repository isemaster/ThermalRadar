package com.termo1.radar.ui;

import android.os.SystemClock;

import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.core.TrackReplayer;
import com.termo1.radar.flight.VarioThermalDetector;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;

/**
 * StatusManager — вычисление статуса полёта (ПОИСК, СПИРАЛЬ, ТЕРМИК, СНИЖЕНИЕ).
 * Выделен из MainActivity (updateStatus).
 */
public class StatusManager {

    private String currentStatus = "ПОИСК";

    public String getStatus() { return currentStatus; }

    /** Обновить статус на основе текущих данных. */
    public void update(boolean trackMode, TrackReplayer trackReplayer,
                       boolean simMode, ThermalDetector thermalDetector,
                       boolean scenarioMode, com.termo1.radar.core.FlightSimulator flightSim,
                       SensorController sensorController,
                       VarioThermalDetector varioThermalDetector,
                       float varioThreshold,
                       android.content.SharedPreferences prefs,
                       VarioSoundManager varioSoundManager) {
        if (trackMode) {
            if (trackReplayer != null && trackReplayer.isRunning()) {
                float v = trackReplayer.getVario();
                if (trackReplayer.isThermalActive() || v > 1.0f) {
                    currentStatus = UiManager.STATUS_CLIMB;
                } else if (v < -1.0f) {
                    currentStatus = UiManager.STATUS_SINK;
                } else {
                    currentStatus = UiManager.STATUS_SEARCH;
                }
            }
            return;
        }
        if (simMode) {
            currentStatus = thermalDetector != null
                    ? thermalDetector.getStatusText() : UiManager.STATUS_SEARCH;
            return;
        }
        if (thermalDetector == null) {
            currentStatus = UiManager.STATUS_SEARCH;
            return;
        }

        ThermalBlip activeBlip = thermalDetector.getCurrentBlip();
        boolean hasConfirmedBlip = (activeBlip != null)
                && thermalDetector.isBlipConfirmed()
                && (SystemClock.elapsedRealtime() - activeBlip.bornMs < activeBlip.lifeMs);

        if (hasConfirmedBlip && (thermalDetector.getStatus() == ThermalDetector.STATUS_THERMAL
                || thermalDetector.getStatus() == ThermalDetector.STATUS_INSIDE)) {
            currentStatus = String.format(java.util.Locale.US, "ТЕРМИК РЯДОМ — %.0fм", activeBlip.distance);
        } else {
            float vario = sensorController.getVario();
            if (scenarioMode && flightSim != null && flightSim.isRunning()) {
                vario = flightSim.getVario();
            } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
                vario = trackReplayer.getVario();
            }
            float level = 0f;
            if (thermalDetector != null) {
                com.termo1.radar.core.SignalProcessor sp = thermalDetector.getSignalProcessor();
                if (sp != null) level = sp.getTurbulenceMs2();
            }

            boolean varioThermal = false;
            if (varioThermalDetector != null && !simMode && !scenarioMode && !trackMode) {
                long now = SystemClock.elapsedRealtime();
                varioThermalDetector.update(vario, now);
                float vt = prefs.getFloat("vario_threshold", 0.5f);
                varioThermalDetector.setThreshold(vt);
                if (varioSoundManager != null) {
                    varioSoundManager.setDeadBandHigh(vt);
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
                currentStatus = UiManager.STATUS_SPIRAL;
            } else if (vario < -1.0f) {
                currentStatus = UiManager.STATUS_SINK;
            } else if (thermalDetector.getStatus() == ThermalDetector.STATUS_SUSPECT) {
                currentStatus = UiManager.STATUS_SPIRAL;
            } else {
                currentStatus = UiManager.STATUS_SEARCH;
            }
        }
    }
}
