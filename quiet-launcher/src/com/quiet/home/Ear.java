package com.quiet.home;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * Listens through the TV's built-in microphone, only while asked, and notices when you've finished:
 * it learns the room's sound for a moment (after the TV's own sound has stepped down), waits for
 * speech, and stops after a short pause (or at 10 seconds). Nothing is kept; the recording goes to the cloud once and is dropped.
 */
final class Ear {

    interface Level {
        void level(float l, boolean speaking);     // 0..1 for the meter
    }

    static final int RATE = 16000, FRAME = 320;     // 20 ms frames
    static final int MAX_MS = 10_000, WAIT_MS = 5_000, END_MS = 750, PREROLL = 25, LEARN = 20, ONSET = 3;

    volatile boolean stop, cancel;
    /** The microphone gave only silence (another app has it, or Android held it back). */
    boolean blocked;


    android.media.AudioDeviceInfo builtIn;
    /** A test: open the microphone this way (an AudioSource) instead of VOICE_RECOGNITION; -1: the usual. */
    int source = -1;
    boolean restartWake;
    /** The same recording without the cleaning (the cloud tries it if the cleaned one comes back empty). */
    byte[] raw;
    /** No clear start was heard, but something rose above the room: sent anyway (the cloud may be guessing). */
    boolean unsure;
    /** Speech has started (a second press now means: that's all, go). */
    volatile boolean voiced;
    final long startedAt = android.os.SystemClock.uptimeMillis();

    Ear(android.content.Context c) {
        builtIn = bestMic(c);
    }

