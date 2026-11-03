package com.quiet.home;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.dreams.DreamService;

import com.quiet.px.Font;
import com.quiet.px.Groove;
import com.quiet.px.Pal;
import com.quiet.px.PxView;
import com.quiet.px.Stage;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The screensaver: the old road with nothing in front of it. The world carries on as it would — the
 * hour, the weather, Pip, the frog, the fireflies. Pip and Mote keep their own day: up and about for
 * a few minutes, then a nap (Mote curled on Pip's back), then up again; at night they sleep, stirring
 * now and then. A small, dim clock drifts a little every minute so nothing burns in.
 *
 * If music is playing it comes all the same: the song and who made it sit under the clock, and if
 * it's Apple Music the party carries on behind (and the TV doesn't fall asleep on the music).
 */
public class Dream extends DreamService implements PxView.Scene {
    Stage stage;
    PxView view;
    Groove groove;
    int drift;
    String song = "", artist = "";
    boolean playing;
    long readAt;
    android.os.PowerManager.WakeLock awake;                  // held while music plays, against the TV's sleep timer

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        stage = new Stage(getPackageName().hashCode() ^ 0x5157);
        SharedPreferences prefs = getSharedPreferences("quiet", MODE_PRIVATE);
        stage.doorOpen = prefs.getBoolean("door", false);
        stage.days = prefs.getInt("days", 0);
        stage.placeDog(150);
        com.quiet.px.World.load(this, stage);                 // Pip as you left him (the screensaver doesn't move him on)
        stage.partyAllowed = !"off".equals(Prefs.get(this, "party"));
        view = new PxView(this, stage, this);
        view.rest = this::resting;
        setContentView(view);
        groove = new Groove(stage);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        com.quiet.px.Weather.refresh(this);
        readSong();
        groove.start();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        groove.stop();
        if (awake != null && awake.isHeld()) awake.release();
        super.onDreamingStopped();
    }

    /** What's playing, if anything (through home's notification listener). */
    void readSong() {
        readAt = System.currentTimeMillis();
        String t = "", a = "", g = "";
        boolean p = false, apple = false;
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, Listener.class));
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getExtras() != null && st.getExtras().containsKey("quiet.winddown"))
                    stage.windDown = st.getExtras().getFloat("quiet.winddown", 0f);
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                MediaMetadata m = c.getMetadata();
                if (m != null) {
                    CharSequence ct = m.getText(MediaMetadata.METADATA_KEY_TITLE), ca = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
                    t = ct == null ? "" : ct.toString();
                    a = ca == null ? "" : ca.toString();
                    String mg = m.getString(MediaMetadata.METADATA_KEY_GENRE);
                    g = mg == null ? "" : mg;
                }
                p = true;
                String pkg = c.getPackageName();
                apple = pkg.equals("com.quiet.listen") || pkg.equals("com.apple.android.music");
                break;
            }
        } catch (SecurityException ignored) {
        }
        song = t;
        artist = a;
        playing = p;
        stage.music = p && apple;
        stage.genre = g;
        if (awake == null) {
            @SuppressWarnings("deprecation")
            int dim = android.os.PowerManager.SCREEN_DIM_WAKE_LOCK;
            awake = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(dim, "quiet:music-dream");
            awake.setReferenceCounted(false);
        }
        if (p && !awake.isHeld()) awake.acquire();            // the TV's sleep timer doesn't cut the music off
        else if (!p && awake.isHeld()) awake.release();
    }

    // ---------------------------------------------------------------- their own day

    boolean napping;
    long switchAt;                                            // uptime ms of the next wake or nap
    long hopAt;                                               // when Mote next flits somewhere
    float hopX = 60, hopY = 120;

    boolean resting() {
        long now = android.os.SystemClock.uptimeMillis();
        if (switchAt == 0) {                                   // just come on: about for a bit first (by day)
            napping = false;
            switchAt = now + (night() ? 60_000 : minutes(1.5f, 3f));
        }
        if (now >= switchAt) {
            napping = !napping;
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (night()) switchAt = now + (napping ? minutes(25, 45) : minutes(1.5f, 3));   // a stir, then back to sleep
            else if (napping) switchAt = now + (h >= 13 && h < 16 ? minutes(3, 6) : minutes(2, 4));   // longer after lunch
            else switchAt = now + minutes(4, 8);
        }
        return napping;
    }

    boolean night() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    long minutes(float lo, float hi) {
        return (long) ((lo + stage.rnd.nextFloat() * (hi - lo)) * 60_000);
    }

    /** Awake, Mote doesn't hover in one place: it drifts between whatever's worth being near. */
    void flit(Stage s) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < hopAt) return;
        hopAt = now + 6_000 + s.rnd.nextInt(9_000);
        int pick = s.rnd.nextInt(10);
        float pip = com.quiet.px.Ties.pipAt(s);
        if (pick < 3 && !Float.isNaN(pip)) {                   // with Pip
            hopX = pip + (s.rnd.nextBoolean() ? 14 : -14);
            hopY = Stage.WATER_Y - 24;
        } else if (pick < 5) {                                 // low over the stream
            hopX = 110 + s.rnd.nextInt(180);
            hopY = Stage.WATER_Y - 9;
        } else if (pick < 7) {                                 // among the big mushrooms
            hopX = 240 + s.rnd.nextInt(50);
            hopY = Stage.WATER_Y - 28;
        } else if (pick < 8 && !Float.isNaN(com.quiet.px.Ties.frogAt(s))) {   // to see the frog
            hopX = com.quiet.px.Ties.frogAt(s) + 10;
            hopY = Stage.WATER_Y - 14;
        } else {                                               // up into the road's light
            hopX = 190 + s.rnd.nextInt(60);
            hopY = 70 + s.rnd.nextInt(40);
        }
        hopX = Math.max(12, Math.min(308, hopX));
    }

    static String fit(String s, int w) {
        if (Font.microWidth(s, 1) <= w) return s;
        while (s.length() > 1 && Font.microWidth(s + "..", 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "..";
    }

    @Override
    public void frame(Stage s) {
        if (System.currentTimeMillis() - readAt > 2000) readSong();
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        s.rosetta = false;                                     // (when, is private in this shared copy)
        drift = (h * 60 + m) % 7;
        s.camera(0.5f);
        s.resolve();
        s.decor();
        String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date());
        int x = 14 + drift * 2, y = 12 + (drift % 3) * 2;
        int col = Pal.mix(Pal.SOFT, Pal.VOID, s.darkness() * 0.35f);
        s.text(time, x, y, 3, col);
        if (playing && !song.isEmpty()) {                     // ♪ the song, and who made it beneath
            int bob = ((int) (s.t * 2.5f) & 1) == 0 ? -1 : 0;
            s.small("♪", x + 1, y + 27 + bob, 1, Pal.MINT);
            s.small(fit(Font.upper(song), 210), x + 8, y + 27, 1, Pal.mix(Pal.SOFT, Pal.VOID, s.darkness() * 0.3f));
            if (!artist.isEmpty())
                s.small(fit(Font.upper(artist), 210), x + 8, y + 35, 1, Pal.mix(Pal.DIM, Pal.VOID, s.darkness() * 0.3f));
        }
        if (s.partyOn()) s.mote.perch(70, 110);               // (no sleeping at a party)
        else if (s.mote.asleep) s.mote.perch(60, 120);        // it curls up on Pip's back from here
        else {
            flit(s);
            s.mote.perch(hopX, hopY);
        }
        s.actors();
        s.pool();
        s.reveal();
    }
}
