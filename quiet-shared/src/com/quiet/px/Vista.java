package com.quiet.px;

/**
 * What you see through the arch: an old road running on underground, long abandoned and taken
 * back by green — a barrel vault on ribbed arches, flagstones with grass in every joint, a channel
 * of water down the middle that runs out into our stream, ferns along
 * the walls, roots hanging from the vault, breaks in the vault where daylight gets in. Side passages lead off left and right — one lantern-lit, one aglow with mushrooms, one just
 * dark — and things live here: a fox or a hare crossing between them, butterflies in the light by
 * day, fireflies drifting down the road at night, bats at dusk. A few arches along, the dark takes
 * it. Somewhere at the very end, an ember of a doorway.
 *
 * It is ray-marched once (in far-wall coordinates, so it moves with the far parallax) whenever
 * the hour or the weather changes; per frame only the ember, the lamps and the dust in the light
 * are animated.
 */
final class Vista {
    static final int FW = Stage.FW, FH = Stage.FH, P = Stage.PAD / 2;
    final int[] img = new int[FW * FH];
    boolean painted;

    // The road, in its own units. Camera looks straight down it, from low down: our eye is at the
    // stream, level with Pip, so the horizon sits just above the bank and the floor lies flat (a
    // higher eye made the floor climb the screen like a ramp beside the straight wall).
    static final float VX = 236 + P, VY = 126 + P, F = 52f, CAMH = 4.2f, HALF = 11f, SPRING = 9.5f,
            SPACING = 7f, RIB = 1.1f, RIBIN = 0.9f, ROAD = 6f, ZMAX = 44f, DARK_AT = 34f, CHANNEL = 0.9f;
    // Everything you can see past the broken wall.
    static final int X0 = 96 + P, X1 = FW - 1, Y0 = 0, Y1 = Stage.WATER_Y + P;
    // Where daylight from the collapse lands on the road: z, lateral, radius.
    static final float[][] HOLES = {{6f, 0f, 2.6f}};
    static final float TREE_Z = 16, TREE_X = 5;
    static final int[] BROKEN = {3};                  // a rib that fell long ago
    // Side passages: z from, z to, side (-1 left, 1 right), what's down there (0 dark, 1 lanterns, 2 mushrooms).
    static final float[][] PASSAGES = {{10.4f, 12.9f, -1, 1}, {19.6f, 22f, 1, 2}, {26.8f, 28.6f, -1, 0}};

    // Life on the road.
    final java.util.Random rnd = new java.util.Random(13);
    float crossIn = 12f, crossT = -1, crossZ, crossFrom, crossTo;
    int crossKind;                                    // 0 fox, 1 hare, 2 hedgehog
    final float[][] flutter = new float[6][4];        // z, u, h, phase
    float batT = -1, batIn = 20f;
    float leafZ = -1, leafIn = 20f, leafPh;           // a leaf riding the channel out to our stream

    // Palette of the hour: haze, light, stone, stoneD, stoneL, moss, mossD, leaf, leafD, bark, dark, lit.
    static final int[] DAY = {0xFF8E9CB0, 0xFFFFF0C8, 0xFF58607A, 0xFF2A3048, 0xFF7C869E, 0xFF4E7E4C, 0xFF2A4A36,
            0xFF62A058, 0xFF2E5C38, 0xFF3E3030, 0xFF121624, 0xFFFFD890};
    static final int[] GOLD = {0xFFB87A5C, 0xFFFFC878, 0xFF664644, 0xFF322230, 0xFF96685A, 0xFF5E6C3C, 0xFF30382A,
            0xFF80903E, 0xFF3A4A2A, 0xFF40261F, 0xFF1A1018, 0xFFFFE0A0};
    static final int[] BLUE = {0xFF4A4478, 0xFFE8B0D0, 0xFF2E2C50, 0xFF161430, 0xFF46446E, 0xFF2A4A48, 0xFF162A2A,
            0xFF3A6A50, 0xFF1A3A30, 0xFF241C2A, 0xFF0A0A16, 0xFFFFC890};
    static final int[] NIGHT = {0xFF182440, 0xFF6AE8D0, 0xFF1E2640, 0xFF0C1022, 0xFF2E3A5A, 0xFF1A3A34, 0xFF0E2020,
            0xFF1E4A3A, 0xFF0E2A22, 0xFF1A1620, 0xFF05060C, 0xFFFFB44A};
    final int[] C = new int[12];
    int haze, light, stone, stoneD, stoneL, moss, mossD, leaf, leafD, bark, dark, lit, fogCol;
    float day;
    static final int LANTERN = 0xFFFFB45A, SHROOM = 0xFF4EF0C8;

