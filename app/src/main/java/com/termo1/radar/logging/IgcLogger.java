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
import java.util.zip.CRC32;

/**
 * IgcLogger — запись IGC-формата (FR), 1 Гц, весь полёт в ОДИН файл.
 *
 * IGC — стандарт для парапланеризма: совместимость с XCSoar, SeeYou, Leonardo.
 *
 * Режим работы:
 * - Один файл от startLogging() до stopLogging() — без чанков
 * - В заголовке полный набор H-записей
 * - B-records каждую секунду с валидными GPS-координатами
 * - G-record в конце с CRC32 от всего содержимого (корректный для IGC-совместимых программ)
 *
 * Формат B-записи:
 *   B HHMMSS DDMMmmm N DDDMMmmm E A PPPPP GGGGG
 */
public class IgcLogger {

    private static final String TAG = "TERMO1_IGC";

    // ========================================================================
    // Константы
    // ========================================================================

    /** Частота записи: 1 Гц */
    private static final long LOG_INTERVAL_MS = 1000;

    /** Формат времени IGC: HHMMSS — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_TIME_FMT_TL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HHmmss", Locale.US));
    /** Формат даты IGC: DDMMYY — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_DATE_FMT_TL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("ddMMyy", Locale.US));

    /** Формат даты для имени файла — ThreadLocal */
    private static final ThreadLocal<SimpleDateFormat> FILE_DATE_FMT_TL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US));

    // ========================================================================
    // Состояние
    // ========================================================================

    /** Флаг логирования — volatile для видимости между потоками */
    private volatile boolean logging;
    private String logDir;

    // Текущий файл
    private BufferedOutputStream currentOut;
    private String currentFileName;

    // Для подсчёта CRC32 всего содержимого файла
    private CRC32 crc32;

    // Временные метки (элапсед)
    private long startElapsedMs;

    // Для 1 Гц децимации
    private long lastLogElapsedMs;
    private int seqNum; // порядковый номер B-записи
    private boolean headerWritten;

    // ========================================================================
    // GpsSnapshot — immutable snapshot для потокобезопасного обмена GPS данными
    // ========================================================================

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

    public void setLogDir(String dir) {
        this.logDir = dir;
    }

    /**
     * Обновить GPS данные (вызывать из MainActivity в bgTask).
     * Атомарно заменяет весь снимок.
     */
    public void updateGps(double lat, double lon, float altGps, float altBaro,
                          float speed, float heading, float accuracy, long fixAgeMs) {
        gpsSnapshot = new GpsSnapshot(lat, lon, altGps, altBaro,
                                      speed, heading, accuracy, fixAgeMs);
    }

    // ========================================================================
    // Старт / Стоп
    // ========================================================================

    /** Начать запись IGC-лога — создаёт ОДИН файл на весь полёт */
    public void startLogging() {
        if (logging) return;
        if (logDir == null) {
            Log.e(TAG, "Log directory not set");
            return;
        }

        logging = true;
        startElapsedMs = SystemClock.elapsedRealtime();
        seqNum = 0;
        lastLogElapsedMs = 0;
        headerWritten = false;
        crc32 = new CRC32();

        // Создаём файл с единым именем на весь полёт
        File dir = new File(logDir, "igc");
        if (!dir.exists()) dir.mkdirs();

        String dateStr = FILE_DATE_FMT_TL.get().format(new Date());
        currentFileName = "Flight_" + dateStr + ".igc";
        File file = new File(dir, currentFileName);

        try {
            currentOut = new BufferedOutputStream(new FileOutputStream(file));
            writeHeader();
            headerWritten = true;
            Log.i(TAG, "IGC logging STARTED: " + currentFileName);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create IGC file: " + currentFileName, e);
            currentOut = null;
            logging = false;
        }
    }

    /** Остановить запись — закрывает файл с корректным G-record */
    public void stopLogging() {
        if (!logging) return;
        logging = false;
        closeCurrentFile();
        Log.i(TAG, "IGC logging STOPPED: " + currentFileName + " (" + seqNum + " records)");
    }

    public boolean isLogging() { return logging; }

    // ========================================================================
    // Запись сэмпла (1 Гц)
    // ========================================================================

    /**
     * Записать один IGC B-record. Вызывать в bgTask,
     * внутренняя децимация до 1 Гц.
     */
    public void recordSample() {
        if (!logging) return;

        long elapsedNow = SystemClock.elapsedRealtime();
        long elapsed = elapsedNow - startElapsedMs;

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
                gps.altBaro, gps.altGps, gps.fixAgeMs, gps.accuracy);

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
                                 float baroAlt, float gpsAlt,
                                 long fixAgeMs, float accuracy) {
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

        // A = GPS fix validity
        char fixChar = (fixAgeMs < 3000 && accuracy < 50) ? 'A' : 'V';

        return String.format(Locale.US, "B%s%s%s%s%s %c%05d%05d",
                timeStr, latStr, (lat >= 0 ? "N" : "S"),
                lonStr, (lon >= 0 ? "E" : "W"),
                fixChar, pressAlt, gpsAltInt);
    }

    /**
     * Форматировать время IGC: HHMMSS из elapsed ms.
     */
    private String formatIGCTime(long elapsedMs) {
        long wallMs = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs) + elapsedMs;
        Date d = new Date(wallMs);
        return IGC_TIME_FMT_TL.get().format(d);
    }

    /**
     * Форматировать широту в IGC: DDMMmmm
     */
    private String formatIGCLat(double lat) {
        double abs = Math.abs(lat);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%02d%05.0f", deg, min * 1000);
    }

    /**
     * Форматировать долготу в IGC: DDDMMmmm
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

    /** Заголовок IGC — пишется один раз при старте */
    private void writeHeader() throws IOException {
        long wallStart = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs);
        Date startDate = new Date(wallStart);

        writeLine("A" + IGC_DATE_FMT_TL.get().format(startDate));
        writeLine("HFDTE" + IGC_DATE_FMT_TL.get().format(startDate));
        writeLine("HFPLTPILOT:ARTHUR");
        writeLine("HFGTYGLIDERTYPE:Paraglider");
        writeLine("HFGIDGLIDERID:TERMO1");
        writeLine("HFFXA035");
        writeLine("HFALGALTPRESSURE:1013.25");
        writeLine("HFGPS:Internal");
        writeLine("I000000");
    }

    /** Закрыть файл — пишет G-record (CRC32) и flush + close */
    private void closeCurrentFile() {
        if (currentOut == null) return;

        try {
            // G-record — CRC32 от всего байтового содержимого (без байт самого G-record)
            // Стандартный подход для IGC: G + 4-байтный CRC32 в hex
            long crcValue = crc32.getValue();
            writeLine("G" + String.format("%08X", crcValue & 0xFFFFFFFFL));

            currentOut.flush();
            currentOut.close();
            Log.i(TAG, "File closed: " + currentFileName + " (" + seqNum + " records, CRC=" + String.format("%08X", crcValue) + ")");
        } catch (IOException e) {
            Log.e(TAG, "Failed to close IGC file", e);
        } finally {
            currentOut = null;
            currentFileName = null;
        }
    }

    /** Записать строку + CRLF в файл + обновить CRC32 */
    private void writeLine(String line) {
        if (currentOut == null) return;
        try {
            byte[] bytes = (line + "\r\n").getBytes("ISO-8859-1");
            currentOut.write(bytes);
            if (crc32 != null) {
                crc32.update(bytes);
            }
        } catch (IOException e) {
            Log.e(TAG, "IGC write error", e);
        }
    }

    /** Полный сброс */
    public void destroy() {
        stopLogging();
    }
}
