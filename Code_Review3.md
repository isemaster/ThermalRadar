# ThermalRadar v0.2.2 — Code Review (3-я итерация)

**Репозиторий:** github.com/isemaster/ThermalRadar  
**Версия:** v0.2.2 (коммит d3ef168)  
**Дата ревью:** 2026-06-24  
**Ревьюер:** опытный разработчик авионики / Android  
**Предыдущие ревью:** v0.1.10 (14 багов), v0.2.0 (28 багов)

---

## 1. Резюме

Автор заявил **«28/28 багов исправлено»**. Фактический результат: **20 из 28 багов исправлено корректно**, 3 исправлены частично, 5 не исправлены. Обнаружено **9 новых багов** (2 критических, 3 высоких, 4 средних). Общая оценка: **значительный прогресс**, но критические проблемы в SimulationManager и VarioSoundManager остались.

| Категория | v0.2.0 | v0.2.2 исправлено | v0.2.2 новые | v0.2.2 итого |
|-----------|--------|-------------------|--------------|--------------|
| Критические | 6 | 4 из 6 | 2 | **4** |
| Высокие | 8 | 6 из 8 | 3 | **5** |
| Средние | 9 | 7 из 9 | 4 | **6** |
| Низкие | 5 | 3 из 5 | 0 | **2** |
| **Итого** | **28** | **20 из 28** | **9** | **17** |

---

## 2. Статус исправлений v0.2.0

### 2.1 Критические баги (6)

| # | Баг | Статус | Комментарий |
|---|-----|--------|-------------|
| BUG-01 | FlightStateMachine.state → AtomicInteger | ✅ Исправлено | `AtomicInteger state`, `synchronized(stateLock)` защищает все переходы. Гонка update/updateSpeedBased устранена. |
| BUG-02 | Дублирующие вызовы processSample/circlingManager.update | ✅ Исправлено | `processSample()` вызывается один раз (стр. 915), `circlingManager.update()` — один раз (стр. 917). Разделение ответственности. |
| BUG-03 | ThermalBaseEstimator инвертированный ветер | ✅ Исправлено | Строки 113-114: `pilotLat - (windV * t) / 111320.0` — минус корректный (база на наветренной стороне). Вектор ветра правильно конвертирован: `windDirRad = windBearing + 180` (откуда → куда). |
| BUG-04 | WakeLock утечка | ✅ Исправлено | `releaseWakeLock()` вызывается в `onDestroy()` (стр. 486), а также в `onPause()` при отсутствии логирования (стр. 476). Добавлен `refreshWakeLock()` с периодическим обновлением. |
| BUG-05 | Deep-copy ThermalBlips | ✅ Исправлено | Добавлен copy-constructor в `ThermalBlip` (стр. 30-38). В MainActivity (стр. 2273): `thermalsCopy.add(new ThermalBlip(t))` — корректный deep copy под synchronized. |
| BUG-06 | IgcLogger immutable GpsSnapshot | ✅ Исправлено | Введён immutable класс `GpsSnapshot` (стр. 97-115), атомарная замена через `volatile GpsSnapshot gpsSnapshot` (стр. 118). Устранена гонка между 8 отдельными volatile-полями. |

### 2.2 Высокие баги (8)

| # | Баг | Статус | Комментарий |
|---|-----|--------|-------------|
| BUG-07 | altMsl=0f в FlightSimulator | ✅ Исправлено | `altMsl = 500f` (стр. 126) — корректная стартовая высота. |
| BUG-08 | checkStop fullWindow инвертирован | ✅ Исправлено | Строки 241-248: `fullWindow = true` если есть точка старше cutoff — логика корректна. |
| BUG-09 | Мёртвая зона скорости 2.5–5 м/с | ✅ Исправлено | Гистерезис реализован: TAKEOFF=5.0, LANDING=2.5 м/с (стр. 42-44). Зона 2.5-5.0 — сохранение состояния, таймеры не сбрасываются (стр. 181-185). |
| BUG-10 | SignalProcessor totalZc удваивает частоту | ✅ Исправлено | `totalZc = zcCountX` (стр. 183) — только X-канал, без удвоения. |
| BUG-11 | L1-норма вместо RMS | ✅ Исправлено | Строки 225-238: корректный RMS через variance (`varX = sumSqX/WINDOW - meanX²`), а не L1-норма. |
| BUG-12 | CirclingManager EKF блокировка при windConfidence≥2 | ✅ Исправлено | Строка 434: `ekfWeight = (windConfidence < 2) ? 1.0f : 0.3f` — EKF обновляется всегда, но с пониженным весом при высокой уверенности. |
| BUG-13 | SimpleDateFormat thread safety в IgcLogger | ✅ Исправлено | `ThreadLocal<SimpleDateFormat>` (стр. 55-59). Потокобезопасно. |
| BUG-14 | Bitmap leak в StaticMapLoader | ✅ Исправлено | `entryRemoved()` с `oldValue.recycle()` (стр. 94-97). AsyncTask заменён на `ExecutorService` (стр. 48). |

