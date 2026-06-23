package com.termo1.radar.logging;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * IgcLogger — запись IGC-формата (FR), 1 Гц, весь полёт.
 *
 * IGC — стандарт для парапланеризма: совместимость с XCSoar, SeeYou, Leonardo.
 *
 * Формат B-записи:
 *   B HHMMSS DDMMmmm N DDDMMmmm E A PPPPP GGGGG
 *
 * H-записи в заголовке:
 *   HFDTE: дата полёта
 *   HFPLT: пилот (пусто)
 *   HFGTY: тип глайдера (paraglider)
 *   HFFXA: точность GPS (35m)
 *   HFALG: давление (1013.25)
 *
 * I-запись: расширения (пусто, но декларируем)
 *
 * G-запись: GRecord (в реализации — CRC16 упрощённый)
 *
 * Логика:
 * - Автостарт с LogManager (через коллбэк)
 * - 1 Гц фиксированная частота (B-records каждую секунду)
 * - События через L-записи (CIRCLING_START, THERMAL, etc.)
 * - Нарезка по 10 мин (как CSV лог)
 * - Пишется в ту же папку logs/
 */
public class IgcLogger {

    private static final String TAG = "TERMO1_IGC";

    // ========================================================================
    // Константы
    // ========================================================================

    /** Частота записи: 1 Гц */
    private static final long LOG_INTERVAL_MS = 1000;

    /** Нарезка: 10 минут на файл */
    private static final long CHUNK_DURATION_MS = 10 * 60 * 1000L;

    /** Формат времени IGC: HHMMSS — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_TIME_FMT_TL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HHmmss", Locale.US));
    /** Формат даты IGC: DDMMYY — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_DATE_FMT_TL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("ddMMyy", Locale.US));

    // ========================================================================
    // Состояние
    // ========================================================================

    /** Флаг логирования — volatile для видимости между потоками */
    private volatile boolean logging;
    private String logDir;

    // Текущий файл
    private BufferedOutputStream currentOut;
    private String currentFileName;

    // Временные метки (элапсед)
    private long startElapsedMs;
    private long chunkStartElapsedMs;
    private int chunkIndex;

    // Для 1 Гц децимации
    private long lastLogElapsedMs;
    private int seqNum; // порядковый номер B-записи

    // GPS кэш (обновляется из MainActivity)
    private volatile double cachedLat;
    private volatile double cachedLon;
    private volatile float cachedAltGps;
    private volatile float cachedAltBaro;
    private volatile float cachedSpeed;
    private volatile float cachedHeading;
    private volatile float cachedAccuracy;
    private volatile long cachedFixAgeMs;

    // ========================================================================
    // GpsSnapshot — immutable snapshot для потокобезопасного обмена GPS данными
    // ========================================================================

    /** Неизменяемый снимок GPS-данных для потокобезопасности */
    private static class GpsSnapshot {
        final double lat, lon;
        final float altGps, altBaro, speed, heading, accuracy;
        final long fixAgeMs;
        final long timestampMs;

        GpsSnapshot(double lat, double lon, float altGps, float altBaro,
                    float speed, float heading, float accuracy, long fixAgeMs) {
            this.lat = lat;
            this.lon = lon;
            this.altGps = altGps;
            this.altBaro = altBaro;
            this.speed = speed;
            this.heading = heading;
            this.accuracy = accuracy;
            this.fixAgeMs = fixAgeMs;
            this.timestampMs = SystemClock.elapsedRealtime();
        }
    }

    /** Единый атомарный снимок (вместо 8 отдельных volatile полей) */
    private volatile GpsSnapshot gpsSnapshot;

    // ========================================================================
    // Callback
    // ========================================================================

    /** Коллбэк для получения событий */
    public interface IgcEventCallback {
        void onEvent(String eventType, String details);
    }

    private IgcEventCallback eventCallback;

