package com.quiet.drop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.quiet.px.Keys;

/**
 * The phone remote's arrows and OK: a round pad, drawn in stone with pixel arrows. Press a side for
 * that arrow, the middle for OK; hold an arrow and it repeats (lists scroll on).
 */
final class Pad extends View {

    interface OnKey {
        void key(int keyCode);
    }

    static final int RING = 0xFF1C1F33, MIDDLE = 0xFF262A44, LIT = 0xFF34395C;
    static final int[] ARROWS = {Keys.UP, Keys.RIGHT, Keys.DOWN, Keys.LEFT};

    final OnKey onKey;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF oval = new RectF();
    final Handler again = new Handler(Looper.getMainLooper());
    int down = -1;                                             // 0-3 an arrow (up, right, down, left), 4 OK

    final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (down < 0 || down > 3) return;
            onKey.key(ARROWS[down]);
            again.postDelayed(this, 140);
        }
    };

    Pad(Context c, OnKey onKey) {
        super(c);
        this.onKey = onKey;
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 2, inner = r * 0.40f;
        paint.setColor(RING);
        c.drawCircle(cx, cy, r, paint);
        if (down >= 0 && down < 4) {                           // the side under your thumb
            paint.setColor(LIT);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, -135 + 90 * down, 90, true, paint);
        }
        paint.setAntiAlias(false);
        float px = Math.max(2, Math.round(r / 34f));           // one arrow pixel
        for (int i = 0; i < 4; i++) {
            c.save();
            c.rotate(90 * i, cx, cy);
            paint.setColor(down == i ? Phone.AMBER : Phone.TEXT);
            float tip = cy - (r + inner) / 2f - 2.5f * px;
            for (int row = 0; row < 4; row++)                  // a pixel triangle, 4 rows, pointing out
                c.drawRect(cx - (row + 0.5f) * px, tip + row * px, cx + (row + 0.5f) * px, tip + (row + 1) * px, paint);
            c.restore();
        }
        paint.setAntiAlias(true);
        paint.setColor(down == 4 ? Phone.AMBER : MIDDLE);
        c.drawCircle(cx, cy, inner, paint);
        paint.setColor(down == 4 ? Phone.PURPLE : Phone.TEXT);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(inner * 0.42f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText("OK", cx, cy - (paint.descent() + paint.ascent()) / 2, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy), dx = e.getX() - cx, dy = e.getY() - cy;
            double d = Math.hypot(dx, dy);
            if (d > r) return false;
            double deg = Math.toDegrees(Math.atan2(dy, dx));   // 0 is right, 90 is down
            down = d < r * 0.40f ? 4 : deg > -135 && deg <= -45 ? 0 : deg > -45 && deg <= 45 ? 1 : deg > 45 && deg <= 135 ? 2 : 3;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onKey.key(down == 4 ? Keys.OK : ARROWS[down]);
            if (down < 4) again.postDelayed(repeat, 450);
            invalidate();
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            again.removeCallbacks(repeat);
            down = -1;
            invalidate();
        }
        return true;
    }
}
