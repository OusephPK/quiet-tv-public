package com.quiet.px;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Random;

/**
 * The old road (World v2; v1, the well with the arch, is saved in versions/v1). A 320×180 world
 * drawn in software, then scaled ×6 by the GPU with no smoothing. You stand at a wall of big dressed
 * stone (the words live on it); to the right it breaks off raggedly onto the overgrown road running
 * away into the dark (Vista), its vault overhead broken open to the real sky. See WORLD.md.
 *
 * Borrowing from how Animal Well builds a frame, but cheap enough for a TV chip:
 *  - Darkness first. Stone has no colour of its own, only reflectance; the lights that reach it
 *    pick a step on a palette ramp (warm for lamplight, cool for glow, fog-blue for the far wall).
 *  - Ordered (Bayer) dithering between ramp steps instead of gradients: the palette threshold.
 *  - Layers: a near wall with an arch, a far tunnel seen through it (with its own, deeper arch),
 *    and foreground roots, each shifting by a different amount as you move through the list.
 *  - A cheap fluid: a 1-D spring surface for the pool, which reflects everything above it.
 *  - Life: fireflies, vines, drips, a snail, a frog, a fish, a moth, eyes in the dark.
 *    Some of it happens every few seconds, some once in a long while. Nothing is a stored image.
 *  - Real time of day: the beam from the crack above is pale in the morning, amber at dusk,
 *    moonlit at night (when more fireflies come out).
 */
public final class Stage {
    public static final int W = 320, H = 180;
    public static final int WATER_Y = 166;

    // Kept from the well for the creatures that still use them: the middle of the opening, its
    // half-width, and where the old arch sprang (the ledge and the road's mouth line up with them).
    static final int ARCH_CX = 244, ARCH_HW = 62, SPRING = 74, RISE = 46;
    /** Where the near wall breaks off, by row: past this x you're looking down the road. */
    static final int JAMB = 148, LEDGE_X = 228, LEDGE_Y = 20;
    static final int PAD = 24, FW = W + PAD, FH = H + PAD;
    static final int INNER_CX = ARCH_CX + PAD / 2, INNER_HW = 22, INNER_SPRING = 118 + PAD / 2, INNER_RISE = 22;

    public final int[] out = new int[W * H];
    final byte[] near = new byte[W * H];       // near-wall reflectance, 0 = open (see the tunnel)
    final byte[] nearMoss = new byte[W * H];
    final byte[] sky = new byte[W * H];         // 1 = the opening to the sky
    final Life life;
    final byte[] far = new byte[FW * FH];      // far-wall reflectance
    final byte[] carveA = new byte[FW * FH], carveB = new byte[FW * FH];
    float carveMix = 1f;
    final float[] warm = new float[W * H], cool = new float[W * H];       // lights that move, rebuilt each frame
    final float[] sWarm = new float[W * H], sCool = new float[W * H];     // lights that don't, baked when the hour changes
    final float[] roadW = new float[W * H], roadK = new float[W * H];     // this frame's lamplight on the road, faded with depth
    boolean baking;
    float sGain = 1f;
    final float[] amb = new float[W * H];
    final float[] shaft = new float[W * H];
    final float[] shaftU = new float[W * H];       // 0..1 across the beam, for its rays
    final float[] rays = new float[65];            // the rays' strength across the beam, this frame
    int shaftX0 = W, shaftX1 = 0;

    public final Random rnd = new Random(7);
    public float t;
    public final Firefly mote = new Firefly();
    public Sound sound;
    /** Music is playing somewhere: the fireflies dance in two alternating groups. */
    public boolean music;
    /** Pip the dog keeps to this stretch of the bank, so he stays clear of each screen's words. */
    public int dogMin = 60, dogMax = 186;
    /** False on screens whose words reach down to the bank: Pip keeps living, just out of sight. */
    public boolean dogOut = true;
    final Dog dog = new Dog();
    final Lore lore = new Lore();
    final Vista vista = new Vista();
    final Eggs eggs = new Eggs();
    final Seasons seasons = new Seasons();
    /** What the well remembers (see Lore). The home screen keeps these for the world. */
    public boolean doorOpen, rosetta;
    public Runnable onDoorOpened;
    public int days;
    /** Seconds into the well waking up (the TV turning on); -1 when it's awake. */
    float wakeT = -1;
    // Very rarely the world glitches for a moment, and Pip startles at it. (debug.quiet.glitch = soon)
    float glitchIn = 1500f, glitchT = -1;
    int glitchSeed;

    // The beat. A steady clock at the music's tempo; Groove (when it can hear) sets the tempo and
    // nudges the phase onto real kicks. Without it, a relaxed 100 BPM.
    public volatile float bpm;
    public volatile long hitAt;
    long hitSeen;
    float beatPh;
    int beats;
    boolean beatNow;
    // The world dancing to it, Mario Wonder style: whenever music plays, everything joins in (groove
    // eases in over a couple of seconds, won a little behind it), and when it stops or pauses it all
    // settles back, slowly. A swell in the song (Groove hears it) makes the mushrooms burst into bloom.
    public volatile float loud, bass, mid, high;          // from Groove: the song right now
    public volatile float toneBass, toneMid, toneHigh;    // ...and its balance, over the last few seconds
    public volatile String genre = "";                    // Apple's genres for the song playing, "A|B" (Party)
    public volatile boolean partyAllowed = true;          // Quiet Settings → MUSIC PARTY
    public volatile float windDown;                       // 0..1: a music sleep timer running out (the world dims)
    /** What Quiet's voice is doing (0 nothing, 1 listening, 2 thinking, 3 done): Pip looks, Mote listens. */
    public volatile int voice;
    float voiceT;

    public void voice(int state) {
        if (state == 1 && voice != 1) mote.cheer();
        if (state == 3 && voice != 3) mote.cheer();
        voice = state;
        voiceT = 0;
    }
    final Party party = new Party();
    public volatile long swellAt;
    float bassE, midE, highE, loudE;                     // the same, smoothed for the eye
    long swellSeen;
    boolean debugMusic, debugRide;
    float groove, won;
    static final int[][] MUSH = {{158, 5, 5, 0}, {163, 8, 7, 0}, {169, 4, 3, 1}, {270, 6, 5, 0}, {277, 9, 7, 0},
            {285, 5, 5, 0}, {291, 3, 3, 1}, {262, 4, 3, 0}, {236, 3, 3, 1}, {242, 5, 5, 0}};
    float lureIn = 45f, lureFor;

    /** The world makes sounds; whoever is listening decides what they sound like. */
    public interface Sound {
        void plip(float pitch);     // a drop into water, 0..1 = low..high
        void croak();
        void chime();
        default void chirp() {}     // a bird on the keystone
        default void squeak() {}    // bats leaving the tunnel
        default void rain(float amount) {}   // 0..1, how hard it's raining (a soft bed, not drops)
        default void rainDrop() {}  // the odd drop you can pick out of the rain
        default void thunder() {}   // a distant rumble, after the lightning
    }

    // Camera: list position nudges the layers apart.
    float cam, camTarget;
    int farOx, farOy, rootOy;

    // Water surface.
    final float[] wh = new float[W], wv = new float[W];

    // Fireflies.
    static final int FLIES = 14;
    final float[] fx = new float[FLIES], fy = new float[FLIES], fvx = new float[FLIES],
            fvy = new float[FLIES], fph = new float[FLIES];
    float syncIn = 70f, syncFor;

    // Vines hanging from the arch.
    // Vines and roots: from the rim of the collapse, the underside of the ledge, the top of the broken wall.
    final int[] vineX = {200, 214, 252, 266, 284, 301, 313};
    static final int[] VINE_BASE = {22, 34, 16, 28, 30, 44, 24};
    final int[] vineLen = VINE_BASE.clone();
    int grownFor, drapeTop;
    final float[] vinePh = {0f, 1.7f, 3.1f, 4.4f, 5.2f, 2.3f, 0.8f};
    final int[] vineTop = new int[vineX.length];

    // Small transient things. x, y, vx, vy, life, kind.
    static final int MAXP = 96;
    static final int P_DUST = 0, P_DRIP = 1, P_ZED = 2, P_SPORE = 3, P_MOTE = 4, P_DROPLET = 5, P_PEBBLE = 6, P_TRAIL = 7, P_RAIN = 8, P_NOTE = 9;
    final float[][] p = new float[MAXP][6];
    float dripIn = 3f, moteIn = 0f;

    // Occasional happenings.
    float fishIn = 35f, fishT = -1, fishX;
    float frogIn = 60f, frogT = -1, frogX, frogSit;
    boolean frogBrave;                                   // this time it won't be scared off by Pip
    boolean frogRides;
    boolean frogAfloat;                // this visit, the frog is out on the stream (on the leaf), not on the bank
    /** The drift lane: out in the water, below the bank's reflection line, where the leaf floats. */
    public static final int LANE = WATER_Y + 6;
    float eyesIn = 50f, eyesT = -1;
    int eyesX, eyesY;
    float pebbleIn = 45f;
    float bloomIn = 30f, bloom;
    float mothIn = 80f, mothT = -1, mothX, mothY, mothA;
    float snailX = INNER_CX - 70, snailStep;

    // Time of day.
    float hour = 12f, hourCheck;
    float shaftPower = 0.5f;
    // The beam you can see in the air: daylight (or moonlight) falling through the collapse onto
    // the road. Strength and colour follow the hour and the weather; fog makes it stand out.
    float beamBase;
    int beamCol = 0xFFFFF0C8;
    boolean shaftWarm, night;

