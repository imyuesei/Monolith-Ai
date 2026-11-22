package com.monolith.notcheyes;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The floating black "dynamic notch" with eyes. Hosted either by the
 * accessibility service (sits above the status bar, so taps work) or by the
 * foreground service as a plain overlay.
 */
public class NotchController {

    static final String PREFS = "notch";

    private final Context ctx;
    private final int windowType;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout pill;
    private EyesView eyes;
    private TextView info;
    private GradientDrawable bg;
    private WindowManager.LayoutParams lp;
    private ValueAnimator anim;
    private float progress;
    private boolean expanded;
    private int cw, ch, ew, eh;
    private final float density;

    private final Runnable autoCollapse = () -> setExpanded(false, null);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if ("size".equals(key) || "offset".equals(key)) {
            loadSizes();
            applyProgress(progress);
        }
    };

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (pill == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                eyes.surprise();
                setExpanded(true, "⚡ Charging · " + battery() + "%");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                eyes.setExpression(EyesView.SAD, 2500);
                setExpanded(true, "Unplugged · " + battery() + "%");
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                eyes.celebrate();
                setExpanded(true, null);
            }
        }
    };

    public NotchController(Context ctx, int windowType) {
        this.ctx = ctx;
        this.windowType = windowType;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    public boolean isShowing() {
        return pill != null;
    }

    public void show() {
        if (pill != null) return;
        loadSizes();

        bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(ch / 2f);

        pill = new FrameLayout(ctx);
        pill.setBackground(bg);
        pill.setClipToOutline(true);

        eyes = new EyesView(ctx, false);
        pill.addView(eyes, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        info = new TextView(ctx);
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

        // our own tap / long-press detection, so nothing in the view tree can swallow it
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        final Runnable longPress = () -> {
            eyes.setExpression(EyesView.SHY, 2500);
            eyes.blinkNow();
            pill.setTag("long");
        };
        pill.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pill.setTag(new float[]{e.getRawX(), e.getRawY()});
                    pill.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    Object t = pill.getTag();
                    if (t instanceof float[]) {
                        float[] d = (float[]) t;
                        if (Math.abs(e.getRawX() - d[0]) > slop || Math.abs(e.getRawY() - d[1]) > slop) {
                            handler.removeCallbacks(longPress);
                            pill.setTag(null);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    if (pill.getTag() instanceof float[]) onTap();
                    pill.setTag(null);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    pill.setTag(null);
                    return true;
            }
            return false;
        });

        lp = new WindowManager.LayoutParams(cw, ch, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = topOffset();
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
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(events, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(events, f);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        handler.postDelayed(() -> {
            if (eyes != null) eyes.celebrate();
            setExpanded(true, "Hello 👋");
        }, 400);
    }

    public void hide() {
        handler.removeCallbacksAndMessages(null);
        if (anim != null) anim.cancel();
        if (pill == null) return;
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        try {
            ctx.unregisterReceiver(events);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            wm.removeView(pill);
        } catch (IllegalArgumentException ignored) {
        }
        pill = null;
        progress = 0;
        expanded = false;
    }

    private void onTap() {
        if (expanded) {
            setExpanded(false, null);
        } else {
            int[] moods = {EyesView.HAPPY, EyesView.PLEASED, EyesView.HAPPY};
            eyes.setExpression(moods[(int) (Math.random() * moods.length)], 2500);
            setExpanded(true, null);
        }
    }

    private void loadSizes() {
        int size = prefs.getInt("size", 50);
        cw = dp(90 + size * 0.8f);
        ch = Math.round(cw * 0.27f);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        ew = Math.min(screenW - dp(24), Math.round(cw * 2.6f));
        eh = Math.round(ch * 3.7f);
        if (lp != null) lp.y = topOffset();
    }

    private int topOffset() {
        return dp(prefs.getInt("offset", 25) * 0.4f);
    }

    private void setExpanded(boolean e, String text) {
        if (pill == null) return;
        expanded = e;
        handler.removeCallbacks(autoCollapse);
        if (e) {
            info.setText(text != null ? text
                    : DateFormat.getTimeFormat(ctx).format(new Date()) + "  ·  " + battery() + "%");
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
        try {
            wm.updateViewLayout(pill, lp);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private int battery() {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        return bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
