package com.monolith.notcheyes;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

/**
 * Renderer for two thick ring eyes. The rings never change shape: expressions
 * come from straight black bars that slide in and tilt over each ring, and
 * blinks from two bars closing like eyelids. All motion runs on critically
 * damped springs so it starts and stops smoothly.
 */
final class Eyes {

    static final int IDLE = 0, HAPPY = 1, PLEASED = 2, SHY = 3, SAD = 4, SLEEPY = 5;

    private static final float MASK_OFF = 1.35f; // bar fully outside the ring

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    private final Spring lookX = new Spring(110), lookY = new Spring(110);
    private final Spring liftY = new Spring(60);
    private final Spring pop = new Spring(160);
    private final Spring[] maskAngle = {new Spring(55), new Spring(55)};
    private final Spring[] maskDist = {new Spring(70), new Spring(70)};

    private int expression = IDLE;
    private long expressionUntil, nextSaccade, nextBlink, popUntil;
    private long blinkStart = -1;
    private int blinksLeft;
    private float lid; // 0 open .. 1 closed
    private boolean following;
    private boolean autoPlay = true;
    private long last;

    Eyes() {
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        mask.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 2; i++) {
            maskDist[i].snap(MASK_OFF);
            maskAngle[i].snap(90);
        }
        long now = now();
        nextSaccade = now + 700;
        nextBlink = now + 1600;
        expressionUntil = now + 2500;
    }

    static long now() {
        return android.view.animation.AnimationUtils.currentAnimationTimeMillis();
    }

    // ---------------------------------------------------------------- control

    void setExpression(int e, long durationMs) {
        long now = now();
        expression = e;
        expressionUntil = now + durationMs;
        float a, d, lift;
        switch (e) {
            case HAPPY:   a = 90;  d = 0.02f; lift = -0.18f; break; // ∩  bar along the bottom
            case PLEASED: a = -45; d = 0.48f; lift = 0f;     break; // bar from the upper inner corner
            case SHY:     a = 225; d = 0.48f; lift = 0.28f;  break; // bar from the upper outer corner
            case SAD:     a = 270; d = 0.05f; lift = 0.2f;   break; // ∪  bar along the top
            case SLEEPY:  a = 270; d = 0.45f; lift = 0.15f;  break; // heavy top lid
            default:      a = Float.NaN; d = MASK_OFF; lift = 0f; break;
        }
        for (int i = 0; i < 2; i++) {
            if (!Float.isNaN(a)) {
                float ai = i == 0 ? a : 180f - a;
                // a bar that is off screen can jump to its new angle and slide in from there
                if (maskDist[i].x > 1.1f) maskAngle[i].snap(ai);
                else maskAngle[i].target = ai;
            }
            maskDist[i].target = d;
        }
        liftY.target = lift;
        if (e != IDLE) {
            lookX.target = 0;
            lookY.target = 0;
        }
    }

    void celebrate() {
        setExpression(HAPPY, 2400);
    }

    void surprise() {
        setExpression(IDLE, 1800);
        popUntil = now() + 650;
        lookX.target = 0;
        lookY.target = 0;
        nextSaccade = now() + 1400;
    }

    void blink() {
        if (blinkStart < 0) {
            blinkStart = now();
            blinksLeft = 1;
        }
    }

    void follow(float nx, float ny) {
        following = true;
        if (expression != IDLE) setExpression(IDLE, 1500);
        lookX.target = clamp(nx, -1, 1);
        lookY.target = clamp(ny, -1, 1);
    }

    void release() {
        following = false;
        nextSaccade = now() + 1500;
    }

    void randomMood() {
        int[] moods = {HAPPY, PLEASED, SHY, HAPPY};
        blink();
        setExpression(moods[rnd.nextInt(moods.length)], 2600);
    }

    // ----------------------------------------------------------------- update

    private void update() {
        long now = now();
        float dt = last == 0 ? 0.016f : Math.min(0.064f, (now - last) / 1000f);
        last = now;

        if (autoPlay && !following && now >= expressionUntil) {
            if (expression != IDLE) {
                setExpression(IDLE, 2200 + rnd.nextInt(2500));
            } else {
                float r = rnd.nextFloat();
                int e = r < 0.35f ? HAPPY : r < 0.6f ? PLEASED : r < 0.82f ? SHY : r < 0.92f ? SAD : SLEEPY;
                setExpression(e, 2600 + rnd.nextInt(1800));
            }
        }

        if (expression == IDLE && !following && now >= nextSaccade) {
            if (rnd.nextFloat() < 0.35f) {
                lookX.target = 0;
                lookY.target = 0;
            } else {
                lookX.target = rnd.nextFloat() * 2f - 1f;
                lookY.target = (rnd.nextFloat() * 2f - 1f) * 0.6f;
            }
            nextSaccade = now + 900 + rnd.nextInt(2200);
        }
        pop.target = now < popUntil ? 1f : 0f;

        // springs, in small fixed steps for stability
        int steps = Math.max(1, (int) Math.ceil(dt / 0.008f));
        float h = dt / steps;
        for (int s = 0; s < steps; s++) {
            lookX.step(h);
            lookY.step(h);
            liftY.step(h);
            pop.step(h);
            for (int i = 0; i < 2; i++) {
                maskAngle[i].stepAngle(h);
                maskDist[i].step(h);
            }
        }

        // eyelid blink: close fast, hold, open a little slower
        if (blinkStart < 0 && now >= nextBlink && expression != SLEEPY) {
            blinkStart = now;
            blinksLeft = rnd.nextFloat() < 0.25f ? 2 : 1;
        }
        lid = 0f;
        if (blinkStart >= 0 && now >= blinkStart) {
            float t = now - blinkStart;
            if (t < 80) lid = smooth(t / 80f);
            else if (t < 120) lid = 1f;
            else if (t < 250) lid = 1f - smooth((t - 120) / 130f);
            else {
                blinksLeft--;
                if (blinksLeft > 0) blinkStart = now + 90;
                else {
                    blinkStart = -1;
                    nextBlink = now + 2400 + rnd.nextInt(3600);
                }
            }
        }
    }

    // ------------------------------------------------------------------- draw

    /** Updates and draws the eyes centered in the given box. */
    void draw(Canvas c, float left, float top, float right, float bottom, int background) {
        float w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) {
            update();
            return;
        }
        float R = Math.min(h * 0.31f, w / 8.2f);
        float sep = R * 1.5f;
        float maxX = Math.max(0, Math.min(R * 1.5f, w / 2f - sep - R * 1.2f));
        float maxY = Math.max(0, Math.min(R * 0.7f, h / 2f - R * 1.15f));
        drawAt(c, left + w / 2f, top + h / 2f, R, sep, maxX, maxY, background);
    }

    /**
     * Updates and draws the eyes with explicit geometry.
     * @param cx,cy center point between the eyes
     * @param R     outer ring radius
     * @param sep   distance from the center point to each eye's center
     * @param maxX  how far the pair may shift sideways (gaze and sway)
     * @param maxY  how far the pair may shift vertically (gaze)
     */
    void drawAt(Canvas c, float cx0, float cy0, float R, float sep, float maxX, float maxY, int background) {
        update();
        if (R <= 0) return;
        R *= 1f + 0.14f * pop.x;
        float stroke = R * 0.44f;

        double t = now() / 1000.0;
        float bob = expression == HAPPY ? (float) Math.sin(t * Math.PI * 2 * 1.3) * R * 0.1f : 0f;
        float sway = (expression == PLEASED || expression == SHY)
                ? (float) Math.sin(t * Math.PI * 2 * 0.55) * Math.min(R * 0.22f, maxX * 0.6f) : 0f;

        float dx = clamp(lookX.x * maxX + sway, -maxX, maxX);
        float cx = cx0 + dx;
        float cy = cy0 + lookY.x * maxY + liftY.x * R + bob;

        ring.setStrokeWidth(stroke);
        mask.setColor(background);
        float ringR = R - stroke / 2f;

        for (int i = 0; i < 2; i++) {
            float ex = cx + (i == 0 ? -sep : sep);
            c.save();
            // keep each eye's bars inside its own square
            float box = R + 2f;
            c.clipRect(ex - box, cy - box, ex + box, cy + box);
            c.drawCircle(ex, cy, ringR, ring);

            float d = maskDist[i].x;
            if (d < MASK_OFF - 0.01f) {
                c.save();
                c.rotate(maskAngle[i].x, ex, cy);
                c.drawRect(ex + d * R, cy - 2 * R, ex + 3 * R, cy + 2 * R, mask);
                c.restore();
            }
            if (lid > 0.001f) {
                float edge = box * (1f - lid);
                c.drawRect(ex - box, cy - box - 1, ex + box, cy - edge, mask);
                c.drawRect(ex - box, cy + edge, ex + box, cy + box + 1, mask);
            }
            c.restore();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static float smooth(float x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Critically damped spring. */
    private static final class Spring {
        float x, v, target;
        final float k, damping;

        Spring(float stiffness) {
            k = stiffness;
            damping = 2f * (float) Math.sqrt(stiffness);
        }

        void snap(float value) {
            x = target = value;
            v = 0;
        }

        void step(float dt) {
            v += (k * (target - x) - damping * v) * dt;
            x += v * dt;
        }

        void stepAngle(float dt) {
            float d = (target - x) % 360f;
            if (d > 180f) d -= 360f;
            if (d < -180f) d += 360f;
            v += (k * d - damping * v) * dt;
            x += v * dt;
        }
    }
}
