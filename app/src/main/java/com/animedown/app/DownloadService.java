package com.animedown.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class DownloadService extends Service {
    static final String CH = "downloads";
    final Runnable listener = new Runnable() {
        public void run() { update(); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
        Dl.listeners.remove(listener);
        Dl.listeners.add(listener);
        update();
        return START_NOT_STICKY;
    }

    void update() {
        if (Dl.active() == 0) {
            Dl.listeners.remove(listener);
            stopForeground(true);
            stopSelf();
            return;
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, build());
    }

    Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        int act = Dl.active();
        b.setSmallIcon(android.R.drawable.stat_sys_download)
         .setContentTitle("Anime-Down")
         .setContentText(act + (act == 1 ? " download em andamento" : " downloads em andamento"))
         .setOngoing(true);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, DownloadsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentIntent(pi);
        Item r = Dl.firstRunning();
        if (r != null && r.total > 0) {
            b.setProgress(100, (int) (r.done * 100 / r.total), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    @Override public void onDestroy() {
        Dl.listeners.remove(listener);
        super.onDestroy();
    }
}
