package com.termo1.radar;

import com.termo1.radar.core.ThermalDetector;
import com.termo1.radar.flight.VarioThermalDetector;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;

import java.util.List;

/**
 * SensorDataHandler — обработка данных сенсоров.
 * Принимает сырые данные LINEAR_ACCEL, кормит ThermalDetector,
 * управляет списком блипов, VarioThermalDetector.
 */
public class SensorDataHandler {

    private static final int THERMAL_LIMIT = 12;

    private final ThermalDetector thermalDetector;
    private final VarioThermalDetector varioThermalDetector;
    private final List<ThermalBlip> thermals;
    private final Object thermalLock;

    public SensorDataHandler(ThermalDetector thermalDetector,
                             VarioThermalDetector varioThermalDetector,
                             List<ThermalBlip> thermals,
                             Object thermalLock) {
        this.thermalDetector = thermalDetector;
        this.varioThermalDetector = varioThermalDetector;
        this.thermals = thermals;
        this.thermalLock = thermalLock;
    }

    /** Обработать сэмпл LINEAR_ACCEL. Вызывается из sensorDataListener. */
    public void processAccel(float axG, float ayG, float azG, long nowMs) {
        if (thermalDetector == null) return;

        thermalDetector.processSample(axG, ayG);
        ThermalBlip detBlip = thermalDetector.getCurrentBlip();

        if (detBlip != null && nowMs - detBlip.bornMs < detBlip.lifeMs) {
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

    /** Обработать gravity accel (fallback для устройств без LINEAR_ACCEL). */
    public void processGravityAccel(float axMs2, float ayMs2, float azMs2, long nowMs) {
        // Same as processAccel but with gravity data converted to g
        processAccel(axMs2 / 9.81f, ayMs2 / 9.81f, azMs2 / 9.81f, nowMs);
    }

    /** Обновить VarioThermalDetector. */
    public boolean updateVarioThermal(float varioMs, long nowMs, boolean isCircling) {
        if (varioThermalDetector != null) {
            return varioThermalDetector.update(varioMs, nowMs, isCircling);
        }
        return false;
    }

    /** Сбросить термалы при старте реплея/сценария. */
    public void clearThermals() {
        synchronized (thermalLock) {
            thermals.clear();
        }
    }

    public ThermalDetector getThermalDetector() { return thermalDetector; }
}
