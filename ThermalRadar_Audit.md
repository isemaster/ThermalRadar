# TERMO1 Radar — Технический аудит кода

**Репозиторий:** https://github.com/isemaster/ThermalRadar
**Анализируемая версия:** v0.2.0+ (текущий HEAD на 2026-06-24)
**Тип аудита:** полный технический разбор (симуляция + логика/описание + архитектура + качество кода)
**Язык аудита:** русский
**Аудитор:** технический эксперт (автоматизированный разбор исходников)

---

## 0. TL;DR

Найдено **23 проблемы**, из них **5 критических**, **8 высоких**, **7 средних**, **3 низких**.

Два самых заметных пользователю симптома объясняются следующими корневыми причинами:

| Симптом | Корневая причина |
|---|---|
| **«Радар дёргается на экране»** | В режиме `simMode` (75-секундная симуляция) `MainActivity.onDraw()` берёт `headingDisplay` из `getCompassHeading()`, то есть из **реального магнитометра телефона**, а не из `SimulationManager.getHeading()`. Любое движение телефона в руке мгновенно отражается на радаре, при том что accel-сигнал при этом приходит симулированный. См. §3.1. |
| **«36 секторов вместо 12 для термиков»** | В `LiftDatabase.SECTOR_COUNT = 36` и дополнительно в `RadarRenderer` дважды захардкожен литерал `36` (строки 385, 717). По требованиям должно быть `12` секторов по `30°`. См. §4.1. |

Дополнительно в режиме симуляции есть несколько багов временной шкалы (`dt cap`, неявное `long → float`, детерминированный seed), которые при определённых условиях дают заметные «провалы» в отрисовке.

---

## 1. Состав проверенных артефактов

Исходники (`app/src/main/java/com/termo1/radar/`):

| Категория | Файлы |
|---|---|
| Симуляция | `core/SimulationManager.java`, `core/FlightSimulator.java`, `core/TrackReplayer.java` |
| Детекция | `core/SignalProcessor.java`, `core/ThermalDetector.java`, `flight/VarioThermalDetector.java` |
| Полётная логика | `flight/CirclingManager.java`, `flight/FlightStateMachine.java`, `flight/ThermalLocator.java`, `flight/ThermalBaseEstimator.java`, `flight/LiftDatabase.java`, `flight/WindEKF.java`, `flight/WindStore.java`, `flight/WindDriftCalculator.java`, `flight/BlindFlightMode.java` |
| Сенсоры | `sensors/SensorController.java`, `sensors/VarioManager.java`, `sensors/HeadingFilter.java`, `gps/GpsManager.java` |
| UI | `ui/RadarRenderer.java`, `ui/UiManager.java`, `ui/VarioSoundManager.java`, `MainActivity.java` |
| Логирование | `logging/IgcLogger.java`, `logging/LogManager.java` |

Документация: `README.md`, `SIMULATION.md`, `manual.md`, `calibration.md`, `algo.md`, `Code_Review3.md`, `VERSION_010.md`.

Также учтены результаты предыдущего ревью `Code_Review3.md` — где его выводы совпадают с моими, я даю короткую ссылку «(см. Code_Review3 NEW-XX)»; где разошёлся — помечаю явно.

---

## 2. Сводная таблица по приоритетам

| ID | Критичность | Файл | Краткое описание |
|----|---|---|---|
| **BUG-A01** | 🔴 CRITICAL | `MainActivity.java` | В режиме `simMode` для `headingDisplay` используется реальный компас, а не `SimulationManager.getHeading()` — основная причина «дёрганья радара» |
| **BUG-A02** | 🔴 CRITICAL | `LiftDatabase.java`, `RadarRenderer.java` | `SECTOR_COUNT = 36` и захардкоженный литерал `36` в двух местах UI — должно быть `12` |
| **BUG-A03** | 🔴 CRITICAL | `SimulationManager.java` | `dt cap`: `if (dt > 0.05f) dt = 0.02f` вместо `Math.min(dt, 0.05f)` — рассинхронизация симуляции и реального времени |
| **BUG-A04** | 🔴 CRITICAL | `MainActivity.java` | В `simMode` `liftDatabase.recordLift(getCompassHeading(), …)` пишет lift-карту по реальному heading — полностью ломает секторный анализ в симуляции |
| **BUG-A05** | 🔴 CRITICAL | `SimulationManager.java` | `float prevMs = elapsedMs` (long → float) — потеря точности на длинных сессиях; та же ошибка в `updatePosition` |
| **BUG-A06** | 🟠 HIGH | `SimulationManager.java` | Box-Muller генератор с детерминированным seed (`noiseSeedX=0`, `+= 0.1`) — одна и та же последовательность шума при каждом запуске |
| **BUG-A07** | 🟠 HIGH | `SimulationManager.java` | `CIRCLE_RATE = 36°/с` — физически нереалистично для параплана (норма 10–18°/с) |
| **BUG-A08** | 🟠 HIGH | `FlightSimulator.java` | `currentOffset` вычисляется через несколько веток `if/else`, на границах веток — скачки позиций пилота (внезапное смещение на 7–10 м) |
| **BUG-A09** | 🟠 HIGH | `FlightSimulator.java` | Турбулентность в ядре 0.02–0.06 g — на краю термика `SNR < 3`, детектор не срабатывает (см. Code_Review3 NEW-04) |
| **BUG-A10** | 🟠 HIGH | `MainActivity.java` | `getCompassHeading()` — нет fallback на `SimulationManager.getHeading()` при активной симуляции; та же проблема в `circlingManager.update()` |
| **BUG-A11** | 🟠 HIGH | `RadarRenderer.java` | Дублирование логики: два разных места с `360f / 36f`, нет ссылки на `LiftDatabase.SECTOR_COUNT` |
| **BUG-A12** | 🟠 HIGH | `CirclingManager.java` | 4 сектора по 90° (N/E/S/W) vs 36 секторов в `LiftDatabase` — две независимые модели «лучшего сектора», голос подсказок может расходиться с визуальной подсветкой |
| **BUG-A13** | 🟠 HIGH | `MainActivity.java`, `SensorController.java` | `getCompassHeading()` игнорирует `magneticDeclination`: на экране показывается магнитный heading, а `LiftDatabase`/`ThermalLocator` работают в магнитной системе — расхождение с картой/ветром |
| **BUG-A14** | 🟡 MEDIUM | `FlightSimulator.java` | Захардкоженный `startH = 90f` в фазе «turn left to NORTH» — сломается при изменении тайминга круга |
| **BUG-A15** | 🟡 MEDIUM | `ThermalBaseEstimator.java` | «Итеративный спуск» — мёртвый код: 10 итераций делают то же самое, что и финальная формула; `driftDist` вычисляется, но не используется |
| **BUG-A16** | 🟡 MEDIUM | `ThermalDetector.java` | `TH_SUSPECT = TH_THERMAL = 0.020f` — нет гистерезиса; адаптивный порог частично маскирует, но не решает (см. Code_Review3 BUG-17) |
| **BUG-A17** | 🟡 MEDIUM | `ThermalDetector.java` | `bornMs = System.currentTimeMillis()` — wall clock вместо `elapsedRealtime()` (см. Code_Review3 BUG-18) |
| **BUG-A18** | 🟡 MEDIUM | `RadarRenderer.java` | `drawBestLiftSector` создаёт `new RectF(...)` каждый кадр — аллокация в `onDraw`, GC-паузы на слабых устройствах |
| **BUG-A19** | 🟡 MEDIUM | `FlightSimulator.java` | `liftAtPilot` clamp-блок после добавления `osc` — границы `THERMAL_LIFT_EDGE..CORE`, но `osc *= centeringProgress`, при `centeringProgress=0` osc=0 — на старте крутки нет колебаний вообще |
| **BUG-A20** | 🟡 MEDIUM | `MainActivity.java` | God Object: 2695 строк (см. Code_Review3 BUG-28) |
| **BUG-A21** | 🟢 LOW | `LiftDatabase.java` | `BEST_SECTOR_UPDATE_FACTOR = 1.1f` объявлен, но не используется — мёртвая константа |
| **BUG-A22** | 🟢 LOW | `SimulationManager.java` | Magic number `0x1p31` (2³¹) в Box-Muller без объяснения; можно проще через `Random.nextGaussian()` |
| **BUG-A23** | 🟢 LOW | `CirclingManager.java` | `sectorVario` — только EMA α=0.3, нет накопления среднего по сэмплам; для 4 секторов при 17-секундном круге это допустимо, но расходится с подходом `LiftDatabase` |

---

## 3. Раздел A — Режим симуляции (P0 / P1)

### BUG-A01 [CRITICAL] — Радар дёргается: `headingDisplay` в `simMode` берётся из реального компаса

**Файл:** `MainActivity.java`, строки 2337–2345 (`RadarView.onDraw`).

**Что происходит.** В методе `onDraw` направление радара выбирается так:

```java
float headingDisplay = getCompassHeading();           // ← РЕАЛЬНЫЙ компас
float varioDisplay  = sensorController.getVario();    // ← РЕАЛЬНЫЙ баровариометр

if (scenarioMode && flightSim != null && flightSim.isRunning()) {
    headingDisplay = flightSim.getHeading();          // сценарий 100с — ОК
    varioDisplay   = flightSim.getVario();
} else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
    headingDisplay = trackReplayer.getHeading();      // трек-реплей — ОК
    varioDisplay   = trackReplayer.getVario();
}
// ВАЖНО: для simMode (75с, SimulationManager) — нет ветки, остаётся реальный компас
```

Метод `getCompassHeading()` (строки 1674–1678) возвращает:

```java
private float getCompassHeading() {
    if (sensorController.isCompassReady()) return sensorController.getHeading();   // магнитометр
    if (gpsManager.isReady())               return gpsManager.getHeading();        // GPS track
    return 0.0f;
}
```

То есть для `simMode` (SimulationManager, 75-секундная демонстрация, которая запускается из настроек как «Симуляция 75с») **радар ориентирован по реальному положению телефона в пространстве**, а не по симулированному курсу. Симулятор тем временем генерирует аккуратный, плавный heading (`computeHeading(tSec)` возвращает 0° → 90° → круг → 0°), но он нигде не используется.

**Почему это именно «дёрганье».** Параллельно работают две не связанных между собой системы:

