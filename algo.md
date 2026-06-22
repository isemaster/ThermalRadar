
---
Важное замечание для программиста - параплан обычно снижается со скоростью 1-1.5 метра в секунду, если вдруг скорость сниженеия стала меньше на 0.75 метра в сек-то это уже термик -
надо делать спираль и смещаться к ядру. - это тоже надо учесть в программе. 
Думаю надо сделать это в настройках ползунок - детекция термика от -1 до +2 метр в сек скороподъености на усмотрение пилота.


## 11. Wind Detection — определение и учёт ветра

**Файлы:** `src/Computer/Wind/CirclingWind.cpp`, `WindEKF.cpp`, `Store.cpp`

### 11.1. CirclingWind — по спирали в термике (основной метод)

#### Идея
Когда пилот крутит в термике, GPS-трек описывает круги. Если есть ветер, эти круги **дрейфуют** — смещаются относительно земли. По разнице между путевой скоростью (GPS) и воздушной скоростью (TAS/барометрической) на разных курсах можно вычислить ветер.

#### Алгоритм шаг за шагом

```
1. Сбор семплов во время кручения (circling = true)
   Каждый семпл: { time, track (курс), ground_speed, tas }

2. Отслеживание полного оборота (360°)
   while current_circle < 360°:
     current_circle += Δtrack
     собираем семплы в буфер (макс 80 точек)

3. Контроль качества круга:
   - Минимум 8 семплов за оборот
   - Равномерность шага по времени (< 2 сек между семплами)
   - Стабильность угловой скорости (max_deviation / avg < 1.0)

4. Расчёт смещения (offset):
   offset = avg(ground_speed - tas)  // средний снос за круг

5. Амплитуда = оценка скорости ветра:
   amplitude = avg(|ground_speed - tas - offset|) × π/2
   clamp amplitude ≤ 30 м/с (реалистичный предел)

6. FitCosine — подгонка под косинусойду (определение направления):
   Лучшее направление ветра — то, при котором
   sum((ground_speed - tas - offset) - amplitude × cos(track - wind_bearing))² → min
   
   Поиск: iterative narrowing (6+3+3+3 шага)
     start: search 6 шагов по всему кругу
     narrow: делим шаг пополам, 3 итерации
     точность: < 2°
```

#### Параметры

| Параметр | Значение | Описание |
|----------|----------|----------|
| Макс семплов | 80 | Размер буфера |
| Мин семплов за оборот | 8 | Иначе недостаточно данных |
| Макс шаг по времени | 2.0 сек | Иначе данные устарели |
| Допуск неравномерности | 5% | Отклонение шага |
| Макс ветер | 30 м/с | Физический лимит |
| Точность поиска | < 2° | Итеративное сужение |
| Задержка GPS | 0.25 сек | Компенсация латентности |

#### Качество измерения (0-5)

```cpp
int quality = 5;
if (circle_quality > 0.2 + roundness_skew) quality = 4;
if (circle_quality > 0.3 + roundness_skew) quality = 3;
if (circle_quality > 0.4 + roundness_skew) quality = 2;
if (circle_quality > 0.5 + roundness_skew) quality = 1;
if (circle_quality > 0.7 + roundness_skew) quality = 0;

roundness_skew = wind_speed × 0.02 (GPS) или 0.01 (гироскоп)
// при сильном ветре круги кажутся менее ровными
```

### 11.2. WindEKF — расширенный фильтр Калмана (на прямых участках)

Когда пилот летит прямо (cruise), ветер определяется по разнице между GPS-вектором и TAS.

#### State vector

```
X = [wind_u, wind_v, scale_factor]
  wind_u     — ветер по оси X (м/с)
  wind_v     — ветер по оси Y (м/с)  
  scale_factor — поправка на точность TAS (0.5-1.5)
```

#### Update equation

