package com.termo1.radar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * ThermalRadarService — foreground service с WakeLock и уведомлением.
 * Обеспечивает работу сенсоров/GPS/логов/TTS при выключенном экране.
 *
 * MainActivity живёт как обычно, но запускает этот сервис,
 * который не даёт системе убить процесс и держит WakeLock.
 * При выключении экрана onPause не останавливает сенсоры (если идёт запись),
 * а сервис держит CPU в активном состоянии через PARTIAL_WAKE_LOCK.
 */
public class ThermalRadarService extends Service {

    private static final String TAG = "TERMO1_SVC";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "termo1_radar_channel";

    private PowerManager.WakeLock wakeLock;

    // Singleton reference для доступа из MainActivity
    private static ThermalRadarService instance;

    public static ThermalRadarService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "Service onCreate");

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("ThermalRadar работает"));

        // Исправлено SVC-2: WakeLock с таймаутом 6 часов
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TERMO1:RadarSvc");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
            Log.i(TAG, "WakeLock acquired (foreground service, 6h timeout)");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // не поддерживаем binding — используем статический доступ
    }

    // ========================================================================
    // Notification — исправлено SVC-1: кешированный Builder, не аллоцировать каждый кадр
    // ========================================================================

    private String lastNotifText = "";
    private NotificationCompat.Builder notifBuilder;

    public void updateNotification(boolean isLogging, float vario) {
        String text;
        if (isLogging) {
            text = String.format(Locale.US, "Запись | варьо %+.1f м/с", vario);
        } else {
            text = "ThermalRadar работает";
        }
        // Не обновляем если текст не изменился
        if (text.equals(lastNotifText)) return;
        lastNotifText = text;
        
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            if (notifBuilder == null) {
                notifBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("ThermalRadar")
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW);
            }
            notifBuilder.setContentText(text);
            nm.notify(NOTIF_ID, notifBuilder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ThermalRadar", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Уведомление о работе ThermalRadar в фоне");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String content) {
        // buildNotification остаётся для начального startForeground
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ThermalRadar")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
