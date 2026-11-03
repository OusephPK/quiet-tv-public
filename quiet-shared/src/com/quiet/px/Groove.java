package com.quiet.px;

import android.media.audiofx.Visualizer;

/**
 * Listens to what the TV is playing (the output mix, never the microphone) for kicks, and keeps
 * the world's beat clock on them: Mote's dance, the fireflies and Pip's nodding follow the song.
 * Needs RECORD_AUDIO to be granted; without it the world just grooves at a relaxed 100 BPM.
 */
public final class Groove implements Visualizer.OnDataCaptureListener {
    final Stage stage;
    Visualizer vis;
    float avg, last;
    long lastHit;
    final float[] gaps = new float[8];
    int nGaps;
    // How loud the song is now against how loud it's been lately; a lasting jump is a swell.
    float fast, slow;
    long aboveSince, lastSwell;
    // The song in three bands, each against its own recent level: bass, the middle, the top.
    final float[] bandFast = new float[3], bandSlow = new float[3];
    static final int[][] BANDS = {{1, 3}, {4, 40}, {41, 180}};

    public Groove(Stage stage) {
        this.stage = stage;
    }

    public void start() {
        if (vis != null) return;
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            vis.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), false, true);
            vis.setEnabled(true);
            android.util.Log.i("QuietGroove", "listening");
        } catch (Throwable t) {
            android.util.Log.i("QuietGroove", "can't listen (" + t.getMessage() + "), steady tempo");
            vis = null;
        }
    }

    public void stop() {
        if (vis == null) return;
        try {
            vis.setEnabled(false);
            vis.release();
        } catch (Throwable ignored) {
        }
        vis = null;
    }

    @Override
    public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) {}

    @Override
    public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
        // Bins are re/im pairs; with 1024 samples at 44.1/48 kHz each is ~45 Hz, so 1..3 is the kick.
        float e = 0;
        for (int k = 1; k <= 3 && 2 * k + 1 < fft.length; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            e += (float) Math.sqrt(re * re + im * im);
        }
        long now = System.currentTimeMillis();
        boolean rising = e > last;
        last = e;
        if (e > avg * 1.35f && e > 6f && rising && now - lastHit > 280) {
            if (lastHit > 0) {
                float gap = (now - lastHit) / 1000f;
                while (gap > 0.86f) gap /= 2f;          // fold into 70..150 BPM
                while (gap < 0.4f) gap *= 2f;
                gaps[nGaps++ % gaps.length] = gap;
                if (nGaps >= 4) {
                    float[] g = java.util.Arrays.copyOf(gaps, Math.min(nGaps, gaps.length));
                    java.util.Arrays.sort(g);
                    float bpm = 60f / g[g.length / 2];
                    if (Math.abs(bpm - stage.bpm) > 3) android.util.Log.i("QuietGroove", "tempo " + Math.round(bpm));
                    stage.bpm = bpm;
                }
            }
            lastHit = now;
            stage.hitAt = now;
        }
        avg = avg * 0.92f + e * 0.08f;
        float all = 0;
        for (int k = 1; k <= 64 && 2 * k + 1 < fft.length; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            all += (float) Math.sqrt(re * re + im * im);
        }
        for (int b = 0; b < 3; b++) {
            float bv = 0;
            for (int k = BANDS[b][0]; k <= BANDS[b][1] && 2 * k + 1 < fft.length; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                bv += (float) Math.sqrt(re * re + im * im);
            }
            bandFast[b] += (bv - bandFast[b]) * 0.35f;
            bandSlow[b] += (bv - bandSlow[b]) * 0.01f;
            float perBin = bandSlow[b] / (BANDS[b][1] - BANDS[b][0] + 1);   // the song's tonal balance (Party reads it)
            if (b == 0) stage.toneBass = perBin;
            else if (b == 1) stage.toneMid = perBin;
            else stage.toneHigh = perBin;
            // Around its usual level a band sits near 0.4; a hit well above it reaches 1.
            float level = bandSlow[b] > 0.5f ? Math.max(0f, Math.min(1f, (bandFast[b] / bandSlow[b] - 0.6f) / 1.1f)) : 0f;
            if (b == 0) stage.bass = level;
            else if (b == 1) stage.mid = level;
            else stage.high = level;
        }
        fast += (all - fast) * 0.2f;                    // ~a quarter of a second
        slow += (all - slow) * 0.008f;                  // ~six seconds
        stage.loud = slow > 1 ? Math.min(1f, fast / (slow * 1.8f)) : 0f;
        if (slow > 20 && fast > slow * 1.4f) {
            if (aboveSince == 0) aboveSince = now;
            else if (now - aboveSince > 1200 && now - lastSwell > 25000) {   // a chorus, a drop
                lastSwell = now;
                stage.swellAt = now;
            }
        } else aboveSince = 0;
        if (now - lastHit > 6000) {                     // quiet passage: forget the tempo
            stage.bpm = 0;
            nGaps = 0;
        }
    }
}