```
Каждый GPS fix:
  dx = gps_vel.x - X[0]     // снос по X
  dy = gps_vel.y - X[1]     // снос по Y
  mag = hypot(dx, dy)       // модуль ветра
  
  Error = airspeed - X[2] × mag  // невязка измерения
  
  K[0] = -X[2] × dx/mag × k     // коэффициент усиления по X
  K[1] = -X[2] × dy/mag × k     // коэффициент усиления по Y
  K[2] = mag × 1e-5             // коэффициент усиления scale
  
  X[0] += K[0] × Error    // коррекция ветра
  X[1] += K[1] × Error    // коррекция ветра
  X[2] += K[2] × Error    // коррекция TAS
  
  k = k + 0.01 × (0.01 - k)  // адаптивное усиление
  
Clamp: X[2] ∈ [0.5, 1.5]
```

### 11.3. WindStore — хранение и агрегация

```
Структура:
  Map<altitude_layer, List<WindMeasurement>>
  
  WindMeasurement = { wind_vector, quality, timestamp }

Логика:
  1. Новое измерение → сохраняется в слой по высоте
  2. При запросе ветра:
     - Выбрать слой по текущей высоте
     - Взвешенное среднее: quality × 1/age
     - Если слоя нет — ближайший по высоте
  3. Старые измерения (> 5 мин) — удаляются
```

### 11.4. Учёт сноса в навигации

```cpp
// Расчёт поправки на ветер (Wind Drift)
drift_angle = asin(wind_speed × sin(wind_bearing - track) / airspeed)

// Effective track (с учётом сноса)
effective_heading = track - drift_angle

// Скорость относительно земли с учётом ветра
ground_speed = airspeed × cos(drift_angle) + 
               wind_speed × cos(wind_bearing - track)
```

### 11.5. Полная реализация на Kotlin

