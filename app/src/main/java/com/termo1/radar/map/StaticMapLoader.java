package com.termo1.radar.map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StaticMapLoader — загрузка тайлов CartoDB под компас.
 *
 * - Загружает 3×3 = 9 тайлов zoom 14, склеивает в composite 768×768 px
 * - Кэш: LruCache (32 MB) + диск (LRU евикция, макс 50 МБ)
 * - Обновление: при пересечении границы центрального тайла
 * - Threading: ExecutorService вместо депрекейтнутого AsyncTask
 */
public class StaticMapLoader {

    private static final String TAG = "TERMO1_MAP";
    private static final String CACHE_SUBDIR = "map_cache";
    private static final int MAP_W = 768;
    private static final int MAP_H = 768;
    private static final int TILE_GRID = 3;       // 3×3 тайлов
    private static final int TILE_SIZE = 256;      // px
    private static final int DEFAULT_ZOOM = 14;
    private static final int MEM_CACHE_KB = 32 * 1024;       // 32 MB
    private static final long DISK_CACHE_MAX_BYTES = 50 * 1024 * 1024L; // 50 MB disk limit

    // Esri World Topo — контрастная топографическая карта (как в навигаторе)
    // URL формат: /tile/{z}/{y}/{x} (y и x перевёрнуты!)
    private static final String OSM_TILE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/%d/%d/%d";

    // Threading: ExecutorService вместо AsyncTask
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    // Текущий центр карты (координаты ЦЕНТРА тайла, не пилота)
    private double cachedCenterLat;
    private double cachedCenterLon;
    private int cachedZoom = DEFAULT_ZOOM;
    private int cachedTileX;
    private int cachedTileY;
    private boolean hasCachedCenter;

    // Флаг загрузки
    private final AtomicBoolean loading = new AtomicBoolean(false);

    // ========================================================================
    // Init
    // ========================================================================

