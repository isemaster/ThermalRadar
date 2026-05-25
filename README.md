# TERMO1 Radar — v0.0.1

Android-приложение для парапланеризма. Анализирует микрораскачку крыла через акселерометр телефона, определяет направление на термик и показывает его на радарном экране.

## Возможности

- **Детекция термиков** — полосовой фильтр Баттерворта 0.25–2.5 Гц, RMS-окно, zero-crossing frequency detector
- **Радар** — направление на термик с привязкой к компасу, кольца дальности 50/100/125/150м
- **Вариометр** — барометрический (или GPS fallback), сглаживание 0.75с, звук Brauniger IQ
- **Логирование** — CSV 50 Гц (22 колонки), автозапись по варио > 1 м/с, ZIP-сжатие
- **Симуляция** — 75-секундный демо-режим полёта
- **Тестовый режим** — 6 шагов проверки датчиков с real-time фидбеком
- **Настройки** — цветовые схемы (тёмная/светлая/высокий контраст), звук, усреднение варио

## Требования

- Android 8.0+ (API 26)
- Акселерометр (обязательно)
- Барометр (опционально — для вариометра)
- Магнитометр (опционально — для компаса)
- GPS (опционально — для лога скорости/высоты)

## Установка

```
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

> APK не подписан — для установки через USB используйте debug APK или подпишите своим ключом.

## Сборка

```bash
./gradlew assembleDebug   # подписан debug-ключом
./gradlew assembleRelease # unsigned, с R8 minification
```

## Структура проекта

```
app/src/main/java/com/termo1/radar/
├── MainActivity.java         — главный экран, сенсоры, рендеринг
├── Termo1FileProvider.java   — ContentProvider для шаринга логов
├── core/
│   ├── SignalProcessor.java  — фильтр Баттерворта + RMS + ZC
│   ├── SimulationManager.java — симуляция 75с
│   └── ThermalDetector.java  — детекция/верификация термиков
├── model/
│   └── ThermalBlip.java      — модель blip на радаре
└── ui/
    ├── RadarRenderer.java     — отрисовка радара Canvas
    ├── SettingsActivity.java  — экран настроек
    ├── UiManager.java         — HUD: варио, высоты, статус
    └── VarioSoundManager.java — звук AudioTrack (Brauniger IQ)
```

## Лицензия

MIT
