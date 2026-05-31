# TERMO1 Radar — v0.0.5

Android-приложение для парапланеризма. Анализирует микрораскачку крыла через акселерометр телефона, определяет направление на термик и показывает его на радарном экране.

## Возможности

- **Детекция термиков** — полосовой фильтр Баттерворта 0.25–2.5 Гц, RMS-окно, zero-crossing frequency detector
- **Радар** — направление на термик с привязкой к компасу, кольца дальности 50/100/125/150м
- **Вариометр** — барометрический, с адаптивным сглаживанием, звук Brauniger IQ
- **Голосовые подсказки TTS** — направление + дистанция, 45° сектора, русский язык
- **Вибрация** — при смене статуса (ПОИСК → ТЕРМИК РЯДОМ → НАБОР)
- **Логирование** — CSV 50 Гц (27 колонок), ZIP-сжатие, нарезка по 10 мин, автозапись
- **Слепой полёт** — телефон в кармане, gravity-based tilt compensation, только TTS
- **Калибровка наклона** — запоминает угол крепления телефона на груди
- **Симуляция** — 75-секундный демо-режим полёта
- **Тестовый режим** — 6 шагов проверки датчиков с real-time фидбеком
- **Настройки** — звук, вибрация, голосовые подсказки, слепой полёт, цветовые схемы

## Требования

- Android 8.0+ (API 26)
- Акселерометр (обязательно)
- Барометр (опционально — для вариометра)
- Магнитометр (опционально — для компаса)
- GPS (опционально — для лога)

## Установка

```bash
adb install Termo1-Radar-v0.0.5.apk
```

## Сборка

```bash
./gradlew assembleDebug
```

## Документация

- [manual.md](manual.md) — полное руководство пилота
- [simula.md](simula.md) — методика проверки на турнике
- [calibration.md](calibration.md) — калибровка наклона телефона
- [tilt-calibration-plan.md](tilt-calibration-plan.md) — план реализации калибровки

## Структура проекта

```
app/src/main/java/com/termo1/radar/
├── MainActivity.java           — главный экран, сенсоры, рендеринг
├── core/
│   ├── SignalProcessor.java    — фильтр Баттерворта + RMS + ZC
│   ├── SimulationManager.java  — симуляция 75с
│   └── ThermalDetector.java    — детекция/верификация термиков
├── flight/
│   ├── FlightStateMachine.java — автостарт/автостоп по высоте
│   └── BlindFlightMode.java    — слепой полёт (gravity tilt compensation)
├── sensors/
│   ├── SensorController.java   — регистрация датчиков, компас, калибровка наклона
│   └── VarioManager.java       — баровариометр с адаптивным alpha
├── gps/
│   └── GpsManager.java         — FusedLocationProvider (отключаемый)
├── logging/
│   └── LogManager.java         — 50 Гц, ZIP, нарезка по 10 мин
├── model/
│   └── ThermalBlip.java        — модель blip на радаре
└── ui/
    ├── RadarRenderer.java      — отрисовка радара Canvas
    ├── SettingsActivity.java   — экран настроек
    ├── UiManager.java          — HUD: варио, высоты, статус
    └── VarioSoundManager.java  — звук AudioTrack (Brauniger IQ)
```

## Лицензия

MIT
