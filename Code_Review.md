# TERMO1 Radar v0.2.0 — Результаты ревью кода

**Рецензент:** Senior Avionics & Android Developer  
**Дата:** 2026-06-24  
**Версия проекта:** v0.2.0 (коммит `7c8b1d3`)  
**Обнаружено проблем:** 28 (6 критических, 8 высоких, 9 средних, 5 низких)

---

## 0. Статус исправлений из предыдущего ревью (v0.1.10)

| # | Проблема | Статус |
|---|---------|--------|
| 1.1 | FlightSimulator: высота 0 м | ❌ **НЕ ИСПРАВЛЕНО** — `altMsl = 0f` (строка 121) |
| 1.2 | Нет Quiet Period в FlightSimulator | ❌ **НЕ ИСПРАВЛЕНО** |
| 1.3 | Синусоидальный шум вместо белого | ❌ **НЕ ИСПРАВЛЕНО** |
| 1.4 | Слабая турбулентность в FlightSimulator | ❌ **НЕ ИСПРАВЛЕНО** |
| 2.1 | ZC: сумма двух каналов удваивает частоту | ❌ **НЕ ИСПРАВЛЕНО** — `totalZc = zcCountX + zcCountY` (строка 184) |
| 2.2 | noiseFloor: L1-норма вместо RMS | ❌ **НЕ ИСПРАВЛЕНО** |
| 2.3 | Нестабильное направление турбулентности | ❌ **НЕ ИСПРАВЛЕНО** |
| 3.1 | Нет гистерезиса SUSPECT/THERMAL | ❌ **НЕ ИСПРАВЛЕНО** — `TH_SUSPECT = TH_THERMAL = 0.020f` |
| 3.2 | Смешение currentTimeMillis/elapsedRealtime | ⚠️ Частично — новые модули (IgcLogger) используют elapsedRealtime, но MainActivity нет |
| 4.1 | Baseline VarioThermalDetector загрязняется | ❌ **НЕ ИСПРАВЛЕНО** |
| 4.3 | bornMs: смешение шкал времени | ❌ **НЕ ИСПРАВЛЕНО** |

**Вывод:** Ни один из 11 багов предыдущего ревью не был исправлен. При этом добавлено ~2900 строк нового кода с новыми ошибками.

---

## 1. Критические ошибки (SAFETY-CRITICAL)

### 🔴 BUG-01: FlightStateMachine.state — полное отсутствие потокобезопасности

**Файл:** `FlightStateMachine.java`, строка 48  
**Суть:** Поле `state` — обычный `int`, без `volatile`, без блокировки. Методы `update()` и `updateSpeedBased()` пишут в него, а `isFlying()`, `getState()` читаются из других потоков (UI, background). В авиационном приложении это означает, что определение посадки может быть пропущено, лог полёта — не остановлен, TTS-подсказки — подавлены.

```java
// Сейчас:
private int state = STATE_ON_GROUND;

// Должно быть:
private volatile int state = STATE_ON_GROUND;
// Или лучше: private final AtomicInteger state = new AtomicInteger(STATE_ON_GROUND);
```

**Рекомендация:** Заменить `state` на `AtomicInteger`. Все переходы выполнять через `compareAndSet()` или `synchronized`. Это гарантирует видимость изменений между потоками и атомарность переходов.

---

### 🔴 BUG-02: Двойной вызов processSample() и circlingManager.update()

**Файл:** `MainActivity.java`, строки 905–907 и 1968–1997  
**Суть:** `processSample()` вызывается И из фонового обработчика 10 Гц (строка 905), И из onDraw() ~30 FPS (строка 1968). Внутри `processSample()` вызывается `flightStateMachine.update()`, который записывает историю высоты. При двойном вызове буфер истории заполняется в 3 раза быстрее ожидаемого, что сокращает эффективное временное окно и может вызвать преждевременное обнаружение посадки.