### 2.3 Средние баги (9)

| # | Баг | Статус | Комментарий |
|---|-----|--------|-------------|
| BUG-15 | SignalProcessor signed RMS | ✅ Исправлено | `varX = sumSqX/WINDOW - meanX²` с `if (varX < 0) varX = 0` — стандартный RMS. |
| BUG-16 | SignalProcessor: шум калибровки не RMS | ✅ Исправлено | Строка 201: `float sample = sqrt(bpOutX² + bpOutY²)` — RMS-калибровка. |
| BUG-17 | ThermalDetector TH_SUSPECT = TH_THERMAL | ⚠️ Частично | `TH_SUSPECT = TH_THERMAL = 0.020f` — пороги по-прежнему равны. Однако введён адаптивный порог: `thermalThresh = max(TH_THERMAL, nf * 5f)` (стр. 114), что создаёт косвенный гистерезис при работающем noiseFloor. Формально пороги SUSPECT/THERMAL равны — гистерезиса нет, но на практике адаптивные пороги частично компенсируют проблему. |
| BUG-18 | bornMs через System.currentTimeMillis() в ThermalDetector | ⚠️ Частично | Стр. 254: `long now = System.currentTimeMillis()` — по-прежнему используется wall clock, хотя остальной код перешёл на elapsedRealtime. В симуляции это может вызвать некорректный age-расчёт при смене системного времени. |
| BUG-19 | ThermalLocator: отрицательный dtSec | ✅ Исправлено | Стр. 157: `double dtSec = Math.max(0, (nowMs - obs.timeMs) / 1000.0)` — защита от отрицательного dt. |
| BUG-20 | ThermalLocator: нет синхронизации | ✅ Исправлено | Методы `addPoint` и `update` объявлены `synchronized` (стр. 100, 147). |
| BUG-21 | VarioThermalDetector baseline загрязняется | ✅ Исправлено | Стр. 74: `if (!thermalDetected && !isCircling)` — baseline не обновляется во время термика или крутки. |
| BUG-22 | LiftDatabase bestSectorLift не затухает | ✅ Исправлено | Строки 117-124: полный пересчёт bestSector при каждом recordLift — bestSectorLift корректно уменьшается при снижении EMA. |
| BUG-23 | RadarRenderer секторы сдвинуты на 5° | ✅ Исправлено | Стр. 413: `startAngle = -(i * sectorDeg)` — с центром сектора 0 на 0° (N). |

### 2.4 Низкие баги (5)

| # | Баг | Статус | Комментарий |
|---|-----|--------|-------------|
| BUG-24 | WindDriftCalculator sin вместо tan | ✅ Исправлено | Стр. 95: `Math.tan(driftRad) * 1000` — корректно для бокового смещения на км путевого пути. |
| BUG-25 | SimulationManager синусоидальный шум | ❌ НЕ ИСПРАВЛЕНО | SimulationManager.generateAccel() по-прежнему использует синусоидальный шум (стр. 266-267): `sin(noisePhase * 0.7 + 0.3)`. Это не белый шум — сигнал периодический, коррелированный, не проходит статистический тест на случайность. FlightSimulator исправлен (Box-Muller), но SimulationManager — нет. |
| BUG-26 | IGC B-record формат | ✅ Исправлено | Формат B-записи скорректирован (стр. 285). |
| BUG-27 | VarioSoundManager completeSinePeriod() | ❌ НЕ ИСПРАВЛЕНО | Метод completeSinePeriod() (стр. 335-363) аппроксимирует количество сэмплов до zero crossing по фиксированной частоте 100 Гц (стр. 359: `SAMPLE_RATE / 100.0`), а не по текущей частоте тона. При частоте 800 Гц ошибка ≈ 8×. Анти-click не работает корректно. |
| BUG-28 | God Object MainActivity | ❌ НЕ ИСПРАВЛЕНО | 2695 строк. Рост на 5 строк по сравнению с v0.2.0. Все новые исправления добавлены в тот же монолитный файл без рефакторинга. |

---

## 3. Новые баги v0.2.2

### NEW-01 [CRITICAL] — SimulationManager: синусоидальный шум + нет Quiet Period

