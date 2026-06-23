package com.termo1.radar.map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * StaticMapLoader — загрузка статической OSM-карты под компас.
 *
 * - HTTP-запрос к OpenStreetMap staticmap
 * - Кэш: LruCache (32 MB) + диск (cache-папка, TTL бесконечный)
 * - Обновление: при смещении > 500 м
 * - Формат: PNG 400×400, zoom 13-15
 *
 * URL: https://staticmap.openstreetmap.de/staticmap.php?center=lat,lon&zoom=Z&size=WxH&maptype=mapnik
 */
public class StaticMapLoader {

    private static final String TAG = "TERMO1_MAP";
    private static final String CACHE_SUBDIR = "map_cache";
    private static final int MAP_W = 768;
    private static final int MAP_H = 768;
    private static final int DEFAULT_ZOOM = 14;
    private static final double UPDATE_THRESHOLD_M = 500.0;  // м — минимальное смещение для обновления
    private static final int MEM_CACHE_KB = 32 * 1024;       // 32 MB

    // OpenStreetMap staticmap сервис (бесплатно, без ключа)
    private static final String OSM_URL =
            "https://staticmap.openstreetmap.de/staticmap.php?center=%.4f,%.4f&zoom=%d&size=%dx%d&maptype=mapnik";

    // ========================================================================
    // Callback
    // ========================================================================

    public interface MapCallback {
        void onMapLoaded(Bitmap bitmap, double centerLat, double centerLon, int zoom);
    }

    // ========================================================================
    // Состояние
    // ========================================================================

    private final File cacheDir;
    private final LruCache<String, Bitmap> memCache;
    private MapCallback callback;

    // Текущий центр карты (чтобы не обновлять, если не уехали)
    private double cachedCenterLat;
    private double cachedCenterLon;
    private int cachedZoom = DEFAULT_ZOOM;
    private boolean hasCachedCenter;

    // Флаг: идёт загрузка сейчас?
    private volatile boolean loading;

    // ========================================================================
    // Init
    // ========================================================================

    /**
     * @param appCacheDir context.getCacheDir().getAbsolutePath()
     */
    public StaticMapLoader(String appCacheDir) {
        this.cacheDir = new File(appCacheDir, CACHE_SUBDIR);
        if (!this.cacheDir.exists()) this.cacheDir.mkdirs();
        this.memCache = new LruCache<String, Bitmap>(MEM_CACHE_KB) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        Log.i(TAG, "StaticMapLoader initialized, cache: " + this.cacheDir.getPath());
    }

    public void setCallback(MapCallback cb) {
        this.callback = cb;
    }

    // ========================================================================
    // Запрос карты
    // ========================================================================

    /**
     * Обновить карту, если пилот сместился > UPDATE_THRESHOLD_M.
     * Вызывать при каждом GPS-фиксе (1 Гц).
     *
     * @param lat  широта пилота
     * @param lon  долгота пилота
     * @param zoom zoom (14 = города/дороги, 15 = детально)
     */
    public void updateIfNeeded(double lat, double lon, int zoom) {
        if (hasCachedCenter) {
            double dist = haversineM(cachedCenterLat, cachedCenterLon, lat, lon);
            if (dist < UPDATE_THRESHOLD_M && zoom == cachedZoom) {
                return; // не уехали далеко — не грузим
            }
        }

        // Округляем до 3 знаков (~100м точности), чтобы соседние позиции
        // попадали в один кэш-ключ
        double roundedLat = Math.round(lat * 100.0) / 100.0;
        double roundedLon = Math.round(lon * 100.0) / 100.0;

        cachedCenterLat = roundedLat;
        cachedCenterLon = roundedLon;
        cachedZoom = zoom;
        hasCachedCenter = true;

        loadMap(roundedLat, roundedLon, zoom);
    }

    /**
     * Принудительно загрузить карту (без проверки расстояния).
     */
    public void forceUpdate(double lat, double lon, int zoom) {
        double roundedLat = Math.round(lat * 100.0) / 100.0;
        double roundedLon = Math.round(lon * 100.0) / 100.0;
        cachedCenterLat = roundedLat;
        cachedCenterLon = roundedLon;
        cachedZoom = zoom;
        hasCachedCenter = true;
        loadMap(roundedLat, roundedLon, zoom);
    }

    // ========================================================================
    // Внутренняя загрузка
    // ========================================================================

    private void loadMap(double lat, double lon, int zoom) {
        String cacheKey = makeCacheKey(lat, lon, zoom);

        // 1. Проверка in-memory кэша
        Bitmap cached = memCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            Log.d(TAG, "Memory cache hit: " + cacheKey);
            if (callback != null) callback.onMapLoaded(cached, lat, lon, zoom);
            return;
        }

        // 2. Проверка дискового кэша
        File cacheFile = new File(cacheDir, cacheKey + ".png");
        if (cacheFile.exists()) {
            try {
                Bitmap diskBitmap = BitmapFactory.decodeStream(new FileInputStream(cacheFile));
                if (diskBitmap != null) {
                    memCache.put(cacheKey, diskBitmap);
                    Log.d(TAG, "Disk cache hit: " + cacheKey);
                    if (callback != null) callback.onMapLoaded(diskBitmap, lat, lon, zoom);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read disk cache: " + cacheKey, e);
            }
        }

        // 3. Нет в кэше → грузим HTTP
        if (loading) {
            Log.d(TAG, "Already loading, skip: " + cacheKey);
            return;
        }

        String url = String.format(Locale.US, OSM_URL, lat, lon, zoom, MAP_W, MAP_H);
        Log.i(TAG, "Downloading map: " + url);

        new DownloadTask(cacheKey, cacheFile).execute(url);
    }

    // ========================================================================
    // Асинхронная загрузка
    // ========================================================================

    private class DownloadTask extends AsyncTask<String, Void, Bitmap> {
        private final String cacheKey;
        private final File cacheFile;

        DownloadTask(String key, File file) {
            this.cacheKey = key;
            this.cacheFile = file;
        }

        @Override
        protected void onPreExecute() {
            loading = true;
        }

        @Override
        protected Bitmap doInBackground(String... urls) {
            try {
                URL url = new URL(urls[0]);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.connect();

                if (conn.getResponseCode() != 200) {
                    Log.e(TAG, "HTTP error: " + conn.getResponseCode() + " for " + urls[0]);
                    return null;
                }

                InputStream is = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();

                if (bitmap != null) {
                    // Сохраняем на диск
                    try {
                        FileOutputStream fos = new FileOutputStream(cacheFile);
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                        fos.close();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save cache file", e);
                    }
                }

                return bitmap;

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + urls[0], e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            loading = false;
            if (bitmap != null) {
                memCache.put(cacheKey, bitmap);
                if (callback != null) {
                    callback.onMapLoaded(bitmap, cachedCenterLat, cachedCenterLon, cachedZoom);
                }
            }
        }
    }

    // ========================================================================
    // Утилиты
    // ========================================================================

    private static String makeCacheKey(double lat, double lon, int zoom) {
        return String.format(Locale.US, "%.2f_%.2f_z%d", lat, lon, zoom);
    }

    /** Haversine distance in meters */
    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Принудительно очистить кэш */
    public void clearCache() {
        memCache.evictAll();
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }
}
