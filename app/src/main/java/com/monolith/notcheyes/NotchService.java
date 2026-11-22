package com.monolith.notcheyes;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Date;

/** Floating black "dynamic notch" with animated eyes, drawn over the home screen. */
public class NotchService extends Service {

    public static final String ACTION_STOP = "com.monolith.notcheyes.STOP";
    static final String PREFS = "notch";
    private static final String CHANNEL = "notch";
    private static final int NOTIF_ID = 1;

    private WindowManager wm;
    private FrameLayout pill;
    private EyesView eyes;
    private TextView info;
    private GradientDrawable bg;
    private WindowManager.LayoutParams lp;
    private ValueAnimator anim;
    private float progress; // 0 = collapsed, 1 = expanded
    private boolean expanded;
    private int cw, ch, ew, eh;
    private float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoCollapse = () -> setExpanded(false, null);

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (pill == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                eyes.surprise();
                setExpanded(true, "⚡ Charging · " + battery() + "%");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                setExpanded(true, "Unplugged · " + battery() + "%");
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                eyes.celebrate();
                setExpanded(true, null);
            }
        }
    };

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
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        loadSizes();
        if (pill == null) addOverlay();
        else applyProgress(progress);
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
                .setContentText("Tap the notch to expand it")
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

    private void loadSizes() {
        density = getResources().getDisplayMetrics().density;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int size = p.getInt("size", 50);      // 0..100
        int offset = p.getInt("offset", 25);  // 0..100
        cw = dp(90 + size * 0.8f);
        ch = Math.round(cw * 0.27f);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        ew = Math.min(screenW - dp(24), Math.round(cw * 2.6f));
        eh = Math.round(ch * 3.7f);
        if (lp != null) lp.y = dp(offset * 0.4f);
    }

    private void addOverlay() {
        wm = getSystemService(WindowManager.class);

        bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(ch / 2f);

        pill = new FrameLayout(this);
        pill.setBackground(bg);
        pill.setClipToOutline(true);

        eyes = new EyesView(this, false);
        pill.addView(eyes, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        info.setGravity(Gravity.CENTER);
        info.setSingleLine(true);
        info.setAlpha(0f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ilp.bottomMargin = dp(14);
        pill.addView(info, ilp);

        pill.setOnClickListener(v -> setExpanded(!expanded, null));
        pill.setOnLongClickListener(v -> {
            eyes.celebrate();
            return true;
        });

        lp = new WindowManager.LayoutParams(cw, ch,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("offset", 25) * 0.4f);
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(pill, lp);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(events, f);

        // say hello
        handler.postDelayed(() -> setExpanded(true, "Hello 👋"), 400);
    }

    private void setExpanded(boolean e, String text) {
        if (pill == null) return;
        expanded = e;
        handler.removeCallbacks(autoCollapse);
        if (e) {
            info.setText(text != null ? text
                    : DateFormat.getTimeFormat(this).format(new Date()) + "  ·  " + battery() + "%");
            handler.postDelayed(autoCollapse, 4500);
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, e ? 1f : 0f);
        anim.setDuration(e ? 520 : 380);
        anim.setInterpolator(e ? new OvershootInterpolator(1.1f) : new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue()));
        anim.start();
    }

    private void applyProgress(float p) {
        progress = p;
        if (pill == null) return;
        float cp = Math.max(0f, Math.min(1f, p));
        int w = Math.round(cw + (ew - cw) * p);
        int h = Math.round(ch + (eh - ch) * p);
        lp.width = Math.max(1, w);
        lp.height = Math.max(1, h);
        bg.setCornerRadius(Math.min(h / 2f, ch / 2f + (dp(38) - ch / 2f) * cp));
        info.setAlpha(Math.max(0f, Math.min(1f, (p - 0.55f) / 0.45f)));
        FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) eyes.getLayoutParams();
        int bottom = Math.round(dp(26) * cp);
        if (elp.bottomMargin != bottom) {
            elp.bottomMargin = bottom;
            eyes.setLayoutParams(elp);
        }
        wm.updateViewLayout(pill, lp);
    }

    private int battery() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        return bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (anim != null) anim.cancel();
        if (pill != null) {
            try {
                unregisterReceiver(events);
            } catch (IllegalArgumentException ignored) {
            }
            wm.removeView(pill);
            pill = null;
        }
        super.onDestroy();
    }
}
