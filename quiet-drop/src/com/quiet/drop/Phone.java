package com.quiet.drop;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.ScrollView;

import com.quiet.px.Keys;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drop on your phone: "Quiet TV" in the share menu of every app, and a small remote with your
 * phone's microphone ("Talk to TV" on the home screen opens it already listening). Paired once from
 * Drop's page on the TV (a quiet://pair link carrying the TV's address and a secret); finds the TV
 * again by name on the Wi-Fi if its address changes.
 */
public class Phone extends Activity {

    static final int PURPLE = 0xFF06060C, STONE = 0xFF141626, TEXT = 0xFFF4EEDC, DIM = 0xFF6A7090, AMBER = 0xFFFFB44A, MINT = 0xFF4EF0C8, PINK = 0xFFFF5C8A;
    /** What the "Talk to TV" shortcut sends (res/xml/shortcuts.xml), and its id. */
    static final String TALK = "com.quiet.drop.TALK", SHORTCUT = "talk";
    static final String UNPAIRED = "NOT PAIRED YET · OPEN DROP ON THE TV, SCAN, TAP 'PAIR IT'";
    static final String SPEAK = "Tap and speak; it sends when you stop. Or hold it while you speak. English, Hindi or Malayalam.";
    static final String UNTIL = "Speak now; it sends when you stop. Tap to send it sooner.";