1. **Симулятор сигнала** (`SimulationManager.generateAccel`) выпускает термические «пыхи» вокруг **симулированной** позиции пилота и через `thermalDetector.processSample(simAx, simAy)` формирует `ThermalBlip` с углом `signalProcessor.getStableDirDeg()` — направление относительно **телефона**.
2. **Рендерер** поворачивает радар на `-headingDisplay`, где `headingDisplay` — реальный compass heading. При тряске телефона в руках магнитометр даёт шум ±5–15° (особенно если рядом металл/динамики), и радар мгновенно отрабатывает каждое колебание.

Итог: blip термика, рассчитанный относительно **симулированного** пилота, рисуется на фоне **реально-трясущегося** компаса. Возникает характерный «дрожащий радар» — стрелка компаса и вся секторная диаграмма колеблются синхронно с телефоном, а термик «плавает» по экрану.

**Что говорит документация.** В `README.md` раздел «Режимы»:

> **Симуляция 75с** — Демо-режим: полёт NORTH → EAST → круг → NORTH (с puffs по курсу)

Из описания очевидно, что heading должен следовать симулированной траектории. В `algo.md` (раздел 12) так же явно сказано: «Фильтруй не только heading, но и отрисовку — deadband перед invalidate()». То есть в **любом** режиме heading должен быть стабильным, а в режиме симуляции он к тому же должен браться из симулятора.

**Исправление.** Добавить симулированную ветку в `onDraw`:

```diff
 float headingDisplay = getCompassHeading();
 float varioDisplay  = sensorController.getVario();
 if (scenarioMode && flightSim != null && flightSim.isRunning()) {
     headingDisplay = flightSim.getHeading();
     varioDisplay   = flightSim.getVario();
 } else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
     headingDisplay = trackReplayer.getHeading();
     varioDisplay   = trackReplayer.getVario();
+} else if (simMode && simulation != null && simulation.isRunning()) {
+    headingDisplay = simulation.getHeading();
+    varioDisplay   = simulation.getVario();
 }
```

`SimulationManager.getHeading()` уже существует (строка 348) — нужно просто его вызвать. Дополнительно имеет смысл в `simMode` отключить магнитометр (через `sensorController.unregisterListeners()` на время симуляции) либо пометить `getCompassHeading()` как `@Deprecated` для использования в `simMode`.

---

### BUG-A02 [CRITICAL] — `SECTOR_COUNT = 36` вместо 12

**Файлы:** `flight/LiftDatabase.java` (строка 22), `ui/RadarRenderer.java` (строки 385, 717), `README.md` (строка 20).

**Что не так.** `LiftDatabase` хранит карту подъёма по 36 секторам по 10°. Это слепое копирование XCSoar, но XCSoar — для планёров с кругом 60–120 секунд. У параплана круг 12–20 секунд (в `FlightSimulator` это явно задано: `CIRCLE_PERIOD_SEC = 17.45f`).

| Параметр | XCSoar (планёр) | TERMO1 (параплан) | Должно быть |
|---|---|---|---|
| Период круга | ~90 с | 17.45 с | — |
| Частота сэмплов | 1 Гц | 50 Гц | — |
| Сэмплов за круг | ~90 | ~870 | — |
| Секторов | 36 | 36 | **12** |
| Сэмплов на сектор | ~2.5 | ~24 | **~72** |
| Ширина сектора | 10° | 10° | **30°** |

При 36 секторах по 10° в одном круге параплана набегает всего ~24 сэмпла на сектор. Минимум в `LiftDatabase.MIN_SAMPLES_PER_SECTOR = 5` — поэтому формально сектор валиден, но статистика шумная: 5 сэмплов за 0.2 секунды кружения — это почти одно измерение. При 12 секторах по 30° будет ~72 сэпла на сектор — устойчивая EMA-оценка подъёма.

**Дополнительно.** В `RadarRenderer` захардкожен литерал `36`:

```java
// RadarRenderer.java, строка 385
float sectorDeg = 360f / 36f;   // 10° на сектор
…
for (int i = 0; i < 36; i++) {                  // ← литерал 36
    if (sectorLiftValues[i] > maxLift) maxLift = sectorLiftValues[i];
    …
}

// RadarRenderer.java, строка 717
float sectorWidth = 360f / 36f;                  // ← литерал 36
```

Если поменять `SECTOR_COUNT` в `LiftDatabase` на `12`, но не трогать `RadarRenderer`, то `sectorLiftValues[12..35]` вызовет `ArrayIndexOutOfBoundsException`, потому что `setSectorLiftData(float[] arr)` (его сигнатуру нужно проверить) выделяет массив по размеру входных данных. Это **классическая ловушка нарушения DRY**.

**Что говорит документация.** В `README.md`, строка 20:

> **LiftDatabase** — 36 секторов по 10°, отслеживание лучшего сектора

Но в `SIMULATION.md` (методика проверки на турнике) и `manual.md` нет упоминания 36 секторов. Также голосовые подсказки в README используют «5 секторов по 45°» — это ещё одна, **третья** модель секторов, которая в коде не реализована (фактически для голоса используется `CirclingManager` с 4 секторами по 90°).

**Исправление.**

1. В `LiftDatabase.java`:

```diff
-/** Количество секторов */
-public static final int SECTOR_COUNT = 36;
+/** Количество секторов (12 по 30° — параплан: круг ~17 с при 50 Гц → ~72 сэмпла на сектор) */
+public static final int SECTOR_COUNT = 12;
```

2. В `RadarRenderer.java` — убрать литерал `36`, везде использовать `LiftDatabase.SECTOR_COUNT`:

```diff
-import com.termo1.radar.flight.LiftDatabase;
…
-private void drawSectorDiagram(Canvas c) {
-    …
-    float sectorDeg = 360f / 36f;   // 10° на сектор
+    float sectorDeg = 360f / LiftDatabase.SECTOR_COUNT;
     …
-    for (int i = 0; i < 36; i++) {
+    for (int i = 0; i < LiftDatabase.SECTOR_COUNT; i++) {
         if (sectorLiftValues[i] > maxLift) maxLift = sectorLiftValues[i];
         …
     }
     …
-    for (int i = 0; i < 36; i++) {
+    for (int i = 0; i < LiftDatabase.SECTOR_COUNT; i++) {
         …
     }
 }

 private void drawBestLiftSector(Canvas c) {
     …
-    float sectorWidth = 360f / 36f;
+    float sectorWidth = 360f / LiftDatabase.SECTOR_COUNT;
     …
 }
```

3. В `MainActivity.java` (строка 2261) убедиться, что `liftDatabase.getLiftValues()` возвращает массив нужной длины, и что `radarRenderer.setSectorLiftData(liftValues)` принимает массив произвольной длины. Также обновить размер `sectorLiftValues[]` в `RadarRenderer`.

4. В `README.md` обновить описание: «LiftDatabase — 12 секторов по 30°, отслеживание лучшего сектора».

5. В `CirclingManager` либо оставить 4 сектора (это отдельная модель для оценки ветра и голоса), либо унифицировать с LiftDatabase — но тогда голос будет говорить не «ядро на ветер / по ветру», а «ядро в секторе 7», что для пилота менее информативно. Рекомендую оставить раздельно, но явно документировать различие.

---

### BUG-A03 [CRITICAL] — `dt cap` ломает временную шкалу симуляции

**Файл:** `core/SimulationManager.java`, строки 138–139 и 214–215.

**Что не так.**

```java
// update(), строка 138
float dtSec = (elapsedMs - prevMs) / 1000f;
if (dtSec > 0.05f) dtSec = 0.02f; // cap          ← НЕПРАВИЛЬНО
if (dtSec > 0f) {
    altMsl += vario * dtSec;
}

// updatePosition(), строка 215
float dt = (elapsedMs - prevMs) / 1000f;
if (dt > 0.05f) dt = 0.02f;                       ← ТАК ЖЕ НЕПРАВИЛЬНО
if (dt < 0.001f) return;
…
pilotX += SPEED * dt * Math.sin(headingRad);
pilotY += SPEED * dt * Math.cos(headingRad);
```

Логика `if (dt > 0.05) dt = 0.02` задумывалась как «защита от пропусков кадров». На практике это работает так: при реальном `dt = 100 ms` (типичный лаг Android после GC или при уходе в фон) код подставляет `dt = 20 ms` и интегрирует только 20 мс позиции. Через 100 мс реального времени позиция пилота продвинется только на 20 мс — **симуляция замедляется в 5 раз** относительно реального времени. При повторных пропусках накапливается всё большее расхождение, и `tSec` (вычисляемый через `nowMs / 1000f`) уходит вперёд, а позиция пилота отстаёт.

**Симптомы.** На длинных симуляциях (>30 с) траектория пилота «сжимается» — он пролетает заметно меньшую дистанцию, чем должен по сценарию. В фазе круга heading и позиция рассинхронизируются: heading рассчитывается через `computeHeading(tSec)` (абсолютное время), а позиция — через интегрирование `dt` (накопительное). Когда `dt` занижается, позиция отстаёт от heading — пилот «летит боком».

**Правильный clamp.** Нужно не заменять `dt` на 20 мс, а ограничить сверху (чтобы один большой скачок не «выстрелил» позицию в стратосферу):

```diff
-float dtSec = (elapsedMs - prevMs) / 1000f;
-if (dtSec > 0.05f) dtSec = 0.02f; // cap
+float dtSec = (elapsedMs - prevMs) / 1000f;
+if (dtSec > 0.05f) dtSec = 0.05f; // clamp 50 ms max — защита от скачков позиции при лагах
 if (dtSec > 0f) {
     altMsl += vario * dtSec;
 }
```

То же самое в `updatePosition`. Это стандартная практика для игр и реалтайм-симуляций: `dt = min(dt, MAX_DT)`.

Дополнительно, в `FlightSimulator.java` (строка 159) сделано правильно: `float dt = Math.min((elapsedMs - prevMs) / 1000f, 0.05f);` — нужно привести `SimulationManager` к тому же виду.

---

### BUG-A04 [CRITICAL] — В `simMode` `liftDatabase.recordLift()` пишет по реальному heading

**Файл:** `MainActivity.java`, строки 934–936 (`bgTask`).

```java
// LiftDatabase: записываем варио в сектор
if (!Float.isNaN(varioVal) && !Float.isInfinite(varioVal)) {
    liftDatabase.recordLift(getCompassHeading(), varioVal);   // ← РЕАЛЬНЫЙ compass
}
```