Аналогично `circlingManager.update()` вызывается дважды — двойная подача нарушает timing-логику: turn rate EMA, интервалы между кругами, оценка ветра по дрейфу центров.

**Рекомендация:** Разделить логику: фоновый обработчик вызывает `processSample()` + `circlingManager.update()`, а onDraw() делает только рендеринг. Удалить дублирующие вызовы из onDraw().

---

### 🔴 BUG-03: ThermalBaseEstimator — направление сноса инвертировано

**Файл:** `ThermalBaseEstimator.java`, строки 112–113  
**Суть:** Код вычисляет позицию базы термика, смещаясь ПО ВЕТРУ от пилота:

```java
locLat = pilotLat + (windV * t) / 111320.0;   // вниз по ветру
locLon = pilotLon + (windU * t) / 111320.0;    // вниз по ветру
```

Но база термика — это место, где термик «выходит из земли». Термиковый столб дрейфует ПО ВЕТРУ, поэтому база (где столб касался земли в прошлом) находится ПРОТИВ ВЕТРА от пилота. Пилот находится на вершине столба, столб наклонён по ветру, а его основание — с наветренной стороны.

**Рекомендация:** Изменить знаки на противоположные:
```java
locLat = pilotLat - (windV * t) / 111320.0;   // против ветра
locLon = pilotLon - (windU * t) / 111320.0;    // против ветра
```

---

### 🔴 BUG-04: WakeLock утечка при уничтожении Activity

**Файл:** `MainActivity.java`, строки 468–499  
**Суть:** Если Activity уничтожается во время записи лога, `onPause()` пропускает `releaseWakeLock()` (проверка `!logManager.isLogging()`), а `onDestroy()` не освобождает WakeLock явно. Результат: WakeLock удерживается бесконечно, батарея разряжается. Для парапланерного приложения разряженный телефон в полёте — прямая угроза безопасности.

**Рекомендация:** Добавить безусловный `releaseWakeLock()` в `onDestroy()`. Также рассмотреть перенос WakeLock в `ThermalRadarService`, который переживает уничтожение Activity.

---

### 🔴 BUG-05: Shallow copy thermals + мутация ThermalBlip из разных потоков

**Файл:** `MainActivity.java` (строка 2277), `RadarRenderer.java` (строки 635–636)  
**Суть:** `thermalsCopy.addAll(thermals)` копирует ссылки, а не объекты. RadarRenderer в `drawThermals()` пишет `t.px` и `t.py` в ThermalBlip из рендер-потока, в то время как сценарий/трек-реплей пишет `tb.distance`, `tb.strength`, `tb.angle` из другого потока. Это data race — блип может появиться в неверной позиции, что дезинформирует пилота.

**Рекомендация:** Либо deep-copy ThermalBlip при создании `thermalsCopy`, либо сделать поля ThermalBlip `volatile`, либо не мутировать существующие объекты — создавать новые при каждом обновлении.

---

### 🔴 BUG-06: IgcLogger — GPS-кеш не атомарен

**Файл:** `IgcLogger.java`, строки 82–89  
**Суть:** Поля `cachedLat`, `cachedLon`, `cachedAltGps` и др. объявлены как `volatile` по отдельности. Но `updateGps()` пишет их последовательно, а `recordSample()` читает. Читатель может увидеть частично обновлённый фикс: новая широта + старая долгота. Это нарушает целостность IGC-записи.

**Рекомендация:** Заменить отдельные volatile-поля на единый immutable объект:
```java
private static class GpsSnapshot {
    final double lat, lon;
    final float altGps, altBaro, speed, heading, accuracy;
    final long fixAgeMs;
}
private volatile GpsSnapshot gpsSnapshot;
```
Запись: создать новый объект, атомарно заменить ссылку. Чтение: прочитать ссылку один раз.

---

## 2. Высокие ошибки (HIGH)

### 🟠 BUG-07: FlightStateMachine — гонка между update() и updateSpeedBased()

