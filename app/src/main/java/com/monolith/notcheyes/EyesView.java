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
 * Thick white ring eyes on black. Each eye is a ring with a wedge cut out;
 * the wedge spins and opens to form expressions:
 *   IDLE   O O    full rings that look around
 *   HAPPY  ∩ ∩    gap at the bottom, bobbing
 *   PLEASED ◡ ◡   gaps facing up and inward, swaying
 *   SHY    ◟ ◞    gaps facing up and outward, eyes dropped
 *   SAD    ∪ ∪    gap at the top
 * Blinks squash the eyes until they vanish for a moment.
 */
public class EyesView extends View {

    public static final int IDLE = 0, HAPPY = 1, PLEASED = 2, SHY = 3, SAD = 4;

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Random rnd = new Random();
    private final boolean interactive;

    // per eye (0 = left, 1 = right): gap center angle and gap size in degrees
    private final float[] gapC = {90, 90}, gapS = {0, 0};
    private final float[] tgtC = {90, 90}, tgtS = {0, 0};

    private int expression = IDLE;
    private long expressionUntil;

    private float lookX, lookY, targetX, targetY;
    private float dropY, tgtDropY; // in eye radii
    private float pop;             // surprise scale bump
    private long popUntil;

    private float open = 1f;
    private long blinkStart = -1;
    private int blinksLeft;

    private long nextSaccade, nextBlink, lastFrame, downTime;
    private boolean following;

    public EyesView(Context context, boolean interactive) {
        super(context);
        this.interactive = interactive;
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeCap(Paint.Cap.BUTT);
        core.setColor(0xFFFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.BUTT);
        glow.setColor(0xFFDDF4FF);
        long now = SystemClock.uptimeMillis();
        nextSaccade = now + 600;
        nextBlink = now + 1800;
        expressionUntil = now + 2500;
    }

    /** Show an expression for a while, then go back to idle. */
    public void setExpression(int e, long durationMs) {
        long now = SystemClock.uptimeMillis();
        boolean fromClosed = gapS[0] < 20f;
        expression = e;
        expressionUntil = now + durationMs;
        float c, s, drop;
        switch (e) {
            case HAPPY:   c = 90;  s = 150; drop = -0.15f; break;
            case PLEASED: c = 310; s = 105; drop = 0f;     break;
            case SHY:     c = 230; s = 110; drop = 0.3f;   break;
            case SAD:     c = 270; s = 150; drop = 0.25f;  break;
            default:      c = tgtC[0]; s = 0; drop = 0f;   break;
        }
        tgtC[0] = c;
        tgtC[1] = mirror(c);
        tgtS[0] = tgtS[1] = s;
        tgtDropY = drop;
        if (e != IDLE) {
            targetX = 0;
            targetY = 0;
            if (fromClosed) {
                // start the gap half a turn away so it spins into place
                gapC[0] = c - 180f;
                gapC[1] = mirror(c) + 180f;
            }
        }
        invalidate();
    }

    public void celebrate() {
        setExpression(HAPPY, 2200);
    }

    public void surprise() {
        setExpression(IDLE, 1500);
        popUntil = SystemClock.uptimeMillis() + 600;
        targetX = 0;
        targetY = 0;
        nextSaccade = SystemClock.uptimeMillis() + 1200;
    }

