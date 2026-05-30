package com.termo1.radar.logging;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * LogManager — логирование полёта с нарезкой по 10 минут.
 *
 * Особенности:
 * - Запись 50 Гц (каждый сэмпл LINEAR_ACCEL)
 * - Нарезка по 10 минут: Start → Flight → Flight → ... → Finish
 * - Per-chunk ZIP в памяти (ByteArrayOutputStream → ZipOutputStream)
 * - Без заголовков и концовок внутри файлов
 * - GPS cache (push model — обновляется 1 Гц из MainActivity)
 * - Формат имени: {Prefix}{yyyyMMddHHmmss}-{yyyyMMddHHmmss}.zip
 *
 * Имена чанков:
 *   Start{ts}-{ts}.zip   — первый чанк полёта
 *   Flight{ts}-{ts}.zip  — серединные чанки (каждые 10 мин)
 *   Finish{ts}-{ts}.zip  — последний чанк (при остановке)
 *
 * GPS-данные повторяются 50 раз в секунду (обновляются 1 Гц),
 * ZIP level 9 отлично сжимает повторы.
 */
public class LogManager {

    private static final String TAG = "TERMO1_LOG";

    // ========================================================================
    // Константы
    // ========================================================================

    private static final int SAMPLE_DECIMATION = 1;               // 1 = 50 Гц
    private static final long CHUNK_DURATION_MS = 10 * 60 * 1000L; // 10 мин

    // ========================================================================
    // Состояние
    // ========================================================================

    private boolean isLogging;
    private StringBuilder logBuffer;
    private long flightStartMs;          // время начала всей сессии
    private long chunkStartMs;           // время начала текущего чанка
    private int chunkIndex;              // 0 = Start, 1+ = Flight
    private int sampleCounter;
    private String externalLogDir;

    // I/O thread
    private HandlerThread ioThread;
    private Handler ioHandler;

    // ========================================================================
    // GPS cache (push model — обновляется 1 Гц из MainActivity)
    // ========================================================================

    private volatile double cachedGpsLat;
    private volatile double cachedGpsLon;
    private volatile float cachedGpsAlt;
    private volatile float cachedGpsSpeed;
    private volatile float cachedGpsHeading;

    public void updateGpsCache(double lat, double lon, float alt, float speed, float heading) {
        cachedGpsLat = lat;
        cachedGpsLon = lon;
        cachedGpsAlt = alt;
        cachedGpsSpeed = speed;
        cachedGpsHeading = heading;
    }

    // ========================================================================
    // Коллбэк для получения данных датчиков
    // ========================================================================

    private LogDataProvider dataProvider;

    public interface LogDataProvider {
        float getAccelX();
        float getAccelY();
        float getAccelZ();
        float getGyroX();
        float getGyroY();
        float getGyroZ();
        float getMagX();
        float getMagY();
        float getMagZ();
        float getPressure();
        float getPitch();
        float getRoll();
        float getLogHeading();
        String getThermalLogSuffix();
    }

    // ========================================================================
    // Init
    // ========================================================================

    public LogManager() {
        logBuffer = new StringBuilder(65536);
        ioThread = new HandlerThread("termo1-log-io");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
    }

    public void setDataProvider(LogDataProvider provider) {
        this.dataProvider = provider;
    }

    public void setExternalLogDir(String dir) {
        this.externalLogDir = dir;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Начать запись. Первый чанк получит префикс "Start".
     */
    public void startLogging() {
        if (isLogging) return;
        isLogging = true;
        long now = System.currentTimeMillis();
        flightStartMs = now;
        chunkStartMs = now;
        chunkIndex = 0;
        sampleCounter = 0;
        logBuffer.setLength(0);
        Log.i(TAG, "Logging STARTED");
    }

    /**
     * Остановить запись. Текущий чанк финализируется как "Finish".
     */
    public void stopLogging() {
        if (!isLogging) return;
        isLogging = false;

        long nowMs = System.currentTimeMillis();
        finalizeChunk(nowMs, true); // isFinish=true

        Log.i(TAG, "Logging STOPPED");
    }

    public void destroy() {
        stopLogging();
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
            ioHandler = null;
        }
    }

    // ========================================================================
    // Запись сэмпла (вызывается из сенсорного потока ~50 Гц)
    // ========================================================================