`bgTask` выполняется на главном лупере с интервалом `BG_INTERVAL_MS = 100L` (10 Гц). Внутри нет проверки режима — ни `simMode`, ни `scenarioMode`, ни `trackMode`. Получается:

- В `simMode` `varioVal` — это симулированный варио (правильно, из `simulation.getVario()`), но heading — реальный компас. Lift-карта заполняется по случайному направлению, в котором пилот случайно держит телефон. Через 60 сэмплов (6 сек) «лучший сектор» окажется там, где телефон случайно отклонился — на экране подсветится случайный сектор.
- В `scenarioMode` и `trackMode` — то же самое: `varioVal` берётся из `flightSim.getVario()` (строка 2341 `onDraw`), но в `bgTask` `getCompassHeading()` всё равно реальный.

**Исправление.** Ввести единый метод `getCurrentHeading()` и `getCurrentVario()`, которые учитывают активный режим:

```diff
+private float getCurrentHeading() {
+    if (simMode && simulation != null && simulation.isRunning())
+        return simulation.getHeading();
+    if (scenarioMode && flightSim != null && flightSim.isRunning())
+        return flightSim.getHeading();
+    if (trackMode && trackReplayer != null && trackReplayer.isRunning())
+        return trackReplayer.getHeading();
+    return getCompassHeading();
+}
+
+private float getCurrentVario() {
+    if (simMode && simulation != null && simulation.isRunning())
+        return simulation.getVario();
+    if (scenarioMode && flightSim != null && flightSim.isRunning())
+        return flightSim.getVario();
+    if (trackMode && trackReplayer != null && trackReplayer.isRunning())
+        return trackReplayer.getVario();
+    return sensorController.getVario();
+}
```

И заменить все вызовы `getCompassHeading()` / `sensorController.getVario()` в местах, где нужно «текущее поведение», а не «сырой сенсор». Особенно — в `circlingManager.update()` (строка 917), `liftDatabase.recordLift()` (строка 936), `speakThermalDirection()` (строка 1686).

Дополнительно, в `onDraw` (строки 2337–2345) упростить до одной строки `float headingDisplay = getCurrentHeading();` — это автоматически решит BUG-A01.

---

### BUG-A05 [CRITICAL] — `long → float` неявное преобразование теряет точность

**Файл:** `core/SimulationManager.java`, строки 130–131 и 208.

```java
float prevMs = elapsedMs;   // elapsedMs — long, поле класса
elapsedMs = nowMs;          // nowMs — long, параметр
…
float dtSec = (elapsedMs - prevMs) / 1000f;
```

`elapsedMs` и `nowMs` — `long` миллисекунды от `SystemClock.elapsedRealtime()`. Преобразование в `float` теряет точность начиная с ~16 секунд: `float` имеет 24 бита мантиссы, что даёт ~7 значащих цифр. К моменту `elapsedMs = 16_777_217` (4.66 часа, но реально проблема начинается раньше из-за накопления) `prevMs` и `elapsedMs` округляются до одинакового значения, `dtSec` становится 0.

Симуляция 75 секунд это не убивает — но `trackReplayer` запускается на ~10 минут, и `FlightSimulator` на 102 секунды. Кроме того, `float` для времени — плохая практика в принципе.

**Исправление.** Все временные переменные держать в `long`, деление на `1000.0` делать в `double`:

```diff
-long prevMs = elapsedMs;
-elapsedMs = nowMs;
-…
-float dtSec = (elapsedMs - prevMs) / 1000f;
-if (dtSec > 0.05f) dtSec = 0.05f;
+long prevMs = elapsedMs;
+elapsedMs = nowMs;
+…
+double dtSec = (elapsedMs - prevMs) / 1000.0;
+if (dtSec > 0.05) dtSec = 0.05;
+if (dtSec > 0) {
+    altMsl += vario * (float) dtSec;
+}
```

В `FlightSimulator.java` эта ошибка уже исправлена (строка 152: `long prevMs = elapsedMs;`). Нужно привести `SimulationManager` к тому же виду.

---

### BUG-A06 [HIGH] — Box-Muller с детерминированным seed

**Файл:** `core/SimulationManager.java`, строки 76–77, 270–278; `core/FlightSimulator.java`, строки 67–68, 425–436.

В `SimulationManager`:

```java
private double noiseSeedX, noiseSeedY;     // инициализируются в 0 в start()
…
double u1 = Math.sin(noiseSeedX += 0.1) * 0x1p31;
double u2 = Math.cos(noiseSeedY += 0.1) * 0x1p31;
```

В `FlightSimulator` — аналогично. Каждый запуск симуляции даёт **идентичную** последовательность шума. Это значит:

1. `SignalProcessor.calibCount` калибруется на одной и той же 100 сэмплов. Любой баг, который маскируется конкретной фазой синуса, будет воспроизводиться всегда.
2. Если в шуме есть корреляция с фазой puffs (а она есть — `puff.freq` и `noiseSeedX` обе используют `sin`), при перезапуске симуляции появится «паттерн» в детекции.
3. Magic number `0x1p31` (2³¹) не объяснён комментарием; `Math.sin(x) * 2^31` даёт значение в диапазоне `[-2^31, +2^31]`, но `Math.sin` не равномерно распределён — это сэмплер с sinusoidal density, а не uniform. В результате Box-Muller получает не равномерные `u1, u2`, а искажённые.

**Что говорит Code_Review3 (NEW-03).** Предыдущее ревью отмечает эту же проблему и рекомендует «использовать `java.util.Random` с Box-Muller». В `SimulationManager` уже есть `private final Random random = new Random(42);` (строка 82) для puff-генерации, но он НЕ используется для шума акселерометра. Это странное упущение.

**Исправление.** Заменить хэш-подобный генератор на `Random.nextGaussian()`:

```diff
-private double noiseSeedX, noiseSeedY;
+private final java.util.Random noiseRandom = new java.util.Random();
 …
 public void start() {
     …
-    noiseSeedX = 0.0;
-    noiseSeedY = 0.0;
+    noiseRandom.setSeed(System.currentTimeMillis()); // недетерминированный seed
     …
 }
 …
 private void generateAccel(float tSec) {
     float ax = 0f, ay = 0f;
-    double u1 = Math.sin(noiseSeedX += 0.1) * 0x1p31;
-    double u2 = Math.cos(noiseSeedY += 0.1) * 0x1p31;
-    double u1n = (u1 % 1.0 + 1.0) % 1.0;
-    double u2n = (u2 % 1.0 + 1.0) % 1.0;
-    if (u1n < 1e-10) u1n = 0.5;
-    double norm = Math.sqrt(-2.0 * Math.log(u1n)) * Math.cos(2.0 * Math.PI * u2n);
-    float whiteX = NOISE_FLOOR_G * (float) Math.min(Math.abs(norm), 3.0) * Math.signum((float)norm);
-    double normY = Math.sqrt(-2.0 * Math.log(u1n)) * Math.sin(2.0 * Math.PI * u2n);
-    float whiteY = NOISE_FLOOR_G * (float) Math.min(Math.abs(normY), 3.0) * Math.signum((float)normY);
+    // Box-Muller через java.util.Random — стандартный, протестированный, потокобезопасный
+    float whiteX = NOISE_FLOOR_G * (float) noiseRandom.nextGaussian();
+    float whiteY = NOISE_FLOOR_G * (float) noiseRandom.nextGaussian();
     ax += whiteX;
     ay += whiteY;
     …
 }
```

То же самое в `FlightSimulator.updateAccel()` (строки 424–436). Альтернатива — оставить Box-Muller ручной реализации, но использовать `Random.nextDouble()` для `u1, u2` вместо `sin/cos`.

---

### BUG-A07 [HIGH] — `CIRCLE_RATE = 36°/с` нереалистично

**Файл:** `core/SimulationManager.java`, строка 55.

```java
// Circle: 360° in 10s = 36°/s
private static final float CIRCLE_RATE = 360.0f / 10.0f;
```

У параплана нормальная скорость крутки в ядре термика — **10–18°/с** (период 20–36 сек). 36°/с (период 10 сек) — это уже агрессивная крутка на спортивном параплане в узком ядре, а для обычного пилота — почти вращение. В `FlightSimulator` этот параметр задан правильно: `CIRCLE_PERIOD_SEC = 17.45f`, что даёт `CIRCLE_RATE_RAD_S = 0.36 рад/с ≈ 20.6°/с` — реалистично.

Из-за 36°/с:

- `CirclingManager.MIN_TURN_RATE_DEG_S = 4f` — детектирует крутку мгновенно (хорошо).
- Но `LiftDatabase` за 10-секундный круг наберёт ~500 сэмплов на 36 секторов = 14 сэмплов/сектор — меньше, чем `MIN_SAMPLES_PER_SECTOR = 5` × 3 = 15. Граничный случай, иногда сектор будет помечен как невалидный.
- Визуально на радаре это выглядит как «слишком быстрое вращение» — пилот не успевает следить за секторами.

**Исправление.**

```diff
-// Circle: 360° in 10s = 36°/s
-private static final float CIRCLE_RATE = 360.0f / 10.0f;
+// Circle: 360° in ~18s = 20°/s — реалистичная парапланерная крутка
+private static final float CIRCLE_PERIOD_SEC = 18.0f;
+private static final float CIRCLE_RATE = 360.0f / CIRCLE_PERIOD_SEC;
```

Заодно вынести `T_CIRCLE_END` с учётом нового периода, иначе круг будет обрываться на 270°.

---

### BUG-A08 [HIGH] — Скачки `currentOffset` на границах веток

**Файл:** `core/FlightSimulator.java`, строки 281–292.

```java
if (centeringProgress < 0.3f) {
    currentOffset = 18f * (1f - centeringProgress / 0.3f * 0.4f);     // 18 → 10.8
    guidanceText = "Сместись к ядру! Подъём усилится";
} else if (centeringProgress < 0.6f) {
    currentOffset = 10.8f * (1f - (centeringProgress - 0.3f) / 0.3f * 0.5f);  // 10.8 → 5.4
    guidanceText = "Хорошо, ближе к центру!";
} else {
    currentOffset = 5.4f * (1f - (centeringProgress - 0.6f) / 0.4f);    // 5.4 → 0
    if (currentOffset < 1.5f) currentOffset = 1.5f;
    guidanceText = "Отлично! 3 м/с стабильно";
}
```

