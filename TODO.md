# ThermalRadar — план работ

## ✅ Выполнено

### v0.1.1
- [x] Стрелка ветра: перенесена в RadarRenderer (вращается с компасом)
- [x] Paint вынесены из onDraw() в поля (без GC-пауз)
- [x] TH_SUSPECT 0.020→0.010f (статус SUSPECT снова работает)
- [x] CirclingManager синхронизирован (synchronized)
- [x] onPause не убивает сенсоры/GPS/WakeLock при записи лога
- [x] WakeLock без таймаута при активной записи

### v0.1.2
- [x] Foreground Service (ThermalRadarService) с WakeLock + notification
- [x] Разрешения FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS

### v0.1.3
- [x] Звук/вибрация читаются из настроек (sound_enabled, vibrate_enabled)
- [x] Сломанные кнопки лога в Settings удалены
- [x] FlightStateMachine: выход из FINISHED → ON_GROUND для повторного взлёта
- [x] elapsedRealtime в SignalProcessor, VarioManager, FSM, CirclingManager, времени полёта
- [x] LogManager: synchronized, periodic flush 5с, join I/O-потока
- [x] Магнитное склонение (GeomagneticField) в SensorController
- [x] UiManager: нейтральный цвет vario для 0.0 (±0.05)
- [x] Удалён мёртвый вызов lifeLeft() в RadarRenderer

### v0.1.4
- [x] recordEvent без мёртвого параметра tsMs (2 params)
- [x] Airspeed slider в настройках (8–15 м/с, шаг 0.1)
- [x] GpsManager поля volatile
- [x] gpsAccuracy < 25 м для стартовой высоты
- [x] CSV-заголовок в первой строке Samples.csv
- [x] Магнетометр на SENSOR_DELAY_GAME
- [x] launchMode singleTask в манифесте
- [x] Симуляция на elapsedRealtime

---

## 🔴 Критические (надо сделать)

- [ ] **Race conditions**: CirclingManager читается из UI-треда без синхронизации. ThermalBlip поля не immutables
- [ ] **Направление спирали (лево/право)**: CirclingManager не сохраняет знак gyroEma, при реверсе totalAngle может занулиться, метка «крутим термик» не покажется
- [ ] **Foreground Service полный**: сейчас сервис только для WakeLock + notification, сенсоры/детекция/лог всё ещё в MainActivity. Для гарантированной работы в Doze нужно вынести всё в сервис

## 🟠 Высокий приоритет

- [ ] **Точность GPS**: отсев gpsAccuracy > 25 м при сборе точек спирали в CirclingManager (сейчас только для стартовой высоты)
- [ ] **BlindFlightMode геометрия**: yHoriz = ayHoriz + azHoriz — сомнительно при боковом наклоне телефона
- [ ] **Audio focus**: TTS + AudioTrack должны запрашивать AudioManager.requestAudioFocus, иначе звонки/уведомления конфликтуют
- [ ] **Время полёта**: flightTimeMs в UI считает SystemClock.elapsedRealtime() - logManager.getFlightStartMs(), но getFlightStartMs() возвращает wallStartMs — несоответствие шкал
- [ ] **Мёртвый код SensorController**: onPressureSample() нигде не вызывается, дублирующие поля vario/varioBuf/altFiltered — висят мёртвым грузом

## 🟡 Средний приоритет

- [ ] **8 секторов вместо 4** для центровки (N/NE/E/SE/S/SW/W/NW)
- [ ] **Порог крутки в настройки** (сейчас 18°/с — плоские спирали не детектятся)
- [ ] **Экспорт в IGC / KML** из логов
- [ ] **Индикация GPS + батареи** на HUD
- [ ] **Множественные термики** на радаре (сейчас только currentBlip)
- [ ] **Профили крыла** (EN-A / EN-B / EN-C/D / miniwing) — предустановки порогов
- [ ] **Тип крепления**: на груди / на руке / в кармане

## 🟢 Низкий приоритет / Полировка

- [ ] **Анимация нажатия кнопок**
- [ ] **Экспорт лога с главного экрана**
- [ ] **Google Drive / Dropbox интеграция**
- [ ] **Английский язык**: strings.xml EN + RU
- [ ] **Ночной режим** (Oakley Prizm-совместимый)
- [ ] **Double для state фильтра** Баттерворта (float дрейфует за часы)
- [ ] **i18n / локализация** всех строк
- [ ] **CI**: GitHub Actions — lint + test + assembleRelease
- [ ] **ProGuard / R8 config** для release
- [ ] **F-Droid / Play Internal Testing** публикация
