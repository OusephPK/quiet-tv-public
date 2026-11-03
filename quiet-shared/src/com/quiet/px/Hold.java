package com.quiet.px;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * Tells a tap of OK from a hold. A timer decides, not the key repeats, because not every remote
 * repeats a held key (and the ones that do keep repeating long after the hold has done its job).
 * Feed it every OK event; it calls tap() on a quick release, or hold() once while still held.
 */
public final class Hold {
    public interface Target {
        void tap();

        void hold();
    }

    static final long MS = 500;

    final Handler handler = new Handler(Looper.getMainLooper());
    final Target target;
    boolean down, fired;
    final Runnable fire = new Runnable() {
        @Override
        public void run() {
            if (down && !fired) {
                fired = true;
                target.hold();
            }
        }
    };

    public Hold(Target target) {
        this.target = target;
    }

    public void key(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                down = true;
                fired = false;
                handler.removeCallbacks(fire);
                handler.postDelayed(fire, MS);
            } else if (down && e.getEventTime() - e.getDownTime() >= MS) {
                fire.run();                                 // a repeat can be a touch quicker than the timer
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(fire);
            boolean was = down && !fired && !e.isCanceled();
            down = false;
            if (was) target.tap();
        }
    }

    /** Forget a press in progress (its release does nothing). */
    public void cancel() {
        handler.removeCallbacks(fire);
        down = false;
    }
}
