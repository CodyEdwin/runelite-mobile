package com.codyedwin.runelitemobile.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import androidx.core.app.NotificationCompat;

import com.codyedwin.runelitemobile.JavaGUILauncherActivity;
import com.codyedwin.runelitemobile.R;
import com.codyedwin.runelitemobile.Tools;

/**
 * Foreground service living in the RuneLite (:gui_installer) process.
 *
 * Without it the RuneLite JVM is just a cached background process as soon as another app is in
 * front. The Jagex login runs in Chrome, and while it was in front Android's low-memory killer
 * reaped :gui_installer ("has died: cch"); the jagex: callback then recreated
 * JavaGUILauncherActivity, which started a brand-new, logged-out RuneLite while the login result
 * was sent to a socket nobody listened on any more.
 */
public class RuneLiteService extends Service {
    private static final int NOTIFICATION_ID = 0x52_4C; // "RL"

    // Keep the CPU and Wi-Fi up with the screen off so the game keeps ticking and stays connected.
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("kill", false)) {
            releaseLocks();
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent kill = PendingIntent.getService(this, 0x524C,
                new Intent(this, RuneLiteService.class).putExtra("kill", true), immutable);
        PendingIntent content = PendingIntent.getActivity(this, 0x524C,
                new Intent(this, JavaGUILauncherActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), immutable);
        Notification notification = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle("RuneLite")
                .setContentText("RuneLite is running in the background")
                .setContentIntent(content)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_terminate), kill)
                .setSmallIcon(R.drawable.notif_icon)
                .setOngoing(true)
                .setSilent(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        acquireLocks();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RuneLite:game");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RuneLite:game");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            android.util.Log.w("RuneLiteService", "Unable to acquire wake/wifi lock", e);
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        releaseLocks();
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
