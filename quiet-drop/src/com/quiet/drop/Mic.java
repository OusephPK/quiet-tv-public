package com.quiet.drop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The phone remote's microphone: a big round button with the pixel microphone of the "Talk to TV"
 * icon (res/drawable/talk_mic.xml, the same pixels). Amber at rest, mint while it listens (with a
 * ring that follows your voice), dimmer while it sends.
 */
final class Mic extends View {

    static final int IDLE = 0, LISTENING = 1, SENDING = 2;

    // The icon's pixels on its 18-pixel grid: x, y, width, height (capsule, then cradle, stem, foot).
    static final int[][] HEAD = {{8, 4, 2, 1}, {7, 5, 4, 4}, {8, 9, 2, 1}}, GLINT = {{8, 5, 1, 2}},
            STAND = {{5, 7, 1, 3}, {12, 7, 1, 3}, {6, 10, 1, 1}, {11, 10, 1, 1}, {7, 11, 4, 1}, {8, 12, 2, 1}, {7, 13, 4, 1}};

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    int state = IDLE;
    float level, shown;

    Mic(Context c) {
        super(c);
        setHapticFeedbackEnabled(true);
    }

    void show(int s) {
        state = s;
        level = 0;
        invalidate();
    }

    /** How loud you are now, 0 to 1 (while listening). */
    void level(float l) {
        level = Math.max(0, Math.min(1, l));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, room = Math.min(cx, cy), r = room * 0.84f;
        shown += (level - shown) * 0.5f;                       // the ring eases toward your voice
        if (state == LISTENING) {
            paint.setColor(Phone.MINT);
            paint.setAlpha(70);
            c.drawCircle(cx, cy, r + (room - r) * (0.35f + 0.65f * shown), paint);
            if (Math.abs(level - shown) > 0.01f) postInvalidateOnAnimation();
        }
        paint.setAlpha(255);
        paint.setColor(state == IDLE ? Phone.AMBER : state == LISTENING ? Phone.MINT : 0xFF2E8F7A);
        c.drawCircle(cx, cy, r, paint);
        // The microphone, dark on the light circle: its 10 rows fill half the circle's height.
        float s = r / 10f;
        paint.setAntiAlias(false);
        paint.setColor(Phone.PURPLE);
        for (int[][] part : new int[][][]{HEAD, STAND}) for (int[] p : part) cell(c, cx, cy, s, p);
        paint.setColor(state == IDLE ? 0xFFFFD596 : 0xFFA6F7E2);      // the glint, a lighter step of the circle
        cell(c, cx, cy, s, GLINT[0]);
        paint.setAntiAlias(true);
    }

    void cell(Canvas c, float cx, float cy, float s, int[] p) {
        float x = cx + (p[0] - 9) * s, y = cy + (p[1] - 9) * s;
        c.drawRect(Math.round(x), Math.round(y), Math.round(x + p[2] * s), Math.round(y + p[3] * s), paint);
    }
}
