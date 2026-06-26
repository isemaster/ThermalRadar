package com.termo1.radar.core;

import android.os.SystemClock;
import com.termo1.radar.model.ThermalBlip;

/**
 * ThermalDetector — детектор термиков по микрораскачке.
 * Использует SignalProcessor для фильтрации и определения направления.
 *
 * Режимы:
 * - ПОИСК (SEARCH): уровень турбулентности ниже порога
 * - ПОДОЗРЕНИЕ (SUSPECT): уровень > порога, направление нестабильно
 * - ТЕРМИК (THERMAL): уровень > порога, направление стабильно
 * - ВНУТРИ (INSIDE): очень высокая амплитуда, близко к центру
 *
 * Для теста "на столе" (hand-wave):
 * - Порог снижен до 0.015g (~0.15 м/с²) — рука создаёт 0.03-0.1g
 * - Направление показывается сразу при стабильном сигнале > 0.5 с
 */
public class ThermalDetector {

    private final SignalProcessor signalProcessor;

    // Пороги (в g) с гистерезисом — SUSPECT < THERMAL для плавного перехода
    private static final float TH_SUSPECT    = 0.015f;  // ~0.15 м/с² — порог подозрения (ниже THERMAL для гистерезиса)
    private static final float TH_THERMAL    = 0.020f;  // ~0.20 м/с² → 75м (уверенный сигнал)
    private static final float TH_INSIDE     = 0.080f;  // ~0.80 м/с² → 37.5м (внутри термика)

    // Стабилизация направления: нужно N отсчётов подряд с уровнем > порога
    private static final int DIR_STABLE_COUNT = 25; // ~0.5 с при 50 Гц

    private int aboveThresholdCount = 0;
    private boolean directionReady = false;

    // Последнее созданное ThermalBlip (для MainActivity)
    private volatile ThermalBlip currentBlip = null;
    private volatile long lastBlipUpdateMs = 0;

    // Статус
    public static final int STATUS_SEARCH   = 0;
    public static final int STATUS_SUSPECT  = 1;
    public static final int STATUS_THERMAL  = 2;
    public static final int STATUS_INSIDE   = 3;

    private int status = STATUS_SEARCH;

    // Auto-threshold для адаптации к шуму
    private float adaptiveThreshold = TH_SUSPECT;

    // Сглаженное расстояние (EMA) — вместо random-дёрганий
    private float smoothDist = 60f;
    private static final float DIST_EMA_ALPHA = 0.08f;    // ~250ms при 50Гц

    // Нелинейная шкала strength: tanh(snr/scale) даёт градацию во всём диапазоне
    private static final float STRENGTH_SCALE = 15f;       // SNR, при котором strength≈6.3

    // === Верификация blip ===
    private static final float ANGLE_DIFF_RESET_THRESHOLD = 45f; // градусов, сброс при резкой смене направления
    private static final float AVG_FIRST_MIN = 0.0001f;          // мин. среднее первого периода для ratio
    // После стабилизации направления ждём 2 полных периода сигнала,
    // сравниваем level первого и последнего. Если растёт → CONFIRMED (термик),
    // если падает → REJECTED (пилот летит не туда), показываем "змейку".
    // Время верификации адаптивное: 2 периода × 50 Гц.
    // Для 2 Гц → 50 сэмплов (1с). Для 0.5 Гц → 200 сэмплов (4с).
    private static final float CONFIRM_GROW_RATIO = 1.2f;  // level вырос в 1.2+ → CONFIRMED
    private static final float CONFIRM_SHRINK_RATIO = 0.8f; // level упал в 0.8- → REJECTED
    private static final int MAX_REJECTIONS = 3;           // 3 REJECTED подряд → "змейка"
    private static final int MIN_CONFIRM_SAMPLES = 25;     // минимум 0.5с
    private static final int MAX_CONFIRM_SAMPLES = 250;    // максимум 5с

    private int confirmCount = 0;          // сколько сэмплов прошло после directionReady
    private float confirmStartLevel = 0f;  // level на момент начала верификации
    private int confirmTarget = 50;        // сколько сэмплов нужно для вердикта (адаптивно)
    private float confirmSumFirst = 0f;    // сумма level за первый период (для averaged comparison)
    private float confirmSumSecond = 0f;   // сумма level за второй период
    private int confirmHalfTarget = 25;    // половина confirmTarget — граница периодов
    private int consecutiveRejections = 0; // сколько REJECTED подряд
    private boolean blipConfirmed = false; // true: показываем blip, false: скрываем
    private String pilotAdvice = "";       // рекомендация пилоту ("ЛЕТАЙ ЗМЕЙКОЙ")
    private float lastConfirmedAngle = 0f; // угол последнего подтверждённого blip
    private boolean hasConfirmedBlip = false; // был ли хоть раз подтверждённый blip

    public ThermalDetector() {
        this.signalProcessor = new SignalProcessor();
    }

    public SignalProcessor getSignalProcessor() {
        return signalProcessor;
    }