    final Handler ui = new Handler(Looper.getMainLooper());
    TextView status, hint, micLabel;
    Mic mic;
    Button pin, pairAsk;
    LinearLayout remoteBox, pairBox, codeRow;
    EditText codeBox;
    // recording: the microphone is open. held: the button is down (letting go sends it); otherwise
    // the ask ends by itself when you stop talking. cancelled: you went back or left; nothing is sent.
    volatile boolean recording, held, cancelled;
    boolean pressing, wantTalk;
    long pressedAt;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("phone", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = getIntent();
        String action = i == null ? null : i.getAction();
        if (Intent.ACTION_VIEW.equals(action) && i.getData() != null && "quiet".equals(i.getData().getScheme())) {
            Uri d = i.getData();
            prefs(this).edit().putString("host", d.getQueryParameter("h")).putString("port", d.getQueryParameter("p"))
                    .putString("token", d.getQueryParameter("t")).apply();
        } else if (Intent.ACTION_SEND.equals(action)) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT), subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
            if (text == null && i.getClipData() != null && i.getClipData().getItemCount() > 0)
                text = String.valueOf(i.getClipData().getItemAt(0).coerceToText(this));
            send(text == null ? "" : text, subject == null ? "" : subject, true);
            return;
        }
        build();
        if (TALK.equals(action) && state == null) listen(false);         // from the shortcut: already listening
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pin != null) pin.setVisibility(canPin() ? View.VISIBLE : View.GONE);
        if (remoteBox != null && paired() && prefs(this).getBoolean("rpaired", false))
            presses.execute(() -> {                            // open the TV's line now: the first press is instant
                try {
                    remote();
                } catch (Exception ignored) {
                }
            });
    }

    @Override
    protected void onPause() {
        super.onPause();
        Keys.Pairing p = pairingNow;                           // a pairing left half done: the TV drops its code
        pairingNow = null;
        if (p != null) {
            codeRow.setVisibility(View.GONE);
            pairAsk.setVisibility(View.VISIBLE);
            pairAsk.setEnabled(true);
        }
        presses.execute(() -> {                                // the TV's line closes while the remote isn't showing
            if (p != null) p.close();
            Keys.Remote l = line;
            line = null;
            if (l != null) l.close();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopListening();                     // left mid-ask (home, the screen went off): nothing is sent
    }

    @Override
    public void onBackPressed() {
        if (recording) stopListening();      // one step out: stop listening, send nothing
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        boolean asked = wantTalk;
        wantTalk = false;
        if (results.length == 0) return;     // interrupted: asked again next time
        if (results[0] != PackageManager.PERMISSION_GRANTED) say("NO MICROPHONE · ALLOW IT IN THE PHONE'S SETTINGS: APPS, DROP", AMBER);
        else if (asked) listen(false);       // the shortcut, the first time: listening once it's allowed
    }

    // ---------------------------------------------------------------- the little remote

    void build() {
        ScrollView scroll = new ScrollView(this);              // small phones scroll; the mic stays on top
        scroll.setBackgroundColor(PURPLE);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = dp(24);
        col.setPadding(side, dp(40), side, side);
        scroll.addView(col);
        TextView title = text("QUIET TV", 28, TEXT);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(title);
        status = text(paired() ? "PAIRED · SHARE ANYTHING TO 'QUIET TV'" : UNPAIRED, 13, paired() ? MINT : AMBER);
        status.setPadding(0, dp(8), 0, dp(16));
        col.addView(status);

        // Front and centre: the microphone. Tap and speak (it sends when you stop), or hold it.
        mic = new Mic(this);
        mic.setOnTouchListener((v, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                pressedAt = e.getEventTime();
                if (recording) {                                  // listening after a tap: this one sends it now
                    pressing = false;
                    recording = false;
                } else pressing = listen(true);
            } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && pressing) {
                pressing = false;
                if (e.getEventTime() - pressedAt < 400) {         // a tap: it listens on until you stop talking
                    held = false;
                    hint.setText(UNTIL);
                } else recording = false;                         // held: letting go sends it
            }
            return true;
        });
        col.addView(mic, new LinearLayout.LayoutParams(-1, dp(176)));
        micLabel = text("", 16, TEXT);
        micLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        micLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(micLabel, padded());
        hint = text(SPEAK, 13, DIM);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(hint, padded());
        idle();

        // The TV's own buttons (through Google's remote service on it: Keys), once this phone is paired as a remote.
        remoteBox = new LinearLayout(this);
        remoteBox.setOrientation(LinearLayout.VERTICAL);
        remoteBox.addView(new Pad(this, this::key), spaced(-1, dp(232), 28));
        remoteBox.addView(row(new String[]{"BACK", "HOME", "PLAY/PAUSE"}, new int[]{Keys.BACK, Keys.HOME, Keys.PLAY_PAUSE}, false),
                spaced(-1, dp(52), 20));
        remoteBox.addView(row(new String[]{"VOL -", "MUTE", "VOL +"}, new int[]{Keys.VOLUME_DOWN, Keys.MUTE, Keys.VOLUME_UP}, true),
                spaced(-1, dp(52), 10));
        Button dictate = button("SPEAK TO TYPE INTO THE TV", AMBER, PURPLE);
        dictate.setOnClickListener(v -> dictate());
        remoteBox.addView(dictate, spaced(-1, dp(52), 20));
        remoteBox.addView(text("In any search box on the TV (YouTube, Netflix, any app): tap, say the words, "
                + "and they're typed in. Say \"clear\", \"delete the last word\" or \"add ...\" to change them.", 13, DIM), padded());
        col.addView(remoteBox);
        pairBox = pairing();
        col.addView(pairBox, spaced(-1, -2, 28));
        showRemote(prefs(this).getBoolean("rpaired", false));

        EditText box = new EditText(this);
        box.setHint("Type to the TV, or into its open search box");
        box.setHintTextColor(DIM);
        box.setTextColor(TEXT);
        box.setBackground(round(STONE));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setSingleLine(true);
        box.setImeOptions(EditorInfo.IME_ACTION_SEND);
        box.setOnEditorActionListener((v, id, ev) -> {
            String t = box.getText().toString().trim();
            if (!t.isEmpty()) {
                send(t, "", false);
                box.setText("");
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(32);
        col.addView(box, lp);
        col.addView(text("Or share from any app: tap Share, then 'Quiet TV'.", 13, DIM), padded());

        pin = new Button(this);                                   // shown until it's on the home screen
        pin.setText("ADD 'TALK TO TV' TO HOME SCREEN");
        pin.setTextColor(TEXT);
        pin.setTextSize(13);
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pin.setBackground(round(STONE));
        pin.setOnClickListener(v -> addToHome());
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2);
        under.topMargin = dp(32);
        col.addView(pin, under);
        setContentView(scroll);
    }

    void idle() {
        mic.show(Mic.IDLE);
        micLabel.setText("TAP TO TALK");
        hint.setText(SPEAK);
    }

    LinearLayout.LayoutParams spaced(int w, int h, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** A row of the remote's smaller buttons; with repeat, holding one keeps pressing (volume). */
    LinearLayout row(String[] labels, int[] codes, boolean repeat) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Button b = button(labels[i], Pad.RING, TEXT);
            b.setTextSize(13);
            int code = codes[i];
            Runnable again = new Runnable() {
                @Override
                public void run() {
                    key(code);
                    b.postDelayed(this, 160);
                }
            };
            b.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setBackground(round(Pad.LIT));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    key(code);
                    if (repeat) v.postDelayed(again, 450);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.removeCallbacks(again);
                    v.setBackground(round(Pad.RING));
                }
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.leftMargin = dp(10);
            r.addView(b, lp);
        }
        return r;
    }

    Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setBackground(round(bg));
        b.setStateListAnimator(null);
        return b;
    }

    // ---------------------------------------------------------------- the TV's buttons (Keys)

    final java.util.concurrent.ExecutorService presses = java.util.concurrent.Executors.newSingleThreadExecutor();
    volatile Keys.Remote line;
    Keys.Pairing pairingNow;

    /** Until this phone is paired as the TV's remote: a few words and a button, then the code the TV shows. */
    LinearLayout pairing() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(STONE));
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.addView(text("Use this phone as the TV's remote too: arrows, OK, back, home and volume, in every app. "
                + "Pair it once: the TV shows a code to type here.", 13, DIM));
        pairAsk = button("SHOW A CODE ON THE TV", AMBER, PURPLE);
        pairAsk.setOnClickListener(v -> askCode());
        box.addView(pairAsk, spaced(-1, dp(52), 14));
        codeRow = new LinearLayout(this);
        codeBox = new EditText(this);
        codeBox.setHint("CODE");
        codeBox.setHintTextColor(DIM);
        codeBox.setTextColor(TEXT);
        codeBox.setTextSize(22);
        codeBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeBox.setGravity(Gravity.CENTER);
        codeBox.setSingleLine(true);
        codeBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        codeBox.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        codeBox.setBackground(round(PURPLE));
        codeBox.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeBox.setOnEditorActionListener((v, id, ev) -> {
            finishPairing();
            return true;
        });
        codeRow.addView(codeBox, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button go = button("PAIR", AMBER, PURPLE);
        go.setOnClickListener(v -> finishPairing());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(52));
        lp.leftMargin = dp(10);
        codeRow.addView(go, lp);
        codeRow.setVisibility(View.GONE);
        box.addView(codeRow, spaced(-1, -2, 14));
        return box;
    }

    void showRemote(boolean paired) {
        remoteBox.setVisibility(paired ? View.VISIBLE : View.GONE);
        pairBox.setVisibility(paired ? View.GONE : View.VISIBLE);
    }

    Keys keysFor() throws java.security.GeneralSecurityException {
        SharedPreferences p = prefs(this);
        String id = p.getString("rid", "");
        if (id.isEmpty()) {                                    // made once on this phone; it's what the TV remembers
            id = Keys.identity("Quiet TV phone");
            p.edit().putString("rid", id).apply();
        }
        return new Keys(p.getString("host", ""), id);
    }

    /** The open line for keys, made when needed (the first press waits ~1 s for it). Off the UI thread. */
    Keys.Remote remote() throws Exception {
        Keys.Remote l = line;
        if (l != null && l.alive()) return l;
        try {
            try {
                l = keysFor().connect("Quiet TV phone");
            } catch (javax.net.ssl.SSLException e) {
                throw e;
            } catch (java.io.IOException gone) {              // not at its address: the router may have moved it
                if (!rediscover(this)) throw gone;
                l = keysFor().connect("Quiet TV phone");
            }
        } catch (javax.net.ssl.SSLException first) {           // turned away: once more, a moment later
            Thread.sleep(1500);
            try {
                l = keysFor().connect("Quiet TV phone");
            } catch (javax.net.ssl.SSLException e) {           // the TV doesn't know this phone (any more): pair
                prefs(this).edit().putBoolean("rpaired", false).apply();
                ui.post(() -> showRemote(false));
                throw e;
            }
        }
        if (!l.waitReady(4000)) {
            l.close();
            throw new java.io.IOException("NO ANSWER");
        }
        return line = l;
    }

    void key(int code) {
        presses.execute(() -> {
            try {
                remote().press(code);
            } catch (Exception e) {
                line = null;
                say("COULDN'T REACH THE TV'S REMOTE (SAME WI-FI?)", PINK);
            }
        });
    }

    void askCode() {
        if (!paired()) {
            say(UNPAIRED, AMBER);
            return;
        }
        say("ASKING THE TV FOR A CODE...", DIM);
        pairAsk.setEnabled(false);
        presses.execute(() -> {
            try {
                Keys.Pairing p = keysFor().pair("Quiet TV phone");
                ui.post(() -> {
                    pairingNow = p;
                    pairAsk.setVisibility(View.GONE);
                    codeRow.setVisibility(View.VISIBLE);
                    codeBox.setText("");
                    codeBox.requestFocus();
                    getSystemService(InputMethodManager.class).showSoftInput(codeBox, 0);
                });
                say("TYPE THE 6 CHARACTERS THE TV SHOWS", AMBER);
            } catch (Exception e) {
                ui.post(() -> pairAsk.setEnabled(true));
                say("COULDN'T REACH THE TV (SAME WI-FI?)", PINK);
            }
        });
    }

    void finishPairing() {
        Keys.Pairing p = pairingNow;
        String code = codeBox.getText().toString();
        if (p == null || code.length() < 6) return;
        pairingNow = null;
        say("PAIRING...", DIM);
        presses.execute(() -> {
            try {
                p.finish(code);
            } catch (Exception e) {
                ui.post(() -> {
                    codeRow.setVisibility(View.GONE);
                    pairAsk.setVisibility(View.VISIBLE);
                    pairAsk.setEnabled(true);
                });
                say("THAT'S NOT THE CODE".equals(e.getMessage()) ? "THAT'S NOT THE CODE · ASK FOR A NEW ONE" : "COULDN'T PAIR · TRY AGAIN", PINK);
                return;
            }
            prefs(this).edit().putBoolean("rpaired", true).apply();
            ui.post(() -> {
                showRemote(true);
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(codeBox.getWindowToken(), 0);
            });
            say("PAIRED · THE TV'S BUTTONS ARE HERE", MINT);
            try {
                Thread.sleep(1500);                            // the TV takes a moment to keep a new pairing
                remote();                                      // ready for the first press
            } catch (Exception ignored) {
            }
        });
    }

    /** Opens the microphone for one ask. Held: until you let go. Otherwise until you stop talking (or tap). */
    boolean listen(boolean hold) {
        if (recording) return false;
        if (!paired()) {
            say(UNPAIRED, AMBER);
            return false;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wantTalk = !hold;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return false;
        }
        recording = true;
        held = hold;
        cancelled = false;
        mic.show(Mic.LISTENING);
        micLabel.setText(dictating ? "SAY THE WORDS TO TYPE..." : "LISTENING...");
        if (!hold) hint.setText(UNTIL);
        mic.post(() -> mic.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY));
        new Thread(this::record, "talk").start();
        return true;
    }

    void stopListening() {
        if (!recording) return;
        cancelled = true;
        recording = false;
    }

    /**
     * One ask, on its own thread: records until it's over, then sends it to the TV. Over: you let go,
     * tapped again, or (after a tap) stopped talking for a moment. Like the TV's own ear (Ear) it first
     * learns the room, and hears you relative to that; nothing goes if you went back or said nothing.
     */
    void record() {
        int rate = 16000, frame = 640;                            // bytes: 20 ms
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean heard = false;
        try {
            r.startRecording();
            byte[] buf = new byte[frame];
            float[] room = new float[15];
            float floor = 0, level = 0, voice = 0;
            int frames = 0, loud = 0, quiet = 0;
            while (recording) {
                int n = r.read(buf, 0, frame);
                if (n <= 0) break;
                pcm.write(buf, 0, n);
                double sum = 0;
                for (int k = 0; k + 1 < n; k += 2) {
                    int v = (short) ((buf[k] & 0xFF) | (buf[k + 1] << 8));
                    sum += (double) v * v;
                }
                float rms = (float) Math.sqrt(sum / Math.max(1, n / 2));
                level = level * 0.75f + rms * 0.25f;              // smoothed, so a film's loud moments aren't you
                int ms = ++frames * 20;
                if (frames % 3 == 0) {                            // the ring round the mic follows your voice
                    float ring = Math.min(1f, level / Math.max(floor * 6f, 400f));
                    ui.post(() -> mic.level(ring));
                }
                if (ms >= (held ? 15_000 : 10_000)) break;
                if (frames <= room.length) {                      // the room as it is now: a quiet share of it,
                    room[frames - 1] = rms;                       // so speaking straight away doesn't raise it
                    if (frames == room.length) {
                        float[] s = room.clone();
                        Arrays.sort(s);
                        floor = s[room.length * 3 / 10];
                    }
                    continue;
                }
                float on = Math.max(floor * 2.2f, 50f), off = Math.max(Math.max(floor * 1.5f, 35f), voice * 0.2f);
                if (!heard) {
                    loud = level > on ? loud + 1 : 0;
                    heard = loud >= 3;                            // a moment of it, not a click
                    if (loud == 0) floor = floor * 0.97f + Math.min(level, floor * 1.5f) * 0.03f;   // drift with the room
                    if (!heard && !held && ms >= 6_000) break;   // nothing said
                } else {
                    if (level > on) voice = voice == 0 ? level : voice * 0.9f + level * 0.1f;
                    quiet = level < off ? quiet + 20 : 0;
                    if (!held && quiet >= 900) break;             // you've stopped talking
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
        }
        boolean byItself = recording;                             // it heard you stop (or nothing), not you
        boolean dictation = dictating;
        dictating = false;
        recording = false;
        if (cancelled || pcm.size() < 16000 || byItself && !heard) {   // under half a second, or nothing said
            ui.post(() -> {
                if (!recording) idle();
            });
            say(cancelled ? "NOTHING SENT" : "DIDN'T HEAR ANYTHING · TAP TO TRY AGAIN", cancelled ? DIM : AMBER);
            return;
        }
        ui.post(() -> {
            if (recording) return;
            mic.show(Mic.SENDING);
            micLabel.setText("SENDING...");
        });
        if (dictation) {                                          // SPEAK TO TYPE: the words, for the box on the TV
            String got = post(this, "/hear", wav(pcm.toByteArray(), rate), "audio/wav", "", true);
            ui.post(() -> {
                if (!recording) idle();
            });
            if (got == null) say("COULDN'T REACH THE TV (SAME WI-FI?)", PINK);
            else if (got.startsWith("DONE\n")) say(got.length() == 5 ? "DONE ON THE TV" : "TYPED ON THE TV: " + got.substring(5).toUpperCase(java.util.Locale.ROOT), MINT);
            else if (!typeReply(got)) say(got, AMBER);
            return;
        }
        String sent = post(this, "/voice", wav(pcm.toByteArray(), rate), "audio/wav", "", canType());
        ui.post(() -> {
            if (!recording) idle();
            if (sent != null) mic.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        });
        if (sent == null || !typeReply(sent)) say(sent == null ? "COULDN'T REACH THE TV (SAME WI-FI?)" : "ON THE TV", sent == null ? PINK : MINT);
        if (sent != null) {
            try {
                getSystemService(ShortcutManager.class).reportShortcutUsed(SHORTCUT);   // the phone learns you use it
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- "Talk to TV" on the home screen

    /** The phone can put shortcuts on its home screen, and "Talk to TV" isn't there yet. */
    boolean canPin() {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (!sm.isRequestPinShortcutSupported()) return false;
            for (ShortcutInfo s : sm.getPinnedShortcuts()) if (SHORTCUT.equals(s.getId())) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks the phone to put "Talk to TV" (the shortcut in res/xml/shortcuts.xml) on its home screen; it asks you first. */
    void addToHome() {
        boolean asked;
        try {
            asked = getSystemService(ShortcutManager.class).requestPinShortcut(new ShortcutInfo.Builder(this, SHORTCUT).build(), null);
        } catch (Exception e) {
            asked = false;
        }
        if (!asked) Toast.makeText(this, "Touch and hold the Drop icon, then drag 'Talk to TV' to your home screen.", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- sending

    void send(String text, String subject, boolean thenClose) {
        if (text.trim().isEmpty()) {
            if (thenClose) finish();
            return;
        }
        if (!paired()) {
            Toast.makeText(this, "Pair with the TV first: open Drop on the TV, scan its code, tap 'Pair it'.", Toast.LENGTH_LONG).show();
            if (thenClose) finish();
            return;
        }
        Context app = getApplicationContext();
        boolean canType = !thenClose && canType();                // shared from another app: never typed into a box
        new Thread(() -> {
            String r = post(app, "/share", text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", subject, canType);
            if (r == null || !typeReply(r)) ui.post(() -> Toast.makeText(app, r == null ? "Couldn't reach the TV (same Wi-Fi?)" : "On the TV", Toast.LENGTH_SHORT).show());
        }, "share").start();
        if (thenClose) finish();
    }

    // ---------------------------------------------------------------- typing into the TV's search boxes

    /** The TV's answer when its keyboard is up in a box it can't type into itself: these words, for us to type. */
    static final String TYPE = "TYPE\n";

    /** This phone is also the TV's remote, so it can type into the TV's boxes (Google's remote service). */
    boolean canType() {
        return prefs(this).getBoolean("rpaired", false);
    }

    volatile boolean dictating;

    /** SPEAK TO TYPE: one ask whose words are typed into the box on the TV, never taken as a command. */
    void dictate() {
        if (recording) {                                          // tapped again: send it now
            recording = false;
            return;
        }
        if (!canType()) {
            say("PAIR THIS PHONE AS THE TV'S REMOTE FIRST", AMBER);
            return;
        }
        dictating = true;
        if (!listen(false)) dictating = false;
    }

    /**
     * Types the words into the box open on the TV, the way Google's phone remote types (then the
     * keyboard's search key, if the TV's own keyboard is up). The phone tells the TV what the line saw,
     * for finding out which apps take it. If the line to the TV is down, the words go as an ask instead.
     */
    /**
     * The TV's "TYPE" / "GRID" answer: words for its box, after taking out (delete key) the letters we
     * typed there before. False if it isn't one.
     */
    boolean typeReply(String r) {
        boolean keyboard = r.startsWith(TYPE);
        if (!keyboard && !r.startsWith("GRID\n")) return false;
        String[] p = r.substring(5).split("\n", -1);
        int del = 0;
        try {
            if (p.length > 1) del = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException ignored) {
        }
        typeOnTv(p[0], keyboard, del);
        return true;
    }

    void typeOnTv(String words, boolean keyboard) {
        typeOnTv(words, keyboard, 0);
    }

    void typeOnTv(String words, boolean keyboard, int del) {
        presses.execute(() -> {
            try {
                Keys.Remote l = remote();
                boolean saw = l.sawBox();
                for (int i = 0; i < Math.min(del, 80); i++) {     // what we typed before, out letter by letter
                    l.press(Keys.DEL);
                    Thread.sleep(70);
                }
                l.type(words, keyboard);
                say(words.isEmpty() ? "CLEARED THE TV'S SEARCH" : "SENT TO THE TV'S SEARCH: " + words.toUpperCase(java.util.Locale.ROOT), MINT);
                String note = "typed '" + words + "' after " + del + " deletes (" + (keyboard ? "TV keyboard" : "app's own letters")
                        + "), box seen: " + saw + "; " + l.lately();
                post(getApplicationContext(), "/note", note.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "", false);
            } catch (Exception e) {
                line = null;
                String r = post(getApplicationContext(), "/share", words.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "", false);
                say(r == null ? "COULDN'T REACH THE TV (SAME WI-FI?)" : "COULDN'T TYPE THERE · ASKED THE TV INSTEAD", r == null ? PINK : AMBER);
            }
        });
    }

    boolean paired() {
        return !prefs(this).getString("token", "").isEmpty();
    }

    void say(String s, int color) {
        ui.post(() -> {
            if (status != null) {
                status.setText(s);
                status.setTextColor(color);
            } else Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });
    }

    /** POSTs to the TV; if its address changed, finds it again by name on the Wi-Fi and retries. */
    static String post(Context c, String path, byte[] body, String type, String subject, boolean canType) {
        String r = tryPost(c, path, body, type, subject, canType);
        if (r == null && rediscover(c)) r = tryPost(c, path, body, type, subject, canType);
        return r;
    }

    static String tryPost(Context c, String path, byte[] body, String type, String subject, boolean canType) {
        SharedPreferences p = prefs(c);
        String host = p.getString("host", ""), port = p.getString("port", "8642");
        if (host.isEmpty()) return null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
            h.setConnectTimeout(3000);
            h.setDoOutput(true);
            h.setRequestMethod("POST");
            h.setRequestProperty("Content-Type", type);
            h.setRequestProperty("X-Quiet-Token", p.getString("token", ""));
            if (!subject.isEmpty()) h.setRequestProperty("X-Subject", URLEncoder.encode(subject, "UTF-8"));
            if (canType) h.setRequestProperty("X-Can-Type", "1");
            h.setRequestProperty("X-Quiet-Phone", "2");            // reads "TYPE|GRID\n<words>\n<letters to delete>"
            h.setReadTimeout(canType ? 30_000 : 8000);           // it may be waiting for the words to be heard
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = h.getOutputStream()) {
                o.write(body);
            }
            int code = h.getResponseCode();
            try (InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream()) {
                ByteArrayOutputStream got = new ByteArrayOutputStream();
                byte[] b = new byte[512];
                for (int n; in != null && got.size() < 8192 && (n = in.read(b)) > 0; ) got.write(b, 0, n);
                return code < 400 ? (got.size() > 0 ? got.toString("UTF-8") : "ok") : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    static boolean rediscover(Context c) {
        NsdManager nsd = c.getSystemService(NsdManager.class);
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] found = {false};
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override
            public void onServiceFound(NsdServiceInfo s) {
                if (!s.getServiceName().startsWith("Quiet TV")) return;
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s2, int e) {}
                    @Override
                    public void onServiceResolved(NsdServiceInfo s2) {
                        prefs(c).edit().putString("host", s2.getHost().getHostAddress()).putString("port", String.valueOf(s2.getPort())).apply();
                        found[0] = true;
                        done.countDown();
                    }
                });
            }
        };
        try {
            nsd.discoverServices("_quiettv._tcp.", NsdManager.PROTOCOL_DNS_SD, l);
            done.await(5, TimeUnit.SECONDS);
            nsd.stopServiceDiscovery(l);
        } catch (Exception ignored) {
        }
        return found[0];
    }

    static byte[] wav(byte[] pcm, int rate) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        o.write('R'); o.write('I'); o.write('F'); o.write('F');
        le(o, 36 + pcm.length, 4);
        o.write('W'); o.write('A'); o.write('V'); o.write('E'); o.write('f'); o.write('m'); o.write('t'); o.write(' ');
        le(o, 16, 4); le(o, 1, 2); le(o, 1, 2); le(o, rate, 4); le(o, rate * 2, 4); le(o, 2, 2); le(o, 16, 2);
        o.write('d'); o.write('a'); o.write('t'); o.write('a');
        le(o, pcm.length, 4);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    static void le(ByteArrayOutputStream o, int v, int n) {
        for (int i = 0; i < n; i++) o.write((v >> (8 * i)) & 0xFF);
    }

    // ---------------------------------------------------------------- looks

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    LinearLayout.LayoutParams padded() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        return lp;
    }

    GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(6));
        return g;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
