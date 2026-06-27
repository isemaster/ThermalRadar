# IGC-as-Truth Pipeline — Архитектура

## Принцип

IGC-файл = единый источник правды. Live полёт и реплей чужих треков — один pipeline.

## Схема

```
┌─────────────────────────────────────────────────────────────┐
│                    IGC-as-Truth Pipeline                     │
└─────────────────────────────────────────────────────────────┘

LIVE FLIGHT:
  GPS(1Hz) + baro(5Hz) ──→ IgcLogger ──→ IGC-файл (диск)
                               │              │
                               │         [inotify/таймер]
                               │              │
                               └──→ RingBuffer ──→ IGCParser ←── CHUZHOY IGC
                                       (B-record)      │
                                                       ↓
                                                  TrackPoint[]
                                                       ↓
                                                  IGCAnalyzer
                                                   (scroll window)
                                                       ↓
                                                  DisplayFrame
                                                   (immutable)
                                                       ↓
                                                  RadarView.onDraw()
                                                  (field snapshot)

ACCEL BLIPS (add-on, если есть companion ZIP):
  AccelThermalDetector ←── ZIP-cache ←── LogManager (50Гц CSV)
       │
       └──→ List<ThermalBlip> (отдельный, не через IGC)
                ↓
           RadarView.onDraw()  // мержит с IGC-блипами

REPLAY:
  Чужой IGC ──→ IGCParser ──→ IGCAnalyzer ──→ DisplayFrame ──→ RadarView
  (тот же pipeline, только не перезапускается каждые 200мс)
```

## Новые классы

### IGCParser.java (~400 строк)
- static `parse(InputStream)` → `TrackPoint[]`
- static `parse(String filePath)` → `TrackPoint[]`
- Fault-tolerant: пробелы, "00000", LXNav/Flytec/Naviter форматы
- H-record: парсит QNH для baro correction
- G-record: проверяет CRC
- Обрабатывает midnight crossing
- Сохраняет и GPS alt и pressure alt
- Расшифровывает E-records (FXD extensions)

### TrackPoint.java (расширенный)
```java
public class TrackPoint {
    public final double lat, lon;
    public final float pressAltM;    // pressure altitude (m)
    public final float gpsAltM;      // GPS altitude (m)
    public final float timeSec;      // seconds from start of flight
    public final boolean fixValid;   // 'A' or 'V'
    
    // computed for display
    public float displayHeightM;     // GPS alt preferred, fallback pressure
}
```

### IGCAnalyzer.java (~600 строк)
- Принимает `TrackPoint[]` — весь трек целиком
- Метод `analyzeAt(float timeSec)` → `DisplayFrame`
- Скользящие окна:
  - **Speed:** segment distance / GPS interval, EMA 0.7
  - **Vario:** pressure alt delta, EMA 0.25
  - **Heading:** atan2 from position delta, meridian correction
  - **L/D:** 8s window, net displacement / baro vario integral
  - **Circling:** heading accumulation over 30s window
  - **Wind from circling drift:** centroid drift between spirals
  - **Wind from straight flight:** GPS speed ± airspeed
- Метод `getTotalTime()` — длительность трека
- Метод `seekTo(float timeSec)` — перемотка (great-circle interp)

