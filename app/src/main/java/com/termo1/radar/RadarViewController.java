package com.termo1.radar;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.map.StaticMapLoader;
import com.termo1.radar.model.ThermalBlip;
import com.termo1.radar.sensors.SensorController;
import com.termo1.radar.ui.RadarRenderer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * RadarViewController — подготовка данных для RadarRenderer.
 * Собирает thermalsCopy, heading, vario, wind, sectorData
 * из FlightController и ReplayController, передаёт в renderer.
 *
 * Исправлено P2: выделен из MainActivity.
 */
public class RadarViewController {

    private final RadarRenderer renderer;
    private final List<ThermalBlip> thermalsCopy = new ArrayList<>();
    private final Object thermalLock;

    private float headingDisplaySmoothed;
    private boolean headingDisplayInitialized;
    private long lastHeadingFrameMs;

    public RadarViewController(RadarRenderer renderer, Object thermalLock) {
        this.renderer = renderer;
        this.thermalLock = thermalLock;
    }

    public RadarRenderer getRenderer() { return renderer; }
    public List<ThermalBlip> getThermalsCopy() { return thermalsCopy; }

    /** Обновить heading smoothing (EMA) */
    public float smoothHeading(float rawHeading, long nowMs) {
        if (!headingDisplayInitialized) {
            headingDisplaySmoothed = rawHeading;
            headingDisplayInitialized = true;
            lastHeadingFrameMs = nowMs;
            return rawHeading;
        }
        long dtMs = nowMs - lastHeadingFrameMs;
        lastHeadingFrameMs = nowMs;
        float smoothingFactor = 0.15f + 0.10f * Math.min(dtMs / 33f, 3f);
        if (smoothingFactor < 0.01) smoothingFactor = 0.01f;
        float diff = ((rawHeading - headingDisplaySmoothed + 540f) % 360f) - 180f;
        headingDisplaySmoothed = (float)((headingDisplaySmoothed + diff * smoothingFactor + 360f) % 360f);
        return headingDisplaySmoothed;
    }

    public void resetHeading() {
        headingDisplayInitialized = false;
        headingDisplaySmoothed = 0;
    }

    /** Скопировать термики из общего списка в renderer-safe список (deep copy с reuse) */
    public void syncThermals(List<ThermalBlip> source) {
        synchronized (thermalLock) {
            int srcSize = source.size();
            // Убедимся что thermalsCopy имеет достаточно объектов для переиспользования
            while (thermalsCopy.size() < srcSize) {
                thermalsCopy.add(new ThermalBlip());
            }
            // Удаляем лишние
            while (thermalsCopy.size() > srcSize) {
                thermalsCopy.remove(thermalsCopy.size() - 1);
            }
            for (int ti = 0; ti < srcSize; ti++) {
                thermalsCopy.get(ti).set(source.get(ti));
            }
            // Удаляем мёртвые из source
            long nowMs = System.currentTimeMillis();
            source.removeIf(tb -> !tb.isAlive(nowMs));
        }
    }
}