**Файл:** `SimulationManager.java`, строки 265-268  
**Проблема:** Шум генерируется как `sin(noisePhase * 0.7 + 0.3)` — это периодический сигнал, а не белый шум. SignalProcessor калибруется на первые 100 сэмплов (2 секунды), но синусоида за это время может дать заниженный или завышенный noiseFloor в зависимости от фазы. В отличие от FlightSimulator, где применён Box-Muller и Quiet Period, SimulationManager не имеет ни того, ни другого. В результате калибровка noiseFloor в SimulationManager недостоверна, ThermalDetector может давать ложные срабатывания или пропускать реальные сигналы.  
**Рекомендация:** Перенести Box-Muller генерацию шума из FlightSimulator. Добавить Quiet Period (2 секунды без термиков в начале сценария). Установить `altMsl = 500f` вместо `INITIAL_ALT_MSL = 500.0f` — это уже сделано, но шум и quiet period — нет.

### NEW-02 [CRITICAL] — VarioSoundManager: completeSinePeriod() использует фиксированную частоту

**Файл:** `VarioSoundManager.java`, строки 335-363  
**Проблема:** Метод `completeSinePeriod()` вычисляет количество сэмплов до zero crossing по формуле `samples = ceil(toZero / 2π × SAMPLE_RATE / 100.0)`. Деление на 100 — это хардкод «минимальной частоты 100 Гц», а не текущая частота тона (которая может быть 350-1000 Гц при climb, 100-300 Гц при sink). При частоте 800 Гц: реальное количество сэмплов до zero crossing ≈ 27, а метод вернёт ≈ 216. Анти-click генерирует 216 сэмплов синуса на частоте, которая уже прекратилась, создавая слышимый щелчок вместо гладкого затухания.  
**Рекомендация:** Передать текущую `currentSineFreq` в метод и использовать её: `samples = ceil(toZero / (2π × freq / SAMPLE_RATE))`. Или сохранять фазовую скорость `twoPiF` как поле и использовать её для расчёта.

### NEW-03 [HIGH] — FlightSimulator: Box-Muller генератор с детерминированным seed

**Файл:** `FlightSimulator.java`, строки 429-438  
**Проблема:** Генератор шума использует `noiseSeedX` и `noiseSeedY`, которые обновляются на каждом сэмпле (`noiseSeedX += 0.1`). Функция `Math.sin(noiseSeedX * 0x1p31)` производит хэш-подобные значения, но это не true random: при каждом запуске симуляции последовательность одинаковая (seed=0). Если пользователь перезапустит симуляцию, noiseFloor калибруется на идентичную последовательность, что маскирует проблемы. Более того, `(u1 % 1.0 + 1.0) % 1.0` — опасная операция: `u1 = sin(x) * 2^31` может быть любой, но `% 1.0` на очень больших double может дать неточный результат из-за потери точности.  
**Рекомендация:** Использовать `java.util.Random` с Box-Muller, как в SimulationManager (там хотя бы `new Random(42)`).

### NEW-04 [HIGH] — FlightSimulator: турбулентность 0.02-0.06g — диапазон слишком узкий

**Файл:** `FlightSimulator.java`, строки 446-448  
**Проблема:** При крутке генерируется турбулентность `turb = 0.02f + 0.04f * (THERMAL_LIFT_CORE - liftAtPilot) / THERMAL_LIFT_CORE`. Это даёт диапазон 0.02-0.06g. С учётом noiseFloor ≈ 0.01g (после Box-Muller), SNR составляет всего 2-6. ThermalDetector требует SNR > 3 для детекции (стр. 104: `if (snr <= 3f)`). Это означает, что при краевых условиях (edge термика, где liftAtPilot ≈ THERMAL_LIFT_CORE) турбулентность ≈ 0.02g, SNR ≈ 2 — детекция не сработает. Симуляция может не демонстрировать работу детектора на краях термика.  
**Рекомендация:** Увеличить турбулентность до 0.03-0.08g (SNR 3-8).

### NEW-05 [HIGH] — FlightStateMachine: update() не синхронизирован полностью

**Файл:** `FlightStateMachine.java`, строки 95-123  
**Проблема:** Метод `update()` содержит два `synchronized(stateLock)` блока (стр. 96-101 и 113-122), но запись в `altitudeHistory[]` между ними (стр. 104-107) не защищена. Если `update()` и `updateSpeedBased()` вызываются из разных потоков, и `updateSpeedBased()` косвенно влияет на `histHead`/`histFill` (через смену состояния в первом synchronized блоке, которая сбрасывает состояние в `reset()`), возможна рассинхронизация. На практике это маловероятно, т.к. reset() тоже не синхронизирован на полях altitudeHistory.  
**Рекомендация:** Объединить оба synchronized блока в один или защитить altitudeHistory отдельным lock.