    public void recordSample() {
        if (!isLogging || dataProvider == null) return;

        sampleCounter++;
        if (sampleCounter % SAMPLE_DECIMATION != 0) return;

        long now = System.currentTimeMillis();
        long dtMs = now - flightStartMs;

        // Проверка: нужно ли начать новый чанк (каждые 10 мин)
        if (now - chunkStartMs >= CHUNK_DURATION_MS) {
            rotateChunk(now);
        }

        // Формат: dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,
        //   ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,
        //   thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor
        logBuffer.append(dtMs).append(',');
        logBuffer.append(cachedGpsSpeed).append(',');
        logBuffer.append(cachedGpsHeading).append(',');
        logBuffer.append(String.format(Locale.US, "%.6f", cachedGpsLat)).append(',');
        logBuffer.append(String.format(Locale.US, "%.6f", cachedGpsLon)).append(',');
        logBuffer.append(cachedGpsAlt).append(',');
        logBuffer.append(dataProvider.getAccelX() * 1000f).append(',');
        logBuffer.append(dataProvider.getAccelY() * 1000f).append(',');
        logBuffer.append(dataProvider.getAccelZ() * 1000f).append(',');
        logBuffer.append(dataProvider.getGyroX() * 1000f).append(',');
        logBuffer.append(dataProvider.getGyroY() * 1000f).append(',');
        logBuffer.append(dataProvider.getGyroZ() * 1000f).append(',');
        logBuffer.append(dataProvider.getMagX()).append(',');
        logBuffer.append(dataProvider.getMagY()).append(',');
        logBuffer.append(dataProvider.getMagZ()).append(',');
        logBuffer.append(dataProvider.getPressure()).append(',');
        logBuffer.append(dataProvider.getPitch()).append(',');
        logBuffer.append(dataProvider.getRoll()).append(',');
        logBuffer.append(dataProvider.getLogHeading());
        logBuffer.append(dataProvider.getThermalLogSuffix());
        logBuffer.append('\n');
    }

    // ========================================================================
    // Нарезка: ротация чанков
    // ========================================================================

    /** Закрыть текущий чанк, начать новый */
    private void rotateChunk(long nowMs) {
        finalizeChunk(nowMs, false);
        chunkStartMs = nowMs;
        chunkIndex++;
    }

    /** Финализировать чанк: ZIP в памяти → запись на диск */
    private void finalizeChunk(long nowMs, boolean isFinish) {
        if (logBuffer.length() == 0) return;

        String prefix;
        if (isFinish) {
            prefix = "Finish";
        } else if (chunkIndex == 0) {
            prefix = "Start";
        } else {
            prefix = "Flight";
        }

        final String data = logBuffer.toString();
        logBuffer.setLength(0);

        final String finalPrefix = prefix;
        final long startTs = chunkStartMs;
        final long endTs = nowMs;

        ioHandler.post(() -> writeZipChunk(finalPrefix, startTs, endTs, data));
    }

    // ========================================================================
    // ZIP-сжатие в памяти → запись на диск
    // ========================================================================

    /**
     * Сжать CSV-данные в ZIP в памяти, записать на диск одной операцией.
     * Файл: {prefix}{startTs}-{endTs}.zip, внутри .csv с тем же именем.
     */
    private void writeZipChunk(String prefix, long startMs, long endMs, String csvData) {
        if (externalLogDir == null) {
            Log.e(TAG, "External storage not available");
            return;
        }

        File logDir = new File(externalLogDir, "logs");
        if (!logDir.exists()) logDir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        String startStr = sdf.format(new Date(startMs));
        String endStr = sdf.format(new Date(endMs));
        String baseName = prefix + startStr + "-" + endStr;

        try {
            // Сжимаем CSV в ZIP в памяти
            byte[] csvBytes = csvData.getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream(csvBytes.length / 2);
            ZipOutputStream zos = new ZipOutputStream(baos);
            zos.setLevel(9);

            ZipEntry entry = new ZipEntry(baseName + ".csv");
            entry.setSize(csvBytes.length);
            zos.putNextEntry(entry);
            zos.write(csvBytes);
            zos.closeEntry();
            zos.close();

            // Пишем ZIP на диск одной операцией
            byte[] zipBytes = baos.toByteArray();
            File zipFile = new File(logDir, baseName);
            FileOutputStream fos = new FileOutputStream(zipFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            bos.write(zipBytes);
            bos.flush();
            bos.close();

            double ratio = (double) zipBytes.length / csvBytes.length * 100.0;
            Log.i(TAG, String.format(Locale.US,
                    "Chunk saved: %s (%d KB → %d KB, %.0f%%)",
                    baseName, csvBytes.length / 1024, zipBytes.length / 1024, ratio));

        } catch (IOException e) {
            Log.e(TAG, "Failed to write ZIP chunk: " + baseName, e);
        }
    }

    // ========================================================================
    // Состояние
    // ========================================================================

    public boolean isLogging() { return isLogging; }
    public long getFlightStartMs() { return flightStartMs; }
}