```kotlin
// ==========================================
// WindDetector.kt — модуль определения ветра
// ==========================================

data class WindEstimate(
    val bearing: Double,     // градусы, откуда дует
    val speed: Double,       // м/с
    val quality: Int,        // 0-5
    val altitude: Double     // м, высота замера
)

class CirclingWindDetector(
    private val maxSamples: Int = 80
) {
    data class Sample(
        val timeMs: Long,
        val track: Double,       // градусы
        val groundSpeed: Double,  // м/с
        val tas: Double           // м/с (true airspeed)
    )
    
    private val samples = ArrayDeque<Sample>(maxSamples)
    private var startTrack: Double? = null
    private var totalTurn: Double = 0.0
    
    fun feed(sample: Sample): WindEstimate? {
        if (startTrack == null) {
            startTrack = sample.track
            samples.add(sample)
            return null
        }
        
        // 1. Накопление поворота
        val dTrack = normalizeAngle(sample.track - samples.last().track)
        totalTurn += abs(dTrack)
        
        samples.addLast(sample)
        if (samples.size > maxSamples) samples.removeFirst()
        
        // 2. Полный оборот?
        if (totalTurn < 360.0) return null
        
        // 3. Валидация
        if (samples.size < 8) return null  // мало точек
        if (!validateUniformity()) return null  // неравномерный
        
        // 4. Расчёт ветра
        return calcWind()
    }
    
    private fun calcWind(): WindEstimate? {
        val n = samples.size
        val avgStepMs = (samples.last().timeMs - samples.first().timeMs) / (n - 1)
        
        // Offset (средний снос)
        val speedOffset = samples.sumOf { it.groundSpeed - it.tas } / n
        
        // Amplitude (скорость ветра)
        val amplitude = samples.sumOf { 
            abs(it.groundSpeed - it.tas - speedOffset) 
        } / n * (Math.PI / 2.0)
        
        if (amplitude > 30.0) return null  // нереалистично
        
        // Direction (поиск наилучшего угла)
        var bestBearing = 0.0
        var bestFit = Double.MAX_VALUE
        
        var step = 60.0  // 6 шагов по 360°
        var mid = 180.0
        repeat(4) {
            for (angle in (mid - step * 3)..(mid + step * 3) step step) {
                val fit = fitCosine(angle, amplitude, speedOffset)
                if (fit < bestFit) {
                    bestFit = fit
                    bestBearing = angle
                }
            }
            mid = bestBearing
            step /= 2.0
        }
        
        // Quality
        val q = estimateQuality(amplitude)
        
        return WindEstimate(
            bearing = bestBearing.toBearing(),
            speed = amplitude,
            quality = q,
            altitude = 0.0  // заполнить извне
        )
    }
    
    private fun fitCosine(bearing: Double, amplitude: Double, offset: Double): Double {
        return samples.sumOf { s ->
            val expected = amplitude * cos((s.track - bearing).toRadians()) + offset
            val actual = s.groundSpeed - s.tas
            (expected - actual).let { it * it }
        }
    }
    
    private fun validateUniformity(): Boolean {
        // Проверка равномерности шага
        val steps = samples.zipWithNext { a, b -> b.timeMs - a.timeMs }
        val avg = steps.average()
        return steps.all { abs(it - avg) / avg < 0.05 }
    }
    
    private fun estimateQuality(speed: Double): Int {
        return when {
            speed < 1.0 -> 5   // слабый ветер → точнее
            speed < 3.0 -> 4
            speed < 5.0 -> 3
            speed < 10.0 -> 2
            speed < 20.0 -> 1
            else -> 0
        }
    }
    
    private fun normalizeAngle(a: Double): Double {
        var r = a % 360
        if (r > 180) r -= 360
        if (r < -180) r += 360
        return r
    }
    
    fun reset() {
        samples.clear()
        startTrack = null
        totalTurn = 0.0
    }
}

// ==========================================
// WindEKF.kt — фильтр Калмана для оценки ветра
// ==========================================

class WindEKF {
    // State: [wind_u, wind_v, scale_factor]
    private var x = doubleArrayOf(0.0, 0.0, 1.0)
    private var k = 0.04  // усиление
    
    fun update(airspeed: Double, gpsVx: Double, gpsVy: Double) {
        val dx = gpsVx - x[0]
        val dy = gpsVy - x[1]
        val mag = hypot(dx, dy)
        
        if (mag < 0.1 || airspeed < 1.0) return  // нет данных
        
        // Kalman gains
        val k0 = -x[2] * dx / mag * k
        val k1 = -x[2] * dy / mag * k
        val k2 = mag * 1e-5
        
        // Innovation
        val error = airspeed - x[2] * mag
        
        // Update state
        x[0] += k0 * error
        x[1] += k1 * error
        x[2] += k2 * error
        
        // Clamp scale factor
        x[2] = x[2].coerceIn(0.5, 1.5)
        
        // Adaptive gain decay
        k = k + 0.01 * (0.01 - k)
    }
    
    val windSpeed: Double get() = hypot(x[0], x[1])
    val windDirection: Double get() = atan2(-x[0], -x[1]).toDegrees().toBearing()
    val quality: Int get() = when {
        k < 0.02 -> 3  // устоялось
        k < 0.05 -> 2
        k < 0.1 -> 1
        else -> 0
    }
    
    fun reset() {
        x = doubleArrayOf(0.0, 0.0, 1.0)
        k = 0.04
    }
}

// ==========================================
// WindStore.kt — хранение и отображение
// ==========================================

data class WindMeasurement(
    val bearing: Double,
    val speed: Double,
    val quality: Int,
    val altitude: Double,
    val timestamp: Long
)

class WindStore(
    private val layerHeight: Double = 100.0  // 100м слои
) {
    private val measurements = mutableMapOf<Int, MutableList<WindMeasurement>>()
    
    fun add(estimate: WindEstimate, altitude: Double) {
        val layer = (altitude / layerHeight).toInt()
        measurements.getOrPut(layer) { mutableListOf() }.add(
            WindMeasurement(
                bearing = estimate.bearing,
                speed = estimate.speed,
                quality = estimate.quality,
                altitude = altitude,
                timestamp = System.currentTimeMillis()
            )
        )
        // Очистка старых
        cleanup()
    }
    
    fun getWindAt(altitude: Double, maxAgeMs: Long = 300_000L): WindEstimate? {
        val layer = (altitude / layerHeight).toInt()
        val now = System.currentTimeMillis()
        
        // Ищем в текущем слое, потом в соседних
        val candidates = listOf(layer, layer-1, layer+1).flatMap { l ->
            measurements[l]?.filter { now - it.timestamp < maxAgeMs } ?: emptyList()
        }
        
        if (candidates.isEmpty()) return null
        
        // Взвешенное среднее по качеству
        val totalWeight = candidates.sumOf { it.quality.toDouble() }
        val avgBearing = candidates.sumOf { it.bearing * it.quality / totalWeight } % 360
        val avgSpeed = candidates.sumOf { it.speed * it.quality / totalWeight }
        val avgQuality = candidates.map { it.quality }.average().toInt()
        
        return WindEstimate(avgBearing, avgSpeed, avgQuality, altitude)
    }
    
    private fun cleanup() {
        val now = System.currentTimeMillis()
        measurements.values.forEach { list ->
            list.removeAll { now - it.timestamp > 600_000L }  // 10 мин
        }
    }
}

// ==========================================
// WindDriftCalculator.kt — учёт сноса ветром
// ==========================================

object WindDriftCalculator {
    data class WindCorrected(
        val heading: Double,       // курс с учётом сноса
        val groundSpeed: Double,   // скорость относительно земли
        val driftAngle: Double     // угол сноса
    )
    
    fun calculate(
        desiredTrack: Double,  // желаемый курс
        airspeed: Double,      // воздушная скорость
        windBearing: Double,   // откуда ветер
        windSpeed: Double      // скорость ветра
    ): WindCorrected {
        // Угол между ветром и курсом
        val windAngle = (windBearing - desiredTrack).toRadians()
        
        // Угол сноса
        val drift = asin(windSpeed * sin(windAngle) / airspeed)
        
        // Курс для компенсации
        val heading = (desiredTrack - drift.toDegrees()).toBearing()
        
        // Путевая скорость
        val gs = airspeed * cos(drift) + windSpeed * cos(windAngle)
        
        return WindCorrected(
            heading = heading,
            groundSpeed = gs,
            driftAngle = drift.toDegrees()
        )
    }
}
```