    public void setEventCallback(IgcEventCallback cb) {
        this.eventCallback = cb;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    public IgcLogger() {}

    /** Установить директорию для логов (та же, что у LogManager) */
    public void setLogDir(String dir) {
        this.logDir = dir;
    }

    /**
     * Обновить GPS данные (вызывать из MainActivity в bgTask).
     * Атомарно заменяет весь снимок (без race condition между полями).
     */
    public void updateGps(double lat, double lon, float altGps, float altBaro,
                          float speed, float heading, float accuracy, long fixAgeMs) {
        gpsSnapshot = new GpsSnapshot(lat, lon, altGps, altBaro,
                                      speed, heading, accuracy, fixAgeMs);
    }

    // ========================================================================
    // Старт / Стоп
    // ========================================================================

    /** Начать запись IGC-лога */
    public void startLogging() {
        if (logging) return;
        if (logDir == null) {
            Log.e(TAG, "Log directory not set");
            return;
        }

        logging = true;
        startElapsedMs = SystemClock.elapsedRealtime();
        chunkStartElapsedMs = startElapsedMs;
        chunkIndex = 0;
        seqNum = 0;
        lastLogElapsedMs = 0;

        startNewChunk(true);

        Log.i(TAG, "IGC logging STARTED");
    }

    /** Остановить запись */
    public void stopLogging() {
        if (!logging) return;
        logging = false;
        closeCurrentFile();
        Log.i(TAG, "IGC logging STOPPED");
    }

    public boolean isLogging() { return logging; }

    // ========================================================================
    // Запись сэмпла (1 Гц — вызывать в bgTask)
    // ========================================================================

    /**
     * Записать один IGC B-record. Вызывать в bgTask (~10 Гц),
     * внутренняя децимация до 1 Гц.
     */
    public void recordSample() {
        if (!logging) return;

        long elapsedNow = SystemClock.elapsedRealtime();
        long elapsed = elapsedNow - startElapsedMs;

        // Проверка: пора ли новый чанк
        if (elapsedNow - chunkStartElapsedMs >= CHUNK_DURATION_MS) {
            closeCurrentFile();
            chunkIndex++;
            chunkStartElapsedMs = elapsedNow;
            startNewChunk(false);
        }

        // 1 Гц децимация
        if (elapsed - lastLogElapsedMs < LOG_INTERVAL_MS) return;
        lastLogElapsedMs = elapsed;

        // Проверка валидности GPS — атомарное чтение снимка
        GpsSnapshot gps = gpsSnapshot;
        if (gps == null) return;
        if (gps.lat == 0.0 && gps.lon == 0.0) return;
        if (gps.fixAgeMs > 5000) return;

        seqNum++;

        // Формируем B-record
        String bRecord = formatBRecord(
                elapsed, gps.lat, gps.lon,
                gps.altBaro, gps.altGps);

        writeLine(bRecord);
    }

    /**
     * Записать событие (L-record).
     */
    public void recordEvent(String eventType, String details) {
        if (!logging) return;
        long elapsedNow = SystemClock.elapsedRealtime();
        long elapsed = elapsedNow - startElapsedMs;
        String safe = details.replace(',', ';').replace('\n', ' ');

        // L-record: L HDLDT event_type:details
        String timeStr = formatIGCTime(elapsed);
        String lRecord = "L" + timeStr + " " + eventType + ":" + safe;
        writeLine(lRecord);

        // Также передаём внешнему коллбэку
        if (eventCallback != null) {
            eventCallback.onEvent(eventType, details);
        }
    }

    // ========================================================================
    // Форматирование
    // ========================================================================

    /**
     * Форматировать B-record из элапсед-времени и координат.
     *
     * B HHMMSS DDMMmmm N DDDMMmmm E A PPPPP GGGGG
     */
    private String formatBRecord(long elapsedMs,
                                  double lat, double lon,
                                  float baroAlt, float gpsAlt) {
        String timeStr = formatIGCTime(elapsedMs);
        String latStr = formatIGCLat(lat);
        String lonStr = formatIGCLon(lon);

        // Pressure altitude: PPPPP (5 digits, meters)
        int pressAlt = (int) Math.round(baroAlt);
        if (pressAlt < 0) pressAlt = 0;
        if (pressAlt > 99999) pressAlt = 99999;

        // GPS altitude: GGGGG (5 digits, meters)
        int gpsAltInt = (int) Math.round(gpsAlt);
        if (gpsAltInt < 0) gpsAltInt = 0;
        if (gpsAltInt > 99999) gpsAltInt = 99999;

        // A = GPS fix validity (1 = valid 3D fix, 2 = valid 2D fix)
        GpsSnapshot gps = gpsSnapshot;
        char fixChar = (gps != null && gps.fixAgeMs < 3000 && gps.accuracy < 50) ? 'A' : 'V';

        // BUG-26: B-record без лишнего пробела между N/S и lon — стандарт IGC
        // IGC: BHHMMSSDDMMmmmNDDDMMmmmEAPPPGGGGG или с пробелами группами
        // Наш формат: B HHMMSS DDMMmmmN DDDMMmmmE A PPPPP GGGGG
        return String.format(Locale.US, "B%s%s%s%s%s %c%05d%05d",
                timeStr, latStr, (lat >= 0 ? "N" : "S"),
                lonStr, (lon >= 0 ? "E" : "W"),
                fixChar, pressAlt, gpsAltInt);
    }

    /**
     * Форматировать время IGC: HHMMSS из elapsed ms.
     * Используем системное время старта + elapsed.
     */
    private String formatIGCTime(long elapsedMs) {
        long wallMs = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs) + elapsedMs;
        Date d = new Date(wallMs);
        return IGC_TIME_FMT_TL.get().format(d);
    }

