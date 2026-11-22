package com.monolith.notcheyes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Fallback host for the notch when the accessibility service is off.
 * A plain overlay sits under the status bar, so on most phones taps at the
 * very top of the screen go to the status bar instead of the notch.
 */
public class NotchService extends Service {

    public static final String ACTION_STOP = "com.monolith.notcheyes.STOP";
    static final String PREFS = NotchController.PREFS;
    private static final String CHANNEL = "notch";
    private static final int NOTIF_ID = 1;

    private NotchController notch;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (NotchAccessibilityService.isRunning() || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (notch == null) notch = new NotchController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        notch.show();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Notch", NotificationManager.IMPORTANCE_MIN));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, NotchService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Notch Eyes is watching")
                .setContentText("Turn on Notch Eyes in Accessibility so taps work")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notif), "Stop", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        if (notch != null) notch.hide();
        super.onDestroy();
    }
}
