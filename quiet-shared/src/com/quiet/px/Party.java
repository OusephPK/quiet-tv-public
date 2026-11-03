package com.quiet.px;

/**
 * When Apple Music plays, the old road throws a party, and what kind depends on the song.
 *
 * The mood comes from the music itself (Groove): the tempo, how much of it is bass against the
 * middle, how bright the top is. It only changes after a few seconds of agreement, so one quiet
 * bar doesn't send everyone home.
 *
 *   CHILL   slow, or no steady beat      Pip lounges, Mote drifts in eights, the frog croons
 *   GROOVE  mid-tempo, heavy bass        Pip nods and two-steps in sunglasses, the mouse breaks
 *   DANCE   fast and steady              hops and spins, colour fireflies, a disco ball comes down
 *   ROCK    fast, guitars over bass      Pip headbangs, the frog jumps, the spider bangs on its thread
 *   BRIGHT  light, lots of top           Pip tilts his head, a bird sings, Mote flutters
 *
 * Whatever the mood, nobody sleeps through it: everyone comes out.
 */
final class Party {
    static final int CHILL = 0, GROOVE = 1, DANCE = 2, ROCK = 3, BRIGHT = 4;
    static final String[] NAMES = {"chill", "groove", "dance", "rock", "bright"};

    int mood = CHILL, maybe = -1;
    float maybeFor, judgeIn;
    int forced = -1;                                   // debug.quiet.mood
    float bpmS;                                        // the tempo, steadied over several seconds
    String lastGenre = "";

    // The disco ball, lowered on a thread when a DANCE song starts.
    float ball;                                        // 0 up out of sight .. 1 down
    int lastNote;

    boolean on(Stage s) {
        return s.won > 0.4f;
    }

    /**
     * The kind of music, from the genres Apple gives the song (much surer than listening: the tonal
     * balance of real songs swings too far within one song to tell genres apart). Pop and the like
     * go by tempo. -1 when the genres don't say.
     */
    int fromGenre(String genres, float bpm) {
        String g = genres.toLowerCase(java.util.Locale.ROOT);
        if (g.isEmpty()) return -1;
        for (String one : g.split("\\|")) {
            if (one.equals("music") || one.isEmpty()) continue;
            if (has(one, "ambient", "classical", "new age", "lo-fi", "lofi", "chill", "downtempo", "easy listening",
                    "jazz", "soundtrack", "meditation", "sleep", "instrumental", "radio")) return CHILL;
            if (has(one, "hip-hop", "hip hop", "rap", "r&b", "soul", "funk", "reggae", "afro", "trap", "dancehall")) return GROOVE;
            if (has(one, "dance", "electronic", "house", "techno", "edm", "disco", "trance", "dubstep", "drum", "punjabi", "bhangra")) return DANCE;
            if (has(one, "metal", "punk", "grunge", "hard rock", "alternative", "rock"))      // slow "rock" is usually mellow (Jack Johnson is filed as Rock)
                return one.contains("soft rock") || (bpm > 40 && bpm < 95 && !has(one, "metal", "punk", "hard")) ? BRIGHT : ROCK;
            if (has(one, "singer/songwriter", "folk", "acoustic", "country", "indie", "christian", "gospel", "children")) return BRIGHT;
            if (has(one, "pop", "latin", "bollywood", "indian", "k-pop", "j-pop", "tamil", "malayalam", "telugu", "hindi"))
                return bpm >= 108 ? DANCE : BRIGHT;
        }
        return -1;
    }

    static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    int classify(Stage s) {
        if (forced >= 0) return forced;
        int byGenre = fromGenre(s.genre, bpmS);
        if (byGenre >= 0) return byGenre;
        // Measured on real songs through the TV: bass/mid sits around 1.2 (guitars) to 1.7+ (808s),
        // top/mid around 0.15 (warm) to 0.3+ (bright).
        float bpm = bpmS, mid = Math.max(1e-3f, s.toneMid);
        float bassy = s.toneBass / mid, bright = s.toneHigh / mid;
        if (bpm < 40) return bright > 0.27f ? BRIGHT : CHILL;
        if (bpm < 95) return bassy > 1.55f ? GROOVE : bright > 0.27f ? BRIGHT : CHILL;
        if (bpm < 118) return bassy > 1.55f ? GROOVE : bassy < 1.3f ? ROCK : bright > 0.27f ? BRIGHT : DANCE;
        return bassy < 1.3f ? ROCK : DANCE;
    }

