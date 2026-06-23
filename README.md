# TERMO1 Radar — v0.2.0

Android-приложение для парапланеризма. Анализирует микрораскачку крыла через акселерометр, вариометр и гироскоп, определяет направление/близость термика, отслеживает крутку, помогает центрировать ядро, оценивает ветер.

Порты ключевых алгоритмов из **XCSoar** (CirclingComputer, ThermalLocator, LiftDatabaseComputer, ThermalBase, VarioSynthesiser, WindDriftCalculator).

## Возможности

### 🔥 Детекция термиков (2 канала)

| Канал | Принцип | Статус на экране |
|-------|---------|------------------|
| **Акселерометр** | Полосовой фильтр Баттерворта 0.25–2.5 Гц, RMS, zero-crossing + TH_SUSPECT SNR | «ТЕРМИК РЯДОМ» + жёлтый блип с направлением |
| **Вариометр** | Baseline снижения (30s скользящее окно). Если vario > baseline + threshold — сигнал | «ВАРИО ТЕРМИК» (без блипа, только статус) |

### 🎯 Центровка термика (XCSoar-стиль)

- **CirclingManager** — порт XCSoar CirclingComputer: 4-состояний (CRUISE / SUSPECT_CLIMB / CLIMB / SUSPECT_CRUISE) с гистерезисом
- **ThermalLocator** — взвешенный центроид точек при крутке с компенсацией дрейфа по ветру, recency-затухание
- **LiftDatabase** — 36 секторов по 10°, отслеживание лучшего сектора
- **ThermalBaseEstimator** — итеративный спуск на 10 шагов с учётом сноса ветром → координата базы термика
- Голос после каждого круга: *«ядро на ветер»*, *«ядро по ветру»*, *«Ядро север/восток/юг/запад»*

### 💨 Определение ветра

Два независимых источника, объединённые в `WindStore` с разбивкой по слоям высоты (100 м):

| Источник | Когда | Метод |
|----------|-------|-------|
| **CirclingWind** | В крутке | Трекинг GPS-центров спиралей → дрейф между оборотами |
| **WindEKF** | Прямой полёт | Фильтр Калмана (state: wind_u, wind_v, scale_factor) |

- **WindDriftCalculator** — расчёт угла сноса и effective heading по формуле XCSoar
- **WindStore** — хранение по высотным слоям, взвешенное среднее (quality × age), очистка старше 5 мин

### 🗺 OSM-карта под компасом (новое в v0.2.0)

- **StaticMapLoader** — подгрузка тайла OpenStreetMap 768×768 px, zoom 14
- Кеширование на SD-карту (LruCache + disk cache)
- **Сдвиг карты** с плавной интерполяцией при движении пилота
- Карта вращается вместе с компасом

### 🧭 Компас

- **Штатный режим**: магнитометр + гравитация → rotation matrix, heading через `SensorManager.getOrientation()`
- **Фильтрация heading**: Clamp (>50°/с) → Median(3) → Alpha-Beta(α=0.6, β=0.2) с unwrap + Deadband 1.5°
- **GPS fallback**: если магнитометр не откалиброван — rotation matrix из GPS-курса + гравитации
- **Без компаса и GPS** — raw (телефонные координаты)

### 🔊 Варио-звук (XCSoar VarioSynthesiser — новое в v0.2.0)

Порт XCSoar `VarioSynthesiser`:
- 6 аудиорежимов: silence / climb beep / climb pause / sink tone / continous tone / thermal beep
- Climb: 350→1000 Hz, период 200→50 ms (растёт с набором)
- Sink: 300→100 Hz (ниже -1.2 м/с)
- Dead band: тишина от -1.2 до -0.1 м/с
- Anti-click: завершение периода синуса перед тишиной
- AudioTrack USAGE_ALARM, 44100 Hz, MONO, 16-bit PCM

### 🗣 Голосовые подсказки TTS

- Направление + дистанция до термика (5 секторов по 45°, передняя полусфера)
- Центровка после каждого круга (лучший сектор)
- База термика (высота + направление)
- Дебаунс 8 секунд
- Русский язык

### 📝 Логирование

- **Samples.csv** — 50 Гц, все сенсоры, GPS, статус детекции
- **Events.csv** — события: голос, блипы, крутка, ветер, центровка
- **IGC-лог** (новое) — стандартный формат FR (1 Гц, B/H/L/G записи), совместимость с XCSoar / SeeYou / Leonardo
- Нарезка каждые 10 мин в ZIP level 9
- Монотонное время (`elapsedRealtime`)

### 🛠 Режимы

| Режим | Описание |
|-------|----------|
| **Симуляция 75с** | Демо-режим: полёт NORTH → EAST → круг → NORTH (с puffs по курсу) |
| **Тест полёта 100с** | Сценарий: лебёдка → свободный полёт → подход к термику → крутка → выход |
| **Трек-реплей** | Проигрывание IGC-трека с 2× скоростью |
| **Тестовый режим** | 6 шагов проверки датчиков с real-time фидбеком |
| **Слепой полёт (карман)** | Яркость 15%, только варио/высота/время + голос |
| **Солнечный режим** | Макс. яркость + контрастный HUD |

