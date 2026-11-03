package com.quiet.home;

import com.quiet.px.Box;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Quiet's voice, over every app.
 *
 * Press the remote's voice button (or say the wake word) and the card rises at the foot of the
 * screen: Mote listens through the TV's own microphone (the TV's sound dips meanwhile), the words
 * go to the cloud once (Brain), and one thing happens: an app opens, music plays in Listen, a title
 * opens in Watch, YouTube or Netflix searches, playback or volume changes, BACK/HOME, words are
 * typed into the focused search box, the input changes, or the TV goes to sleep. Common short
 * commands ("pause", "ruko", "next", "awaaz badhao") are understood on the TV without the second trip.
 *
 * It's an accessibility service because that's what may see the remote's keys in every app, read
 * which app is in front and its focused text box, press BACK/HOME and draw over everything. Home
 * enables it itself (WRITE_SECURE_SETTINGS). The OpenAI key arrives through Drop and lives only in
 * home's private preferences.
 */
public class Voice extends AccessibilityService {

    static final String TAG = "QuietVoice";
    static volatile Voice self;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService work = worker("voice");

    /** One background thread for a kind of work; once the voice has stopped, anything more is let go (never a crash). */
    static ExecutorService worker(String name) {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> quietThread(r, name),
                (r, ex) -> Log.i(TAG, "the voice has stopped: " + name + " work let go"));
    }

    /** A thread whose failure is logged, never fatal: the voice must not take the home screen down. */
    static Thread quietThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, "trouble in " + th.getName() + ": " + ex, ex));
        return t;
    }
    WindowManager wm;
    Card card;
    boolean shown;
    volatile boolean busy;
    volatile Ear ear;
    volatile String front = "";
    volatile int asks;                 // counts asks: a new one ends a longer ask still going (next)
    int duckedFrom = -1;
    /** The last few asks (when, what was said, what happened), so follow-ups make sense. */
    final java.util.ArrayDeque<String[]> recent = new java.util.ArrayDeque<>();
    Runnable tvOff;
    Mic mic;                       // the wake word's ear, when it's on
    Inbox inbox;                   // the door for the phone app
    String wakeText = "";          // what the wake word sounds like in words ("hey pip")

    /** Tells every Quiet page what the voice is doing, so the world can react (Pip looks, Mote listens). */
    void tell(int state) {
        try {
            sendBroadcast(new Intent("com.quiet.VOICE").putExtra("state", state), "com.quiet.permission.PREFS");
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- setup

    /** Called by home on start: make sure the voice is switched on (it needs no setup screen). */
    static void ensureEnabled(Context c) {
        try {
            ContentResolver r = c.getContentResolver();
            String me = new ComponentName(c, Voice.class).flattenToString(), shortMe = new ComponentName(c, Voice.class).flattenToShortString();
            String on = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            on = on == null ? "" : on;
            String all = ":" + on + ":";
            if (!all.contains(":" + me + ":") && !all.contains(":" + shortMe + ":"))
                Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on.isEmpty() ? me : on + ":" + me);
            if (Settings.Secure.getInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1)
                Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            // And the TV's assistant: what the remote's Assistant button (and "assist") starts.
            String ask = new ComponentName(c, Ask.class).flattenToString();
            if (!ask.equals(Settings.Secure.getString(r, "assistant"))) Settings.Secure.putString(r, "assistant", ask);
        } catch (Exception e) {
            Log.w(TAG, "can't switch the voice on: " + e);
        }
    }

    @Override
    protected void onServiceConnected() {
        self = this;
        lastTest = prop("debug.quiet.ask");                    // a test left from before a restart: done already
        ui.postDelayed(new Runnable() {                        // test asks come by themselves (no screen change needed)
            @Override
            public void run() {
                if (front.isEmpty()) {                         // just started: what's in front, before any screen change
                    String p = activePackage();
                    if (p != null && !p.isEmpty() && !p.equals("com.android.systemui")) front = p;
                }
                if (self != Voice.this) return;                // stopped: a newer copy does this now
                testAsk();
                ui.postDelayed(this, 2000);
            }
        }, 4000);
        wm = getSystemService(WindowManager.class);
        int left = Prefs.prefs(this).getInt("duckedFrom", -1);
        if (left > 0) {                                        // the sound was dipped when we last stopped: put it back
            getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, left, 0);
            Prefs.prefs(this).edit().remove("duckedFrom").apply();
            Log.i(TAG, "put the volume back to " + left);
        }
        try {
            android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
            sf.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenRx, sf);
        } catch (Exception ignored) {
        }
        wakeText = Prefs.prefs(this).getString("wakeText", "");
        // The TV's own microphone hears a sofa voice too faintly for a dependable wake word: off
        // unless a USB microphone is plugged in or it's switched back on in Settings (once, here).
        // Tried three ways (on-TV matching, lower thresholds, cloud confirmation): this TV's microphone
        // hears a sofa voice too faintly for even the cloud to make out a two-word phrase. Off, once,
        // unless a USB microphone is plugged in; Settings can still switch it on.
        if (Prefs.prefs(this).getInt("wakeV", 0) == 4 && !Prefs.prefs(this).getString("wakeModel", "").isEmpty()) {
            Prefs.prefs(this).edit().putInt("wakeV", 5).putString("wakeOn", "on").putString("wakeText", "hello quiet").apply();   // one more try
        }
        if (Prefs.prefs(this).getInt("wakeV", 0) < 4) {
            android.media.AudioDeviceInfo best = Ear.bestMic(this);
            boolean usb = best != null && best.getType() != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
            Prefs.prefs(this).edit().putInt("wakeV", 4).putString("wakeOn", usb ? Prefs.prefs(this).getString("wakeOn", "") : "off").apply();
        }
        if (Prefs.prefs(this).getInt("wakeV", 0) < 2) {
            Prefs.prefs(this).edit().putInt("wakeV", 2)
                    .putString("wakeOn", Ear.bestMic(this) != null && Ear.bestMic(this).getType() != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
                            ? Prefs.prefs(this).getString("wakeOn", "") : "off").apply();
        }
        inbox = new Inbox(this);
        inbox.start();
        ui.postDelayed(stillWatching, 5 * 60_000);
        screenOn = getSystemService(android.os.PowerManager.class).isInteractive();   // (started with the screen off: on comes later)
        if (screenOn && "on".equals(Prefs.prefs(this).getString("wakeOn", ""))) startWake();
        try {
            registerReceiver(wakeRx, new android.content.IntentFilter("com.quiet.WAKE"), "com.quiet.permission.PREFS", null);
            registerReceiver(shareRx, new android.content.IntentFilter("com.quiet.SHARE"), "com.quiet.permission.PREFS", null);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "ready" + (key().isEmpty() ? " (no key yet: send one with Drop)" : ""));
    }

    @Override
    public void onDestroy() {
        if (self == this) self = null;
        // Everything this copy had scheduled ends with it (3 Nov: Android reconnected the voice without restarting
        // home; the old copy's test check ran on into its stopped worker and took home down).
        ui.removeCallbacksAndMessages(null);
        stopWake();
        if (inbox != null) inbox.stop();
        try {
            unregisterReceiver(wakeRx);
            unregisterReceiver(shareRx);
            unregisterReceiver(screenRx);
        } catch (Exception ignored) {
        }
        unduck();
        hideCard();
        work.shutdownNow();
        shellQ.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            CharSequence cls = e.getClassName();
            // Our own card (an overlay) isn't an app coming to the front: only home's activity counts.
            boolean ourOverlay = p.equals(getPackageName()) && (cls == null || !cls.toString().endsWith(".Home"));
            // Only apps count as "in front": the TV's own services flash windows too (3 Nov: com.mediatek.tv.service
            // took the front from Apple TV, and its search waited for Apple TV forever).
            if (!p.equals("com.android.systemui") && !p.equals("android") && !ourOverlay && (p.equals(getPackageName()) || launchable(p))) {
                front = p;
                String[] t = lastTyped;
                if (t != null && !t[0].equals(p)) lastTyped = null;
            }
            testAsk();
        }
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Test switch: setprop debug.quiet.ask open_netflix (underscores for spaces), then change
     * what's on screen: that sentence goes through the same steps as speech, minus the microphone.
     * debug.quiet.ask listen starts real listening. Cleared by the request after.
     */
    String lastTest = "";

    void testAsk() {
        String v = "";
        try {
            v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.quiet.ask");
        } catch (Throwable ignored) {
        }
        if (v == null || v.isEmpty()) {                       // cleared: the same ask may be tried again
            lastTest = "";
            return;
        }
        if (v.equals(lastTest) || busy) return;
        lastTest = v;
        if (v.equals("listen")) {
            ui.post(() -> begin(false));
            return;
        }
        if (v.equals("teach")) {
            ui.post(this::teach);
            return;
        }
        if (v.startsWith("see")) {                             // what's on screen, described into the log (testing)
            work.execute(() -> {
                byte[] shot = screenshotNow();
                String lit = focusedWords();
                try {
                    org.json.JSONObject d = shot == null ? new org.json.JSONObject() : new Brain(key()).glance(shot,
                            "Describe it for someone who can't see it: {\"app\", \"screen\": what kind of screen, \"focused\": "
                                    + "the item that has the focus exactly as shown, \"rows\": the visible rows with their titles in "
                                    + "order, \"search\": any search box and what's typed in it, \"other\": anything else notable}", 900);
                    Log.i(TAG, "see: front " + front + ", readable focus '" + lit + "', " + visible().size() + " readable; " + d);
                } catch (Exception e) {
                    Log.w(TAG, "see failed: " + e);
                }
            });
            return;
        }
        if (v.startsWith("node_")) {                           // one readable thing, and the ones around it (testing)
            AccessibilityNodeInfo n = find(v.substring(5).replace('_', ' '));
            for (int i = 0; n != null && i < 5; i++, n = n.getParent()) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                Log.i(TAG, "node " + i + ": " + n.getClassName() + " '" + words(n) + "' " + r + " click " + n.isClickable()
                        + " focusable " + n.isFocusable() + " focused " + n.isFocused() + " visible " + n.isVisibleToUser()
                        + " actions " + n.getActionList().size() + " id " + n.getViewIdResourceName());
            }
            return;
        }
        if (v.startsWith("bench")) {                           // hard asks through the agent, decided but not done (testing)
            String[] models = v.length() > 6 ? v.substring(6).split(",") : new String[]{Agent.model(this)};
            work.execute(() -> {
                String[] asks = {"can you play me the kind of music i would like", "i don't like hindi music, can you play something else",
                        "can you play some meal time videos", "put something on", "what was i listening to?",
                        "play something relaxing for dinner", "what's the latest season of the bear and where can i watch it",
                        "find me a good thriller to watch tonight", "play the last song i listened to", "who won the cricket match yesterday?",
                        "play that song from the new shah rukh khan movie", "something to fall asleep to"};
                String ctx = context(false);
                for (String m : models)
                    for (String q : asks) {
                        long t0 = System.currentTimeMillis();
                        try {
                            Agent ag = new Agent(this, new Brain(key()), m);
                            ag.dry = true;
                            JSONObject a = ag.run(q, ctx, what -> { });
                            Log.i(TAG, "bench " + m + " " + (System.currentTimeMillis() - t0) + " ms | " + q + " | " + brief(a));
                        } catch (Exception e) {
                            Log.w(TAG, "bench " + m + " " + (System.currentTimeMillis() - t0) + " ms | " + q + " | FAILED " + e);
                        }
                    }
                Log.i(TAG, "bench done");
            });
            return;
        }
        if (v.startsWith("dry_")) {                            // one ask decided, not done (testing)
            String q = v.substring(4).replace('_', ' ');
            work.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    boolean typing = boxHere();
                    Agent ag = new Agent(this, new Brain(key()), Agent.model(this));
                    ag.dry = true;
                    JSONObject a = quickNow(q);
                    if (a == null) a = fast(q);
                    if (a == null) a = ag.run(q, context(typing), what -> { });
                    Log.i(TAG, "dry " + (System.currentTimeMillis() - t0) + " ms | " + q + " | " + brief(a));
                } catch (Exception e) {
                    Log.w(TAG, "dry | " + q + " | FAILED " + e);
                }
            });
            return;
        }
        if (v.startsWith("forget_")) {                         // a taste note gone (testing)
            Log.i(TAG, Taste.forget(this, v.substring(7).replace('_', ' ')));
            return;
        }
        if (v.startsWith("models")) {                          // which models the key may use, into the log (testing)
            work.execute(() -> {
                try {
                    java.net.HttpURLConnection c = new Brain(key()).open("models", "GET");
                    org.json.JSONArray d = new JSONObject(new String(Brain.drain(c), java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < d.length(); i++) {
                        String id = d.getJSONObject(i).optString("id");
                        if (id.matches("(gpt|o\\d|chatgpt).*") && !id.matches(".*(audio|realtime|tts|transcribe|image|search-preview|instruct).*"))
                            b.append(id).append(' ');
                    }
                    Log.i(TAG, "models: " + b);
                } catch (Exception e) {
                    Log.w(TAG, "models: " + e);
                }
            });
            return;
        }
        if (v.startsWith("context")) {                         // what the AI is told, into the log (testing)
            work.execute(() -> {
                for (String l : context(false).split("\n")) Log.i(TAG, "context: " + l);
            });
            return;
        }
        if (v.startsWith("words")) {                           // what the voice can read in front, no cloud (testing)
            long w0 = android.os.SystemClock.uptimeMillis();
            visible();
            Log.i(TAG, "words: read in " + (android.os.SystemClock.uptimeMillis() - w0) + " ms");
            Log.i(TAG, "words: front " + front + ", active " + activePackage() + ", focus '" + focusedWords() + "', box "
                    + (focusedText() != null) + ", keyboard " + keyboardUp() + ", playing here " + playingIn(front) + "; " + onScreen());
            return;
        }
        if (v.equals("history")) {                             // the diary of asks, into the log
            for (String l : Prefs.prefs(this).getString("voiceDiary", "").split("\n")) Log.i(TAG, "diary: " + l);
            return;
        }
        if (v.equals("hints")) {                               // what the transcription is primed with
            work.execute(() -> Log.i(TAG, "hints: " + hints()));
            return;
        }
        if (v.startsWith("share_")) {                          // as if shared from the phone
            String t = v.substring(6).replace('_', ' ');
            ui.post(() -> shared(t, ""));
            return;
        }
        String said = v.replace('_', ' ');
        String key = key();
        Log.i(TAG, "test ask: " + said + (key.isEmpty() ? " (no key)" : ""));
        if (key.isEmpty()) return;
        busy = true;
        asks++;
        ui.post(() -> {
            showCard();
            card.listening(false);
            card.heard(said);
            tell(2);
        });
        work.execute(() -> {
            try {
                Thread.sleep(1500);                            // the app that just came up settles, as before anyone speaks
                long t0 = System.currentTimeMillis();
                boolean typing = boxHere();
                JSONObject act = decision(new Brain(key), said, typing, () -> context(typing));
                Log.i(TAG, "test decided in " + (System.currentTimeMillis() - t0) + " ms: " + act);
                JSONObject a = act;
                ui.post(() -> carryOut(said, a, 1));
            } catch (Exception ex) {
                Log.w(TAG, "test failed: " + ex);
                ui.post(() -> card.fail(String.valueOf(ex.getMessage())));
            } finally {
                busy = false;
            }
        });
    }

    String key() {
        return Prefs.prefs(this).getString("voiceKey", "");
    }

    // ---------------------------------------------------------------- the button

    static boolean trigger(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_ASSIST || k == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        lastKey = android.os.SystemClock.uptimeMillis();
        if (e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) humanKeyAt = lastKey;   // not our own typing
        if (asking) {                                         // "still watching?": yes
            if (down) ui.post(this::stillHere);
            return true;
        }
        if (trigger(k)) {
            if (down && e.getRepeatCount() == 0) {
                Log.i(TAG, "voice button (" + KeyEvent.keyCodeToString(k) + ")");
                ui.post(this::pressed);
            }
            return true;                                      // ours, not Google's
        }
        if (choosing() && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK)) {   // the card's list has the remote
            if (down && e.getRepeatCount() == 0) {
                Log.i(TAG, "card list: key " + KeyEvent.keyCodeToString(k));
                if (k == KeyEvent.KEYCODE_BACK) ui.post(() -> {
                    choices = null;
                    card.dismiss();
                });
                else if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) ui.post(() -> card.move(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
                else ui.post(() -> pick(card.sel));
            }
            return true;
        }
        if (busy && k == KeyEvent.KEYCODE_BACK) {             // BACK while listening: never mind
            if (!down) ui.post(this::cancel);
            return true;
        }
        if (down && e.getRepeatCount() == 0 && !common(k))
            Log.i(TAG, "key " + k + " (" + KeyEvent.keyCodeToString(k) + ") scan " + e.getScanCode() + " from " + e.getDevice());
        return false;
    }

    /** The list on the card, if one is showing: what each choice does (an ask), and what it says. */
    volatile java.util.List<String> choices, choiceLabels;
    volatile long listAt;                                      // when a list last went up on the card

    boolean choosing() {
        return choices != null && card != null && card.list && card.running && !card.leaving;
    }

    /** One of the card's choices: done as if it had been asked. */
    void pick(int i) {
        java.util.List<String> c = choices;
        choices = null;
        if (c == null || i < 0 || i >= c.size()) return;
        card.list = false;
        asked(c.get(i));
    }

    /** A sentence, done as if it had been said (quick, fast, then the AI). */
    void asked(String said) {
        String key = key();
        if (key.isEmpty() || said.trim().isEmpty()) return;
        asks++;
        showCard();
        card.listening(false);
        card.heard(said);
        tell(2);
        work.execute(() -> {
            try {
                boolean typing = boxHere();
                JSONObject act = decision(new Brain(key), said, typing, () -> context(typing));
                JSONObject a = act;
                ui.post(() -> carryOut(said, a, 1));
            } catch (Exception ex) {
                Log.w(TAG, "asked failed: " + ex);
                ui.post(() -> card.fail("COULDN'T REACH THE CLOUD"));
            }
        });
    }

    static boolean common(int k) {
        return (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER) || k == KeyEvent.KEYCODE_BACK
                || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_VOLUME_UP
                || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    void pressed() {
        Log.i(TAG, "voice: start (busy " + busy + ", wake ear " + (mic != null && mic.running) + ")");
        Ear e = ear;
        if (busy && e != null && e == hidden) {               // only the wake word's unseen check: the button wins
            hidden = null;
            e.cancel = true;
            begin(false);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (busy && e == null && now - busyAt > 12_000) busy = false;   // stuck from an ask that never let go
        if (busy && e != null) {
            // Pressed again: that's all, go. But not in the first moments, before any voice: that's the remote
            // sending the press twice, or a second try because the card was slow; it would end the ask unheard.
            if (!e.voiced && now - e.startedAt < 2500) Log.i(TAG, "voice: second press too soon, ignored");
            else e.stop = true;
        } else if (!busy) begin(false);
    }

    void cancel() {
        Ear e = ear;
        if (e != null) e.cancel = true;
        if (card != null) card.dismiss();
        tell(0);
    }

    // ---------------------------------------------------------------- one ask

    void begin(boolean wake) {
        asks++;
        showCard();
        String key = key();
        if (key.isEmpty()) {
            card.listening(false);
            card.fail("SEND YOUR KEY TO THE TV WITH DROP");
            return;
        }
        busy = true;
        busyAt = android.os.SystemClock.uptimeMillis();
        card.listening(wake);
        tell(1);
        duck();
        Ear e = new Ear(this);
        String test = prop("debug.quiet.earsrc");
        if (!test.isEmpty()) {                                 // a test: each press tries another way of opening the mic
            int[] ways = {android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, android.media.MediaRecorder.AudioSource.MIC,
                    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, android.media.MediaRecorder.AudioSource.CAMCORDER, android.media.MediaRecorder.AudioSource.UNPROCESSED};
            e.source = test.equals("cycle") ? ways[earTry++ % ways.length] : Integer.parseInt(test.replaceAll("\\D", "0"));
            e.restartWake = mic != null;
            stopWake();                                        // its own stream, so the wake ear lets go
            Log.i(TAG, "ear test: this time source " + e.source);
        } else if (mic != null && mic.running && !mic.deaf()) {       // share the wake word's ear (and what it just heard)
            e.mic = mic;
            e.before = mic.handOver(wake);
            e.presetFloor = mic.spot.floor;
        }
        ear = e;
        boolean typing = boxHere();
        work.execute(() -> ask(e, key, typing, wake));
    }

    /**
     * Maybe the wake word: the TV's matcher is generous (this microphone is faint), so the last two
     * seconds go to the quick cloud model. Meanwhile the ear keeps recording, invisibly; only if the
     * cloud heard the wake phrase does the card appear (and nothing said after it is lost).
     */
    volatile boolean pending;
    /** The wake word's recording while the cloud checks it (no card yet); a button press replaces it. */
    volatile Ear hidden;

    long lastWakeCheck;
    int wakeChecksToday;
    String wakeDay = "";

    void maybeWoke() {
        if (busy || pending || asking || !screenOn) return;
        long now = android.os.SystemClock.uptimeMillis();
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        if (!today.equals(wakeDay)) {
            wakeDay = today;
            wakeChecksToday = 0;
        }
        if (now - lastWakeCheck < 2500 || wakeChecksToday >= 1500) return;   // at most one every 2.5 s, 1500 a day
        lastWakeCheck = now;
        wakeChecksToday++;
        String key = key();
        Mic m = mic;
        if (key.isEmpty() || m == null) return;
        // Any short phrase is worth a check in a quiet room; with a film or music on it's mostly the TV
        // talking (a check every few seconds, and the button had to wait), so then only a real match.
        if (m.lastWakeScore == Float.MAX_VALUE && getSystemService(AudioManager.class).isMusicActive()) return;
        pending = true;
        ui.postDelayed(() -> {
            if (busy || mic != m) {
                pending = false;
                return;
            }
            byte[] all = m.recent();
            int keep = Math.min(all.length, 16000 * 2 * 22 / 10);   // the last 2.2 s
            byte[] snip = java.util.Arrays.copyOfRange(all, all.length - keep, all.length);
            busy = true;
            Ear e = new Ear(this);
            e.mic = m;
            e.before = m.handOver(true);
            e.presetFloor = m.spot.floor;
            ear = e;
            hidden = e;
            boolean typing = boxHere();
            quietThread(() -> {
                String heard = "";
                boolean yes = false;
                long t0 = System.currentTimeMillis();
                try {
                    heard = new Brain(key).hearQuick(Ear.wav(snip), "");      // unprimed: a prompt gets echoed on silence
                    yes = afterWake(heard, true) != null;
                } catch (Exception x) {
                    Log.w(TAG, "wake check failed: " + x);
                }
                Log.i(TAG, "wake: cloud heard '" + heard + "' in " + (System.currentTimeMillis() - t0) + " ms → " + (yes ? "yes" : "no"));
                boolean ok = yes;
                ui.post(() -> {
                    pending = false;
                    if (hidden != e) return;                   // the button came first, or it was let go
                    hidden = null;
                    if (ok) {
                        showCard();
                        card.listening(true);
                        tell(1);
                        duck();
                    } else e.cancel = true;
                });
            }, "wakecheck").start();
            work.execute(() -> ask(e, key, typing, true));
        }, 250);
    }

    void ask(Ear e, String key, boolean typing, boolean wake) {
        Brain b = new Brain(key);
        quietThread(b::warm, "warm").start();                  // the connection opens while you speak
        // What's around (apps, shelves, the screen) is gathered while you speak, not before.
        java.util.concurrent.FutureTask<String> hintsF = new java.util.concurrent.FutureTask<>(this::hints);
        java.util.concurrent.FutureTask<String> ctxF = new java.util.concurrent.FutureTask<>(() -> context(typing));
        quietThread(() -> {
            hintsF.run();
            ctxF.run();
        }, "context").start();
        try {
            long t0 = System.currentTimeMillis();
            byte[] wav = e.listen((l, speaking) -> ui.post(() -> {
                if (card != null) card.level(l);
            }));
            ui.post(() -> {
                if (ear == e) unduck();
            });
            for (long w0 = System.currentTimeMillis(); wake && pending && !e.cancel && System.currentTimeMillis() - w0 < 3000; ) Thread.sleep(40);
            if (e.cancel) return;
            if (e == hidden) {                                 // the cloud didn't answer in time: let it go, unseen
                hidden = null;
                Log.i(TAG, "wake: no answer from the cloud in time, let go");
                return;
            }
            ui.post(() -> tell(2));
            Log.i(TAG, "listened " + (System.currentTimeMillis() - t0) + " ms: " + (wav == null ? (e.blocked ? "silence (blocked)"
                    : e.cancel ? "cancelled" : "no speech") : wav.length / 32 + " ms of speech"));
            if (wav == null) {
                ui.post(() -> tell(0));
                if (e.blocked) Log.w(TAG, "the microphone gave only silence (held back, or in use)");
                diary("(nothing heard" + (e.blocked ? ": the microphone was blocked)" : ")"));
                ui.post(() -> card.fail(e.blocked ? "THE MICROPHONE IS BUSY OR BLOCKED" : "DIDN'T HEAR ANYTHING"));
                return;
            }
            ui.post(() -> card.heard(""));
            long t1 = System.currentTimeMillis();
            String hints;
            try {
                hints = hintsF.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception x) {
                hints = "Quiet TV.";
            }
            String text = b.hear(wav, hints);
            if (text.trim().isEmpty() && e.raw != null) {     // the cleaned one said nothing: as it was heard
                text = b.hear(e.raw, hints);
                Log.i(TAG, "heard nothing in the cleaned recording; in the plain one: '" + text + "'");
            }
            if (!text.isEmpty() && quick(text) == null && echoes(text, hints)) {
                // Only one of the names it was primed with ("pause" came back as a name on the shelf):
                // heard, or just said back? The same sound again, without the names, settles it.
                String again = b.hear(wav, "");
                Log.i(TAG, "heard '" + text + "' (one of the hints); again without them: '" + again + "'");
                text = again;
            }
            long t2 = System.currentTimeMillis();
            if (e.unsure && invented(text)) {
                Log.i(TAG, "heard '" + text + "' from a recording with no clear voice: taken as nothing");
                text = "";
            }
            Log.i(TAG, "heard: '" + text + "'");
            if (text.isEmpty()) {
                diary("'' (didn't catch it)");
                ui.post(() -> card.fail("DIDN'T CATCH THAT"));
                return;
            }
            if (wake) {                                        // the cloud's double check: was it really the wake word?
                String rest = afterWake(text);
                Mic m = mic;
                if (rest == null && m != null && m.lastWakeScore < m.spot.threshold * 0.6f) rest = text;   // a sure match: trust it
                if (rest == null) {
                    Log.i(TAG, "wake: not it ('" + text + "')");
                    ui.post(() -> {
                        card.dismiss();
                        tell(0);
                    });
                    return;
                }
                if (rest.isEmpty()) {                          // just the wake word: now the command
                    ui.postDelayed(() -> begin(false), 150);
                    return;
                }
                text = rest;
            }
            String said = text;
            ui.post(() -> card.heard(said));
            JSONObject act = decision(b, text, typing, () -> {
                try {
                    return ctxF.get(4, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception x) {
                    return "";
                }
            });
            long t3 = System.currentTimeMillis();
            Log.i(TAG, "heard in " + (t2 - t1) + " ms, decided in " + (t3 - t2) + " ms (spoke " + (t1 - t0) + " ms): " + act);
            if (e.unsure && act.optString("name").equals("say") && said.trim().split("\\s+").length < 3) {   // a faint guess ("Respect.") only chatted about
                Log.i(TAG, "a faint recording heard as '" + said + "' and only answered: taken as not caught");
                diary("'" + said + "' (faint, not caught)");
                ui.post(() -> card.fail("DIDN'T CATCH THAT · CLOSER OR LOUDER"));
                return;
            }
            JSONObject a = act;
            ui.post(() -> carryOut(said, a, 1));
        } catch (Exception ex) {
            String why = ex.getMessage() != null && ex.getMessage().equals(ex.getMessage().toUpperCase(Locale.ROOT))
                    ? ex.getMessage() : "COULDN'T REACH THE CLOUD";
            Log.w(TAG, "ask failed: " + ex);
            diary("(failed: " + why + ")");
            ui.post(() -> {
                card.fail(why);
                tell(0);
            });
        } finally {
            if (e.restartWake) ui.postDelayed(this::startWake, 500);
            if (ear == e) {                                    // (unless a button press took over meanwhile)
                ui.post(this::unduck);
                if (mic != null) mic.takeBack();
                busy = false;
                ear = null;
            }
        }
    }

    /** The whole transcript is one of the hints (or part of one): the model may have said it back. */
    static boolean echoes(String text, String hints) {
        String t = plain(text);
        if (t.matches("(where s |where is )?(skye|mote)|(on|open) (youtube|netflix)")) return false;   // the world's names: likely what was said
        return !t.isEmpty() && t.split(" ").length <= 4 && (" " + plain(hints) + " ").contains(" " + t + " ");
    }

    static String plain(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    // ---------------------------------------------------------------- still watching?

    volatile long lastKey = android.os.SystemClock.uptimeMillis();
    volatile boolean asking;
    Runnable sleepNow;

    /** Every few minutes: something playing, nobody's touched the remote for hours? Ask. */
    final Runnable stillWatching = new Runnable() {
        @Override
        public void run() {
            ui.postDelayed(this, 5 * 60_000);
            if (asking || busy || !screenOn) return;
            MediaController c = playing();
            PlaybackState st = c == null ? null : c.getPlaybackState();
            if (st == null || st.getState() != PlaybackState.STATE_PLAYING) return;
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            long quietFor = android.os.SystemClock.uptimeMillis() - lastKey;
            long limit = (hour >= 23 || hour < 5 ? 2 : 4) * 3_600_000L;
            if (quietFor < limit) return;
            Log.i(TAG, "still watching? (no key for " + quietFor / 60_000 + " min)");
            asking = true;
            showCard();
            card.prompt("STILL WATCHING?", "PRESS ANY KEY · OR IT SLEEPS IN A MINUTE");
            tell(1);
            sleepNow = () -> {
                if (!asking) return;
                asking = false;
                MediaController m = playing();
                if (m != null) m.getTransportControls().pause();
                card.done("GOODNIGHT", 1500);
                tell(0);
                ui.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 1600);
            };
            ui.postDelayed(sleepNow, 60_000);
        }
    };

    void stillHere() {
        asking = false;
        if (sleepNow != null) ui.removeCallbacks(sleepNow);
        card.done("CARRY ON", 900);
        tell(3);
    }

    // ---------------------------------------------------------------- from the phone

    /** Screen off: the wake ear stops (no listening while the TV sleeps), anything under way ends. */
    volatile boolean screenOn = true;
    final android.content.BroadcastReceiver screenRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (!screenOn) {
                stopWake();
                cancel();
                asking = false;
                if (sleepNow != null) ui.removeCallbacks(sleepNow);
                unduck();
            } else if ("on".equals(Prefs.prefs(Voice.this).getString("wakeOn", "")))
                ui.postDelayed(() -> {                         // once the TV counts as awake (else Android holds the microphone back)
                    if (screenOn) startWake();
                }, 1500);
            Log.i(TAG, "screen " + (screenOn ? "on" : "off"));
        }
    };

    final android.content.BroadcastReceiver shareRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {           // from Drop's page on the phone
            String t = i.getStringExtra("text");
            if (t != null && !t.trim().isEmpty()) shared(t.trim(), "");
        }
    };

    /** Something shared from the phone app: show it, work out what it is, do it. */
    void shared(String text, String subject) {
        asks++;
        String key = key();
        if (key.isEmpty()) {                                   // no brain yet: a link still opens
            java.util.regex.Matcher m = Share.URL_IN.matcher(text);
            if (m.find()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.group())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return;
            }
        }
        showCard();
        card.listening(false);
        card.heard(subject.isEmpty() ? text : subject);
        card.label = "FROM YOUR PHONE";
        tell(2);
        Log.i(TAG, "shared from the phone: " + (text.length() > 120 ? text.substring(0, 120) : text));
        if (!Share.URL_IN.matcher(text).find() && boxHere()) {   // a search box is open: type it there
            try {
                act(new JSONObject().put("name", "type").put("args", new JSONObject().put("text", text)));
            } catch (org.json.JSONException ignored) {
            }
            tell(3);
            return;
        }
        work.execute(() -> {
            try {
                JSONObject act = Share.route(this, text, subject, new Brain(key));
                Log.i(TAG, "shared → " + act);
                ui.post(() -> carryOut("(shared from the phone) " + text, act, 1));
            } catch (Exception ex) {
                Log.w(TAG, "share failed: " + ex);
                ui.post(() -> {
                    card.fail(key.isEmpty() ? "SEND YOUR KEY TO THE TV WITH DROP" : "COULDN'T WORK THAT OUT");
                    tell(0);
                });
            }
        });
    }

    /** Words only, for the phone to type into a box on the TV. Null if it couldn't be heard. */
    String transcribe(byte[] wav) {
        String key = key();
        if (key.isEmpty()) return null;
        ui.post(() -> {
            showCard();
            card.listening(false);
            card.label = "FROM YOUR PHONE";
            card.heard("");
        });
        try {
            Brain b = new Brain(key);
            String text = bare(b.hear(wav, hints()));
            if (text.isEmpty()) {
                ui.post(() -> card.fail("DIDN'T CATCH THAT"));
                return null;
            }
            return text;
        } catch (Exception e) {
            Log.w(TAG, "transcribe failed: " + e);
            ui.post(() -> card.fail("COULDN'T REACH THE CLOUD"));
            return null;
        }
    }

    /**
     * In a search box: the words to type, or null if it's a command. Anything that doesn't start like a
     * command or a question is words for the box ("Kevin Hart" in YouTube is a search, not Kevin Hart's
     * music); "search (for) X", "find X", "type X" are X. Decided here, not by the AI, so it's the same
     * every time.
     */
    static String dictation(String said) {
        String s = bare(said).replaceFirst("(?i)^((hey|ok|okay|hello)[ ,]+)?((quiet|quite|pip)[ ,]+)?(please[ ,]+)?((can|could|would|will) you )?(please )?", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^(?:search(?: for| karo)?|find|look up|look for|type|write) (.+)$").matcher(s);
        if (m.matches()) return m.group(1).trim()
                .replaceFirst("(?i)^(youtube|netflix|prime( video)?|hotstar|apple tv|listen|watch|stremio|the app|it) for ", "")
                .replaceFirst("(?i) (on|in) (youtube|netflix|prime( video)?|hotstar|apple tv|listen|watch|stremio)$", "");
        String low = s.toLowerCase(Locale.ROOT);
        // a doing word with something after it ("play Kevin Hart", "open Netflix", "turn it up")
        if (low.matches("(play|open|launch|start|watch|put|listen|show|turn|switch|set|change|increase|decrease|raise|lower|volume|take|bring|navigate|leave|exit|quit|"
                + "tell|close|exit|remind|wake|chalao|chala|lagao|kholo|dikhao|sunao|bajao|clear|delete|add|cancel)\\b.*")) return null;
        // a button on its own ("pause", "go back"; not "Home Alone" or "Back to the Future")
        if (low.matches("(go (back|home|to .+)|home|back|next|previous|skip|stop|pause|resume|mute|unmute|sleep|quit|undo|backspace|"
                + "band karo|ruko)( it| this| that| the \\w+)?")) return null;
        // a question ("what's the weather?"; not "How I Met Your Mother" or "Kaun Banega Crorepati")
        if (said.trim().endsWith("?") && low.matches("(what|what's|whats|who|who's|why|how|when|where|which|is|are|do|does|did|can|will|"
                + "kya|kaun|kab|kaise|kahan)\\b.*")) return null;
        if (low.matches("(what's|whats|what is|who's|who is|what time|how much|how many|tell me)\\b.*")) return null;
        return s.isEmpty() ? null : s;
    }

    /**
     * What speech-to-text tends to make up from a room with no voice in it: thanks and goodbyes, a lone
     * filler, or another language's script (not English, Hindi or Malayalam).
     */
    static boolean invented(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ']", "").trim();
        if (t.isEmpty()) return true;
        if (t.matches("(thank you|thanks|thanks for watching|thank you for watching|bye|goodbye|you|okay|ok|hmm|um|uh|so|yeah|oh)( so much)?( everyone)?"))
            return true;
        for (int i = 0; i < t.length(); i++) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(t.codePointAt(i));
            if (sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.DEVANAGARI && sc != Character.UnicodeScript.MALAYALAM
                    && sc != Character.UnicodeScript.COMMON && sc != Character.UnicodeScript.INHERITED) return true;
        }
        return false;
    }

    /** Words as spoken, without the full stop the transcription adds. */
    static String bare(String text) {
        return text.trim().replaceAll("[.,!?;:]+$", "").trim();
    }

    /**
     * What words said in a search box mean: an edit ("clear this", "delete the last word", "add
     * trailer"), words to type (misheard names put right), or a command (null). The usual phrasings
     * are known here at once; anything else is asked of the AI, so any way of saying it works.
     * mustType (the phone's SPEAK TO TYPE): never a command.
     */
    JSONObject inBox(Brain b, String said, boolean mustType) {
        JSONObject edit = boxEdit(said);
        if (edit != null) return edit;
        if (!mustType && elsewhere(said)) {                   // "search Netflix for Dune" while in YouTube's box
            Log.i(TAG, "'" + said + "' names another app than " + front + ": a command, not words for this box");
            return null;
        }
        String plain = dictation(said);
        if (plain == null && !mustType) return null;           // plainly a command: play, open, pause, a question
        try {
            JSONObject r = b.boxAsk(bare(said), front.isEmpty() ? "the TV" : label(front), boxNow());
            String d = r.optString("do"), t = r.optString("text").trim();
            Log.i(TAG, "in the box, '" + said + "' means " + r);
            if (d.equals("clear") || d.equals("word") || d.equals("add") && !t.isEmpty()) return typeAct(t, d);
            if (d.equals("command") && !mustType) return null;
            return typeAct(t.isEmpty() ? (plain != null ? plain : bare(said)) : t, "");
        } catch (Exception e) {
            return typeAct(plain != null ? plain : bare(said), "");
        }
    }

    /** The words name an app to do it in ("search Netflix for X", "X on Prime") that isn't the one in front. */
    boolean elsewhere(String said) {
        String s = plain(said);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:search|find|look up|look for|look)(?: on| in)? (.+?) for .+$").matcher(s);
        java.util.List<String> names = new java.util.ArrayList<>();
        if (m.matches()) names.add(m.group(1));
        m = java.util.regex.Pattern.compile("^.+ (?:on|in) (.+)$").matcher(s);
        if (m.matches()) names.add(m.group(1));
        for (String n : names) {
            n = n.replaceAll("^the ", "").replaceAll(" (app|application)$", "");
            if (!appNamed(n)) continue;
            App a = findApp(n);
            if (a != null && !a.pkg.equals(front)) return true;
        }
        return false;
    }

    /**
     * The TV's own mic (the remote's button, the wake word) in a box nothing can read (YouTube, Netflix):
     * words or an edit, typed through the TV's own developer connection (act "type" → Shell). A command
     * (null) goes on as usual.
     */
    JSONObject outOfReach(Brain b, String said) {
        JSONObject box = inBox(b, said, false);
        if (box == null) return null;
        if (!keyboardUp() && !searchShowing()) {               // watching, not searching: it's for the TV, not a box
            Log.i(TAG, "'" + said + "' in " + front + ": no search box on screen, taken as a command");
            return null;
        }
        Log.i(TAG, "'" + said + "' in " + front + ": a hidden box → " + box);
        return box;
    }

    /**
     * Is a search box really showing in the app in front (one that hides its screen)? Not while it plays
     * something (3 Nov: words said during a YouTube video were typed into nothing); else a look at the screen.
     */
    /** The app has a media session with a title now (it tells Android what it plays). */
    boolean saysWhatPlays(String pkg) {
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Listener.class)))
                if (pkg.equals(c.getPackageName()) && c.getMetadata() != null
                        && !History.first(c.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE),
                        c.getMetadata().getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).isEmpty()) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    boolean searchShowing() {
        if (stillOurSearch(front)) return true;               // we typed there, untouched since (Netflix shows us nothing)
        if (blind()) return false;                             // a black picture can't show a box
        MediaController m = playing();
        if (m != null && front.equals(m.getPackageName()) && m.getPlaybackState() != null
                && m.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return false;
        boxHolds();                                            // the picture, read (kept a few seconds)
        return seenBox;
    }

    final java.util.concurrent.ExecutorService shellQ = worker("shell");

    /**
     * Types into the hidden box in front through the TV's own developer connection (Shell): what we
     * typed there before goes first (the delete key), then the new words; the TV's keyboard also gets
     * its search key. Says so on the card; if the connection is refused, says the phone can.
     */
    void typeByShell(JSONObject act) {
        JSONObject x = act.optJSONObject("args");
        String op = x == null ? "" : x.optString("op"), said = x == null ? "" : x.optString("text");
        boolean keyboard = keyboardUp();
        String pkg = front, key = key();
        card.heard(said);
        card.listening(false);
        card.working("LOOKING AT THE BOX", 12000);
        if (seenIn.equals(pkg) && System.currentTimeMillis() - seenAt < 4000) {   // just looked (the TV allows one
            String held = seenBox ? seenText : null;                              // picture a second or so)
            shellQ.execute(() -> shellType(pkg, op, said, keyboard, key, null, held));
            return;
        }
        // First a look at what the box holds: one delete too many in YouTube goes back a page.
        shoot(new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult r) {
                byte[] shot = jpeg(r);
                shellQ.execute(() -> shellType(pkg, op, said, keyboard, key, shot, null));
            }

            @Override
            public void onFailure(int code) {
                shellQ.execute(() -> shellType(pkg, op, said, keyboard, key, null, null));
            }
        });
    }

    /** What the box holds (read off the screen), the edit applied, the old letters deleted exactly, the new typed. */
    void shellType(String pkg, String op, String said, boolean keyboard, String key, byte[] shot, String known) {
        String now = known;
        try {
            if (shot != null) now = new Brain(key).readBox(shot);
        } catch (Exception e) {
            Log.w(TAG, "reading the box: " + e);
        }
        boolean slow = dropsKeys(pkg);
        boolean guessed = false;
        if (now == null) {                                     // couldn't see it: only what we typed there ourselves
            String[] t = lastTyped;
            if (t != null && t[0].equals(pkg)) now = t[1];
            else if (!op.isEmpty()) {
                ui.post(() -> card.fail("I CAN'T SEE A SEARCH BOX HERE"));
                return;
            } else {
                now = "";
                guessed = true;
            }
        }
        String words = Box.edit(now, op, said);
        // Exactly as many deletes as letters: one too many in YouTube goes back a page. Netflix ignores extra ones,
        // so there a box we couldn't read is emptied anyway.
        int del = guessed && slow ? 30 : now.length();
        Log.i(TAG, "typing into " + pkg + " (the TV itself): box held '" + now + "', now '" + words + "' (" + del + " deletes"
                + (slow ? ", slowly" : "") + ")");
        diary("'" + words + "' -> typed into " + label(pkg) + " (the TV itself)");
        StringBuilder cmd = new StringBuilder(Shell.deleting(del, slow));
        String w = Shell.typing(words, slow);
        if (!w.isEmpty()) cmd.append(cmd.length() > 0 ? "; sleep 0.5; " : "").append(w);
        if (keyboard && !w.isEmpty()) cmd.append("; input keyevent 66");
        if (cmd.length() == 0) {
            ui.post(() -> card.done("NOTHING TO CLEAR", 1200));
            return;
        }
        try {
            if (slow) ui.post(() -> card.working(words.isEmpty() ? "CLEARING" : "TYPING " + com.quiet.px.Font.upper(words), 60_000));
            Shell.run(this, cmd.toString(), () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
            typedInto(pkg, words);
            Log.i(TAG, "shell typed: " + cmd);
            ui.post(() -> card.done(words.isEmpty() ? "CLEARED" : "TYPED", 1200));
        } catch (Exception e) {
            Log.w(TAG, "shell: " + e);
            diary("couldn't type by the TV's own connection: " + e);
            ui.post(() -> card.fail("ONLY THE PHONE CAN TYPE HERE"));
        }
    }

    static final java.util.regex.Pattern SIGN_IN_BOX = java.util.regex.Pattern.compile("\\b(log ?in|sign ?in|sign ?up|password|"
            + "passcode|otp|one[- ]time|verification|mobile|phone|e-?mail|username|user ?id|pin|cvv|card number|account)\\b");
    static final java.util.regex.Pattern SIGN_IN_SCREEN = java.util.regex.Pattern.compile("\\b(log ?in|sign ?in|sign ?up|"
            + "password|otp|verification code|mobile number|phone number|enter your e-?mail|create (an )?account|activation code)\\b");

    /**
     * Why typing here would be into a sign-in (a password, phone, e-mail or PIN box; a box or a screen asking to log
     * in), or null. Never typed into: the owner's rule (3 Nov: Hotstar, not signed in, opened on its login page and
     * a search went into its mobile number box).
     */
    String signIn(AccessibilityNodeInfo box) {
        if (box != null) {
            if (box.isPassword()) return "a password box";
            int t = box.getInputType(), cls = t & android.text.InputType.TYPE_MASK_CLASS, v = t & android.text.InputType.TYPE_MASK_VARIATION;
            if (cls == android.text.InputType.TYPE_CLASS_PHONE) return "a phone number box";
            if (cls == android.text.InputType.TYPE_CLASS_TEXT && (v == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || v == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                return "an e-mail or password box";
            if (cls == android.text.InputType.TYPE_CLASS_NUMBER && v == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) return "a PIN box";
            String about = (box.getHintText() + " " + box.getContentDescription() + " " + box.getViewIdResourceName()
                    + (box.getParent() == null ? "" : " " + words(box.getParent()))).toLowerCase(Locale.ROOT).replace('_', ' ');
            if (SIGN_IN_BOX.matcher(about).find()) return "a sign-in box";
        }
        if (front.startsWith("com.quiet.")) return null;       // ours: no sign-ins to type into
        if (SIGN_IN_SCREEN.matcher(onScreen().toLowerCase(Locale.ROOT)).find()) return "a sign-in screen";
        return null;
    }

    void refuseSignIn(String why) {
        String app = label(front);
        Log.w(TAG, "won't type: " + why + " in " + front);
        diary("(didn't type: " + app + " shows " + why + ")");
        card.say(com.quiet.px.Font.upper(app) + " WANTS A SIGN-IN · THAT'S YOURS TO DO");
    }

    /** Apps that lose keys sent in a burst: typed into a couple of letters at a time. */
    static boolean dropsKeys(String pkg) {
        return "com.netflix.ninja".equals(pkg);
    }

    volatile long typedAt, humanKeyAt;

    /** We typed these words there, just now. */
    void typedInto(String pkg, String words) {
        lastTyped = new String[]{pkg, words};
        typedAt = android.os.SystemClock.uptimeMillis();
    }

    /**
     * The search we typed into is surely still up in that app: it's in front, it's been under 5 minutes, and nobody
     * has pressed a key on the remote since (for apps that show us nothing, like Netflix, this is all we can know).
     */
    boolean stillOurSearch(String pkg) {
        String[] t = lastTyped;
        long now = android.os.SystemClock.uptimeMillis();
        return t != null && t[0].equals(pkg) && pkg.equals(front) && humanKeyAt < typedAt && now - typedAt < 5 * 60_000;
    }

    static JSONObject typeAct(String text, String op) {
        try {
            JSONObject x = new JSONObject().put("text", text);
            if (!op.isEmpty()) x.put("op", op);
            return new JSONObject().put("name", "type").put("args", x);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** What the box in front holds, as far as we know (read, or what we last typed there). */
    String boxNow() {
        AccessibilityNodeInfo f = focusedText();
        if (f != null) return f.getText() == null || f.isShowingHintText() ? "" : f.getText().toString();
        String says = gridSays();
        if (says != null) return says;
        String[] t = lastTyped;
        return t != null && t[0].equals(front) ? t[1] : "";
    }

    String seenText;
    long seenAt;
    String seenIn = "";
    boolean seenBox;                                           // the last look found a search box

    /**
     * What the hidden box in front holds now: read off a picture of the screen (kept a few seconds, so
     * one ask looks once), else what we typed there ourselves, else nothing. Off the main thread.
     */
    String boxHolds() {
        if (seenIn.equals(front) && System.currentTimeMillis() - seenAt < 4000) return seenText;
        String got = null;
        try {
            java.util.concurrent.CompletableFuture<byte[]> shot = new java.util.concurrent.CompletableFuture<>();
            shoot(new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    shot.complete(jpeg(r));
                }

                @Override
                public void onFailure(int code) {
                    shot.complete(null);
                }
            });
            byte[] jpg = shot.get(3, java.util.concurrent.TimeUnit.SECONDS);
            if (jpg != null) got = new Brain(key()).readBox(jpg);
        } catch (Exception e) {
            Log.w(TAG, "reading the box: " + e);
        }
        seenBox = got != null;
        if (got == null) {
            String[] t = lastTyped;
            got = t != null && t[0].equals(front) ? t[1] : "";
        } else Log.i(TAG, "the box in " + front + " holds '" + got + "'");
        seenText = got;
        seenIn = front;
        seenAt = System.currentTimeMillis();
        return got;
    }

    /** The app in front (not ours) shows nothing we can read: its search is only the phone's to type into. */
    boolean hidesItself() {
        return !front.isEmpty() && !front.startsWith("com.quiet.") && onScreen().isEmpty();
    }

    /** The Quiet app whose search screen is open now (it says so through home's provider: Box), or "". */
    static volatile String quietBox = "";

    boolean quietBoxHere() {
        String q = quietBox;
        return !q.isEmpty() && q.equals(front);
    }

    /** Our own apps with a search (their letters are drawn, so they take words through Box). */
    static boolean quietSearch(String pkg) {
        return "com.quiet.watch".equals(pkg) || "com.quiet.listen".equals(pkg);
    }

    /** A box the TV can type into itself: one it can read, or a Quiet app's search screen. */
    boolean boxHere() {
        return focusedText() != null || quietBoxHere() || letterGrid();
    }

    /**
     * The phone's SPEAK TO TYPE: these words go into the box on screen. A box we can read, we type into
     * here ("DONE"); otherwise the phone does, through Google's remote service: with the TV's keyboard up
     * ("TYPE", then its search key), or into an app's own letter grid ("GRID").
     */
    String dictated(String said) {
        String signing = signIn(focusedText());
        if (signing != null) {
            ui.post(() -> {
                showCard();
                refuseSignIn(signing);
            });
            return "ON THE TV";
        }
        JSONObject box = inBox(new Brain(key()), said, true);
        boolean isEdit = !box.optJSONObject("args").optString("op").isEmpty();
        String text = box.optJSONObject("args").optString("text");
        if (focusedText() != null || quietSearch(front)) {   // a Quiet app: its search, even if it wasn't open
            Log.i(TAG, "typing into " + front + " (here): '" + text + "'");
            diary("'" + text + "' -> typed into " + label(front));
            ui.post(() -> {
                showCard();
                act(box);
            });
            return "DONE\n" + (isEdit ? "" : text);
        }
        return forPhone(edited(box), keyboardUp());
    }

    /**
     * The answer that has the phone type these words into the box in front. In YouTube and Netflix the
     * phone's typing only ever adds (an empty or shorter box is ignored, 2 Nov), so what we typed there
     * before is taken out first, letter by letter with the delete key: "TYPE|GRID\n<words>\n<letters>".
     */
    String forPhone(String words, boolean keyboard) {
        String signing = signIn(null);
        if (signing != null) {
            ui.post(() -> {
                showCard();
                refuseSignIn(signing);
            });
            return "ON THE TV";
        }
        int del = boxHolds().length();                         // exactly: one delete too many in YouTube goes back
        typing(words, keyboard ? "the TV's keyboard" : "the app's own letters");
        return (keyboard ? "TYPE\n" : "GRID\n") + words + "\n" + del;
    }

    /**
     * Spoken edits of the box in front, instead of words for it: "clear (the search)", "delete the last
     * word", "add trailer". Null if it's just words. Only asked while a box is in front.
     */
    static JSONObject boxEdit(String said) {
        String s = said.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^(hey |ok |okay )?(quiet |pip )?(please )?(can you |could you )?", "").replaceAll(" please$", "");
        String op = null, words = "";
        if (s.matches("(clear|erase|delete|remove|wipe|empty|reset|clean)( it| that| this| all| everything| the| my)*"
                + "( search| text| words| box| search box| field| query)?( all| everything)?|start (over|again)|clear karo|"
                + "saaf karo|hatao|mita do|delete karo|sab hatao")) op = "clear";
        else if (s.matches("(delete|remove|erase|backspace|undo)( the| that)?( last)? word|backspace|undo( that)?|"
                + "remove the last word|take off the last word")) op = "word";
        else if (s.matches("(add|also|plus|and add|also add|type also|put) .+")) {
            op = "add";
            words = s.replaceFirst("^(and add|also add|type also|add|also|plus|put) ", "");
        }
        if (op == null) return null;
        try {
            return new JSONObject().put("name", "type").put("args", new JSONObject().put("op", op).put("text", words));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** What we last typed, and where: the box's words for boxes we can't read. */
    volatile String[] lastTyped;

    /** The words a phone-typed box should hold after this edit (from what we typed there last). */
    String edited(JSONObject edit) {
        JSONObject x = edit.optJSONObject("args");
        String now = boxHolds();
        return Box.edit(now, x == null ? "" : x.optString("op"), x == null ? "" : x.optString("text"));
    }

    /** These words are being typed into the box in front: said on the card, kept in the diary. */
    void typing(String text, String how) {
        Log.i(TAG, "typing into " + front + " (" + how + "): '" + text + "'");
        diary("'" + text + "' -> typed into " + label(front) + " (" + how + ")");
        typedInto(front, text);
        ui.post(() -> {
            showCard();
            card.listening(false);
            card.heard(text);
            card.done(text.isEmpty() ? "CLEARED" : "TYPING IT IN", 1400);
        });
    }

    /** The TV's own keyboard is showing: some app's text box has focus (readable or not). */
    boolean keyboardUp() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** A box is open that only the phone can type into: the keyboard is up, but the app shows us nothing. */
    boolean hiddenBox() {
        return focusedText() == null && keyboardUp();
    }

    /**
     * The phone's main mic, from a phone that can also type into the TV's boxes: the same as spoken(),
     * but if the words belong in a box only the phone can reach (the TV's keyboard is up, or an app that
     * hides its screen and they're just saying words to look up), they come back for the phone to type:
     * "TYPE\n<words>" / "GRID\n<words>". Otherwise "ON THE TV" once it's decided.
     */
    String fromPhone(byte[] wav) {
        java.util.concurrent.CompletableFuture<String> out = new java.util.concurrent.CompletableFuture<>();
        ui.post(() -> spoken(wav, out));
        try {
            return out.get(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ON THE TV";
        }
    }

    static void answer(java.util.concurrent.CompletableFuture<String> phone, String reply) {
        if (phone != null) phone.complete(reply);
    }

    void spoken(byte[] wav) {
        spoken(wav, null);
    }

    /** You spoke into the phone app: the same as speaking to the TV, with a much better microphone. */
    void spoken(byte[] wav, java.util.concurrent.CompletableFuture<String> phone) {
        asks++;
        if (front.isEmpty()) front = activePackage();          // just restarted: nothing has come to the front since
        String key = key();
        showCard();
        card.listening(false);
        if (key.isEmpty()) {
            card.fail("SEND YOUR KEY TO THE TV WITH DROP");
            answer(phone, "ON THE TV");
            return;
        }
        card.heard("");
        tell(2);
        boolean typing = boxHere();
        // The same steps as the remote's button (ask): what's around gathered while it's heard, the
        // echo check, instant asks, boxes, then the AI; failures in the diary.
        java.util.concurrent.FutureTask<String> ctxF = new java.util.concurrent.FutureTask<>(() -> context(typing));
        quietThread(ctxF, "context").start();
        work.execute(() -> {
            try {
                Brain b = new Brain(key);
                long t1 = System.currentTimeMillis();
                String hints = hints();
                String heard = b.hear(wav, hints);
                if (!heard.isEmpty() && quick(heard) == null && echoes(heard, hints)) {
                    String again = b.hear(wav, "");
                    Log.i(TAG, "heard '" + heard + "' (one of the hints); again without them: '" + again + "'");
                    heard = again;
                }
                String text = heard;
                Log.i(TAG, "heard (phone): '" + text + "'");
                if (text.isEmpty()) {
                    diary("'' (didn't catch it, phone)");
                    ui.post(() -> card.fail("DIDN'T CATCH THAT"));
                    answer(phone, "ON THE TV");
                    return;
                }
                ui.post(() -> card.heard(text));
                JSONObject act = quickNow(text);
                boolean hidden = !typing && (hidesItself() || keyboardUp());
                if (act == null && phone == null && hidden) act = outOfReach(b, text);   // an older phone app: the TV types
                boolean hides = phone != null && hidesItself(), keyboard = phone != null && !typing && keyboardUp();
                JSONObject box = act == null && (typing || hides || keyboard) ? inBox(b, text, false) : null;
                Log.i(TAG, "phone ask '" + text + "' in " + front + ": box here " + typing + ", keyboard " + keyboard + ", hides itself " + hides
                        + " → " + (box == null ? "a command" : box.toString()));
                if (box != null && !typing && !keyboard && !searchShowing()) {   // watching, not searching
                    Log.i(TAG, "phone: no search box on screen in " + front + ", taken as a command");
                    box = null;
                }
                if (box != null && typing) act = box;          // a box the TV types into itself
                else if (box != null) {                        // only the phone can: YouTube, Netflix, the TV's keyboard
                    answer(phone, forPhone(edited(box), keyboard));
                    return;
                }
                if (act == null) act = fast(text);
                if (act == null) {
                    String ctx;
                    try {
                        ctx = ctxF.get(4, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception x) {
                        ctx = context(typing);
                    }
                    act = b.decide(text, ctx);
                }
                Log.i(TAG, "phone: heard and decided in " + (System.currentTimeMillis() - t1) + " ms: " + act);
                JSONObject a = act;
                ui.post(() -> carryOut(text, a, 1));
                answer(phone, "ON THE TV");
            } catch (Exception ex) {
                String why = ex.getMessage() != null && ex.getMessage().equals(ex.getMessage().toUpperCase(Locale.ROOT))
                        ? ex.getMessage() : "COULDN'T REACH THE CLOUD";
                Log.w(TAG, "phone voice failed: " + ex);
                diary("(failed, phone: " + why + ")");
                ui.post(() -> {
                    card.fail(why);
                    tell(0);
                });
                answer(phone, "ON THE TV");
            }
        });
    }

    // ---------------------------------------------------------------- the wake word

    final android.content.BroadcastReceiver wakeRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {           // from Quiet Settings
            String a = i.getStringExtra("action");
            if ("teach".equals(a)) teach();
            else if ("off".equals(a)) setWake(false);
            else if ("on".equals(a)) setWake(true);
        }
    };

    void startWake() {
        if (mic != null && mic.running) return;
        mic = new Mic(this);
        if (!mic.spot.load(Prefs.prefs(this).getString("wakeModel", ""))) {
            mic = null;
            return;
        }
        mic.woke = before -> ui.post(this::maybeWoke);
        mic.start();
    }

    int earTry;
    long busyAt;

    static String prop(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    void stopWake() {
        if (mic != null) mic.stop();
        mic = null;
    }

    String setWake(boolean on) {
        if (on && Prefs.prefs(this).getString("wakeModel", "").isEmpty()) {
            teach();
            return "TEACH ME YOUR WAKE WORD";
        }
        Prefs.prefs(this).edit().putString("wakeOn", on ? "on" : "off").apply();
        if (on) startWake();
        else stopWake();
        return on ? "WAKE WORD ON" : "WAKE WORD OFF";
    }

    /** The transcript without the wake word at its start; null if it doesn't start with it. */
    String afterWake(String text) {
        return afterWake(text, false);
    }

    /**
     * quick: the 2-second check. A one-word wake ("quiet", "quite") only counts as the first word of
     * a short phrase there (it's common in TV dialogue: "quite right..."); in the full command it
     * must be the first word.
     */
    String afterWake(String text, boolean quick) {
        for (String phrase : WAKE_PHRASES) {
            if (!phrase.contains(" ")) continue;
            String rest = after(text, phrase);
            if (rest != null) return rest;
        }
        String[] w = text.trim().split("\\s+");
        if (w.length == 0 || (quick && w.length > 3)) return null;
        String first = norm(w[0]);
        if (first.equals("quiet") || first.equals("quite") || first.equals("quiete") || first.equals("qwiet")) {
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < w.length; i++) rest.append(rest.length() > 0 ? " " : "").append(w[i]);
            return rest.toString().replaceAll("^[\\p{Punct}\\s]+", "").trim();
        }
        return null;
    }

    /** Longer phrases of everyday words survive this faint microphone far better than "hey pip". */
    static final String[] WAKE_PHRASES = {"hello quiet", "hey quiet", "hi quiet", "okay quiet", "quiet tv", "hello quite",
            "hey quite", "hi quite", "quite tv", "hey pip"};

    String after(String text, String phrase) {
        String[] said = text.trim().split("\\s+");
        String[] want = phrase.trim().split("\\s+");
        for (int skip = 0; skip <= 6 && skip < said.length; skip++)      // what came before it (room sound) is dropped
            for (int k = Math.max(1, want.length - 1); k <= want.length + 1 && skip + k <= said.length; k++) {
                StringBuilder head = new StringBuilder();
                for (int i = skip; i < skip + k; i++) head.append(said[i]);
                String h = norm(head.toString()), w = norm(phrase);
                if (distance(h, w) <= Math.max(2, Math.round(w.length() * 0.35f))) {
                    StringBuilder rest = new StringBuilder();
                    for (int i = skip + k; i < said.length; i++) rest.append(rest.length() > 0 ? " " : "").append(said[i]);
                    return rest.toString().replaceAll("^[\\p{Punct}\\s]+", "").trim();
                }
            }
        return null;
    }

    /** Teaching the wake word: say it four times (a couple more if they don't agree). */
    void teach() {
        String key = key();
        showCard();
        if (busy) return;
        boolean wasOn = mic != null && mic.running;
        if (!wasOn) {
            mic = new Mic(this);
            mic.start();
        }
        Mic m = mic;
        m.level = l -> ui.post(() -> card.level(l));         // the meter shows what the TV hears
        busy = true;
        List<List<float[]>> takes = new ArrayList<>();
        final byte[][] last = new byte[1][];
        final long[] heardAt = {android.os.SystemClock.uptimeMillis()};
        card.prompt("SAY 'HELLO QUIET'", "AS YOU NORMALLY WOULD · 1 OF 4");
        tell(1);
        m.spot.reset();
        m.spot.teaching = (frames, pcm) -> ui.post(() -> {
            takes.add(frames);
            last[0] = pcm;
            heardAt[0] = android.os.SystemClock.uptimeMillis();
            int good = Spotter.agreeing(takes);
            if (takes.size() < 4) card.prompt("GOT IT · 'HELLO QUIET' AGAIN", "THE SAME WAY · " + (takes.size() + 1) + " OF 4");
            else if (good < 3 && takes.size() < 7) card.prompt("ONCE MORE", "THE SAME WAY, PLEASE");
            else {
                m.spot.teaching = null;
                m.spot.learn(takes);
                Prefs.prefs(this).edit().putString("wakeModel", m.spot.save()).putString("wakeOn", "on").apply();
                Log.i(TAG, "wake: taught from " + takes.size() + " takes, threshold " + m.spot.threshold);
                busy = false;
                mic.woke = before -> ui.post(this::maybeWoke);
                card.done("WAKE WORD LEARNED", 2500);
                tell(3);
                byte[] lastTake = last[0];
                if (!key.isEmpty() && lastTake != null) work.execute(() -> {      // what it is, in words
                    try {
                        String w = new Brain(key).hear(Ear.wav(lastTake), "A short wake word for a TV, like Hey Pip.")
                                .replaceAll("[\\p{Punct}]", "").trim();
                        if (!w.isEmpty()) {
                            wakeText = w;
                            Prefs.prefs(this).edit().putString("wakeText", w).apply();
                            ui.post(() -> card.say("SAY '" + w + "' ANYTIME"));
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "wake: couldn't name it: " + ex);
                    }
                });
            }
        });
        // Nobody said anything for a while: stop teaching.
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (m.spot.teaching == null) return;
                if (android.os.SystemClock.uptimeMillis() - heardAt[0] > 15_000) {
                    m.spot.teaching = null;
                    busy = false;
                    card.fail("STOPPED TEACHING");
                    tell(0);
                    if (!wasOn && !"on".equals(Prefs.prefs(Voice.this).getString("wakeOn", ""))) stopWake();
                } else ui.postDelayed(this, 2000);
            }
        }, 2000);
    }

    /**
     * What to do about these words: the same steps whichever way they came (the remote's button, a card pick,
     * the rest of a longer ask, a typed test): instant asks, a search box's words or edits, then the AI.
     */
    JSONObject decision(Brain b, String text, boolean typing, java.util.concurrent.Callable<String> ctx) throws Exception {
        String title = justAnswered(text);
        if (title != null) {                                   // "play it" right after an answer about a title: that one
            String in = front.isEmpty() || front.equals(getPackageName()) ? "" : " here in " + label(front);
            Log.i(TAG, "'" + text + "' means the title just answered about: '" + title + "'");
            text = "play \"" + title + "\"" + in + " (the one I just told them about" + (in.isEmpty() ? "" : ", on screen now") + ")";
        }
        JSONObject act = quickNow(text);
        if (act == null && typing) act = inBox(b, text, false);   // in a search box: words, an edit, or a command
        if (act == null && !typing && (hidesItself() || keyboardUp())) act = outOfReach(b, text);
        if (act == null) act = fast(text);
        if (act == null) {
            String c = ctx.call();
            try {                                              // the agent: it may find things out first
                act = new Agent(this, b, Agent.model(this)).run(text, c, what -> ui.post(() -> {
                    if (card != null) card.working(what, 30_000);
                }));
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("THE KEY") || m.startsWith("OUT OF")) throw e;
                Log.w(TAG, "agent failed (" + e + "): the quick way instead");
                act = b.decide(text, c);
            }
        }
        return act;
    }

    /** Words the TV understands by itself, in English and Hindi, so they happen at once. */
    static JSONObject quick(String said) {
        String s = said.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^(hey |ok |okay )?(quiet |pip )?(please )?(can you |could you |will you )?(please )?", "")
                .replaceAll("( please| now| for me| a bit| a little| a little bit| slightly| thoda| zara)+$", "");
        try {
            // "pause" on its own is easily heard as something close ("paws", "pose")
            if (s.matches("(pause|pause it|pause this|pause (the |this )?(music|song|video|show|movie|film)|paws|pose|pours|paused|"
                    + "stop|stop it|stop (the |this )?(music|song)|ruko|ruk jao|band karo|pause karo|रुको|रोको)"))
                return media("pause");
            if (s.matches("(play|resume|play it|unpause|chalao|chala do|play karo|चलाओ)")) return media("play");
            if (s.matches("(next|next song|next one|skip|skip it|agla|agla gaana|अगला)")) return media("next");
            if (s.matches("(previous|previous song|last song|pichla|pichla gaana|पिछला)")) return media("previous");
            if (s.matches("(back|go back|peeche|wapas|पीछे)")) return new JSONObject().put("name", "go").put("args", new JSONObject().put("where", "back"));
            if (s.matches("(home|go home|ghar)")) return new JSONObject().put("name", "go").put("args", new JSONObject().put("where", "home"));
            String loud = "(the )?(volume|sound)";
            if (s.matches("(volume up|louder|(a bit |a little )?louder|turn it up|turn " + loud + " up|turn up " + loud
                    + "|(increase|raise) " + loud + "|awaaz badhao|आवाज़ बढ़ाओ)")) return volume("up");
            if (s.matches("(volume down|quieter|softer|(a bit |a little )?(quieter|softer)|turn it down|turn " + loud + " down|turn down "
                    + loud + "|(decrease|lower|reduce) " + loud + "|awaaz kam karo|आवाज़ कम करो)")) return volume("down");
            if (s.matches("(mute|chup|mute it)")) return volume("mute");
            if (s.matches("(unmute)")) return volume("unmute");
        } catch (org.json.JSONException ignored) {
        }
        return null;
    }

    /** Common asks the TV can read without the second trip: music by someone, opening an app, a title. */
    JSONObject fast(String said) throws org.json.JSONException {
        String s = said.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim()
                .replaceAll("^(hey |ok |okay )?(pip )?(please )?(can you |could you |will you )?", "").replaceAll(" please$", "");
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("^(?:play|put on|start)(?: me)?(?: some| a few)?(?: songs| music| tracks| hits)(?: by| of| from) (.+)$").matcher(s)).find()
                || (m = java.util.regex.Pattern.compile("^(?:play )?(.+?) (?:ke|ki) (?:gaane|gane|songs)(?: chalao| bajao| lagao| play karo)?$").matcher(s)).find()
                || (m = java.util.regex.Pattern.compile("^play (.+?) songs$").matcher(s)).find()) {
            String who = m.group(1).replaceAll("^(some|a few|the|my|good|nice|new|latest|best|old) ", "");
            boolean generic = who.matches("(?i)(some|any|good|nice|new|latest|best|old|hindi|malayalam|tamil|telugu|english|punjabi|"
                    + "romantic|sad|happy|party|dance|chill|lofi|lo fi|relaxing|workout|90s|80s|retro|classic|devotional|kids)( .*)?");
            return new JSONObject().put("name", "play_music").put("args", new JSONObject().put("query", who).put("kind", generic ? "any" : "artist"));
        }
        if ((m = java.util.regex.Pattern.compile("^(?:open|launch|start|go to) (.+)$").matcher(s)).find() && appNamed(m.group(1)))
            return new JSONObject().put("name", "open_app").put("args", new JSONObject().put("app", m.group(1)));
        if ((m = java.util.regex.Pattern.compile("^(?:continue|resume|carry on with|keep watching) (.+)$").matcher(s)).find() && continuing(m.group(1)) != null)
            return new JSONObject().put("name", "watch").put("args", new JSONObject().put("title", m.group(1)).put("resume", true));
        if ((m = java.util.regex.Pattern.compile("^(?:search (?:on )?youtube for|youtube (?:search )?for|search for (.+) on youtube) ?(.*)$").matcher(s)).find()) {
            String q = m.group(1) != null ? m.group(1) : m.group(2);
            if (q != null && !q.isEmpty())
                return new JSONObject().put("name", "search").put("args", new JSONObject().put("app", "youtube").put("query", q));
        }
        return null;
    }

    static JSONObject media(String a) throws org.json.JSONException {
        return new JSONObject().put("name", "media").put("args", new JSONObject().put("action", a));
    }

    static JSONObject volume(String a) throws org.json.JSONException {
        return new JSONObject().put("name", "volume").put("args", new JSONObject().put("action", a));
    }

    // ---------------------------------------------------------------- doing it

    void act(JSONObject a) {
        String name = a.optString("name");
        JSONObject x = a.optJSONObject("args");
        if (x == null) x = new JSONObject();
        try {
            switch (name) {
                case "open_app": {
                    App app = findApp(x.optString("app"));
                    if (app == null) {
                        card.say("CAN'T FIND " + x.optString("app"));
                        break;
                    }
                    if (x.optBoolean("fresh") && app.pkg.equals("com.netflix.ninja")) {   // blind: its own sure route
                        int gen = asks;
                        x.put("_home", true);
                        shellQ.execute(() -> {
                            try {
                                if (netflixHome(gen, "Netflix home")) ui.post(() -> card.done("NETFLIX HOME", 1800));
                            } catch (Exception e) {
                                Log.w(TAG, "netflix home: " + e);
                                ui.post(() -> card.fail("COULDN'T OPEN NETFLIX"));
                            }
                        });
                        break;
                    }
                    if (x.optBoolean("fresh") && !app.pkg.startsWith("com.quiet.")) {   // its own home page (ours: as they are)
                        fresh(app);
                        card.done("OPENING " + app.label + " HOME", 2400);
                        break;
                    }
                    launch(app.pkg);
                    card.done("OPENING " + app.label, 1600);
                    break;
                }
                case "tap": {                                  // a place on the screen the AI saw (look)
                    android.graphics.Point size = new android.graphics.Point();
                    getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(size);
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo((float) Math.max(0, Math.min(1, x.optDouble("x", .5))) * (size.x - 1),
                            (float) Math.max(0, Math.min(1, x.optDouble("y", .5))) * (size.y - 1));
                    boolean ok = dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80)).build(), null, null);
                    card.done(ok ? "PRESSED " + x.optString("what") : "CAN'T PRESS THERE", 1500);
                    break;
                }
                case "play_music": {
                    String q = x.optString("query");
                    startActivity(new Intent().setComponent(new ComponentName("com.quiet.listen", "com.quiet.listen.FromHome"))
                            .putExtra("playQuery", q).putExtra("playKind", x.optString("kind", "any"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    card.done("PLAYING " + q, 1600);
                    break;
                }
                case "watch": {
                    String title = x.optString("title");
                    // "What's trending / popular / new": the AI can't know today's charts, but Watch's own
                    // catalogs are live (and an empty suggestion would search for nothing).
                    if (title.trim().isEmpty() || title.toLowerCase(Locale.ROOT).matches(".*\\b(trending|popular|new|latest|top|this week|right now|recent)\\b.*")) {
                        boolean shows = title.toLowerCase(Locale.ROOT).matches(".*\\b(series|shows?|tv)\\b.*");
                        startActivity(new Intent().setComponent(new ComponentName("com.quiet.watch", "com.quiet.watch.FromHome"))
                                .putExtra("section", shows ? "SERIES" : "MOVIES").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        card.done(shows ? "POPULAR SHOWS RIGHT NOW" : "POPULAR FILMS RIGHT NOW", 1800);
                        break;
                    }
                    if (x.optBoolean("suggest")) {             // "something light and funny": a list to choose from
                        startActivity(new Intent().setComponent(new ComponentName("com.quiet.watch", "com.quiet.watch.FromHome"))
                                .putExtra("query", title).putExtra("suggest", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        card.done("FINDING SOME FOR YOU", 1800);
                        break;
                    }
                    String[] shelf = continuing(title);       // already under way: carry on, as you'd expect
                    if (shelf != null && (x.optBoolean("resume") || norm(shelf[0]).equals(norm(title)))) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shelf[2])).setPackage("com.stremio.one")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        card.done("CONTINUING " + shelf[0], 1800);
                    } else {
                        startActivity(new Intent().setComponent(new ComponentName("com.quiet.watch", "com.quiet.watch.FromHome"))
                                .putExtra("query", title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        card.done("FINDING " + title, 1600);
                    }
                    break;
                }
                case "search": {
                    String q = x.optString("query"), app = x.optString("app").toLowerCase(Locale.ROOT).trim();
                    if (!app.equals("netflix") && !app.equals("youtube")) {
                        App a2 = findApp(app.replace('_', ' '));
                        if (a2 == null) {
                            card.say("CAN'T FIND " + app.replace('_', ' '));
                            break;
                        }
                        if (quietSearch(a2.pkg)) {                 // ours: its own search takes the words (Box)
                            Intent t = new Intent("com.quiet.TYPE").setPackage(a2.pkg).putExtra("text", q);
                            if (a2.pkg.equals(front)) sendBroadcast(t, "com.quiet.permission.PREFS");
                            else {                                 // it listens once it's in front
                                launch(a2.pkg);
                                ui.postDelayed(() -> sendBroadcast(t, "com.quiet.permission.PREFS"), 1500);
                            }
                            card.done("SEARCHING " + com.quiet.px.Font.upper(a2.label), 1600);
                            break;
                        }
                        searchIn(a2, q);
                        break;
                    }
                    if (app.equals("netflix")) {                // a fixed route, the AI only says which screen it is
                        card.working("OPENING NETFLIX SEARCH", 40_000);
                        String nq = q;
                        shellQ.execute(() -> netflixSearch(nq));
                        break;
                    }
                    // YouTube: its own search intent (what worked, 2 Nov: "search YouTube for MrBeast").
                    try {
                        startActivity(new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv")
                                .putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                    card.done("SEARCHING YOUTUBE FOR " + q, 1800);
                    break;
                }
                case "media":
                    card.done(media(x.optString("action"), x.optInt("seconds", 10)), 1100);
                    break;
                case "volume":
                    card.done(volume(x.optString("action"), x.optInt("level", -1)), 1100);
                    break;
                case "go":
                    boolean home = x.optString("where").equals("home");
                    performGlobalAction(home ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK);
                    card.done(home ? "HOME" : "BACK", 900);
                    break;
                case "type": {
                    String op = x.optString("op");
                    String done = op.equals("clear") ? "CLEARED" : op.equals("word") ? "TOOK OFF A WORD" : op.equals("add") ? "ADDED" : "TYPED";
                    AccessibilityNodeInfo f = focusedText();
                    String signing = signIn(f);
                    if (signing != null) {                     // the owner's rule: sign-ins are theirs
                        refuseSignIn(signing);
                        break;
                    }
                    if (f == null && quietSearch(front)) {     // our own search: it takes the words (or the edit) itself
                        Intent t = new Intent("com.quiet.TYPE").setPackage(front).putExtra("text", x.optString("text"));
                        if (!op.isEmpty()) t.putExtra("op", op);
                        sendBroadcast(t, "com.quiet.permission.PREFS");
                        card.done(done, 1200);
                        break;
                    }
                    if (f == null && letterGrid()) {             // the app's own letter keys (Apple TV)
                        typeIntoGrid(a, label(front));
                        break;
                    }
                    if (f == null && (hidesItself() || keyboardUp())) {   // YouTube, Netflix: the TV's own connection
                        typeByShell(a);
                        break;
                    }
                    if (f == null) {
                        card.say("NO TEXT BOX HERE");
                        break;
                    }
                    String now = f.getText() == null || f.isShowingHintText() ? "" : f.getText().toString();
                    String words = Box.edit(now, op, x.optString("text"));
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, words);
                    boolean typed = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                    if (typed && !words.isEmpty() && searchBox(f))  // a search box: search it, as the keyboard's key would
                        ui.postDelayed(() -> {
                            try {
                                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                            } catch (Exception ignored) {
                            }
                        }, 300);
                    card.done(typed ? done : "COULDN'T TYPE THERE", 1200);
                    break;
                }
                case "tv":
                    if (x.optString("action").equals("off")) {
                        card.done("GOODNIGHT", 1200);
                        ui.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 1300);
                    } else {
                        String got = input(x.optString("input"));
                        if (got == null) card.say("CAN'T FIND THAT INPUT");
                        else card.done("SWITCHING TO " + got, 1600);
                    }
                    break;
                case "say":
                    card.say(x.optString("text").replaceAll("\\*+|__|`", "").replaceAll("\\s+", " ").trim());   // no markdown on the card
                    break;
                case "choose": {                               // a short list: picked with the remote or by voice
                    org.json.JSONArray o = x.optJSONArray("options");
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    java.util.List<String> todo = new java.util.ArrayList<>();
                    for (int i = 0; o != null && i < o.length() && i < 6; i++) {
                        JSONObject op = o.optJSONObject(i);
                        if (op == null) continue;
                        labels.add(op.optString("label"));
                        todo.add(op.optString("ask"));
                    }
                    if (labels.isEmpty()) {
                        card.say(x.optString("text"));
                        break;
                    }
                    labels = apart(labels);
                    listAt = System.currentTimeMillis();
                    choices = todo;
                    choiceLabels = labels;
                    card.choose(x.optString("text"), labels);
                    break;
                }
                case "youtube": {
                    // YouTube on this TV takes no words to play and shows nothing to press, but plays a video or a
                    // list it's given: the top video for the words (found here), or their own mix (signed in there).
                    String q = x.optString("query").trim();
                    String vid = x.optString("id").trim();
                    if (vid.matches("[A-Za-z0-9_-]{11}")) {        // one the agent found and chose
                        long from = q.isEmpty() ? -1 : History.leftAt(this, "com.google.android.youtube.tv", q);
                        playOnYouTube(vid, from);
                        card.done(from > 0 ? "BACK WHERE YOU LEFT IT" : "PLAYING " + com.quiet.px.Font.upper(q.isEmpty() ? "IT" : q), 1800);
                        break;
                    }
                    if (q.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?list=RDMM"))
                                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        card.done("PLAYING YOUR YOUTUBE MIX", 1800);
                        break;
                    }
                    card.working("FINDING " + q, 12000);
                    work.execute(() -> {
                        String id = topVideo(q);
                        // One they were watching here, unfinished: back where they left it.
                        long resume = History.leftAt(this, "com.google.android.youtube.tv", q);
                        ui.post(() -> {
                            try {
                                if (id == null) {                  // couldn't tell: its results, to pick from
                                    act(new JSONObject().put("name", "search").put("args", new JSONObject().put("app", "youtube").put("query", q)));
                                    return;
                                }
                                playOnYouTube(id, resume);
                                card.done(resume > 0 ? "BACK WHERE YOU LEFT IT" : "PLAYING " + q, 1800);
                            } catch (Exception e) {
                                Log.w(TAG, "couldn't play on YouTube: " + e);
                                card.fail("COULDN'T DO THAT");
                            }
                        });
                    });
                    break;
                }
                case "youtube_play":                           // a YouTube link from the phone
                    playOnYouTube(x.optString("id"));
                    card.done("PLAYING ON YOUTUBE", 1800);
                    break;
                case "listen_play":                            // an Apple Music link
                    startActivity(new Intent().setComponent(new ComponentName("com.quiet.listen", "com.quiet.listen.FromHome"))
                            .putExtra("playId", x.optString("id")).putExtra("playKind", x.optString("kind"))
                            .putExtra("playTitle", Font_upper(x.optString("title"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    card.done("PLAYING IN LISTEN", 1800);
                    break;
                case "open_link":
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(x.optString("url")))
                                .setPackage(x.optString("pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        launch(x.optString("pkg"));
                    }
                    card.done("OPENING " + x.optString("label"), 1800);
                    break;
                case "setting":
                    card.done(setting(x.optString("name"), x.optString("value")), 1600);
                    break;
                case "select": {
                    AccessibilityNodeInfo n = find(x.optString("text"));
                    if (n == null) {
                        card.say("CAN'T SEE " + x.optString("text") + " HERE");
                        break;
                    }
                    if (paying(words(n))) {
                        card.say("I DON'T BUY OR RENT ANYTHING · THAT'S YOURS TO PRESS");
                        break;
                    }
                    String what = x.optString("text");
                    card.working("PRESSING " + com.quiet.px.Font.upper(what), 8000);
                    boolean sawIt = x.optBoolean("_seen");
                    shellQ.execute(() -> {
                        boolean ok = pressAsRemote(n);
                        ui.post(() -> {
                            if (ok) card.done("SELECTED " + com.quiet.px.Font.upper(what), 1300);
                            else if (!sawIt && !blind())       // the focus wouldn't go there: the arrows, by sight
                                reach("Move the highlight to \"" + what + "\" with the arrows, and press OK once it is the lit one.");
                            else card.fail("CAN'T PRESS THAT HERE");
                        });
                    });
                    break;
                }
                case "timer":
                    card.done(timer(x.optString("what"), x.optInt("minutes", 0)), 2200);
                    break;
                case "wake": {
                    String w = x.optString("action");
                    if (w.equals("teach")) {
                        card.dismiss();
                        ui.postDelayed(this::teach, 600);
                    } else card.done(setWake(w.equals("on")), 1600);
                    break;
                }
                default:
                    card.say("NOT SURE WHAT TO DO");
            }
        } catch (Exception e) {
            Log.w(TAG, "couldn't " + name + ": " + e);
            card.fail("COULDN'T DO THAT");
        }
    }

    /**
     * Card labels that tell the choices apart on a short line: where one starts like another (two highlights videos,
     * Hindi and English), its first two words, then what's different. "|" becomes "·".
     */
    static java.util.List<String> apart(java.util.List<String> labels) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String l = labels.get(i).replace(" | ", " · ").trim();
            int common = 0;
            for (int j = 0; j < labels.size(); j++) {
                if (j == i) continue;
                String o = labels.get(j).replace(" | ", " · ").trim();
                int k = 0;
                while (k < l.length() && k < o.length() && l.charAt(k) == o.charAt(k)) k++;
                common = Math.max(common, k);
            }
            if (common > 15) {
                String[] w = l.split(" ");
                String head = w.length > 1 ? w[0] + " " + w[1] : w[0];
                int cut = l.lastIndexOf(' ', common);
                String rest = l.substring(cut < 0 ? common : cut).replaceFirst("^[\\s·]+", "");
                if (!rest.isEmpty() && !rest.startsWith(head)) l = head + " … " + rest;
            }
            out.add(l);
        }
        return out;
    }

    static String Font_upper(String s) {
        return com.quiet.px.Font.upper(s == null ? "" : s);
    }

    String media(String action, int seconds) {
        MediaController c = playing();
        if (action.equals("play")) {
            // "Play" is for the app in front: its paused video, or (a video app with nothing paused) the
            // thing chosen there, never the music paused in Listen behind it.
            MediaController mine = sessionOf(front);
            PlaybackState was = c == null ? null : c.getPlaybackState();
            boolean on = was != null && was.getState() == PlaybackState.STATE_PLAYING;
            if (mine != null) c = mine;
            else if (!on && videoApp(front)) return pressFocused() ? "PLAYING" : "PICK SOMETHING TO PLAY";
        }
        if (c == null) return "NOTHING IS PLAYING";
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState st = c.getPlaybackState();
        boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        switch (action) {
            case "pause": t.pause(); return "PAUSED";
            case "play": t.play(); return "PLAYING";
            case "toggle": if (on) t.pause(); else t.play(); return on ? "PAUSED" : "PLAYING";
            case "next": t.skipToNext(); return "NEXT";
            case "previous": t.skipToPrevious(); return "PREVIOUS";
            case "restart": t.seekTo(0); return "FROM THE START";
            case "forward":
            case "rewind": {
                if (st == null) return "CAN'T SKIP HERE";
                long pos = st.getPosition();
                if (on) pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                int s = Math.max(1, seconds);
                t.seekTo(Math.max(0, pos + (action.equals("forward") ? s : -s) * 1000L));
                return (action.equals("forward") ? "+" : "-") + s + " SECONDS";
            }
        }
        return "OK";
    }

    // ---------------------------------------------------------------- inside other apps

    /** The words on screen in the app in front, for "what's this", "play the second one", "skip intro". */
    List<AccessibilityNodeInfo> visible() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return out;
        }
        if (root == null) return out;
        // Fresh from the app, not Android's copy: some apps (Apple TV) rarely say they changed, and the copy went stale
        // (3 Nov: "the first one" was read off a page that had gone, and OK typed an "a"). A small tree costs little.
        root.refresh();
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && out.size() < 80 && seen++ < 1500) {
            AccessibilityNodeInfo n = q.poll();
            if (n != null && seen < 400) n.refresh();
            if (n == null || !n.isVisibleToUser()) continue;
            if (!words(n).isEmpty()) out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return out;
    }

    static String words(AccessibilityNodeInfo n) {
        CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    String onScreen() {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> dup = new java.util.HashSet<>();
        List<AccessibilityNodeInfo> all = visible();
        int letters = 0;
        for (AccessibilityNodeInfo n : all) if (words(n).matches("[A-Za-z]")) letters++;
        boolean keys = letters >= 20;                          // an app's own letter keys: not things to choose
        if (keys) b.append("(its letter keys are up)");
        for (AccessibilityNodeInfo n : all) {
            String w = words(n);
            if (keys && w.matches("[A-Za-z0-9]|abc|123|Space|Delete|Clear|&?123|ABC")) continue;
            if (w.length() > 80) w = w.substring(0, 80);
            if (!dup.add(w)) continue;
            if (b.length() + w.length() > 1400) break;
            b.append(b.length() > 0 ? " | " : "").append(w);
        }
        return b.toString();
    }

    AccessibilityNodeInfo find(String text) {
        String want = norm(text);
        if (want.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : visible()) {
            String w = norm(words(n));
            if (w.isEmpty()) continue;
            int score = w.equals(want) ? 0 : w.startsWith(want) ? 1 : w.contains(want) ? 2 : want.contains(w) && w.length() > 3 ? 3 : 99;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return bestScore < 99 ? best : null;
    }

    /** Clicks the thing (or the nearest clickable thing around it); failing that, puts the focus on it. */
    /**
     * Presses it the way the remote would: the focus put on it (and checked that it's there), then OK. TV apps often
     * ignore a plain click (the Play Store's search, Apple TV's CLEAR, 3 Nov). If the focus won't go there, the click.
     * Off the main thread. Never anything that buys, rents or pays.
     */
    boolean pressAsRemote(AccessibilityNodeInfo n) {
        if (paying(words(n))) return false;
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent()) {
            if (!c.isFocusable()) continue;
            if (!c.isFocused()) c.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            try {
                Thread.sleep(350);
                AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null) f.refresh();
                c.refresh();
                // The real focus, read fresh, must be the very thing: OK goes wherever the focus truly is.
                boolean same = f != null && (f.equals(c) || f.equals(n)) && words(f).equals(words(c));
                if (f != null && !same) {
                    // Not there, and the focus is elsewhere: no click either, which in some apps acts on the focus
                    // (3 Nov: Apple TV typed an "a" from its letter keys). The caller steers there by sight instead.
                    Log.i(TAG, "pressing: the focus is on '" + words(f) + "', not '" + words(c) + "': not pressed");
                    return false;
                }
                if (same && !paying(words(f))) {
                    Shell.run(this, "input keyevent 23", () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "pressing as the remote: " + e);
            }
            break;
        }
        if (press(n)) return true;
        android.graphics.Rect r = new android.graphics.Rect();     // last: a tap where it is (some apps take touch)
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        return dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80)).build(), null, null);
    }

    /** The app says, in words, what has its focus (Apple TV doesn't: its focus reads as blank). */
    boolean focusReadable() {
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) return false;
            f.refresh();
            return !words(f).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Words on something that spends money: never pressed by the voice (the owner's rule). */
    static boolean paying(String words) {
        return words.toLowerCase(Locale.ROOT).matches(".*\\b(buy|rent|purchase|subscribe|pay|payment|upgrade|plans?)\\b.*");
    }

    static boolean press(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent())
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent())
            if (c.isFocusable() && c.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true;
        return false;
    }

    /**
     * Search in any app: open it, find its search (a text box we can fill, the app's own letter keys, or something
     * called Search to press first), and type. Checked as it goes: a search that never opened is said plainly,
     * never "searched" (3 Nov: Apple TV's Search was "pressed" with its menu closed, and nothing happened).
     */
    void searchIn(App app, String q) {
        Intent direct = searchLink(app.pkg, q);
        if (direct != null) {                                  // the app's own search link (tested): straight to its results
            try {
                startActivity(direct);
                Log.i(TAG, "search in " + app.label + ": its own search link, for '" + q + "'");
                card.working("SEARCHING " + com.quiet.px.Font.upper(app.label), 15000);
                checkSearched(app, q, asks);
                return;
            } catch (Exception e) {
                Log.w(TAG, "search in " + app.label + ": its link refused: " + e);
            }
        }
        launch(app.pkg);
        card.working("SEARCHING " + com.quiet.px.Font.upper(app.label), 30000);
        final int[] tries = {0};
        final boolean[] pressedSearch = {false};
        int gen = asks;
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (gen != asks) return;                       // a newer ask: this one is over
                if (++tries[0] > 16) {
                    if (keyboardUp()) {                        // the TV's keyboard is up for a box it hides
                        Log.i(TAG, "search in " + app.label + ": the keyboard is up, typing into it");
                        typeByShell(typeAct(q, ""));
                        return;
                    }
                    Log.w(TAG, "search in " + app.label + ": never reached its search (" + visible().size() + " readable things)");
                    diary("(couldn't reach " + app.label + "'s search for '" + q + "')");
                    if (!blind()) {                            // pressing by name didn't get there: by sight
                        reach(app.label + " is in front. Go to its search (a search icon, a menu on the left, or a box at the top), "
                                + "press OK on it, then type \"" + q + "\" with type.");
                        return;
                    }
                    card.fail("COULDN'T GET TO " + com.quiet.px.Font.upper(app.label) + "'S SEARCH");
                    return;
                }
                if (!app.pkg.equals(front)) {
                    ui.postDelayed(this, 500);
                    return;
                }
                String signing = signIn(focusedText());
                if (signing != null) {                         // it wants a sign-in first (3 Nov: Hotstar's mobile number box)
                    refuseSignIn(signing);
                    return;
                }
                if (letterGrid()) {                            // its own letter keys are up: type with the TV's keys
                    Log.i(TAG, "search in " + app.label + ": its letter keys are on screen");
                    typeIntoGrid(typeAct(q, ""), app.label);
                    return;
                }
                AccessibilityNodeInfo box = focusedText();
                if (box == null) {
                    for (AccessibilityNodeInfo n : visible())
                        if (n.isEditable()) {
                            box = n;
                            break;
                        }
                }
                if (box != null && signIn(box) != null) {
                    refuseSignIn(signIn(box));
                    return;
                }
                if (box != null) {
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, q);
                    box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                        checkSearched(app, q, gen);
                        return;
                    }
                }
                if (!pressedSearch[0] && tries[0] >= 3) {      // (a moment for the app to draw first)
                    if (sideMenuToSearch()) {
                        pressedSearch[0] = true;
                        Log.i(TAG, "search in " + app.label + ": through its side menu");
                    } else {
                        AccessibilityNodeInfo s = find("search");
                        if (s != null) {
                            pressedSearch[0] = true;
                            shellQ.execute(() -> Log.i(TAG, "search in " + app.label + ": '" + words(s) + "' "
                                    + (pressAsRemote(s) ? "pressed" : "wouldn't press")));
                        }
                    }
                    if (pressedSearch[0]) tries[0] = Math.max(tries[0], 8);   // its search page comes up: a few moments more
                }
                ui.postDelayed(this, 500);
            }
        };
        ui.postDelayed(step, 1200);
    }

    /**
     * Search links that apps on this TV really follow, each one seen working (3 Nov). The standard search request
     * isn't one: the Play Store opened its search empty, Hotstar refused it.
     */
    static Intent searchLink(String pkg, String q) {
        if (pkg.equals("com.android.vending"))
            return new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return null;
    }

    /** A few seconds on: the words searched for are on screen in that app (it says so), or the card says it didn't take. */
    void checkSearched(App app, String q, int gen) {
        ui.postDelayed(() -> {
            if (gen != asks) return;
            String seen = onScreen().toLowerCase(Locale.ROOT);
            boolean ok = app.pkg.equals(front) && seen.contains(q.toLowerCase(Locale.ROOT).trim());
            Log.i(TAG, "search in " + app.label + ": '" + q + "' " + (ok ? "is on screen" : "isn't on screen (in front: " + front + ")"));
            diary("'" + q + "' -> searched in " + app.label + (ok ? "" : " (didn't take)"));
            if (ok) card.done("SEARCHING " + com.quiet.px.Font.upper(app.label) + " FOR " + com.quiet.px.Font.upper(q), 1800);
            else card.fail("THAT SEARCH DIDN'T TAKE IN " + com.quiet.px.Font.upper(app.label));
        }, 3500);
    }

    /** The app shows its own letter keys (a search page with an A-Z grid, like Apple TV's), not the TV's keyboard. */
    boolean letterGrid() {
        int n = 0;
        for (AccessibilityNodeInfo v : visible()) if (words(v).matches("[A-Za-z]")) n++;
        return n >= 20;
    }

    /** What the app's own search shows as typed ("Suggestions, severance" in Apple TV); null if it doesn't say. */
    String gridSays() {
        for (AccessibilityNodeInfo v : visible()) {
            String w = words(v);
            if (w.startsWith("Suggestions, ")) return w.substring(13).trim();
        }
        return null;
    }

    /**
     * A side menu with the current section marked ("Sidebar, Home" in Apple TV): LEFT opens it on that section,
     * UP as many times as Search is above it, OK. False if there's no such menu on screen.
     */
    boolean sideMenuToSearch() {
        List<AccessibilityNodeInfo> v = visible();
        int s = -1, c = -1;
        for (int i = 0; i < v.size(); i++) {
            String w = words(v.get(i));
            if (s < 0 && w.equalsIgnoreCase("search")) s = i;
            if (c < 0 && w.startsWith("Sidebar, ")) c = i;
        }
        if (s < 0 || c <= s || c - s > 8) return false;
        StringBuilder cmd = new StringBuilder("input keyevent 21; sleep 0.8; input keyevent");
        for (int i = 0; i < c - s; i++) cmd.append(" 19");
        cmd.append("; sleep 0.5; input keyevent 23");
        shellQ.execute(() -> {
            try {
                Shell.run(this, cmd.toString(), () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
            } catch (Exception e) {
                Log.w(TAG, "side menu: " + e);
            }
        });
        return true;
    }

    /**
     * Types into an app's own letter keys with the TV's keys (Shell): every change is the whole search typed afresh,
     * the box emptied first with a burst of deletes, plenty to spare (an extra delete in an empty letter-key box does
     * nothing; YouTube's hidden box, where it means BACK, never comes here), then the words in one go. (3 Nov: Apple TV
     * ignores a press on its CLEAR, and deletes counted from a guess left "slowhisburn".) What it held: what we typed
     * there, else what it says ("Suggestions, ...", refreshed only now and then).
     */
    void typeIntoGrid(JSONObject act, String appLabel) {
        JSONObject x = act.optJSONObject("args");
        String op = x == null ? "" : x.optString("op"), said = x == null ? "" : x.optString("text");
        String pkg = front;
        String says = gridSays();
        String[] t = lastTyped;
        boolean known = t != null && t[0].equals(pkg);
        String now = known ? t[1] : says != null ? says : "";
        String words = Box.edit(now, op, said);
        int del = known || says != null ? Math.max(now.length(), says == null ? 0 : says.length()) + 6 : 40;
        String label = com.quiet.px.Font.upper(appLabel);
        card.working("TYPING IN " + label, 15000);
        shellQ.execute(() -> {
            StringBuilder cmd = new StringBuilder("input keyevent");
            for (int i = 0; i < Math.min(del, 60); i++) cmd.append(" 67");
            String w = Shell.typed(words);
            if (!w.isEmpty()) cmd.append("; sleep 0.8; input text '").append(w).append("'");
            try {
                Shell.run(this, cmd.toString(), () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
                typedInto(pkg, words);
                Log.i(TAG, "typed into " + pkg + "'s letter keys: held '" + now + "' (it says '" + says + "'), " + del
                        + " deletes, now '" + words + "'");
                diary("'" + words + "' -> typed into " + appLabel + "'s letter keys");
                ui.post(() -> card.done(words.isEmpty() ? "CLEARED" : "SEARCHING " + label + " FOR " + com.quiet.px.Font.upper(words), 1800));
            } catch (Exception e) {
                Log.w(TAG, "typing into the letter keys: " + e);
                ui.post(() -> card.fail("ONLY THE PHONE CAN TYPE HERE"));
            }
        });
    }

    /** The last picture of the screen was all black: the app in front won't be seen (Netflix, Apple TV, a protected film). */
    static boolean blank(int[] thumb) {
        if (thumb == null) return false;
        long sum = 0;
        for (int v : thumb) sum += v;
        return sum / (float) thumb.length < 3f;
    }

    /** Apps known to show only black to pictures of the screen (checked 3 Nov). */
    boolean blind() {
        return front.equals("com.netflix.ninja");             // (Apple TV hides only its videos)
    }

    /** Home's own settings, by voice (the same ones Quiet Settings changes). */
    String setting(String name, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        android.content.SharedPreferences.Editor e = Prefs.prefs(this).edit();
        switch (name) {
            case "home_sound":
                String m = v.contains("off") || v.contains("silen") ? "off" : v.contains("nature") ? "nature" : "on";
                e.putString("soundMode", m).apply();
                return m.equals("off") ? "HOME SOUND OFF" : m.equals("nature") ? "NATURE ONLY" : "MUSIC + NATURE";
            case "loudness":
                String l = v.contains("soft") || v.contains("quiet") || v.contains("low") ? "soft" : v.contains("loud") || v.contains("high") ? "loud" : "normal";
                e.putString("soundLevel", l).apply();
                return "HOME SOUND " + l.toUpperCase(Locale.ROOT);
            case "music_party":
                boolean on = !(v.contains("off") || v.contains("no") || v.contains("stop"));
                e.putString("party", on ? "on" : "off").apply();
                return on ? "MUSIC PARTY ON" : "MUSIC PARTY OFF";
            case "motion":
                String mo = v.contains("instant") || v.contains("fast") || v.contains("off") ? "instant" : "standard";
                e.putString("motion", mo).apply();
                return "MOTION " + mo.toUpperCase(Locale.ROOT);
        }
        return "CAN'T CHANGE THAT YET";
    }

    /** Sleep timers: the music stops (Listen winds it down), or the TV goes to sleep. 0 cancels. */
    String timer(String what, int minutes) {
        if (what.equals("tv")) {
            if (tvOff != null) ui.removeCallbacks(tvOff);
            tvOff = null;
            if (minutes <= 0) return "TV TIMER OFF";
            tvOff = () -> {
                tvOff = null;
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            };
            ui.postDelayed(tvOff, minutes * 60_000L);
            return "TV SLEEPS IN " + minutes + " MIN";
        }
        sendBroadcast(new Intent("com.quiet.listen.SLEEP").setPackage("com.quiet.listen").putExtra("minutes", minutes),
                "com.quiet.permission.PREFS");                // Listen winds down in the background
        return minutes == 0 ? "MUSIC TIMER OFF" : minutes < 0 ? "MUSIC STOPS AFTER THIS SONG" : "MUSIC STOPS IN " + minutes + " MIN";
    }

    /**
     * "Play it", "watch that", "play this one" right after the voice answered about a title (in quotes, as answers
     * give them): that title. Null otherwise. (3 Nov: after "Dark Matter is a sci-fi thriller...", "play it" became a
     * bare play, which Apple TV ignored.)
     */
    String justAnswered(String said) {
        String s = plain(said);
        if (!s.matches("(please )?(play|watch|start|put on|open) (it|that|this|that one|this one|the same)( please)?")) return null;
        String[] last = recent.peekLast();
        if (last == null || System.currentTimeMillis() - Long.parseLong(last[0]) > 180_000 || !last[2].contains("[say")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]{2,90})\"|'([^']{2,90})'").matcher(last[2]);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        // Unquoted: the name the answer starts with ("Tenzing is a movie about...")
        m = java.util.regex.Pattern.compile("\\[say text=([A-Z0-9][^.,;]{0,60}?) (is|was|are|follows|tells|stars) ").matcher(last[2]);
        return m.find() ? m.group(1).trim() : null;
    }

    void remember(String said, String did) {
        recent.add(new String[]{String.valueOf(System.currentTimeMillis()), said, did});
        while (recent.size() > 4) recent.poll();
        diary("'" + said + "' -> " + did);
    }

    // ---------------------------------------------------------------- systemic safeguards
    //
    // Three rules every action goes through, so whole kinds of mistakes can't happen:
    //  1. Live, not remembered: the AI can't know what's trending, new or top today, so such asks go
    //     to live catalogs (Watch's Stremio rows, Apple Music's Top 100 chart), never its memory.
    //  2. Nothing empty: a missing title, song, query or app is taken from what was actually said,
    //     or the voice asks, instead of doing something with nothing.
    //  3. Checked, not assumed: a few seconds after acting, the voice checks the right app came to
    //     the front; if not it says so plainly and the diary records it (no false "SEARCHING...").

    static final java.util.regex.Pattern LIVE = java.util.regex.Pattern.compile(
            "\\b(trending|popular|new releases?|newest|latest|top|charts?|this week|right now|these days|recent|hit)\\b");

    JSONObject sanity(String said, JSONObject a) {
        try {
            String name = a.optString("name");
            JSONObject x0 = a.optJSONObject("args");
            // (5) asked, not told: a plain question never starts something playing (3 Nov: "what was I watching?"
            // played it). The answer instead, and "play it" next plays it.
            if (name.matches("youtube|play_music|listen_play") && question(said) && x0 != null) {
                String what = x0.optString("query", x0.optString("title", ""));
                return what.isEmpty() ? a : ask("THAT WAS '" + what + "' · SAY PLAY IT");
            }
            JSONObject x = a.optJSONObject("args");
            if (x == null) {
                x = new JSONObject();
                a.put("args", x);
            }
            if (x.optBoolean("_agent")) return a;               // the agent looked things up: its choice stands
            String s = said == null ? "" : said.toLowerCase(Locale.ROOT);
            boolean live = LIVE.matcher(s).find();
            switch (name) {
                case "play_music": {
                    // What's left once descriptions are taken out: if nothing, they named nothing in particular.
                    String q = (x.optString("query") + " ").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ");
                    String core = q.replaceAll("\\b(some|any|good|nice|great|best|cool|awesome|lovely|music|songs?|tracks?|"
                            + "something|anything|the|a|please|play|me|my|mine|favou?rites?|favou?rite|i|like|love|to|listen|on|"
                            + "apple|for|of|gaane|gane)\\b", " ").replaceAll("\\s+", " ").trim();
                    if (live && !s.contains(" by ") && !s.contains(" ke ")) {        // today's songs: Apple's live chart
                        x.put("query", "Top 100: India");
                        x.put("kind", "playlist");
                    } else if (core.isEmpty() && !"artist".equals(x.optString("kind"))) {
                        x.put("query", "your favourites");                          // their own taste, never a song called "Good Music"
                        x.put("kind", "mine");
                    } else if (core.matches("(relaxing|relax|chill|calm|peaceful|soft|romantic|love|sad|happy|party|dance|"
                            + "workout|gym|sleep|focus|study|upbeat|energetic|feel good|morning|evening|rainy|road trip|devotional)"
                            + "( (bollywood|hindi|malayalam|tamil|english|punjabi))?")) {
                        x.put("kind", "playlist");                                   // a mood: a playlist for it, not a title
                    }
                    break;
                }
                case "watch":
                    if (live) {
                        x.put("title", "trending" + (s.matches(".*\\b(series|shows?|serials?)\\b.*") ? " series" : ""));
                        x.put("suggest", true);
                    } else if (x.optString("title").trim().isEmpty()) x.put("title", said);
                    break;
                case "search":
                case "youtube":
                    if (x.optString("query").trim().isEmpty() && !name.equals("youtube")) return ask("WHAT SHOULD I SEARCH FOR?");
                    break;
                case "type":
                    if (x.optString("text").trim().isEmpty() && x.optString("op").matches("|add")) return ask("WHAT SHOULD I TYPE?");
                    break;
                case "select":
                    if (x.optString("text").trim().isEmpty()) return ask("WHICH ONE?");
                    break;
                case "open_app":
                    if (x.optString("app").trim().isEmpty()) return ask("WHICH APP?");
                    break;
            }
        } catch (org.json.JSONException ignored) {
        }
        return a;
    }

    /** "The first one", "the second video", "the last title": counted on screen (unless the card shows a list). */
    boolean ordinal(String said) {
        // A list on the card just now (it may have timed out while the AI decided): the ordinal was about it.
        if (choosing() || System.currentTimeMillis() - listAt < 60_000) return false;
        // "the last song I listened to", "the first video I watched today": memory, not the screen
        if (plain(said).matches(".*\\b(i|we)( was| were| have| had)? (watch|watched|watching|listen|listened|listening|play|played|playing|saw|seen|heard|put on)\\b.*"))
            return false;
        return plain(said).matches(".*\\b(the )?(first|second|third|fourth|fifth|last|1st|2nd|3rd|4th|5th|next|top|"
                + "pehla|pehli|doosra|doosri|teesra|teesri) ?(one|video|title|film|movie|show|episode|result|item|song)?\\b.*")
                && plain(said).matches(".*\\b(first|second|third|fourth|fifth|last|1st|2nd|3rd|4th|5th|top|pehla|pehli|doosra|doosri|teesra|teesri)\\b.*");
    }

    /** A plain question (what, which, who, when...) with nothing in it asking to play or open. */
    static boolean question(String said) {
        String raw = said.trim();
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", " ").replaceAll("\\s+", " ").trim()
                .replaceFirst("^(hey |ok |okay |hello )?(quiet |quite |pip )?(tell me |can you tell me |do you know )?", "");
        boolean asked = raw.endsWith("?") || s.matches("(what|what's|whats|which|who|who's|when|where|why|how|did i|have i|was i)\\b.*")
                || s.matches(".*\\b(what is it about|what's it about|whats it about|is it good|is it any good|who is in it|who's in it|tell me about)\\b.*");
        // A request asked as a question ("can you open Netflix?", "can you go home?") is still a request.
        return asked && !s.matches(".*\\b(play|put on|open|start|resume|continue|watch it|watch that|show me|turn|switch|go|take me|"
                + "pause|stop|search|find|type|clear|delete|set|change|mute|unmute|increase|decrease|raise|lower|skip|next|"
                + "back|home|launch|close|exit|sleep|lagao|chalao|kholo|band)\\b.*");
    }

    static JSONObject ask(String question) throws org.json.JSONException {
        return new JSONObject().put("name", "say").put("args", new JSONObject().put("text", question));
    }

    /** Which apps should be in front a moment after this action (empty: nothing to check). */
    java.util.Set<String> expected(JSONObject a) {
        java.util.Set<String> e = new java.util.HashSet<>();
        JSONObject x = a.optJSONObject("args");
        String app = x == null ? "" : x.optString("app").toLowerCase(Locale.ROOT);
        switch (a.optString("name")) {
            case "open_app": {
                App f = findApp(app);
                if (f != null) e.add(f.pkg);
                break;
            }
            case "play_music":
            case "listen_play":
                e.add("com.quiet.listen");
                break;
            case "watch":
                e.add("com.quiet.watch");
                e.add("com.stremio.one");
                break;
            case "youtube":
            case "youtube_play":
                e.add("com.google.android.youtube.tv");
                break;
            case "search":
                if (app.contains("youtube")) e.add("com.google.android.youtube.tv");
                else if (app.contains("netflix")) e.add("com.netflix.ninja");
                else {
                    App f = findApp(app);
                    if (f != null) e.add(f.pkg);
                }
                break;
        }
        return e;
    }

    /** The app that really has the screen (the focused window), whatever overlays sit on top. */
    String activePackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Exception ignored) {
        }
        return front;
    }

    /**
     * Checked and fixed: an ask to play is done when something plays, not when the app opens. A little later
     * the app's media session must be playing; if not, the voice looks and presses what it takes (a profile
     * picker, a paused player: OK or play), once; for Apple Music (ours, it plays out of sight) it says so.
     */
    void verifyPlaying(String said, JSONObject a, int step) {
        String name = a.optString("name");
        JSONObject x = a.optJSONObject("args");
        String pkg;
        long wait;
        if (name.equals("youtube")) {
            pkg = "com.google.android.youtube.tv";
            wait = 11_000;
        } else if (name.equals("play_music") || name.equals("listen_play")) {
            pkg = "com.quiet.listen";
            wait = 32_000;                                     // Apple's player, cold, can take ~20 s
        } else if (name.equals("media") && x != null && x.optString("action").equals("play") && !front.startsWith("com.quiet.")
                && hasSession(front)) {                        // only an app that reports its player (not Apple TV: a
                                                               // "fix" there would press OK on a show that's playing)
            pkg = front;
            wait = 4_000;
        } else return;
        if (card == null || card.state == Card.FAIL || x != null && (x.optBoolean("_fixing") || x.optBoolean("_seen"))) return;   // one try
        int mine = asks;
        ui.postDelayed(() -> {
            if (mine != asks) return;                          // a newer ask: never speak over it
            if (playingIn(pkg)) {
                Log.i(TAG, "checked: '" + said + "' is playing in " + pkg);
                return;
            }
            Log.w(TAG, "not playing: '" + said + "' (" + brief(a) + ") in " + pkg + ", in front " + front);
            diary("(not playing after '" + said + "': " + brief(a) + ")");
            showCard();
            if (pkg.equals("com.quiet.listen")) {
                card.fail("APPLE MUSIC DIDN'T START · TRY AGAIN");
                return;
            }
            try {                                              // one try at fixing it, by sight and the remote's buttons
                JSONObject fix = new JSONObject(a.toString());
                fix.getJSONObject("args").put("_fixing", true);
                look(said + " (it was started but isn't playing yet: whatever is in the way, a profile to pick, a "
                        + "paused or stopped player, an ad's skip button, press what makes it play, then say what happened)", fix, step);
            } catch (Exception e) {
                card.fail("IT DIDN'T START PLAYING");
            }
        }, wait);
    }

    /** The words on what has the focus in the app in front, if it lets us read it (Apple TV, Settings, Play Store). */
    String focusedWords() {
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (f == null || f.isEditable()) return "";
            String w = words(f);
            for (int i = 0; w.isEmpty() && i < f.getChildCount() && i < 6; i++) {
                AccessibilityNodeInfo c = f.getChild(i);
                if (c != null) w = words(c);
            }
            return w.length() > 120 ? w.substring(0, 120) : w;
        } catch (Exception e) {
            return "";
        }
    }

    /** That app has a media session at all (it reports its player). */
    boolean hasSession(String pkg) {
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Listener.class)))
                if (pkg.equals(c.getPackageName())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** That app's media session is playing right now. */
    boolean playingIn(String pkg) {
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Listener.class)))
                if (pkg.equals(c.getPackageName()) && c.getPlaybackState() != null
                        && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Netflix search by a fixed route, blind: Netflix shows only black to pictures of the screen and nothing to read,
     * so every step is one that's right from where the step before left it (worked out with a camera on the TV,
     * 3 Nov). Already on the search we typed into: just the new words. Otherwise Netflix starts afresh, which always
     * opens on "Choose a Profile" with the last one lit; it plays a preview there the moment it's ready (its media
     * session says so, ~25 s). RIGHT (off the edit pencil, if that was lit), OK: home. UP to the top menu, which
     * lands on Home; LEFT is the search icon, which opens search; OK into its letters; the words, two at a time.
     * (Netflix's links open its home on another profile, not search: not used.)
     */
    void netflixSearch(String q) {
        String nf = "com.netflix.ninja";
        int gen = asks;
        try {
            if (stillOurSearch(nf)) {
                shellType(nf, "", q, false, key(), null, null);
                return;
            }
            if (!netflixHome(gen, q)) return;
            if (gen != asks || !nf.equals(front)) return;
            ui.post(() -> card.working("OPENING NETFLIX SEARCH", 60_000));
            Shell.run(this, "input keyevent 19 19; sleep 0.4; input keyevent 19 19; sleep 0.6; input keyevent 21; sleep 1.5; "
                    + "input keyevent 23; sleep 0.8", null);
            if (gen != asks || !nf.equals(front)) return;
            ui.post(() -> card.working("TYPING " + com.quiet.px.Font.upper(q), 60_000));
            Shell.run(this, Shell.typing(q, true), null);
            typedInto(nf, q);
            Log.i(TAG, "netflix search: typed '" + q + "'");
            diary("'" + q + "' -> searched in Netflix");
            ui.post(() -> card.done("SEARCHING NETFLIX FOR " + com.quiet.px.Font.upper(q), 2200));
        } catch (Exception e) {
            Log.w(TAG, "netflix search: " + e);
            ui.post(() -> card.fail("COULDN'T GET TO NETFLIX SEARCH"));
        }
    }

    /**
     * The remote's buttons as one shell command. Each `input` takes over a second to start on this TV, but takes
     * many keys at once: arrows in a row go together; OK, BACK and HOME (which change the screen) go on their own,
     * a moment apart.
     */
    static String keyCommand(java.util.List<String> keys) {
        StringBuilder cmd = new StringBuilder();
        boolean arrows = false;
        for (String k : keys) {
            if (k.equals("wait")) {                            // a screen still loading: two seconds, no key
                cmd.append(cmd.length() > 0 ? "; " : "").append("sleep 2");
                arrows = false;
                continue;
            }
            int code = k.equals("up") ? 19 : k.equals("down") ? 20 : k.equals("left") ? 21 : k.equals("right") ? 22
                    : k.equals("ok") ? 23 : k.equals("home") ? 3 : 4;
            boolean arrow = code >= 19 && code <= 22;
            if (arrow && arrows) cmd.append(' ').append(code);
            else cmd.append(cmd.length() > 0 ? "; sleep 0.3; " : "").append("input keyevent ").append(code);
            arrows = arrow;
        }
        return cmd.toString();
    }

    /**
     * Netflix's home on the usual profile, blind and sure: Netflix started afresh opens on "Choose a Profile" with the
     * last one lit, and plays a preview there the moment it's ready (its media session says so, ~25 s); RIGHT (off the
     * edit pencil, if that was lit), OK. False (and said) if it never came up. Off the main thread.
     */
    boolean netflixHome(int gen, String why) throws Exception {
        String nf = "com.netflix.ninja";
        ui.post(() -> card.working("OPENING NETFLIX", 60_000));
        Shell.run(this, "am force-stop " + nf, () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
        Thread.sleep(600);
        ui.post(() -> launch(nf));
        long t0 = System.currentTimeMillis();
        Thread.sleep(3000);
        while (!playingIn(nf)) {                               // the profile picker is up when its preview plays
            if (gen != asks) return false;
            if (System.currentTimeMillis() - t0 > 50_000 || !nf.equals(front)) {
                Log.w(TAG, "netflix: " + (nf.equals(front) ? "never got to its profiles" : front + " came to the front"));
                diary("(Netflix didn't come up for '" + why + "')");
                ui.post(() -> card.fail("NETFLIX DIDN'T COME UP"));
                return false;
            }
            Thread.sleep(500);
        }
        Log.i(TAG, "netflix: profiles up after " + (System.currentTimeMillis() - t0) + " ms");
        ui.post(() -> card.working("CHOOSING YOUR PROFILE", 60_000));
        Thread.sleep(1500);
        if (gen != asks || !nf.equals(front)) return false;
        Shell.run(this, "input keyevent 22; sleep 0.5; input keyevent 23", null);
        Thread.sleep(6000);                                    // home, its rows and the top menu
        return gen == asks;
    }

    /** What's playing now (or paused last), anywhere: [title, artist], or null. */
    String[] nowPlaying() {
        MediaController c = playing();
        if (c == null || c.getMetadata() == null) return null;
        MediaMetadata md = c.getMetadata();
        String t = History.first(md.getString(MediaMetadata.METADATA_KEY_TITLE), md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        String a = History.first(md.getString(MediaMetadata.METADATA_KEY_ARTIST), md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        return t.isEmpty() ? null : new String[]{t, a};
    }

    /** What's on screen, in words, for the agent's see_screen (off the main thread). */
    String describeScreen(String question) {
        String words = onScreen();
        byte[] shot = screenshotNow();
        if (shot == null || blank(lastThumb))
            return label(front) + " hides its picture." + (words.isEmpty() ? "" : " Its readable words: " + words);
        try {
            String lit = focusedWords();
            JSONObject d = new Brain(key()).glance(shot, "Describe it for someone deciding what to do: {\"app\", \"screen\": "
                    + "what kind of screen, \"focused\": the item that has the focus exactly as shown, \"rows\": the visible "
                    + "rows with their titles in order, \"answer\": " + JSONObject.quote(question) + "}", 900);
            return d + (lit.isEmpty() ? "" : " (the app says the focus is on \"" + lit + "\")");
        } catch (Exception e) {
            return "Couldn't see the screen (" + e.getMessage() + ")." + (words.isEmpty() ? "" : " Readable words: " + words);
        }
    }

    /** A picture of the screen now, as the AI sees it (off the main thread); null if the TV won't give one. */
    byte[] screenshotNow() {
        try {
            java.util.concurrent.CompletableFuture<byte[]> shot = new java.util.concurrent.CompletableFuture<>();
            shoot(new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    shot.complete(jpeg(r));
                }

                @Override
                public void onFailure(int code) {
                    shot.complete(null);
                }
            });
            return shot.get(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** A goal reached by sight: looks, presses the remote's buttons and types until it's there (or says it can't). */
    void reach(String goal) {
        try {
            look(goal, new JSONObject().put("name", "look").put("args", new JSONObject().put("what", goal)), 1);
        } catch (org.json.JSONException ignored) {
        }
    }

    void verifyLater(String said, JSONObject a) {
        java.util.Set<String> want = expected(a);
        if (want.isEmpty() || card == null || card.state == Card.FAIL) return;
        int mine = asks;                                      // a newer ask means don't speak over it
        ui.postDelayed(() -> {
            String now = activePackage();
            if (mine != asks || want.contains(now) || want.contains(front)) return;
            String what = a.optString("name");
            Log.w(TAG, "didn't happen: '" + said + "' → " + what + " expected " + want + ", in front " + now);
            diary("(didn't happen: '" + said + "' → " + brief(a) + "; in front: " + label(front) + ")");
            showCard();
            card.fail("THAT DIDN'T OPEN · TRY AGAIN");
        }, 6000);
    }

    /** One step of an ask: done, and remembered with what was actually done; if they asked for more, the rest follows. */
    void carryOut(String said, JSONObject a, int step) {
        // look, or words to press that aren't on screen ("the second video"): see the screen first, once.
        JSONObject ax = a.optJSONObject("args");
        // Asked, not told (3 Nov: "I like the second title, what is it about?" opened it): a question only gets an
        // answer. Anything else the AI chose for it becomes a look that may only answer.
        if (question(said) && !a.optString("name").matches("say|choose|look") && (ax == null || !ax.optBoolean("_seen"))) {
            Log.i(TAG, "'" + said + "' is a question; " + a.optString("name") + " becomes an answer from the screen");
            try {
                a = new JSONObject().put("name", "look").put("args", new JSONObject().put("what", said).put("_answer", true));
            } catch (org.json.JSONException ignored) {
            }
            ax = a.optJSONObject("args");
        }
        // "The first one", "the second video": what's on screen in front (or the card's list), never a title from
        // memory (3 Nov: in YouTube and Apple TV, "play the first one" played the last video in the history).
        if (ordinal(said) && !a.optString("name").matches("select|look|say|choose") && (ax == null || !ax.optBoolean("_seen") && !ax.optBoolean("_agent"))) {
            Log.i(TAG, "'" + said + "' counts things on screen; " + a.optString("name") + " becomes a look");
            try {
                a = new JSONObject().put("name", "look").put("args", new JSONObject().put("what", said));
            } catch (org.json.JSONException ignored) {
            }
            ax = a.optJSONObject("args");
        }
        if (a.optString("name").equals("look") || a.optString("name").equals("select") && ax != null
                && !ax.optBoolean("_seen") && find(ax.optString("text")) == null) {
            look(said, a, step);
            return;
        }
        String was = front;
        a = sanity(said, a);
        act(a);
        verifyLater(said, a);
        verifyPlaying(said, a, step);
        remember(said, card.label + "  [" + brief(a) + "]");
        tell(3);
        JSONObject x = a.optJSONObject("args");
        String rest = x == null ? "" : x.optString("then").trim();
        if (rest.isEmpty() && a.optString("name").equals("open_app") && x != null && x.optBoolean("fresh") && !x.optBoolean("_home")
                && card.state != Card.FAIL)
            // Some apps (YouTube: Google's casting keeps it running) can't be closed, and come back as they
            // were left: then the voice looks, and goes back or uses the app's menu to its home page.
            rest = "make sure " + x.optString("app") + "'s own home page is showing: if a video, a search or anything else "
                    + "is, look and press back or use its menu";
        if (rest.isEmpty() || step >= 3 || a.optString("name").equals("say") || card.state == Card.FAIL) return;
        card.hideAt = 0;                                       // the card stays up for the rest
        next(rest, was, a, step + 1);
    }

    /**
     * The rest of a longer ask ("go home and then pause the music"): once the screen shows what the step
     * before did, it's decided afresh, seeing that screen. Anything asked meanwhile ends it.
     */
    void next(String rest, String was, JSONObject before, int step) {
        int gen = asks;
        String key = key(), did = before.optString("name"), target = target(before);
        JSONObject x = before.optJSONObject("args");
        boolean fresh = did.equals("open_app") && x != null && x.optBoolean("fresh");
        // Steps that bring up another app: wait for it (one started afresh takes longer, and first leaves if it was
        // in front), then a moment for it to draw what the next step will see.
        boolean moves = target != null || did.matches("watch|play_music|tv|open_link|listen_play");
        long t0 = System.currentTimeMillis();
        ui.postDelayed(new Runnable() {
            boolean left = true;                               // (an app in front opened fresh stays: fresh())

            @Override
            public void run() {
                if (gen != asks) return;
                if (!left && !front.equals(target)) left = true;
                boolean waiting = !left || (target != null ? !front.equals(target) : moves && front.equals(was));
                if (waiting && System.currentTimeMillis() - t0 < (fresh ? 9000 : 3000)) {
                    ui.postDelayed(this, 100);
                    return;
                }
                ui.postDelayed(() -> {
                    if (gen != asks) return;
                    showCard();
                    card.listening(false);
                    card.heard(rest);
                    tell(2);
                    work.execute(() -> {
                        try {
                            boolean typing = boxHere();
                            JSONObject act = decision(new Brain(key), rest, typing, () -> context(typing));
                            Log.i(TAG, "step " + step + " decided: " + act);
                            JSONObject a = act;
                            ui.post(() -> {
                                if (gen == asks) carryOut(rest, a, step);
                            });
                        } catch (Exception ex) {
                            Log.w(TAG, "step " + step + " failed: " + ex);
                            ui.post(() -> {
                                if (gen != asks) return;
                                card.fail("COULDN'T DO THE REST");
                                tell(0);
                            });
                        }
                    });
                }, fresh && !was.equals(target) ? 4000 : moves ? 1200 : 900);
            }
        }, 100);
    }

    /** The app an action brings to the front, when it's clear which. */
    String target(JSONObject a) {
        JSONObject x = a.optJSONObject("args");
        if (x == null) return null;
        switch (a.optString("name")) {
            case "open_app":
                App app = findApp(x.optString("app"));
                return app == null ? null : app.pkg;
            case "go":
                return x.optString("where").equals("home") ? getPackageName() : null;
            case "youtube":
            case "youtube_play":
                return "com.google.android.youtube.tv";
            case "search":
                String in = x.optString("app").toLowerCase(Locale.ROOT);
                return in.equals("youtube") ? "com.google.android.youtube.tv" : in.equals("netflix") ? "com.netflix.ninja" : null;
            default:
                return null;
        }
    }

    /** An app from its own home page: closed in the background first (apps come back where they were left). */
    void fresh(App app) {
        // Already in front: never out to the home screen and back (3 Nov: "it takes me home, then back to YouTube,
        // then back"). It stays, and the look that follows presses back to the app's own home page.
        if (app.pkg.equals(front)) return;
        try {                                                   // not in front: closed out of sight, then opened
            getSystemService(android.app.ActivityManager.class).killBackgroundProcesses(app.pkg);
        } catch (Exception e) {
            Log.w(TAG, "couldn't close " + app.pkg + ": " + e);
        }
        ui.postDelayed(() -> launch(app.pkg), 400);
    }

    /**
     * look: a picture of the screen for the AI (an app that shows no words, or "the second one"), then the
     * real action, chosen seeing it. The picture goes to the cloud only for asks like these.
     */
    void look(String said, JSONObject a, int step) {
        look(said, a, step, 1, "");
    }

    /**
     * One round of looking. If the AI presses the remote's buttons (press: to reach something it can't
     * select or tap), it sees the screen again afterwards, up to 4 rounds, until it says it's done.
     */
    int[] lookThumb;
    int stillFor;

    void look(String said, JSONObject a, int step, int round, String pressed) {
        int gen = asks;
        if (round == 1) {
            lookThumb = null;
            stillFor = 0;
        }
        String key = key();
        card.working(round == 1 ? "LOOKING" : "LOOKING AGAIN", 20000);
        shoot(new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult r) {
                work.execute(() -> {
                    try {
                        byte[] pic = jpeg(r);                           // (the picture can be read once)
                        if (blank(lastThumb) && onScreen().isEmpty()) {
                            // All black and nothing readable: the app won't be seen (Netflix; a protected film). Said
                            // plainly, never a guess from a black picture.
                            String who = label(front);
                            Log.w(TAG, "look: " + front + " shows only black, and no words");
                            diary("(" + who + " can't be seen: '" + said + "')");
                            ui.post(() -> {
                                if (gen == asks) card.say(who + " DOESN'T LET ME SEE ITS SCREEN");
                            });
                            return;
                        }
                        String asked = pressed.isEmpty() ? said
                                : said + "\n(Pressed so far: " + pressed + ". This is the screen now: press more, or finish.)";
                        // What has the focus, read first on its own: judging 'this one' or which way to press from
                        // the whole picture at once went wrong (3 Nov: Slow Horses for the second title, Edit Profile).
                        String litNow = "";
                        if (!front.startsWith("com.quiet.")) {
                            String lit = focusedWords();
                            try {
                                String hl = lit.isEmpty() ? new Brain(key).highlighted(pic) : "Highlighted now: \"" + lit + "\"";
                                java.util.regex.Matcher q = java.util.regex.Pattern.compile("^Highlighted now: \"([^\"]*)\"").matcher(hl);
                                litNow = q.find() ? q.group(1) : "";
                                asked += "\n(" + hl + ")";
                            } catch (Exception ignored) {
                            }
                        }
                        if (blank(lastThumb)) asked += "\n(The picture is black: this app hides its screen. Go only by 'On screen'.)";
                        JSONObject ax2 = a.optJSONObject("args");
                        boolean answerOnly = question(said) || ax2 != null && ax2.optBoolean("_answer");
                        JSONObject seen = new Brain(key).see(asked, context(false), pic, answerOnly);
                        if (seen.optString("name").equals("look"))
                            seen = new JSONObject().put("name", "say").put("args", new JSONObject().put("text", "I can't make it out"));
                        JSONObject x = a.optJSONObject("args"), sx = seen.optJSONObject("args");
                        if (x != null && sx != null && sx.optString("then").isEmpty() && !x.optString("then").isEmpty())
                            sx.put("then", x.optString("then"));        // the rest of a longer ask rides along
                        if (sx != null) sx.put("_seen", true);          // chosen seeing it: no second look
                        if (seen.optString("name").equals("select") && sx != null && (find(sx.optString("text")) == null || !focusReadable())) {
                            // Its words can't be pressed by name here (Apple TV shows none, or won't say where its focus
                            // is): OK, only if the picture shows it's the lit one.
                            String want = sx.optString("text");
                            boolean lit = !litNow.isEmpty() && (norm(litNow).contains(norm(want)) || norm(want).contains(norm(litNow)));
                            Log.i(TAG, "look: '" + want + "' isn't readable here; the lit one is '" + litNow + "' → " + (lit ? "OK" : "not pressed"));
                            seen = lit ? new JSONObject().put("name", "press").put("args", new JSONObject().put("keys", new org.json.JSONArray().put("ok")))
                                    : new JSONObject().put("name", "say").put("args", new JSONObject().put("text", "I can't see " + want + " to press it"));
                            sx = seen.optJSONObject("args");
                        }
                        Log.i(TAG, "saw (round " + round + "), decided: " + seen);
                        JSONObject s = seen;
                        int[] th = lastThumb;
                        boolean same = round > 1 && differ(th, lookThumb) < 3f;
                        lookThumb = th;
                        boolean waited = pressed.endsWith("[\"wait\"]");     // loading: no change is no failure
                        stillFor = same && !waited ? stillFor + 1 : 0;
                        if (stillFor >= 2) {                           // pressing isn't changing anything: stop, say so
                            Log.w(TAG, "look: the screen didn't change after pressing, twice: stopped");
                            diary("(stopped: pressing changed nothing, for '" + said + "')");
                            ui.post(() -> {
                                if (gen == asks) card.fail("PRESSING ISN'T GETTING THERE · STOPPED");
                            });
                            return;
                        }
                        if (s.optString("name").equals("press") && sx != null && playingIn(front)) {
                            // A video playing: left/right would scrub it (3 Nov). Back first, to the app's lists.
                            org.json.JSONArray k0 = sx.optJSONArray("keys");
                            String k0s = k0 == null ? "" : k0.toString();
                            if (k0s.contains("left") || k0s.contains("right") || k0s.contains("up") || k0s.contains("down"))
                                sx.put("keys", new org.json.JSONArray().put("back"));
                        }
                        if (s.optString("name").equals("press") && sx != null && String.valueOf(sx.optJSONArray("keys")).contains("ok")
                                && paying(litNow)) {                   // the owner's rule: never buy or rent
                            Log.w(TAG, "look: OK would press '" + litNow + "': refused");
                            diary("(refused to press '" + litNow + "' for '" + said + "')");
                            ui.post(() -> {
                                if (gen == asks) card.say("THAT ONE IS TO BUY OR RENT · I WON'T PRESS IT");
                            });
                            return;
                        }
                        java.util.regex.Matcher named = java.util.regex.Pattern.compile("\"([^\"]{2,90})\"").matcher(said);
                        if (s.optString("name").equals("press") && sx != null && String.valueOf(sx.optJSONArray("keys")).contains("ok")
                                && named.find() && !litNow.isEmpty() && !norm(litNow).contains(norm(named.group(1)))) {
                            // A named title, and OK would press something else (3 Nov: Apple TV's banner moved on from F1 to
                            // another show between the look and the press). Its own search finds that one instead.
                            String title = named.group(1);
                            App here = findApp(label(front));
                            Log.w(TAG, "look: OK would press '" + litNow + "', not '" + title + "': searching instead");
                            ui.post(() -> {
                                if (gen != asks) return;
                                if (here != null && !blind()) searchIn(here, title);
                                else card.say("I CAN'T SEE " + com.quiet.px.Font.upper(title) + " TO PRESS IT");
                            });
                            return;
                        }
                        if (s.optString("name").equals("press") && sx != null && String.valueOf(sx.optJSONArray("keys")).contains("ok")
                                && letterGrid() && (litNow.trim().length() <= 1 || litNow.matches("(?i)space|delete|clear|abc|123"))) {
                            // OK on a letter key types it (3 Nov: Apple TV's search got "f1 the movief"): only moves here.
                            org.json.JSONArray only = new org.json.JSONArray();
                            org.json.JSONArray k0 = sx.optJSONArray("keys");
                            for (int i = 0; k0 != null && i < k0.length(); i++) if (!k0.optString(i).equals("ok")) only.put(k0.optString(i));
                            Log.i(TAG, "look: OK would type '" + litNow + "' from the letter keys: only " + only);
                            sx.put("keys", only);
                        }
                        if (s.optString("name").equals("press") && sx != null) {   // the remote's buttons (Shell)
                            org.json.JSONArray names = sx.optJSONArray("keys");
                            int n = names == null ? 0 : Math.min(6, names.length());
                            String now = (pressed.isEmpty() ? "" : pressed + ", ") + names;
                            java.util.List<String> keyList = new java.util.ArrayList<>();
                            for (int i = 0; i < n; i++) keyList.add(names.optString(i));
                            String cmd = keyCommand(keyList);
                            ui.post(() -> card.working(n == 1 && names.optString(0).equals("wait") ? "WAITING FOR IT TO LOAD"
                                    : n == 1 ? "PRESSING " + names.optString(0).toUpperCase(Locale.ROOT) : "PRESSING " + n + " BUTTONS", 20000));
                            boolean pressedOk;
                            try {
                                Shell.run(Voice.this, cmd, () -> ui.post(() -> card.say("PRESS ALLOW ON THE TV, ONCE")));
                                pressedOk = true;
                            } catch (Exception shellEx) {
                                Log.w(TAG, "shell (pressing): " + shellEx);
                                pressedOk = false;
                            }
                            boolean viaShell = pressedOk;
                            ui.post(() -> {
                                if (gen != asks) return;
                                if (!viaShell) {                       // no Shell: back and home only, through accessibility
                                    for (int i = 0; i < n; i++) {
                                        String k = names.optString(i);
                                        if (!k.equals("home") && !k.equals("back")) continue;
                                        int g = k.equals("home") ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK;
                                        ui.postDelayed(() -> performGlobalAction(g), 450L * i);
                                    }
                                }
                                ui.postDelayed(() -> {                 // the app moves; then look again (or stop)
                                    if (gen != asks) return;
                                    if (round < 7) look(said, a, step, round + 1, now);
                                    else {                             // ran out of tries: said so, never a pretend DONE
                                        card.fail("COULDN'T GET THERE · TRY ANOTHER WAY");
                                        remember(said, "COULDN'T GET THERE  [press keys=" + now + "]");
                                        diary("(couldn't get there by pressing: '" + said + "')");
                                        tell(0);
                                    }
                                }, viaShell ? 900 : 450L * n + 1300);
                            });
                            return;
                        }
                        ui.post(() -> {
                            if (gen == asks) carryOut(said, s, step);
                        });
                    } catch (Exception ex) {
                        Log.w(TAG, "look failed: " + ex);
                        ui.post(() -> {
                            if (gen == asks) card.fail("COULDN'T SEE THE SCREEN");
                        });
                    }
                });
            }

            @Override
            public void onFailure(int code) {
                Log.w(TAG, "screenshot failed: " + code);
                ui.post(() -> {
                    if (gen == asks) card.fail("COULDN'T SEE THE SCREEN");
                });
            }
        });
    }

    /**
     * A picture of the screen without our own card in it: the card shows the words that were heard, and the AI
     * once read them as the search box's (3 Nov: 23 deletes into an empty YouTube box, which went back a page).
     * The card steps aside for the moment of the picture. The TV allows one picture a second: a second one
     * asked too soon waits its turn.
     */
    void shoot(TakeScreenshotCallback cb) {
        ui.post(() -> {
            boolean hide = shown && card != null && card.getVisibility() == android.view.View.VISIBLE;
            if (hide) card.setVisibility(android.view.View.INVISIBLE);
            Runnable back = () -> {
                if (hide && card != null) card.setVisibility(android.view.View.VISIBLE);
            };
            TakeScreenshotCallback wrapped = new TakeScreenshotCallback() {
                boolean again;

                @Override
                public void onSuccess(ScreenshotResult r) {
                    back.run();
                    cb.onSuccess(r);
                }

                @Override
                public void onFailure(int code) {
                    if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !again) {
                        again = true;
                        TakeScreenshotCallback me = this;
                        ui.postDelayed(() -> takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), me), 1050);
                        return;
                    }
                    back.run();
                    cb.onFailure(code);
                }
            };
            ui.postDelayed(() -> takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), wrapped), hide ? 150 : 0);
        });
    }

    /** The screenshot made small (1024 wide) and a JPEG: plenty to read titles by, quick to send. */
    static volatile int[] lastThumb;

    /** How different two screens are (0: the same; a press that changed nothing stays under ~3). */
    static float differ(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return 255;
        long d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / (float) a.length;
    }

    static byte[] jpeg(ScreenshotResult r) {
        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
        try {
            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
            android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sw, 1024, Math.max(1, 1024 * sw.getHeight() / sw.getWidth()), true);
            android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(sw, 32, 18, true);   // did a press change anything?
            int[] th = new int[32 * 18];
            tiny.getPixels(th, 0, 32, 0, 0, 32, 18);
            for (int i = 0; i < th.length; i++) th[i] = ((th[i] >> 16 & 0xFF) + (th[i] >> 8 & 0xFF) + (th[i] & 0xFF)) / 3;
            lastThumb = th;
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, o);
            return o.toByteArray();
        } finally {
            hb.close();
        }
    }

    /** quick(), unless "it" may be a setting just changed ("mute it" after home sound went on): then the AI weighs it. */
    JSONObject quickNow(String said) {
        String[] last = recent.peekLast();
        if (last != null && (last[2].contains("[setting") || last[2].contains("[say") || last[2].contains("[look"))
                && System.currentTimeMillis() - Long.parseLong(last[0]) < 180_000
                && said.toLowerCase(Locale.ROOT).matches(".*\\b(it|that|them|back|this|this one|that one|the same|again)\\b.*"))
            return null;                                       // about what was just said or done: the AI, with that
        return quick(said);
    }

    /**
     * Plays a YouTube video by its id. YouTube on this TV plays its https address (a link from the phone
     * sent as "vnd.youtube:" opened YouTube but never played, 2 Nov).
     */
    void playOnYouTube(String id) {
        playOnYouTube(id, -1);
    }

    void playOnYouTube(String id, long fromSeconds) {
        Uri watch = Uri.parse("https://www.youtube.com/watch?v=" + Uri.encode(id) + (fromSeconds > 0 ? "&t=" + fromSeconds + "s" : ""));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, watch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * The video YouTube finds for some words (its results page, read as a guest): the one whose title is those words
     * exactly, if one is (a title from what played here: 3 Nov, the top result for an English highlights video was its
     * Hindi twin), else the one sharing the most words with them among the first few, else the first. Null if none.
     */
    static String topVideo(String q) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.youtube.com/results?search_query="
                    + java.net.URLEncoder.encode(q, "UTF-8")).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en");
            StringBuilder page = new StringBuilder();
            try (java.io.Reader in = new java.io.InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                char[] buf = new char[16384];
                for (int n; page.length() < 1_500_000 && (n = in.read(buf)) > 0; ) page.append(buf, 0, n);
            }
            java.util.List<String[]> found = videos(page.toString(), 8);   // ads and Shorts come under other names
            if (found.isEmpty()) return null;
            java.util.Set<String> want = new java.util.HashSet<>(java.util.Arrays.asList(History.norm(q).split(" ")));
            String best = found.get(0)[0];
            int bestScore = -1;
            for (String[] v : found) {
                if (History.norm(v[1]).equals(History.norm(q))) return v[0];
                int score = 0;
                for (String w : History.norm(v[1]).split(" ")) if (want.contains(w)) score++;
                score -= Math.abs(History.norm(v[1]).split(" ").length - want.size());   // no extra or missing words
                if (score > bestScore) {
                    bestScore = score;
                    best = v[0];
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "youtube lookup: " + e);
        }
        return null;
    }

    /** The videos on a YouTube results page, in order: [id, title, channel, length, views, age]. */
    static java.util.List<String[]> videos(String page, int max) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(page);
        java.util.List<int[]> at = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        while (m.find() && ids.size() < max) {
            at.add(new int[]{m.end()});
            ids.add(m.group(1));
        }
        for (int i = 0; i < ids.size(); i++) {
            int from = at.get(i)[0], to = Math.min(page.length(), Math.min(from + 20000, i + 1 < ids.size() ? at.get(i + 1)[0] : page.length()));
            String w = page.substring(from, to);
            out.add(new String[]{ids.get(i), grab(w, "\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\""),
                    grab(w, "\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\""),
                    grab(w, "\"lengthText\":.{0,300}?\"simpleText\":\"([^\"]+)\""),
                    grab(w, "\"viewCountText\":\\{\"simpleText\":\"([^\"]+)\""),
                    grab(w, "\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"")});
        }
        return out;
    }

    static String grab(String w, String re) {
        java.util.regex.Matcher t = java.util.regex.Pattern.compile(re).matcher(w);
        return t.find() ? t.group(1).replace("\\u0026", "&").replace("\\\"", "\"") : "";
    }

    /** An action in a few words, for "Just before": "setting name=home_sound value=off". */
    static String brief(JSONObject a) {
        StringBuilder b = new StringBuilder(a.optString("name"));
        JSONObject x = a.optJSONObject("args");
        if (x != null)
            for (java.util.Iterator<String> k = x.keys(); k.hasNext(); ) {
                String n = k.next();
                if (!n.equals("then") && !n.startsWith("_")) b.append(' ').append(n).append('=').append(x.opt(n));
            }
        return b.toString();
    }

    /**
     * The last 40 asks, kept in home's prefs: the TV's log rolls over in minutes (the firmware is
     * chatty), so what went wrong yesterday is still here (debug.quiet.ask history logs it).
     */
    void diary(String line) {
        String at = new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(new Date());
        ui.post(() -> {
            android.content.SharedPreferences p = Prefs.prefs(this);
            String[] old = p.getString("voiceDiary", "").split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, old.length - 39); i < old.length; i++)
                if (!old[i].isEmpty()) b.append(old[i]).append('\n');
            p.edit().putString("voiceDiary", b.append(at).append("  ").append(line).toString()).apply();
        });
    }

    String volume(String action, int level) {
        AudioManager am = getSystemService(AudioManager.class);
        int s = AudioManager.STREAM_MUSIC, max = am.getStreamMaxVolume(s);
        if (duckedFrom >= 0) unduck();
        switch (action) {
            case "up":
                for (int i = 0; i < 2; i++) am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, i == 1 ? AudioManager.FLAG_SHOW_UI : 0);
                return "VOLUME UP";
            case "down":
                for (int i = 0; i < 2; i++) am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, i == 1 ? AudioManager.FLAG_SHOW_UI : 0);
                return "VOLUME DOWN";
            case "mute":
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return "MUTED";
            case "unmute":
                am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                return "SOUND ON";
            case "set":
                int v = Math.round(Math.max(0, Math.min(100, level)) / 100f * max);
                am.setStreamVolume(s, v, AudioManager.FLAG_SHOW_UI);
                return "VOLUME " + Math.max(0, Math.min(100, level));
        }
        return "OK";
    }

    /** While you speak, the TV's own sound steps down (and back up after), so it hears you. */
    void duck() {
        AudioManager am = getSystemService(AudioManager.class);
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (v > 2 && duckedFrom < 0) {
            duckedFrom = v;
            Prefs.prefs(this).edit().putInt("duckedFrom", v).commit();   // restored even after a crash
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, v / 3), 0);
        }
    }

    void unduck() {
        if (duckedFrom < 0) return;
        AudioManager am = getSystemService(AudioManager.class);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, duckedFrom, 0);
        duckedFrom = -1;
        Prefs.prefs(this).edit().remove("duckedFrom").apply();
    }

    String input(String want) {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String w = norm(want);
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput()) continue;
            CharSequence l = info.loadLabel(this);
            String label = l == null ? "" : l.toString();
            if (!w.isEmpty() && (norm(label).contains(w) || w.contains(norm(label)))) {
                startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return label.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- what's around

    static final class App {
        final String pkg, label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    List<App> appsCache;
    long appsAt;
    final java.util.Map<String, List<String[]>> shelfCache = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<String, Long> shelfAt = new java.util.concurrent.ConcurrentHashMap<>();

    List<App> apps() {
        long now = android.os.SystemClock.uptimeMillis();
        if (appsCache != null && now - appsAt < 10 * 60_000) return appsCache;
        appsCache = loadApps();
        appsAt = now;
        return appsCache;
    }

    List<App> loadApps() {
        PackageManager pm = getPackageManager();
        List<App> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                out.add(new App(p, String.valueOf(r.loadLabel(pm))));
            }
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    App findApp(String name) {
        String n = norm(name);
        if (n.isEmpty()) return null;
        String alias = n.contains("applemusic") || n.equals("music") ? "com.quiet.listen"
                : n.contains("stremio") ? "com.stremio.one" : n.contains("prime") || n.contains("amazon") ? "com.amazon.amazonvideo.livingroom"
                : n.contains("hotstar") ? "in.startv.hotstar" : null;
        List<App> all = apps();
        App best = null;
        int bestScore = Integer.MAX_VALUE;
        for (App a : all) {
            if (a.pkg.equals(alias)) return a;
            String l = norm(a.label);
            int score = l.equals(n) ? 0 : l.startsWith(n) || n.startsWith(l) ? 1 : l.contains(n) || n.contains(l) ? 2 : 3 + distance(l, n);
            if (score < bestScore || (score == bestScore && a.pkg.startsWith("com.quiet."))) {   // ours win a tie
                bestScore = score;                                                                 // ("Settings")
                best = a;
            }
        }
        return bestScore <= 4 ? best : null;
    }

    /** Only when the words are just an app's name ("open netflix"), not "open MrBeast's channel on YouTube". */
    boolean appNamed(String words) {
        String n = norm(words.replaceAll("^the ", "").replaceAll(" (app|application)$", ""));
        if (n.isEmpty()) return false;
        for (App a : apps()) {
            String l = norm(a.label);
            if (l.equals(n) || (n.length() >= 4 && distance(l, n) <= 1)) return true;
        }
        return n.equals("applemusic") || n.equals("primevideo") || n.equals("prime") || n.equals("hotstar");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    final java.util.Map<String, Boolean> launchables = new java.util.concurrent.ConcurrentHashMap<>();

    /** An app with an icon to open (not a service of the TV's own). */
    boolean launchable(String pkg) {
        Boolean b = launchables.get(pkg);
        if (b == null) {
            PackageManager pm = getPackageManager();
            b = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
            launchables.put(pkg, b);
        }
        return b;
    }

    void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    List<String[]> shelf(String pkg) {
        Long at = shelfAt.get(pkg);
        long now = android.os.SystemClock.uptimeMillis();
        if (at != null && now - at < 3 * 60_000 && shelfCache.containsKey(pkg)) return shelfCache.get(pkg);
        List<String[]> out = loadShelf(pkg);
        shelfCache.put(pkg, out);
        shelfAt.put(pkg, now);
        return out;
    }

    List<String[]> loadShelf(String pkg) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getContentResolver().query(Uri.parse("content://" + pkg + ".shelf/all"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String[] r = new String[c.getColumnCount()];
                for (int k = 0; k < r.length; k++) r[k] = c.getString(k) == null ? "" : c.getString(k);
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A Continue Watching entry matching the title: [title, sub, stremio uri]. */
    String[] continuing(String title) {
        String n = norm(title);
        if (n.isEmpty()) return null;
        for (String[] r : shelf("com.quiet.watch")) {
            String t = norm(r[0]);
            if (r.length > 2 && (t.equals(n) || t.contains(n) || n.contains(t))) return r;
        }
        return null;
    }

    MediaController sessionOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Listener.class)))
                if (pkg.equals(c.getPackageName())) return c;
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean videoApp(String pkg) {
        return pkg != null && pkg.matches(".*(youtube|netflix|hotstar|amazonvideo|primevideo|stremio|vlc|jio|sonyliv|zee5|mxtech|plex|kodi|disney).*");
    }

    /**
     * Presses whatever is selected in the app in front, like OK on the remote: only a real item, with
     * words on it (YouTube shows Android one blank surface, nothing to press).
     */
    boolean pressFocused() {
        try {
            AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null || words(n).isEmpty()) return false;
            for (int i = 0; n != null && i < 4; i++, n = n.getParent())
                if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {
        }
        return false;
    }

    MediaController playing() {
        try {
            MediaSessionManager m = getSystemService(MediaSessionManager.class);
            List<MediaController> list = m.getActiveSessions(new ComponentName(this, Listener.class));
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** The box says it's for searching (its id, hint or words). */
    static boolean searchBox(AccessibilityNodeInfo f) {
        String s = (f.getViewIdResourceName() + " " + f.getHintText() + " " + f.getContentDescription()).toLowerCase(Locale.ROOT);
        return s.contains("search") || s.contains("query") || s.contains("find");
    }

    AccessibilityNodeInfo focusedText() {
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return f != null && f.isEditable() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** The names it's likely to hear, to prime the transcription. */
    String hints() {
        StringBuilder b = new StringBuilder("Quiet TV. On YouTube, on Netflix. Skye, Mote. Apps: ");
        for (App a : apps()) b.append(a.label).append(", ");
        for (String[] r : shelf("com.quiet.watch")) b.append(r[0]).append(", ");
        for (String[] r : shelf("com.quiet.listen"))
            if (!r[0].matches(".*['’]s Station$"))           // their own station is mostly their name, and "pause" was heard as it
                b.append(r[0]).append(r.length > 1 && !r[1].isEmpty() ? " (" + r[1] + ")" : "").append(", ");
        return b.length() > 900 ? b.substring(0, 900) : b.toString();
    }

    /** What's on the TV right now, for deciding. */
    String context(boolean typing) {
        StringBuilder b = new StringBuilder();
        b.append("Now: ").append(new SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.ENGLISH).format(new Date())).append('\n');
        String temp = com.quiet.px.Weather.temperature();
        if (!temp.isEmpty()) b.append("Weather: ").append(temp).append(com.quiet.px.Weather.rain() ? ", raining" : com.quiet.px.Weather.fog() ? ", foggy"
                : com.quiet.px.Weather.overcast() > 0.6f ? ", cloudy" : ", clear").append('\n');
        b.append("In front: ").append(front.isEmpty() ? "the home screen" : label(front)).append('\n');
        if (!front.isEmpty() && !front.startsWith("com.quiet.") && !saysWhatPlays(front))
            b.append(label(front)).append(" doesn't tell the TV what it's playing: for 'what am I watching?' in it, look (its title "
                    + "shows on screen when paused or when its controls are up); never answer from another app.\n");
        android.content.SharedPreferences wp = Prefs.prefs(this);
        String fest = com.quiet.px.World.festival();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float hr = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        MediaController m = playing();                      // the world dances to Apple Music only (Listen)
        boolean dancing = m != null && "com.quiet.listen".equals(m.getPackageName()) && m.getPlaybackState() != null
                && m.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        b.append("Skye now: ").append(com.quiet.px.World.now(hr, dancing)).append('\n');
        b.append("The world now: ").append(com.quiet.px.World.moon()).append(fest.isEmpty() ? "" : ", " + fest)
                .append(", ").append(wp.getInt("days", 0)).append(" days on the tally")
                .append(wp.getBoolean("door", false) ? ", the door at the end of the road stands open" : "").append('\n');
        MediaController c = playing();
        if (c != null && c.getMetadata() != null) {
            MediaMetadata md = c.getMetadata();
            PlaybackState st = c.getPlaybackState();
            b.append("Playing: ").append(md.getString(MediaMetadata.METADATA_KEY_TITLE)).append(" by ")
                    .append(md.getString(MediaMetadata.METADATA_KEY_ARTIST)).append(" in ").append(label(c.getPackageName()))
                    .append(st != null && st.getState() == PlaybackState.STATE_PLAYING ? "" : " (paused)").append('\n');
        }
        java.util.List<String> on = choosing() ? choiceLabels : null, todo = choices;
        if (on != null && todo != null) {
            b.append("On the card (a list they can pick from):");
            for (int i = 0; i < on.size() && i < todo.size(); i++)
                b.append(' ').append(i + 1).append(". ").append(on.get(i)).append(" [ask: ").append(todo.get(i)).append(']');
            b.append('\n');
        }
        String lit = front.isEmpty() || front.startsWith("com.quiet.") ? "" : focusedWords();
        if (!lit.isEmpty()) b.append("Highlighted on screen now (what 'this one' means): \"").append(lit).append("\"\n");
        b.append("Text box focused: ").append(typing ? "yes" : keyboardUp() ? "the keyboard is up, but this app hides its box" : "no").append('\n');
        long now = System.currentTimeMillis();
        StringBuilder before = new StringBuilder();
        for (String[] r : recent)
            if (now - Long.parseLong(r[0]) < 180_000) before.append("  '").append(r[1]).append("' -> ").append(r[2]).append('\n');
        if (before.length() > 0) b.append("Just before (oldest first):\n").append(before);
        if (tvOff != null) b.append("A TV sleep timer is set.\n");
        if (!front.isEmpty() && !front.startsWith("com.quiet.")) {   // (ours draw their words; Listen's hidden web page isn't on screen)
            String seen = onScreen();
            if (!seen.isEmpty()) b.append("On screen in ").append(label(front)).append(": ").append(seen).append('\n');
            else b.append(label(front)).append(" shows no readable words (look sees it).\n");
        }
        b.append("Apps: ");
        for (App a : apps()) b.append(a.label).append(", ");
        b.append("\nContinue watching: ");
        for (String[] r : shelf("com.quiet.watch")) b.append(r[0]).append(", ");
        b.append("\nApple Music's own recently-played list (albums, playlists; can lag): ");
        for (String[] r : shelf("com.quiet.listen")) b.append(r[0]).append(", ");
        // What actually played on this TV, every app, newest first: the truth for "what was I watching/listening to".
        String told = Taste.told(this), habits = Taste.habits(this);
        if (!told.isEmpty()) b.append("\nWhat they've told you about their taste (always follow it):\n").append(told);
        if (!habits.isEmpty()) b.append("\nTheir habits on this TV, by time of day (for 'the usual', 'put something on'):\n").append(habits);
        String played = History.recent(this, 12);
        b.append("\nPlayed on this TV, newest first (every app; the truth for what they watched or listened to):\n")
                .append(played.isEmpty() ? "(nothing yet)\n" : played);
        // Last, next to what they said: what was just done, so "unmute it back" means that (not the TV's sound).
        String[] last = recent.peekLast();
        if (last != null && now - Long.parseLong(last[0]) < 90_000)
            b.append("\nLast done, ").append((now - Long.parseLong(last[0])) / 1000).append(" s ago, for '").append(last[1])
                    .append("': ").append(last[2]).append(". 'It', 'back', 'undo', 'no' and 'actually' mean that same thing.");
        return b.toString();
    }

    // ---------------------------------------------------------------- the card

    void showCard() {
        if (card == null) {
            card = new Card(this);
            card.gone = this::hideCard;
            card.stuck = () -> {
                asks++;                                        // and the ask is over: nothing carries on after "didn't finish"
                Log.w(TAG, "the card called it stuck: '" + card.words + "' at '" + card.label + "'");
                diary("(stuck: '" + card.words + "' at " + card.label + ")");
            };
        }
        if (shown) return;
        int k = Math.max(1, getResources().getDisplayMetrics().widthPixels / 320);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Card.W * k, Card.HMAX * k,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;               // top-right, like a notification
        p.x = 8 * k;
        p.y = 8 * k;
        try {
            wm.addView(card, p);
            shown = true;
        } catch (Exception e) {
            Log.w(TAG, "can't show the card: " + e);
        }
    }

    void hideCard() {
        if (!shown || card == null) return;
        try {
            wm.removeView(card);
        } catch (Exception ignored) {
        }
        shown = false;
    }
}