### 11.6. Отображение ветра на экране

```
Типовой экран парапланерного прибора:

 ╔═══════════════════════════════╗
 ║    ▲ True North   ○ N        ║
 ║    │                         ║
 ║    │     ← Wind 270°         ║
 ║    │      3.5 m/s            ║
 ║    │      NW (315°)          ║
 ║    │      @ 850m             ║
 ║    │      qual: 4/5          ║
 ║  ──┴──→ Track 045°           ║
 ║                               ║
 ║  [W] 12 km/h NW  ↑ 850m ▓▓▓═ ║
 ║                               ║
 ║  WindArrow:                   ║
 ║    ↑ направление ОТКУДА дует  ║
 ║    длина пропорциональна силе ║
 ║    цвет = качество            ║
 ╚═══════════════════════════════╝
```

Элементы отображения:
- **Стрелка ветра** — указывает откуда дует
- **Скорость** — м/с или км/ч
- **Высота** — на какой высоте измерен
- **Качество** — 0-5 (или процент)
- **Drift indicator** — куда сносит относительно курса

---

## 12. Heading/Compass Smoothing — сглаживание курса для отображения

### Проблема
В парапланеризме компас/heading на смартфоне прыгает из-за:
- Низкой точности магнитометра (±5-10°)
- Помех от динамиков/камеры телефона
- Разворота телефона на груди (качается вместе с пилотом)
- GPS track noise (±3-5°)