**Файл:** `FlightStateMachine.java`  
**Суть:** Оба метода могут переводить `state`, но не синхронизированы между собой. Если altitude-based detection устанавливает `STATE_FINISHED`, а speed-based одновременно — `STATE_FLYING`, состояние становится неконсистентным.

**Рекомендация:** Все переходы состояния выполнять внутри единого `synchronized` блока или через `AtomicInteger.compareAndSet()`.

---

### 🟠 BUG-08: FlightStateMachine — checkStop() fullWindow логика инвертирована

**Файл:** `FlightStateMachine.java`, строки 222–226  
**Суть:** Цикл устанавливает `fullWindow = false` когда `i == histFill - 1` (последняя итерация). Но это означает: «если самая старая точка всё ещё в 5-минутном окне, значит окно неполное» — что противоположно истине. Если буфер содержит 5+ минут данных, самая старая точка будет ЗА пределами cutoff, и `fullWindow` останется `true` — это случайно работает, но логика хрупкая и неясная.

**Рекомендация:** Переписать проверку: `fullWindow` = `true`, если существует хотя бы одна точка старше cutoff. Явный код лучше неявного побочного эффекта.

---

### 🟠 BUG-09: FlightStateMachine — «мёртвая зона» скорости 2.5–5.0 м/с

**Файл:** `FlightStateMachine.java`, строки 170–174  
**Суть:** При `gpsSpeed` между 2.5 и 5.0 м/с оба счётчика `movingClockActive` и `stationaryClockActive` сбрасываются в `false`. Параплан в ламинаре летит со скоростью ~9 м/с, но при слабом ветре или на лебёдке скорость может быть 3–4 м/с. В этом диапазоне ни взлёт, ни посадка не детектируются по скорости.

**Рекомендация:** Убрать зазор между порогами. Использовать гистерезис: взлёт при `> 5.0 м/с`, посадка при `< 2.5 м/с` в течение N секунд. Между 2.5 и 5.0 — сохранять текущее состояние.

---

### 🟠 BUG-10: SimpleDateFormat в IgcLogger — не потокобезопасен

**Файл:** `IgcLogger.java`, строки 55–58  
**Суть:** Статические экземпляры `SimpleDateFormat` (`IGC_TIME_FMT`, `IGC_DATE_FMT`) не потокобезопасны. Если два экземпляра IgcLogger существуют (маловероятно, но возможно), конкурирующий доступ разрушит форматирование.

**Рекомендация:** Использовать `ThreadLocal<SimpleDateFormat>` или создавать новый экземпляр при каждом форматировании (производительность не критична при 1 Гц).

---

### 🟠 BUG-11: IgcLogger.logging не volatile

**Файл:** `IgcLogger.java`  
**Суть:** Флаг `logging` — обычный `boolean`. Если `stopLogging()` вызывается из одного потока, а `recordSample()` читает из другого, поток-читатель может никогда не увидеть `logging = false` и писать в закрытый поток.

**Рекомендация:** Объявить `logging` как `volatile`.

---

### 🟠 BUG-12: CirclingManager — EKF-ветер обновляется только при windConfidence < 2

**Файл:** `CirclingManager.java`, строка 427  
**Суть:** Условие `if (windConfidence < 2)` блокирует обновление отображаемого ветра от EKF после двух измерений. После этого EKF продолжает накапливать данные, но его оценки не используются для отображения. Это означает, что при длительном прямом полёте оценка ветра перестаёт уточняться на экране.

**Рекомендация:** Изменить логику: всегда обновлять `windFromDeg`/`windSpeedMs` от EKF, но с меньшим весом при высоком `windConfidence` (т.к. есть оценка по спиралям, которая точнее).

---

### 🟠 BUG-13: NPE на цепочке thermalDetector.getSignalProcessor()

**Файл:** `MainActivity.java`, строки 681, 772, 1141, 2389  
**Суть:** Множество вызовов `thermalDetector.getSignalProcessor().getTurbulenceMs2()` и подобных без null-проверки. Если `getSignalProcessor()` возвращает null (например, при reset), приложение упадёт с NPE прямо в полёте.

