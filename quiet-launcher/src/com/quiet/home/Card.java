package com.quiet.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import com.quiet.px.Font;
import com.quiet.px.Pal;

/**
 * The voice's card: a small stone slab at the foot of the screen, over whatever is in front (our
 * pages, YouTube, Netflix...), drawn in the same 6x pixels and fonts as everything else.
 *
 *   LISTENING   Mote glows and breathes with your voice; a little meter moves
 *   your words  as soon as they're understood, then what's happening ("OPENING NETFLIX")
 *   a reply     for questions ("IT'S 9:41"), or why something couldn't be done (in pink)
 */
final class Card extends View implements Choreographer.FrameCallback {

    static final int W = 200, H = 30, HMAX = 124;       // grows (downwards) for a longer answer or a list
    static final int LISTEN = 0, THINK = 1, DONE = 2, FAIL = 3;

    final Bitmap bmp = Bitmap.createBitmap(W, HMAX, Bitmap.Config.ARGB_8888);
    final int[] px = new int[W * HMAX];
    final Paint paint = new Paint();
    final Rect src = new Rect(0, 0, W, HMAX), dst = new Rect();
    /** More than fits the small card: the answer's lines, or a list to pick from (sel: the one lit). */
    final java.util.List<String> body = new java.util.ArrayList<>();
    boolean list;
    int sel;
    float h = H;
    final float[] meter = new float[12];

    int state = LISTEN;
    /** An in-between step (LOOKING, PRESSING...): if it runs out before something else is said, that's a failure. */
    boolean working;
    long since = SystemClock.uptimeMillis();          // when the state last changed (the watchdog)
    Runnable stuck;                                    // told when the card had to call something stuck
    String words = "", label = "LISTENING";
    float level, glow, rise = 1f, t;
    boolean running, leaving;
    long last, hideAt;
    Runnable gone;

    Card(Context c) {
        super(c);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    void listening(boolean wake) {
        working = false;
        since = SystemClock.uptimeMillis();
        body.clear();
        list = false;
        state = LISTEN;
        words = "";
        label = wake ? "LISTENING · SAY IT" : "LISTENING";
        leaving = false;
        hideAt = 0;
        rise = 1f;
        java.util.Arrays.fill(meter, 0);
        start();
    }

    /** Asking for something (teaching the wake word): big line and small line, listening. */
    void prompt(String big, String small) {
        state = LISTEN;
        words = Font.upper(big);
        label = Font.upper(small);
        leaving = false;
        hideAt = 0;
        start();
    }

    void level(float l) {
        level = l;
    }

    void heard(String text) {
        words = Font.upper(text);
        working = false;
        since = SystemClock.uptimeMillis();
        state = THINK;
        label = "THINKING";
    }

    void done(String what, long ms) {
        working = false;
        since = SystemClock.uptimeMillis();
        state = DONE;
        label = Font.upper(what);
        hideAt = SystemClock.uptimeMillis() + ms;
    }

    /** An in-between step: shown like done, but if nothing follows before it runs out, it becomes a failure. */
    void working(String what, long ms) {
        done(what, ms);
        working = true;
    }

    void fail(String why) {
        working = false;
        since = SystemClock.uptimeMillis();
        state = FAIL;
        label = Font.upper(why);
        hideAt = SystemClock.uptimeMillis() + 3200;
    }

    /** A reply: one line if it fits, else the first part large and the rest beneath; longer, the card grows. */
    void say(String text) {
        working = false;
        since = SystemClock.uptimeMillis();
        state = DONE;
        body.clear();
        list = false;
        String t = Font.upper(text).trim();
        int room = W - 26 - 8;
        java.util.List<String> lines = wrap(t, room);
        if (lines.size() > 2) {                                // a longer answer: first line large, the rest beneath
            words = lines.get(0);
            label = "";
            body.addAll(lines.subList(1, Math.min(lines.size(), 9)));
        } else if (Font.microWidth(t, 2) <= room) {
            label = t;
        } else {
            String[] w = t.split(" ");
            StringBuilder a = new StringBuilder();
            int i = 0;
            while (i < w.length && Font.width((a + " " + w[i]).trim(), 1, 1) <= room) a.append(a.length() > 0 ? " " : "").append(w[i++]);
            StringBuilder b = new StringBuilder();
            while (i < w.length) b.append(b.length() > 0 ? " " : "").append(w[i++]);
            words = a.toString();
            label = b.toString();
        }
        hideAt = SystemClock.uptimeMillis() + 2500 + t.length() * 70L;
    }

    /** A short list to pick from (arrows and OK, or by saying which): stays a while, the first one lit. */
    void choose(String title, java.util.List<String> options) {
        working = false;
        since = SystemClock.uptimeMillis();
        state = DONE;
        words = fit(Font.upper(title), W - 34, false);
        label = "UP/DOWN · OK · OR SAY WHICH";
        body.clear();
        for (int i = 0; i < options.size() && i < 8; i++) body.add(fit((i + 1) + "  " + Font.upper(options.get(i)), W - 30, false));
        list = true;
        sel = 0;
        hideAt = SystemClock.uptimeMillis() + 25_000;
        leaving = false;
        start();
    }

    void move(int by) {
        if (!list || body.isEmpty()) return;
        sel = Math.max(0, Math.min(body.size() - 1, sel + by));
        hideAt = SystemClock.uptimeMillis() + 25_000;
    }

    static java.util.List<String> wrap(String t, int room) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder a = new StringBuilder();
        for (String w : t.split(" ")) {
            if (a.length() > 0 && Font.width(a + " " + w, 1, 1) > room) {
                out.add(a.toString());
                a.setLength(0);
            }
            a.append(a.length() > 0 ? " " : "").append(w);
        }
        if (a.length() > 0) out.add(a.toString());
        return out;
    }

