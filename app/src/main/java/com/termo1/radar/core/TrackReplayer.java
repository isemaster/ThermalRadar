package com.termo1.radar.core;

import com.termo1.radar.igc.TrackPoint;
import com.termo1.radar.gps.GpsManager;
import com.termo1.radar.sensors.SensorController;
import com.termo1.radar.sensors.HeadingFilter;
import com.termo1.radar.flight.CirclingManager;
import com.termo1.radar.flight.FlightStateMachine;
import com.termo1.radar.flight.WindDriftCalculator;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * TrackReplayer — проигрывание IGC трека через LIVE-пайплайн.
 *
 * Принцип (аналогично FlyMe o/j.a + o/j.a(alt, ts)):
 *   - IGC B-рекорды подаются в GpsManager и VarioManager
 *     как если бы это были живые GPS-данные.
 *   - Vario, speed, heading вычисляются стандартными фильтрами.
 *   - Ветер — через CirclingManager (дрейф спиралей) как в live.
 *   - Термики — через CirclingManager (isCircling + showRedCore).
 *
 * Bridge-совместимость: все методы, которые переопределяет
 * TrackReplayerDisplayBridge в MainActivity, имеют реализации
 * на основе IGC-трека или live-менеджеров.
 */
public class TrackReplayer {

    // ========================================================================
    // Внутренний класс TrackPoint для совместимости с RadarView (bridge)
    // ========================================================================
    public static class TrackPoint {
        public final double lat, lon;
        public final float altMeters;   // display altitude (m)
        public final float timeSec;     // seconds from start
        public TrackPoint(double lat, double lon, float altMeters, float timeSec) {
            this.lat = lat; this.lon = lon;
            this.altMeters = altMeters; this.timeSec = timeSec;
        }
    }

    // ========================================================================
    // Конфигурация
    // ========================================================================
    private float playbackSpeed = 2.0f;

    // ========================================================================
    // Движки (инжектятся снаружи — те же, что и в live-режиме)
    // ========================================================================
    private GpsManager gpsManager;
    private SensorController sensorCtrl;
    private HeadingFilter headingFilter;
    private CirclingManager circlingMgr;
    private FlightStateMachine flightFSM;
    private WindDriftCalculator windCalc;

    // ========================================================================
    // IGC трек
    // ========================================================================
    private List<TrackPoint> track;
    private int currentIdx;
    private double pilotLat, pilotLon;
    private float pilotAlt;

    // Вспомогательные для расчётов в advanceStep
    private float simSpeedMs;        // скорость из IGC сегмента
    private float simHeadingDeg;     // курс из IGC сегмента
    private float simVarioMs;        // варио из IGC сегмента
    private float launchAltitude;    // высота первой точки
    private float lastFeedAltMs;     // последняя поданная высота (для vario bridge)

    // Для seekTo — сохранённая копия текущей позиции
    private float currentAltInterp;
    private float currentHeadingInterp;

    private long igcBaseTimeMs;
    private float trackDurationSec;
    private float timeOffsetSec;     // timeSec первой точки

    // ========================================================================
    // Состояние реплея
    // ========================================================================
    private boolean running;
    private boolean finished;
    private boolean paused;
    private double simTimeSec;
    private double lastRealTimeMs;
    private float avgDtSec;

    // ========================================================================
    // Ветер (копия из CirclingManager для UI)
    // ========================================================================
    private float windFromDeg = -1f;
    private float windSpeedMs = 0f;

    // Термики (из CirclingManager)
    private boolean showRedCore;
    private boolean thermalActive;
    private float thermalBearingDeg;
    private float thermalDistM;
    private boolean circlingDetected;

    // Guidance
    private String guidanceText = "";

    // Совместимость с bridge (hasSensorData, accel)
    private boolean hasSensorData;
    private float accelX, accelY;

    // ========================================================================
    // Конструктор
    // ========================================================================
    public TrackReplayer() {}

    // ========================================================================
    // Инжект зависимостей
    // ========================================================================
    public void setGpsManager(GpsManager gpsManager) {
        this.gpsManager = gpsManager;
    }

    public void setSensorController(SensorController sensorCtrl) {
        this.sensorCtrl = sensorCtrl;
    }

    public void setHeadingFilter(HeadingFilter headingFilter) {
        this.headingFilter = headingFilter;
    }