    /**
     * Обработать один отсчёт акселерометра (ось X, Y в g).
     * Вызывать на каждом сэмпле.
     *
     * @param ax линейное ускорение по X (g)
     * @param ay линейное ускорение по Y (g)
     */
    public void processSample(float ax, float ay) {
        float level = signalProcessor.process(ax, ay);
        float snr = signalProcessor.getSnr();
        float nf = signalProcessor.getNoiseFloor();

        // Фильтр SNR: чистое снижение даёт SNR 0.5–1.3, термик — 5–500
        if (snr <= 3f) {
            status = STATUS_SEARCH;
            aboveThresholdCount = 0;
            directionReady = false;
            currentBlip = null;
            return;
        }

        // Адаптивные пороги: базовые static thresholds × noiseFloor
        float suspectThresh  = Math.max(TH_SUSPECT,  nf * 3f);
        float thermalThresh  = Math.max(TH_THERMAL,  nf * 5f);
        float insideThresh   = Math.max(TH_INSIDE,   nf * 10f);
        adaptiveThreshold = suspectThresh;

        // Определение статуса
        if (level > insideThresh) {
            status = STATUS_INSIDE;
        } else if (level > thermalThresh) {
            status = STATUS_THERMAL;
        } else if (level > suspectThresh) {
            status = STATUS_SUSPECT;
        } else {
            status = STATUS_SEARCH;
            aboveThresholdCount = 0;
            return; // не обновляем blip
        }

        // Счётчик стабилизации
        if (status >= STATUS_SUSPECT) {
            aboveThresholdCount++;
            if (aboveThresholdCount >= DIR_STABLE_COUNT && !directionReady) {
                directionReady = true;
            }
        } else {
            aboveThresholdCount = 0;
            directionReady = false;
        }

        if (!directionReady) {
            return;
        }

        // ================================================================
        // Верификация blip: считаем CONFIRM_SAMPLES отсчётов,
        // сравниваем level начала и конца.
        // ================================================================

        // Запоминаем начальный level при старте верификации
        if (confirmCount == 0) {
            confirmStartLevel = level;
            confirmSumFirst = 0f;
            confirmSumSecond = 0f;
            // Адаптивное время: 2 полных периода сигнала
            float freq = signalProcessor.getDominantFrequency();
            float periodSec = 1f / Math.max(freq, 0.25f);
            confirmTarget = (int)(periodSec * 2f * 50f); // 2 периода × 50 Гц
            confirmTarget = Math.max(MIN_CONFIRM_SAMPLES, Math.min(MAX_CONFIRM_SAMPLES, confirmTarget));
            confirmHalfTarget = confirmTarget / 2;
        }
        confirmCount++;

        // Накопление level: первый период → confirmSumFirst, второй → confirmSumSecond
        if (confirmCount <= confirmHalfTarget) {
            confirmSumFirst += level;
        } else {
            confirmSumSecond += level;
        }

        // Если направление сильно изменилось (>45°) — сбрасываем верификацию
        float angleDeg = signalProcessor.getStableDirDeg();
        if (angleDeg < 0) angleDeg += 360f;
        if (angleDeg >= 360f) angleDeg -= 360f;

        if (confirmCount > 5 && hasConfirmedBlip) {
            float angleDiff = Math.abs(angleDeg - lastConfirmedAngle);
            if (angleDiff > 180f) angleDiff = 360f - angleDiff;
            if (angleDiff > ANGLE_DIFF_RESET_THRESHOLD) {
                // Резкая смена направления — сбрасываем подтверждение
                blipConfirmed = false;
                confirmCount = 0;
            }
        }

        // Достигли окна верификации — принимаем решение
        if (confirmCount >= confirmTarget && !blipConfirmed) {
            float avgFirst  = (confirmHalfTarget > 0) ? confirmSumFirst  / confirmHalfTarget : 0f;
            float avgSecond = (confirmTarget - confirmHalfTarget > 0)
                    ? confirmSumSecond / (confirmTarget - confirmHalfTarget) : 0f;
            float ratio = (avgFirst > AVG_FIRST_MIN) ? avgSecond / avgFirst : 1f;

            if (ratio >= CONFIRM_GROW_RATIO) {
                // Сигнал растёт — летим к термику! ✓
                blipConfirmed = true;
                hasConfirmedBlip = true;
                lastConfirmedAngle = angleDeg;
                consecutiveRejections = 0;
                pilotAdvice = "";
            } else if (ratio <= CONFIRM_SHRINK_RATIO) {
                // Сигнал падает — летим не туда. Сброс + "змейка"
                consecutiveRejections++;
                if (consecutiveRejections >= MAX_REJECTIONS) {
                    pilotAdvice = "ЛЕТАЙ ЗМЕЙКОЙ";
                }
                // Сбрасываем детекцию — ищем заново
                confirmCount = 0;
                directionReady = false;
                aboveThresholdCount = 0;
                currentBlip = null;
                return;
            } else {
                // Неопределённо — продолжаем наблюдение, сбрасываем счётчик
                confirmCount = 0;
                return;
            }
        }

        // Пока blip не подтверждён — не рисуем
        if (!blipConfirmed) {
            // Если прошло больше половины окна, а всё ещё не решили — даём шанс
            if (confirmCount < confirmTarget / 2) {
                return;
            }
            // Во второй половине окна показываем тусклый пробный blip
        }

        // ================================================================
        // Создаём ThermalBlip (только для CONFIRMED или пробного)
        // ================================================================

        // Strength: нелинейная шкала от SNR, чтобы не упиралось в потолок 8.0
        // tanh даёт S-кривую: SNR=5→str≈3.2, SNR=15→str≈6.3, SNR=50→str≈8.0
        float strength = 1f + 7f * (float) Math.tanh(snr / STRENGTH_SCALE);

        // При пробном blip — уменьшаем яркость
        if (!blipConfirmed) {
            strength *= 0.3f; // тусклый, чтобы не отвлекал
        }

        // Distance: RMS 0.05 м/с² = 150м, 0.2 = 75м, 0.8 = 37.5м (обратный квадрат)
        float rmsMs2 = level * 9.81f;
        float distFromRms = 150f * (float) Math.sqrt(0.05f / Math.max(rmsMs2, 0.01f));
        distFromRms = Math.max(10f, Math.min(150f, distFromRms));

        // Size factor от частоты: низкая частота → широкий термик → большой шарик
        float sizeFactor = signalProcessor.getFreqSizeFactor();

        // EMA — blip не дёргается, а плавно перемещается
        smoothDist += (distFromRms - smoothDist) * DIST_EMA_ALPHA;
        float dist = Math.max(10f, Math.min(150f, smoothDist));

        long now = SystemClock.elapsedRealtime();

        if (blipConfirmed && currentBlip != null) {
            // Термик подтверждён: создаём НОВЫЙ объект с заблокированным углом
            // Атомарная замена ссылки (volatile) — без data race с UI тредом
            ThermalBlip updated = new ThermalBlip(currentBlip.angle, strength, dist, "accel", now);
            updated.sizeFactor = sizeFactor;
            updated.bornMs = currentBlip.bornMs; // сохраняем original bornMs — blip не дублируется
            // Адаптивное время жизни: INSIDE = 15с, сильный = 12с, слабый = 8с
            if (status == STATUS_INSIDE) {
                updated.lifeMs = 15000L;
            } else if (strength > 3f) {
                updated.lifeMs = 12000L;
            } else {
                updated.lifeMs = 8000L;
            }
            currentBlip = updated;
        } else {
            // Исправлено TD-1: создать локально, заполнить поля, потом volatile publish
            // Иначе UI-тред мог увидеть lifeMs=0 (default long) → blip истёк мгновенно
            ThermalBlip nb = new ThermalBlip(angleDeg, strength, dist, "accel", now);
            nb.sizeFactor = sizeFactor;
            nb.lifeMs = blipConfirmed ? 12000L : 3000L;
            currentBlip = nb;
            if (blipConfirmed) {
                // Запомнили угол первого подтверждения
                lastConfirmedAngle = angleDeg;
                currentBlip.angle = angleDeg;
            }
        }
        lastBlipUpdateMs = now;
    }