    /**
     * Форматировать широту в IGC: DDMMmmm
     * 59.4095 → 5924.570N
     */
    private String formatIGCLat(double lat) {
        double abs = Math.abs(lat);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%02d%05.0f", deg, min * 1000);
    }

    /**
     * Форматировать долготу в IGC: DDDMMmmm
     * 29.3037 → 02918.222E
     */
    private String formatIGCLon(double lon) {
        double abs = Math.abs(lon);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%03d%05.0f", deg, min * 1000);
    }

    // ========================================================================
    // Файловые операции
    // ========================================================================

    private void startNewChunk(boolean isFirst) {
        File dir = new File(logDir, "igc");
        if (!dir.exists()) dir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        String dateStr = sdf.format(new Date(
                System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs)));

        String prefix = isFirst ? "Start" : "Flight";
        currentFileName = prefix + dateStr + ".igc";
        File file = new File(dir, currentFileName);

        try {
            currentOut = new BufferedOutputStream(new FileOutputStream(file));

            // Пишем заголовок H-записей
            writeHeader(dir, isFirst);
            Log.i(TAG, "Chunk started: " + currentFileName);

        } catch (IOException e) {
            Log.e(TAG, "Failed to create IGC file: " + currentFileName, e);
            currentOut = null;
        }
    }

    private void writeHeader(File dir, boolean isFirst) throws IOException {
        long wallStart = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs);
        Date startDate = new Date(wallStart);

        // HFDTE: дата
        writeLine("A" + IGC_DATE_FMT_TL.get().format(startDate));

        // HFxxx: заголовки
        writeLine("HFDTE" + IGC_DATE_FMT_TL.get().format(startDate));
        writeLine("HFPLTPILOT:ARTHUR");
        writeLine("HFGTYGLIDERTYPE:Paraglider");
        writeLine("HFGIDGLIDERID:TERMO1");
        writeLine("HFFXA035"); // точность 35м
        writeLine("HFALGALTPRESSURE:1013.25"); // QNH
        writeLine("HFGPS:Internal");

        // I-запись: расширения (0 байт доп. данных)
        writeLine("I000000");

        // Первый B-record — точка старта
        seqNum = 0;
    }

    private void closeCurrentFile() {
        if (currentOut == null) return;

        try {
            // Пишем G-record (упрощённый — количество записей)
            // ВНИМАНИЕ: G-record — placeholder, НЕ FAI-compliant.
            // Для FAI-санкционированных соревнований требуется RSA-подпись
            // по спецификации IGC G-record.
            int gSum = seqNum % 65536;
            writeLine("G" + String.format("%04X", gSum));

            currentOut.flush();
            currentOut.close();
            Log.i(TAG, "Chunk closed: " + currentFileName + " (" + seqNum + " records)");
        } catch (IOException e) {
            Log.e(TAG, "Failed to close IGC file", e);
        } finally {
            currentOut = null;
            currentFileName = null;
        }
    }

    private void writeLine(String line) {
        if (currentOut == null) return;
        try {
            byte[] bytes = (line + "\r\n").getBytes("ISO-8859-1");
            currentOut.write(bytes);
        } catch (IOException e) {
            Log.e(TAG, "IGC write error", e);
        }
    }

    /** Полный сброс */
    public void destroy() {
        stopLogging();
    }
}
