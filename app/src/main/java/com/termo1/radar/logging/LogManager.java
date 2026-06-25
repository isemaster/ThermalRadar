package com.termo1.radar.logging;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * LogManager — companion sensor logger for IGC flights.
 * Streams CSV data to a temporary file during flight, then zips it on stop.
 * This avoids OOM on long flights (was accumulating all data in StringBuilder).
 * - synchronized on all file access
 * - async I/O on dedicated HandlerThread
 * - periodic flush every 5 seconds
 * - base file name set externally (same as IGC file)
 */
public class LogManager {

    private static final String TAG = "TERMO1_LOG";

    private static final int SAMPLE_DECIMATION = 1;

    /** Flush the writer every 5 seconds so we don't lose data on crash. */
    private static final long FLUSH_INTERVAL_MS = 5000L;

    private boolean isLogging;
    private final Object bufferLock = new Object();

    /** Temp file streamed to during flight. Null when not logging. */
    private BufferedWriter csvWriter;
    /** The temp file path. Deleted after zipping on stop. */
    private File tempCsvFile;

    private long elapsedStartMs;
    private long wallStartMs;
    private int sampleCounter;
    private String externalLogDir;
    private String baseFileName;

    private HandlerThread ioThread;
    private Handler ioHandler;

    /** Runnable that periodically flushes the CSV writer on the IO thread. */
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (bufferLock) {
                if (!isLogging || csvWriter == null) return;
                try {
                    csvWriter.flush();
                } catch (IOException e) {
                    Log.e(TAG, "Periodic flush failed", e);
                }
            }
            // Re-post as long as logging is active (checked at top of next run)
            ioHandler.postDelayed(this, FLUSH_INTERVAL_MS);
        }
    };

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

        final String fName = baseFileName;
        final String logDirPath = externalLogDir;
        if (fName == null || logDirPath == null) {
            Log.w(TAG, "startLogging skipped: baseFileName or externalLogDir not set");
            return;
        }

        isLogging = true;

        elapsedStartMs = SystemClock.elapsedRealtime();
        wallStartMs = System.currentTimeMillis();
        sampleCounter = 0;

        synchronized (bufferLock) {
            try {
                File logDir = new File(logDirPath, "logs");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }

                // Create a temp file for streaming CSV
                tempCsvFile = new File(logDir, fName + ".tmp");
                csvWriter = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(tempCsvFile), StandardCharsets.UTF_8),
                        8192 // 8 KB buffer
                );

                // CSV header
                csvWriter.write("dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,");
                csvWriter.write("gpsFixAge,gpsAccuracy,vario,");
                csvWriter.write("ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,");
                csvWriter.write("thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor,");
                csvWriter.write("detectStatus\n");

                // Start periodic flush
                ioHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);

                Log.i(TAG, "Logging STARTED wall=" + wallStartMs + " elapsed=" + elapsedStartMs
                        + " tempFile=" + tempCsvFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to open temp CSV file for logging", e);
                isLogging = false;
                tempCsvFile = null;
                csvWriter = null;
            }
        }
    }

    public void stopLogging() {
        if (!isLogging) return;
        isLogging = false;

        // Remove the periodic flush runnable
        ioHandler.removeCallbacks(flushRunnable);

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
            if (csvWriter == null) return;
            try {
                csvWriter.write(String.valueOf(dtMs));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(cachedGpsSpeed));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(cachedGpsHeading));
                csvWriter.write(',');
                csvWriter.write(String.format(Locale.US, "%.6f", cachedGpsLat));
                csvWriter.write(',');
                csvWriter.write(String.format(Locale.US, "%.6f", cachedGpsLon));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(cachedGpsAlt));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(cachedGpsFixAge));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(cachedGpsAccuracy));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getVario()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getAccelX() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getAccelY() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getAccelZ() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getGyroX() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getGyroY() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getGyroZ() * 1000f));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getMagX()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getMagY()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getMagZ()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getPressure()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getPitch()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getRoll()));
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getLogHeading()));
                csvWriter.write(dataProvider.getThermalLogSuffix());
                csvWriter.write(',');
                csvWriter.write(String.valueOf(dataProvider.getDetectStatus()));
                csvWriter.write('\n');
            } catch (IOException e) {
                Log.e(TAG, "Failed to write sample to temp CSV", e);
            }
        }
    }

    public void recordEvent(String eventType, String details) {
        // Events are currently not included in the single CSV per flight.
        // Kept as a no-op to preserve the interface contract.
    }

    /**
     * Close the temp CSV writer, then zip the temp file to [externalLogDir]/logs/[baseName].zip
     * on the IO handler thread. The temp file is deleted after zipping.
     */
    private void writeFinalZip() {
        // Capture local references under lock, then zip async outside the lock
        final File tempFile;
        final String fName;
        final String logDirPath;

        synchronized (bufferLock) {
            tempFile = this.tempCsvFile;
            fName = this.baseFileName;
            logDirPath = this.externalLogDir;

            // Close the writer first
            if (csvWriter != null) {
                try {
                    csvWriter.flush();
                    csvWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing CSV temp file", e);
                }
                csvWriter = null;
            }
            this.tempCsvFile = null;
        }

        if (tempFile == null || fName == null || logDirPath == null) {
            Log.e(TAG, "Cannot write final ZIP: tempFile, baseFileName, or externalLogDir not set");
            return;
        }

        ioHandler.post(() -> {
            File logDir = new File(logDirPath, "logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            File zipFile = new File(logDir, fName + ".zip");

            try {
                // Read the temp file and zip it
                byte[] csvBytes = readFileBytes(tempFile);
                if (csvBytes == null) {
                    Log.e(TAG, "Failed to read temp CSV file for zipping");
                    return;
                }

                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
                zos.setLevel(9);

                ZipEntry entry = new ZipEntry(fName + ".csv");
                entry.setSize(csvBytes.length);
                zos.putNextEntry(entry);
                zos.write(csvBytes);
                zos.closeEntry();
                zos.close();

                double ratio = (double) zipFile.length() / csvBytes.length * 100.0;
                Log.i(TAG, String.format(Locale.US,
                        "Final ZIP saved: %s (%d KB -> %d KB, %.0f%%)",
                        zipFile.getAbsolutePath(),
                        csvBytes.length / 1024,
                        zipFile.length() / 1024,
                        ratio));

            } catch (IOException e) {
                Log.e(TAG, "Failed to write final ZIP: " + zipFile.getAbsolutePath(), e);
            } finally {
                // Delete the temp file regardless of success/failure
                if (tempFile.exists()) {
                    boolean deleted = tempFile.delete();
                    if (!deleted) {
                        Log.w(TAG, "Failed to delete temp CSV file: " + tempFile.getAbsolutePath());
                    }
                }
            }
        });
    }

    /** Read the full content of a file into a byte array. Returns null on error. */
    private static byte[] readFileBytes(File file) {
        if (file == null || !file.exists()) return null;
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            try {
                int offset = 0;
                int remaining = data.length;
                while (remaining > 0) {
                    int read = fis.read(data, offset, remaining);
                    if (read < 0) break;
                    offset += read;
                    remaining -= read;
                }
            } finally {
                fis.close();
            }
            return data;
        } catch (IOException e) {
            Log.e(TAG, "Error reading temp CSV file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    public boolean isLogging() { return isLogging; }
    public long getFlightStartMs() { return wallStartMs; }
}
