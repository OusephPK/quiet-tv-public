package com.quiet.home;

/**
 * Takes the room's steady sound (hiss, hum, a fan, the TV's own quiet murmur) out of a recording before
 * it's raised and heard. This TV's microphone hears a sofa voice at only ~1.4x the room (3 Nov: room 23,
 * voice 33), so raising it raises the hiss just as much; this lowers the hiss first.
 *
 * Spectral subtraction: 32 ms pieces (512 at 16 kHz, half overlapping, Hann), the room's level in each
 * frequency taken from the quietest pieces (where nobody is talking),
 * and each piece's frequencies kept by how far they stand above it, smoothed in time so it doesn't
 * warble. Pure Java (no Android), so it can be tried off the TV.
 */
final class Clean {

    static final int N = Mfcc.N, H = N / 2, BINS = N / 2 + 1;
    static final float[] HANN = new float[N];

    static {
        for (int i = 0; i < N; i++) HANN[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));   // halves overlap to 1
    }

    private Clean() {
    }

    static short[] clean(short[] v) {
        int frames = v.length < N ? 0 : (v.length - N) / H + 1;
        if (frames < 8) return v;
        float[][] re = new float[frames][], im = new float[frames][], mag = new float[frames][BINS];
        for (int f = 0; f < frames; f++) {
            float[] r = new float[N], m = new float[N];
            for (int i = 0; i < N; i++) r[i] = v[f * H + i] * HANN[i];
            Mfcc.fft(r, m);
            re[f] = r;
            im[f] = m;
            for (int k = 0; k < BINS; k++) mag[f][k] = (float) Math.hypot(r[k], m[k]);
        }
        // The room, from the quietest pieces only (where nobody is talking), averaged per frequency: with
        // speech nearly all the way through, a share of every frequency would take the voice for room.
        float[] energy = new float[frames];
        for (int f = 0; f < frames; f++) for (int k = 2; k < BINS; k++) energy[f] += mag[f][k] * mag[f][k];
        Integer[] order = new Integer[frames];
        for (int f = 0; f < frames; f++) order[f] = f;
        java.util.Arrays.sort(order, (x, y) -> Float.compare(energy[x], energy[y]));
        int quiet = Math.max(3, frames / 7);
        float[] noise = new float[BINS];
        for (int q = 0; q < quiet; q++) for (int k = 0; k < BINS; k++) noise[k] += mag[order[q]][k] / quiet;
        float[] out = new float[v.length + N], prev = new float[BINS];
        java.util.Arrays.fill(prev, 1f);
        for (int f = 0; f < frames; f++) {
            float[] r = re[f], m = im[f];
            for (int k = 0; k < BINS; k++) {
                float g = mag[f][k] <= 0 ? 0.2f : Math.max(0.2f, 1f - 1.3f * noise[k] / mag[f][k]);
                if (k < 5) g = 0.05f;                         // under ~150 Hz: a fan's hum, not words
                g = 0.65f * g + 0.35f * prev[k];
                prev[k] = g;
                r[k] *= g;
                m[k] *= g;
                if (k > 0 && k < N / 2) {                     // the mirrored half, so the result stays real
                    r[N - k] = r[k];
                    m[N - k] = -m[k];
                }
            }
            for (int i = 0; i < N; i++) m[i] = -m[i];         // inverse by conjugates
            Mfcc.fft(r, m);
            for (int i = 0; i < N; i++) out[f * H + i] += r[i] / N;
        }
        short[] s = new short[v.length];
        int end = (frames - 1) * H + N;
        for (int i = 0; i < v.length; i++) {
            float x = i < end ? out[i] : v[i] * 0.12f;
            s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(x)));
        }
        return s;
    }
}