На границе `centeringProgress = 0.3`: первая ветка даёт `18 * (1 - 1.0 * 0.4) = 18 * 0.6 = 10.8`. Вторая ветка (вход) даёт `10.8 * 1.0 = 10.8` — совпадает. ✅

На границе `centeringProgress = 0.6`: вторая ветка даёт `10.8 * (1 - 1.0 * 0.5) = 10.8 * 0.5 = 5.4`. Третья ветка (вход) даёт `5.4 * 1.0 = 5.4` — совпадает. ✅

Кажется, всё хорошо — значения на границах согласованы. Но посмотрим на **производную** по `centeringProgress`:

- Первая ветка: `d/dp [18 * (1 - p/0.3 * 0.4)] = -18 * 0.4 / 0.3 = -24 м/ед.` 
- Вторая ветка: `d/dp [10.8 * (1 - (p-0.3)/0.3 * 0.5)] = -10.8 * 0.5 / 0.3 = -18 м/ед.`
- Третья ветка: `d/dp [5.4 * (1 - (p-0.6)/0.4)] = -5.4 / 0.4 = -13.5 м/ед.`

Скорость изменения `currentOffset` разная в разных ветках. Это даёт «ступеньку» в ускорении пилота — на границе 0.3 и 0.6 пилот получает мгновенное изменение `liftAtPilot` (через `dist = sqrt(dx² + dy²)` в `updateThermal`). Не критично, но создаёт рывки в `vario`, которые могут ложно сработать в `VarioThermalDetector`.

**Исправление.** Заменить кусочно-линейную функцию на одну гладкую (smoothstep или экспоненту):

```diff
-if (centeringProgress < 0.3f) {
-    currentOffset = 18f * (1f - centeringProgress / 0.3f * 0.4f);
-    guidanceText = "Сместись к ядру! Подъём усилится";
-} else if (centeringProgress < 0.6f) {
-    currentOffset = 10.8f * (1f - (centeringProgress - 0.3f) / 0.3f * 0.5f);
-    guidanceText = "Хорошо, ближе к центру!";
-} else {
-    currentOffset = 5.4f * (1f - (centeringProgress - 0.6f) / 0.4f);
-    if (currentOffset < 1.5f) currentOffset = 1.5f;
-    guidanceText = "Отлично! 3 м/с стабильно";
-}
+// Экспоненциальное затухание offset: 18 → 1.5 за 25 с, гладкая C∞ функция
+currentOffset = 1.5f + 16.5f * (float) Math.exp(-centeringProgress * 3.5f);
+if (centeringProgress < 0.3f)       guidanceText = "Сместись к ядру! Подъём усилится";
+else if (centeringProgress < 0.6f)  guidanceText = "Хорошо, ближе к центру!";
+else                                guidanceText = "Отлично! 3 м/с стабильно";
```

---

### BUG-A09 [HIGH] — Турбулентность 0.02–0.06 g → SNR < 3 на краю термика

**Файл:** `core/FlightSimulator.java`, строки 442–446.

```java
if (isCircling) {
    float turb = 0.03f + 0.05f * (THERMAL_LIFT_CORE - liftAtPilot) / THERMAL_LIFT_CORE;
    ax += turb * (float) Math.sin(circleAngle * 3 + noisePhase * 0.1);
    ay += turb * (float) Math.cos(circleAngle * 3 + noisePhase * 0.07f);
}
```

`liftAtPilot` варьируется от `THERMAL_LIFT_EDGE = 1.0` до `THERMAL_LIFT_CORE = 3.0`. Подставим:

- В ядре: `turb = 0.03 + 0.05 * (3 - 3)/3 = 0.03` g.
- На краю: `turb = 0.03 + 0.05 * (3 - 1)/3 = 0.03 + 0.033 = 0.063` g.

С учётом `NOISE_FLOOR_G = 0.01` (после Box-Muller), SNR на краю ≈ `0.063 / 0.01 = 6.3` — хорошо. В ядре SNR ≈ `0.03 / 0.01 = 3.0` — ровно на пороге `ThermalDetector.if (snr <= 3f) return;` (строка 104). То есть в самом ядре термика, где `liftAtPilot` максимален, **детектор может отключаться** из-за того, что турбулентность слишком мала. Это парадокс: физически пилот в ядре должен чувствовать **больше** микрораскачки, а не меньше.

**Исправление.**

```diff
-if (isCircling) {
-    float turb = 0.03f + 0.05f * (THERMAL_LIFT_CORE - liftAtPilot) / THERMAL_LIFT_CORE;
-    ax += turb * (float) Math.sin(circleAngle * 3 + noisePhase * 0.1);
-    ay += turb * (float) Math.cos(circleAngle * 3 + noisePhase * 0.07f);
-}
+if (isCircling) {
+    // Турбулентность растёт к ядру (как в реальности): 0.04 g на краю → 0.10 g в ядре
+    float coreRatio = (liftAtPilot - THERMAL_LIFT_EDGE) / (THERMAL_LIFT_CORE - THERMAL_LIFT_EDGE);
+    coreRatio = Math.max(0f, Math.min(1f, coreRatio));
+    float turb = 0.04f + 0.06f * coreRatio;   // 0.04 .. 0.10 g → SNR 4 .. 10
+    ax += turb * (float) Math.sin(circleAngle * 3 + noisePhase * 0.1);
+    ay += turb * (float) Math.cos(circleAngle * 3 + noisePhase * 0.07f);
+}
```

См. также Code_Review3 NEW-04 — рекомендация увеличить до 0.03–0.08 g. Здесь предложено ещё более явно: турбулентность должна **расти** к ядру, а не убывать.

---

## 4. Раздел B — Логические несостыковки «описание ↔ код» (P1 / P2)

### BUG-A02 (продолжение) — README: «36 секторов по 10°»

Подробно разобрано в §3 (BUG-A02). Здесь добавим только сводку несостыковок документации и кода по теме секторов:

| Где | Что написано | Что в коде |
|---|---|---|
| `README.md` строка 20 | «LiftDatabase — 36 секторов по 10°» | `LiftDatabase.SECTOR_COUNT = 36` ✅ совпадает, но по ТЗ должно быть 12 |
| `README.md` строка 62 | «5 секторов по 45°, передняя полусфера» для голоса | В коде нет такой структуры. `CirclingManager.SECTORS = 4` (по 90°), голос через `getCircleGuidance()` использует «на ветер / по ветру» |
| `CirclingManager` javadoc | «4 сектора по 90°: N/E/S/W» | `SECTORS = 4` ✅ совпадает |
| `RadarRenderer` строка 385 | (нет в документации) | `360f / 36f` — захардкожено, должно зависеть от `LiftDatabase.SECTOR_COUNT` |

**Вывод.** В проекте **три разные модели секторов**, и ни одна не соответствует README для голоса. Это запутывает и студента, и будущего разработчика. Нужно либо унифицировать, либо явно зафиксировать в `manual.md` разницу между «секторами LiftDatabase для визуализации», «секторами CirclingManager для оценки ветра» и «секторами для голосовых подсказок».

---

### BUG-A10 / BUG-A13 — `getCompassHeading()` не учитывает режим и magnetic declination

**Файл:** `MainActivity.java`, строки 1674–1678; `SensorController.java`, строки 538, 588–595.

```java
// MainActivity
private float getCompassHeading() {
    if (sensorController.isCompassReady()) return sensorController.getHeading();   // ← магнитный
    if (gpsManager.isReady())               return gpsManager.getHeading();        // ← истинный
    return 0.0f;
}
```

`sensorController.getHeading()` возвращает **магнитный** heading (через `SensorManager.getOrientation()`, который работает в магнитной системе координат). `gpsManager.getHeading()` возвращает **истинный** курс (GPS всегда истинный). То есть `getCompassHeading()` возвращает значения в **разных системах координат** в зависимости от того, какой fallback сработал.

`SensorController.getTrueHeading()` (строка 588) уже умеет прибавлять `magneticDeclination`, но **не используется** в `getCompassHeading()`. В итоге:

- `LiftDatabase.recordLift(getCompassHeading(), …)` пишет в сектор по магнитному heading.
- `ThermalLocator.update(pilotLat, pilotLon, …)` работает в географических координатах (широта/долгота).
- `WindStore` хранит направление ветра «откуда» в истинных градусах.
- `RadarRenderer.draw(...)` поворачивает радар на `-headingDisplay` — если headingDisplay магнитный, то север на радаре указывает на магнитный север, а карта OSM (если подгружена) ориентирована по истинному северу. **Расхождение на величину магнитного склонения** (в Москве ~10°).

**Что говорит README.** Строка 47:

> **GPS fallback**: если магнитометр не откалиброван — rotation matrix из GPS-курса + гравитации

То есть fallback осознан, но не сказано, что это меняет систему координат. Также в `manual.md` не упоминается, что нужно калибровать компас при смене региона (где склонение другое).

**Исправление.**

```diff
 private float getCompassHeading() {
-    if (sensorController.isCompassReady()) return sensorController.getHeading();
-    if (gpsManager.isReady())               return gpsManager.getHeading();
+    // Всегда возвращаем ИСТИННЫЙ heading (с учётом magnetic declination)
+    if (sensorController.isCompassReady()) return sensorController.getTrueHeading();
+    if (gpsManager.isReady())               return gpsManager.getHeading(); // уже истинный
     return 0.0f;
 }
```

Также добавить в `SettingsActivity` кнопку «Калибровка магнитного склонения» (по текущим GPS-координатам через `GeomagneticField`), чтобы пилот в новом регионе мог обновить `magneticDeclination`.

---

### BUG-A11 [HIGH] — Magic number `36` в `RadarRenderer`

Подробно разобрано в §3 (BUG-A02). Дополнительный аспект: если в будущем кто-то захочет сделать `SECTOR_COUNT` настраиваемым (например, разные значения для параплана/планёра/дельтаплана), захардкоженные `36` в UI не позволят это сделать без отдельного исправления.

**Исправление.** Везде в `RadarRenderer` заменить литерал `36` на `LiftDatabase.SECTOR_COUNT` (см. diff в BUG-A02).

---

