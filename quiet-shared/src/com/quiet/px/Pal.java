package com.quiet.px;

/**
 * "Wellspring" — the one palette every Quiet screen is drawn from.
 * Stone is never coloured directly: it picks a step from a ramp based on the light that reaches it,
 * so the whole scene stays on-palette however the lights move.
 */
public final class Pal {
    public static final int VOID = 0xFF06060C;

    // Emissive colours: things that glow or are UI, unaffected by lighting.
    public static final int TEXT = 0xFFF4EEDC;
    public static final int SOFT = 0xFF9298B0;   // names at rest
    public static final int DIM = 0xFF6A7090;
    public static final int FAINT = 0xFF363B52;
    public static final int AMBER = 0xFFFFB44A;
    public static final int EMBER = 0xFFC8641E;
    public static final int MINT = 0xFF4EF0C8;
    public static final int MINT_HI = 0xFFC8FFF0;
    public static final int PINK = 0xFFFF5C8A;
    public static final int MOSS_DK = 0xFF12302C;
    public static final int MOSS = 0xFF1F5A48;
    public static final int MOSS_HI = 0xFF2F8A64;
    public static final int WATER = 0xFF07121C;


    // Seven-step ramps for lit stone: neutral, lamp-lit (warm), glow-lit (cool).
    public static final int[] STONE = {
            0xFF06060C, 0xFF0C0C18, 0xFF141626, 0xFF1D2135, 0xFF282E47, 0xFF39425E, 0xFF4F5B7A};
    public static final int[] WARM = {
            0xFF06060C, 0xFF120C10, 0xFF22141A, 0xFF351D1E, 0xFF4F2C22, 0xFF73432A, 0xFF9C6234};
    public static final int[] COOL = {
            0xFF06060C, 0xFF08101A, 0xFF0C1C24, 0xFF12292F, 0xFF1A3B3F, 0xFF245352, 0xFF33716A};

    // The far tunnel: fogged, blue-grey, never as bright as the near wall.
    public static final int[] FAR = {
            0xFF06060C, 0xFF0A0B15, 0xFF0E111E, 0xFF131829, 0xFF1A2135, 0xFF222B43, 0xFF2C3753};

    // Mote the firefly.
    public static final int F_BODY = 0xFF3B2A20;
    public static final int F_BACK = 0xFF6A4E36;
    public static final int F_HEAD = 0xFF4E3828;
    public static final int F_WING = 0xFFBFD8E0;
    public static final int F_WING_DK = 0xFF7F9EAA;
    public static final int F_GLOW = 0xFFFFE48A;
    public static final int F_GLOW_HI = 0xFFFFF6C8;

    /** 4×4 Bayer matrix, centred on zero, for ordered dithering. */
    public static final float[] BAYER = new float[16];
    static {
        int[] m = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        for (int i = 0; i < 16; i++) BAYER[i] = (m[i] + 0.5f) / 16f - 0.5f;
    }

    public static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private Pal() {}
}
