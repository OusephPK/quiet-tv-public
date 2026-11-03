package com.quiet.listen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the music going while you're on the home screen or in another app. */
public class Playback extends Service {

    static void show(Context c, String title, String artist) {
        c.startForegroundService(new Intent(c, Playback.class).putExtra("t", title).putExtra("a", artist));
    }

    static void hide(Context c) {
        c.stopService(new Intent(c, Playback.class));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("music", "Listen", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "music")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(i == null ? "Listen" : i.getStringExtra("t"))
                .setContentText(i == null ? "" : i.getStringExtra("a"))
                .setOngoing(true)
                .setContentIntent(Main.nowPlayingIntent(this))
                .build();
        startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