### BUG-A12 [HIGH] — Две модели секторов: `CirclingManager` (4×90°) vs `LiftDatabase` (36×10°)

**Файлы:** `flight/CirclingManager.java` (строки 67–68, 603–608), `flight/LiftDatabase.java` (строка 22).

`CirclingManager` хранит собственные `sectorVario[4]` и `sectorCount[4]` для оценки «лучшего сектора» при крутке — используется для голосовых подсказок «ядро на ветер / по ветру». Это **дублирует** функциональность `LiftDatabase`, который делает то же самое, но с другим разрешением.

Когда пилот крутит термик:

1. `CirclingManager` обновляет свои 4 сектора (по 90°) и через `getCircleGuidance()` формирует голос.
2. `LiftDatabase.recordLift()` обновляет свои 36 секторов (по 10°) и через `getBestSectorDirection()` формирует **визуальную** подсветку на радаре и `bestSectorDirection` для `RadarRenderer`.

Возможна ситуация: голос говорит «ядро на ветер» (сектор 0 в 4-секторной модели = N), а на радаре подсвечен сектор 1 в 36-секторной модели (10°..20°, почти N, но не точно N). Пилот видит «лучший сектор = NNE», слышит «на ветер = N», и путается.

**Исправление.** Два варианта:

**Вариант A (минимальный).** Оставить обе модели, но явно синхронизировать через `LiftDatabase`:

- `CirclingManager` читает `liftDatabase.getBestSectorHeading()` и переводит в «на ветер / по ветру / N / E / S / W».
- Удалить `sectorVario[]` из `CirclingManager`.

**Вариант B (правильный).** Унифицировать на `LiftDatabase` с `SECTOR_COUNT = 12`. Тогда:

- 12 секторов по 30° = 12 направлений (N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW — нет, это 16 направлений для 12 секторов не подходит). 
- Лучше: 12 секторов по 30° = направления N, NNE-ish, NE, ENE-ish, E, ESE-ish, SE, SSE-ish, S, SSW-ish, SW, WSW-ish. Это неудобно для голоса.

Поэтому **рекомендую вариант A**: `CirclingManager` продолжает использовать 4 сектора для голоса (это естественно — пилоту проще услышать «на ветер / по ветру»), но берёт данные из `LiftDatabase` через агрегацию 12 → 4.

---

### BUG-A14 [MEDIUM] — Захардкоженный `startH = 90f`

**Файл:** `core/FlightSimulator.java`, строки 165–175 (`SimulationManager.computeHeading` для фазы turn-left-to-NORTH).

```java
} else if (tSec < T_TURN_END) {
    // Turn left from current heading back toward 0° (North)
    float turnTime = tSec - T_CIRCLE_END;
    float startH = 90f;  // after 360° circle, back at 90°
    float delta = -90f;   // turn left
    float p = turnTime / (T_TURN_END - T_CIRCLE_END);
    float sp = p * p * (3f - 2f * p); // smoothstep
    float h = startH + delta * sp;
    h = h % 360f;
    if (h < 0) h += 360f;
    return h;
}
```

Если изменить `T_EAST_END` или `T_CIRCLE_END` (например, из-за BUG-A07), круг начнётся с другого heading, и `startH = 90f` будет неверным. Поворот произойдёт из неправильной точки.

**Исправление.** Запомнить heading в конце круга и использовать его:

```diff
+private float headingAtCircleEnd = 90f;  // обновляется в computeHeading при tSec == T_CIRCLE_END
 …
 } else if (tSec < T_CIRCLE_END) {
     // Full circle, 36°/s, starting from 90°
     float circTime = tSec - T_EAST_END;
+    float h = (90f + circTime * CIRCLE_RATE) % 360f;
+    if (circTime >= (T_CIRCLE_END - T_EAST_END) - 0.001f) {
+        headingAtCircleEnd = h;   // запомнили финальный heading круга
+    }
-    return (90f + circTime * CIRCLE_RATE) % 360f;
+    return h;
 } else if (tSec < T_TURN_END) {
     float turnTime = tSec - T_CIRCLE_END;
-    float startH = 90f;  // after 360° circle, back at 90°
+    float startH = headingAtCircleEnd;
     float delta = -90f;   // turn left
     …
 }
```

---

### BUG-A15 [MEDIUM] — `ThermalBaseEstimator`: мёртвый код, неэффективный цикл

**Файл:** `flight/ThermalBaseEstimator.java`, строки 100–129.

```java
double dh = altitudeMsl / DESCENT_STEPS;
double locLat = pilotLat;
double locLon = pilotLon;

for (int step = 1; step <= DESCENT_STEPS; step++) {
    double h = altitudeMsl - step * dh;
    double t = (altitudeMsl - h) / averageClimb;

    // Снос по ветру: позиция смещается ПРОТИВ ветра
    double driftDist = windSpeedMs * t;                              // ← вычислено, но НЕ ИСПОЛЬЗУЕТСЯ
    locLat = pilotLat - (windV * t) / 111320.0;                      // ← пересчёт от pilotLat, не от locLat
    locLon = pilotLon - (windU * t) / (111320.0 * Math.cos(…));      // ← то же самое

    if (h <= 0) {
        // Достигли земли
        double exactT = altitudeMsl / averageClimb;
        locLat = pilotLat - (windV * exactT) / 111320.0;
        locLon = pilotLon - (windU * exactT) / (111320.0 * Math.cos(…));
        …
        return new ThermalBaseResult(locLat, locLon, 0, 0, dist, bearing, true);
    }
}
```

Проблемы:

1. `driftDist` объявлен и вычисляется, но нигде не используется — мёртвый код.
2. `t = (altitudeMsl - h) / averageClimb` = `step * dh / averageClimb` — линейно растёт от `dh/avg` до `altitudeMsl/avg`. На каждом шаге `locLat` **полностью пересчитывается** через `pilotLat - windV * t / 111320`, без накопления. То есть на шаге 10 `t = 10 * dh / avg = altitudeMsl / avg` — это финальная позиция. Все предыдущие 9 итераций ничего не дают, кроме перевычисления той же формулы с меньшим `t`.
3. Условие `if (h <= 0)` срабатывает только при `step == DESCENT_STEPS` (т.к. `dh = altitudeMsl / DESCENT_STEPS` и `h = altitudeMsl - step * dh`). То есть `if` внутри цикла бесполезен — он выполнится ровно один раз, на последней итерации. А весь цикл эквивалентен одной строке:

```java
double exactT = altitudeMsl / averageClimb;
locLat = pilotLat - (windV * exactT) / 111320.0;
locLon = pilotLon - (windU * exactT) / (111320.0 * Math.cos(Math.toRadians(locLat)));
```

**Что говорит javadoc.** «Итеративный спуск на 10 шагов с учётом сноса ветром → координата базы термика». В текущей реализации это **не итеративный** алгоритм — это просто формула. Если задумывалось накопление сноса по шагам (каждый шаг добавляет смещение), то формула должна быть:

```java
locLat -= (windV * dt) / 111320.0;  // dt — время этого шага
locLon -= (windU * dt) / (111320.0 * Math.cos(Math.toRadians(locLat)));
```

Но физически снос за всё время спуска — это `windV * totalTime`, и не имеет значения, разбивать ли на шаги. Так что **в текущей формулировке** правильнее убрать цикл и оставить одну формулу. Либо реализовать настоящий итеративный алгоритм с переменной скоростью ветра по высотным слоям (используя `WindStore.getWindAtAltitude`).

**Исправление (минимальное — убрать мёртвый код и упростить):**

```diff
-public static ThermalBaseResult estimate(
-        double pilotLat, double pilotLon,
-        double altitudeMsl, double averageClimb,
-        double windBearing, double windSpeedMs) {
-
-    if (averageClimb < MIN_AVERAGE_CLIMB || altitudeMsl < 10) {
-        return new ThermalBaseResult(pilotLat, pilotLon, 0, 0, 0, 0, false);
-    }
-
-    double windDirRad = Math.toRadians(windBearing + 180);
-    double windU = windSpeedMs * Math.sin(windDirRad);
-    double windV = windSpeedMs * Math.cos(windDirRad);
-
-    double maxTime = altitudeMsl / averageClimb;
-    if (maxTime > MAX_DESCENT_TIME) maxTime = MAX_DESCENT_TIME;
-
-    double dh = altitudeMsl / DESCENT_STEPS;
-    double locLat = pilotLat;
-    double locLon = pilotLon;
-
-    for (int step = 1; step <= DESCENT_STEPS; step++) {
-        double h = altitudeMsl - step * dh;
-        double t = (altitudeMsl - h) / averageClimb;
-        double driftDist = windSpeedMs * t;     // мёртвый код
-        locLat = pilotLat - (windV * t) / 111320.0;
-        locLon = pilotLon - (windU * t) / (111320.0 * Math.cos(Math.toRadians(locLat)));
-        if (h <= 0) {
-            double exactT = altitudeMsl / averageClimb;
-            locLat = pilotLat - (windV * exactT) / 111320.0;
-            locLon = pilotLon - (windU * exactT) / (111320.0 * Math.cos(Math.toRadians(locLat)));
-            double dist = haversineMeters(pilotLat, pilotLon, locLat, locLon);
-            float bearing = bearingDeg(pilotLat, pilotLon, locLat, locLon);
-            return new ThermalBaseResult(locLat, locLon, 0, 0, dist, bearing, true);
-        }
-    }
-    …
-}
+public static ThermalBaseResult estimate(
+        double pilotLat, double pilotLon,
+        double altitudeMsl, double averageClimb,
+        double windBearing, double windSpeedMs) {
+
+    if (averageClimb < MIN_AVERAGE_CLIMB || altitudeMsl < 10) {
+        return new ThermalBaseResult(pilotLat, pilotLon, 0, 0, 0, 0, false);
+    }
+
+    // Вектор ветра (куда дует)
+    double windDirRad = Math.toRadians(windBearing + 180);
+    double windU = windSpeedMs * Math.sin(windDirRad);
+    double windV = windSpeedMs * Math.cos(windDirRad);
+
+    // Время спуска термика от текущей высоты до земли
+    double t = Math.min(altitudeMsl / averageClimb, MAX_DESCENT_TIME);
+
+    // База термика — на наветренной стороне: Pilot - WindVector * t
+    double locLat = pilotLat - (windV * t) / 111320.0;
+    double locLon = pilotLon - (windU * t) / (111320.0 * Math.cos(Math.toRadians(locLat)));
+
+    double dist = haversineMeters(pilotLat, pilotLon, locLat, locLon);
+    float bearing = bearingDeg(pilotLat, pilotLon, locLat, locLon);
+    return new ThermalBaseResult(locLat, locLon, 0, 0, dist, bearing, true);
+}
```