    public void setCirclingManager(CirclingManager circlingMgr) {
        this.circlingMgr = circlingMgr;
    }

    public void setFlightStateMachine(FlightStateMachine flightFSM) {
        this.flightFSM = flightFSM;
    }

    public void setWindDriftCalculator(WindDriftCalculator windCalc) {
        this.windCalc = windCalc;
    }

    // ========================================================================
    // Регулировка скорости
    // ========================================================================
    public void setSpeed(float speed) {
        this.playbackSpeed = Math.max(0.5f, Math.min(20f, speed));
    }
    public float getPlaybackSpeed() { return playbackSpeed; }

    // ========================================================================
    // Совместимость (bridge: setHasSensorData, setAirspeedMs, setWind)
    // ========================================================================
    public void setHasSensorData(boolean v) { this.hasSensorData = v; }
    public void setAirspeedMs(float v) {}
    public void setWind(float fromDeg, float speedMs) {
        this.windFromDeg = fromDeg;
        this.windSpeedMs = speedMs;
    }

    // ========================================================================
    // Загрузка IGC
    // ========================================================================
    public boolean loadFile(String filePath) {
        try {
            loadFromIGC(new FileInputStream(filePath));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return track != null && track.size() >= 2;
    }

    /** Загрузка встроенного демо-трека */
    public void loadEmbeddedDemoTrack(android.content.Context context) {
        try {
            loadFromIGC(context.getResources().openRawResource(
                    com.termo1.radar.R.raw.track_replay));
            android.util.Log.i("TERMO1_REPLAY", "Loaded embedded demo track: " +
                    (track != null ? track.size() : 0) + " points");
        } catch (Exception e) {
            e.printStackTrace();
            if (track == null) track = new ArrayList<>();
            android.util.Log.e("TERMO1_REPLAY", "Failed to load embedded demo track", e);
        }
    }

    /** Загрузка ZIP с сенсорами (bridge-совместимость) */
    public boolean loadSensorZip(String zipPath) {
        // В FlyMe-стиле ZIP не обязателен — IGC даёт всё необходимое
        return false;
    }

    private void loadFromIGC(InputStream inputStream) {
        track = new ArrayList<>();
        long dayOffset = 0;
        float prevTimeSec = -1f;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) != 'B') continue;
                if (line.length() < 35) continue;

                try {
                    // BHHMMSSDDMMmmmNDDDMMmmmEAPPPGGGGG
                    char fixValid = line.charAt(24);
                    if (fixValid != 'A') continue;

                    String t = line.substring(1, 7);
                    int hh = Integer.parseInt(t.substring(0, 2));
                    int mm = Integer.parseInt(t.substring(2, 4));
                    int ss = Integer.parseInt(t.substring(4, 6));
                    float timeSec = hh * 3600f + mm * 60f + ss;

                    // Midnight crossing
                    if (prevTimeSec >= 0) {
                        float candidate = timeSec + dayOffset * 86400f;
                        if (candidate < prevTimeSec - 3600f) {
                            dayOffset++;
                            candidate = timeSec + dayOffset * 86400f;
                        }
                        timeSec = candidate;
                    }
                    prevTimeSec = timeSec;

                    // Lat: DDMMmmm N/S
                    float latDeg = Float.parseFloat(line.substring(7, 9));
                    float latMin = Float.parseFloat(line.substring(9, 14)) / 1000f;
                    double lat = latDeg + latMin / 60.0;
                    if (line.charAt(14) == 'S') lat = -lat;

                    // Lon: DDDMMmmm E/W
                    float lonDeg = Float.parseFloat(line.substring(15, 18));
                    float lonMin = Float.parseFloat(line.substring(18, 23)) / 1000f;
                    double lon = lonDeg + lonMin / 60.0;
                    if (line.charAt(23) == 'W') lon = -lon;

                    if (lat == 0.0 && lon == 0.0) continue;

                    // Altitude: pressure preferred, fallback GPS
                    int pressAlt = 0, gpsAlt = 0;
                    try { pressAlt = Integer.parseInt(line.substring(25, 30).trim()); } catch (Exception ignored) {}
                    if (line.length() >= 35) {
                        try { gpsAlt = Integer.parseInt(line.substring(30, 35).trim()); } catch (Exception ignored) {}
                    }

                    float dispAlt = (pressAlt > 0) ? (float)pressAlt : (float)gpsAlt;
                    track.add(new TrackPoint(lat, lon, dispAlt, timeSec));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (track == null || track.size() < 2) return;

        // Определение средней частоты
        float dtSum = 0;
        int dtCount = 0;
        for (int i = 1; i < Math.min(track.size(), 30); i++) {
            float dt = track.get(i).timeSec - track.get(i - 1).timeSec;
            if (dt > 0 && dt < 5f) { dtSum += dt; dtCount++; }
        }
        avgDtSec = dtCount > 0 ? dtSum / dtCount : 1f;

        timeOffsetSec = track.get(0).timeSec;
        trackDurationSec = track.get(track.size() - 1).timeSec - timeOffsetSec;
        launchAltitude = track.get(0).altMeters;
    }