**Рекомендация:** Добавить null-проверку: `if (thermalDetector != null && thermalDetector.getSignalProcessor() != null)`.

---

### 🟠 BUG-14: StaticMapLoader — устаревший AsyncTask + утечка Bitmap

**Файл:** `StaticMapLoader.java`  
**Суть:** (1) `AsyncTask` депрекейтнут с API 30, генерирует предупреждения компилятора. (2) При вытеснении Bitmap из LruCache метод `entryRemoved()` по умолчанию не вызывает `bitmap.recycle()` — нативная память утекает. На устройстве с ограниченной памятью это приведёт к `OutOfMemoryError`.

**Рекомендация:** (1) Заменить `AsyncTask` на `ExecutorService` + `Handler`. (2) Переопределить `entryRemoved()` в LruCache для вызова `bitmap.recycle()`. (3) Ограничить размер дискового кеша.

---

## 3. Средние ошибки (MEDIUM)

### 🟡 BUG-15: FlightSimulator — все 4 бага из предыдущего ревью не исправлены

- `altMsl = 0f` вместо 500 (ломает FlightStateMachine)
- Нет Quiet Period (ломает калибровку noiseFloor)
- Синусоидальный шум (нереалистичная калибровка)
- Слабая турбулентность 0.01–0.04g (SNR < 3, ThermalDetector не срабатывает)

**Все четыре бага делают симуляцию неработоспособной.**

---

### 🟡 BUG-16: SignalProcessor — ZC и noiseFloor не исправлены

- Суммирование `zcCountX + zcCountY` удваивает частоту
- `|bpX| + |bpY|` вместо RMS для noiseFloor
- Знаковый RMS для направления — нестабильный метод

---

### 🟡 BUG-17: ThermalLocator — отсутствие потокобезопасности

**Файл:** `ThermalLocator.java`  
`addPoint()` и `update()` мутируют shared state без синхронизации. Если вызываются из разных потоков (GPS thread vs UI), возможны race conditions.

**Рекомендация:** Добавить `synchronized` на критические методы или использовать `ConcurrentLinkedQueue` для буфера.

---

### 🟡 BUG-18: ThermalLocator.Observation.timeMs — double вместо long

**Файл:** `ThermalLocator.java`, строка 39  
Потеря точности для больших timestamp'ов. `double` имеет 53 бита мантиссы, а `elapsedRealtime()` — до 52 бит. На практике это безопасно, но с точки зрения типов — ошибка.

**Рекомендация:** Заменить `double timeMs` на `long timeMs` в Observation.

---

### 🟡 BUG-19: ThermalLocator — отрицательный dtSec при clock skew

**Файл:** `ThermalLocator.java`, строка 162  
Если `nowMs < obs.timeMs` (корректировка часов), `dtSec` становится отрицательным, что даёт обратный дрейф и некорректный вес recency.

**Рекомендация:** `double dtSec = Math.max(0, (nowMs - obs.timeMs) / 1000.0);`

---

### 🟡 BUG-20: VarioSoundManager — completeSinePeriod() не работает

**Файл:** `VarioSoundManager.java`, строки 335–352  
Метод описан как «anti-click: завершить период синуса перед тишиной», но всегда возвращает `1`. Анти-клик не функционирует — при переходе между тонами будут слышимые щелчки.

**Рекомендация:** Реализовать properly: генерировать сэмплы до следующего zero-crossing, или применить fade-out envelope на 2–5 мс.

---

### 🟡 BUG-21: VarioThermalDetector — baseline загрязняется термиками

**Файл:** `VarioThermalDetector.java` (из предыдущего ревью, не исправлено)  
Скользящее среднее за 30 с включает значения из термика, поднимая baseline. Через 30 с в термике `effectiveThreshold` становится слишком высоким.

**Рекомендация:** Исключать из расчёта baseline значения, когда `thermalDetected = true`.

