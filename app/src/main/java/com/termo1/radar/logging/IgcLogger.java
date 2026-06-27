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
 * IgcLogger — запись IGC-формата (FR), 1 Гц, весь полёт в ОДИН файл.
 *
 * IGC — стандарт для парапланеризма: совместимость с XCSoar, SeeYou, Leonardo.
 *
 * Режим работы:
 * - Один файл от startLogging() до stopLogging() — без чанков
 * - В заголовке полный набор H-записей
 * - B-records каждую секунду с валидными GPS-координатами
 * - G-record в конце с CRC-16-CCITT от всего содержимого (корректный для IGC-совместимых программ)
 *
 * Формат B-записи:
 *   B HHMMSS DDMMmmm N DDDMMmmm E A PPPPP GGGGG
 */
public class IgcLogger {

    private static final String TAG = "TERMO1_IGC";

    // ========================================================================
    // Константы
    // ========================================================================

    /** Частота записи: 5 Гц (1 Гц GPS + 5 Гц baro-altitude) */
    private static final long LOG_INTERVAL_MS = 200;

    /** Формат времени IGC: HHMMSS — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_TIME_FMT_TL =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat f = new SimpleDateFormat("HHmmss", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f;
            });
    /** Формат даты IGC: DDMMYY — ThreadLocal для потокобезопасности */
    private static final ThreadLocal<SimpleDateFormat> IGC_DATE_FMT_TL =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat f = new SimpleDateFormat("ddMMyy", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f;
            });

    /** Формат даты для имени файла — ThreadLocal */
    private static final ThreadLocal<SimpleDateFormat> FILE_DATE_FMT_TL =
            ThreadLocal.withInitial(() -> {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f;
            });

    // CRC-16-CCITT constants
    private static final int CRC16_POLY = 0x1021;

    // ========================================================================
    // Состояние
    // ========================================================================

    /** Флаг логирования — volatile для видимости между потоками */
    private volatile boolean logging;
    private String logDir;

    // Имя файла — можно задать извне (единое с LogManager)
    private String baseFileName;

    // Текущий файл
    private BufferedOutputStream currentOut;
    private FileOutputStream currentFos; // для fsync
    private String currentFileName;

    /** Для подсчёта CRC-16-CCITT всего содержимого файла */
    private int crc16 = 0xFFFF;

    /** StringBuilder для formatIGCTime (IL-2: без new Date() на 5Гц) */
    private final StringBuilder igcTimeSb = new StringBuilder(6);

    // Pilot/glider configuration
    private String pilotName = "UNKNOWN";
    private String gliderType = "Paraglider";
    private String gliderId = "UNKNOWN";

    // Временные метки (элапсед)
    private long startElapsedMs;
    private long wallStartMs; // H-08: зафиксировано при startLogging
    private long lastFlushMs;

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

    /** Задать имя файла извне (должно совпадать с LogManager). */
    public void setBaseFileName(String name) {
        this.baseFileName = name;
    }

    // ========================================================================
    // Pilot/glider setters
    // ========================================================================

    public void setPilotName(String pilotName) {
        this.pilotName = pilotName;
    }

    public void setGliderType(String gliderType) {
        this.gliderType = gliderType;
    }

    public void setGliderId(String gliderId) {
        this.gliderId = gliderId;
    }

    // ========================================================================
    // Pilot/glider getters
    // ========================================================================

    public String getPilotName() {
        return pilotName;
    }

    public String getGliderType() {
        return gliderType;
    }

    public String getGliderId() {
        return gliderId;
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
        // Defensive check: if already logging, stop first before restarting (R11)
        if (logging) {
            stopLogging();
        }
        if (logDir == null) {
            Log.e(TAG, "Log directory not set");
            return;
        }

        logging = true;
        startElapsedMs = SystemClock.elapsedRealtime();
        wallStartMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startElapsedMs); // H-08
        seqNum = 0;
        lastLogElapsedMs = 0;
        headerWritten = false;
        crc16 = 0xFFFF;

        // Создаём файл с единым именем на весь полёт
        File dir = new File(logDir, "igc");
        if (!dir.exists()) dir.mkdirs();

        String dateStr = FILE_DATE_FMT_TL.get().format(new Date());
        if (baseFileName != null) {
            currentFileName = baseFileName + ".igc";
        } else {
            currentFileName = "Flight_" + dateStr + ".igc";
        }
        File file = new File(dir, currentFileName);

        try {
            currentFos = new FileOutputStream(file);
            currentOut = new BufferedOutputStream(currentFos);
            writeHeader();
            headerWritten = true;
            lastFlushMs = SystemClock.elapsedRealtime();
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

    // IGC pipeline: live track buffer (for real-time IGC analysis)
    private final java.util.ArrayList<com.termo1.radar.igc.TrackPoint> liveTrack =
            new java.util.ArrayList<>();
    private static final int LIVE_TRACK_MAX = 7200; // 2 hours at 5Hz = 36000, use 7200 (1Hz)

    /** Получить текущий путь к IGC-файлу */
    public String getCurrentFilePath() {
        if (logDir == null || currentFileName == null) return null;
        return new java.io.File(new java.io.File(logDir, "igc"), currentFileName).getAbsolutePath();
    }

    /** Получить live track buffer (для IGC pipeline) */
    public com.termo1.radar.igc.TrackPoint[] getLiveTrackArray() {
        synchronized (liveTrack) {
            return liveTrack.toArray(new com.termo1.radar.igc.TrackPoint[0]);
        }
    }

    /** Очистить live track buffer (при старте/стопе полёта) */
    public void clearLiveTrack() {
        synchronized (liveTrack) {
            liveTrack.clear();
        }
    }

    public boolean isLogging() { return logging; }

    // ========================================================================
    // Запись сэмпла (1 Гц)
    // ========================================================================

    /**
     * Записать один IGC B-record. Вызывать из render loop,
     * внутренняя децимация до 5 Гц.
     * GPS обновляется 1 раз в секунду, baro-altitude — каждый раз.
     */
    public void recordSample() {
        if (!logging) return;

        long elapsedNow = SystemClock.elapsedRealtime();
        long elapsed = elapsedNow - startElapsedMs;

        // 5 Гц децимация
        if (elapsed - lastLogElapsedMs < LOG_INTERVAL_MS) return;
        lastLogElapsedMs = elapsed;

        // GPS snapshot — если нет, пишем без координат (только время)
        GpsSnapshot gps = gpsSnapshot;
        double lat = 0, lon = 0;
        float baroAlt = 0, gpsAlt = 0;
        long fixAge = 99999;
        float acc = 999;
        boolean hasFix = false;
        if (gps != null) {
            lat = gps.lat;
            lon = gps.lon;
            baroAlt = gps.altBaro;
            gpsAlt = gps.altGps;
            fixAge = gps.fixAgeMs;
            acc = gps.accuracy;
            // Исправлено IL-1: hasFix проверяет fixAge < 5000 && accuracy < 50f, не только null island
            hasFix = !(lat == 0.0 && lon == 0.0)
                    && fixAge < 5000
                    && acc < 50f;
        }

        seqNum++;

        String bRecord = formatBRecord(
                elapsed, lat, lon,
                baroAlt, gpsAlt, fixAge, acc, hasFix);
        writeLine(bRecord);

        // IGC pipeline: push to live track buffer (1Hz, every 5th B-record)
        // We push at 1Hz to keep buffer size manageable
        if (seqNum % 5 == 1) {
            synchronized (liveTrack) {
                float timeSec = elapsed / 1000f;
                com.termo1.radar.igc.TrackPoint tp =
                    new com.termo1.radar.igc.TrackPoint(
                        lat, lon, baroAlt, gpsAlt, timeSec, hasFix);
                liveTrack.add(tp);
                if (liveTrack.size() > LIVE_TRACK_MAX) {
                    liveTrack.remove(0);
                }
            }
        }
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
                                 long fixAgeMs, float accuracy,
                                 boolean fixValid) {
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

        // A/V = GPS fix validity (по hasFix, а не по fixAgeMs)
        char fixChar = fixValid ? 'A' : 'V';

        return String.format(Locale.US, "B%s%s%s%s%s%c%05d%05d",
                timeStr, latStr, (lat >= 0 ? "N" : "S"),
                lonStr, (lon >= 0 ? "E" : "W"),
                fixChar, pressAlt, gpsAltInt);
    }

    /**
     * Форматировать время IGC: HHMMSS из elapsed ms (H-08: wallStartMs фикс).
     * IL-2: без new Date() — ручной расчёт + StringBuilder, 0 аллокаций на 5Гц. */
    private String formatIGCTime(long elapsedMs) {
        long wallMs = wallStartMs + elapsedMs;
        long totalSec = wallMs / 1000;
        long hh = (totalSec / 3600) % 24;
        long mm = (totalSec / 60) % 60;
        long ss = totalSec % 60;
        igcTimeSb.setLength(0);
        if (hh < 10) igcTimeSb.append('0'); igcTimeSb.append(hh);
        if (mm < 10) igcTimeSb.append('0'); igcTimeSb.append(mm);
        if (ss < 10) igcTimeSb.append('0'); igcTimeSb.append(ss);
        return igcTimeSb.toString();
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
    // CRC-16-CCITT
    // ========================================================================

    /**
     * Update CRC-16-CCITT with new data bytes.
     * Polynomial: 0x1021, init: 0xFFFF, no reflection, no final XOR.
     */
    private void updateCrc16(byte[] data) {
        for (byte b : data) {
            crc16 ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc16 & 0x8000) != 0) {
                    crc16 = (crc16 << 1) ^ CRC16_POLY;
                } else {
                    crc16 <<= 1;
                }
            }
            crc16 &= 0xFFFF;
        }
    }

    // ========================================================================
    // Файловые операции
    // ========================================================================

    /** Заголовок IGC — пишется один раз при старте */
    private void writeHeader() throws IOException {
        long wallStart = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - startElapsedMs);
        Date startDate = new Date(wallStart);

        writeLine("ATER" + IGC_DATE_FMT_TL.get().format(startDate));
        writeLine("HFDTE" + IGC_DATE_FMT_TL.get().format(startDate));
        writeLine("HFPLTPILOT:" + pilotName);
        writeLine("HFGTYGLIDERTYPE:" + gliderType);
        writeLine("HFGIDGLIDERID:" + gliderId);
        writeLine("HFFXA035");
        writeLine("HFALGALTPRESSURE:1013.25");
        writeLine("HFGPS:Internal");
        writeLine("I000000");
    }

    /** Закрыть файл — пишет G-record (CRC-16-CCITT) и flush + close */
    private void closeCurrentFile() {
        if (currentOut == null) return;

        try {
            // G-record — CRC-16-CCITT от всего байтового содержимого (без байт самого G-record)
            // IGC формат: G + 4-значный CRC-16 в hex
            writeLine("G" + String.format("%04X", crc16 & 0xFFFF));

            currentOut.flush();
            currentOut.close();
            Log.i(TAG, "File closed: " + currentFileName + " (" + seqNum + " records, CRC=" + String.format("%04X", crc16 & 0xFFFF) + ")");
        } catch (IOException e) {
            Log.e(TAG, "Failed to close IGC file", e);
        } finally {
            currentOut = null;
            currentFileName = null;
        }
    }

    /** Записать строку + CRLF в файл + обновить CRC-16 + периодический flush (C-06) */
    private void writeLine(String line) {
        if (currentOut == null) return;
        try {
            byte[] bytes = (line + "\r\n").getBytes("ISO-8859-1");
            currentOut.write(bytes);
            updateCrc16(bytes);
            long now = SystemClock.elapsedRealtime();
            if (now - lastFlushMs > 5000) {
                currentOut.flush();
                if (currentFos != null) {
                    currentFos.getFD().sync();
                }
                lastFlushMs = now;
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
