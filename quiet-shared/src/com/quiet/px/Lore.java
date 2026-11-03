package com.quiet.px;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * What the well remembers. None of it is explained anywhere on screen.
 *
 * (In this shared copy the old script's sayings, its key, and what opens the door are placeholders: the real ones
 * are kept private, for the people who find them on the TV itself.)
 */
final class Lore {
    static final String[] SAYINGS = {"NOT THESE WORDS", "THE REAL ONES", "ARE KEPT", "ON THE TV"};   // placeholders
    static final String AFTER = "PLACEHOLDER";
    static final int[] SEQUENCE = {0, 0, 0, 0, 0};   // a placeholder: the real sequence is private
    static final boolean[][][] GLYPHS = new boolean[26][][];

    static {
        // Each letter: a stem and two or three branches on a 3×5 grid, all different.
        Random r = new Random(1);                       // (the real seed is private)
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 26; i++) {
            while (true) {
                boolean[][] g = new boolean[5][3];
                int stem = r.nextInt(3);
                for (int y = 0; y < 5; y++) g[y][stem] = true;
                int branches = 2 + r.nextInt(2);
                for (int b = 0; b < branches; b++) {
                    int y = r.nextInt(5), kind = r.nextInt(3);
                    if (kind == 0) for (int x = 0; x < 3; x++) g[y][x] = true;               // a bar
                    else if (kind == 1) g[y][(stem + 1 + r.nextInt(2)) % 3] = true;          // a spur
                    else {                                                                    // a slant
                        int x = (stem + 1) % 3;
                        g[y][x] = true;
                        if (y + 1 < 5) g[y + 1][(x + 1) % 3] = true;
                    }
                }
                StringBuilder k = new StringBuilder();
                for (boolean[] row : g) for (boolean c : row) k.append(c ? '#' : '.');
                if (seen.add(k.toString())) {
                    GLYPHS[i] = g;
                    break;
                }
            }
        }
    }

    final int[] recent = new int[SEQUENCE.length];
    float keeperIn = 90f, keeperT = -1, opening = -1;

    // ---------------------------------------------------------------- the moon, the door, the keeper

    static boolean fullMoon(Life l) {
        return l.moonPhase > 0.4f && l.moonPhase < 0.6f;
    }

    static int quarter(Life l) {
        float p = l.moonPhase;
        return p < 0.125f || p >= 0.875f ? 0 : p < 0.375f ? 1 : p < 0.625f ? 2 : 3;
    }

    /** A key on the home screen. True if that completed the door's sequence. */
    boolean key(Stage s, int code) {
        System.arraycopy(recent, 1, recent, 0, recent.length - 1);
        recent[recent.length - 1] = code;
        if (s.doorOpen || !openable(s)) return false;
        for (int i = 0; i < SEQUENCE.length; i++) if (recent[i] != SEQUENCE[i]) return false;
        s.doorOpen = true;
        opening = 0;
        if (s.sound != null) {
            s.sound.chime();
            s.sound.chime();
        }
        if (s.onDoorOpened != null) s.onDoorOpened.run();
        return true;
    }

    /** When the door listens: private in this shared copy (here, never). */
    static boolean openable(Stage s) {
        return false;
    }

    void tick(Stage s, float dt) {
        if (opening >= 0 && (opening += dt) > 4f) opening = -1;
        Life l = s.life;
        if (fullMoon(l) && l.dark > 0.6f && l.moonUp()) {
            keeperIn -= dt;
            if (keeperIn <= 0 && keeperT < 0) {
                keeperT = 0;
                keeperIn = 240f + s.rnd.nextFloat() * 240f;
            }
        }
        if (keeperT >= 0 && (keeperT += dt) > 14f) keeperT = -1;
    }

    boolean keeperHere() {
        return keeperT > 1f && keeperT < 13f;
    }

    // ---------------------------------------------------------------- drawing

    /** The old script, cut down the right-hand pillar: this quarter's saying. */
    void inscription(Stage s, boolean rosetta) {
        String text = s.doorOpen ? AFTER : SAYINGS[quarter(s.life)];
        boolean glow = fullMoon(s.life) && s.life.dark > 0.5f;
        int y = 60;                                              // down the broken edge of the near wall, below the words
        float w = s.wonder();
        int lit = 0, chase = s.beats % Math.max(1, text.replace(" ", "").length());
        for (int i = 0; i < text.length() && y < 150; i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                y += 3;
                continue;
            }
            boolean[][] g = GLYPHS[ch - 'A'];
            int x = Stage.jambEdge(y + 2) - 7;
            for (int gy = 0; gy < 5; gy++)
                for (int gx = 0; gx < 3; gx++) {
                    if (!g[gy][gx]) continue;
                    int px = x + gx, py = y + gy;
                    if (px >= Stage.W || py >= Stage.H || s.near[py * Stage.W + px] == 0) continue;
                    int idx = py * Stage.W + px;
                    if (w > 0.1f && (lit == chase || lit == chase - 1)) s.out[idx] = Pal.mix(s.out[idx], lit == chase ? Pal.MINT_HI : Pal.MINT, 0.8f * w);
                    else if (glow) s.out[idx] = Pal.mix(s.out[idx], Pal.MINT, 0.55f);
                    else {
                        s.out[idx] = Pal.mix(s.out[idx], Pal.VOID, 0.55f);                     // the cut
                        if (px + 1 < Stage.W) s.out[idx + 1] = Pal.mix(s.out[idx + 1], s.gStone[6], 0.35f);  // its lit edge
                    }
                }
            if (rosetta) s.small(String.valueOf(ch), x - 6, y, 1, Pal.FAINT);
            lit++;
            y += 7;
        }
    }

    /** Days you've come home, scratched into a stone by the water in fives. */
    void tally(Stage s, int days) {
        if (days <= 0) return;
        int x = 6, y = 144;
        for (int d = 0; d < Math.min(days, 60); d++) {
            int group = d / 5, k = d % 5;
            int gx = x + group * 7;
            if (k < 4) for (int j = 0; j < 4; j++) mark(s, gx + k, y + j);
            else for (int j = 0; j < 4; j++) mark(s, gx - 1 + j, y + 3 - j);         // the fifth, across
        }
    }

    static void mark(Stage s, int x, int y) {
        if (x < 0 || x >= Stage.W || y < 0 || y >= Stage.H || s.near[y * Stage.W + x] == 0) return;
        int i = y * Stage.W + x;
        s.out[i] = Pal.mix(s.out[i], Pal.VOID, 0.5f);
    }

    /** A cloaked figure with a lantern at the edge of the opening, looking in. */
    void keeper(Stage s, Life l) {
        if (keeperT < 0) return;
        float a = Math.min(1f, Math.min(keeperT, 14f - keeperT) / 1.5f);    // fades in and out
        String[] art = {
                "..##...",
                ".####..",
                ".#..#..",
                ".####.L",
                "######|",
                "######|",
                ".####.|",
                ".####..",
                ".#..#.."};
        int x0 = Life.SKY_X0 + 9, y0 = 3;
        for (int r = 0; r < art.length; r++)
            for (int c = 0; c < art[r].length(); c++) {
                char ch = art[r].charAt(c);
                if (ch == '.') continue;
                if (Pal.BAYER[((r & 3) << 2) | (c & 3)] + 0.5f > a) continue;
                int col = ch == 'L' ? Pal.AMBER : ch == '|' ? 0xFF2A2420 : 0xFF0A0A14;
                l.skyPx(s, x0 + c, y0 + r, col);
            }
    }

    /** The Keeper's lantern reaches the stone; runs before the stone is lit. */
    void lights(Stage s) {
        if (keeperT >= 0) {
            float a = Math.min(1f, Math.min(keeperT, 14f - keeperT) / 1.5f);
            float[] zu = Vista.keeperAt(keeperT);                    // the lantern, wherever they are
            float[] at = s.vista.proj(zu[0], zu[1], 3f);
            s.light(at[0] - s.farOx + 3, at[1] - s.farOy, 34, 0.45f * a, true, zu[0]);
        }
    }
}
