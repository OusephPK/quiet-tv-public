package com.quiet.home;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Hears the wake word, on the TV, from a few recordings of the owner saying it (no cloud, no
 * library). Fed 20 ms frames of 16 kHz sound:
 *
 *  - it does nothing much until something louder than the room starts (the room's level is
 *    tracked all the time), then keeps that stretch of sound and its fingerprints (Mfcc);
 *  - every tenth of a second it lines the start of that stretch up against each recording
 *    (Mfcc.match); when enough of them agree, that's the wake word, and whatever was said after
 *    it in the same breath ("Hey Pip, play Arijit") is handed on as the start of the command;
 *  - a stretch that never matches is dropped as soon as it ends (or after 3 s).
 *
 * Teaching uses the same ears: teach() collects one clean utterance at a time.
 */
final class Spotter {

    static final int FRAME = 320, PREROLL = 12, MAX_FRAMES = 150, QUIET_END = 15;

    final List<float[][]> templates = new ArrayList<>();
    float threshold = Float.MAX_VALUE;
    float floor = 0;
    int seen;

    // The stretch of sound being considered.
    boolean inSpeech;
    int quietFor, framesIn, sinceCheck, cooldown;
    final java.util.ArrayDeque<short[]> pre = new java.util.ArrayDeque<>();
    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    final List<float[]> feats = new ArrayList<>();
    final Mfcc mfcc = new Mfcc(feats::add);
    float lastLevel;

    /** The result of a match: what was said after the wake word (may be empty). */
    static final class Hit {
        final byte[] after;
        final float score;

        Hit(byte[] after, float score) {
            this.after = after;
            this.score = score;
        }
    }

    /** Set when teaching: the next whole utterance goes here instead of being matched. */
    interface Taught {
        void utterance(List<float[]> frames, byte[] pcm);
    }

    Taught teaching;
    String lastScores = "";

    void reset() {
        inSpeech = false;
        quietFor = framesIn = sinceCheck = 0;
        pcm.reset();
        feats.clear();
        mfcc.reset();
        pre.clear();
    }

    /** One 20 ms frame. Returns a Hit when the wake word was just heard. */
    Hit feed(short[] f) {
        double sum = 0;
        for (short v : f) sum += v * (double) v;
        float rms = (float) Math.sqrt(sum / f.length);
        lastLevel = rms;
        seen++;
        if (seen <= 10) {                                     // first, the room
            floor = seen == 1 ? rms : floor * 0.8f + rms * 0.2f;
            return null;
        }
        if (teaching != null) return teach(f, rms);
        if (cooldown > 0) cooldown--;
        floor = floor * 0.998f + Math.min(rms, floor * 3f) * 0.002f;
        // Always listening (on the TV only): keep the last few seconds of fingerprints.
        int before = feats.size();
        mfcc.push(f, 0, f.length);
        // A voice from the sofa reaches this microphone at only 1.5-2.5x the room's level.
        recentLoud = recentLoud * 0.96f + (rms > Math.max(floor * 1.35f, 21f) ? 0.04f : 0f);
        while (feats.size() > MAX_FRAMES * 2) feats.remove(0);
        sinceCheck += feats.size() - before;
        // A short phrase on its own (0.3-1.4 s, then a pause) is a candidate too: the cloud checks it.
        float on = Math.max(floor * 1.35f, 21f), off = Math.max(floor * 1.2f, 19f);
        if (rms > on) {
            if (segFrames == 0) segQuiet = 0;
            segFrames++;
            segQuiet = 0;
        } else if (segFrames > 0) {
            segQuiet++;
            if (rms > off) segFrames++;
            if (segQuiet >= 10) {                               // 200 ms of quiet: the phrase is over
                boolean shortOne = segFrames >= 15 && segFrames <= 70;
                segFrames = 0;
                if (shortOne && cooldown == 0) {
                    cooldown = 50;
                    lastScores = "short phrase";
                    return new Hit(new byte[0], Float.MAX_VALUE);
                }
            }
        }
        if (templates.isEmpty() || cooldown > 0 || sinceCheck < 6 || recentLoud < 0.08f) return null;
        sinceCheck = 0;
        long t0 = System.nanoTime();
        Hit h = check();
        checkNanos += System.nanoTime() - t0;
        checks++;
        if (h != null) {
            cooldown = 75;                                    // 1.5 s before listening for it again
            feats.clear();
            mfcc.reset();
        }
        return h;
    }

