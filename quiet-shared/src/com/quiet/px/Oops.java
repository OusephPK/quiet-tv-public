package com.quiet.px;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * When something breaks. Instead of Android's grey "has stopped" box, the page you were on comes
 * straight back, and says so in the world for a few seconds ("SOMETHING WENT WRONG · STARTED AGAIN").
 *
 *  - Only while you're looking at it: a page that breaks in the background just goes quietly
 *    (it never jumps in front of what you're watching).
 *  - Twice in half a minute: it doesn't try again; you're taken home instead.
 *  - The cause is written to the log (logcat, tag "Quiet") for us to fix.
 */
public final class Oops {

    static volatile boolean installed;
    static volatile Activity front;          // the Quiet page you're looking at, if any
    static final long AGAIN_MS = 30_000, SHOW_MS = 7_000;

    private Oops() {}

    /** Every Quiet page calls this in onCreate. */
    public static void install(Activity a) {
        if (installed) return;
        installed = true;
        Context app = a.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Log.e("Quiet", "broke in " + app.getPackageName() + " (" + t.getName() + ")", e);
                Activity f = front;
                SharedPreferences p = app.getSharedPreferences("oops", 0);
                long now = System.currentTimeMillis();
                boolean again = now - p.getLong("at", 0) < AGAIN_MS;
                p.edit().putLong("at", now).putBoolean("show", f != null && !again).commit();
                if (f != null) {
                    Intent i;
                    if (again) {
                        i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    } else {
                        i = new Intent(app, f.getClass());
                        Intent was = f.getIntent();
                        if (was != null && was.getAction() != null) i.setAction(was.getAction());
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    app.startActivity(i);             // queued now, while we're still in front
                }
            } catch (Throwable ignored) {
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }

    public static void resumed(Activity a) {
        front = a;
        // Test: setprop debug.quiet.crash <settings|watch|...> breaks that page once, 3 s after it opens.
        String test = Life.debugString("debug.quiet.crash");
        String me = a.getPackageName();
        if (!test.isEmpty() && me.endsWith("." + test)
                && System.currentTimeMillis() - a.getSharedPreferences("oops", 0).getLong("at", 0) > AGAIN_MS)
            new android.os.Handler(a.getMainLooper()).postDelayed(() -> {
                throw new IllegalStateException("test crash (debug.quiet.crash)");
            }, 3000);
    }

    public static void paused(Activity a) { if (front == a) front = null; }

    // ---------------------------------------------------------------- the note in the world

    long until, since;
    float t;

    /** Read once, when a page's view is made: did the last one just break? */
    static Oops check(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("oops", 0);
        if (!p.getBoolean("show", false)) return null;
        p.edit().putBoolean("show", false).apply();
        Oops o = new Oops();
        o.since = SystemClock.uptimeMillis();
        o.until = o.since + SHOW_MS;
        return o;
    }

    /** A key pressed after the first moment puts it away. */
    boolean dismissable() {
        return SystemClock.uptimeMillis() - since > 1200;
    }

    /** False once it's had its few seconds. A small stone slab, centred, over everything. */
    boolean draw(Stage s, float dt) {
        if (SystemClock.uptimeMillis() > until) return false;
        t += dt;
        String a = "SOMETHING WENT WRONG", b = "IT'S BEEN STARTED AGAIN";
        int w = Math.max(Font.width(a, 1, 1), Font.microWidth(b, 2)) + 24, h = 29;
        int x = (Stage.W - w) / 2, y = 74;
        y += t < 0.25f ? Math.round((0.25f - t) * 16) : 0;        // settles into place
        s.over(x, y, w, h + 1);                                   // over any cover or poster too
        s.rect(x, y, w, h, Pal.STONE[2]);
        s.rect(x + 1, y, w - 2, 1, Pal.STONE[4]);                 // worn top edge
        s.rect(x + 1, y + 1, w - 2, 1, Pal.STONE[3]);
        s.rect(x, y + h, w, 1, Pal.VOID);                         // its shadow
        s.px(x, y, Pal.VOID);
        s.px(x + w - 1, y, Pal.VOID);
        s.rect(x + w / 2 - 6, y + 5, 12, 1, Pal.AMBER);           // a small amber mark: something to know
        s.text(a, x + (w - Font.width(a, 1, 1)) / 2, y + 9, 1, Pal.TEXT);
        s.small(b, x + (w - Font.microWidth(b, 2)) / 2, y + 20, 2, Pal.DIM);
        return true;
    }
}
