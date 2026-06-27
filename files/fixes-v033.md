# Сводка фиксов v0.3.3

## 4 бага исправлено

| # | Баг | Причина | Фикс |
|---|-----|---------|------|
| 1 | **Север карты не вверху** при реплее | Heading smoothing вычислялся от live компаса ДО того как trackMode переопределял heading. `headingDisplayFinal` оставался live compass | **RadarView.java**: в trackMode полностью пропускаем heading smoothing, `headingDisplayFinal = 0f` (north-up). `a.headingDisplayInitialized = false` чтобы после реплея smoothing начался заново |
| 2 | **AGL = MSL** (неверно) | Bridge не переопределял `getLaunchAltitude()` → базовый TrackReplayer возвращал 0 → AGL = MSL - 0 = MSL | **MainActivity.java bridge**: добавлен `getLaunchAltitude()` → `df.launchAltitude` из DisplayFrame |
| 3 | **Треки полёта не рисуются** | `getTrack()` в bridge возвращал `null` с комментарием "IGC pipeline не использует старый TrackPoint" | **MainActivity.java bridge**: `getTrack()` конвертирует IGC `TrackPoint[]` → `List<TrackReplayer.TrackPoint>`. Сделан `public` конструктор `TrackReplayer.TrackPoint` |
| 4 | **L/D и дальность не считаются** | (a) L/D использовал `a.getCompassHeading()` (stale при реплее — сенсоры выключены). (b) Range брал MSL а не AGL | **(a) RadarView.java**: при trackMode используем `df.headingDeg` из DisplayFrame. **(b) RadarView.java**: `Math.max(0, altitude - launchAltitude)` |

## Файлы

- D:/Tradar/ThermalRadar-v0.3.3.apk (1 369 227 байт)
- vc102, v0.3.3

## Проверка

```bash
# 1. getLaunchAltitude в bridge
grep -n "getLaunchAltitude" app/src/main/java/com/termo1/radar/MainActivity.java
# Ожидание: 296 (override в TrackReplayerDisplayBridge)

# 2. getTrack не null
grep -n "getTrack()" app/src/main/java/com/termo1/radar/MainActivity.java
# Ожидание: 341 (возвращает List из IGC TrackPoint[], не null)

# 3. headingDisplayFinal=0 при trackMode
grep -n "headingDisplayFinal" app/src/main/java/com/termo1/radar/RadarView.java
# Ожидание: 1984+ (два присваивания: 0f для trackMode, a.headingDisplaySmoothed для live)

# 4. L/D pilotTrack из DisplayFrame при trackMode
grep -n "df.headingDeg" app/src/main/java/com/termo1/radar/RadarView.java
# Ожидание: 2269

# 5. Range: AGL а не MSL
grep -n "aglForRange = Math.max" app/src/main/java/com/termo1/radar/RadarView.java
# Ожидание: 2382
```
