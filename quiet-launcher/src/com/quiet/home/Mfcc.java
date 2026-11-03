package com.quiet.home;

/**
 * Sound → a "fingerprint" every 10 ms: 12 mel-frequency cepstral coefficients, the classic
 * description of how speech sounds (what the mouth is shaping, not how loud it is). Fed 16 kHz
 * samples in any amounts; emits one 12-number frame per 160 samples (25 ms windows, 10 ms apart).
 * Written for the TV: one 512-point FFT per frame, no allocation once running.
 */
final class Mfcc {

    interface Out {
        void frame(float[] c);
    }

    static final int RATE = 16000, WIN = 400, HOP = 160, N = 512, MELS = 26, CEPS = 12;

    static final float[] HAMMING = new float[WIN];
    static final float[][] FILTERS = new float[MELS][N / 2 + 1];
    static final float[][] DCT = new float[CEPS][MELS];
    static final float[] COS = new float[N / 2], SIN = new float[N / 2];
    static final int[] REV = new int[N];

    static {
        for (int i = 0; i < WIN; i++) HAMMING[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (WIN - 1)));
        // Triangular mel filters from 60 Hz to 7.6 kHz.
        double lo = mel(60), hi = mel(7600);
        int[] bin = new int[MELS + 2];
        for (int m = 0; m < MELS + 2; m++) bin[m] = (int) Math.floor((N + 1) * hz(lo + (hi - lo) * m / (MELS + 1)) / RATE);
        for (int m = 1; m <= MELS; m++) {
            for (int k = bin[m - 1]; k < bin[m]; k++) FILTERS[m - 1][k] = (k - bin[m - 1]) / (float) Math.max(1, bin[m] - bin[m - 1]);
            for (int k = bin[m]; k <= bin[m + 1] && k <= N / 2; k++) FILTERS[m - 1][k] = (bin[m + 1] - k) / (float) Math.max(1, bin[m + 1] - bin[m]);
        }
        for (int c = 0; c < CEPS; c++)                       // c1..c12 (c0 is just loudness)
            for (int m = 0; m < MELS; m++) DCT[c][m] = (float) Math.cos(Math.PI * (c + 1) * (m + 0.5) / MELS);
        for (int i = 0; i < N / 2; i++) {
            COS[i] = (float) Math.cos(-2 * Math.PI * i / N);
            SIN[i] = (float) Math.sin(-2 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }

    static double mel(double hz) {
        return 2595 * Math.log10(1 + hz / 700);
    }

    static double hz(double mel) {
        return 700 * (Math.pow(10, mel / 2595) - 1);
    }

    final float[] ring = new float[WIN];
    final float[] re = new float[N], im = new float[N], power = new float[N / 2 + 1], energies = new float[MELS];
    int filled, sinceHop;
    float prev;
    final Out out;

    Mfcc(Out out) {
        this.out = out;
    }

    void reset() {
        filled = 0;
        sinceHop = 0;
        prev = 0;
    }

    void push(short[] s, int off, int n) {
        for (int i = off; i < off + n; i++) {
            float x = s[i] / 32768f;
            float y = x - 0.97f * prev;                       // pre-emphasis: the consonants matter
            prev = x;
            System.arraycopy(ring, 1, ring, 0, WIN - 1);
            ring[WIN - 1] = y;
            if (filled < WIN) filled++;
            if (++sinceHop >= HOP && filled == WIN) {
                sinceHop = 0;
                out.frame(compute());
            }
        }
    }

    float[] compute() {
        for (int i = 0; i < N; i++) {
            re[i] = i < WIN ? ring[i] * HAMMING[i] : 0f;
            im[i] = 0f;
        }
        fft(re, im);
        float total = 0;
        for (int k = 0; k <= N / 2; k++) {
            power[k] = re[k] * re[k] + im[k] * im[k];
            total += power[k];
        }
        for (int m = 0; m < MELS; m++) {
            float e = 0;
            float[] f = FILTERS[m];
            for (int k = 0; k <= N / 2; k++) if (f[k] != 0) e += f[k] * power[k];
            energies[m] = (float) Math.log(e + 1e-9);
        }
        float[] c = new float[CEPS + 1];                       // + the frame's loudness (log), for finding speech
        c[CEPS] = (float) Math.log(total + 1e-9);
        for (int i = 0; i < CEPS; i++) {
            float v = 0;
            for (int m = 0; m < MELS; m++) v += DCT[i][m] * energies[m];
            c[i] = v;
        }
        return c;
    }

    static void fft(float[] re, float[] im) {
        for (int i = 0; i < N; i++) {
            int j = REV[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            int half = size >> 1, step = N / size;
            for (int i = 0; i < N; i += size)
                for (int j = 0; j < half; j++) {
                    float wr = COS[j * step], wi = SIN[j * step];
                    int a = i + j, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
        }
    }

    // ---------------------------------------------------------------- comparing two utterances

    /** Takes each coefficient's average away (cancels the room and the microphone's colour). */
    static float[][] normalise(java.util.List<float[]> frames) {
        return normalise(frames, 0, frames.size(), 0, frames.size());
    }

    /** Frames [from, to), with the average taken over [meanFrom, meanTo) (the wake word, not what follows). */
    static float[][] normalise(java.util.List<float[]> frames, int from, int to, int meanFrom, int meanTo) {
        float[] mean = new float[CEPS];
        int n = Math.max(1, meanTo - meanFrom);
        for (int j = meanFrom; j < meanTo; j++) for (int i = 0; i < CEPS; i++) mean[i] += frames.get(j)[i] / n;
        float[][] out = new float[to - from][];
        for (int j = from; j < to; j++) {
            out[j - from] = new float[CEPS];
            for (int i = 0; i < CEPS; i++) out[j - from][i] = frames.get(j)[i] - mean[i];
        }
        return out;
    }

    /** Where speech really starts: the first frame within 15 dB of the loudest nearby. */
    static int speechStart(java.util.List<float[]> f, int lookAhead) {
        int n = Math.min(f.size(), lookAhead);
        float peak = -1e9f;
        for (int j = 0; j < n; j++) peak = Math.max(peak, f.get(j)[CEPS]);
        for (int j = 0; j < n; j++) if (f.get(j)[CEPS] > peak - 3.5f) return Math.max(0, j - 2);
        return 0;
    }

    static int speechEnd(java.util.List<float[]> f) {
        float peak = -1e9f;
        for (float[] x : f) peak = Math.max(peak, x[CEPS]);
        for (int j = f.size() - 1; j >= 0; j--) if (f.get(j)[CEPS] > peak - 3.5f) return Math.min(f.size(), j + 3);
        return f.size();
    }

    static float dist(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < CEPS; i++) {
            float d = a[i] - b[i];
            s += d * d;
        }
        return (float) Math.sqrt(s);
    }

    /**
     * How unlike the template the start of the utterance is (dynamic time warping, so a word said
     * faster or slower still lines up). The utterance may begin a little late (free start within
     * the first frames) and may carry on after the wake word (open end): returns the best average
     * frame distance, and in endAt[0] the utterance frame where the wake word ended.
     */
    /**
     * The template against the utterance with the utterance's end pinned to its last frames (the
     * wake word has just finished) and its start free (anywhere in the first part).
     */
    static float matchEnding(float[][] t, float[][] u, int endSlack) {
        int T = t.length, U = u.length;
        if (T < 5 || U < T / 2) return Float.MAX_VALUE;
        float INF = Float.MAX_VALUE / 4;
        float[] prevC = new float[U], curC = new float[U];
        int[] prevL = new int[U], curL = new int[U];
        int freeStart = Math.max(1, U - (int) (T * 0.6f));
        for (int j = 0; j < U; j++) {
            prevC[j] = j < freeStart ? dist(t[0], u[j]) : INF;
            prevL[j] = 1;
        }
        for (int i = 1; i < T; i++) {
            // Only cells a path ending now could pass through (slopes between 1/2 and 2): ~half the work.
            int left = T - 1 - i, lo = Math.max(0, U - endSlack - left * 2 - 2), hi = Math.min(U - 1, U - 1 - left / 2 + 2);
            for (int j = 0; j < U; j++) {
                if (j < lo || j > hi) {
                    curC[j] = INF;
                    curL[j] = 1;
                    continue;
                }
                float best = prevC[j];
                int len = prevL[j];
                if (j > 0 && prevC[j - 1] <= best) {
                    best = prevC[j - 1];
                    len = prevL[j - 1];
                }
                if (j > 0 && curC[j - 1] < best) {
                    best = curC[j - 1];
                    len = curL[j - 1];
                }
                if (best >= INF) {
                    curC[j] = INF;
                    curL[j] = 1;
                    continue;
                }
                curC[j] = best + dist(t[i], u[j]);
                curL[j] = len + 1;
            }
            float[] tc = prevC; prevC = curC; curC = tc;
            int[] tl = prevL; prevL = curL; curL = tl;
        }
        float best = Float.MAX_VALUE;
        for (int j = Math.max(0, U - endSlack); j < U; j++)
            if (prevC[j] < INF) best = Math.min(best, prevC[j] / prevL[j]);
        return best;
    }

    static float match(float[][] t, float[][] u, int[] endAt) {
        int T = t.length, U = u.length;
        if (T < 5 || U < T / 2) return Float.MAX_VALUE;
        int band = Math.max(8, T / 2);
        float INF = Float.MAX_VALUE / 4;
        float[] prevC = new float[U], curC = new float[U];
        int[] prevL = new int[U], curL = new int[U];
        int freeStart = Math.min(U, 10);
        for (int j = 0; j < U; j++) {
            prevC[j] = j < freeStart ? dist(t[0], u[j]) : INF;
            prevL[j] = 1;
        }
        for (int i = 1; i < T; i++) {
            for (int j = 0; j < U; j++) {
                curC[j] = INF;
                curL[j] = 1;
                if (j > i + band + freeStart || j < i / 2 - band) continue;
                float d = dist(t[i], u[j]);
                float best = prevC[j];
                int len = prevL[j];
                if (j > 0 && prevC[j - 1] <= best) {
                    best = prevC[j - 1];
                    len = prevL[j - 1];
                }
                if (j > 0 && curC[j - 1] < best) {
                    best = curC[j - 1];
                    len = curL[j - 1];
                }
                if (best >= INF) continue;
                curC[j] = best + d;
                curL[j] = len + 1;
            }
            float[] tc = prevC; prevC = curC; curC = tc;
            int[] tl = prevL; prevL = curL; curL = tl;
        }
        float best = Float.MAX_VALUE;
        for (int j = Math.max(0, (int) (T * 0.6f)); j < U; j++) {
            if (prevC[j] >= INF) continue;
            float avg = prevC[j] / prevL[j];
            if (avg < best) {
                best = avg;
                if (endAt != null) endAt[0] = j;
            }
        }
        return best;
    }
}