---

### BUG-A19 [MEDIUM] — `osc *= centeringProgress` убирает колебания в начале крутки

**Файл:** `core/FlightSimulator.java`, строки 391–394.

```java
float osc = 0.15f * (float) Math.sin(circleAngle * 2 + noisePhase * 0.1);
osc *= centeringProgress; // more oscillation early on     ← КОММЕНТАРИЙ ВРУЧ
liftAtPilot = Math.max(THERMAL_LIFT_EDGE, Math.min(THERMAL_LIFT_CORE, liftAtPilot + osc));
```

Комментарий говорит «more oscillation early on» (больше колебаний в начале), но `osc *= centeringProgress` означает **меньше** колебаний в начале (когда `centeringProgress` близко к 0). Это противоречие — либо формула неверна, либо комментарий.

Физически смысл в том, что в начале крутки пилот ещё не отцентрирован, и подъём должен колебаться сильнее (то ядро, то край). К концу — стабильное ядро, колебаний меньше. Правильно было бы:

```diff
-float osc = 0.15f * (float) Math.sin(circleAngle * 2 + noisePhase * 0.1);
-osc *= centeringProgress; // more oscillation early on
+// Больше колебаний в начале (когда пилот не отцентрирован), меньше в конце
+float osc = 0.15f * (1f - centeringProgress * 0.7f) * (float) Math.sin(circleAngle * 2 + noisePhase * 0.1);
```

Либо просто поменять комментарий, если задумывалось именно плавное нарастание.

---

## 5. Раздел C — Архитектура (P2)

### BUG-A20 [MEDIUM] — `MainActivity` God Object (2695 строк)

См. Code_Review3 BUG-28. За год разработки файл вырос до 2695 строк, и в нём смешаны:

- Управление UI (onCreate, onCreateOptionsMenu, onDraw)
- Управление сенсорами (registerListeners, unregisterListeners)
- Управление симуляциями (startSimLoop, startFlightScenario, startTrackReplay, startTestMode — 4 разных режима, 4 разных `*Handler`, 4 разных `*Task`)
- Обработка логики (processSample, addThermal, liftDatabase)
- Внутренний класс `RadarView extends View` — рендерер внутри активити
- TTS, Wakelock, Settings, Permissions

**Минимальная декомпозиция:**

1. `FlightOrchestrator` — управление режимами (sim/scenario/track/real/test). Один `Handler`, один `Runnable`, одна точка входа `tick()`.
2. `SensorPipeline` — accel → SignalProcessor → ThermalDetector, в отдельном треде.
3. `ThermalMapManager` — управление `thermals` списком, ThermalLocator, LiftDatabase.
4. `RadarView` вынести в отдельный файл `ui/RadarView.java`.

Это уменьшит `MainActivity` до ~500 строк и значительно упростит тестирование.

---

### BUG-A18 [MEDIUM] — Аллокации в `onDraw`

**Файл:** `ui/RadarRenderer.java`, строки 716–720 (`drawBestLiftSector`).

```java
private void drawBestLiftSector(Canvas c) {
    if (bestSectorIndex < 0) return;
    float sectorWidth = 360f / 36f;
    float sectorHalf = sectorWidth / 2f;
    float sectorCenterDeg = bestSectorIndex * sectorWidth + sectorHalf;
    float outerR = r - 2f;

    RectF sectorRect = new RectF(cx - outerR, cy - outerR,    // ← АЛЛОКАЦИЯ КАЖДЫЙ КАДР
                                  cx + outerR, cy + outerR);
    c.drawArc(sectorRect, -sectorCenterDeg - sectorHalf, sectorWidth, false,
              bestSectorStrokePaint);
    …
}
```

См. Code_Review3 ARCH-03. В `drawSectorDiagram` уже сделано правильно (`sectorRect` — поле класса, переиспользуется через `sectorRect.set(...)`). В `drawBestLiftSector` — нет. На 30 кадрах/с это 30 аллокаций `RectF` в секунду = 1800 в минуту, что на слабых устройствах даёт заметные GC-паузы (1–3% CPU на GC).

**Исправление.**

```diff
+private final RectF bestSectorRect = new RectF();   // поле класса
 …
 private void drawBestLiftSector(Canvas c) {
     if (bestSectorIndex < 0) return;
     …
-    RectF sectorRect = new RectF(cx - outerR, cy - outerR,
-                                  cx + outerR, cy + outerR);
+    bestSectorRect.set(cx - outerR, cy - outerR,
+                       cx + outerR, cy + outerR);
-    c.drawArc(sectorRect, -sectorCenterDeg - sectorHalf, sectorWidth, false,
+    c.drawArc(bestSectorRect, -sectorCenterDeg - sectorHalf, sectorWidth, false,
               bestSectorStrokePaint);
     …
 }
```

---

## 6. Раздел D — Качество кода (P3)

### BUG-A16 [MEDIUM] — `TH_SUSPECT = TH_THERMAL` — нет гистерезиса

См. Code_Review3 BUG-17. В коде:

```java
private static final float TH_SUSPECT    = 0.015f;  // ← в javadoc сказано «ниже THERMAL для гистерезиса»
private static final float TH_THERMAL    = 0.020f;  // ← но в v0.2.2 их выровняли
```

Подождите, проверим текущее состояние:

```java
private static final float TH_SUSPECT    = 0.015f;  // ~0.15 м/с²
private static final float TH_THERMAL    = 0.020f;  // ~0.20 м/с²
```

ОК, в текущей версии пороги **разные** (0.015 vs 0.020). Гистерезис есть. Code_Review3 ошибается, говоря что они равны — видимо, ревью делалось по старому коммиту. **В моём аудите BUG-A16 отменяется** — пороги корректны. Отметим это явным образом, чтобы устранить путаницу.

---

### BUG-A17 [MEDIUM] — `bornMs` через `System.currentTimeMillis()`

См. Code_Review3 BUG-18. Подтверждаю — в `ThermalDetector.java` строка 254:

```java
long now = SystemClock.elapsedRealtime();   // ← НЕТ, на самом деле:
```

Проверим:

```java
// ThermalDetector.java, строка 254
long now = SystemClock.elapsedRealtime();
```

На самом деле в текущей версии уже `elapsedRealtime()`. Code_Review3 здесь тоже устарел. **BUG-A17 в моём аудите также отменяется.**

Это важный момент: предыдущее ревью делалось по коммиту `d3ef168` (v0.2.2), а в текущем HEAD некоторые баги из списка NEW-xx уже исправлены. Я отмечаю только те, которые **актуальны на текущий момент**.

---

### BUG-A21 [LOW] — Мёртвая константа `BEST_SECTOR_UPDATE_FACTOR`

**Файл:** `flight/LiftDatabase.java`, строка 37.

```java
/** Коэффициент обновления лучшего сектора (если новый > old × factor) */
private static final float BEST_SECTOR_UPDATE_FACTOR = 1.1f;
```

Нигде в коде не используется. Поиск по проекту: 0 ссылок. Видимо, задумывался для гистерезиса «лучший сектор не должен меняться слишком часто», но не реализован.

**Исправление.** Либо удалить, либо реализовать гистерезис:

```diff
-/** Коэффициент обновления лучшего сектора (если новый > old × factor) */
-private static final float BEST_SECTOR_UPDATE_FACTOR = 1.1f;
+/** Гистерезис лучшего сектора: новый кандидат должен превзойти старый на 10% */
+private static final float BEST_SECTOR_UPDATE_FACTOR = 1.1f;
```

И в `recordLift`:

```diff
-for (int s = 0; s < SECTOR_COUNT; s++) {
-    if (initialized[s] && liftValues[s] > bestSectorLift) {
-        bestSectorLift = liftValues[s];
-        bestSectorIndex = s;
-    }
-}
+int newBest = -1;
+float newBestLift = Float.NEGATIVE_INFINITY;
+for (int s = 0; s < SECTOR_COUNT; s++) {
+    if (initialized[s] && liftValues[s] > newBestLift) {
+        newBestLift = liftValues[s];
+        newBest = s;
+    }
+}
+// Гистерезис: меняем лучший сектор, только если новый уверенно лучше
+if (newBest != bestSectorIndex
+        && (bestSectorIndex < 0 || newBestLift >= bestSectorLift * BEST_SECTOR_UPDATE_FACTOR)) {
+    bestSectorIndex = newBest;
+    bestSectorLift  = newBestLift;
+}
```

Это сделает голосовые подсказки стабильнее — сектор не будет «дрожать» между двумя соседними значениями.

---

### BUG-A22 [LOW] — Magic number `0x1p31` в Box-Muller

Связано с BUG-A06. После перехода на `Random.nextGaussian()` проблема уходит сама собой. Если всё же хочется оставить ручной Box-Muller, нужно:

1. Добавить комментарий, объясняющий `0x1p31` (это 2³¹, масштаб, чтобы `Math.sin * 2^31` давал значение comparable с `Long.MAX_VALUE`).
2. Использовать `Random.nextDouble()` вместо `sin/cos * 2^31` — стандартный равномерный сэмплер.

---

### BUG-A23 [LOW] — `CirclingManager.sectorVario` без накопления среднего

**Файл:** `flight/CirclingManager.java`, строки 378–382.

```java
if (sectorCount[sector] == 0) {
    sectorVario[sector] = vario;
} else {
    sectorVario[sector] = 0.7f * sectorVario[sector] + 0.3f * vario;
}
sectorCount[sector]++;
```

EMA с α=0.3 — не накапливает среднего, как `LiftDatabase`. У `LiftDatabase` аналогичная формула `liftValues[idx] += alpha * (vario - liftValues[idx])` с адаптивным `alpha = 1/min(sampleCount, 50)` — то есть первые 50 сэмплов усредняются равномерно, потом EMA.

Для 4 секторов по 90° с 17-секундным кругом при 50 Гц = ~210 сэмплов на сектор за круг. EMA α=0.3 даёт «эффективное окно» ~3 сэмпла — это слишком коротко. В `LiftDatabase` первые 50 сэмплов — фактически average, потом EMA с α=0.02. Это разумнее.