    // ========================================================================
    // Управление воспроизведением
    // ========================================================================
    public void start() {
        if (track == null || track.size() < 2) return;
        running = true;
        finished = false;
        paused = false;
        simTimeSec = 0;
        simSpeedMs = 0;
        simHeadingDeg = 0;
        simVarioMs = 0;
        currentIdx = 0;
        lastRealTimeMs = -1;
        lastFeedAltMs = -1;
        TrackPoint first = track.get(0);
        pilotLat = first.lat;
        pilotLon = first.lon;
        pilotAlt = first.altMeters;
        currentAltInterp = first.altMeters;
        currentHeadingInterp = 0;
        showRedCore = false;
        thermalActive = false;
        thermalBearingDeg = 0;
        thermalDistM = 0;
        circlingDetected = false;
        guidanceText = "";

        // Первая точка → live pipeline (инициализация)
        feedToLivePipeline(first.lat, first.lon, first.altMeters,
                igcBaseTimeMs + (long)((first.timeSec - timeOffsetSec) * 1000));
    }

    public void stop() {
        running = false;
        finished = true;
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public void setPaused(boolean v) { paused = v; }
    public boolean isPaused() { return paused; }

    public boolean isRunning() { return running; }
    public boolean isFinished() { return finished; }

    // ========================================================================
    // Перемотка (FlyMe: позиционирование по треку)
    // ========================================================================
    public void seekTo(float timeSec) {
        if (track == null || track.size() < 2) return;
        simTimeSec = Math.max(0, Math.min(timeSec, trackDurationSec));
        float targetTrackTime = timeOffsetSec + (float) simTimeSec;

        // Найти сегмент
        currentIdx = 0;
        for (int i = 0; i < track.size() - 1; i++) {
            if (targetTrackTime >= track.get(i).timeSec
                    && targetTrackTime < track.get(i + 1).timeSec) {
                currentIdx = i;
                break;
            }
        }

        // Интерполяция позиции
        TrackPoint a = track.get(currentIdx);
        TrackPoint b = track.get(Math.min(currentIdx + 1, track.size() - 1));
        float segLen = b.timeSec - a.timeSec;
        float t = (segLen > 0.001f) ? (targetTrackTime - a.timeSec) / segLen : 0;
        if (t > 1) t = 1;
        if (t < 0) t = 0;

        pilotLat = a.lat + (b.lat - a.lat) * t;
        pilotLon = a.lon + (b.lon - a.lon) * t;
        currentAltInterp = a.altMeters + (b.altMeters - a.altMeters) * t;
        pilotAlt = currentAltInterp;

        // Heading по направлению сегмента
        double dLat = b.lat - a.lat;
        double dLon = b.lon - a.lon;
        double dLonAdj = dLon * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0));
        currentHeadingInterp = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
        if (currentHeadingInterp < 0) currentHeadingInterp += 360;
        simHeadingDeg = currentHeadingInterp;

        // Speed из сегмента
        if (segLen > 0.001f) {
            double distM = haversineM(a.lat, a.lon, b.lat, b.lon);
            simSpeedMs = (float)(distM / segLen);
        }

        // Vario из сегмента
        if (segLen > 0.001f) {
            simVarioMs = (b.altMeters - a.altMeters) / segLen;
        }

