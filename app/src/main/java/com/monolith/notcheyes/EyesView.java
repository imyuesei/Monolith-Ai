package com.monolith.notcheyes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Two glowing ring eyes on black that look around, blink, and smile.
 * Used full-screen in the app and tiny inside the floating notch.
 */
public class EyesView extends View {

    private static final int CORE = 0xFFFFFFFF;
    private static final int GLOW = 0xFFBFE6FF;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Random rnd = new Random();

    private final boolean interactive;

    // gaze, -1..1
    private float lookX, lookY, targetX, targetY;
    // eye openness, 0..1
    private float open = 1f;
    private long blinkStart = -1;
    private int blinksLeft;
    // smile amount, 0..1
    private float happy;
    private long happyUntil;
    // wide "surprised" amount, 0..1
    private float wide;
    private long wideUntil;

    private long nextSaccade, nextBlink, lastFrame, downTime;
    private boolean following;

    public EyesView(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeCap(Paint.Cap.ROUND);
        core.setColor(CORE);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setColor(GLOW);
        long now = SystemClock.uptimeMillis();
        nextSaccade = now + 600;
        nextBlink = now + 1500;
    }

    /** Smile for a moment (eyes become ^ ^). */
    public void celebrate() {
        happyUntil = SystemClock.uptimeMillis() + 1600;
        invalidate();
    }

    /** Pop the eyes open wide for a moment. */
    public void surprise() {
        wideUntil = SystemClock.uptimeMillis() + 900;
        targetX = 0;
        targetY = 0;
        nextSaccade = SystemClock.uptimeMillis() + 1200;
        invalidate();
    }

    /** Glance in a direction for a moment. */
    public void glance(float x, float y) {
        targetX = clamp(x, -1, 1);
        targetY = clamp(y, -1, 1);
        nextSaccade = SystemClock.uptimeMillis() + 1400;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        float w = getWidth(), h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = SystemClock.uptimeMillis();
                // fall through
            case MotionEvent.ACTION_MOVE:
                following = true;
                targetX = clamp((e.getX() - w / 2f) / (w / 2f), -1, 1);
                targetY = clamp((e.getY() - h / 2f) / (h / 2f), -1, 1);
                return true;
            case MotionEvent.ACTION_UP:
                following = false;
                if (SystemClock.uptimeMillis() - downTime < 250) celebrate();
                nextSaccade = SystemClock.uptimeMillis() + 1500;
                return true;
            case MotionEvent.ACTION_CANCEL:
                following = false;
                return true;
        }
        return false;
    }

    private void update(long now, float dt) {
        // random saccades
        if (!following && now >= nextSaccade) {
            float roll = rnd.nextFloat();
            if (roll < 0.3f) {
                targetX = 0;
                targetY = 0;
            } else {
                targetX = rnd.nextFloat() * 2f - 1f;
                targetY = (rnd.nextFloat() * 2f - 1f) * 0.7f;
            }
            if (roll > 0.93f) happyUntil = now + 1300;
            else if (roll > 0.88f) wideUntil = now + 700;
            nextSaccade = now + 700 + rnd.nextInt(2600);
        }
        float k = 1f - (float) Math.exp(-dt * 14f);
        lookX += (targetX - lookX) * k;
        lookY += (targetY - lookY) * k;

        float hk = 1f - (float) Math.exp(-dt * 10f);
        happy += ((now < happyUntil ? 1f : 0f) - happy) * hk;
        wide += ((now < wideUntil ? 1f : 0f) - wide) * hk;

        // blinking (single or double)
        if (blinkStart < 0 && now >= nextBlink && happy < 0.1f) {
            blinkStart = now;
            blinksLeft = rnd.nextFloat() < 0.22f ? 2 : 1;
        }
        open = 1f;
        if (blinkStart >= 0 && now >= blinkStart) {
            float t = (now - blinkStart) / 200f;
            if (t < 0.4f) open = 1f - t / 0.4f;
            else open = Math.min(1f, (t - 0.4f) / 0.6f);
            if (t >= 1f) {
                open = 1f;
                blinksLeft--;
                if (blinksLeft > 0) {
                    blinkStart = now + 70;
                } else {
                    blinkStart = -1;
                    nextBlink = now + 1800 + rnd.nextInt(4200);
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        update(now, dt);

        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float breathe = 1f + 0.025f * (float) Math.sin(now / 650.0);
        float r = Math.min(h * 0.28f, w * 0.115f) * breathe * (1f + 0.18f * wide);
        float stroke = r * 0.30f;
        float sep = r * 1.75f;
        float maxX = Math.max(0, Math.min(r * 1.3f, w / 2f - sep - r - stroke * 2));
        float maxY = Math.max(0, Math.min(r * 0.8f, h / 2f - r - stroke * 2));

        float cx = w / 2f + lookX * maxX;
        float cy = h / 2f + lookY * maxY - happy * r * 0.25f;

        for (int side = -1; side <= 1; side += 2) {
            // the eye on the side we are looking toward turns away and narrows
            float turn = Math.max(0, side * lookX);
            float sx = 1f - 0.3f * turn;
            float ex = cx + side * sep * (1f - 0.12f * Math.abs(lookX));
            float rx = r * sx;
            float ry = r * Math.max(0.07f, open);
            drawEye(c, ex, cy, rx, ry, stroke);
        }

        if (isAttachedToWindow() && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    private void drawEye(Canvas c, float x, float y, float rx, float ry, float stroke) {
        oval.set(x - rx, y - ry, x + rx, y + ry);
        // 360 = full ring, 160 = smiling arc on top ( ^ )
        float sweep = 360f - happy * 200f;
        float start = 270f - sweep / 2f;

        // soft glow passes
        for (int i = 3; i >= 1; i--) {
            glow.setStrokeWidth(stroke * (1f + i * 1.1f));
            glow.setAlpha(i == 3 ? 18 : i == 2 ? 34 : 60);
            c.drawArc(oval, start, sweep, false, glow);
        }
        core.setStrokeWidth(stroke);
        c.drawArc(oval, start, sweep, false, core);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