    public void blinkNow() {
        if (blinkStart < 0) {
            blinkStart = SystemClock.uptimeMillis();
            blinksLeft = 1;
        }
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
                if (expression != IDLE) setExpression(IDLE, 1500);
                targetX = clamp((e.getX() - w / 2f) / (w / 2f), -1, 1);
                targetY = clamp((e.getY() - h / 2f) / (h / 2f), -1, 1);
                return true;
            case MotionEvent.ACTION_UP:
                following = false;
                if (SystemClock.uptimeMillis() - downTime < 250) {
                    int[] moods = {HAPPY, PLEASED, SHY};
                    setExpression(moods[rnd.nextInt(moods.length)], 2500);
                    blinkNow();
                }
                nextSaccade = SystemClock.uptimeMillis() + 1500;
                return true;
            case MotionEvent.ACTION_CANCEL:
                following = false;
                return true;
        }
        return false;
    }

    private void update(long now, float dt) {
        // expression timeline
        if (!following && now >= expressionUntil) {
            if (expression != IDLE) {
                blinkNow();
                setExpression(IDLE, 2000 + rnd.nextInt(2500));
            } else {
                float r = rnd.nextFloat();
                int e = r < 0.4f ? HAPPY : r < 0.65f ? PLEASED : r < 0.9f ? SHY : SAD;
                blinkNow();
                setExpression(e, 2500 + rnd.nextInt(1800));
            }
        }

        // gaze wanders only while idle
        if (expression == IDLE && !following && now >= nextSaccade) {
            if (rnd.nextFloat() < 0.35f) {
                targetX = 0;
                targetY = 0;
            } else {
                targetX = rnd.nextFloat() * 2f - 1f;
                targetY = (rnd.nextFloat() * 2f - 1f) * 0.7f;
            }
            nextSaccade = now + 600 + rnd.nextInt(2000);
        }

        float k = 1f - (float) Math.exp(-dt * 14f);
        lookX += (targetX - lookX) * k;
        lookY += (targetY - lookY) * k;

        float ka = 1f - (float) Math.exp(-dt * 9f);
        for (int i = 0; i < 2; i++) {
            gapC[i] += angleDiff(gapC[i], tgtC[i]) * ka;
            gapS[i] += (tgtS[i] - gapS[i]) * ka;
        }
        dropY += (tgtDropY - dropY) * ka;
        pop += ((now < popUntil ? 1f : 0f) - pop) * (1f - (float) Math.exp(-dt * 16f));

        // blinking
        if (blinkStart < 0 && now >= nextBlink) {
            blinkStart = now;
            blinksLeft = rnd.nextFloat() < 0.25f ? 2 : 1;
        }
        open = 1f;
        if (blinkStart >= 0 && now >= blinkStart) {
            float t = (now - blinkStart) / 230f;
            if (t < 0.35f) open = 1f - t / 0.35f;
            else if (t < 0.5f) open = 0f;
            else open = Math.min(1f, (t - 0.5f) / 0.5f);
            if (t >= 1f) {
                open = 1f;
                blinksLeft--;
                if (blinksLeft > 0) {
                    blinkStart = now + 80;
                } else {
                    blinkStart = -1;
                    nextBlink = now + 2200 + rnd.nextInt(3500);
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
        if (w > 0 && h > 0) {
            float r = Math.min(h * 0.27f, w / 9f) * (1f + 0.15f * pop);
            float stroke = r * 0.55f;
            float outer = r + stroke / 2f;
            float sep = outer * 1.55f;
            float maxX = Math.max(0, Math.min(r * 1.4f, w / 2f - sep - outer - stroke));
            float maxY = Math.max(0, Math.min(r * 0.9f, h / 2f - outer - stroke * 0.5f));

            double t = now / 1000.0;
            float bob = 0, sway = 0;
            if (expression == HAPPY) bob = (float) Math.sin(t * Math.PI * 2 * 1.4) * r * 0.14f;
            if (expression == PLEASED || expression == SHY) {
                sway = (float) Math.sin(t * Math.PI * 2 * 0.6) * r * 0.25f;
            }

            float cx = w / 2f + lookX * maxX + sway;
            float cy = h / 2f + lookY * maxY + dropY * r + bob;

            if (open > 0.04f) {
                for (int i = 0; i < 2; i++) {
                    float ex = cx + (i == 0 ? -sep : sep);
                    drawEye(c, ex, cy, r, r * open, stroke, i);
                }
            }
        }
        if (isAttachedToWindow() && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    private void drawEye(Canvas c, float x, float y, float rx, float ry, float stroke, int i) {
        oval.set(x - rx, y - ry, x + rx, y + ry);
        float gs = gapS[i];
        glow.setStrokeWidth(stroke * 1.5f);
        glow.setAlpha(28);
        core.setStrokeWidth(stroke);
        if (gs < 1f) {
            c.drawOval(oval, glow);
            c.drawOval(oval, core);
        } else {
            float sweep = 360f - gs;
            float start = gapC[i] + gs / 2f;
            c.drawArc(oval, start, sweep, false, glow);
            c.drawArc(oval, start, sweep, false, core);
        }
    }

    private static float mirror(float a) {
        return 180f - a;
    }

    private static float angleDiff(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
