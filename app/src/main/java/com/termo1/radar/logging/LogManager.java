package com.termo1.radar.logging;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
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

public class LogManager {

    private static final String TAG = "TERMO1_LOG";

    private static final int SAMPLE_DECIMATION = 1;
    private static final long CHUNK_DURATION_MS = 10 * 60 * 1000L;

    private boolean isLogging;
    private StringBuilder logBuffer;
    private StringBuilder eventBuffer;

    // Временная шкала: dtMs = elapsedRealtime - elapsedStartMs (монотонно, без скачков)
    private long elapsedStartMs;          // SystemClock.elapsedRealtime() при старте лога
    private long wallStartMs;             // System.currentTimeMillis() при старте (для привязки к реальному времени)
    private long chunkStartElapsed;       // elapsedRealtime начала текущего чанка
    private int chunkIndex;
    private int sampleCounter;
    private String externalLogDir;

    private HandlerThread ioThread;
    private Handler ioHandler;

    private volatile double cachedGpsLat;
    private volatile double cachedGpsLon;
    private volatile float cachedGpsAlt;
    private volatile float cachedGpsSpeed;
    private volatile float cachedGpsHeading;
    private volatile float cachedGpsAccuracy;
    private volatile long cachedGpsFixAge;

    public void updateGpsCache(double lat, double lon, float alt, float speed, float heading,
                                float accuracy, long fixAgeMs) {
        cachedGpsLat = lat;
        cachedGpsLon = lon;
        cachedGpsAlt = alt;
        cachedGpsSpeed = speed;
        cachedGpsHeading = heading;
        cachedGpsAccuracy = accuracy;
        cachedGpsFixAge = fixAgeMs;
    }

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
        float getVario();
        int getDetectStatus();
        String getThermalLogSuffix();
    }

    public LogManager() {
        logBuffer = new StringBuilder(65536);
        eventBuffer = new StringBuilder(4096);
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

    public void startLogging() {
        if (isLogging) return;
        isLogging = true;

        elapsedStartMs = SystemClock.elapsedRealtime();  // монотонная шкала
        wallStartMs = System.currentTimeMillis();        // для привязки к реальному времени

        chunkStartElapsed = elapsedStartMs;
        chunkIndex = 0;
        sampleCounter = 0;
        logBuffer.setLength(0);
        eventBuffer.setLength(0);

        // Первое событие — точка привязки к реальному времени
        eventBuffer.append("0,WALL_START,").append(wallStartMs).append('\n');

        Log.i(TAG, "Logging STARTED wall=" + wallStartMs + " elapsed=" + elapsedStartMs);
    }

    public void stopLogging() {
        if (!isLogging) return;
        isLogging = false;
        long wallNow = System.currentTimeMillis();
        finalizeChunk(wallNow, true);
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

    public void recordSample() {
        if (!isLogging || dataProvider == null) return;
        sampleCounter++;
        if (sampleCounter % SAMPLE_DECIMATION != 0) return;

        long elapsedNow = SystemClock.elapsedRealtime();
        long dtMs = elapsedNow - elapsedStartMs;

        // Проверка ротации чанка по elapsed-времени
        if (elapsedNow - chunkStartElapsed >= CHUNK_DURATION_MS) {
            rotateChunk(elapsedNow);
        }

        logBuffer.append(dtMs).append(',');
        logBuffer.append(cachedGpsSpeed).append(',');
        logBuffer.append(cachedGpsHeading).append(',');
        logBuffer.append(String.format(Locale.US, "%.6f", cachedGpsLat)).append(',');
        logBuffer.append(String.format(Locale.US, "%.6f", cachedGpsLon)).append(',');
        logBuffer.append(cachedGpsAlt).append(',');
        logBuffer.append(cachedGpsFixAge).append(',');
        logBuffer.append(cachedGpsAccuracy).append(',');
        logBuffer.append(dataProvider.getVario()).append(',');
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
        logBuffer.append(',');
        logBuffer.append(dataProvider.getDetectStatus());
        logBuffer.append('\n');
    }

    public void recordEvent(long tsMs, String eventType, String details) {
        if (!isLogging) return;
        // tsMs — может быть System.currentTimeMillis() от вызывающего кода.
        // Преобразуем в elapsed-шкалу: flightStartMs = wallStartMs,
        // elapsedStartMs — соответствующая elapsed-метка.
        // dtElapsed = tsMs - wallStartMs + (elapsed... нет, так нельзя.
        //
        // Правильно: вызывающий передаёт tsMs = System.currentTimeMillis().
        // Преобразуем: dtElapsed = elapsedStartMs + (tsMs - wallStartMs) — неверно,
        // т.к. между wallStartMs и elapsedStartMs нет постоянного соотношения.
        //
        // Лучшее решение: хранить и elapsed-время в вызывающем коде.
        // Но чтобы не ломать API, вычисляем через wall-clock разницу с поправкой на дрейф.
        // На практике в полёте (без NTP-скачков) разница wall-elapsed стабильна.

        long elapsedNow = SystemClock.elapsedRealtime();
        long wallNow = System.currentTimeMillis();

        // Вычисляем dtMs через elapsed (монотонно)
        long dtMs = elapsedNow - elapsedStartMs;

        eventBuffer.append(dtMs).append(',');
        eventBuffer.append(eventType).append(',');
        String safe = details.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
        eventBuffer.append(safe).append('\n');
    }

    private void rotateChunk(long elapsedNow) {
        // Для имени файла нужны wall-clock метки. Берём их сейчас.
        long wallNow = System.currentTimeMillis();
        finalizeChunk(wallNow, false);
        chunkStartElapsed = elapsedNow;
        chunkIndex++;
    }

    private void finalizeChunk(long wallNow, boolean isFinish) {
        if (logBuffer.length() == 0) return;

        String prefix;
        if (isFinish) {
            prefix = "Finish";
        } else if (chunkIndex == 0) {
            prefix = "Start";
        } else {
            prefix = "Flight";
        }

        final String csvData = logBuffer.toString();
        logBuffer.setLength(0);

        final String eventsData = eventBuffer.toString();
        eventBuffer.setLength(0);

        final String finalPrefix = prefix;
        // Имя файла — по wall-clock (человекочитаемо)
        final long startWall = wallStartMs + (chunkStartElapsed - elapsedStartMs);
        final long endWall = wallNow;

        ioHandler.post(() -> writeZipChunk(finalPrefix, startWall, endWall, csvData, eventsData));
    }

    private void writeZipChunk(String prefix, long startWallMs, long endWallMs,
                                String csvData, String eventsData) {
        if (externalLogDir == null) {
            Log.e(TAG, "External storage not available");
            return;
        }
        File logDir = new File(externalLogDir, "logs");
        if (!logDir.exists()) logDir.mkdirs();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        String startStr = sdf.format(new Date(startWallMs));
        String endStr = sdf.format(new Date(endWallMs));
        String baseName = prefix + startStr + "-" + endStr;

        try {
            byte[] csvBytes = csvData.getBytes("UTF-8");
            byte[] eventsBytes = eventsData.getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream(
                    (csvBytes.length + eventsBytes.length) / 2);
            ZipOutputStream zos = new ZipOutputStream(baos);
            zos.setLevel(9);

            ZipEntry samplesEntry = new ZipEntry(baseName + ".csv");
            samplesEntry.setSize(csvBytes.length);
            zos.putNextEntry(samplesEntry);
            zos.write(csvBytes);
            zos.closeEntry();

            if (eventsBytes.length > 0) {
                ZipEntry eventsEntry = new ZipEntry(baseName + "_events.csv");
                eventsEntry.setSize(eventsBytes.length);
                zos.putNextEntry(eventsEntry);
                zos.write(eventsBytes);
                zos.closeEntry();
            }

            zos.close();
            byte[] zipBytes = baos.toByteArray();
            File zipFile = new File(logDir, baseName);
            FileOutputStream fos = new FileOutputStream(zipFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            bos.write(zipBytes);
            bos.flush();
            bos.close();

            double ratio = (double) zipBytes.length / csvBytes.length * 100.0;
            Log.i(TAG, String.format(Locale.US,
                    "Chunk saved: %s (%d KB -> %d KB, %.0f%%)",
                    baseName, csvBytes.length / 1024, zipBytes.length / 1024, ratio));

        } catch (IOException e) {
            Log.e(TAG, "Failed to write ZIP chunk: " + baseName, e);
        }
    }

    public boolean isLogging() { return isLogging; }

    /** Время старта лога по wall-clock (System.currentTimeMillis) — для UI */
    public long getFlightStartMs() { return wallStartMs; }
}