### DisplayFrame.java (immutable snapshot)
```java
public class DisplayFrame {
    public final double pilotLat, pilotLon;
    public final float altitudeMsl;      // m
    public final float altitudeAgl;      // m (MSL - startAlt)
    public final float speedMs;          // m/s (current)
    public final float speedKmh;         // km/h (computed)
    public final float varioMs;          // m/s (smoothed EMA)
    public final float avgVario30;       // m/s (30s avg)
    public final float headingDeg;       // degrees
    public final float windFromDeg;      // meteo FROM
    public final float windSpeedMs;      // m/s
    public final float glideRatio;       // L/D
    public final float glideRangeKm;     // range
    public final boolean isCircling;
    public final float thermalBearing;   // to thermal center
    public final float thermalDistM;     // to thermal center
    public final float progress;         // 0..1
    
    // Track polyline (cached once, not per frame)
    public final float[] trackPolyPx;
    public final float[] trackPolyPy;
    public final int[] trackPolyColors;
    public final int trackPolyCount;
    
    // Trail (buffer, adds 1Hz)
    public final float[] trailPx, trailPy;
    public final int[] trailColors;
    public final int trailCount;
    
    // Thermal blips (from IGC baro analysis + accel add-on)
    public final List<ThermalBlip> blips;
    
    // HUD strings (pre-formatted)
    public final String speedStr, varioStr, windStr;
    public final String altMslStr, altAglStr, avgVarioStr;
    public final String flightTimeStr;
    public final String glideRatioStr, glideRangeStr;
    public final String statusStr;
}
```

### AccelThermalDetector.java (add-on, ~300 строк)
- Адаптер: читает companion ZIP если есть
- Параллельно 50Гц: берет ax/ay → ThermalDetector.processSample()
- Публикует List<ThermalBlip>
- Только если ZIP файл доступен (live или replay)

## Что меняется в существующих файлах

### IgcLogger.java (+ memory buffer)
- Добавить RingBuffer последних 60 B-records (60s x 1Hz = 60 записей)
- После каждого flush → сигнал "новые данные IGC готовы"

### MainActivity.java (оркестровка)
- `startTrackReplay()` → вместо TrackReplayer использует IGCParser + IGCAnalyzer
- `renderTask` → читает DisplayFrame, не поля напрямую
- Убрать `trackMode` гейты (кроме start/stop GPS)
- Live: таймер 5Hz → читает RingBuffer → IGCAnalyzer → DisplayFrame

### Заменяется/удаляется:
- ❌ TrackReplayer.java — заменён IGCParser + IGCAnalyzer
- ❌ CirclingManager.java — логика круток в IGCAnalyzer
- ❌ ThermalDetector.java — только как AccelThermalDetector add-on
- ❌ SignalProcessor.java — только для accel
- ❌ VarioThermalDetector.java
- ❌ ThermalLocator.java
- ❌ LiftDatabase.java — float[12] в IGCAnalyzer
- ❌ WindStore.java — в IGCAnalyzer
- ❌ WindGradientDescent.java
- ❌ ThermalBaseEstimator.java
- ⚡ VarioManager.java — урезается (только baro для live)
- ⚡ FlightController.java — урезается (только IGC-логгинг + accel)
- ⚡ SensorController.java — урезается (компас для heading display)

### RadarView.java / RadarRenderer.java
- Вместо `a.trackReplayer.getXxx()`, `a.gpsManager.getXxx()` → `displayFrame.xxx`
- Вместо `if (trackMode)` — DisplayFrame уже содержит все данные
- Кэширование: polyline pre-calced в DisplayFrame
- dirty flag для trackPoly вместо пересчёта каждый кадр

## Порядок имплементации

### Фаза 1: IGC Core (сегодня)
1. `TrackPoint.java` — расширенная модель
2. `IGCParser.java` — парсинг любого IGC + тест на чужих треках
3. `DisplayFrame.java` — immutable snapshot
4. `IGCAnalyzer.java` — speed, vario, heading, L/D, circling, wind
5. Интеграция в реплей: MainActivity.startTrackReplay() → IGCParser + IGCAnalyzer вместо TrackReplayer
6. RadarView → читает DisplayFrame

### Фаза 2: Live Flight (следующая сессия)
7. IgcLogger RingBuffer → IGCAnalyzer каждые 200ms
8. Live DisplayFrame → RadarView
9. AccelThermalDetector add-on

### Фаза 3: Удаление мёртвого кода
10. Удалить TrackReplayer, CirclingManager (old), ThermalDetector (old)
11. Урезать VarioManager, FlightController, SensorController
