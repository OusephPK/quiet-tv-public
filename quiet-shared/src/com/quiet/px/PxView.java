package com.quiet.px;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * Runs the frame loop and puts the 320×180 buffer on screen at an integer scale, unsmoothed.
 * ~20 fps while you're pressing buttons, ~12 fps while you watch, and nothing at all while paused.
 */
public final class PxView extends View implements Choreographer.FrameCallback {

    public interface Scene {
        void frame(Stage s);
    }

    /** Decides when Mote (and so Pip) rests, instead of "no keys for a while". The screensaver has one. */
    public interface Rest {
        boolean resting();
    }

    public Rest rest;

    static final long ACTIVE_MS = 50, IDLE_MS = 83, BUSY_MS = 6_000, SLEEP_AFTER_MS = 90_000;

    final Stage stage;
    final Scene scene;
    final Bitmap bmp = Bitmap.createBitmap(Stage.W, Stage.H, Bitmap.Config.ARGB_8888);
    final Paint paint = new Paint();
    final Rect src = new Rect(0, 0, Stage.W, Stage.H), dst = new Rect();
    boolean running;
    Oops oops;                 // the last page broke: say so for a few seconds
    long last, lastInput;

    public PxView(Context c, Stage stage, Scene scene) {
        super(c);
        this.stage = stage;
        this.scene = scene;
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        setBackgroundColor(Pal.VOID);
        lastInput = SystemClock.uptimeMillis();
        oops = Oops.check(c);
    }

    public void start() {
        if (running) return;
        running = true;
        last = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** Any key press wakes Mote and restores the full frame rate. */
    public void poke() {
        lastInput = SystemClock.uptimeMillis();
        stage.mote.sleep(false);
        if (oops != null && oops.dismissable()) oops = null;
    }

    /** Render one frame right now (used after input so response never waits for the next tick). */
    public void now() {
        render(0);
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        long ms = nanos / 1_000_000;
        boolean idle = rest != null ? rest.resting() : ms - lastInput > SLEEP_AFTER_MS;
        stage.mote.sleep(idle && !stage.partyOn());       // nobody sleeps through the music
        // Full speed while you're pressing buttons; a gentler pace while you just watch.
        long step = ms - lastInput < BUSY_MS ? ACTIVE_MS : IDLE_MS;
        if (last == 0) last = ms - step;
        if (ms - last >= step) {
            float dt = Math.min(0.2f, (ms - last) / 1000f);
            last = ms;
            render(dt);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    void render(float dt) {
        stage.tick(dt);
        stage.nBlits = 0;
        stage.nOver = 0;
        scene.frame(stage);
        if (oops != null && !oops.draw(stage, dt)) oops = null;
        bmp.setPixels(stage.out, 0, Stage.W, 0, 0, Stage.W, Stage.H);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int scale = Math.max(1, Math.min(w / Stage.W, h / Stage.H));
        int dw = Stage.W * scale, dh = Stage.H * scale;
        dst.set((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawBitmap(bmp, src, dst, paint);
        float k = dst.width() / (float) Stage.W;
        c.save();
        for (int i = 0; i < stage.nOver * 4; i += 4) {
            int[] o = stage.over;
            c.clipOutRect(dst.left + o[i] * k, dst.top + o[i + 1] * k, dst.left + (o[i] + o[i + 2]) * k, dst.top + (o[i + 1] + o[i + 3]) * k);
        }
        for (int i = 0; i < stage.nBlits; i++) {
            Stage.Blit b = stage.blits.get(i);
            fineDst.set(dst.left + b.x * k, dst.top + b.y * k, dst.left + (b.x + b.w) * k, dst.top + (b.y + b.h) * k);
            c.drawBitmap(b.b, null, fineDst, b.dim > 0.01f ? dimmed(b.dim) : paint);
        }
        c.restore();
    }

    final android.graphics.RectF fineDst = new android.graphics.RectF();
    final Paint dimPaint = new Paint();
    float dimAt = -1;

    Paint dimmed(float d) {
        if (d != dimAt) {
            dimAt = d;
            float m = 1f - d;
            dimPaint.setFilterBitmap(false);
            dimPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                    m, 0, 0, 0, 0, 0, m, 0, 0, 0, 0, 0, m, 0, 0, 0, 0, 0, 1, 0}));
        }
        return dimPaint;
    }
}