---

### 🟡 BUG-22: LiftDatabase — bestSectorLift никогда не уменьшается

**Файл:** `LiftDatabase.java`, строка 116  
Условие обновления `liftValues[idx] > bestSectorLift * 1.1` означает, что `bestSectorLift` только растёт. Если лучший сектор ослабевает (термик затухает), старый «лучший» сохраняется бесконечно.

**Рекомендация:** Добавить механизм衰减: при каждом новом круге уменьшать `bestSectorLift` на небольшой процент (например, `*= 0.95`), либо пересчитывать `bestSectorIndex` при `recordLift()`.

---

### 🟡 BUG-23: RadarRenderer — секторная диаграмма смещена на ~5°

**Файл:** `RadarRenderer.java`, строки 411–413  
Математика даёт сектор 0 с центром на 355° вместо 0° (N). Все направления термиков отображаются со сдвигом.

**Рекомендация:** Исправить формулу: `startAngle = -(i * 10)` (без +5), затем `drawArc(startAngle - 5, 10, ...)`.

---

## 4. Низкие ошибки (LOW)

### 🟢 BUG-24: WindDriftCalculator.driftPerKm — sin вместо tan

**Файл:** `WindDriftCalculator.java`, строка 94  
`Math.sin(driftRad) * 1000` даёт боковое смещение на км воздушного пути, а не путевого. Правильно: `Math.tan(driftRad) * 1000`. Для малых углов разница незначительна, но при сильном сносе — существенна.

---

### 🟢 BUG-25: WindDriftCalculator — неиспользуемое поле LOCALE

**Файл:** `WindDriftCalculator.java`, строка 130  
`private static final Locale LOCALE = Locale.US;` — мёртвый код.

---

### 🟢 BUG-26: IgcLogger — B-record формат с лишним пробелом

**Файл:** `IgcLogger.java`, строка 255  
Между N/E индикатором и долготой — пробел, не соответствующий стандарту FAI IGC.

---

### 🟢 BUG-27: IgcLogger — G-record не обеспечивает целостность

**Файл:** `IgcLogger.java`, строка 353  
G-record использует `seqNum % 65536` как псевдо-CRC — не обеспечивает обнаружение модификаций. Для FAI-санкционированных соревнований это неприемлемо.

**Рекомендация:** Документировать, что G-record — placeholder, не FAI-compliant. Для соревнований — реализовать RSA-подпись по спецификации IGC.

---

### 🟢 BUG-28: StaticMapLoader — нет лимита дискового кеша

**Файл:** `StaticMapLoader.java`  
Дисковый кеш растёт бесконечно («TTL бесконечный»). За сезон полётов может занять сотни мегабайт.

**Рекомендация:** Добавить LRU-евикцию на диске с лимитом, например 50 МБ.

---

## 5. Архитектурные замечания (не баги, но важно)

### ARC-01: MainActivity — God Object (2690 строк)

MainActivity делает ВСЁ: сенсоры, GPS, термики, рендеринг, TTS, логирование, симуляция, тест-режим, UI. Это нарушает SRP, делает код нечитаемым и нестабильным. Рендеринг в `onDraw()` содержит ~700 строк с GPS-trail обработкой, thermal management, circling manager updates, map refresh, IGC logging.

**Рекомендация:** Выделить минимум 5 отдельных классов:
- `ThermalOrchestrator` — управление детекцией и блипами
- `SimulationOrchestrator` — симуляция/сценарий/трек
- `GpsTrailManager` — GPS-трек и конвертация в пиксели
- `FlightUIController` — TTS, вибрация, яркость, WakeLock
- `MarkerManager` — entry/exit маркеры

### ARC-02: Аллокации в onDraw() — GC pressure

- `new Paint()` в blind mode каждый кадр (строка 1921)
- `new float[2]` для каждого trail point каждый кадр (строка 2218)
- `new float[]` массивы для маркеров каждый кадр (строки 2234–2236)
- `new RectF()`, `new Path()`, `new Rect()` каждый кадр