### 12.1. XCSoar: три уровня сглаживания heading

```
Уровень 1 — RAW (сырой heading с сенсора/гироскопа/GPS)
    ↓
Уровень 2 — LowPass α=0.3 (используется в TurnRate для детектора поворота)
    ↓
Уровень 3 — Display smoothing (для отображения компаса на экране UI)
```

В XCSoar сглаживание для детектора (α=0.3) и для отображения — **разные фильтры**.

### 12.2. Display smoothing — EMA с unwrap

```kotlin
class HeadingSmoother(
    private val periodMs: Long = 3000L  // 3 сек сглаживание для отображения
) {
    private var smoothHeading: Double? = null
    private var lastUpdateMs: Long = 0L
    private var unwrappedHeading: Double = 0.0  // без скачков 359→0

    /**
     * @param rawHeading градусы 0-360
     * @return отфильтрованный heading для экрана компаса
     */
    fun update(rawHeading: Double, nowMs: Long): Double {
        if (smoothHeading == null) {
            smoothHeading = rawHeading
            unwrappedHeading = rawHeading
            lastUpdateMs = nowMs
            return rawHeading
        }

        val dt = (nowMs - lastUpdateMs).coerceIn(1, 1000) / 1000.0
        lastUpdateMs = nowMs

        // 1. UNWRAP — разворачиваем угол без скачка 359→0→359
        var diff = rawHeading - unwrappedHeading
        while (diff > 180.0) diff -= 360.0
        while (diff < -180.0) diff += 360.0
        unwrappedHeading += diff  // теперь монотонно растёт/падает

        // 2. ALPHA — коэффициент EMA
        val alpha = if (periodMs > 0) {
            dt / (periodMs / 1000.0 + dt)
        } else 1.0

        // 3. EMA в unwrapped-пространстве
        smoothHeading = smoothHeading!! + alpha * (unwrappedHeading - smoothHeading!!)

        // 4. WRAP обратно в 0-360 для отображения
        return (smoothHeading!! % 360.0 + 360.0) % 360.0
    }

    /**
     * Deadband — не обновляем отображение если изменение < порога
     * Экономит CPU и не дёргает стрелку
     */
    fun shouldRedraw(
        currentDisplay: Double,
        newHeading: Double,
        thresholdDeg: Double = 1.5
    ): Boolean {
        val diff = abs(newHeading - currentDisplay)
        val circularDiff = minOf(diff, 360.0 - diff)
        return circularDiff >= thresholdDeg
    }
}
```

### 12.3. Alpha-Beta filter (предиктор)

Лучше чем EMA — учитывает скорость поворота и предсказывает следующий угол:

```kotlin
class AlphaBetaHeadingFilter(
    private val alpha: Double = 0.6,   // вес позиции
    private val beta: Double = 0.2     // вес скорости
) {
    private var xk: Double = 0.0   // filtered heading
    private var vk: Double = 0.0   // turn rate (°/с)
    private var lastTimeMs: Long = 0L
    private var unwrapped: Double = 0.0

    fun update(rawHeading: Double, timeMs: Long): Double {
        if (lastTimeMs == 0L) {
            xk = rawHeading
            unwrapped = rawHeading
            lastTimeMs = timeMs
            return rawHeading
        }

        val dt = (timeMs - lastTimeMs).coerceIn(10, 2000) / 1000.0
        lastTimeMs = timeMs

        // Unwrap
        val diff = normalizeAngle(rawHeading - unwrapped)
        unwrapped += diff

        // PREDICT: куда будем через dt
        val xkPred = xk + vk * dt
        val vkPred = vk

        // INNOVATION: на сколько ошиблись
        val innovation = unwrapped - xkPred

        // UPDATE
        xk = xkPred + alpha * innovation
        vk = vkPred + beta * innovation / dt

        // Wrap для отображения
        return wrapTo360(xk)
    }

    private fun normalizeAngle(d: Double): Double {
        var a = d
        while (a > 180) a -= 360
        while (a < -180) a += 360
        return a
    }

    private fun wrapTo360(a: Double) = (a % 360 + 360) % 360
}
```

