package com.quiet.home;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * The always-open ear for the wake word. Runs only when a wake word has been taught and is
 * switched on. Every 20 ms of sound goes to the Spotter (on the TV, nothing leaves it); when the
 * Spotter hears the wake word, or the remote's button is pressed, the same stream is handed to
 * the Ear for the command, with the last second and a half before it, so "Hey Pip, play Arijit"
 * in one breath arrives whole.
 */
final class Mic implements Runnable {

    static final int RATE = 16000, FRAME = 320, KEEP = 150;    // 150 frames: the last 3 s

    interface Woke {
        void woke(byte[] before);
    }

    final Context context;
    final Spotter spot = new Spotter();
    final LinkedBlockingQueue<short[]> toEar = new LinkedBlockingQueue<>();
    final java.util.ArrayDeque<short[]> ring = new java.util.ArrayDeque<>();
    volatile boolean running, earMode;
    volatile float lastWakeScore;
    volatile Woke woke;
    interface Level {
        void level(float l);
    }
    volatile Level level;                                       // while teaching: for the card's meter
    float loudest, quietest = Float.MAX_VALUE;
    int zeros, sinceReport;
    long reportAt = System.currentTimeMillis(), nearAt;
    Thread thread;

    Mic(Context c) {
        context = c.getApplicationContext();
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "wake");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    /** The command is starting: frames go to the Ear (with what came just before, if asked). */
    byte[] handOver(boolean withBefore) {
        toEar.clear();
        byte[] before = withBefore ? recent() : new byte[0];
        earMode = true;
        return before;
    }

    void takeBack() {
        earMode = false;
        toEar.clear();
        spot.reset();
    }

    synchronized byte[] recent() {
        byte[] out = new byte[ring.size() * FRAME * 2];
        int k = 0;
        for (short[] f : ring)
            for (short v : f) {
                out[k++] = (byte) (v & 0xFF);
                out[k++] = (byte) ((v >> 8) & 0xFF);
            }
        return out;
    }

    /** Frames of pure digital silence in a row: a live microphone always hears a little. */
    volatile int silentFrames;
    static final int DEAF = 150;                                // 3 s of it: Android is holding the microphone back

    /** Android held the microphone back (only silence): don't lean on this stream. */
    boolean deaf() {
        return silentFrames >= 25;
    }

    @Override
    public void run() {
        // Android decides once, as the microphone opens, whether this app may hear (it must count as in
        // front then). Opened a moment too early (the TV just waking, or home just installed with the
        // screen off) it gives only silence until it's opened again: so it is, until it hears.
        for (int tries = 0; running && listen(); tries++) {
            Log.w(Voice.TAG, "wake: the microphone gives only silence (held back); opening it again");
            try {
                Thread.sleep(tries < 10 ? 2000 : 30_000);
            } catch (InterruptedException e) {
                break;
            }
        }
        running = false;
    }

    /** One opening of the microphone, until stopped; true if it gave only silence and should be opened again. */
    boolean listen() {
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 32));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(Voice.TAG, "wake: the microphone wouldn't open");
                return false;
            }
            AudioDeviceInfo best = Ear.bestMic(context);
            if (best != null) {
                r.setPreferredDevice(best);
                Log.i(Voice.TAG, "wake: using the " + (best.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "TV's own" : "plugged-in")
                        + " microphone (" + best.getProductName() + ")");
            }
            // Taking the TV's own sound out of what the microphone hears, if this TV can.
            boolean aec = AcousticEchoCanceler.isAvailable(), ns = NoiseSuppressor.isAvailable();
            if (aec) {
                AcousticEchoCanceler e = AcousticEchoCanceler.create(r.getAudioSessionId());
                if (e != null) e.setEnabled(true);
            }
            if (ns) {
                NoiseSuppressor n = NoiseSuppressor.create(r.getAudioSessionId());
                if (n != null) n.setEnabled(true);
            }
            Log.i(Voice.TAG, "wake: listening for the wake word (echo cancelling " + (aec ? "on" : "not available")
                    + ", noise suppression " + (ns ? "on" : "not available") + ")");
            r.startRecording();
            short[] f = new short[FRAME];
            silentFrames = 0;
            while (running) {
                int got = 0;
                while (got < FRAME && running) {
                    int n = r.read(f, got, FRAME - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got < FRAME) {
                    Thread.sleep(50);
                    continue;
                }
                boolean zero = true;
                for (short v : f) if (v != 0) { zero = false; break; }
                silentFrames = zero ? silentFrames + 1 : 0;
                if (silentFrames >= DEAF) return true;
                short[] copy = f.clone();
                synchronized (this) {
                    ring.add(copy);
                    if (ring.size() > KEEP) ring.poll();
                }
                if (earMode) {
                    toEar.offer(copy);
                    continue;
                }
                Spotter.Hit h = spot.feed(copy);
                // What the microphone is hearing: for the meter while teaching, and in the log.
                float rms = spot.lastLevel;
                loudest = Math.max(loudest, rms);
                quietest = Math.min(quietest, rms);
                if (zero) zeros++;
                Level lv = level;
                if (lv != null && spot.teaching != null) lv.level(Math.min(1f, rms / Math.max(spot.floor * 4f, 70f)));
                if (!spot.nearMiss.isEmpty() && System.currentTimeMillis() - nearAt > 400) {
                    Log.i(Voice.TAG, "wake: close but no (" + spot.nearMiss + " vs " + spot.threshold + ", loudest just now "
                            + Math.round(loudest) + ")");
                    nearAt = System.currentTimeMillis();
                }
                spot.nearMiss = "";
                if (++sinceReport >= (spot.teaching != null ? 100 : 1500)) {
                    long wall = System.currentTimeMillis() - reportAt;
                    reportAt = System.currentTimeMillis();
                    Log.i(Voice.TAG, String.format(java.util.Locale.ROOT, "mic: room %.0f, quietest %.0f, loudest %.0f, silent frames %d%s; "
                                    + "%d s of sound took %d s, wake checks %.1f ms each",
                            spot.floor, quietest, loudest, zeros, spot.teaching != null ? " (teaching)" : "",
                            sinceReport * FRAME / RATE, wall / 1000, spot.checks == 0 ? 0f : spot.checkNanos / 1e6f / spot.checks));
                    spot.checkNanos = 0;
                    spot.checks = 0;
                    sinceReport = zeros = 0;
                    loudest = 0;
                    quietest = Float.MAX_VALUE;
                }
                if (h != null && woke != null) {
                    Log.i(Voice.TAG, "wake: maybe (" + spot.lastScores + " vs " + spot.threshold + "), asking the cloud");
                    lastWakeScore = h.score;
                    woke.woke(null);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.w(Voice.TAG, "wake: stopped: " + e);
        } finally {
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
                r.release();
            }
        }
        return false;
    }
}