**Рекомендация:** Предвыделить все объекты один раз в конструкторе / onSizeChanged() и переиспользовать.

### ARC-03: Thread safety как системная проблема

Ни один из новых модулей (ThermalLocator, LiftDatabase, StaticMapLoader) не имеет синхронизации. При этом они вызываются из контекста, где данные приходят из разных потоков (GPS, sensor, UI). Это системная проблема проекта, которую нужно решать архитектурно — либо через thread-confined design (все обновления на main thread), либо через явную синхронизацию.

### ARC-04: Смешение шкал времени — продолжается

`System.currentTimeMillis()` используется в MainActivity минимум на 15+ позициях, тогда как новые модули (IgcLogger) корректно используют `elapsedRealtime()`. Это создаёт предпосылки для subtle timing bugs при корректировке системных часов.

**Рекомендация:** Принять единое правило: все внутренние таймеры — `elapsedRealtime()`, `currentTimeMillis()` — только для имён файлов и отображения.

---

## 6. Сводная таблица

| # | Критичность | Проблема | Файл | Сложность исправления |
|---|------------|---------|------|----------------------|
| 01 | 🔴 CRIT | FSM state — нет потокобезопасности | FlightStateMachine | Лёгкая |
| 02 | 🔴 CRIT | Двойной processSample/circlingManager.update | MainActivity | Лёгкая |
| 03 | 🔴 CRIT | ThermalBase — ветер инвертирован | ThermalBaseEstimator | Лёгкая |
| 04 | 🔴 CRIT | WakeLock утечка в onDestroy | MainActivity | Лёгкая |
| 05 | 🔴 CRIT | Shallow copy thermals + data race | MainActivity + RadarRenderer | Средняя |
| 06 | 🔴 CRIT | IgcLogger GPS-кеш не атомарен | IgcLogger | Средняя |
| 07 | 🟠 HIGH | FSM — гонка update/updateSpeedBased | FlightStateMachine | Средняя |
| 08 | 🟠 HIGH | FSM — checkStop() fullWindow инвертирован | FlightStateMachine | Лёгкая |
| 09 | 🟠 HIGH | FSM — мёртвая зона скорости 2.5–5 м/с | FlightStateMachine | Лёгкая |
| 10 | 🟠 HIGH | SimpleDateFormat не потокобезопасен | IgcLogger | Лёгкая |
| 11 | 🟠 HIGH | IgcLogger.logging не volatile | IgcLogger | Лёгкая |
| 12 | 🟠 HIGH | EKF-ветер блокируется при windConfidence ≥ 2 | CirclingManager | Лёгкая |
| 13 | 🟠 HIGH | NPE на getSignalProcessor() | MainActivity | Лёгкая |
| 14 | 🟠 HIGH | AsyncTask deprecated + Bitmap leak | StaticMapLoader | Средняя |
| 15 | 🟡 MED | FlightSimulator — 4 бага симуляции | FlightSimulator | Лёгкая |
| 16 | 🟡 MED | SignalProcessor — ZC/noiseFloor/направление | SignalProcessor | Лёгкая |
| 17 | 🟡 MED | ThermalLocator — нет синхронизации | ThermalLocator | Лёгкая |
| 18 | 🟡 MED | ThermalLocator.timeMs — double вместо long | ThermalLocator | Лёгкая |
| 19 | 🟡 MED | ThermalLocator — отрицательный dtSec | ThermalLocator | Лёгкая |
| 20 | 🟡 MED | VarioSoundManager — anti-click не работает | VarioSoundManager | Средняя |
| 21 | 🟡 MED | VarioThermalDetector baseline загрязняется | VarioThermalDetector | Средняя |
| 22 | 🟡 MED | LiftDatabase bestSectorLift не затухает | LiftDatabase | Лёгкая |
| 23 | 🟡 MED | RadarRenderer — секторы сдвинуты на 5° | RadarRenderer | Лёгкая |
| 24 | 🟢 LOW | WindDriftCalculator — sin вместо tan | WindDriftCalculator | Лёгкая |
| 25 | 🟢 LOW | WindDriftCalculator — мёртвый LOCALE | WindDriftCalculator | Лёгкая |
| 26 | 🟢 LOW | IgcLogger — B-record лишний пробел | IgcLogger | Лёгкая |
| 27 | 🟢 LOW | IgcLogger — G-record не FAI-compliant | IgcLogger | Документация |
| 28 | 🟢 LOW | StaticMapLoader — нет лимита дискового кеша | StaticMapLoader | Средняя |

