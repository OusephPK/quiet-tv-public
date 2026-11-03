package com.quiet.px;

/**
 * How the creatures on the bank know about each other (see LIFE.md). Nobody is pinned to a spot:
 * when anyone chooses where to go, they choose somewhere roomy, away from the others, so they spread
 * out on their own; and every so often each one glances at whatever's most worth a look nearby,
 * even when it doesn't do anything about it.
 */
public final class Ties {
    static final int PIP = 0, FROG = 1, MOUSE = 2, SNAIL = 3;

    /** Where each ground creature is on the bank (NaN when it isn't out). */
    static float where(Stage s, int who) {
        switch (who) {
            case PIP: return s.dogOut ? s.dog.x : Float.NaN;
            case FROG: return s.frogT >= 0 ? s.frogX : Float.NaN;
            case MOUSE: return s.life.mouseT >= 0 ? s.life.mouseX : Float.NaN;
            case SNAIL: return s.life.snailOut ? s.life.snailX : Float.NaN;
        }
        return Float.NaN;
    }

    /** Where Pip is, if he's out (NaN if not). */
    public static float pipAt(Stage s) {
        return where(s, PIP);
    }

    /** Where the frog is, if it's out (NaN if not). */
    public static float frogAt(Stage s) {
        return where(s, FROG);
    }

    /** The nearest other creature's distance from x. */
    static float clearance(Stage s, float x, int me) {
        float best = 999;
        for (int w = 0; w < 4; w++) {
            if (w == me || w == FROG && s.frogAfloat) continue;   // out on the stream, nobody's in its way
            float o = where(s, w);
            if (!Float.isNaN(o)) best = Math.min(best, Math.abs(o - x));
        }
        return best;
    }

    /**
     * Somewhere to go in [lo, hi]: roomy (away from the others), not too far, with a little whim.
     * keep: how much room it likes (beyond this, more room doesn't matter).
     */
    static float roomy(Stage s, float lo, float hi, float from, int me, float keep) {
        if (hi <= lo) return lo;
        float best = from, bestScore = -1e9f;
        for (int k = 0; k < 9; k++) {
            float x = lo + s.rnd.nextFloat() * (hi - lo);
            float room = Math.min(keep, clearance(s, x, me));
            float score = room * 2f - Math.abs(x - from) * 0.15f + s.rnd.nextFloat() * 12f;
            if (score > bestScore) {
                bestScore = score;
                best = x;
            }
        }
        return best;
    }

    /**
     * What's most worth a glance from x (a screen x), or NaN. Things that move or make a noise
     * matter more; nearer matters more. Not every look is a reaction: often it's just noticing.
     */
    static float worthALook(Stage s, float x, int me) {
        float best = Float.NaN, bestScore = 0;
        Life l = s.life;
        for (int w = 0; w < 4; w++) {
            if (w == me) continue;
            float o = where(s, w);
            if (Float.isNaN(o)) continue;
            float d = Math.abs(o - x);
            if (d < 3 || d > 150) continue;
            float sal = w == MOUSE ? 3f : w == FROG ? 2f : w == PIP ? 2f : 0.8f;
            float score = sal * (1f - d / 160f) * (0.6f + s.rnd.nextFloat() * 0.8f);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (s.fishT >= 0) {                                         // a fish leaping: everyone looks
            float score = 3f * (0.6f + s.rnd.nextFloat() * 0.8f);
            if (score > bestScore) {
                bestScore = score;
                best = s.fishX + 10;
            }
        }
        if (l.birdT >= 0 && l.birdPerch > 0) {                       // the bird on the keystone
            float score = 1.6f * (0.5f + s.rnd.nextFloat());
            if (score > bestScore) {
                bestScore = score;
                best = l.birdX;
            }
        }
        float m = Math.abs(s.mote.x - x);
        if (m < 120 && (s.mote.moving() || s.mote.y > Stage.WATER_Y - 50)) {   // Mote, moving or low
            float score = 1.4f * (0.5f + s.rnd.nextFloat());
            if (score > bestScore) {
                bestScore = score;
                best = s.mote.x;
            }
        }
        return best;
    }
}