    /** Получить текущий ThermalBlip для отображения. Может вернуть null. */
    public ThermalBlip getCurrentBlip() {
        return currentBlip;
    }

    /** Время последнего обновления blip. */
    public long getLastBlipUpdateMs() {
        return lastBlipUpdateMs;
    }

    /** Текущий статус детекции. */
    public int getStatus() {
        return status;
    }

    /** Текстовое представление статуса. */
    public String getStatusText() {
        // Если есть активная рекомендация — показываем её
        if (pilotAdvice.length() > 0) return pilotAdvice;

        switch (status) {
            case STATUS_SUSPECT:  return "ПОДОЗРЕНИЕ";
            case STATUS_THERMAL:  return "ТЕРМИК РЯДОМ";
            case STATUS_INSIDE:   return "ВНУТРИ ТЕРМИКА";
            default:              return "ПОИСК";
        }
    }

    /** Статус подтверждения blip. */
    public boolean isBlipConfirmed() {
        return blipConfirmed;
    }

    /** Рекомендация пилоту (пустая строка = лети нормально). */
    public String getPilotAdvice() {
        return pilotAdvice;
    }

    /** Сброс калибровки. */
    public void resetCalibration() {
        signalProcessor.reset();
        aboveThresholdCount = 0;
        directionReady = false;
        currentBlip = null;
        status = STATUS_SEARCH;
        smoothDist = 60f;
        confirmCount = 0;
        confirmStartLevel = 0f;
        confirmTarget = 50;
        confirmSumFirst = 0f;
        confirmSumSecond = 0f;
        confirmHalfTarget = 25;
        blipConfirmed = false;
        consecutiveRejections = 0;
        pilotAdvice = "";
        hasConfirmedBlip = false;
    }
}
