package com.monolith.notcheyes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.util.Date;

/**
 * The floating black "dynamic notch" with eyes. Hosted either by the
 * accessibility service (above the status bar, so taps work) or by the
 * foreground service as a plain overlay.
 *
 * The pill, eyes and text are all drawn by one view, and the window only
 * changes size at the start of an expand and the end of a collapse, so the
 * animation itself never waits on a window relayout.
 */
public class NotchController {

    static final String PREFS = "notch";

    private final Context ctx;
    private final int windowType;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private NotchView view;
    private WindowManager.LayoutParams lp;
    private ValueAnimator anim;
    private boolean expanded;
    private boolean bigWindow;
    private int cw, ch, ew, eh;

    private final Runnable autoCollapse = () -> setExpanded(false, null);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if ("size".equals(key) || "offset".equals(key)) {
            loadSizes();
            resizeWindow(bigWindow);
        }
    };

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (view == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                view.eyes.surprise();
                setExpanded(true, "⚡ Charging · " + battery() + "%");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                view.eyes.setExpression(Eyes.SAD, 2500);
                setExpanded(true, "Unplugged · " + battery() + "%");
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                view.eyes.celebrate();
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

    public void show() {
        if (view != null) return;
        loadSizes();
        view = new NotchView(ctx);

        lp = new WindowManager.LayoutParams(1, 1, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applyWindowSize(false);
        wm.addView(view, lp);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(events, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(events, f);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        handler.postDelayed(() -> {
            if (view == null) return;
            view.eyes.celebrate();
            setExpanded(true, "Hello 👋");
        }, 500);
    }

    public void hide() {
        handler.removeCallbacksAndMessages(null);
        if (anim != null) anim.cancel();
        if (view == null) return;
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        try {
            ctx.unregisterReceiver(events);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            wm.removeView(view);
        } catch (IllegalArgumentException ignored) {
        }
        view = null;
        expanded = false;
    }

    private void onTap() {
        if (expanded) {
            setExpanded(false, null);
        } else {
            view.eyes.randomMood();
            setExpanded(true, null);
        }
    }

    private void onLongPress() {
        view.eyes.blink();
        view.eyes.setExpression(Eyes.SHY, 2600);
        if (!expanded) setExpanded(true, "( •_• )");
    }

    private void loadSizes() {
        int size = prefs.getInt("size", 50);
        cw = dp(90 + size * 0.8f);
        ch = Math.round(cw * 0.27f);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        ew = Math.min(screenW - dp(24), Math.round(cw * 2.6f));
        eh = Math.round(ch * 3.7f);
    }

    private void applyWindowSize(boolean big) {
        bigWindow = big;
        if (big) {
            // room for the overshoot of the expand animation
            lp.width = Math.round(ew * 1.12f) + dp(4);
            lp.height = Math.round(eh * 1.12f) + dp(4);
        } else {
            lp.width = cw + dp(4);
            lp.height = ch + dp(4);
        }
        lp.y = dp(prefs.getInt("offset", 25) * 0.4f);
    }

    private void resizeWindow(boolean big) {
        if (view == null) return;
        applyWindowSize(big);
        try {
            wm.updateViewLayout(view, lp);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void setExpanded(boolean e, String text) {
        if (view == null) return;
        expanded = e;
        handler.removeCallbacks(autoCollapse);
        if (e) {
            view.text = text != null ? text
                    : DateFormat.getTimeFormat(ctx).format(new Date()) + "  ·  " + battery() + "%";
            handler.postDelayed(autoCollapse, 4500);
            if (!bigWindow) resizeWindow(true);
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(view.progress, e ? 1f : 0f);
        if (e) {
            anim.setDuration(560);
            // ease out with a small overshoot, like a spring
            anim.setInterpolator(new PathInterpolator(0.34f, 1.36f, 0.64f, 1f));
        } else {
            anim.setDuration(420);
            anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        }
        anim.addUpdateListener(a -> {
            if (view != null) view.progress = (float) a.getAnimatedValue();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (view != null && !expanded && view.progress <= 0.001f) resizeWindow(false);
            }
        });
        anim.start();
    }

    private int battery() {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        return bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Draws the pill, eyes and text; handles taps inside the pill. */
    @SuppressLint("ViewConstructor")
    private final class NotchView extends View {
        final Eyes eyes = new Eyes();
        float progress;
        String text = "";
        private float press = 1f, pressTarget = 1f;
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int slop;
        private float downX, downY;
        private boolean tracking, longFired;
        private final Runnable longPress = () -> {
            longFired = true;
            pressTarget = 1f;
            onLongPress();
        };

        NotchView(Context c) {
            super(c);
            pill.setColor(Color.BLACK);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(13 * c.getResources().getDisplayMetrics().scaledDensity);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private void computeRect() {
            float p = progress;
            float w = cw + (ew - cw) * p;
            float h = ch + (eh - ch) * p;
            w *= press;
            h *= press;
            float cx = getWidth() / 2f;
            float top = dp(2) + (ch + (eh - ch) * p) * (1f - press) / 2f;
            rect.set(cx - w / 2f, top, cx + w / 2f, top + h);
        }

        @Override
        protected void onDraw(Canvas c) {
            press += (pressTarget - press) * 0.35f;
            computeRect();
            float p = Math.max(0f, Math.min(1f, progress));
            float radius = Math.min(rect.height() / 2f, ch / 2f + (dp(38) - ch / 2f) * p);
            c.drawRoundRect(rect, radius, radius, pill);

            float textBand = dp(30) * p;
            float padX = rect.height() * 0.18f;
            eyes.draw(c, rect.left + padX, rect.top, rect.right - padX,
                    rect.bottom - textBand, Color.BLACK);

            float ta = Math.max(0f, Math.min(1f, (progress - 0.6f) / 0.4f));
            if (ta > 0f && text != null) {
                label.setAlpha(Math.round(255 * ta));
                c.drawText(text, rect.centerX(), rect.bottom - dp(16), label);
            }
            postInvalidateOnAnimation();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    computeRect();
                    if (!rect.contains(e.getX(), e.getY())) return false;
                    tracking = true;
                    longFired = false;
                    downX = e.getX();
                    downY = e.getY();
                    pressTarget = 0.95f;
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (tracking && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                        tracking = false;
                        pressTarget = 1f;
                        handler.removeCallbacks(longPress);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    pressTarget = 1f;
                    if (tracking && !longFired) onTap();
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    pressTarget = 1f;
                    tracking = false;
                    return true;
            }
            return false;
        }
    }
}