    Vista() {
        for (float[] f : flutter) {
            f[0] = 8 + rnd.nextFloat() * 20;
            f[1] = (rnd.nextFloat() - 0.5f) * 8;
            f[2] = 2 + rnd.nextFloat() * 5;
            f[3] = rnd.nextFloat() * 6.28f;
        }
    }

    /** Is this point on a wall actually the mouth of a side passage? Returns its kind, or -1. */
    static int passage(float z, float u, float h) {
        for (float[] p : PASSAGES) {
            if (z < p[0] || z > p[1] || Math.signum(u) != p[2]) continue;
            float f = (z - p[0]) / (p[1] - p[0]);
            if (h < 5.2f + 1.6f * (float) Math.sin(f * Math.PI)) return (int) p[3];
        }
        return -1;
    }

    static int hash(int a, int b, int c) {
        int h = 0x811C9DC5;
        h = (h ^ a) * 16777619;
        h = (h ^ b) * 16777619;
        h = (h ^ c) * 16777619;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    static float hs(int a, int b, int c) {
        return (hash(a, b, c) % 10007) / 10007f;
    }

    static float bay(int x, int y) {
        return Pal.BAYER[((y & 3) << 2) | (x & 3)];
    }

    static int mix(int a, int b, float t) {
        return Pal.mix(a, b, Math.max(0f, Math.min(1f, t)));
    }

    static boolean broken(int idx) {
        for (int b : BROKEN) if (b == idx) return true;
        return false;
    }

    static float vault(float u, boolean rib) {
        float hw = HALF - (rib ? RIBIN : 0);
        if (Math.abs(u) >= hw) return -1;
        return SPRING + (float) Math.sqrt(hw * hw - u * u);
    }

    static float fog(float z) {
        return Math.min(1f, (float) Math.pow(z / DARK_AT, 1.15));
    }

    void palette(Stage s) {
        Life l = s.life;
        float wd = l.day * (1 - l.golden), wg = l.golden, wb = l.blue, wn = l.dark * (1 - l.blue);
        float sum = wd + wg + wb + wn;
        if (sum < 0.01f) {
            wn = 1;
            sum = 1;
        }
        float over = Weather.overcast(), sat = 1f - over * 0.5f;
        int[][] sets = {DAY, GOLD, BLUE, NIGHT};
        float[] w = {wd / sum, wg / sum, wb / sum, wn / sum};
        for (int i = 0; i < C.length; i++) {
            float r = 0, g = 0, b = 0;
            for (int k = 0; k < 4; k++) {
                int c = sets[k][i];
                r += ((c >> 16) & 255) * w[k];
                g += ((c >> 8) & 255) * w[k];
                b += (c & 255) * w[k];
            }
            int c = 0xFF000000 | (Stage.clamp(r) << 16) | (Stage.clamp(g) << 8) | Stage.clamp(b);
            C[i] = i == 11 ? c : Stage.gradeColor(c, new float[]{1, 1, 1}, sat, 0, 0);
        }
        // Built from the world's own graded ramps, a step darker than the near wall, so the road
        // reads as more of the same cave rather than a picture set into it.
        int[] f = s.gFar, st = s.gStone;
        stone = f[4]; stoneL = f[5]; stoneD = f[2]; dark = f[1]; haze = f[3];
        float lift = 0.35f * l.day;                                        // daylight reaches in a little
        stone = mix(stone, st[4], lift);
        stoneL = mix(stoneL, st[5], lift);
        int[] greens = {Pal.MOSS_DK, Pal.MOSS, Pal.MOSS_HI};
        float g = 0.45f + 0.4f * l.day;                                    // greens sit in the same light
        mossD = mix(dark, greens[0], g + 0.2f);
        moss = mix(dark, greens[1], g);
        leafD = mix(dark, greens[1], g + 0.1f);
        leaf = mix(dark, greens[2], g * 0.85f);
        bark = mix(dark, 0xFF3A2A24, g);
        light = mix(stoneL, C[1], 0.55f);                                 // the shafts, softened
        lit = C[11];
        if (over > 0.5f) light = mix(light, haze, (over - 0.5f));          // grey days, weaker shafts
        fogCol = mix(Pal.VOID, dark, 0.6f);
        if (s.doorOpen) fogCol = mix(fogCol, Pal.EMBER, 0.12f);            // warmth from the far end, once it's open
    }

    // Per pixel, for the light that moves (see relight): how far in the surface is (0: nothing
    // there), how much light it gives back, the light the road itself gives it, and its ramp
    // (0 stone, 1 warm, 2 cool).
    final float[] zAt = new float[FW * FH], reflAt = new float[FW * FH], baseL = new float[FW * FH];
    final byte[] kindAt = new byte[FW * FH];
    // What the last march() hit, and what the last surface() was made of.
    float mz, mu, mh;
    boolean mrib;
    float lastRefl;
    int lastKind;

    /** Ray-march the road for this hour and weather. */
    void paint(Stage s) {
        painted = true;
        palette(s);
        day = s.life.day;
        java.util.Arrays.fill(img, Pal.VOID);
        java.util.Arrays.fill(zAt, 0f);
        java.util.Arrays.fill(reflAt, 0f);
        for (int y = Y0; y < Y1; y++)
            for (int x = X0; x <= X1; x++) img[y * FW + x] = shade(s, x, y);
    }

    /**
     * Follows one ray down the road and says what it met: 0 nothing (the dark), 1 floor, 2 wall,
     * 3 vault. Mirrored, it's the ray bounced off the water in the channel, climbing from the floor.
     */
    int march(float dx, float dy, float z, boolean mirrored) {
        while (z < ZMAX) {
            float u = dx * z - bend(z);                // across the road, measured from its (curving) middle
            float h = mirrored ? dy * z - CAMH : CAMH - dy * z;
            float seg = z / SPACING;
            int idx = (int) seg;
            boolean rib = idx >= 1 && (seg - idx) < RIB / SPACING && !broken(idx);
            mz = z;
            mu = u;
            mh = h;
            mrib = rib;
            if (h <= 0 && !mirrored) return 1;
            float top = vault(u, rib);
            if (top < 0) return 2;
            if (h >= top) return 3;
            z += z < 20 ? 0.25f : 0.5f;
        }
        return 0;
    }

    /**
     * Shaded exactly the way the near wall is: a reflectance for the surface, the light that reaches
     * it (in from the arch mouth and falling away, pools under the breaks in the vault, warm spill
     * from the lantern passage, cool from the mushrooms), then a step on the world's own graded ramps
     * with the same ordered dither. Past a few arches nothing reaches, and it's simply dark.
     *
     * Down the middle runs a channel, a hand deep: the water that feeds our stream. Water lies
     * level, so it's what tells the eye the road is flat; it shows the vault and the light from round
     * the bend upside down in it.
     */
    int shade(Stage s, int x, int y) {
        float d = bay(x, y);
        float dx = (x - VX) / F, dy = (y - VY) / F;
        int hit = march(dx, dy, 1.5f, false);
        if (hit == 0) return Pal.VOID;
        int fi = y * FW + x;
        float z = mz, u = mu, h = mh;
        boolean rib = mrib;
        if (hit == 1 && Math.abs(u) < CHANNEL && z > 4f) {
            float L = 0.02f;
            int kind = 2;
            int bounce = march(dx, dy, z, true);
            if (bounce != 0) {
                L += surface(s, bounce, mz, mu, mh, mrib, d) * 0.6f;
                kind = lastKind == 1 ? 1 : 2;               // stays cool, unless it's lamplight it's showing
            }
            if (((int) (z * 5f) + (x >> 1)) % 6 == 0) L *= 0.65f;   // a ripple now and then
            return store(s, fi, z, 0.3f, L, kind, d);
        }
        float L = surface(s, hit, z, u, h, rib, d);
        return store(s, fi, z, lastRefl, L, lastKind, d);
    }

    /** How much light a surface gives back to us (sets lastRefl and lastKind). */
    float surface(Stage s, int hit, float z, float u, float h, boolean rib, float d) {
        float seg = z / SPACING, fs = seg - (int) seg;
        float refl;
        boolean green = false;
        int passage = hit == 2 ? passage(z, u, h) : -1;
        if (hit == 1) {
            float au = Math.abs(u);
            if (au < CHANNEL + 0.3f) {
                refl = 0.8f;                                                           // the channel's dressed kerb
            } else if (au < ROAD) {
                int row = (int) Math.floor(z / 1.6f);
                float uu = u + (row % 2) * 0.9f;
                int col = (int) Math.floor(uu / 1.8f);
                float fz = z / 1.6f - row, fu = uu / 1.8f - col;
                float jw = 0.07f * (1 + z / 25);
                boolean joint = fz < jw || fu < jw * 1.2f;
                refl = joint ? 0.35f : 0.62f + hs(row, col, 1) * 0.25f;
                green = (joint && hs(row, col, 7) < 0.5f) || hs(row, col, 3) < 0.15f;   // grass in the joints, mossy stones
                if (hs(row, col, 4) < 0.04f) refl = 0.2f;
            } else {
                refl = 0.5f + hs((int) (z * 3), (int) (u * 3), 2) * 0.35f;
                green = true;                                                          // ferns and grass at the edges
            }
        } else if (passage >= 0) {
            refl = 0.15f;
        } else {
            if (rib) refl = fs < (RIB / SPACING) * 0.5f ? 0.85f : 0.7f;
            else {
                int course = (int) Math.floor(h / 1.1f);
                float zz = z + (course % 2) * 1.5f;
                int block = (int) Math.floor(zz / 3f);
                refl = 0.55f + hs(course, block, 5) * 0.25f;
                if (h / 1.1f - course < 0.12f || zz / 3f - block < 0.06f) refl = 0.3f;    // mortar
            }
            if (h < 2.2f && hs((int) (z * 2), (int) (h * 3), 6) < 0.6f) green = true;      // moss low on the walls
            int sz = (int) (z * 2.2f);
            if (hit == 3 && hs(sz, 7, 0) < 0.10f && hs(sz, (int) (h * 2), 8) < 0.85f) refl = 0.22f;   // roots
            if (hit == 2 && hs(sz, 9, 0) < 0.14f && h > 2 && hs(sz, (int) (h * 3), 10) < 0.8f) green = true;   // ivy
        }
        // The light that gets this far in.
        float mouth = (0.42f + 0.5f * day) * (float) Math.exp(-z / 10f);
        float w = mouth * 0.5f, k = mouth * 0.5f;
        for (float[] o : HOLES) {
            float pz = o[0] + 3, pu = o[1] + 1.5f;
            float dist = (float) Math.sqrt((z - pz) * (z - pz) * 0.6f + (u - pu) * (u - pu));
            float pool = Math.max(0, 1 - dist / (o[2] * 2.2f)) * (0.55f * day + 0.05f);
            w += pool * 0.5f;
            k += pool * 0.5f;
        }
        for (float[] p : PASSAGES) {
            if (p[3] == 0) continue;
            float along = z < p[0] ? p[0] - z : z > p[1] ? z - p[1] : 0;
            float side = Math.abs(u - p[2] * HALF);
            float spill = Math.max(0, 1 - along / 2.5f - side / 9f) * 0.45f;
            if (passage == (int) p[3] && z >= p[0] && z <= p[1]) spill = 0.5f;
            if (p[3] == 1) w += spill;
            else k += spill;
        }
        // Light from round the bend: whatever is further on, unseen (brighter once the door is open).
        float corner = Math.max(0, 1 - Math.abs(z - 23) / 6f) * (s.doorOpen ? 0.55f : 0.16f + 0.1f * s.life.dark);
        w += corner;
        lastRefl = refl;
        if (green) lastKind = 2;
        else {
            float bias = (w - k) / (w + k + 0.05f) + d * 0.1f;
            lastKind = bias > 0.2f ? 1 : bias < -0.2f ? 2 : 0;
        }
        return refl * (w + k);
    }

    int store(Stage s, int fi, float z, float refl, float L, int kind, float d) {
        zAt[fi] = z;
        reflAt[fi] = refl;
        baseL[fi] = L;
        kindAt[fi] = (byte) kind;
        return color(s, L, kind, d);
    }

    static int color(Stage s, float L, int kind, float d) {
        int lv = (int) (L * 7f + d + 0.5f);
        if (lv <= 0) return Pal.VOID;
        if (lv > 6) lv = 6;
        return (kind == 1 ? s.gWarm : kind == 2 ? s.gCool : s.gStone)[lv];
    }

    /**
     * A road pixel with lamplight on it this frame (w and k already faded with depth by
     * Stage.light), or 0 when there's nothing there to light.
     */
    int relight(Stage s, int fi, float w, float k, float d) {
        float r = reflAt[fi];
        if (r <= 0) return 0;
        int kind = kindAt[fi];
        if (kind == 0 && w > k * 1.5f) kind = 1;
        return color(s, baseL[fi] + r * (w + k), kind, d);
    }

    /**
     * The road bends away to the right a few arches in, so its outer wall closes the view: you never
     * see where it goes, only light coming round the corner.
     */
    static float bend(float z) {
        return z > 11 ? 0.06f * (z - 11) * (z - 11) : 0;
    }

    /**
     * Where the Keeper is: out of the lantern-lit passage on the left, across the road, and still
     * at the bend; then back the way they came as they fade.
     */
    static float[] keeperAt(float t) {
        float[] p = PASSAGES[0];
        float pz = (p[0] + p[1]) / 2, pu = p[2] * (HALF - 0.8f);
        float k = t < 4f ? t / 4f : t > 11f ? 1 - (t - 11f) / 3f : 1f;
        k = Math.max(0, Math.min(1, k));
        k = k * k * (3 - 2 * k);
        return new float[]{pz + (17f - pz) * k, pu + (1.5f - pu) * k};
    }

    float[] proj(float z, float u, float h) {
        return new float[]{VX + F * (u + bend(z)) / z, VY + F * (CAMH - h) / z};
    }

    static final String[][] CRITTERS = {
            {"..##.....", ".####.##.", "#####.#..", ".#..#...."},        // fox (tail first, facing right)
            {"....#..", "....#..", ".####..", "#####..", ".#..#.."},     // hare
            {".####..", "######.", ".#..#.."}};                           // hedgehog

    /** Small lives along the road. */
    void life(Stage s, int ox, int oy) {
        float dt = 1f / 12f;
        Life l = s.life;
        // Something crosses from one side to the other now and then.
        crossIn -= dt;
        if (crossIn <= 0 && crossT < 0) {
            float[] p = PASSAGES[rnd.nextInt(2)];
            crossZ = (p[0] + p[1]) / 2;
            crossFrom = p[2] * (HALF - 0.6f);
            crossTo = -crossFrom * 0.85f;
            crossKind = l.dark > 0.5f ? 2 : rnd.nextInt(2);
            crossT = 0;
            crossIn = 25f + rnd.nextFloat() * 40f;
        }
        if (crossT >= 0) {
            if (!(s.partyOn() && crossT >= 0.5f)) crossT += dt * (crossKind == 2 ? 0.08f : 0.16f);   // at a party it stops halfway
            if (crossT >= 1) crossT = -1;
            else {
                float u = crossFrom + (crossTo - crossFrom) * crossT;
                boolean hop = (crossKind == 1 && ((int) (s.t * 6) & 1) == 0) || (s.partyOn() && s.beatPhase() < 0.3f);
                float[] p = proj(crossZ, u, hop ? 0.3f : 0);
                String[] art = CRITTERS[crossKind];
                int dir = crossTo > crossFrom ? 1 : -1, w = art[0].length();
                if (s.partyOn() && crossT >= 0.5f && (s.beats & 2) == 0) dir = -dir;      // turning about
                int col = mix(0xFF0A0A12, crossKind == 0 ? 0xFF6A3418 : 0xFF3A342E, day * 0.6f);   // silhouettes, like everything this far in
                for (int r = 0; r < art.length; r++)
                    for (int c = 0; c < w; c++)
                        if (art[r].charAt(c) == '#')
                            s.farPx(Math.round(p[0]) - ox + (dir > 0 ? c - w / 2 : w / 2 - c), Math.round(p[1]) - oy - art.length + r + 1, col);
            }
        }
        // Butterflies in the light by day; fireflies drifting down the road at night.
        boolean night = l.dark > 0.5f;
        int shown = night ? 4 : 2;                          // a couple of real things, not confetti
        for (int fi = 0; fi < flutter.length; fi++) {
            float[] f = flutter[fi];
            f[3] += dt * 2;
            if (fi >= shown) continue;
            f[0] += (float) Math.sin(f[3] * 0.3f) * 0.03f - (night ? 0.02f : 0);
            f[1] += (float) Math.cos(f[3] * 0.7f) * 0.05f;
            f[2] += (float) Math.sin(f[3] * 1.3f) * 0.04f;
            if (f[0] < 5 || f[0] > 30) f[0] = night ? 30 : 8 + rnd.nextFloat() * 18;
            f[1] = Math.max(-HALF + 1, Math.min(HALF - 1, f[1]));
            f[2] = Math.max(1, Math.min(SPRING + 2, f[2]));
            float[] p = proj(f[0], f[1], f[2]);
            int x = Math.round(p[0]) - ox, y = Math.round(p[1]) - oy;
            if (night) {
                if (((int) (s.t * 1.7f + f[3]) & 3) != 0) s.farPx(x, y, mix(Pal.MINT_HI, fogCol, fog(f[0]) * 0.6f));
            } else if (l.day > 0.3f) {
                boolean open = ((int) (s.t * 8 + f[3]) & 1) == 0;
                int c = mix(f[3] % 2 > 1 ? 0xFFFFB44A : 0xFFE89AD8, fogCol, fog(f[0]) * 0.6f);
                s.farPx(x, y, c);
                if (open) s.farPx(x + 1, y - 1, c);
            }
        }
        // Now and then a leaf comes down the channel out of the dark, turning, and on toward us.
        leafIn -= dt;
        if (leafIn <= 0 && leafZ < 0) {
            leafZ = 24f;
            leafPh = rnd.nextFloat() * 6.28f;
            leafIn = s.debugRide ? 4f : 35f + rnd.nextFloat() * 60f;
        }
        if (leafZ >= 0) {
            leafZ -= dt * (0.6f + 0.25f * (float) Math.sin(s.t * 0.7f + leafPh));
            if (leafZ < 5f) {
                leafZ = -1;
                if (s.life.leafX < 0) {                          // and on into our stream
                    s.life.leafX = s.channelX();
                    s.life.leafRidden = false;
                    s.life.leafCol = s.eggs.month >= 9 && s.eggs.month <= 11 ? 0xFFC8782E : 0xFF7EB04E;
                }
            }
            else {
                float[] p = proj(leafZ, (float) Math.sin(s.t * 0.9f + leafPh) * (CHANNEL * 0.5f), 0.05f);
                int x = Math.round(p[0]) - ox, y = Math.round(p[1]) - oy;
                int c = mix(s.eggs.month >= 9 && s.eggs.month <= 11 ? 0xFFC8782E : 0xFF7EB04E, fogCol, fog(leafZ) * 0.7f);
                s.farPx(x, y, c);
                if (leafZ < 12 && ((int) (s.t * 2 + leafPh) & 1) == 0) s.farPx(x + 1, y, c);   // turning as it nears
            }
        }
        // Bats at dusk, out of the dark and over our heads.
        if (l.golden > 0.3f || l.blue > 0.3f) {
            batIn -= dt;
            if (batIn <= 0 && batT < 0) {
                batT = 0;
                batIn = 30f + rnd.nextFloat() * 40f;
            }
        }
        if (batT >= 0) {
            batT += dt * 0.25f;
            if (batT > 1) batT = -1;
            else
                for (int k = 0; k < 3; k++) {
                    float z = ZMAX * 0.8f * (1 - batT) + 2 + k * 2, u = (float) Math.sin(batT * 9 + k * 2) * 3, h = SPRING + 1 + (float) Math.cos(batT * 7 + k) * 1.5f;
                    float[] p = proj(z, u, h);
                    int x = Math.round(p[0]) - ox, y = Math.round(p[1]) - oy;
                    boolean up = ((int) (s.t * 12 + k) & 1) == 0;
                    int c = mix(0xFF14101C, fogCol, fog(z) * 0.5f);
                    s.farPx(x, y, c);
                    s.farPx(x - 1, y + (up ? -1 : 0), c);
                    s.farPx(x + 1, y + (up ? -1 : 0), c);
                }
        }
    }

    void put(int x, int y, int c) {
        if (x >= 0 && x < FW && y >= 0 && y < Y1) img[y * FW + x] = c;
    }

    /** The Keeper at the bend, the wall lamps (one relit for each week), life, dust in the light. */
    void animate(Stage s) {
        int ox = s.farOx, oy = s.farOy;
        boolean open = s.doorOpen;
        // Sometimes, on a full-moon night, someone stands at the bend with a lantern.
        if (s.lore.keeperT >= 0) {
            float a = Math.min(1f, Math.min(s.lore.keeperT, 14f - s.lore.keeperT) / 1.5f);
            String[] art = {".#.", "###", "###", "###L", "###|", ".#.", "#.#"};
            float[] zu = keeperAt(s.lore.keeperT);
            float[] at = proj(zu[0], zu[1], 0);
            int kx = Math.round(at[0]) - ox, ky = Math.round(at[1]) - oy - art.length + 1;
            for (int r = 0; r < art.length; r++)
                for (int c = 0; c < art[r].length(); c++) {
                    char ch = art[r].charAt(c);
                    if (ch == '.' || Pal.BAYER[((r & 3) << 2) | (c & 3)] + 0.5f > a) continue;
                    s.farPx(kx + c, ky + r, ch == 'L' ? Pal.AMBER : ch == '|' ? 0xFF2A2420 : 0xFF0A0A12);
                }
        }
        // Lamps on the ribs, alternating sides, receding into the dark.
        int lamps = open || s.wonder() > 0.5f ? 4 : Math.min(4, s.days / 7);
        for (int k = 0; k < lamps; k++) {
            float z = SPACING * (k + 1) + 0.3f, u = (k % 2 == 0 ? -1 : 1) * (HALF - RIBIN - 0.2f);
            float[] p = proj(z, u, 5.5f);
            boolean f = ((int) (s.t * 5 + k * 3) % 7) != 0;
            int c = mix(f ? Pal.AMBER : Pal.EMBER, fogCol, fog(z) * 0.6f);
            c = mix(c, Pal.TEXT, s.pulse(k & 1) * 0.5f + s.midE * s.wonder() * 0.4f);   // in time, trading beats
            s.farPx(Math.round(p[0]) - ox, Math.round(p[1]) - oy, c);
            if (k < 2) s.farPx(Math.round(p[0]) - ox, Math.round(p[1]) - oy - 1, mix(c, Pal.TEXT, 0.3f));
        }
        life(s, ox, oy);
        // Dust drifting down through the shafts.
        for (int i = 0; i < 4; i++) {
            float[] o = HOLES[i % HOLES.length];
            float f = ((s.t * 0.05f + i * 0.137f) % 1f);
            float[] a = proj(o[0], o[1], SPRING + 6), e = proj(o[0] + 3, o[1] + 1.5f, 0);
            int x = Math.round(a[0] + (e[0] - a[0]) * f + (float) Math.sin(s.t + i) * 1.5f) - ox,
                    y = Math.round(a[1] + (e[1] - a[1]) * f) - oy;
            if (s.open(x, y)) s.out[y * Stage.W + x] = mix(s.out[y * Stage.W + x], light, 0.7f);
        }
    }
}
