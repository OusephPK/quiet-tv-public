package com.quiet.home;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets the home screen read what's playing (media sessions) from any app, and keeps one thing
 * playing at a time.
 *
 * Listen's music comes from Apple's player inside a WebView, and YouTube on this TV doesn't make
 * it stop (they played over each other). Android keeps this listener running all the time, so it
 * watches every app's player: when YouTube, Netflix, Stremio or anything else starts playing,
 * Listen pauses (and stays paused); when you start music in Listen, whatever else was playing
 * pauses. Only pairs involving Listen are touched; other apps sort themselves out as usual.
 *
 * Granted once over adb: cmd notification allow_listener com.quiet.home/com.quiet.home.Listener
 */
public class Listener extends NotificationListenerService {

    static final String LISTEN = "com.quiet.listen";

    final Handler ui = new Handler(Looper.getMainLooper());
    final Map<MediaSession.Token, Watch> watched = new HashMap<>();
    MediaSessionManager sessions;
    boolean settled;                      // the sessions already there when we connect start nothing
    final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    final class Watch extends MediaController.Callback {
        final MediaController c;
        int state;

        Watch(MediaController c) {
            this.c = c;
            state = stateOf(c.getPlaybackState());
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState st) {
            History.note(Listener.this, c);                    // what plays, and where it's left, in every app
            int now = stateOf(st);
            boolean started = now == PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PLAYING;
            state = now;
            if (started) giveWay(this);
        }

        @Override
        public void onMetadataChanged(android.media.MediaMetadata md) {
            History.note(Listener.this, c);
        }

        @Override
        public void onSessionDestroyed() {
            forget(c.getSessionToken());
        }
    }

    static int stateOf(PlaybackState st) {
        return st == null ? PlaybackState.STATE_NONE : st.getState();
    }

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, Listener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, ui);
            settled = false;
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w("Quiet", "listener: no access to media sessions");
        }
        settled = true;
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Watch w : watched.values()) w.c.unregisterCallback(w);
        watched.clear();
    }

    void track(List<MediaController> list) {
        if (list == null) return;
        Set<MediaSession.Token> here = new HashSet<>();
        for (MediaController c : list) {
            MediaSession.Token t = c.getSessionToken();
            here.add(t);
            if (watched.containsKey(t)) continue;
            Watch w = new Watch(c);
            watched.put(t, w);
            c.registerCallback(w, ui);
            History.note(this, c);                            // already playing when we started listening
            if (settled && w.state == PlaybackState.STATE_PLAYING) giveWay(w);   // arrived already playing
        }
        for (Iterator<Map.Entry<MediaSession.Token, Watch>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MediaSession.Token, Watch> e = it.next();
            if (!here.contains(e.getKey())) {
                e.getValue().c.unregisterCallback(e.getValue());
                it.remove();
            }
        }
    }

    void forget(MediaSession.Token t) {
        Watch w = watched.remove(t);
        if (w != null) w.c.unregisterCallback(w);
    }

    /** Someone started playing: if Listen is on either side, the other one pauses. */
    void giveWay(Watch starter) {
        boolean listenStarted = LISTEN.equals(starter.c.getPackageName());
        for (Watch w : watched.values()) {
            if (w == starter || w.state != PlaybackState.STATE_PLAYING) continue;
            boolean isListen = LISTEN.equals(w.c.getPackageName());
            if (isListen == listenStarted) continue;          // only Listen against the others
            Log.i("Quiet", "one at a time: " + starter.c.getPackageName() + " plays, " + w.c.getPackageName() + " pauses");
            try {
                w.c.getTransportControls().pause();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