    public StaticMapLoader(String appCacheDir) {
        this.cacheDir = new File(appCacheDir, CACHE_SUBDIR);
        if (!this.cacheDir.exists()) this.cacheDir.mkdirs();

        this.memCache = new LruCache<String, Bitmap>(MEM_CACHE_KB) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                         Bitmap oldValue, Bitmap newValue) {
                // Не recycle — bitmap может ещё использоваться в RadarRenderer.
                // GC заберёт, когда все ссылки уйдут.
            }
        };

        Log.i(TAG, "StaticMapLoader initialized, cache: " + this.cacheDir.getPath());
    }

    public void setCallback(MapCallback cb) {
        this.callback = cb;
    }

    /** Освободить ресурсы */
    public void destroy() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "executor shutdown interrupted");
        }
    }

    // ========================================================================
    // Запрос карты
    // ========================================================================

    public void updateIfNeeded(double lat, double lon, int zoom) {
        int tx = lonToTileX(lon, zoom);
        int ty = latToTileY(lat, zoom);
        if (hasCachedCenter && tx == cachedTileX && ty == cachedTileY && zoom == cachedZoom) {
            return;
        }
        cachedTileX = tx;
        cachedTileY = ty;
        cachedZoom = zoom;
        cachedCenterLat = tileYToLat(ty + 0.5, zoom);
        cachedCenterLon = tileXToLon(tx + 0.5, zoom);
        hasCachedCenter = true;
        loadMap(lat, lon, zoom);
    }

    public void forceUpdate(double lat, double lon, int zoom) {
        int tx = lonToTileX(lon, zoom);
        int ty = latToTileY(lat, zoom);
        cachedTileX = tx;
        cachedTileY = ty;
        cachedZoom = zoom;
        cachedCenterLat = tileYToLat(ty + 0.5, zoom);
        cachedCenterLon = tileXToLon(tx + 0.5, zoom);
        hasCachedCenter = true;
        loadMap(lat, lon, zoom);
    }

    // ========================================================================
    // Внутренняя загрузка — 9 тайлов, склейка в composite 768×768
    // ========================================================================

    private void loadMap(double lat, double lon, int zoom) {
        final String cacheKey = makeTileCacheKey(lat, lon, zoom);

        // 1. In-memory кэш
        Bitmap cached = memCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            Log.d(TAG, "Memory cache hit: " + cacheKey);
            if (callback != null) callback.onMapLoaded(cached, cachedCenterLat, cachedCenterLon, zoom);
            return;
        }

        // 2. Дисковый кэш
        File cacheFile = new File(cacheDir, cacheKey + ".png");
        if (cacheFile.exists()) {
            try {
                Bitmap diskBitmap = BitmapFactory.decodeStream(new FileInputStream(cacheFile));
                if (diskBitmap != null) {
                    memCache.put(cacheKey, diskBitmap);
                    Log.d(TAG, "Disk cache hit: " + cacheKey);
                    if (callback != null) callback.onMapLoaded(diskBitmap, cachedCenterLat, cachedCenterLon, zoom);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read disk cache: " + cacheKey, e);
            }
        }

        // 3. Нет в кэше → HTTP через ExecutorService
        if (!loading.compareAndSet(false, true)) {
            Log.d(TAG, "Already loading, skip: " + cacheKey);
            return;
        }

        int centerTx = lonToTileX(lon, zoom);
        int centerTy = latToTileY(lat, zoom);
        final String url = String.format(Locale.US, OSM_TILE_URL, zoom, centerTy, centerTx);
        Log.i(TAG, "Downloading 3x3 tiles centered on: " + url);

        final int fZoom = zoom;
        final int fTx = centerTx;
        final int fTy = centerTy;
        final File fCacheFile = cacheFile;

        executor.submit(() -> {
            Bitmap composite = composeTiles(fTx, fTy, fZoom);
            if (composite != null) {
                // Сохраняем composite на диск
                try {
                    FileOutputStream fos = new FileOutputStream(fCacheFile);
                    composite.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    enforceDiskCacheLimit();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to save cache file", e);
                }
            }
            loading.set(false);
            final Bitmap result = composite;
            mainHandler.post(() -> {
                if (result != null && !result.isRecycled()) {
                    memCache.put(cacheKey, result);
                    if (callback != null) {
                        callback.onMapLoaded(result, cachedCenterLat, cachedCenterLon, fZoom);
                    }
                }
            });
        });
    }

    /** Загрузить 3×3 тайла и склеить в один Bitmap 768×768 */
    private Bitmap composeTiles(int centerTx, int centerTy, int zoom) {
        Bitmap composite = Bitmap.createBitmap(
                TILE_GRID * TILE_SIZE, TILE_GRID * TILE_SIZE, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(composite);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        boolean anyLoaded = false;

        for (int dx = 0; dx < TILE_GRID; dx++) {
            for (int dy = 0; dy < TILE_GRID; dy++) {
                int tx = centerTx + dx - 1;
                int ty = centerTy + dy - 1;
                Bitmap tile = downloadSingleTile(tx, ty, zoom);
                if (tile != null) {
                    c.drawBitmap(tile, dx * TILE_SIZE, dy * TILE_SIZE, p);
                    tile.recycle(); // безопасно — пиксели скопированы в composite
                    anyLoaded = true;
                }
            }
        }
        return anyLoaded ? composite : null;
    }

    // ========================================================================
    // HTTP загрузка одного тайла 256×256
    // ========================================================================

    private Bitmap downloadSingleTile(int tileX, int tileY, int zoom) {
        String urlStr = String.format(Locale.US, OSM_TILE_URL, zoom, tileY, tileX);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent",
                    "ThermalRadar/0.2.11 (+https://github.com/isemaster/ThermalRadar)");
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "HTTP error: " + conn.getResponseCode() + " for " + urlStr);
                return null;
            }

            InputStream is = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Tile download failed: " + urlStr, e);
            return null;
        }
    }

    /** LRU евикция дискового кеша: удаляем самые старые файлы пока не уложимся в лимит */
    private void enforceDiskCacheLimit() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long total = 0;
        for (File f : files) {
            if (f.isFile()) total += f.length();
        }

        if (total <= DISK_CACHE_MAX_BYTES) return;

        // Сортируем по lastModified (самые старые первыми)
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        for (File f : files) {
            if (total <= DISK_CACHE_MAX_BYTES) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                Log.d(TAG, "Evicted disk cache: " + f.getName());
            }
        }
    }

    // ========================================================================
    // Утилиты
    // ========================================================================

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

    // ========================================================================
    // Преобразование lat/lon ↔ tile x/y
    // ========================================================================

    /** Долгота → tile X */
    private static int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    /** Широта → tile Y */
    private static int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 << zoom));
    }

    /** Tile Y → широта центра тайла */
    private static double tileYToLat(double ty, int zoom) {
        double n = Math.PI - 2.0 * Math.PI * ty / (1 << zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Tile X → долгота центра тайла */
    private static double tileXToLon(double tx, int zoom) {
        return tx / (1 << zoom) * 360.0 - 180.0;
    }

    /** Формирует кэш-ключ из tile координат (вместо округлённых lat/lon) */
    private static String makeTileCacheKey(double lat, double lon, int zoom) {
        int tx = lonToTileX(lon, zoom);
        int ty = latToTileY(lat, zoom);
        return tx + "_" + ty + "_z" + zoom;
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