    void dismiss() {
        leaving = true;
    }

    void start() {
        if (running) return;
        running = true;
        last = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        long ms = nanos / 1_000_000;
        float dt = last == 0 ? 0.05f : Math.min(0.1f, (ms - last) / 1000f);
        if (last == 0 || ms - last >= 45) {
            last = ms;
            t += dt;
            long nowMs = SystemClock.uptimeMillis();
            // The watchdog: every ask ends in an answer, an action or a failure said out loud, never silence.
            if (!leaving && (state == THINK && nowMs - since > 30_000 || working && hideAt > 0 && nowMs > hideAt)) {
                boolean thinking = state == THINK;
                fail(thinking ? "THAT TOOK TOO LONG · TRY AGAIN" : "THAT DIDN'T FINISH · TRY AGAIN");
                if (stuck != null) stuck.run();
            } else if (hideAt > 0 && nowMs > hideAt) leaving = true;
            rise += ((leaving ? 1f : 0f) - rise) * Math.min(1f, dt * 14f);
            float want = body.isEmpty() ? H : Math.min(HMAX, H + 4 + body.size() * 10);
            h += (want - h) * Math.min(1f, dt * 12f);                 // it grows out of itself, and back
            if (leaving && rise > 0.97f) {
                running = false;
                if (gone != null) gone.run();
                return;
            }
            render();
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------------------------------------------------------------- drawing

    void render() {
        java.util.Arrays.fill(px, 0);
        int hh = Math.round(h);
        int lift = Math.round(rise * (hh + 2));
        int y0 = -lift;                                        // slides down from the top edge
        // The slab: stone, a worn top edge, chipped corners.
        rect(0, y0, W, hh - 1, Pal.STONE[2]);
        rect(1, y0, W - 2, 1, Pal.STONE[4]);
        rect(1, y0 + 1, W - 2, 1, Pal.STONE[3]);
        rect(0, y0 + hh - 1, W, 1, Pal.VOID);
        dot(0, y0, 0);
        dot(W - 1, y0, 0);
        // Mote, the lamp: brighter with your voice while listening, steady while thinking.
        float target = state == LISTEN ? 0.35f + level * 0.9f : state == THINK ? 0.55f + 0.25f * (float) Math.sin(t * 6) : 0.6f;
        glow += (target - glow) * 0.35f;
        mote(12, y0 + 15, glow);
        // Words and the line under them.
        int tx = 26, maxW = W - tx - 8 - (state == LISTEN ? 30 : 0);
        String top = words.isEmpty() ? (state == LISTEN ? "" : "") : fit(words, maxW, false);
        int ly = y0 + 18;
        if (!top.isEmpty()) Font.draw(px, W, HMAX, top, tx, y0 + 7, 1, 1, Pal.TEXT);
        else ly = y0 + 12;
        int lc = state == FAIL ? Pal.PINK : state == DONE ? Pal.AMBER : state == LISTEN ? Pal.MINT : Pal.DIM;
        String l = fit(label + (state == THINK ? dots() : ""), maxW, true);
        Font.drawMicro(px, W, HMAX, l, tx, ly + 1, 2, Pal.VOID);
        Font.drawMicro(px, W, HMAX, l, tx, ly, 2, lc);
        for (int i = 0; i < body.size(); i++) {               // the rest of the answer, or the list
            int by = y0 + H + 1 + i * 10;
            if (by + 8 > y0 + hh - 2) break;                   // still growing
            boolean lit = list && i == sel;
            if (lit) rect(18, by - 2, W - 22, 11, Pal.STONE[3]);
            if (lit) Font.draw(px, W, HMAX, "\u2192", 10, by, 1, 1, Pal.AMBER);
            Font.draw(px, W, HMAX, body.get(i), list ? 22 : tx, by, 1, 1, lit ? Pal.TEXT : list ? Pal.DIM : Pal.SOFT);
        }
        if (state == LISTEN) {                                 // the meter: twelve little bars
            System.arraycopy(meter, 1, meter, 0, meter.length - 1);
            meter[meter.length - 1] = level;
            for (int i = 0; i < meter.length; i++) {
                int h = 1 + Math.round(meter[i] * 9);
                rect(W - 34 + i * 2, y0 + 20 - h, 1, h, meter[i] > 0.15f ? Pal.MINT : Pal.FAINT);
            }
        }
        bmp.setPixels(px, 0, W, 0, 0, W, HMAX);
    }

    String dots() {
        int n = 1 + (int) (t * 3) % 3;
        return n == 1 ? "." : n == 2 ? ".." : "...";
    }

    static String fit(String s, int w, boolean micro) {
        if ((micro ? Font.microWidth(s, 2) : Font.width(s, 1, 1)) <= w) return s;
        while (s.length() > 1 && (micro ? Font.microWidth(s + "...", 2) : Font.width(s + "...", 1, 1)) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "...";
    }

    void mote(int cx, int cy, float g) {
        // A soft dithered halo, then the firefly: dark body, pale wings, a glowing tail.
        int r = 4 + Math.round(g * 4);
        for (int y = -r; y <= r; y++)
            for (int x = -r; x <= r; x++) {
                float d = (float) Math.sqrt(x * x + y * y) / r;
                if (d > 1f) continue;
                float a = (1f - d) * g;
                float bayer = Pal.BAYER[((y & 3) << 2) | (x & 3)] + 0.5f;
                if (a > bayer * 0.9f) blend(cx + x, cy + y, a > 0.6f ? 0xFF6A5420 : 0xFF2E2A22);
            }
        int wing = ((int) (t * 14) & 1) == 0 ? -1 : 0;
        dot(cx - 2, cy - 3 + wing, Pal.F_WING);
        dot(cx - 1, cy - 3 + wing, Pal.F_WING_DK);
        dot(cx + 1, cy - 3 + wing, Pal.F_WING);
        dot(cx + 2, cy - 3 + wing, Pal.F_WING_DK);
        rect(cx - 1, cy - 2, 3, 2, Pal.F_BODY);
        dot(cx - 2, cy - 1, Pal.F_HEAD);
        rect(cx + 1, cy, 2, 2, g > 0.5f ? Pal.F_GLOW_HI : Pal.F_GLOW);
        dot(cx + 3, cy + 1, Pal.F_GLOW);
    }

    void rect(int x, int y, int w, int h, int c) {
        for (int j = Math.max(0, y); j < Math.min(HMAX, y + h); j++)
            for (int i = Math.max(0, x); i < Math.min(W, x + w); i++) px[j * W + i] = c;
    }

    void dot(int x, int y, int c) {
        if (x >= 0 && x < W && y >= 0 && y < HMAX) px[y * W + x] = c;
    }

    void blend(int x, int y, int c) {
        if (x >= 0 && x < W && y >= 0 && y < HMAX && px[y * W + x] == Pal.STONE[2]) px[y * W + x] = c;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        dst.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawBitmap(bmp, src, dst, paint);
    }
}
