package com.termo1.radar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal ContentProvider to serve log files for Intent.ACTION_SEND.
 * Authority: com.termo1.radar.fileprovider
 *
 * No AndroidX dependency needed — pure SDK.
 */
public class Termo1FileProvider extends ContentProvider {

    private static final String TAG = "Termo1FileProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // URI format: content://<authority>/logs/filename.csv
        String path = uri.getPath(); // e.g. "/logs/flight_20260523_114514.csv"
        java.io.File extDir = getContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "External storage not available");
            throw new FileNotFoundException("External storage not available");
        }
        // Path traversal protection: нормализуем и проверяем, что файл внутри extDir
        File file = new File(extDir, path);
        try {
            String canonicalPath = file.getCanonicalPath();
            String canonicalExtDir = extDir.getCanonicalPath();
            if (!canonicalPath.startsWith(canonicalExtDir + File.separator)
                    && !canonicalPath.equals(canonicalExtDir)) {
                Log.e(TAG, "Path traversal blocked: " + canonicalPath);
                throw new FileNotFoundException("Invalid path");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to resolve canonical path", e);
            throw new FileNotFoundException("Invalid path");
        }
        if (!file.exists()) {
            Log.e(TAG, "File not found: " + file.getAbsolutePath());
            throw new FileNotFoundException("File not found: " + path);
        }
        Log.d(TAG, "Opening file: " + file.getAbsolutePath());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "text/csv";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