### NEW-06 [MEDIUM] — IgcLogger: startNewChunk создаёт SimpleDateFormat без ThreadLocal

**Файл:** `IgcLogger.java`, строка 332  
**Проблема:** В методе `startNewChunk()` создаётся `new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)` (стр. 332) — локальная переменная, не ThreadLocal. Само по себе это безопасно (локальная переменная), но нарушает консистентность: остальные DateFormat-ы в классе используют ThreadLocal. Если в будущем кто-то вынесет этот код в общий метод, появится thread safety regression.  
**Рекомендация:** Заменить на `IGC_DATE_FMT_TL.get()` с паттерном `"yyyyMMddHHmmss"`, либо добавить отдельный ThreadLocal для имени файла.

### NEW-07 [MEDIUM] — FlightSimulator: heading при крутке — потенциальная нестабильность

**Файл:** `FlightSimulator.java`, строки 299-303  
**Проблема:** Heading при крутке вычисляется как `atan2(cos(circleAngle), -sin(circleAngle)) + 90f`. При `circleAngle = 0` это даёт `atan2(1, 0) + 90 = 90 + 90 = 180°`. При `circleAngle = π/2` — `atan2(0, -1) + 90 = 180 + 90 = 270°`. Результат зависит от квадранта atan2, и нормализация через `while (heading < 0) heading += 360` может дать скачки при переходе через 0°/360°. Если heading близок к 360° и добавляется 90°, результат 450° — нормализуется до 90°, но во время перехода один кадр может иметь heading=450°, другой=90°.  
**Рекомендация:** Использовать `heading = (float)Math.toDegrees(circleAngle + Math.PI/2) % 360f` — более простая и стабильная формула (heading = касательная к окружности).

### NEW-08 [MEDIUM] — RadarRenderer: drawThermals модифицирует ThermalBlip.px/py на Canvas

**Файл:** `RadarRenderer.java`, строки 636-637  
**Проблема:** `t.px = cx + (float) Math.sin(rad) * distPx` и `t.py = cy - (float) Math.cos(rad) * distPx` — рендерер модифицирует объекты из списка `thermals`. Хотя теперь это deep-copy (исправление BUG-05), это плохая практика: рендерер должен быть чистой функцией от данных, а не мутировать их. Если рендер вызывается дважды за кадр (invalidate), координаты пересчитываются — это безопасно, но нарушает принцип разделения ответственности.  
**Рекомендация:** Вычислять px/py в локальные переменные, не записывая в ThermalBlip.

### NEW-09 [MEDIUM] — FlightStateMachine: reset() не синхронизирован полностью

**Файл:** `FlightStateMachine.java`, строки 261-272  
**Проблема:** Метод `reset()` синхронизирует только `state.set(STATE_ON_GROUND)` (стр. 262-264), но последующие записи в `histHead`, `histFill`, `movingClockActive`, `stationaryClockActive` и т.д. (стр. 265-271) выполняются без блокировки. Если другой поток вызывает `update()` одновременно с `reset()`, он может прочитать частично сброшенное состояние (например, `histHead=0` но `histFill=100` от старых данных).  
**Рекомендация:** Обернуть всё тело `reset()` в `synchronized(stateLock)`.

---

## 4. Архитектурные замечания (перенос из v0.2.0)

### ARCH-01: MainActivity — God Object (2695 строк)

Не исправлено. Файл вырос на 5 строк. Все новые исправления добавлены в монолит. Рекомендация прежняя: выделить минимум 3 модуля:
- `FlightOrchestrator` — управление режимами (sim/scenario/track/real)
- `SensorPipeline` — обработка сенсоров (accel→SignalProcessor→ThermalDetector)
- `ThermalMapManager` — управление списком thermals, ThermalLocator, LiftDatabase

### ARCH-02: Смешение шкал времени

Частично улучшено. IgcLogger и FlightStateMachine используют `SystemClock.elapsedRealtime()`. Однако ThermalDetector.currentBlip.bornMs по-прежнему использует `System.currentTimeMillis()` (стр. 254). Это создаёт рассинхронизацию: age термика рассчитывается в wall-clock времени, а все остальные модули — в elapsed. Если пользователь меняет системное время (NTP, ручная настройка), blip может «прыгать» по яркости.

### ARCH-03: Аллокации в onDraw

