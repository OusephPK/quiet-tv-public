package com.quiet.px;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/**
 * Base for every Quiet screen: the well, one Menu, D-pad handling with hold-to-act.
 * Subclasses draw their own words over the world and decide what OK and hold-OK do.
 */
public abstract class PxActivity extends Activity implements PxView.Scene {

    protected Stage stage;
    protected PxView view;
    protected Menu menu;
    /** System controls, top-right. Null on screens that have none. */
    protected IconBar bar;
    protected boolean inBar;
    /** Optional soundtrack; screens that have one set it in onCreate. */
    protected Ambience audio;
    String lastCarve;

    /** OK: a tap selects, a hold (by the clock, not the remote's repeats) does the row's other thing. */
    final Hold ok = new Hold(new Hold.Target() {
        @Override
        public void tap() {
            if (menu.focused() == null) return;
            stage.mote.cheer();
            if (audio != null) audio.pick();
            select(menu.focused());
            view.now();
        }

        @Override
        public void hold() {
            if (menu.focused() != null) PxActivity.this.hold(menu.focused());
            view.now();
        }
    });

    /** Where the list sits: x, top, height (in art pixels). */
    protected abstract Menu makeMenu();

    /** Lights specific to this screen; runs before the stone is lit. */
    protected void lights(Stage s) {}

    /** Words specific to this screen; runs after the stone is lit. */
    protected abstract void draw(Stage s);

    /** Anything that must sit in front of the fireflies (e.g. a QR code that has to stay scannable). */
    protected void overlay(Stage s) {}

    /** The hint line just above the bank; drawn after the water so it isn't reflected in it. */
    protected String hint() {
        return "";
    }

    /** The hint's colour (Faint by default; Amber when it's about something in progress). */
    protected int hintColor() {
        return Pal.FAINT;
    }

    /** What to carve into the tunnel wall for the selected row ("" for nothing). */
    protected String carving() {
        return "";
    }

    /** The page's search screen is showing (the voice then types what it hears into it). */
    protected boolean searching() {
        return false;
    }

    /** Words for the page's search, from the voice or the phone: put them in and search ("" clears it). */
    protected void typedIn(String words) {
    }

    /** What's in the page's search box now. */
    protected String searchText() {
        return "";
    }

    final Box box = new Box(this, new Box.Page() {
        @Override
        public boolean searching() {
            return PxActivity.this.searching();
        }

        @Override
        public String query() {
            return PxActivity.this.searchText();
        }

        @Override
        public void typed(String words) {
            PxActivity.this.typedIn(words);
            view.poke();
        }
    });

    protected abstract void select(Menu.Item item);

    protected void hold(Menu.Item item) {}

    /** OK on an icon in the system bar. */
    protected void selectIcon(IconBar.Icon icon) {}

    protected void enterBar(boolean on) {
        inBar = on && bar != null && !bar.icons.isEmpty();
        menu.active = !inBar;
    }

    protected void moved() {}

    /** Where Mote waits when there's nothing to select. */
    protected float[] restingPlace() {
        return new float[]{menu.perchX(), menu.top + 3};
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Oops.install(this);
        stage = new Stage(getPackageName().hashCode());
        menu = makeMenu();
        view = new PxView(this, stage, this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Oops.resumed(this);
        World.load(this, stage);                             // everyone where the last page left them
        Weather.refresh(this);
        view.poke();
        view.start();
        if (audio != null) audio.start();
        try {
            registerReceiver(voiceRx, new android.content.IntentFilter("com.quiet.VOICE"), "com.quiet.permission.PREFS", null);
        } catch (Exception ignored) {
        }
        box.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Oops.paused(this);
        try {
            unregisterReceiver(voiceRx);
        } catch (Exception ignored) {
        }
        box.pause();
        ok.cancel();
        World.save(this, stage);
        if (audio != null) audio.stop();
    }

    @Override
    protected void onStop() {
        super.onStop();
        view.stop();          // only once hidden: while the next app loads, this page keeps living
    }

    /** Quiet's voice (home) says what it's doing; the world reacts. */
    final android.content.BroadcastReceiver voiceRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            stage.voice(i.getIntExtra("state", 0));
            view.poke();
        }
    };

    long musicAt;
    volatile boolean asking;
    /** One quiet worker for those trips, instead of a new thread every two seconds. */
    static final java.util.concurrent.Executor ASK = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "music");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void frame(Stage s) {
        box.check();
        // Pages other than home (and Listen, which plays it) hear about Apple Music from home.
        String me = getPackageName();
        if (!me.equals("com.quiet.home") && !me.equals("com.quiet.listen") && android.os.SystemClock.uptimeMillis() - musicAt > 2000) {
            musicAt = android.os.SystemClock.uptimeMillis();
            if (!asking) {                                   // off the drawing thread: it's a trip to another app
                asking = true;
                ASK.execute(() -> {
                    World.music(this, s);
                    asking = false;
                });
            }
        }
        String c = carving();
        if (!c.equals(lastCarve)) {
            lastCarve = c;
            // Big runes if they fit inside the arch, smaller ones if not.
            s.carve(c, 298, 150, Font.width(c, 3, 1) <= 108 ? 3 : 2);
        }
        if (!c.isEmpty()) s.light(250, 138, 80, 0.3f, false);    // the runes' glow on the tunnel wall
        s.camera(menu.fraction());
        if (inBar && (bar == null || bar.icons.isEmpty())) enterBar(false);
        menu.lights(s);
        if (inBar) bar.lights(s);
        lights(s);
        s.resolve();
        s.decor();
        draw(s);
        menu.draw(s);
        if (bar != null) bar.draw(s, inBar);
        if (s.voice == 1 || s.voice == 2) s.mote.perch(298, 46);    // up by the voice's card, listening
        else if (inBar) s.mote.perch(bar.perchX(), bar.perchY());
        else if (menu.focused() != null) s.mote.perch(menu.perchX(), menu.perchY());
        else {
            float[] rest = restingPlace();
            s.mote.perch(rest[0], rest[1]);
        }
        String h = inBar ? "" : hint();
        if (!h.isEmpty()) s.markBand(158);                // the hint sits behind Pip and co., unreflected
        s.actors();
        overlay(s);
        s.pool();
        if (!h.isEmpty()) s.smallBehind(h, 26, 160, 1, hintColor());
        s.reveal();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) view.poke();
        if (down && stage.wakeT >= 0) {           // a key during the wake-up: skip it
            stage.wakeSkip();
            ok.cancel();
            return true;
        }
        if (inBar) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) enterBar(false);
                view.now();
                return true;
            }
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && bar.move(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) && audio != null) audio.step(bar.focus + 3);
                    view.now();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!down && bar.focused() != null) {
                        stage.mote.cheer();
                        if (audio != null) audio.pick();
                        selectIcon(bar.focused());
                    }
                    view.now();
                    return true;
            }
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && menu.move(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)) {
                    if (audio != null) audio.step(menu.focus);
                    moved();
                    view.now();
                } else if (down && k == KeyEvent.KEYCODE_DPAD_UP && bar != null) {
                    enterBar(true);                       // past the top of the list: the system bar
                    view.now();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                ok.key(e);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    stage.mote.look(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    view.now();
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