    float recentLoud;
    int segFrames, segQuiet;
    long checkNanos;
    int checks;
    String nearMiss = "";

    void add(short[] f) {
        for (short v : f) {
            pcm.write(v & 0xFF);
            pcm.write((v >> 8) & 0xFF);
        }
        mfcc.push(f, 0, f.length);
    }

    /** Teaching: one whole utterance at a time, between pauses. */
    Hit teach(short[] f, float rms) {
        // This TV's microphone is quiet: the room sits near 18, a normal voice from the sofa at 40-65.
        float on = Math.max(floor * 1.9f, 26f), off = Math.max(floor * 1.35f, 18f);
        if (!inSpeech) {
            floor = floor * 0.99f + Math.min(rms, floor * 2f) * 0.01f;
            pre.add(f.clone());
            if (pre.size() > PREROLL) pre.poll();
            if (rms > on) {
                inSpeech = true;
                quietFor = framesIn = 0;
                feats.clear();
                mfcc.reset();
                pcm.reset();
                for (short[] p : pre) add(p);
                pre.clear();
            }
            return null;
        }
        add(f);
        framesIn++;
        quietFor = rms < off ? quietFor + 1 : 0;
        if (quietFor >= QUIET_END || framesIn >= MAX_FRAMES) {
            List<float[]> u = trim(feats);
            byte[] said = pcm.toByteArray();
            Taught t = teaching;
            int heardFrames = framesIn;
            reset();
            boolean ok = u.size() >= 20 && u.size() <= 160;
            android.util.Log.i("QuietVoice", "wake: teaching heard " + heardFrames * 20 + " ms of sound, " + u.size() * 10
                    + " ms of it speech" + (ok ? "" : " (too " + (u.size() < 20 ? "short" : "long") + ", ignored)"));
            if (ok && t != null) t.utterance(u, said);
        }
        return null;
    }

    /**
     * Does the sound of the last moment end in the wake word? For each recording of it: take the
     * last stretch about its length (and a bit), normalise it as if it were the wake word, and
     * line them up with the end pinned to now. Works over the TV's own sound: no pauses needed.
     */
    Hit check() {
        int n = feats.size(), votes = 0;
        // Only something clearly louder than the room's background can be the wake word: a good
        // part of the last half second must stand above the quietest fifth of the last 3 s.
        float[] es = new float[n];
        for (int j = 0; j < n; j++) es[j] = feats.get(j)[Mfcc.CEPS];
        float[] sorted = es.clone();
        java.util.Arrays.sort(sorted);
        float room = sorted[Math.max(0, n / 5)];
        int recent = Math.min(n, 50), loud = 0;
        for (int j = n - recent; j < n; j++) if (es[j] > room + 0.8f) loud++;    // +3.5 dB: a voice here is +4-8
        if (loud < recent * 0.3f) return null;
        float best = Float.MAX_VALUE;
        StringBuilder sc = new StringBuilder();
        for (float[][] t : templates) {
            int L = t.length, from = Math.max(0, n - (int) (L * 1.5f) - 6);
            if (n - from < L * 0.7f) {
                sc.append("- ");
                continue;
            }
            float[][] u = Mfcc.normalise(feats, from, n, Math.max(from, n - L), n);
            float sc1 = Mfcc.matchEnding(t, u, 6);
            sc.append(String.format(java.util.Locale.ROOT, "%.2f ", sc1));
            if (sc1 < threshold) votes++;
            best = Math.min(best, sc1);
        }
        lastScores = sc.toString().trim();
        // Strict: all but one of the recordings must agree, and the closest must be clearly close
        // (the first taught threshold proved loose: ordinary room sound came within it).
        int strictVotes = 0;
        for (String v : lastScores.split(" ")) {
            try {
                if (Float.parseFloat(v) < threshold * 0.88f) strictVotes++;
            } catch (NumberFormatException ignored) {
            }
        }
        if (best > threshold * 1.15f) {                       // not even loosely like it
            if (best < threshold * 1.4f) nearMiss = lastScores;
            return null;
        }
        return new Hit(new byte[0], best);                    // a candidate: the cloud confirms
    }

