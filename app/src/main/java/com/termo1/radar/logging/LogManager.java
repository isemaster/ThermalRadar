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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * LogManager — companion sensor logger for IGC flights.
 * Writes a single .zip file per flight containing all sensor data as CSV.
 * - synchronized on all buffer access
 * - async I/O on dedicated HandlerThread
 * - base file name set externally (same as IGC file)
 */
public class LogManager {

    private static final String TAG = "TERMO1_LOG";

    private static final int SAMPLE_DECIMATION = 1;

    private boolean isLogging;
    private final Object bufferLock = new Object();
    private StringBuilder logBuffer;

    private long elapsedStartMs;
    private long wallStartMs;
    private int sampleCounter;
    private String externalLogDir;
    private String baseFileName;

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

    /** Set the base file name (without extension), same as the IGC file name. */
    public void setBaseFileName(String name) {
        this.baseFileName = name;
    }

    public void startLogging() {
        if (isLogging) return;
        isLogging = true;

        elapsedStartMs = SystemClock.elapsedRealtime();
        wallStartMs = System.currentTimeMillis();
        sampleCounter = 0;

        synchronized (bufferLock) {
            logBuffer.setLength(0);
            // CSV header
            logBuffer.append("dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,");
            logBuffer.append("gpsFixAge,gpsAccuracy,vario,");
            logBuffer.append("ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,");
            logBuffer.append("thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor,");
            logBuffer.append("detectStatus\n");
        }

        Log.i(TAG, "Logging STARTED wall=" + wallStartMs + " elapsed=" + elapsedStartMs);
    }

    public void stopLogging() {
        if (!isLogging) return;
        isLogging = false;
        writeFinalZip();
        Log.i(TAG, "Logging STOPPED");
    }

    public void destroy() {
        stopLogging();
        if (ioThread != null) {
            ioThread.quitSafely();
            try {
                ioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

        synchronized (bufferLock) {
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
    }

    public void recordEvent(String eventType, String details) {
        // Events are currently not included in the single CSV per flight.
        // Kept as a no-op to preserve the interface contract.
    }

    /** Write the final .zip with all collected sensor data to [externalLogDir]/logs/[baseName].zip */
    private void writeFinalZip() {
        synchronized (bufferLock) {
            if (logBuffer.length() == 0) return;
            final String csvData = logBuffer.toString();
            logBuffer.setLength(0);

            final String fName = baseFileName;
            final String logDirPath = externalLogDir;

            ioHandler.post(() -> {
                if (logDirPath == null || fName == null) {
                    Log.e(TAG, "Cannot write final ZIP: externalLogDir or baseFileName not set");
                    return;
                }
                File logDir = new File(logDirPath, "logs");
                if (!logDir.exists()) logDir.mkdirs();

                File zipFile = new File(logDir, fName + ".zip");

                try {
                    byte[] csvBytes = csvData.getBytes("UTF-8");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(csvBytes.length / 2);
                    ZipOutputStream zos = new ZipOutputStream(baos);
                    zos.setLevel(9);

                    ZipEntry entry = new ZipEntry(fName + ".csv");
                    entry.setSize(csvBytes.length);
                    zos.putNextEntry(entry);
                    zos.write(csvBytes);
                    zos.closeEntry();
                    zos.close();

                    byte[] zipBytes = baos.toByteArray();
                    FileOutputStream fos = new FileOutputStream(zipFile);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    bos.write(zipBytes);
                    bos.flush();
                    bos.close();

                    double ratio = (double) zipBytes.length / csvBytes.length * 100.0;
                    Log.i(TAG, String.format(Locale.US,
                            "Final ZIP saved: %s (%d KB -> %d KB, %.0f%%)",
                            zipFile.getAbsolutePath(),
                            csvBytes.length / 1024,
                            zipBytes.length / 1024,
                            ratio));

                } catch (IOException e) {
                    Log.e(TAG, "Failed to write final ZIP: " + zipFile.getAbsolutePath(), e);
                }
            });
        }
    }

    public boolean isLogging() { return isLogging; }
    public long getFlightStartMs() { return wallStartMs; }
}