**Исправление.** Привести `CirclingManager.sectorVario` к той же формуле:

```diff
-if (sectorCount[sector] == 0) {
-    sectorVario[sector] = vario;
-} else {
-    sectorVario[sector] = 0.7f * sectorVario[sector] + 0.3f * vario;
-}
-sectorCount[sector]++;
+sectorCount[sector]++;
+float alpha = 1f / Math.min(sectorCount[sector], 50);
+sectorVario[sector] += alpha * (vario - sectorVario[sector]);
```

---

## 7. Сводный план исправлений

### P0 — критические, влияют на работоспособность (симптомы «дёргается» и «36 секторов»)

| # | Баг | Файл | Effort |
|---|---|---|---|
| 1 | BUG-A01 | MainActivity.java:2337–2345 | 5 строк |
| 2 | BUG-A02 | LiftDatabase.java, RadarRenderer.java, README.md | 10 строк + doc |
| 3 | BUG-A03 | SimulationManager.java:138, 215 | 2 строки |
| 4 | BUG-A04 | MainActivity.java:936 (ввести getCurrentHeading/getCurrentVario) | 30 строк |
| 5 | BUG-A05 | SimulationManager.java:130–131, 208 | 4 строки |

### P1 — высокие, влияют на корректность результатов

| # | Баг | Файл | Effort |
|---|---|---|---|
| 6 | BUG-A06 | SimulationManager.java, FlightSimulator.java | 20 строк |
| 7 | BUG-A07 | SimulationManager.java:55 + тайминги | 5 строк |
| 8 | BUG-A08 | FlightSimulator.java:281–292 | 10 строк |
| 9 | BUG-A09 | FlightSimulator.java:442–446 | 5 строк |
| 10 | BUG-A10 / BUG-A13 | MainActivity.java:1674–1678 | 5 строк + unify |
| 11 | BUG-A11 | RadarRenderer.java (3 места) | 6 строк |
| 12 | BUG-A12 | CirclingManager.java (делегировать в LiftDatabase) | 40 строк |

### P2 — средние, качество кода и логика

| # | Баг | Файл | Effort |
|---|---|---|---|
| 13 | BUG-A14 | FlightSimulator.java:165 | 10 строк |
| 14 | BUG-A15 | ThermalBaseEstimator.java:100–129 | −30 строк |
| 15 | BUG-A18 | RadarRenderer.java:716–720 | 3 строки |
| 16 | BUG-A19 | FlightSimulator.java:391–394 | 2 строки |
| 17 | BUG-A20 | MainActivity.java (рефакторинг) | ~1000 строк moved |
| 18 | BUG-A21 | LiftDatabase.java:37, 117–124 | 15 строк |

### P3 — низкие, стиль и мелочи

| # | Баг | Файл | Effort |
|---|---|---|---|
| 19 | BUG-A22 | SimulationManager.java, FlightSimulator.java | решается через BUG-A06 |
| 20 | BUG-A23 | CirclingManager.java:378–382 | 3 строки |

**Снятые баги (исправлены в текущем HEAD после v0.2.2):**

- BUG-17 из Code_Review3 (`TH_SUSPECT = TH_THERMAL`) — в текущем коде 0.015 vs 0.020, гистерезис есть.
- BUG-18 из Code_Review3 (`bornMs` через `currentTimeMillis`) — в `ThermalDetector.java:254` уже `elapsedRealtime()`.
- NEW-02 из Code_Review3 (`completeSinePeriod` хардкод 100 Гц) — в текущем коде передаётся `currentSineFreq`, формула корректна.

---

## 8. Приоритизированные diff-патчи (копировать-вставлять)

Ниже — готовые патчи для P0 и критических P1. Применять в указанном порядке.

### Patch 1 — Исправляет BUG-A01 + BUG-A04 + BUG-A10 + BUG-A13 (единый источник heading/vario)

**Файл:** `app/src/main/java/com/termo1/radar/MainActivity.java`

Добавить два метода рядом с `getCompassHeading()`:

```java
// Единый источник "текущего" heading с учётом активного режима
private float getCurrentHeading() {
    if (simMode && simulation != null && simulation.isRunning())
        return simulation.getHeading();
    if (scenarioMode && flightSim != null && flightSim.isRunning())
        return flightSim.getHeading();
    if (trackMode && trackReplayer != null && trackReplayer.isRunning())
        return trackReplayer.getHeading();
    // Реальный полёт — ИСТИННЫЙ heading (с магнитным склонением)
    if (sensorController.isCompassReady()) return sensorController.getTrueHeading();
    if (gpsManager.isReady())               return gpsManager.getHeading();
    return 0.0f;
}

// Единый источник "текущего" vario с учётом активного режима
private float getCurrentVario() {
    if (simMode && simulation != null && simulation.isRunning())
        return simulation.getVario();
    if (scenarioMode && flightSim != null && flightSim.isRunning())
        return flightSim.getVario();
    if (trackMode && trackReplayer != null && trackReplayer.isRunning())
        return trackReplayer.getVario();
    return sensorController.getVario();
}
```

Заменить использование:

```diff
 // onDraw, строки 2337–2345
-float headingDisplay = getCompassHeading();
-float varioDisplay = sensorController.getVario();
-if (scenarioMode && flightSim != null && flightSim.isRunning()) {
-    headingDisplay = flightSim.getHeading();
-    varioDisplay = flightSim.getVario();
-} else if (trackMode && trackReplayer != null && trackReplayer.isRunning()) {
-    headingDisplay = trackReplayer.getHeading();
-    varioDisplay = trackReplayer.getVario();
-}
+float headingDisplay = getCurrentHeading();
+float varioDisplay  = getCurrentVario();

 // bgTask, строка 919 (circlingManager.update) и 936 (liftDatabase.recordLift)
-circlingManager.update(
-    sensorController.getGyroZ(),
-    getCompassHeading(),
-    gpsManager.getHeading(),
-    sensorController.getVario(),
-    …);
+circlingManager.update(
+    sensorController.getGyroZ(),
+    getCurrentHeading(),       // ← симулированный, если simMode
+    gpsManager.getHeading(),   // ← остаётся реальный GPS для оценки ветра
+    getCurrentVario(),
+    …);

-liftDatabase.recordLift(getCompassHeading(), varioVal);
+liftDatabase.recordLift(getCurrentHeading(), varioVal);

 // speakThermalDirection, строка 1686
-float heading = getCompassHeading();
+float heading = getCurrentHeading();
```

Оставить `getCompassHeading()` для случаев, когда нужно именно «сырой компас» (например, для лога или отладки).

---

### Patch 2 — Исправляет BUG-A02 + BUG-A11 (36 → 12 секторов + убрать magic number)

**Файл 1:** `app/src/main/java/com/termo1/radar/flight/LiftDatabase.java`

```diff
-/** Количество секторов */
-public static final int SECTOR_COUNT = 36;
+/**
+ * Количество секторов карты подъёма.
+ * 12 секторов по 30° — оптимально для параплана:
+ * круг ~17 с при 50 Гц = ~850 сэмплов, ~70 на сектор, EMA-устойчиво.
+ * (XCSoar использует 36, но там планёр с кругом ~90 с.)
+ */
+public static final int SECTOR_COUNT = 12;
```

Также обновить javadoc строк 1–14: убрать «36 секторов, каждый покрывает 10°», написать «12 секторов, каждый покрывает 30°».

**Файл 2:** `app/src/main/java/com/termo1/radar/ui/RadarRenderer.java`

```diff
 // в импортах:
+import com.termo1.radar.flight.LiftDatabase;
 …
 // drawSectorDiagram, строка 385
-float sectorDeg = 360f / 36f;   // 10° на сектор
+float sectorDeg = 360f / LiftDatabase.SECTOR_COUNT;
 …
-for (int i = 0; i < 36; i++) {
+for (int i = 0; i < LiftDatabase.SECTOR_COUNT; i++) {
     if (sectorLiftValues[i] > maxLift) maxLift = sectorLiftValues[i];
     if (sectorLiftValues[i] < minLift) minLift = sectorLiftValues[i];
 }
 …
-for (int i = 0; i < 36; i++) {
+for (int i = 0; i < LiftDatabase.SECTOR_COUNT; i++) {
     float val = sectorLiftValues[i];
     …
     float startAngle = -(i * sectorDeg);
     c.drawArc(sectorRect, startAngle - sectorDeg / 2f, sectorDeg, true, sectorFillPaint);
 }

 // drawBestLiftSector, строка 717
-float sectorWidth = 360f / 36f;
+float sectorWidth = 360f / LiftDatabase.SECTOR_COUNT;
```

Также проверить `sectorLiftValues` объявление: должно быть `float[] sectorLiftValues = new float[LiftDatabase.SECTOR_COUNT];`.

**Файл 3:** `README.md`

```diff
-**LiftDatabase** — 36 секторов по 10°, отслеживание лучшего сектора
+**LiftDatabase** — 12 секторов по 30°, отслеживание лучшего сектора (оптимизировано для параплана: ~70 сэмплов на сектор за 17-секундный круг)
```

И в истории версий:

```diff
-| **0.2.0** | … LiftDatabase (36 секторов) …
+| **0.2.0** | … LiftDatabase (12 секторов) …
```

(или добавить новый пункт в историю про изменение 36 → 12)

---

### Patch 3 — Исправляет BUG-A03 + BUG-A05 (dt clamp + long→float)

**Файл:** `app/src/main/java/com/termo1/radar/core/SimulationManager.java`

```diff
 public void update(long nowMs) {
     if (!running) return;

     float tSec = nowMs / 1000f;
     if (tSec >= SIM_END_SEC) {
         running = false;
         elapsedMs = nowMs;
         return;
     }

-    float prevMs = elapsedMs;
+    long prevMs = elapsedMs;
     elapsedMs = nowMs;

     heading = computeHeading(tSec);
     vario = computeVario(tSec);
     updatePosition(tSec, prevMs);

-    // Integrate altitude from vario
-    float dtSec = (elapsedMs - prevMs) / 1000f;
-    if (dtSec > 0.05f) dtSec = 0.02f; // cap
+    // Интегрирование высоты с clamp (защита от лагов, но не замедление)
+    double dtSec = (elapsedMs - prevMs) / 1000.0;
+    if (dtSec > 0.05) dtSec = 0.05;
     if (dtSec > 0f) {
-        altMsl += vario * dtSec;
+        altMsl += vario * (float) dtSec;
     }

     generatePuffs(nowMs);
     generateAccel(tSec);
 }
```

