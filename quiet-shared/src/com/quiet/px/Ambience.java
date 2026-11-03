package com.quiet.px;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.ArrayDeque;

/**
 * The sound of the well, synthesised live. No audio files.
 *
 *  - A slow pad: four open chords (D, Bm, G, A), each held ~11 s and cross-faded, breathing.
 *  - A kalimba that plays a note from the D-major pentatonic every few seconds, sometimes a pair.
 *  - The world plays along: drips and splashes "plip" (pitched by what fell), the frog croaks,
 *    the fireflies' synchronised blink rings a little arpeggio, and moving through the list
 *    plays the scale, so browsing is quietly musical.
 *  - Rain is a soft filtered hush that fades in and out, with the odd low drop; never a patter.
 *  - Plips are rate-limited and soften when they come close together, so nothing nags.
 *  - Two echoes give it the size of a cave. Everything lives in one mono 22 kHz stream.
 *
 * It starts softly, stops the instant an app opens, and stays silent if something else is
 * already playing music.
 */
public final class Ambience implements Stage.Sound {

    static final int SR = 22050;
    static final int TABLE = 2048;
    static final float[] SINE = new float[TABLE];
    static {
        for (int i = 0; i < TABLE; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
    }

    static final float[][] CHORDS = {
            {146.83f, 220.00f, 329.63f},
            {123.47f, 185.00f, 293.66f},
            {98.00f, 146.83f, 246.94f},
            {110.00f, 164.81f, 277.18f},
    };
    static final float[] SCALE = {
            293.66f, 329.63f, 369.99f, 440.00f, 493.88f, 587.33f, 659.26f, 739.99f, 880.00f, 987.77f};

    final Context context;
    AudioTrack track;
    Thread thread;
    volatile boolean running;
    public volatile boolean enabled = true;
    /** Nature only: no pad or melody, just the world's own sounds. */
    public volatile boolean nature;
    final ArrayDeque<float[]> events = new ArrayDeque<>();
    final java.util.Random rnd = new java.util.Random();

    // Pad.
    final float[] padPh = new float[12];
    int chord, prevChord = 3;
    float chordT, lp;
    long n;

    // Plucked and pitched voices: freq, phase, amp, per-sample decay, kind, age, phase2, overtone level.
    static final int VOICES = 12;
    final float[][] v = new float[VOICES][8];
    static final float OVERTONE_DECAY = (float) Math.exp(-9.0 / SR);
    static final int K_PLUCK = 0, K_PLIP = 1, K_CROAK = 2;
    float pluckIn = 3f;

    // Echoes.
    final float[] d1 = new float[(int) (SR * 0.42f)], d2 = new float[(int) (SR * 0.29f)];

    // Rain: a hush of filtered noise, eased in and out.
    volatile float rainTarget;
    float rainLevel, rnLp, rnLp2, rnHp;
    int seed = 0x2545F491;
    long lastPlip = -SR;
    float thunderT = -1, thLp, thLp2;
    int i1, i2;
    float fb1, fb2, gain;

    // How loud the whole soundtrack sits under the TV's own volume (Quiet Settings → LOUDNESS).
    public volatile float level = 2.2f;

    /** soft | normal | loud → a level. */
    public static float levelFor(String name) {
        return "soft".equals(name) ? 1.4f : "loud".equals(name) ? 3.2f : 2.2f;
    }

    public Ambience(Context c) {
        context = c.getApplicationContext();
    }

    public void start() {
        if (!enabled || running) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return;
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            track = null;
            return;
        }
        gain = 0f;
        running = true;
        thread = new Thread(this::loop, "ambience");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(200);
            } catch (InterruptedException ignored) {
            }
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (on) start();
        else stop();
    }

    // ---------------------------------------------------------------- events from the world

    void post(float kind, float a) {
        if (!running) return;
        synchronized (events) {
            if (events.size() < 16) events.add(new float[]{kind, a});
        }
    }

    @Override public void plip(float pitch) { post(K_PLIP, pitch); }
    @Override public void croak() { post(K_CROAK, 0); }
    @Override public void chime() { post(9, 0); }
    @Override public void chirp() { post(12, 0); }
    @Override public void squeak() { post(13, 0); }
    @Override public void rain(float amount) { rainTarget = amount; }
    @Override public void rainDrop() { post(14, 0); }
    @Override public void thunder() { post(15, 0); }

    /** Moving through a list walks up and down the scale. */
    public void step(int index) { post(10, index); }

    /** Choosing something: a small rising pair. */
    public void pick() { post(11, 0); }

    // ---------------------------------------------------------------- synthesis

    void loop() {
        short[] buf = new short[1024];
        while (running) {
            drain();
            for (int i = 0; i < buf.length; i++) {
                float s = sample();
                s *= level;
                s = s / (1f + Math.abs(s));               // soft clip keeps peaks round
                buf[i] = (short) (s * 30000f);
            }
            AudioTrack t = track;
            if (t == null || !running) break;
            t.write(buf, 0, buf.length);
        }
    }

    void drain() {
        while (true) {
            float[] e;
            synchronized (events) {
                e = events.poll();
            }
            if (e == null) return;
            int kind = (int) e[0];
            if (kind == K_PLIP || kind == 14) {
                // Never a machine-gun: at most ~3 a second, and quieter the closer together they come.
                long since = n - lastPlip;
                if (since < SR * 0.3f) continue;
                lastPlip = n;
                float rest = Math.max(0.4f, Math.min(1f, since / (SR * 1.5f)));
                if (kind == 14) voice(360f + rnd.nextFloat() * 260f, 0.05f * rest, 0.06f, K_PLIP);   // a drop in the rain
                else voice(480f + e[1] * 900f, 0.14f * rest, 0.07f, K_PLIP);
            }
            else if (kind == K_CROAK) {
                voice(150f, 0.14f, 0.07f, K_CROAK);
                voiceLater(135f, 0.12f, 0.07f, K_CROAK, 0.13f);
            } else if (kind == 9) {
                for (int k = 0; k < 4; k++) voiceLater(SCALE[5 + k], 0.07f, 0.9f, K_PLUCK, k * 0.12f);
            } else if (kind == 10) {
                int idx = Math.abs((int) e[1]) % 8;
                voice(SCALE[idx + 2], 0.06f, 0.5f, K_PLUCK);
            } else if (kind == 12) {                       // a bird: two quick high notes
                voice(2637f, 0.035f, 0.07f, K_PLUCK);
                voiceLater(3136f, 0.03f, 0.06f, K_PLUCK, 0.08f);
            } else if (kind == 13) {                       // bats: tiny squeaks
                for (int k = 0; k < 3; k++) voiceLater(3800f + k * 300, 0.015f, 0.03f, K_PLUCK, k * 0.11f);
            } else if (kind == 15) {
                thunderT = 0;                              // a distant roll of thunder
            } else if (kind == 11) {
                voice(SCALE[3], 0.08f, 0.7f, K_PLUCK);
                voiceLater(SCALE[5], 0.08f, 0.9f, K_PLUCK, 0.09f);
            }
        }
    }

    void voice(float f, float amp, float decay, int kind) {
        voiceLater(f, amp, decay, kind, 0);
    }

    void voiceLater(float f, float amp, float decay, int kind, float delay) {
        for (float[] q : v) {
            if (q[2] > 0.0005f || q[5] < 0) continue;
            q[0] = f; q[1] = 0; q[2] = amp; q[3] = (float) Math.exp(-1.0 / (SR * decay)); q[4] = kind;
            q[5] = -delay; q[6] = 0; q[7] = 0.35f;
            return;
        }
    }

    float osc(float[] ph, int i, float f) {
        ph[i] += f * TABLE / SR;
        if (ph[i] >= TABLE) ph[i] -= TABLE;
        return SINE[(int) ph[i]];
    }

    static float sin(float phase) {
        int i = (int) (phase * TABLE) & (TABLE - 1);
        return SINE[i];
    }

    float sample() {
        n++;
        float dt = 1f / SR;
        if (gain < 1f) gain = Math.min(1f, gain + dt / 3f);   // fade in over three seconds

        // Pad: current chord fades in over 3 s while the previous fades out.
        chordT += dt;
        if (chordT > 11f) {
            chordT = 0;
            prevChord = chord;
            chord = (chord + 1) % CHORDS.length;
        }
        float xf = Math.min(1f, chordT / 3f);
        float pad = 0;
        float time = n * dt;
        for (int k = 0; k < 3; k++) {
            float breathe = 0.75f + 0.25f * sin(time * (0.07f + k * 0.023f) + k * 0.3f);
            float a = CHORDS[chord][k], b = CHORDS[prevChord][k];
            pad += (osc(padPh, k * 2, a) + osc(padPh, k * 2 + 1, a * 1.0018f) * 0.8f) * xf * breathe;
            pad += (osc(padPh, 6 + k * 2, b) + osc(padPh, 7 + k * 2, b * 1.0018f) * 0.8f) * (1f - xf) * breathe;
        }
        // A slowly opening and closing low-pass keeps it soft.
        float cutoff = 0.035f + 0.025f * (1f + sin(time * 0.031f));
        lp += (pad - lp) * cutoff;
        float dry = nature ? 0f : lp * 0.045f;

        // Kalimba notes on their own schedule.
        pluckIn -= dt;
        if (pluckIn <= 0 && nature) pluckIn = 1f;
        if (pluckIn <= 0) {
            pluckIn = 2.5f + rnd.nextFloat() * 5f;
            int idx = rnd.nextInt(SCALE.length - 2);
            voice(SCALE[idx], 0.09f, 1.1f, K_PLUCK);
            if (rnd.nextInt(3) == 0) voiceLater(SCALE[idx + 2], 0.06f, 1.1f, K_PLUCK, 0.18f + rnd.nextFloat() * 0.2f);
        }

        float send = 0;
        for (float[] q : v) {
            if (q[5] < 0) {                     // waiting to start
                q[5] += dt;
                if (q[5] >= 0) q[5] = 0;
                continue;
            }
            if (q[2] <= 0.0005f) continue;
            q[5] += dt;
            float age = q[5], f = q[0], out;
            int kind = (int) q[4];
            if (kind == K_PLIP) {
                f *= 1f - 0.45f * Math.min(1f, age / 0.05f);
                q[1] += f / SR;
                out = sin(q[1]);
            } else if (kind == K_CROAK) {
                f *= 1f - 0.2f * Math.min(1f, age / 0.07f);
                q[1] += f / SR;
                float w = sin(q[1]);
                out = Math.max(-0.6f, Math.min(0.6f, w * 2f));      // a reedy, clipped tone
            } else {
                q[1] += f / SR;
                q[6] += f * 3.01f / SR;
                out = sin(q[1]) + q[7] * sin(q[6]);
                q[7] *= OVERTONE_DECAY;
            }
            send += out * q[2];
            q[2] *= q[3];
        }

        // Cave echoes.
        float e1 = d1[i1], e2 = d2[i2];
        fb1 += ((send + e1 * 0.38f) - fb1) * 0.45f;   // darker on every repeat
        fb2 += ((send + e2 * 0.3f) - fb2) * 0.4f;
        d1[i1] = fb1;
        d2[i2] = fb2;
        i1 = (i1 + 1) % d1.length;
        i2 = (i2 + 1) % d2.length;

        // Rain: white noise, smoothed twice (no hiss above ~1 kHz) with the rumble taken out.
        rainLevel += (rainTarget - rainLevel) * (dt / 3f);
        float rain = 0;
        if (rainLevel > 0.002f) {
            seed ^= seed << 13;
            seed ^= seed >>> 17;
            seed ^= seed << 5;
            float w = (seed & 0xFFFF) / 32768f - 1f;
            rnLp += (w - rnLp) * 0.22f;
            rnLp2 += (rnLp - rnLp2) * 0.22f;
            rnHp += (rnLp2 - rnHp) * 0.012f;
            float swell = 0.75f + 0.25f * sin(time * 0.11f) * sin(time * 0.037f + 1f);
            rain = (rnLp2 - rnHp) * rainLevel * swell * 0.12f;
        }

        // Thunder: deep noise, a quick swell and a long rolling fade.
        float thunder = 0;
        if (thunderT >= 0) {
            thunderT += dt;
            seed ^= seed << 13;
            seed ^= seed >>> 17;
            seed ^= seed << 5;
            float w = (seed & 0xFFFF) / 32768f - 1f;
            thLp += (w - thLp) * 0.02f;
            thLp2 += (thLp - thLp2) * 0.03f;
            float env = Math.min(1f, thunderT / 0.25f) * (float) Math.exp(-thunderT / 1.1f)
                    * (0.7f + 0.3f * sin(thunderT * 3.1f));
            thunder = thLp2 * env * 1.6f;
            if (thunderT > 5f) thunderT = -1;
        }

        return (dry + send * 0.8f + (e1 + e2) * 0.35f + rain + thunder) * gain;
    }
}