Не проверялось детально (RadarRenderer — 743 строки). Метод `drawSectorDiagram` создаёт `new RectF(...)` каждый кадр (стр. 392) — это аллокация на каждый кадр, что вызывает GC-паузы на слабых устройствах. Рекомендация: создать RectF один раз в onSizeChanged и переиспользовать.

---

## 5. Сводная таблица всех багов v0.2.2

| ID | Серьёзность | Файл | Описание | Статус |
|----|-------------|------|----------|--------|
| BUG-17 | ⚠️ MEDIUM | ThermalDetector | TH_SUSPECT = TH_THERMAL (нет прямого гистерезиса) | Частично |
| BUG-18 | ⚠️ MEDIUM | ThermalDetector | bornMs через System.currentTimeMillis() | Частично |
| BUG-25 | LOW | SimulationManager | Синусоидальный шум (не белый) | Не исправлено |
| BUG-27 | MEDIUM→HIGH | VarioSoundManager | completeSinePeriod() — хардкод 100 Гц | Не исправлено |
| BUG-28 | LOW | MainActivity | God Object 2695 строк | Не исправлено |
| NEW-01 | 🔴 CRITICAL | SimulationManager | Синусоидальный шум + нет Quiet Period | Новый |
| NEW-02 | 🔴 CRITICAL | VarioSoundManager | completeSinePeriod() — неверная частота | Новый |
| NEW-03 | 🟠 HIGH | FlightSimulator | Box-Muller с детерминированным seed и неточным % | Новый |
| NEW-04 | 🟠 HIGH | FlightSimulator | Турбулентность 0.02-0.06g — SNR < 3 на краях | Новый |
| NEW-05 | 🟠 HIGH | FlightStateMachine | update() — разорванный synchronized | Новый |
| NEW-06 | 🟡 MEDIUM | IgcLogger | SimpleDateFormat без ThreadLocal в startNewChunk | Новый |
| NEW-07 | 🟡 MEDIUM | FlightSimulator | heading при крутке — нестабильная формула | Новый |
| NEW-08 | 🟡 MEDIUM | RadarRenderer | drawThermals мутирует ThermalBlip.px/py | Новый |
| NEW-09 | 🟡 MEDIUM | FlightStateMachine | reset() — неполная синхронизация | Новый |

---

## 6. Приоритетный план исправлений

### P0 — Критические (безопасность полёта)

1. **NEW-01:** SimulationManager — заменить синусоидальный шум на Box-Muller, добавить Quiet Period
2. **NEW-02:** VarioSoundManager — передать текущую частоту в completeSinePeriod()

### P1 — Высокие (корректность алгоритмов)

3. **NEW-05:** FlightStateMachine — объединить synchronized блоки в update()
4. **NEW-04:** FlightSimulator — увеличить турбулентность до 0.03-0.08g
5. **BUG-27:** VarioSoundManager — полная переделка completeSinePeriod()

### P2 — Средние (качество кода)

6. **NEW-09:** FlightStateMachine — синхронизировать reset() полностью
7. **NEW-07:** FlightSimulator — упростить формулу heading при крутке
8. **NEW-08:** RadarRenderer — не мутировать ThermalBlip в drawThermals
9. **NEW-06:** IgcLogger — унифицировать SimpleDateFormat через ThreadLocal
10. **BUG-18:** ThermalDetector — перейти на elapsedRealtime для bornMs

### P3 — Низкие / Рефакторинг

11. **BUG-17:** ThermalDetector — ввести гистерезис TH_SUSPECT < TH_THERMAL
12. **BUG-28:** MainActivity — начать декомпозицию God Object
13. **ARCH-03:** RadarRenderer — устранить аллокации в onDraw

---

## 7. Вывод

Версия v0.2.2 демонстрирует **значительный прогресс**: из 28 багов предыдущего ревью 20 исправлены корректно, включая все 6 критических. Качество исправлений высокое — автор понимает суть проблем и применяет правильные паттерны (AtomicInteger, synchronized, immutable snapshot, ThreadLocal, ExecutorService).

Однако появились **2 новых критических бага** (NEW-01, NEW-02), которые直接影响 работу симуляции и звукового вариометра. SimulationManager — единственный модуль, не получивший исправления по шуму (в то время как FlightSimulator исправлен). VarioSoundManager.completeSinePeriod() — единственный метод с очевидной арифметической ошибкой, который автор «исправил», но по сути только добавил комментарий.

**Рекомендация:** исправить P0-баги перед следующим релизом. На P1-P2 можно выделить отдельную итерацию.