    /** The utterance without the quiet around it (by the fingerprint's own loudness-free shape: we use energy). */
    static List<float[]> trim(List<float[]> frames) {
        int a = Mfcc.speechStart(frames, frames.size()), b = Math.max(a, Mfcc.speechEnd(frames));
        return new ArrayList<>(frames.subList(a, b));
    }

    // ---------------------------------------------------------------- learning the word

    /** Sets the templates and a threshold from how alike they are to each other. */
    void learn(List<List<float[]>> takes) {
        templates.clear();
        for (List<float[]> t : consistent(takes)) templates.add(Mfcc.normalise(t));
        threshold = thresholdFor(templates);
    }

    /** Takes much longer or shorter than the rest (a pause, a cough) are left out. */
    static List<List<float[]>> consistent(List<List<float[]>> takes) {
        List<Integer> lens = new ArrayList<>();
        for (List<float[]> t : takes) lens.add(t.size());
        java.util.Collections.sort(lens);
        float med = lens.isEmpty() ? 0 : lens.get(lens.size() / 2);
        List<List<float[]>> ok = new ArrayList<>();
        for (List<float[]> t : takes) if (Math.abs(t.size() - med) <= med * 0.4f) ok.add(t);
        return ok.size() >= 2 ? ok : takes;
    }

    /** How many of the takes agree with the others (for teaching: "say it once more"). */
    static int agreeing(List<List<float[]>> takes) {
        return consistent(takes).size();
    }

    static float thresholdFor(List<float[][]> ts) {
        List<Float> d = new ArrayList<>();
        for (int a = 0; a < ts.size(); a++)
            for (int b = 0; b < ts.size(); b++)
                if (a != b) {
                    float s = Mfcc.match(ts.get(a), ts.get(b), null);
                    if (s < Float.MAX_VALUE / 8) d.add(s);
                }
        if (d.isEmpty()) return Float.MAX_VALUE;
        java.util.Collections.sort(d);
        float median = d.get(d.size() / 2);
        return median * 1.5f;                              // generous: the cloud double-checks every wake
    }

    // ---------------------------------------------------------------- keeping it

    String save() {
        StringBuilder b = new StringBuilder();
        b.append(threshold);
        for (float[][] t : templates) {
            b.append('|');
            for (int i = 0; i < t.length; i++) {
                if (i > 0) b.append(';');
                for (int k = 0; k < t[i].length; k++) {
                    if (k > 0) b.append(',');
                    b.append(Math.round(t[i][k] * 1000) / 1000f);
                }
            }
        }
        return b.toString();
    }

    boolean load(String s) {
        templates.clear();
        if (s == null || s.isEmpty()) return false;
        try {
            String[] parts = s.split("\\|");
            threshold = Float.parseFloat(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                String[] rows = parts[p].split(";");
                float[][] t = new float[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    String[] v = rows[i].split(",");
                    t[i] = new float[v.length];
                    for (int k = 0; k < v.length; k++) t[i][k] = Float.parseFloat(v[k]);
                }
                templates.add(t);
            }
            return !templates.isEmpty();
        } catch (Exception e) {
            templates.clear();
            return false;
        }
    }
}