---

## 7. Рекомендуемый порядок исправлений

### Фаза 1: Критические исправления (2–3 часа)

1. **BUG-01**: `FlightStateMachine.state` → `AtomicInteger` (30 мин)
2. **BUG-02**: Удалить дублирующие вызовы `processSample()`/`circlingManager.update()` из onDraw() (30 мин)
3. **BUG-03**: Инвертировать знаки wind drift в `ThermalBaseEstimator` (5 мин)
4. **BUG-04**: Добавить `releaseWakeLock()` в `onDestroy()` (15 мин)
5. **BUG-05**: Deep-copy ThermalBlips для rendering (30 мин)
6. **BUG-06**: Заменить volatile-поля IgcLogger на immutable `GpsSnapshot` (30 мин)

### Фаза 2: Высокие приоритеты (3–4 часа)

7. **BUG-07+08+09**: Исправить FlightStateMachine (гонка, fullWindow, мёртвая зона) (1.5 часа)
8. **BUG-10+11**: IgcLogger потокобезопасность (30 мин)
9. **BUG-12**: CirclingManager EKF-ветер gate (15 мин)
10. **BUG-13**: NPE-защита на getSignalProcessor() (30 мин)
11. **BUG-14**: StaticMapLoader — ExecutorService + bitmap.recycle() (1 час)

### Фаза 3: Средние + симуляция (4–5 часов)

12. **BUG-15**: FlightSimulator — altMsl, quiet period, шум, турбулентность (1.5 часа)
13. **BUG-16**: SignalProcessor — ZC, noiseFloor, направление (1 час)
14. **BUG-17–19**: ThermalLocator — синхронизация, типы, dtSec (1 час)
15. **BUG-20**: VarioSoundManager — anti-click (1 час)
16. **BUG-21–23**: VarioThermalDetector, LiftDatabase, RadarRenderer (1 час)

### Фаза 4: Архитектурные улучшения (отдельный спринт)

- Разделение MainActivity (ARC-01)
- Устранение аллокаций в onDraw (ARC-02)
- Единая политика потокобезопасности (ARC-03)
- Унификация шкал времени (ARC-04)

**Общее время на исправления: 10–12 часов**

---

## 8. Заключение

Версия v0.2.0 демонстрирует значительный рост функциональности: порты из XCSoar (CirclingManager, ThermalLocator, LiftDatabase, ThermalBaseEstimator, VarioSynthesiser, WindDriftCalculator), IGC-логгер, OSM-карта. Это серьёзный шаг вперёд по возможностям.

Однако качество кода не соответствует уровню функциональности. **Ключевые проблемы:**

1. **Ни один баг из предыдущего ревью не исправлен** — это системная проблема процесса разработки
2. **Потокобезопасность** — практически отсутствует в критических модулях (FSM, IgcLogger, ThermalLocator, LiftDatabase)
3. **Новый критический баг**: ThermalBaseEstimator показывает базу термика в неверном направлении — это прямая угроза безопасности полёта
4. **Архитектурный долг**: MainActivity на 2690 строк с God Object антипаттерном

Для авиационного приложения, где ошибка может стоить жизни пилоту, приоритет должен быть на **стабильности и корректности**, а не на новых фичах. Рекомендую приостановить добавление функциональности до исправления всех критических и высоких багов.