    /** A USB microphone if one is plugged in (much nearer you, much clearer), else the TV's own. */
    static android.media.AudioDeviceInfo bestMic(android.content.Context c) {
        android.media.AudioDeviceInfo own = null;
        for (android.media.AudioDeviceInfo d : c.getSystemService(android.media.AudioManager.class)
                .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) return d;
            if (t == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) own = d;
        }
        return own;
    }

    /** Blocks until done. The recording as WAV, or null if nothing was said (or it was cancelled). */
    /** When the wake-word microphone is running, the Ear takes its stream instead of opening its own. */
    Mic mic;
    byte[] before;              // the sound just before (the wake word itself), kept at the front
    float presetFloor = -1;     // the room's level, already known (used when woken mid-sentence)

    byte[] listen(Level out) {
        AudioRecord r = null;
        if (mic == null) {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // VOICE_RECOGNITION: the source Android lets a voice service use while another app is in
            // front; pinned to the TV's own microphone (it otherwise sometimes picks the remote's path).
            r = new AudioRecord(source >= 0 ? source : MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 16));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                blocked = true;
                return null;
            }
            if (builtIn != null) r.setPreferredDevice(builtIn);
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * 4);
        java.util.ArrayDeque<short[]> pre = new java.util.ArrayDeque<>();
        short[] f = new short[FRAME];
        float floor = presetFloor >= 0 ? presetFloor : 20f;
        float[] learned = new float[LEARN];
        int frames = 0, quietFor = 0, loud = 0;
        boolean speaking = false, learning = true;
        float level = 0, peak = 0;                           // smoothed (~60 ms): a voice rises and falls each syllable
        float hx = 0, hy = 0;                                // the speech range only: a fan hums and rumbles below it
        java.util.ArrayList<short[]> waited = new java.util.ArrayList<>();   // all of it while waiting, in case
        if (before != null && before.length > 0) {           // woken mid-sentence: already speaking
            pcm.write(before, 0, before.length);
            speaking = true;
            learning = false;
        }
        try {
            if (r != null) r.startRecording();
            while (!cancel) {
                if (r != null) {
                    int got = 0;
                    while (got < FRAME && !cancel) {
                        int n = r.read(f, got, FRAME - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (got < FRAME) break;
                } else {
                    short[] g = mic.toEar.poll(400, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (g == null) {
                        if (!mic.running) break;
                        continue;
                    }
                    f = g;
                }
                frames++;
                // Judged above ~250 Hz (first-order high-pass): the room's fan lives mostly below, a voice above.
                double sum = 0;
                for (short v : f) {
                    hy = 0.91f * (hy + v - hx);
                    hx = v;
                    sum += hy * (double) hy;
                }
                float rms = (float) Math.sqrt(sum / FRAME);
                if (learning) {
                    // The room's own sound as it is now: with a film or music on, that's the TV itself (just
                    // stepped down), not you. Learned afresh each time; a quiet share of it, so speaking
                    // straight away doesn't raise it. Kept as the lead-in, in case you were quick.
                    learned[frames - 1] = rms;
                    pre.add(f.clone());
                    if (frames < LEARN) continue;
                    learning = false;
                    float[] sorted = learned.clone();
                    java.util.Arrays.sort(sorted);
                    if (sorted[LEARN - 1] == 0f) {                // true digital silence: we're being held back
                        blocked = true;
                        break;
                    }
                    floor = sorted[LEARN * 3 / 10];
                    continue;
                }
                // This TV's microphone is quiet (room ~18, a normal voice 40-65): relative to the room.
                // A sofa voice is only ~1.4x the room on this TV (3 Nov: room 23, voice 33), and it rises and
                // falls each syllable: judged on the smoothed level, against a low bar.
                level = level == 0 ? rms : level * 0.6f + rms * 0.4f;
                // (Speech range only, the room reads ~8, not ~23: the bar's floor is low to match; 3 Nov a fixed 22
                // kept a voice of ~19 from ever "starting".)
                float on = Math.max(floor * 1.35f, 7f), off = Math.max(floor * 1.15f, 6f);
                if (!speaking) {
                    pre.add(f.clone());
                    if (pre.size() > PREROLL) pre.poll();
                    if (waited.size() < WAIT_MS / 20 + LEARN) waited.add(f.clone());
                    peak = Math.max(peak, level / Math.max(1f, floor));
                    loud = level > on ? loud + 1 : 0;
                    if (loud >= ONSET) {                          // a moment of it, not a click or a bang
                        speaking = true;
                        voiced = true;
                        for (short[] p : pre) write(pcm, p);
                        pre.clear();
                        waited.clear();
                    } else if (loud == 0) {
                        floor = floor * 0.97f + Math.min(rms, floor * 1.5f) * 0.03f;   // drift with the room
                        if (frames * 20 > WAIT_MS || stop) {
                            // Something rose above the room but never clearly: maybe a soft voice. All of it goes
                            // (cleaned); what the cloud makes of it is checked (Voice: invented, chat).
                            if (peak > 1.2f) {
                                for (short[] p : waited) write(pcm, p);
                                speaking = true;
                                unsure = true;
                            }
                            break;
                        }
                    }
                } else {
                    write(pcm, f);
                    quietFor = level < off ? quietFor + 20 : 0;
                    if (quietFor >= END_MS || stop || pcm.size() >= RATE * 2 * MAX_MS / 1000) break;
                }
                if (stop && !speaking) break;
                if (out != null) out.level(Math.min(1f, rms / Math.max(on * 2.5f, 90f)), speaking);
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
                r.release();
            }
        }
        android.util.Log.i(Voice.TAG, "ear: source " + (source >= 0 ? source : 6) + ", room " + Math.round(floor)
                + ", voice up to " + Math.round(peak * 100) / 100f + "x the room" + (speaking ? "" : " (no start)"));
        if (cancel || !speaking || pcm.size() < RATE * 2 / 4) return null;   // under a quarter second: nothing
        byte[] got = pcm.toByteArray();
        raw = wavOf(louder(got, 30f));                       // as heard, for a second try if the cleaned one says nothing
        return wav(got);
    }

    static void write(ByteArrayOutputStream o, short[] f) {
        for (short v : f) {
            o.write(v & 0xFF);
            o.write((v >> 8) & 0xFF);
        }
    }

    /**
     * Quiet recordings brought up to a clear level before the cloud hears them: by the level of the voice
     * (the loud tenth of the 20 ms pieces), up to a cap, with the rare peaks rounded off instead of
     * clipped, so one click doesn't hold a soft voice down.
     */
    /** The room's steady hiss taken out first (Clean), so raising the voice doesn't raise it too. */
    static byte[] cleaned(byte[] pcm) {
        short[] v = new short[pcm.length / 2];
        for (int i = 0; i < v.length; i++) v[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        v = Clean.clean(v);
        byte[] out = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            out[2 * i] = (byte) (v[i] & 0xFF);
            out[2 * i + 1] = (byte) ((v[i] >> 8) & 0xFF);
        }
        return out;
    }

    static byte[] louder(byte[] pcm, float cap) {
        int n = pcm.length / 2, per = RATE / 50;
        if (n < per) return pcm;
        short[] v = new short[n];
        for (int i = 0; i < n; i++) v[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        float[] lv = new float[n / per];
        for (int k = 0; k < lv.length; k++) {
            double sum = 0;
            for (int i = k * per; i < (k + 1) * per; i++) sum += v[i] * (double) v[i];
            lv[k] = (float) Math.sqrt(sum / per);
        }
        float[] sorted = lv.clone();
        java.util.Arrays.sort(sorted);
        float voice = Math.max(1f, sorted[sorted.length * 9 / 10]);
        float g = Math.min(cap, 5000f / voice);
        android.util.Log.i(Voice.TAG, "ear: voice level " + Math.round(voice) + ", raised " + Math.round(g * 10) / 10f + "x");
        if (g <= 1.1f) return pcm;
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < n; i++) {
            float x = v[i] * g, a = Math.abs(x);
            if (a > 20000f) a = 20000f + (a - 20000f) * 12767f / (12767f + (a - 20000f));   // a soft knee up to full
            int y = Math.round(Math.signum(x) * Math.min(a, 32767f));
            out[2 * i] = (byte) (y & 0xFF);
            out[2 * i + 1] = (byte) ((y >> 8) & 0xFF);
        }
        return out;
    }

    /** Cleaned (Clean: the hiss gone, so it can be raised further), raised, as WAV. */
    static byte[] wav(byte[] pcm) {
        return wavOf(louder(cleaned(pcm), 400f));
    }

    static byte[] wavOf(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = RATE * 2;
        writeStr(o, "RIFF");
        writeInt(o, 36 + pcm.length);
        writeStr(o, "WAVEfmt ");
        writeInt(o, 16);
        writeShort(o, 1);
        writeShort(o, 1);
        writeInt(o, RATE);
        writeInt(o, byteRate);
        writeShort(o, 2);
        writeShort(o, 16);
        writeStr(o, "data");
        writeInt(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    static void writeStr(ByteArrayOutputStream o, String s) {
        for (char c : s.toCharArray()) o.write(c);
    }

    static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }
}