    void tick(Stage s, float dt) {
        bpmS += ((s.bpm > 40 ? s.bpm : 0f) - bpmS) * Math.min(1f, dt / 6f);
        // A new song: decide straight away (from its genre) rather than waiting to hear it out.
        String g = s.genre;
        if (!g.equals(lastGenre)) {
            lastGenre = g;
            int c = fromGenre(g, bpmS);
            if (c >= 0 && c != mood) {
                mood = c;
                maybe = -1;
                android.util.Log.i("QuietGroove", "mood " + NAMES[mood] + " (genre " + g + ")");
            }
        }
        // What kind of music is this? Judged once a second; a change has to hold for a while.
        judgeIn -= dt;
        if (judgeIn <= 0) {
            judgeIn = 1f;
            int c = classify(s);
            if (on(s) && (int) (s.t) % 10 == 0)                     // for tuning: what it hears
                android.util.Log.i("QuietGroove", "hearing bpm " + Math.round(bpmS) + " bass/mid "
                        + String.format(java.util.Locale.ROOT, "%.2f top/mid %.2f → %s", s.toneBass / Math.max(1e-3f, s.toneMid),
                        s.toneHigh / Math.max(1e-3f, s.toneMid), NAMES[c]));
            if (c == mood) maybe = -1;
            else if (c != maybe) {
                maybe = c;
                maybeFor = 0;
            } else if ((maybeFor += 1f) >= (s.won < 0.6f ? 2f : 5f)) {
                mood = c;
                maybe = -1;
                android.util.Log.i("QuietGroove", "mood " + NAMES[mood] + " (bpm " + Math.round(bpmS) + ", bass/mid "
                        + String.format(java.util.Locale.ROOT, "%.2f", s.toneBass / Math.max(1e-3f, s.toneMid))
                        + ", top/mid " + String.format(java.util.Locale.ROOT, "%.2f", s.toneHigh / Math.max(1e-3f, s.toneMid)) + ")");
            }
        }
        boolean on = on(s);
        ball += ((on && mood == DANCE ? 1f : 0f) - ball) * Math.min(1f, dt / 1.6f);
        Life l = s.life;
        l.mouseParty = on;                                   // the mouse comes out to dance, somewhere roomy
        if (!on) return;

        // Everyone out.
        if (s.frogT < 0) s.frogIn = Math.min(s.frogIn, 1.5f);
        else if (!s.frogRides && s.frogT > 0.5f) s.frogSit = Math.max(s.frogSit, s.frogT + 6f);   // stays for the song
        if (l.spiderT < 0) l.spiderIn = Math.min(l.spiderIn, 4f);
        else if (l.spiderT > 14f) l.spiderT = 8f;                                                // hangs about
        if (s.night && s.mothT < 0) s.mothIn = Math.min(s.mothIn, 5f);
        if (l.dark > 0.4f && (mood == DANCE || mood == ROCK)) {
            if (l.batT < 0) l.batIn = Math.min(l.batIn, 2f);
            else if (l.batT > 5f) l.batT = 0.5f;                                                 // keep swirling
        }
        if (l.day > 0.3f) {
            if (l.birdT < 0 && (mood == BRIGHT || mood == CHILL || mood == GROOVE)) l.birdIn = Math.min(l.birdIn, 6f);
            if (l.flyT < 0) l.flyIn = Math.min(l.flyIn, 6f);
            else if (l.flyT > 13f) l.flyT = 2f;
        }
        if (s.vista.crossT < 0) s.vista.crossIn = Math.min(s.vista.crossIn, 4f);
        if (s.beatNow) {
            int every = mood == DANCE || mood == ROCK ? 8 : 16;                // a fish leaps for it
            if (s.fishT < 0 && s.beats % every == 0) s.fishIn = 0;
            // Songs: the frog croons (chill, bright), the bird sings when it's perched (bright).
            boolean sing = (s.beats & 1) == 0 && s.beats != lastNote;
            if (sing && s.frogT > 0.6f && (mood == CHILL || mood == BRIGHT)) {
                lastNote = s.beats;
                s.spawn(s.frogX + 2, Stage.WATER_Y - 8, (s.rnd.nextFloat() - 0.5f) * 0.1f, -0.2f, 2.2f, Stage.P_NOTE);
            }
            if (sing && l.birdT >= 0 && mood == BRIGHT && s.rnd.nextBoolean())
                s.spawn(l.birdX, l.birdY - 4, 0.05f, -0.15f, 2f, Stage.P_NOTE);
        }
    }

    /** Lights for this frame: the disco ball's specks. Before the stone is lit. */
    void lights(Stage s) {
        if (ball < 0.05f) return;
        float[] at = ballAt(s);
        for (int k = 0; k < 6; k++) {
            float a = s.t * 1.1f + k * 1.047f;
            float px = at[0] + (float) Math.cos(a) * (70 + 20 * (k & 1)), py = at[1] + 40 + (float) Math.sin(a) * 34;
            s.light(px, py, 10, 0.5f * ball, (k & 1) == 0);
        }
    }

    float[] ballAt(Stage s) {
        float y = -8 + ball * 42 + (float) Math.sin(s.t * 1.3f) * 1.2f;
        return new float[]{231, y};
    }

    /** Disco fireflies: in a DANCE song they strobe through colours, two groups trading. */
    int flyColor(Stage s, int i, int normal) {
        if (mood != DANCE || s.won < 0.4f) return normal;
        int[] cols = {Pal.PINK, Pal.MINT_HI, Pal.AMBER, 0xFF9AA8FF};
        return cols[(i + s.beats) & 3];
    }

    /** The snail and the disco ball (after the creatures). */
    void draw(Stage s) {
        if (ball > 0.05f) {
            float[] at = ballAt(s);
            int bx = Math.round(at[0]), by = Math.round(at[1]);
            int top = 0;
            while (top < by && s.isSky(top * Stage.W + bx)) top++;           // hung from the rim of the opening
            for (int y = top; y < by - 2; y++) s.px(bx, y, 0xFF5A6078);
            int spin = (int) (s.t * 6);
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx * dx + dy * dy > 5) continue;
                    boolean lit = ((dx + dy + spin) & 1) == 0;
                    s.px(bx + dx, by + dy, lit ? ((dx + spin) % 3 == 0 ? Pal.TEXT : 0xFFB8C4E0) : 0xFF4A5470);
                }
            // Specks of light swinging round the cave.
            for (int k = 0; k < 6; k++) {
                float a = s.t * 1.1f + k * 1.047f;
                int px = Math.round(at[0] + (float) Math.cos(a) * (70 + 20 * (k & 1))), py = Math.round(at[1] + 40 + (float) Math.sin(a) * 34);
                int c = (k & 1) == 0 ? Pal.AMBER : Pal.MINT_HI;
                s.px(px, py, Pal.mix(s.out[Math.max(0, Math.min(Stage.W * Stage.H - 1, py * Stage.W + px))], c, ball));
            }
        }
    }
}