    public Stage(long seed) {
        rnd.setSeed(seed);
        buildNear();
        buildShaft();
        life = new Life(rnd);
        for (int v = 0; v < vineX.length; v++) vineTop[v] = openTop(vineX[v]);
        drapeTop = 6;                                    // ivy over the top of the broken wall
        for (int i = 0; i < FLIES; i++) {
            fx[i] = 170 + rnd.nextFloat() * 130;
            fy[i] = 40 + rnd.nextFloat() * 110;
            fph[i] = rnd.nextFloat() * 6.28f;
        }
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float dx = (x - W * 0.4f) / W, dy = (y - H * 0.45f) / H;
                amb[y * W + x] = Math.max(0.025f, 0.095f - (dx * dx + dy * dy) * 0.2f);
            }
        readClock();
        if (!Life.debugString("debug.quiet.glitch").isEmpty()) glitchIn = 10f;
        // Stagger the rare events so two screens opened together don't do the same thing.
        fishIn += rnd.nextFloat() * 30;
        frogIn += rnd.nextFloat() * 40;
        eyesIn += rnd.nextFloat() * 40;
        mothIn += rnd.nextFloat() * 40;
    }

    // ---------------------------------------------------------------- building

    static boolean inArch(float x, float y, int cx, int hw, int spring, int rise) {
        float nx = (x - cx) / hw;
        if (nx <= -1 || nx >= 1) return false;
        if (y >= spring) return true;
        float ny = (spring - y) / rise;
        return nx * nx + ny * ny < 1f;
    }

    static int archTop(int x) {
        for (int y = 0; y < SPRING; y++) if (inArch(x, y, ARCH_CX, ARCH_HW, SPRING, RISE)) return y;
        return 0;
    }

    /** Elliptic radius around the arch (1 = on the edge), or -1 below the spring line. */
    static float archR(float x, float y) {
        if (y >= SPRING) return -1;
        float nx = (x - ARCH_CX) / ARCH_HW, ny = (SPRING - y) / RISE;
        return (float) Math.sqrt(nx * nx + ny * ny);
    }

    /** The broken edge of the near wall at row y: stones missing toward the top, rubble at the foot. */
    static int jambEdge(int y) {
        float e = JAMB + 4 * (float) Math.sin(y * 0.07) + 3 * (float) Math.sin(y * 0.23 + 1) + ((((y / 6) * 2654435761L) >>> 7 & 7) < 3 ? 3 : 0);
        if (y < 60) e -= (60 - y) * 0.35f;
        if (y > 128) e += (y - 128) * 0.9f;
        return (int) e;
    }

    void buildNear() {
        bricks(near, nearMoss, W, WATER_Y, 11, 5, 22, 18, 125, 60);     // big dressed stones: quiet behind words
        for (int y = 0; y < WATER_Y; y++) {
            int e = jambEdge(y);
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                boolean ledge = x >= LEDGE_X && y < LEDGE_Y - ((x / 5 * 7) % 3 == 0 ? 2 : 0);
                if (x > e && !ledge) {
                    near[i] = 0;                       // beyond the break: the road
                    nearMoss[i] = 0;
                    continue;
                }
                if (x > e - 3 && x <= e) near[i] = (byte) clamp((near[i] & 255) + 60);   // the broken edge catches light
                if (ledge && y >= LEDGE_Y - 4) near[i] = (byte) clamp((near[i] & 255) + 30);
                if (near[i] == 0) near[i] = 1;          // 0 is reserved for "open"
            }
        }
        // The vault has fallen in above the road: the real sky, with a bright lip where light catches it.
        float cx = (Life.SKY_X0 + Life.SKY_X1) / 2f, hw = (Life.SKY_X1 - Life.SKY_X0) / 2f;
        for (int x = Life.SKY_X0; x <= Life.SKY_X1; x++) {
            float n = (x - cx) / hw;
            int bottom = Math.round(Life.SKY_Y1 * (float) Math.sqrt(Math.max(0, 1 - n * n))) + rnd.nextInt(3) - 1;
            for (int y = 0; y < bottom; y++) {
                near[y * W + x] = 0;
                sky[y * W + x] = 1;
            }
            // The rim of the collapse: broken vault stones, so what hangs from it hangs from something.
            for (int k = 0; k < 3; k++) {
                int y = bottom + k;
                if (y < 0 || y >= WATER_Y) continue;
                near[y * W + x] = (byte) (k == 0 ? 220 : k == 1 ? 160 : 110 + rnd.nextInt(40));
            }
        }
        // ...and a few loose stones still clinging under it.
        for (int k = 0; k < 9; k++) {
            int x = Life.SKY_X0 + 4 + rnd.nextInt(Life.SKY_X1 - Life.SKY_X0 - 8);
            int y = openTop(x);
            if (y > 0 && y + 1 < WATER_Y) {
                near[y * W + x] = (byte) 140;
                if (rnd.nextBoolean()) near[y * W + x + 1] = (byte) 120;
            }
        }
    }

    /** Where the road's channel runs into our stream, in screen x. */
    public float channelX() {
        return vista.proj(5f, 0, 0)[0] - farOx;
    }

    /** The first open row at x (where vines and spiders hang from). */
    int openTop(int x) {
        for (int y = 0; y < WATER_Y; y++) if (near[y * W + x] == 0 && sky[y * W + x] == 0) return y;
        return 0;
    }

    boolean isSky(int i) {
        return sky[i] != 0;
    }

    void buildFar() {
        byte[] moss = new byte[FW * FH];
        bricks(far, moss, FW, FH, 4, 2, 6, 10, 75, 60);
        for (int y = 0; y < FH; y++)
            for (int x = 0; x < FW; x++) {
                int i = y * FW + x;
                if (inArch(x, y, INNER_CX, INNER_HW, INNER_SPRING, INNER_RISE)) far[i] = 4;
                else if (inArch(x, y, INNER_CX, INNER_HW + 3, INNER_SPRING, INNER_RISE + 3)) far[i] = (byte) 150;
            }
    }

    /** Coursed stone into `refl`. */
    void bricks(byte[] refl, byte[] moss, int w, int h, int hMin, int hVar, int wMin, int wVar, int base0, int baseVar) {
        Arrays.fill(refl, (byte) 16);
        int y = 0;
        while (y < h) {
            int bh = hMin + rnd.nextInt(hVar);
            int x = -rnd.nextInt(wMin + wVar);
            while (x < w) {
                int bw = wMin + rnd.nextInt(wVar);
                int base = base0 + rnd.nextInt(baseVar);
                for (int by = 0; by < bh - 1; by++)
                    for (int bx = 0; bx < bw - 1; bx++) {
                        int px = x + bx, py = y + by;
                        if (px < 0 || px >= w || py >= h) continue;
                        int v = base + rnd.nextInt(22) - 11;
                        if (by == 0) v += 50;
                        else if (by == bh - 2) v -= 40;
                        if (bx == 0) v += 18;
                        else if (bx == bw - 2) v -= 22;
                        boolean corner = (bx == 0 || bx == bw - 2) && (by == 0 || by == bh - 2);
                        if (corner && rnd.nextInt(3) == 0) v = 16;
                        refl[py * w + px] = (byte) clamp(v);
                    }
                if (rnd.nextInt(5) == 0) {
                    int cx = x + 2 + rnd.nextInt(Math.max(1, bw - 4)), cy = y;
                    for (int k = 0; k < bh - 2; k++) {
                        cy++;
                        cx += rnd.nextInt(3) - 1;
                        if (cx >= 0 && cx < w && cy < h) refl[cy * w + cx] = 38;
                    }
                }
                if (rnd.nextInt(4) == 0) {
                    int mx = x + rnd.nextInt(Math.max(1, bw - 6)), len = 3 + rnd.nextInt(7);
                    for (int k = 0; k < len; k++) {
                        int px = mx + k, py = y + (rnd.nextInt(4) == 0 ? 1 : 0);
                        if (px >= 0 && px < w && py < h) {
                            moss[py * w + px] = 1;
                            refl[py * w + px] = (byte) 200;
                        }
                    }
                }
                x += bw;
            }
            y += bh;
        }
    }

    /** A slanted beam from a crack above the arch down to the water. */
    void buildShaft() {
        for (int y = 12; y < WATER_Y; y++) {
            float f = (y - 12) / (float) (WATER_Y - 12);
            float l = 214 + f * 32, r = 240 + f * 52;
            for (int x = (int) l - 3; x <= (int) r + 3; x++) {
                if (x < 0 || x >= W) continue;
                float edge = Math.min(x - l, r - x);
                float k = Math.max(0f, Math.min(1f, (edge + 3) / 7f));
                k *= 1f - f * 0.55f;
                if (k <= 0) continue;
                shaft[y * W + x] = k;
                shaftU[y * W + x] = (x - l) / (r - l);
                shaftX0 = Math.min(shaftX0, x);
                shaftX1 = Math.max(shaftX1, x);
            }
        }
    }

    static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    // The focused name, glowing letters over the road (no slab: just the carving).
    String slab = "";
    int slabScale = 2;

    /** Show a name in glowing carved letters over the road, right-aligned. Fades in. */
    public void carve(String text, int right, int bottom, int scale) {
        slab = text;
        slabScale = Font.width(text, 2, 1) <= 104 ? 2 : 1;
        carveMix = 0f;
    }

    /** The name's letters: glowing, with a dark edge so they read on anything. After decor, before words. */
    void drawSlab() {
        if (slab.isEmpty()) return;
        int sc = slabScale, tw = Font.width(slab, sc, 1), th = Font.HEIGHT * sc;
        int x0 = 300 - tw, y0 = 150 - th;
        int[] tmp = new int[W * H];
        Font.draw(tmp, W, H, slab, x0, y0, sc, 1, 1);
        float d;
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                d = Pal.BAYER[((y & 3) << 2) | (x & 3)] + 0.5f;
                if (d > carveMix) continue;                                   // dissolves in
                if (tmp[i] != 0) out[i] = tmp[i + W] == 0 ? Pal.MINT_HI : Pal.MINT;
                else if (tmp[i - 1] != 0 || tmp[i + 1] != 0 || tmp[i - W] != 0 || tmp[i + W] != 0) out[i] = Pal.VOID;
            }
    }

    /** (v1 carved into the far wall; unused now.) */
    void carveFar(String text, int right, int bottom, int scale) {
        System.arraycopy(carveB, 0, carveA, 0, carveA.length);
        Arrays.fill(carveB, (byte) 0);
        if (!text.isEmpty()) {
            int[] tmp = new int[FW * FH];
            int w = Font.width(text, scale, 1);
            Font.draw(tmp, FW, FH, text, right - w + PAD / 2, bottom - Font.HEIGHT * scale + PAD / 2, scale, 1, 1);
            for (int y = 1; y < FH - 1; y++)
                for (int x = 1; x < FW - 1; x++) {
                    int i = y * FW + x;
                    if (tmp[i] == 0) {
                        // A one-pixel dark outline around every stroke.
                        if (tmp[i - 1] != 0 || tmp[i + 1] != 0 || tmp[i - FW] != 0 || tmp[i + FW] != 0) carveB[i] = 3;
                        continue;
                    }
                    boolean below = tmp[i + FW] != 0;
                    carveB[i] = (byte) (!below ? 2 : 1);
                }
        }
        carveMix = 0f;
    }

    /**
     * 0..1: where the selection sits in the list. The layers used to drift apart as it moved; with
     * the road in perspective that read as the tunnel sliding up and down with Mote, so the world
     * now holds still (the wall in front doesn't move, so nothing behind it should either).
     */
    public void camera(float f) {
        camTarget = 0f;
    }

    // ---------------------------------------------------------------- frame

    void readClock() {
        boolean changed = life.readClock();
        hour = life.hour;
        night = life.dark > 0.5f;
        shaftWarm = life.golden > 0.3f;
        shaftPower = life.dark > 0.6f ? (life.moonUp() ? 0.2f : 0.08f) : 0.14f + life.day * 0.46f + life.golden * 0.22f;
        float over = Weather.overcast();
        beamBase = life.dark > 0.6f ? (life.moonUp() ? 0.3f : 0f) : 0.3f + life.day * 0.4f + life.golden * 0.3f;
        beamBase *= 1f - over * 0.6f;
        if (Weather.rain()) beamBase *= 0.45f;
        if (Weather.fog()) beamBase = Math.min(0.8f, beamBase * 1.4f + 0.1f);
        beamCol = life.dark > 0.6f ? 0xFFA8BEE0 : shaftWarm ? 0xFFFFC878 : over > 0.5f || Weather.fog() ? 0xFFD8DCE4 : 0xFFFFF0C8;
        if (changed || !baked) bake();
        eggs.readClock(life);
        readSeason();
        float dm = Life.debugFloat("debug.quiet.music");           // test: 1 as if music plays, 2 and it swells
        debugMusic = dm >= 1;
        if (dm >= 2) swellAt = System.currentTimeMillis();
        float fm = Life.debugFloat("debug.quiet.mood");           // test: 0 chill 1 groove 2 dance 3 rock 4 bright
        party.forced = fm >= 0 ? Math.round(fm) : -1;
        debugRide = Life.debugFloat("debug.quiet.frogride") >= 1;  // test: leaves often, and the frog always boards
        grade();
        vista.paint(this);
    }

    /** Which festival season it is (see Seasons); it drives the eggs' fireworks and pumpkins too. */
    void readSeason() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.MONTH) + 1, d = c.get(java.util.Calendar.DAY_OF_MONTH);
        String dbg = Life.debugString("debug.quiet.date");
        if (dbg.length() == 4) {
            m = Integer.parseInt(dbg.substring(0, 2));
            d = Integer.parseInt(dbg.substring(2));
        }
        seasons.read(c, m, d);
        eggs.halloween = seasons.halloween == 0;                       // (more bats on the night itself)
        eggs.diwali = seasons.diwali == -1 || seasons.diwali == 0;     // fireworks: the eve and the night
        eggs.festive = seasons.vishu == -1;                             // and the eve of Vishu
    }

    // ---------------------------------------------------------------- the colour of the hour

    // The ramps the world is actually drawn with: the palette, graded by time of day and weather.
    final int[] gStone = Pal.STONE.clone(), gWarm = Pal.WARM.clone(), gCool = Pal.COOL.clone(), gFar = Pal.FAR.clone();
    int gWater = Pal.WATER;

    /**
     * Day is bright and airy, golden hour drenched in amber, the blue hour violet, night deep blue
     * (so the lamps and fireflies carry it). Grey weather drains the colour; mist lifts the shadows.
     * Only 29 colours are recomputed, so it costs nothing per frame.
     */
    void grade() {
        float day = life.day * (1f - life.golden), gold = life.golden, blue = life.blue, night = life.dark * (1f - blue);
        float sum = day + gold + blue + night;
        if (sum < 0.01f) {
            night = 1f;
            sum = 1f;
        }
        float[][] L = {{1.28f * 1.38f, 1.17f * 1.38f, 0.98f * 1.38f}, {1.5f * 1.18f, 0.98f * 1.18f, 0.66f * 1.18f},
                {0.92f, 0.82f, 1.22f}, {0.62f, 0.76f, 1.18f}};
        float[] w = {day / sum, gold / sum, blue / sum, night / sum};
        float[] l = new float[3];
        for (int c = 0; c < 3; c++) for (int k = 0; k < 4; k++) l[c] += L[k][c] * w[k];
        float over = Weather.overcast(), sat = 1f - over * 0.55f, dim = 1f - over * 0.22f - (Weather.rain() ? 0.08f : 0f);
        float fog = Weather.fog() ? 0.24f : 0f;
        int fogCol = Pal.mix(0xFF262C3C, 0xFF8C96A2, life.day);
        if (life.flash > 0) {                                        // lightning
            float f = life.flash;
            for (int c = 0; c < 3; c++) l[c] = l[c] * (1f + f * 1.6f) + f * 0.3f;
        }
        for (int c = 0; c < 3; c++) l[c] *= dim;
        rampGrade(Pal.STONE, gStone, l, sat, fog, fogCol);
        rampGrade(Pal.WARM, gWarm, l, sat, fog, fogCol);
        rampGrade(Pal.COOL, gCool, l, sat, fog, fogCol);
        rampGrade(Pal.FAR, gFar, l, sat, fog * 1.4f, fogCol);
        gWater = gradeColor(Pal.WATER, new float[]{l[0] * 1.3f + 0.02f, l[1] * 1.3f + 0.03f, l[2] * 1.3f + 0.05f}, sat, fog, fogCol);
    }

    static void rampGrade(int[] src, int[] dst, float[] l, float sat, float fog, int fogCol) {
        dst[0] = src[0];
        for (int i = 1; i < src.length; i++) dst[i] = gradeColor(src[i], l, sat, fog * (0.4f + i * 0.1f), fogCol);
    }

    static int gradeColor(int c, float[] l, float sat, float fog, int fogCol) {
        float r = ((c >> 16) & 255) * l[0], g = ((c >> 8) & 255) * l[1], b = (c & 255) * l[2];
        float y = r * 0.3f + g * 0.59f + b * 0.11f;
        r = y + (r - y) * sat;
        g = y + (g - y) * sat;
        b = y + (b - y) * sat;
        int out = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        return fog > 0 ? Pal.mix(out, fogCol, Math.min(0.6f, fog)) : out;
    }

    static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    boolean baked;

    /** The fixed lights: mushrooms, daylight through the crack, the beam. */
    void bake() {
        baked = true;
        baking = true;
        float[] w = warm, k = cool;
        Arrays.fill(w, 0f);
        Arrays.fill(k, 0f);
        // Mushrooms glow brighter in the dark.
        float glow = 0.55f + life.dark * 0.55f;
        light(286, 160, 92, 0.6f * glow, false);
        light(240, 162, 62, 0.34f * glow, false);
        light(163, 158, 46, 0.4f * glow, false);
        // The whole cave takes on the colour of the hour: neutral daylight, gold at the turn of
        // the day, blue moonlight at night.
        float neutral = 0.01f + life.day * 0.42f, gold = life.golden * 0.24f, moon = life.dark * 0.04f;
        for (int i = 0; i < W * H; i++) {
            float a = amb[i] * 10.5f;
            w[i] += a * (neutral * 0.5f + gold);
            k[i] += a * (neutral * 0.5f + moon);
        }
        // Sunlight is neutral (both maps), golden hour warm, moonlight cool.
        float bw = shaftWarm ? 1f : life.dark > 0.6f ? 0f : 0.5f, bc = shaftWarm ? 0f : life.dark > 0.6f ? 1f : 0.5f;
        for (int y = 12; y < WATER_Y; y++) {
            int row = y * W;
            for (int x = shaftX0; x <= shaftX1; x++) {
                float v = shaft[row + x] * shaftPower;
                w[row + x] += v * bw;
                k[row + x] += v * bc;
            }
        }
        System.arraycopy(w, 0, sWarm, 0, w.length);
        System.arraycopy(k, 0, sCool, 0, k.length);
        Arrays.fill(w, 0f);
        Arrays.fill(k, 0f);
        baking = false;
    }

    /** "MORNING", "AFTERNOON"... for screens that want to greet you. */
    public String partOfDay() {
        return hour < 5 ? "LATE NIGHT" : hour < 12 ? "MORNING" : hour < 17 ? "AFTERNOON" : hour < 21 ? "EVENING" : "NIGHT";
    }

    /** Advance the world. Call once per frame before drawing. */
    public void tick(float dt) {
        t += dt;
        voiceT += dt;
        if ((voice == 3 && voiceT > 2.5f) || (voice != 0 && voiceT > 25f)) voice = 0;   // back to their day
        hourCheck -= dt;
        if (hourCheck <= 0) {
            hourCheck = 30;
            readClock();
        }
        Arrays.fill(warm, 0f);
        Arrays.fill(cool, 0f);
        Arrays.fill(roadW, 0f);
        Arrays.fill(roadK, 0f);
        if (carveMix < 1f) carveMix = Math.min(1f, carveMix + dt / 0.5f);

        cam += (camTarget - cam) * Math.min(1f, dt * 4f);
        farOx = PAD / 2 + Math.round((float) Math.sin(t * 0.05) * 3f);
        farOy = PAD / 2 + Math.round(-cam * 0.5f);
        rootOy = Math.round(cam * 1.2f);

        // The fixed lights breathe together; the mushrooms flare when they bloom.
        sGain = (0.9f + 0.1f * (float) Math.sin(t * 1.3)) * (1f + bloom * 0.5f) * (1f + (0.1f * pulse() + 0.2f * bassE) * won);   // and with the bass

        groove(dt);
        if (wakeT >= 0 && (wakeT += dt) > 0.8f) wakeT = -1;
        if (glitchT >= 0 && (glitchT += dt) > 0.3f) glitchT = -1;
        if (wakeT < 0 && !mote.asleep && (glitchIn -= dt) <= 0) {
            glitchT = 0;
            glitchSeed = rnd.nextInt();
            glitchIn = 1500f + rnd.nextFloat() * 2100f;
            dog.alert = 0.8f;
            if (dog.state != Dog.SLEEPING && !dog.busy()) {
                dog.face = 160 > dog.x ? 1 : -1;
                dog.start(this, Dog.WATCH, 2.5f);
            }
        }
        if (life.flash > 0 || life.flashWas) grade();         // lightning re-grades every frame while it lasts
        waves();
        drips(dt);
        flies(dt);
        happenings(dt);
        life.tick(this, dt);
        particles(dt);

        party.tick(this, dt);
        supper();
        mote.update(this, dt);
        mote.light(this);
        dog.update(this, dt);
        lore.tick(this, dt);
        if (days != grownFor) {                         // the vines grow with the days
            grownFor = days;
            for (int v = 0; v < vineX.length; v++)
                vineLen[v] = Math.max(VINE_BASE[v], Math.min(VINE_BASE[v] + days / 4, WATER_Y - 34 - vineTop[v]));
        }
        lore.lights(this);
        eggs.tick(this, dt);
        eggs.lights(this);
        seasons.tick(this, dt);
        seasons.lights(this);

        party.lights(this);
        // With music, each mushroom glows on its beat, and the light round the bend breathes with it.
        if (groove > 0.02f) {
            for (int m = 0; m < MUSH.length; m++) {
                float p = pulse(m & 1);
                float g = 0.3f * p + 0.3f * bassE * won;
                if (g > 0.02f) light(MUSH[m][0], WATER_Y - 2 - MUSH[m][1], 12 + MUSH[m][2] * 2, g, false);
            }
            float[] bend = vista.proj(23f, 0, 3f);
            light(bend[0] - farOx, bend[1] - farOy, 44, (0.15f * pulse() + 0.4f * loudE) * won, true, 23f);
        }
        for (int v = 0; v < vineX.length; v++)
            if (bud(v)) light(vineX[v] + vineSway(v, vineLen[v]), vineTop[v] + vineLen[v] + 1, 9, 0.18f, false, 2f);
    }

    void waves() {
        for (int i = 0; i < W; i++) wv[i] += -wh[i] * 0.03f - wv[i] * 0.04f;
        for (int pass = 0; pass < 2; pass++)
            for (int i = 1; i < W - 1; i++) wv[i] += (wh[i - 1] + wh[i + 1] - 2 * wh[i]) * 0.12f;
        for (int i = 0; i < W; i++) wh[i] += wv[i];
    }

    void drips(float dt) {
        // Drips come in spells: sometimes the cave is almost dry, sometimes it patters.
        float wet = 0.5f + 0.5f * (float) Math.sin(t * 0.013 + 1);
        dripIn -= dt * (0.4f + wet * 1.6f);
        if (dripIn <= 0) {
            dripIn = 2f + rnd.nextFloat() * 4f;
            int v = rnd.nextInt(vineX.length);
            spawn(vineX[v] + vineSway(v, vineLen[v]), vineTop[v] + vineLen[v] + 1, 0, 0.2f, 9f, P_DRIP);
        }
        moteIn -= dt;
        if (moteIn <= 0) {                       // dust drifting through the beam
            moteIn = 1.8f + rnd.nextFloat() * 2.2f;
            spawn(214 + rnd.nextInt(30), 26 + rnd.nextInt(20), 0.06f + rnd.nextFloat() * 0.05f, 0.12f, 14f, P_MOTE);
        }
    }

    void groove(float dt) {
        float tempo = bpm > 40 ? bpm : 100f;
        beatNow = false;
        beatPh += dt * tempo / 60f;
        if (beatPh >= 1f) {
            beatPh -= 1f;
            beats++;
            beatNow = true;
        }
        long h = hitAt;
        if (h != hitSeen) {                       // a real kick: pull the clock onto it
            hitSeen = h;
            if (beatPh > 0.5f) beatPh += (1f - beatPh) * 0.6f;
            else beatPh -= beatPh * 0.6f;
        }
        boolean playing = (music || debugMusic) && partyAllowed;
        float att = Math.min(1f, dt * 14f), rel = Math.min(1f, dt * 4f);
        if (debugMusic && !music) {                         // test: a made-up song, so the bands move
            bass = pulse() > 0.3f ? 0.9f : 0.3f;
            mid = 0.5f + 0.4f * (float) Math.sin(t * 2.1f);
            high = 0.5f + 0.5f * (float) Math.sin(t * 5.3f);
            loud = 0.6f + 0.3f * (float) Math.sin(t * 0.4f);
        }
        bassE += (bass - bassE) * (bass > bassE ? att : rel);
        midE += (mid - midE) * (mid > midE ? att : rel);
        highE += (high - highE) * (high > highE ? att : rel);
        loudE += (loud - loudE) * rel;
        // Up within a second when the music starts; back to normal the moment it stops.
        groove += ((playing ? 1f : 0f) - groove) * Math.min(1f, dt / (playing ? 0.8f : 0.2f));
        won += ((playing ? 1f : 0f) - won) * Math.min(1f, dt / (playing ? 1.2f : 0.25f));
        long sw = swellAt;
        if (sw != swellSeen) {
            swellSeen = sw;
            if (playing && groove > 0.8f) {
                bloom = 1f;                                // the mushrooms burst open for it
                for (int k = 0; k < 12; k++)
                    spawn(150 + rnd.nextInt(150), WATER_Y - 8 - rnd.nextInt(6), (rnd.nextFloat() - 0.5f) * 0.1f,
                            -0.1f - rnd.nextFloat() * 0.1f, 7f + rnd.nextFloat() * 4, P_SPORE);
            }
        }
        if (beatNow && groove > 0.3f) {            // the stream feels the kick, as hard as the bass hits
            for (int k = 0; k < 3 + Math.round(won * 4); k++) splash(10 + rnd.nextInt(W - 20), 0.12f * groove + 0.4f * bassE * won);
        }
        if (won > 0.3f && rnd.nextFloat() < highE * won * dt * 6f) splash(10 + rnd.nextInt(W - 20), 0.08f);   // the top end: little rain of notes
    }

    /** 0..1, sharp on the beat and gone by the next one; scaled by how much the world is dancing. */
    public float pulse() {
        float p = 1f - beatPh;
        return groove * p * p * p;
    }

    /** The same, for one of two groups that trade beats (Mario-style call and answer). */
    float pulse(int group) {
        return ((beats + group) & 1) == 0 ? pulse() : 0f;
    }

    /** The party's on (Apple Music playing, and the world has caught up with it). */
    public boolean partyOn() {
        return party.on(this);
    }

    /** How far the music's spell has taken hold, 0..1 (follows the music, slowly). */
    public float wonder() {
        return won;
    }

    // Fine pictures for this frame (see Fine): drawn over the grid at full screen resolution.
    static final class Blit {
        android.graphics.Bitmap b;
        int x, y, w, h;
        float dim;
    }
    final java.util.ArrayList<Blit> blits = new java.util.ArrayList<>();
    int nBlits;

    // Grid rects that sit above the fine pictures this frame (a menu over the posters).
    final int[] over = new int[4 * 32];
    int nOver;

    /** Keep the fine pictures out of this rect, so what the grid draws there stays on top. */
    public void over(int x, int y, int w, int h) {
        if (nOver == over.length / 4) return;
        int i = nOver++ * 4;
        over[i] = x;
        over[i + 1] = y;
        over[i + 2] = w;
        over[i + 3] = h;
    }

    /** Draw a Fine picture at grid position x,y, w×h grid pixels; dim 0..1 darkens it. */
    public void fine(android.graphics.Bitmap b, int x, int y, int w, int h, float dim) {
        if (b == null) return;
        if (nBlits == blits.size()) blits.add(new Blit());
        Blit k = blits.get(nBlits++);
        k.b = b;
        k.x = x;
        k.y = y;
        k.w = w;
        k.h = h;
        k.dim = dim;
    }

    /**
     * The TV has just come on: the well is dark, Mote's lamp catches first, and its light spreads
     * until everything is lit. About a second and a half.
     */
    public void wake() {
        wake(0);
    }

    /** The TV's come on after `away` ms off: a long absence gets a bigger hello from Pip. */
    public void wake(long away) {
        wakeT = 0;
        if (sound != null) sound.chime();
        dog.alert = 0.8f;
        dog.bigGreet = away > 3 * 3_600_000L;
    }

    /** Any key during the wake-up: straight to the lit well. */
    public void wakeSkip() {
        wakeT = -1;
    }

    /** The dark of a waking well, cut away around Mote's light. Call after everything is drawn. */
    public void reveal() {
        if (glitchT >= 0) {                                 // a few rows torn sideways, one band off-colour
            Random g = new Random(glitchSeed + (int) (glitchT * 20));
            for (int band = 0; band < 3; band++) {
                int y0 = g.nextInt(H - 8), hgt = 2 + g.nextInt(6), off = (g.nextInt(9) - 4) * 2;
                for (int y = y0; y < y0 + hgt; y++) {
                    int[] row = new int[W];
                    System.arraycopy(out, y * W, row, 0, W);
                    for (int x = 0; x < W; x++) {
                        int c = row[((x - off) % W + W) % W];
                        if (band == 0) c = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
                        out[y * W + x] = c;
                    }
                }
            }
        }
        float wd = windDown;
        if (wd > 0.01f) {                                     // a sleep timer running out: the world goes dark with the music
            float a = Math.min(0.85f, wd * 0.85f);
            for (int i = 0; i < W * H; i++) out[i] = Pal.mix(out[i], Pal.VOID, a);
        }
        if (wakeT < 0) return;
        // Quick, and already open: this panel lights a couple of seconds late, and a dark screen
        // just after pressing power reads as "still off".
        float k = Math.min(1f, wakeT / 0.7f);
        k = k * k * (3 - 2 * k);                                    // ease
        float r = 120f + k * 280f, mx = mote.x - 4 * mote.face, my = mote.y;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float dx = x - mx, dy = (y - my) * 1.15f, dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < r - 10) continue;
                int i = y * W + x;
                if (dist > r) out[i] = Pal.VOID;
                else if (Pal.BAYER[((y & 3) << 2) | (x & 3)] + 0.5f < (dist - (r - 10)) / 10f) out[i] = Pal.VOID;
            }
    }

    /**
     * A few words the well answers to, from any search box. Returns a short reply to show instead
     * of results, or null for an ordinary search.
     */
    public String answer(String query) {
        // (A few words make the well answer; which, and how, is kept private in this shared copy.)
        return null;
    }

    /** A key press from a screen that wants the well to listen (the home screen). */
    public boolean key(int code) {
        eggs.key(this, code);
        return lore.key(this, code);
    }

    /** 0 in daylight .. 1 at night. */
    public float darkness() {
        return life.dark;
    }

    /** Put Pip somewhere (on a screen whose bank stretch differs from the default). */
    public void placeDog(float x) {
        dog.x = dog.tx = x;
    }

    // Supper: every evening Pip's bowl is found filled (see LIFE.md). fedDay is the day he last ate.
    boolean bowlFull;
    int fedDay = -1;

    /** His bed, at the quiet end of his stretch of bank, and his bowl beside it. */
    float bedX() {
        return dogMin + 10;
    }

    float bowlX() {
        return dogMin + 24;
    }

    static int today() {
        return java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
    }

    void supper() {
        if (life.hour >= 18.5f && fedDay != today()) bowlFull = true;
        else if (fedDay == today()) bowlFull = false;
    }

    /** Pip's stretch of bank on this screen; if he's off it, he trots over (never jumps). */
    public void dogPatch(int lo, int hi) {
        if (dogMin == lo && dogMax == hi) return;
        dogMin = lo;
        dogMax = hi;
        if (dog.x < lo || dog.x > hi) {
            dog.tx = dog.x < lo ? lo + 14 : hi - 14;
            if (dog.state != Dog.SLEEPING) dog.start(this, Dog.WALK, 1e9f);
        }
    }

    /** Where everyone is, for the next page (see World). */
    public String snapshot() {
        return "w1|" + Math.round(dog.x) + "|" + dog.face + "|" + (dog.holding ? 1 : 0) + "|" + Math.round(dog.bx)
                + "|" + days + "|" + (doorOpen ? 1 : 0) + "|" + System.currentTimeMillis() + "|" + fedDay + "|" + Math.round(dog.energy * 100);
    }

    /** Pick up the world as the last page left it. Call after this page has set Pip's patch. */
    public void restore(String snap) {
        if (snap == null) return;
        String[] p = snap.split("\\|");
        if (p.length < 8 || !p[0].equals("w1")) return;
        try {
            days = Math.max(days, Integer.parseInt(p[5]));
            doorOpen |= p[6].equals("1");
            if (p.length >= 10) {                            // supper and how tired he is, everywhere the same
                fedDay = Math.max(fedDay, Integer.parseInt(p[8]));
                dog.energy = Integer.parseInt(p[9]) / 100f;
            }
            if (System.currentTimeMillis() - Long.parseLong(p[7]) > 20 * 60_000L) return;   // long ago: he's wandered off
            float x = Float.parseFloat(p[1]);
            dog.face = Integer.parseInt(p[2]) >= 0 ? 1 : -1;
            dog.holding = p[3].equals("1");
            dog.bx = Math.max(dogMin, Math.min(dogMax, Float.parseFloat(p[4])));
            if (x >= dogMin && x <= dogMax) placeDog(x);
            else {                                           // his spot isn't on this page: in he trots from the edge
                boolean left = x < dogMin;
                placeDog(left ? dogMin - 16 : dogMax + 16);
                dog.face = left ? 1 : -1;
                dog.tx = left ? dogMin + 18 : dogMax - 18;
                dog.start(this, Dog.WALK, 1e9f);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /** 0 on the beat, rising to 1 just before the next. */
    public float beatPhase() {
        return beatPh;
    }

    void flies(float dt) {
        syncIn -= dt;
        if (syncIn <= 0 && syncFor <= 0) {
            syncFor = 7f;
            syncIn = 80f + rnd.nextFloat() * 90f;
            if (sound != null) sound.chime();
        }
        if (syncFor > 0) syncFor -= dt;
        // Now and then one firefly drifts down to the bank, as if to tease the dog.
        lureIn -= dt;
        if (lureIn <= 0 && lureFor <= 0 && dog.state != Dog.SLEEPING) {
            lureFor = 9f;
            lureIn = 40f + rnd.nextFloat() * 50f;
        }
        if (lureFor > 0) {
            lureFor -= dt;
            fvx[0] += (dog.x + dog.face * 12 - fx[0]) * 0.0025f;
            fvy[0] += (WATER_Y - 20 - fy[0]) * 0.003f;
        }
        for (int i = 0; i < FLIES; i++) {
            fvx[i] += (rnd.nextFloat() - 0.5f) * 0.12f;
            if (frogT >= 0 && Math.abs(fx[i] - frogX) < 26 && fy[i] > WATER_Y - 30)   // they know about the frog
                fvx[i] += Math.signum(fx[i] - frogX) * 0.08f;
            fvy[i] += (rnd.nextFloat() - 0.5f) * 0.10f;
            float dx = fx[i] - mote.x, dy = fy[i] - mote.y, d2 = dx * dx + dy * dy;
            if (eggs.wish) {                            // (a private minute): they gather round, all lit
                float ang = (float) (i * Math.PI * 2 / FLIES + t * 0.6), tx = mote.x + (float) Math.cos(ang) * 18,
                        ty = mote.y + (float) Math.sin(ang) * 11;
                fvx[i] += (tx - fx[i]) * 0.01f;
                fvy[i] += (ty - fy[i]) * 0.01f;
            } else if (mote.moving() && d2 < 900) {            // scatter from the selector
                fvx[i] += dx / (d2 + 1) * 6;
                fvy[i] += dy / (d2 + 1) * 6;
            } else if (mote.asleep && d2 < 9000) {      // gather round it while it sleeps
                fvx[i] -= dx * 0.0008f;
                fvy[i] -= dy * 0.0008f;
            }
            boolean free = mote.asleep || (i == 0 && lureFor > 0);
            float left = free ? 20 : JAMB + 12, right = 312;
            if (fx[i] < left) fvx[i] += 0.04f;
            if (fx[i] > right) fvx[i] -= 0.04f;
            if (fy[i] < 34) fvy[i] += 0.03f;
            if (fy[i] > WATER_Y - 8) fvy[i] -= 0.04f;
            fvx[i] *= 0.95f;
            fvy[i] *= 0.95f;
            fx[i] += fvx[i];
            fy[i] += fvy[i];
            float g = glow(i);
            if (g > 0.15f) light(fx[i], fy[i], 14 + life.dark * 4, 0.38f * g * (1f + life.dark * 0.5f), false);
        }
    }

    float glow(int i) {
        if (i >= (night ? FLIES : FLIES - 5)) return 0f;            // fewer out by day
        if (groove > 0.3f && syncFor <= 0) {                         // two groups, trading beats
            float g = ((beats + i) & 1) == 0 ? 1f - beatPh : 0.15f;
            g = Math.max(0.15f, g * g);
            if (((i * 7 + beats) % 4) == 0) g = Math.max(g, highE * won);   // and the top end sparkles
            return g;
        }
        float ph = syncFor > 0 ? 0f : fph[i];
        float speed = syncFor > 0 ? 4.5f : 2.3f;
        float g = 0.5f + 0.5f * (float) Math.sin(t * speed + ph);
        return syncFor > 0 ? g * g : g;
    }

    void happenings(float dt) {
        // A fish leaps in the tunnel mouth.
        fishIn -= dt;
        if (fishIn <= 0 && fishT < 0) {
            fishT = 0;
            fishX = ARCH_CX - 40 + rnd.nextInt(60);
            splashAt(fishX, 2.5f, 0.55f);
        }
        if (fishT >= 0) {
            fishT += dt;
            if (fishT >= 0.9f) {
                splashAt(fishX + 22, 2f, 0.7f);
                fishT = -1;
                fishIn = 20f + rnd.nextFloat() * 45f;
            }
        }
        // A frog climbs onto the bank, croaks a few times, dives back in.
        frogIn -= dt;
        if (frogIn <= 0 && frogT < 0) {
            frogT = 0;
            frogX = Ties.roomy(this, 100, 300, 200, Ties.FROG, 40);   // wherever's quiet along the water
            frogBrave = rnd.nextInt(10) < 3;
            frogSit = 14f + rnd.nextFloat() * 18f;
            splashAt(frogX, 1.2f, 0.3f);
            frogRides = false;
            frogAfloat = false;
        }
        // A leaf come down the channel from the road: now and then the frog climbs on for a ride.
        if (frogT < 0 && life.leafX > 60 && life.leafX < channelX() - 10 && !life.leafRidden) {
            life.leafRidden = true;
            if (debugRide || rnd.nextInt(3) == 0) {
                frogT = 0;
                frogRides = true;
                frogAfloat = true;
                frogX = life.leafX;
                frogSit = 60f;                               // until the leaf takes it out of sight
                splashAt(frogX, 1f, 0.25f);
            }
        }
        if (frogRides && frogT >= 0) {
            if (life.leafX < 0 || life.leafX < 30) {          // nearly off the edge: in it goes
                if (frogT < frogSit) frogT = Math.max(frogT, frogSit);
                frogRides = false;
            } else frogX = life.leafX;
        }
        if (frogT >= 0) {
            float before = frogT;
            frogT += dt;
            for (float c = 3f; c < frogSit; c += 5.5f)
                if (before - 0.5f < c && frogT - 0.5f >= c && sound != null) sound.croak();
            if (frogT > frogSit + 1.0f) {
                splashAt(frogAfloat ? frogX : frogX - 8, frogAfloat ? 1.2f : 1.8f, 0.35f);
                frogT = -1;
                frogAfloat = false;
                frogIn = 45f + rnd.nextFloat() * 70f;
            }
        }
        // Eyes open in the dark of the inner arch. More likely at night.
        eyesIn -= dt * (night ? 2f : 1f);
        if (eyesIn <= 0 && eyesT < 0) {
            eyesT = 0;
            eyesX = INNER_CX - 10 + rnd.nextInt(16);
            eyesY = INNER_SPRING - 2 + rnd.nextInt(12);
        }
        if (eyesT >= 0) {
            eyesT += dt;
            if (eyesT > 5f) {
                eyesT = -1;
                eyesIn = 40f + rnd.nextFloat() * 60f;
            }
        }
        // A pebble works loose from the arch.
        pebbleIn -= dt;
        if (pebbleIn <= 0) {
            pebbleIn = 30f + rnd.nextFloat() * 50f;
            int x = ARCH_CX - 30 + rnd.nextInt(60);
            spawn(x, openTop(x), (rnd.nextFloat() - 0.5f) * 0.2f, 0.3f, 10f, P_PEBBLE);
        }
        // The mushrooms bloom and let go of spores.
        bloomIn -= dt;
        if (bloomIn <= 0) {
            bloomIn = 45f + rnd.nextFloat() * 60f;
            bloom = 1f;
            for (int k = 0; k < 10; k++)
                spawn(266 + rnd.nextInt(34), WATER_Y - 8 - rnd.nextInt(6), (rnd.nextFloat() - 0.5f) * 0.1f,
                        -0.08f - rnd.nextFloat() * 0.08f, 7f + rnd.nextFloat() * 4, P_SPORE);
        }
        bloom = Math.max(0f, bloom - dt * 0.25f);
        // A moth comes to the selector's light, circles it, then wanders off.
        mothIn -= dt;
        if (mothIn <= 0 && mothT < 0 && !mote.asleep) {
            mothT = 0;
            mothX = -6;
            mothY = 20 + rnd.nextInt(60);
        }
        if (mothT >= 0) {
            mothT += dt;
            float tx, ty;
            if (mothT < 9f) {
                mothA += dt * 3.2f;
                tx = mote.x + (float) Math.cos(mothA) * 11;
                ty = mote.y - 3 + (float) Math.sin(mothA * 1.3f) * 6;
            } else {
                tx = -20;
                ty = 10;
            }
            mothX += (tx - mothX) * Math.min(1f, dt * 2.2f) + (rnd.nextFloat() - 0.5f) * 1.2f;
            mothY += (ty - mothY) * Math.min(1f, dt * 2.2f) + (rnd.nextFloat() - 0.5f) * 1.2f;
            if (mothT > 9f && mothX < -10) {
                mothT = -1;
                mothIn = 50f + rnd.nextFloat() * 60f;
            }
        }
        // The snail: one pixel every few seconds, all day long, across the far wall.
        snailStep += dt;
        if (snailStep > 4f) {
            snailStep = 0;
            snailX += 1;
            if (snailX > INNER_CX + 80) snailX = INNER_CX - 80;
        }
    }

    void particles(float dt) {
        for (float[] q : p) {
            if (q[4] <= 0) continue;
            q[0] += q[2];
            q[1] += q[3];
            q[4] -= dt;
            int k = (int) q[5];
            if (k == P_DRIP || k == P_PEBBLE) {
                q[3] += 0.12f;
                if (k == P_DRIP) light(q[0], q[1], 7, 0.25f, false);
                if (q[1] >= WATER_Y) {
                    splashAt(q[0], k == P_PEBBLE ? 2.6f : 1.4f, k == P_PEBBLE ? 0.25f : 0.6f + rnd.nextFloat() * 0.4f);
                    q[4] = 0;
                }
            } else if (k == P_RAIN) {
                if (q[1] >= WATER_Y) {
                    life.rainDrop(this, q[0]);
                    q[4] = 0;
                }
            } else if (k == P_ZED) {
                q[0] += (float) Math.sin(t * 3 + q[1]) * 0.1f;
            } else if (k == P_SPORE) {
                q[0] += (float) Math.sin(t * 1.5 + q[1] * 0.3) * 0.08f;
                light(q[0], q[1], 6, 0.15f * Math.min(1f, q[4] / 3f), false);
            } else if (k == P_DROPLET) {
                q[3] += 0.1f;
                if (q[1] >= WATER_Y) q[4] = 0;
            } else if (k == P_MOTE) {
                q[0] += (float) Math.sin(t * 0.7 + q[1] * 0.1) * 0.03f;
                if (q[1] > WATER_Y - 2) q[4] = 0;
            } else if (k == P_NOTE) {
                q[0] += (float) Math.sin(t * 2 + q[1]) * 0.12f;
            } else if (k == P_DUST) {
                q[3] += 0.02f;
            }
        }
    }

    boolean bud(int v) {
        if (won > 0.3f) return pulse(v & 1) > 0.25f;          // with music: flashing on the beat, trading sides
        return Math.sin(t * 1.7 + v) > -0.3;
    }

    int vineSway(int v, int j) {
        float s = (float) Math.sin(t * 0.8f + vinePh[v] + j * 0.11f) * 2f;
        if (groove > 0.01f) s += (float) Math.sin(Math.PI * (beats + beatPh)) * groove * (0.8f + 2.4f * midE * won) * ((v & 1) == 0 ? 1 : -1);
        return Math.round(s * j / vineLen[v]);
    }

    /** Adds a soft round light. Warm lights use the lamp ramp, cool ones the glow ramp. */
    public void light(float lx, float ly, float r, float intensity, boolean isWarm) {
        light(lx, ly, r, intensity, isWarm, -1);
    }

    /**
     * A light, knowing the wall stands between us and the road. One out on the road (depth: how far
     * in, or -1 for "just inside") lights the road and the wall's broken edge, which faces it, but
     * never the wall's face. One on our side lights the wall and reaches into the road's mouth,
     * fading the further in a surface is. Daylight from the sky stays out of the road, which shades
     * its own.
     */
    public void light(float lx, float ly, float r, float intensity, boolean isWarm, float depth) {
        float[] map = isWarm ? warm : cool, road = isWarm ? roadW : roadK;
        int sx = Math.max(0, Math.min(W - 1, (int) lx)), sy = Math.max(0, Math.min(H - 1, (int) ly)), si = sy * W + sx;
        boolean fromSky = sky[si] != 0;
        boolean behind = !fromSky && sy < WATER_Y && near[si] == 0;
        boolean intoRoad = !fromSky && !baking;
        float zl = depth >= 0 ? depth : behind ? 3f : 0f;
        int x0 = Math.max(0, (int) (lx - r)), x1 = Math.min(W - 1, (int) (lx + r));
        int y0 = Math.max(0, (int) (ly - r)), y1 = Math.min(H - 1, (int) (ly + r));
        float r2 = r * r;
        float[] zAt = vista.zAt;
        for (int y = y0; y <= y1; y++) {
            float dy = y - ly;
            int row = y * W;
            boolean above = y < WATER_Y;
            int edge = above ? jambEdge(y) - 4 : W;
            int fy = y + farOy;
            boolean farRow = fy >= 0 && fy < FH;
            for (int x = x0; x <= x1; x++) {
                float dx = x - lx, d2 = dx * dx + dy * dy;
                if (d2 >= r2) continue;
                float k = 1f - d2 / r2, v = k * k * intensity;
                int i = row + x;
                boolean wall = above && near[i] != 0;
                if (behind && wall) {
                    if (x >= edge && x < LEDGE_X) map[i] += v * 0.5f;   // only the broken edge faces the road
                    continue;
                }
                map[i] += v;
                if (intoRoad && above && !wall && sky[i] == 0 && farRow) {
                    int fx = x + farOx;
                    if (fx < 0 || fx >= FW) continue;
                    float zs = zAt[fy * FW + fx];
                    if (zs <= 0) continue;
                    float dz = zs - zl;
                    road[i] += v / (1f + dz * dz / 40f);
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /** Lights the stone (near wall, and the tunnel through the arch) into `out`. */
    public void resolve() {
        float mix = carveMix, g = sGain;
        // The beam breathes a little, as if clouds were passing over the opening.
        float beam = beamBase * (0.88f + 0.12f * (float) Math.sin(t * 0.37f) * (float) Math.sin(t * 0.23f + 1f));
        int bc = beamCol;
        if (beam > 0) for (int j = 0; j < rays.length; j++) {
            float u = j / 64f;
            rays[j] = beam * 0.4f / 0.06f * (0.55f + 0.45f * (float) Math.sin(u * 19f + t * 0.11f) * (float) Math.sin(u * 7f - t * 0.07f + 2f));
        }
        int glintBand = (int) (t * 90f);
        for (int y = 0; y < WATER_Y; y++) {
            int row = y * W, by = (y & 3) << 2;
            int fy = y + farOy;
            for (int x = 0; x < W; x++) {
                int i = row + x;
                float d = Pal.BAYER[by | (x & 3)];
                if (sky[i] != 0) {
                    out[i] = Pal.VOID;            // painted by Life.decor
                    continue;
                }
                int r = near[i] & 255;
                float w = warm[i] + sWarm[i] * g, k = cool[i] + sCool[i] * g;
                if (r != 0) {
                    float L = (amb[i] * 0.3f + w + k) * r * (1f / 255f);
                    int lv = (int) (L * 7f + d + 0.5f);
                    if (lv <= 0) {
                        out[i] = Pal.VOID;
                        continue;
                    }
                    if (lv > 6) lv = 6;
                    int[] ramp;
                    if (nearMoss[i] != 0) ramp = gCool;
                    else {
                        // Only a clear majority tints the stone; even daylight stays neutral grey.
                        float bias = (w - k) / (w + k + 0.05f) + d * 0.1f;
                        ramp = bias > 0.2f ? gWarm : bias < -0.2f ? gCool : gStone;
                    }
                    out[i] = ramp[lv];
                    continue;
                }
                // Through the arch: the far wall, dimmer, bluer, lit at reduced strength.
                int fx = x + farOx;
                int fi = fy * FW + fx;
                int c = (mix >= 1f || d + 0.5f < mix) ? carveB[fi] : carveA[fi];
                if (c != 0) {
                    // Bold glowing runes: bright lip, lit interior, dark outline against the bricks.
                    if (c == 2) out[i] = ((fx + fy / 2 - glintBand) % 420 + 420) % 420 < 12 ? Pal.TEXT : Pal.MINT_HI;
                    else if (c == 1) out[i] = d > 0.1f ? Pal.MINT : Pal.COOL[6];
                    else out[i] = Pal.VOID;
                    continue;
                }
                int px = vista.img[fi];                         // the old road beyond (see Vista)
                float rw = roadW[i], rk = roadK[i];
                if (rw + rk > 0.012f) {                         // with whatever lamplight reaches it this frame
                    int lc = vista.relight(this, fi, rw, rk, d);
                    if (lc != 0) px = lc;
                }
                float b = shaft[i] * beam;
                if (b > 0.02f) {                                 // light hanging in the air, in dithered rays
                    float u = shaftU[i];
                    int q = (int) (shaft[i] * rays[u <= 0 ? 0 : u >= 1 ? 64 : (int) (u * 64f)] + d + 0.5f);
                    if (q > 0) px = Pal.mix(px, bc, Math.min(0.3f, q * 0.06f));
                }
                out[i] = px;
            }
        }
    }

    /** Vines, mushrooms, reeds, the snail, the eyes, the frog. Draw after resolve(). */
    public void decor() {
        life.decor(this);
        if (!vista.painted) vista.paint(this);
        vista.animate(this);
        drawSlab();
        eggs.bank(this);
        seasons.decor(this);
        lore.inscription(this, rosetta);
        lore.tally(this, days);
        // Eyes in the dark.
        if (eyesT >= 0) {
            float e = eyesT;
            boolean open = e > 0.4f && e < 4.6f && !(e > 1.6f && e < 1.75f) && !(e > 3.1f && e < 3.25f);
            if (open) {
                int ex = eyesX - farOx, ey = eyesY - farOy;
                int c = e < 0.7f || e > 4.3f ? Pal.EMBER : Pal.AMBER;
                farPx(ex, ey, c);
                farPx(ex + 4, ey, c);
            }
        }
        for (int v = 0; v < vineX.length; v++) {
            int len = vineLen[v], top = vineTop[v];
            for (int j = 0; j <= len; j++) {
                int x = vineX[v] + vineSway(v, j);
                px(x, top + j, j % 7 == 3 ? Pal.MOSS_HI : Pal.MOSS);
                if (j % 5 == 2) px(x + ((j / 5) % 2 == 0 ? 1 : -1), top + j, Pal.MOSS_HI);
            }
            px(vineX[v] + vineSway(v, len), top + len + 1, bud(v) ? Pal.MINT : Pal.MOSS_HI);
        }
        // Ivy trailing over the top of the broken wall and down its edge.
        for (int y = drapeTop; y < 64 + Math.min(30, days); y++) {
            int x = jambEdge(y) + (((y * 7) % 5) == 0 ? 1 : 0);
            if (((y * 13) % 9) == 4) continue;                         // gaps, so it reads as strands
            px(x, y, (y % 6 == 2) ? Pal.MOSS_HI : Pal.MOSS);
            if (y % 4 == 1) px(x - 1 - ((y / 4) % 2), y, Pal.MOSS);    // leaves on the wall face
        }
        // Mushrooms on the rubble at the wall's foot, and along the far bank.
        for (int m = 0; m < MUSH.length; m++) mushroom(MUSH[m][0], MUSH[m][1], MUSH[m][2], MUSH[m][3] == 1, m & 1);
        float beatSway = groove > 0.01f ? (float) Math.sin(Math.PI * (beats + beatPh)) * groove * (0.6f + 1.8f * midE * won) : 0f;
        for (int i = 0; i < 9; i++) {
            int x = 6 + i * 19 + (i * 37 % 7);
            int h = 2 + (i * 13 % 4);
            int sway = (int) Math.round(Math.sin(t * 0.9 + i) * 0.6 + beatSway * ((i & 1) == 0 ? 1 : -1));
            for (int k = 0; k < h; k++)
                px(x + (k > h / 2 ? sway : 0), WATER_Y - 1 - k, k == h - 1 ? Pal.MOSS : Pal.MOSS_DK);
        }
        if (frogT >= 0) frog();
    }

    boolean open(int x, int y) {
        return x >= 0 && x < W && y >= 0 && y < WATER_Y && near[y * W + x] == 0;
    }

    /** Paints only where the tunnel is visible; the near wall hides the rest. */
    void farPx(int x, int y, int c) {
        if (open(x, y)) out[y * W + x] = c;
    }

    void mushroom(int x, int stem, int cap, boolean pink, int group) {
        // With music: squash on its group's beat, stretch up between (only in a wonder moment).
        float p = Math.max(pulse(group), bassE * won > 0.75f ? 0.5f : 0f);   // a big bass hit squashes them all
        if (p > 0.25f) {
            int deep = bassE * won > 0.85f && stem > 4 ? 2 : 1;
            stem = Math.max(2, stem - deep);
            cap += 2;
        } else if (won > 0.5f && beatPh > 0.25f && beatPh < 0.75f) stem += highE > 0.6f ? 2 : 1;   // and spring up between
        int top = WATER_Y - 1 - stem;
        int hi = (bloom > 0.3f && ((int) (t * 8) & 1) == 0) || p > 0.6f ? Pal.TEXT : pink ? Pal.PINK : Pal.MINT_HI;
        for (int k = 0; k < stem; k++) px(x, WATER_Y - 1 - k, Pal.MOSS_HI);
        int c = pink ? Pal.PINK : Pal.MINT;
        for (int k = -cap / 2; k <= cap / 2; k++) px(x + k, top, c);
        for (int k = -cap / 2 + 1; k <= cap / 2 - 1; k++) px(x + k, top - 1, hi);
        if (cap >= 5) px(x - 1, top - 1, Pal.MINT_HI);
        px(x - cap / 2, top + 1, Pal.MOSS_HI);
        px(x + cap / 2, top + 1, Pal.MOSS_HI);
    }

    void frog() {
        if (frogAfloat) return;                            // drawn out on the water, after the reflection (frogAfloat())
        float e = frogT;
        int x = Math.round(frogX), y = WATER_Y - 1;
        if (e < 0.5f) {
            y = WATER_Y + 3 - Math.round(e * 8);
        } else if (e > frogSit + 0.5f) {
            float k = Math.min(1f, (e - frogSit - 0.5f) / 0.5f);
            x -= Math.round(k * 8);
            y = WATER_Y - 1 - Math.round((float) Math.sin(Math.PI * k) * 8) + (k > 0.8f ? 3 : 0);
        }
        if (won > 0.5f && e > 0.5f && e < frogSit + 0.5f && (party.mood == Party.DANCE || party.mood == Party.ROCK))
            y -= beatPh < 0.3f ? 3 : beatPh < 0.45f ? 1 : 0;   // hopping to it
        // It watches whoever's nearest: Pip if he's about, else Mote's lamp.
        float watch = dogOut && Math.abs(dog.x - x) < 80 ? dog.x : mote.x;
        int face = watch < x ? -1 : 1;
        int G = Pal.MOSS_HI, D = Pal.MOSS, L = 0xFF7FD08A;
        int[][] art = {
                {0, 1, 0, 0, 0, 1, 0},
                {1, 3, 1, 1, 1, 3, 1},
                {1, 1, 2, 2, 2, 1, 1},
                {4, 1, 1, 1, 1, 1, 4},
                {4, 4, 0, 0, 0, 4, 4}};
        float croak = (e - 0.5f) % 5.5f;
        boolean puff = e > 3.5f && croak > 2.5f && croak < 3.4f;
        if (won > 0.5f && e > 0.5f) puff = pulse() > 0.3f;    // singing along
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 7; c++) {
                int v = art[r][face > 0 ? c : 6 - c];
                if (v == 0) continue;
                int col = v == 1 ? G : v == 2 ? L : v == 3 ? Pal.TEXT : D;
                if (y - 4 + r < WATER_Y) px(x - 3 + c, y - 4 + r, col);
            }
        if (puff) {
            px(x - 1, y - 1, 0xFFB8F0C0);
            px(x, y - 1, 0xFFB8F0C0);
            px(x + 1, y - 1, 0xFFB8F0C0);
            px(x, y, 0xFFB8F0C0);
        }
    }

    /**
     * The frog on its leaf, out on the stream: lower on the screen than the bank (further out in the
     * water), legs tucked under, the leaf showing round its edges, the water lapping at it. It climbs
     * on from below, rides, then slips off into the water.
     */
    void frogAfloat() {
        float e = frogT;
        int x = Math.round(frogX), lane = LANE + Math.round(wh[Math.max(0, Math.min(W - 1, x))] * 0.5f);
        int sink = 0;                                       // rows still under the water
        if (e < 0.5f) sink = 4 - Math.round(e * 8);         // climbing aboard
        else if (e > frogSit + 0.5f) sink = Math.round(Math.min(1f, (e - frogSit - 0.5f) / 0.5f) * 4);   // slipping off
        float watch = dogOut && Math.abs(dog.x - x) < 80 ? dog.x : mote.x;
        int face = watch < x ? -1 : 1;
        int G = Pal.MOSS_HI, L = 0xFF7FD08A;
        int[][] art = {
                {0, 1, 0, 0, 0, 1, 0},
                {1, 3, 1, 1, 1, 3, 1},
                {1, 1, 2, 2, 2, 1, 1},
                {1, 1, 1, 1, 1, 1, 1}};
        float croak = (e - 0.5f) % 5.5f;
        boolean puff = e > 3.5f && croak > 2.5f && croak < 3.4f;
        if (won > 0.5f && e > 0.5f) puff = pulse() > 0.3f;
        int bob = e > 0.5f && e < frogSit + 0.5f && ((int) (t * 1.3f + x) & 3) == 0 ? 1 : 0;   // the leaf rocks a little
        for (int r = 0; r < 4; r++) {
            if (r >= 4 - sink) continue;
            for (int c = 0; c < 7; c++) {
                int v = art[r][face > 0 ? c : 6 - c];
                if (v == 0) continue;
                px(x - 3 + c, lane - 4 + r + sink + bob, v == 1 ? G : v == 2 ? L : Pal.TEXT);
            }
        }
        if (puff && sink == 0) {
            px(x - 1, lane - 2 + bob, 0xFFB8F0C0);
            px(x, lane - 2 + bob, 0xFFB8F0C0);
            px(x + 1, lane - 2 + bob, 0xFFB8F0C0);
        }
        // The water laps at the leaf's edges.
        boolean lap = ((int) (t * 2.2f) & 1) == 0;
        px(x + (lap ? -5 : -6), lane + 1, Pal.COOL[4]);
        px(x + (lap ? 5 : 6), lane + 1, Pal.COOL[4]);
    }

    /** Fireflies, particles, the moth, the fish, the selector, the roots. Draw after the UI. */
    public void actors() {
        for (int i = 0; i < FLIES; i++) {
            float g = glow(i);
            if (g < 0.2f) continue;
            int x = Math.round(fx[i]), y = Math.round(fy[i]);
            int fc = party.flyColor(this, i, g > 0.6f ? Pal.MINT_HI : Pal.MINT);
            px(x, y, fc);
            if (g > 0.75f) {
                int halo = fc == Pal.MINT_HI || fc == Pal.MINT ? Pal.COOL[5] : Pal.mix(fc, Pal.VOID, 0.45f);
                px(x - 1, y, halo);
                px(x + 1, y, halo);
                px(x, y - 1, halo);
                px(x, y + 1, halo);
            }
        }
        for (float[] q : p) {
            if (q[4] <= 0) continue;
            int x = Math.round(q[0]), y = Math.round(q[1]);
            switch ((int) q[5]) {
                case P_DRIP:
                    px(x, y, Pal.MINT_HI);
                    px(x, y - 1, Pal.COOL[5]);
                    break;
                case P_PEBBLE:
                    px(x, y, 0xFF6A7090);
                    px(x + 1, y, 0xFF4F5B7A);
                    break;
                case P_ZED: {
                    int c = q[4] > 1f ? Pal.DIM : Pal.FAINT;
                    px(x, y, c); px(x + 1, y, c); px(x + 2, y, c);
                    px(x + 1, y + 1, c);
                    px(x, y + 2, c); px(x + 1, y + 2, c); px(x + 2, y + 2, c);
                    break;
                }
                case P_SPORE:
                    px(x, y, q[4] > 2f ? Pal.MINT_HI : Pal.COOL[5]);
                    break;
                case P_MOTE:
                    if (x >= 0 && x < W && y >= 0 && y < H && shaft[y * W + x] > 0.3f && ((int) (t * 3 + q[0]) & 3) != 0)
                        px(x, y, shaftWarm ? 0xFFE8C890 : 0xFFB8C8D8);
                    break;
                case P_DROPLET:
                    px(x, y, Pal.COOL[5]);
                    break;
                case P_TRAIL:
                    px(x, y, q[4] > 0.25f ? Pal.AMBER : Pal.EMBER);
                    break;
                case P_NOTE: {                         // a little ♪
                    int c = q[4] > 0.8f ? Pal.MINT_HI : Pal.COOL[5];
                    px(x + 1, y, c); px(x + 2, y, c);
                    px(x + 1, y + 1, c); px(x + 1, y + 2, c);
                    px(x, y + 3, c); px(x + 1, y + 3, c);
                    break;
                }
                case P_RAIN:
                    px(x, y, 0xFF8AA0C0);
                    px(x, y - 1, 0xFF5A7090);
                    break;
                default:
                    px(x, y, Pal.FAINT);
            }
        }
        if (fishT >= 0) {
            float k = fishT / 0.9f;
            int x = Math.round(fishX + k * 22), y = WATER_Y - 2 - Math.round((float) Math.sin(Math.PI * k) * 18);
            int dir = k < 0.5f ? -1 : 1;
            px(x - 2, y + dir, Pal.COOL[4]);
            px(x - 1, y, Pal.COOL[6]);
            px(x, y, 0xFFD8E8F0);
            px(x + 1, y, Pal.COOL[6]);
            px(x + 2, y - dir, Pal.COOL[4]);
            px(x, y - 1, Pal.COOL[5]);
        }
        if (mothT >= 0) {
            int x = Math.round(mothX), y = Math.round(mothY);
            boolean up = ((int) (t * 14) & 1) == 0;
            px(x, y, 0xFFB8AE98);
            px(x - 1, y + (up ? -1 : 0), 0xFFE8E0C8);
            px(x + 1, y + (up ? -1 : 0), 0xFFE8E0C8);
            if (!up) {
                px(x - 1, y + 1, 0xFFB8AE98);
                px(x + 1, y + 1, 0xFFB8AE98);
            }
        }
        party.draw(this);
        if (dogOut) dog.draw(this);
        life.actors(this);
        mote.draw(this);
        roots();
    }

    /** Foreground roots: closest to you, so they move the most. Black, rim-lit by the selector. */
    void roots() {
        int[][] strands = {{306, 26}, {311, 40}, {316, 18}, {300, 14}};
        for (int s = 0; s < strands.length; s++) {
            int x0 = strands[s][0], len = strands[s][1];
            for (int j = -6; j < len; j++) {
                int y = j + rootOy - 2;
                int x = x0 + Math.round((float) Math.sin(j * 0.25f + s * 1.3f) * 2f
                        + (float) Math.sin(t * 0.5f + s) * j / len * 1.5f);
                int thick = j < len / 3 ? 2 : 1;
                for (int k = 0; k < thick; k++) px(x + k, y, Pal.VOID);
                if (y >= 0 && y < H && x >= 0 && x < W && cool[y * W + x] + warm[y * W + x] > 0.2f)
                    px(x - 1, y, Pal.COOL[3]);
            }
        }
        // A dark lip of rock in the near-right corner, over the water.
        for (int y = 148; y < H; y++) {
            int w = 4 + (y - 148) / 2 + (int) (Math.sin(y * 0.7) * 1.5);
            int yy = y + rootOy / 2;
            for (int x = W - w; x < W; x++) px(x, yy, Pal.VOID);
            px(W - w - 1, yy, Pal.COOL[2]);
        }
    }

    /** The pool: a rippling, darkened mirror of everything drawn above it. Draw last. */
    public void pool() {
        for (int y = WATER_Y; y < H; y++) {
            int d = y - WATER_Y;
            float fade = 0.5f + d * 0.028f;
            for (int x = 0; x < W; x++) {
                float slope = (x > 0 && x < W - 1) ? (wh[x + 1] - wh[x - 1]) : 0;
                int sx = x + Math.round(slope * 3f + (d > 1 ? fsin(t * 1.8f + y * 0.9f + x * 0.05f) * 0.7f : 0f));
                int sy = WATER_Y - 1 - d - Math.round(wh[x] * 0.8f);
                sx = sx < 0 ? 0 : sx >= W ? W - 1 : sx;
                sy = sy < 0 ? 0 : sy >= WATER_Y ? WATER_Y - 1 : sy;
                out[y * W + x] = Pal.mix(out[sy * W + sx], gWater, fade > 0.92f ? 0.92f : fade);
            }
        }
        for (int x = 0; x < W; x++) {
            float a = Math.abs(wh[x]);
            boolean glint = Math.sin(x * 0.37f + t * 1.1f) > 0.93f || Math.sin(x * 0.11f - t * 0.7f) > 0.97f;
            boolean beam = shaft[(WATER_Y - 1) * W + x] > 0.3f;
            int c = a > 0.8f ? Pal.MINT : beam ? (shaftWarm ? Pal.WARM[5] : Pal.COOL[5]) : a > 0.25f || glint ? Pal.COOL[4] : Pal.COOL[2];
            out[WATER_Y * W + x] = c;
        }
        life.pool(this);
        if (frogT >= 0 && frogAfloat) frogAfloat();
        seasons.overWater(this);
    }

    // ---------------------------------------------------------------- helpers

    static final float[] SIN = new float[1024];
    static {
        for (int i = 0; i < 1024; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2 / 1024);
    }

    /** Table sine: plenty for wobbles and shimmer. */
    static float fsin(float a) {
        return SIN[(int) (a * (1024 / 6.2831855f)) & 1023];
    }

    public void px(int x, int y, int c) {
        if (x >= 0 && x < W && y >= 0 && y < H) out[y * W + x] = c;
    }

    public void rect(int x, int y, int w, int h, int c) {
        for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) px(i, j, c);
    }

    /** A checkerboard of shadow: dims whatever is behind a panel of text while letting the world show through. */
    public void scrim(int x, int y, int w, int h) {
        for (int j = Math.max(0, y); j < Math.min(H, y + h); j++)
            for (int i = Math.max(0, x); i < Math.min(W, x + w); i++)
                if (((i + j) & 1) == 0) out[j * W + i] = Pal.VOID;
    }

    /** Text with a one-pixel drop shadow, so words stay legible over lit stone. */
    public int text(String s, int x, int y, int scale, int color) {
        return text(s, x, y, scale, 1, color);
    }

    public int text(String s, int x, int y, int scale, int tracking, int color) {
        Font.draw(out, W, H, s, x, y + 1, scale, tracking, Pal.VOID);
        return Font.draw(out, W, H, s, x, y, scale, tracking, color);
    }

    // A line of text that sits behind the creatures but isn't reflected in the water (hints above
    // the bank): remember the band before the creatures are drawn, then after the water, write the
    // text only where no creature drew.
    final int[] band = new int[W * 10], scratch = new int[W * H];
    int bandY = -1;

    public void markBand(int y) {
        bandY = Math.max(0, Math.min(H - 10, y));
        System.arraycopy(out, bandY * W, band, 0, W * 10);
    }

    public void smallBehind(String s, int x, int y, int tracking, int color) {
        if (bandY < 0) return;
        int a = bandY * W, b = a + W * 10;
        java.util.Arrays.fill(scratch, a, b, 0);
        Font.drawMicro(scratch, W, H, s, x, y + 1, tracking, VOID_SHADOW);
        Font.drawMicro(scratch, W, H, s, x, y, tracking, color);
        for (int i = a; i < b; i++) {
            int c = scratch[i];
            if (c != 0 && out[i] == band[i - a]) out[i] = c == VOID_SHADOW ? Pal.VOID : c;
        }
        bandY = -1;
    }

    static final int VOID_SHADOW = 0xFF06060D;           // marks the shadow in the scratch (drawn as VOID)

    /** The small voice: 5-pixel caps, also with a shadow. */
    public int small(String s, int x, int y, int tracking, int color) {
        Font.drawMicro(out, W, H, s, x, y + 1, tracking, Pal.VOID);
        return Font.drawMicro(out, W, H, s, x, y, tracking, color);
    }

    public void splash(float x, float strength) {
        int i = Math.round(x);
        for (int k = -2; k <= 2; k++) {
            int j = i + k;
            if (j >= 0 && j < W) wv[j] += strength * (k == 0 ? 1f : 0.5f);
        }
    }

    /** A splash you can see and hear: ripples, a few droplets, a plip. */
    void splashAt(float x, float strength, float pitch) {
        splash(x, strength);
        int n = Math.round(strength * 2);
        for (int k = 0; k < n; k++)
            spawn(x, WATER_Y - 1, (rnd.nextFloat() - 0.5f) * 0.9f, -0.6f - rnd.nextFloat() * 0.6f, 1f, P_DROPLET);
        if (sound != null) sound.plip(pitch);
    }

    void spawn(float x, float y, float vx, float vy, float life, int kind) {
        for (float[] q : p) {
            if (q[4] > 0) continue;
            q[0] = x; q[1] = y; q[2] = vx; q[3] = vy; q[4] = life; q[5] = kind;
            return;
        }
    }

    void trail(float x, float y) {
        spawn(x, y, (rnd.nextFloat() - 0.5f) * 0.1f, 0.05f, 0.45f, P_TRAIL);
    }

    void zed(float x, float y) {
        spawn(x, y, 0.05f, -0.18f, 2.2f, P_ZED);
    }

    /** Something drops in from above and lands in the pool (used when a file arrives). */
    public void drop(float x) {
        spawn(x, 0, 0, 1.2f, 8f, P_DRIP);
    }
}