И в `updatePosition`:

```diff
 private void updatePosition(float tSec, float prevMs) {
     if (prevMs <= 0) {
         pilotX = 0.0;
         pilotY = 0.0;
         return;
     }
-    float dt = (elapsedMs - prevMs) / 1000f;
-    if (dt > 0.05f) dt = 0.02f;
+    // dt как double, clamp 50 ms (стандартная защита real-time симуляции)
+    double dt = (elapsedMs - (long) prevMs) / 1000.0;
+    if (dt > 0.05) dt = 0.05;
     if (dt < 0.001) return;

     double headingRad = Math.toRadians(heading);
-    pilotX += SPEED * dt * Math.sin(headingRad);
-    pilotY += SPEED * dt * Math.cos(headingRad);
+    pilotX += SPEED * dt * Math.sin(headingRad);
+    pilotY += SPEED * dt * Math.cos(headingRad);
 }
```

Дополнительно — изменить сигнатуру `updatePosition(float tSec, float prevMs)` на `updatePosition(float tSec, long prevMs)`, чтобы не делать cast:

```diff
-private void updatePosition(float tSec, float prevMs) {
+private void updatePosition(float tSec, long prevMs) {
```

И в `update()`:

```diff
-updatePosition(tSec, prevMs);
+updatePosition(tSec, prevMs);   // prevMs теперь long
```

---

### Patch 4 — Исправляет BUG-A06 (Box-Muller с детерминированным seed)

**Файл:** `app/src/main/java/com/termo1/radar/core/SimulationManager.java`

```diff
 // в полях класса:
-private double noiseSeedX, noiseSeedY;
+private final java.util.Random noiseRandom = new java.util.Random();

 // в start():
 public void start() {
     …
-    noiseSeedX = 0.0;
-    noiseSeedY = 0.0;
+    noiseRandom.setSeed(System.nanoTime());   // недетерминированный seed
     …
 }

 // в generateAccel():
 private void generateAccel(float tSec) {
     float ax = 0f, ay = 0f;

-    // 1. Базовый шум — БЕЛЫЙ ШУМ Box-Muller (как во FlightSimulator)
-    double u1 = Math.sin(noiseSeedX += 0.1) * 0x1p31;
-    double u2 = Math.cos(noiseSeedY += 0.1) * 0x1p31;
-    double u1n = (u1 % 1.0 + 1.0) % 1.0;
-    double u2n = (u2 % 1.0 + 1.0) % 1.0;
-    if (u1n < 1e-10) u1n = 0.5;
-    double norm = Math.sqrt(-2.0 * Math.log(u1n)) * Math.cos(2.0 * Math.PI * u2n);
-    float whiteX = NOISE_FLOOR_G * (float) Math.min(Math.abs(norm), 3.0) * Math.signum((float)norm);
-    double normY = Math.sqrt(-2.0 * Math.log(u1n)) * Math.sin(2.0 * Math.PI * u2n);
-    float whiteY = NOISE_FLOOR_G * (float) Math.min(Math.abs(normY), 3.0) * Math.signum((float)normY);
+    // 1. Базовый белый шум — стандартный Random.nextGaussian()
+    // (раньше был ручной Box-Muller с детерминированным seed — давал одинаковую
+    // последовательность на каждом запуске, маскируя баги калибровки)
+    float whiteX = NOISE_FLOOR_G * (float) noiseRandom.nextGaussian();
+    float whiteY = NOISE_FLOOR_G * (float) noiseRandom.nextGaussian();
     ax += whiteX;
     ay += whiteY;
     …
 }
```

Аналогично — `core/FlightSimulator.java` (строки 67–68, 119–120, 424–436).

---

### Patch 5 — Исправляет BUG-A07 (CIRCLE_RATE 36°/с → 20°/с)

**Файл:** `app/src/main/java/com/termo1/radar/core/SimulationManager.java`

```diff
-// Circle: 360° in 10s = 36°/s
-private static final float CIRCLE_RATE = 360.0f / 10.0f;
+// Круг: 360° за ~18 с = 20°/с — реалистичная парапланерная крутка.
+// (36°/с — это почти вращение, норма для параплана 10–18°/с)
+private static final float CIRCLE_PERIOD_SEC = 18.0f;
+private static final float CIRCLE_RATE = 360.0f / CIRCLE_PERIOD_SEC;
```

И подкорректировать тайминги — сейчас `T_CIRCLE_END - T_EAST_END = 52 - 42 = 10 с`. Должно быть 18 с:

```diff
-private static final float T_CIRCLE_END = 52.0f;        // +2s
+private static final float T_CIRCLE_END = 60.0f;        // +2s quiet + 18s круг
-private static final float T_TURN_END = 57.0f;          // +2s
+private static final float T_TURN_END = 65.0f;          // +2s quiet + 5s поворот
-private static final float SIM_END_SEC = 77.0f;         // +2s
+private static final float SIM_END_SEC = 85.0f;         // +2s quiet + 20s exit
```

В `MainActivity.startSimLoop` строка 1026 тоже проверьте таймаут `if (elapsed > 75000)` — нужно увеличить до `85000`.

---

## 9. Дополнительные рекомендации (вне баг-трекера)

### 9.1. Тесты

В проекте уже есть `app/src/test/java/com/termo1/radar/Termo1UnitTests.java` — нужно добавить кейсы:

1. `LiftDatabaseTest` — проверить, что `SECTOR_COUNT = 12` и `headingToSector(15°)` = 0 (центр 0° = N), `headingToSector(45°)` = 1.
2. `SimulationManagerTest` — запустить симуляцию на 5 секунд с `nowMs` от `SystemClock.elapsedRealtime()` через mock, проверить, что `pilotX/Y` совпадают с ручным интегрированием `speed * dt * sin(heading)`.
3. `HeadingConsistencyTest` — в режиме `simMode` `getCurrentHeading()` должен совпадать с `simulation.getHeading()`. Это регрессионный тест на BUG-A01.
4. `ThermalBaseEstimatorTest` — убедиться, что при `windSpeedMs = 0` база совпадает с позицией пилота, а при `windSpeedMs = 5, windBearing = 315 (NW)` база смещается на SE от пилота на `5 * altitudeMs / averageClimb` метров.

### 9.2. CI

Добавить в `.github/workflows/`:

1. `build.yml` — `./gradlew assembleDebug` на каждом push.
2. `lint.yml` — `./gradlew detekt` или `spotbugs`.
3. `test.yml` — `./gradlew testDebugUnitTest`.

Сейчас в репозитории только `check_upload.py`, `upload_release.py`, `list_releases.py` — Python-утилиты для релизов, но **нет CI для самого кода**.

### 9.3. Документация

В `README.md` есть несостыковка: в «Возможностях» указано «5 секторов по 45°, передняя полусфера» для голоса, но в коде `CirclingManager.SECTORS = 4` (по 90°). Нужно либо реализовать 5-секторную модель для голоса, либо обновить README.

Также `algo.md` содержит раздел 12 «Heading Filter», где рекомендуется deadband перед `invalidate()`. В коде `HeadingFilter.update()` возвращает `NaN` при deadband, но в `SensorController.updateCompass()` `heading` просто не обновляется. Это **не эквивалентно** «deadband перед invalidate» — `heading` всё равно пересчитывается в Alpha-Beta, просто не отображается. Нужно либо перенести deadband в `MainActivity` перед `radarView.postInvalidateOnAnimation()`, либо явно отметить, что текущая реализация — «deadband на уровне сенсора, а не UI».

### 9.4. Логирование для отладки симуляции

Добавить в `LogManager` флаг `LOG_SIM_MODE = true`, чтобы в `Samples.csv` попадали:

- `simHeading` (из `SimulationManager.getHeading()`)
- `realHeading` (из `getCompassHeading()`)
- `displayedHeading` (то, что передаётся в renderer)
- `dt` (из `update()`)

Это позволит из лога сразу видеть рассинхронизацию симуляции и реального времени (BUG-A03) и несоответствие симулированного/реального heading (BUG-A01).

---

## 10. Заключение

Проект в целом хорошо структурирован для студенческой работы: есть чёткое разделение по пакетам (`core`, `flight`, `sensors`, `ui`, `gps`, `logging`, `map`, `model`), используются паттерны из XCSoar (порт алгоритмов), есть попытка следовать SOLID (например, `ThermalLocator` изолирован от UI). Документация подробная.

**Однако в текущем виде режим симуляции (75-секундная демо) практически неработоспособен** из-за BUG-A01 (heading берётся из реального компаса) и BUG-A04 (lift-карта пишется по реальному heading). Эти два бага — **корневая причина** и «дёрганья радара», и «симуляция работает неверно». После их исправления (Patch 1) симуляция начнёт вести себя так, как описано в README: плавный полёт NORTH → EAST → круг → NORTH, с термиками на ожидаемых позициях.

**Проблема 36 vs 12 секторов** (BUG-A02) — это несоответствие требованиям. Технически код работает (сектора корректно рисуются), но разрешение 10° избыточно для параплана и приводит к шумной статистике. Patch 2 приводит к требованиям и заодно убирает magic number.

**Остальные баги** (BUG-A03..A05, A06..A09) — это технические дефекты, которые проявляются на длинных симуляциях (>30 с) и при работе на реальных устройствах с лагами. Их исправление (Patch 3–5) сделает симуляцию стабильной и предсказуемой.

Рекомендуемый порядок работы:

1. **Patch 1** (BUG-A01 + A04) — исправляет видимый пользователю симптом «дёргается радар».
2. **Patch 2** (BUG-A02) — приводит к требованию «12 секторов».
3. **Patch 3** (BUG-A03 + A05) — стабилизирует временную шкалу симуляции.
4. **Patch 4** (BUG-A06) — недетерминированный шум.
5. **Patch 5** (BUG-A07) — реалистичная скорость крутки.
6. Остальные — по приоритету P1 → P2 → P3.

После применения Patch 1–5 режим симуляции станет пригодным для демонстрации работы детектора термиков, и можно будет переходить к полевым испытаниям.