        // Подать точку в live pipeline
        feedToLivePipeline(pilotLat, pilotLon, currentAltInterp,
                igcBaseTimeMs + (long)(targetTrackTime * 1000));
    }

    // ========================================================================
    // Главный метод — звать ~50-100 Гц из таймера
    // ========================================================================
    public void update(long elapsedRealtimeMs) {
        if (!running || paused || track == null || track.size() < 2) return;

        // dt реального времени
        if (lastRealTimeMs < 0) {
            lastRealTimeMs = elapsedRealtimeMs;
            return;
        }
        double realDtSec = (elapsedRealtimeMs - lastRealTimeMs) / 1000.0;
        if (realDtSec <= 0 || realDtSec > 0.05) realDtSec = 0.02;
        lastRealTimeMs = elapsedRealtimeMs;

        double simDt = realDtSec * playbackSpeed;

        // Sub-step для больших скачков
        double maxSimDt = avgDtSec * 3;
        int subSteps = (int) Math.ceil(Math.abs(simDt) / maxSimDt);
        if (subSteps < 1) subSteps = 1;
        double subDt = simDt / subSteps;

        for (int step = 0; step < subSteps; step++) {
            advanceStep(subDt);
            if (!running) break;
        }
    }

    /** Один под-шаг (аналог FlyMe o/j.a — обработка очередного B-рекорда) */
    private void advanceStep(double dt) {
        simTimeSec += dt;

        float currentTrackTime = timeOffsetSec + (float) simTimeSec;
        float lastTrackTime = track.get(track.size() - 1).timeSec;

        if (currentTrackTime >= lastTrackTime) {
            running = false;
            finished = true;
            return;
        }

        // Находим B-рекорд, который должен быть "сейчас"
        int targetIdx = currentIdx;
        while (targetIdx < track.size() - 1
                && track.get(targetIdx + 1).timeSec <= currentTrackTime) {
            targetIdx++;
        }

        // Если дошли до следующего B-рекорда — подаём в LIVE-пайплайн
        if (targetIdx > currentIdx) {
            for (int i = currentIdx + 1; i <= targetIdx; i++) {
                TrackPoint p = track.get(i);
                feedToLivePipeline(p.lat, p.lon, p.altMeters,
                        igcBaseTimeMs + (long)((p.timeSec - timeOffsetSec) * 1000));
            }
            currentIdx = targetIdx;
        }

        // Текущая позиция (интерполирована между B-рекордами)
        TrackPoint p0 = track.get(currentIdx);
        if (currentIdx < track.size() - 1) {
            TrackPoint p1 = track.get(currentIdx + 1);
            float segLen = p1.timeSec - p0.timeSec;
            if (segLen > 0.001f) {
                float frac = (currentTrackTime - p0.timeSec) / segLen;
                if (frac > 1) frac = 1;
                if (frac < 0) frac = 0;
                pilotLat = p0.lat + (p1.lat - p0.lat) * frac;
                pilotLon = p0.lon + (p1.lon - p0.lon) * frac;
                currentAltInterp = p0.altMeters + (p1.altMeters - p0.altMeters) * frac;
                pilotAlt = currentAltInterp;

                // Heading по сегменту
                double dLat = p1.lat - p0.lat;
                double dLon = p1.lon - p0.lon;
                double dLonAdj = dLon * Math.cos(Math.toRadians((p0.lat + p1.lat) / 2.0));
                currentHeadingInterp = (float) Math.toDegrees(Math.atan2(dLonAdj, dLat));
                if (currentHeadingInterp < 0) currentHeadingInterp += 360;
                simHeadingDeg = currentHeadingInterp;

                // Speed из сегмента
                double distM = haversineM(p0.lat, p0.lon, p1.lat, p1.lon);
                simSpeedMs = (float)(distM / segLen);

                // Vario из сегмента (запас — если live pipeline не дал)
                simVarioMs = (p1.altMeters - p0.altMeters) / segLen;
            } else {
                pilotLat = p0.lat;
                pilotLon = p0.lon;
                pilotAlt = p0.altMeters;
                currentAltInterp = p0.altMeters;
            }
        } else {
            pilotLat = p0.lat;
            pilotLon = p0.lon;
            pilotAlt = p0.altMeters;
            currentAltInterp = p0.altMeters;
        }

        // Забираем ветер из CirclingManager (как в live)
        if (circlingMgr != null) {
            float cmWind = circlingMgr.getWindFromDeg();
            float cmSpeed = circlingMgr.getWindSpeedMs();
            if (cmWind >= 0 && cmSpeed > 0.3f) {
                windFromDeg = cmWind;
                windSpeedMs = cmSpeed;
            }

            // Термики (из CirclingManager)
            showRedCore = circlingMgr.isShowThermalLabel();
            thermalActive = circlingMgr.isCircling();
            circlingDetected = circlingMgr.isCircling();
        }
    }

    // ========================================================================
    // Подача IGC-точки в LIVE-пайплайн (аналог FlyMe o/j.a + o/j.a(alt,ts))
    // ========================================================================
    private void feedToLivePipeline(double lat, double lon, float altMs, long timestampMs) {
        // 1. GPS координаты — в GpsManager
        if (gpsManager != null) {
            gpsManager.injectLocation(lat, lon, altMs, 0f, 0f, timestampMs);
        }

        // 2. Высота → VarioManager
        if (sensorCtrl != null && sensorCtrl.getVarioManager() != null) {
            sensorCtrl.getVarioManager().injectAltitudeMsl(altMs);
        }

        lastFeedAltMs = altMs;
    }

    // ========================================================================
    // Геттеры для UI (полный набор для bridge-совместимости)
    // ========================================================================

    // --- Позиция ---
    public double getLat() { return pilotLat; }
    public double getLon() { return pilotLon; }

    // --- Высота ---
    public float getAltitude() {
        // Если есть VarioManager — берём filtered alt оттуда (гладкий)
        if (sensorCtrl != null && sensorCtrl.getVarioManager() != null) {
            return sensorCtrl.getVarioManager().getAltFiltered();
        }
        return currentAltInterp;
    }
    public float getLaunchAltitude() { return launchAltitude; }

    // --- Скорость ---
    public float getSpeed() {
        // Из GpsManager если доступен
        if (gpsManager != null) {
            float gpsSpd = gpsManager.getSpeed();
            if (gpsSpd > 0.1f) return gpsSpd;
        }
        return simSpeedMs;
    }

    // --- Vario ---
    public float getVario() {
        // Из VarioManager (live pipeline)
        if (sensorCtrl != null) {
            float liveVario = sensorCtrl.getVario();
            if (Math.abs(liveVario) > 0.01f) return liveVario;
        }
        return simVarioMs;
    }

    // --- Heading / Compass ---
    public float getHeading() {
        if (gpsManager != null) {
            float gpsHdg = gpsManager.getHeading();
            if (gpsHdg >= 0) return gpsHdg;
        }
        return currentHeadingInterp;
    }
    public float getCompassHeading() {
        return 0f; // north-up для реплея
    }

    // --- Ветер ---
    public float getWindFromDeg() { return windFromDeg; }
    public float getWindSpeedMs() { return windSpeedMs; }

    // --- Термики ---
    public boolean isThermalActive() { return thermalActive; }
    public boolean isShowRedCore() { return showRedCore; }
    public float getThermalBearing() { return thermalBearingDeg; }
    public float getThermalDistance() { return thermalDistM; }
    public float getThermalRadius() { return 25f; }

    // --- Guidance ---
    public String getGuidanceText() { return guidanceText; }
    public void setGuidanceText(String t) { this.guidanceText = t; }

    // --- Время ---
    public float getCurrentTime() { return (float) simTimeSec; }
    public float getTotalTime() { return trackDurationSec; }
    public float getProgress() {
        if (trackDurationSec <= 0) return 0;
        return (float) Math.min(1.0, simTimeSec / trackDurationSec);
    }
    public double getSimTimeSec() { return simTimeSec; }

    // --- Трек (для рисования полилинии) ---
    public List<TrackPoint> getTrack() {
        if (track == null) return null;
        // Возвращаем IGC трек в формате TrackReplayer.TrackPoint
        List<TrackPoint> result = new ArrayList<>(track.size());
        for (TrackPoint p : track) {
            result.add(p);
        }
        return result;
    }

    // --- Сенсоры (bridge compat) ---
    public boolean hasRealAccel() { return hasSensorData; }
    public float getAccelX() { return accelX; }
    public float getAccelY() { return accelY; }
    public boolean hasSensorData() { return hasSensorData; }

    // ========================================================================
    // Утилиты
    // ========================================================================
    private static float bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1R = Math.toRadians(lat1);
        double lat2R = Math.toRadians(lat2);
        double y = Math.sin(dLon) * Math.cos(lat2R);
        double x = Math.cos(lat1R) * Math.sin(lat2R)
                - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon);
        float b = (float) Math.toDegrees(Math.atan2(y, x));
        if (b < 0) b += 360;
        return b;
    }

    /** Haversine distance in meters */
    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