### 12.4. Медианный фильтр от выбросов

Для подавления импульсных помех (магнитометр сбивается от динамиков/камеры):

```kotlin
class MedianHeadingFilter(private val window: Int = 5) {
    private val buffer = ArrayDeque<Double>(window)

    fun update(heading: Double): Double {
        buffer.addLast(heading)
        if (buffer.size > window) buffer.removeFirst()

        // Сортировка по круговой метрике
        val sorted = buffer.sorted()
        return sorted[sorted.size / 2]
    }
}
```

### 12.5. Рекомендуемый пайплайн для ThermalRadar

```
Sensor (магнитометр/GPS heading) → 10-50 Гц
    ↓
CLAMP выбросов → if |Δheading| > 50°/с → ограничить
    ↓
MEDIAN FILTER (окно 3-5) → убирает импульсный шум
    ↓
EMA или Alpha-Beta → плавная кривая (2-5 сек)
    ↓
DEADBAND (1.5-2°) → не перерисовываем UI без необходимости
    ↓
UI Compass Rose → отображение
```

### 12.6. Практические советы для Android

```kotlin
// 1. ВЫБОР ИСТОЧНИКА HEADING (от приоритета):
val headingSource = when {
    // A) Гироскоп + магнитометр (Samsung/Google rotation vector)
    hasRotationVector() -> Sensor.TYPE_GAME_ROTATION_VECTOR
    // B) Только магнитометр (дёшево, но шумно)
    hasMagnetometer() -> Sensor.TYPE_MAGNETIC_FIELD
    // C) GPS bearing (нет магнитометра)
    else -> null  // используем GPS track
}

// 2. ЧАСТОТА ОБНОВЛЕНИЯ
//    Гироскоп: 10-50 Гц → сглаживание 0.5-1 сек (малая задержка)
//    GPS: 1 Гц → сглаживание 3-5 сек (большая задержка, но нет шума)

// 3. ПЕРИОД СГЛАЖИВАНИЯ ПО РЕЖИМУ ПОЛЁТА:
//    Кручу термик: periodMs = 3000 (плавно, не отвлекает)
//    Лечу прямо:   periodMs = 1500 (быстро, точнее)
//    Приземлился:  periodMs = 500  (мгновенно)

// 4. ФИЛЬТРУЙ НЕ ТОЛЬКО HEADING, НО И ОТРИСОВКУ
//    Используй deadband перед вызовом invalidate():
if (headingSmoother.shouldRedraw(lastDrawnHeading, newHeading, 1.5)) {
    compassView.setHeading(newHeading)
    compassView.invalidate()
    lastDrawnHeading = newHeading
}
```

### 12.7. Сравнение методов

| Метод | Плавность | Задержка | CPU | Убирает шум | Убирает выбросы | Удержание 360° |
|-------|-----------|----------|-----|-------------|----------------|----------------|
| **EMA** (3 сек) | 🟢 Отлично | 🟡 1.5 сек | 🟢 Низкий | 🟢 Да | 🟡 Частично | 🟢 unwrap |
| **Alpha-Beta** | 🟢 Отлично | 🟢 0.5 сек | 🟢 Низкий | 🟢 Да | 🟡 Частично | 🟢 |
| **Медиана** (n=5) | 🟡 Средне | 🟢 0 сек | 🟡 Средний | 🟡 Частично | 🟢 Да | 🟡 |
| **Медиана + EMA** | 🟢 Лучший | 🟡 1 сек | 🟡 Средний | 🟢 Отлично | 🟢 Отлично | 🟢 |

Рекомендация: **Медиана(3) + Alpha-Beta(α=0.6, β=0.2)** — баланс плавности и задержки.

