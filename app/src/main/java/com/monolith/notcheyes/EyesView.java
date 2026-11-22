package com.monolith.notcheyes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;

/** Full-screen eyes for the app. Touch to make them follow, tap for a mood. */
public class EyesView extends View {

    private final Eyes eyes = new Eyes();
    private long downTime;

    public EyesView(Context context, boolean interactive) {
        super(context);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = getWidth(), h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = Eyes.now();
                // fall through
            case MotionEvent.ACTION_MOVE:
                eyes.follow((e.getX() - w / 2f) / (w / 2f), (e.getY() - h / 2f) / (h / 2f));
                return true;
            case MotionEvent.ACTION_UP:
                eyes.release();
                if (Eyes.now() - downTime < 250) eyes.randomMood();
                return true;
            case MotionEvent.ACTION_CANCEL:
                eyes.release();
                return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        eyes.draw(c, 0, 0, getWidth(), getHeight(), Color.BLACK);
        if (isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
