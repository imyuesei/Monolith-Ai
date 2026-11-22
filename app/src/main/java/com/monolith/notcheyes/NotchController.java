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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import java.util.Date;

/**
 * The floating black "dynamic notch" with eyes, built around the front
 * camera: the pill is centered on the camera hole and the eyes sit on each
 * side of it. Hosted either by the accessibility service (above the status
 * bar, so taps work) or by the foreground service as a plain overlay.
 *
 * The pill, eyes and text are all drawn by one view, and the window only
 * changes size at the start of an expand and the end of a collapse, so the
 * animation never waits on a window relayout.
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

    // geometry in screen pixels
    private float camX, camY, camHalfW, camHalfH; // camera hole (camHalfW = 0 when none)
    private boolean hasCamera;
    private float pillTop;      // top of the pill on screen
    private float cw, ch;       // collapsed pill size
    private float ew, eh;       // expanded pill size
    private float rc, re;       // eye radius collapsed / expanded
    private float gap;          // clearance between camera and eyes
    private int screenW;

    private final Runnable autoCollapse = () -> setExpanded(false, null);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if ("size".equals(key) || "offset".equals(key)) resizeWindow(bigWindow);
    };

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (view == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(a)) {
                resizeWindow(bigWindow); // rotation or display change: re-find the camera
            } else if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
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
        view = new NotchView(ctx);

        lp = new WindowManager.LayoutParams(1, 1, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
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
        f.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
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

    // --------------------------------------------------------------- geometry

    /** Finds the front camera hole at the top of the screen, if any. */
    private void findCamera() {
        hasCamera = false;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            DisplayManager dmgr = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dmgr.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null || d.getRotation() != Surface.ROTATION_0) return;
            DisplayCutout cut = d.getCutout();
            if (cut == null) return;
            Rect r = cut.getBoundingRectTop();
            if (r == null || r.isEmpty()) return;
            float cx = r.exactCenterX();
            // only a hole near the middle; corner cameras fall back to a centered pill
            if (Math.abs(cx - screenW / 2f) > screenW * 0.2f) return;
            // for a punch hole, the camera is the round bottom part of the bounding box
            float halfW = r.width() / 2f;
            float halfH;
            float cy;
            if (r.height() > r.width() * 1.3f) {
                halfH = halfW;
                cy = r.bottom - halfW;
            } else {
                halfH = r.height() / 2f;
                cy = r.exactCenterY();
            }
            camX = cx;
            camY = cy;
            camHalfW = halfW;
            camHalfH = halfH;
            hasCamera = true;
        } catch (RuntimeException ignored) {
            hasCamera = false;
        }
    }

    private void computeGeometry() {
        findCamera();
        float k = 0.8f + prefs.getInt("size", 50) / 100f * 0.45f; // ~1.0 at the middle
        float nudge = dp((prefs.getInt("offset", 50) - 50) * 0.4f); // -20dp .. +20dp
        float pad = dp(7) * k;

        if (hasCamera) {
            ch = Math.max(dp(30) * k, camHalfH * 2f + pad * 2f);
            pillTop = Math.max(0, camY - ch / 2f + nudge);
            gap = Math.max(dp(5), camHalfH * 0.35f);
        } else {
            camX = screenW / 2f;
            camHalfW = 0;
            camHalfH = 0;
            ch = dp(32) * k;
            pillTop = Math.max(0, dp(10) + nudge);
            gap = dp(5) * k;
        }
        rc = ch * 0.31f;
        float slack = rc * 0.35f;
        // half width = camera + gap + eye (with room to look around) + edge padding
        float half = camHalfW + gap + rc * 2f + slack + pad * 1.2f;
        cw = half * 2f;

        float maxW = Math.min(screenW - dp(20), 2f * Math.min(camX, screenW - camX) - dp(8));
        ew = Math.max(cw, Math.min(maxW, Math.max(cw * 2.3f, dp(300))));
        eh = Math.max(ch * 3.4f, dp(118) * k);
        re = Math.min(eh * 0.2f, ew / 9f);
    }

    private void applyWindowSize(boolean big) {
        computeGeometry();
        bigWindow = big;
        float w = big ? ew * 1.12f : cw;
        float h = big ? eh * 1.12f : ch;
        int ww = Math.round(w) + dp(6);
        lp.width = ww;
        lp.height = Math.round(pillTop + h) + dp(4);
        // window starts at the very top and is centered on the camera
        lp.x = Math.round(camX - ww / 2f);
        lp.y = 0;
    }

    private void resizeWindow(boolean big) {
        if (view == null) return;
        applyWindowSize(big);
        try {
            wm.updateViewLayout(view, lp);
        } catch (IllegalArgumentException ignored) {
        }
    }

    // -------------------------------------------------------------- animation

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
        private final Path clip = new Path();
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

        /** Camera center in this view's coordinates. */
        private float camLocalX() {
            return getWidth() / 2f;
        }

        private void computeRect() {
            float p = progress;
            float w = (cw + (ew - cw) * p) * press;
            float fullH = ch + (eh - ch) * p;
            float h = fullH * press;
            float cx = camLocalX();
            float top = pillTop + (fullH - h) / 2f;
            rect.set(cx - w / 2f, top, cx + w / 2f, top + h);
        }

        @Override
        protected void onDraw(Canvas c) {
            press += (pressTarget - press) * 0.35f;
            computeRect();
            float p = Math.max(0f, Math.min(1f, progress));
            float radius = Math.min(rect.height() / 2f, ch / 2f + (dp(36) - ch / 2f) * p);
            c.drawRoundRect(rect, radius, radius, pill);

            c.save();
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.clipPath(clip);

            // eyes: beside the camera when collapsed, bigger and lower when expanded
            float textBand = dp(30) * p;
            float R = (rc + (re - rc) * p) * press;
            float cxEyes = camLocalX();
            float expandedCy = rect.top + (rect.height() - textBand) / 2f;
            float cy = hasCamera ? camY * (1f - p) + expandedCy * p : expandedCy;

            // keep the inner edge of each eye clear of the camera
            float clear = camHalfW + gap + R;
            float slack = R * 0.35f;
            float sep = Math.max(R * 1.5f, clear) + slack;
            float halfAvail = rect.width() / 2f - R - rect.height() * 0.12f;
            float maxX = Math.max(0f, Math.min(sep - clear, halfAvail - sep));
            float maxY = Math.max(0f, Math.min(R * 0.7f,
                    (rect.height() - textBand) / 2f - R * 1.1f));
            eyes.drawAt(c, cxEyes, cy, R, sep, maxX, maxY, Color.BLACK);
            c.restore();

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
