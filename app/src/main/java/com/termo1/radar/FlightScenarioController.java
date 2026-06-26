package com.termo1.radar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.termo1.radar.core.FlightSimulator;
import com.termo1.radar.core.SimulationManager;
import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.ui.SettingsActivity;

import java.util.List;

/**
 * FlightScenarioController — управление симуляцией и сценарием полёта.
 * Simulation (75s demo) + FlightScenario (тестовый полёт).
 */
public class FlightScenarioController {

    public interface Callback {
        void onScenarioEnd();
        void onThermalBlip(float axG, float ayG);
    }

    private boolean simMode;
    private boolean scenarioMode;
    private SimulationManager simulation;
    private FlightSimulator flightSim;
    private long simStartMs;
    private long scenarioStartMs;
    private final Handler simHandler = new Handler(Looper.getMainLooper());
    private Runnable simTask;
    private final Handler scenarioHandler = new Handler(Looper.getMainLooper());
    private Runnable scenarioTask;
    private final Callback callback;

    public FlightScenarioController(Callback callback) {
        this.callback = callback;
    }

    public boolean isSimMode() { return simMode; }
    public boolean isScenarioMode() { return scenarioMode; }
    public SimulationManager getSimulation() { return simulation; }
    public FlightSimulator getFlightSim() { return flightSim; }
    public long getSimStartMs() { return simStartMs; }
    public long getScenarioStartMs() { return scenarioStartMs; }

    public void startSimulation(SimulationManager sim) {
        this.simulation = sim;
        simMode = true;
        simStartMs = SystemClock.elapsedRealtime();
        simTask = () -> {
            if (!simMode || simulation == null) return;
            if (!simulation.isRunning()) {
                callback.onScenarioEnd();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - simStartMs;
            if (elapsed > 75000) {
                simulation.stop();
                callback.onScenarioEnd();
                return;
            }
            simulation.update(elapsed);
            if (callback != null) callback.onThermalBlip(simulation.getAccelX(), simulation.getAccelY());
            simHandler.postDelayed(simTask, 20);
        };
        simHandler.postDelayed(simTask, 20);
    }

    public void stopSimulation() {
        simMode = false;
        if (simTask != null) simHandler.removeCallbacks(simTask);
        simulation = null;
    }

    public void startScenario() {
        scenarioMode = true;
        scenarioStartMs = SystemClock.elapsedRealtime();
        flightSim = new FlightSimulator();
        flightSim.start();
        scenarioTask = () -> {
            if (!scenarioMode || flightSim == null) return;
            if (!flightSim.isRunning()) {
                stopScenario();
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - scenarioStartMs;
            flightSim.update(elapsed);
            if (callback != null) callback.onThermalBlip(flightSim.getAccelX(), flightSim.getAccelY());
            scenarioHandler.postDelayed(scenarioTask, 20);
        };
        scenarioHandler.postDelayed(scenarioTask, 20);
    }

    public void stopScenario() {
        scenarioMode = false;
        if (scenarioTask != null) scenarioHandler.removeCallbacks(scenarioTask);
        flightSim = null;
    }

    public void clear() {
        stopSimulation();
        stopScenario();
    }
}