### ⚙️ Настройки

- Звук варио (6 режимов)
- Вибрация при смене статуса
- Слепой полёт / голосовые подсказки / солнечный режим
- Цветовая схема (тёмная / светлая / высокая контрастность)
- Сглаживание варио (5–100 отсчётов)
- Воздушная скорость (8–15 м/с)
- Порог Vario-термика (-1..+2 м/с, ползунок)
- Калибровка наклона телефона
- Отправка логов

## Требования

- Android 8.0+ (API 26)
- Акселерометр (обязательно)
- Барометр (опционально — для вариометра)
- Магнитометр (опционально — для компаса)
- GPS (опционально — для лога и карты)
- Интернет (опционально — для подгрузки OSM-карты)

## Установка

Скачать APK из [релизов](https://github.com/isemaster/ThermalRadar/releases) и установить на телефон.

```bash
adb install ThermalRadar-v0.2.0.apk
```

## Сборка

```bash
./gradlew assembleDebug
```

Быстрая сборка (с кешем): `./gradlew assembleDebug --offline`

## Документация

- [manual.md](manual.md) — руководство пилота
- [SIMULATION.md](SIMULATION.md) — методика проверки на турнике
- [calibration.md](calibration.md) — калибровка наклона телефона

## История версий

| Версия | Что нового |
|--------|------------|
| **0.2.0** | XCSoar-порты: CirclingManager (4-сост.), ThermalLocator (центроид + ветер), LiftDatabase (36 секторов), ThermalBaseEstimator, VarioSynthesiser (PCM-варио), WindDriftCalculator, IgcLogger (стандартный IGC), StaticMapLoader (OSM под компасом), переработан RadarRenderer (+514 строк), переработан VarioSoundManager (+253), INTERNET permission |
| **0.1.10** | GPS fallback для компаса: `buildRotationFromGpsHeading()`, отслеживание точности магнитометра |
| **0.1.9** | HeadingFilter (Median+Alpha-Beta), WindEKF + WindStore, VarioThermalDetector, ползунок порога |
| **0.1.8** | Исправление критических багов симуляции (clock, noise cal, puff geometry, ZC freq, TH_SUSPECT) |
| **0.1.0** | Детекция крутки, ветер по спиралям, Events.csv, монотонное время, слепой/солнечный режим |

## Структура проекта

```
app/src/main/java/com/termo1/radar/
├── MainActivity.java              — главный экран, сенсоры, рендеринг
├── ThermalRadarService.java       — foreground service
├── core/
│   ├── SignalProcessor.java       — фильтр Баттерворта + RMS + ZC
│   ├── SimulationManager.java     — симуляция 75с
│   ├── ThermalDetector.java       — детекция/верификация термиков (акселерометр)
│   ├── FlightSimulator.java       — тестовый сценарий 100с
│   └── TrackReplayer.java         — реплей IGC-трека
├── flight/
│   ├── CirclingManager.java       — [XCSoar] 4-сост. FSM крутки, центровка, ветер
│   ├── FlightStateMachine.java    — автостарт по высоте
│   ├── BlindFlightMode.java       — слепой полёт (gravity tilt compensation)
│   ├── VarioThermalDetector.java  — детекция термика по вариометру
│   ├── WindEKF.java               — фильтр Калмана для ветра на прямых
│   ├── WindStore.java             — хранение ветра по высотным слоям
│   ├── ThermalLocator.java        — [XCSoar] взвешенный центроид термика
│   ├── LiftDatabase.java          — [XCSoar] 36 секторов подъёма
│   ├── ThermalBaseEstimator.java  — [XCSoar] оценка базы термика
│   └── WindDriftCalculator.java   — [XCSoar] расчёт сноса ветром
├── sensors/
│   ├── SensorController.java      — регистрация датчиков, компас, калибровка наклона
│   ├── VarioManager.java          — баровариометр с адаптивным alpha
│   └── HeadingFilter.java         — сглаживание курса (Median+Alpha-Beta)
├── gps/
│   └── GpsManager.java            — FusedLocationProvider
├── logging/
│   ├── LogManager.java            — 50 Гц, ZIP, события, elapsedRealtime
│   └── IgcLogger.java             — [Новое] IGC-логгер (стандарт FR)
├── map/
│   └── StaticMapLoader.java       — [Новое] подгрузка OSM-карты под компас
├── model/
│   └── ThermalBlip.java           — модель blip на радаре
└── ui/
    ├── RadarRenderer.java         — отрисовка радара Canvas (+ OSM карта)
    ├── SettingsActivity.java      — экран настроек
    ├── UiManager.java             — HUD: варио, высоты, статус
    └── VarioSoundManager.java     — [XCSoar] PCM-варио (6 режимов)
```

## Лицензия

MIT
