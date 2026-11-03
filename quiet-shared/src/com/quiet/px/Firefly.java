package com.quiet.px;

/**
 * Mote — a firefly who lives in the well and keeps you company.
 *
 * Mote hovers beside whatever you've selected, its tail lamp lighting the stone. It glides
 * (never teleports), leaves a little ember trail, glances around, does the odd loop when bored,
 * gives a happy flash when you pick something, curls up to sleep when the TV is left alone,
 * and wakes with a start — "!" — when you come back. Left alone a while, it sometimes flies right
 * up to the glass (growing as it comes), taps it twice, and drifts back: it knows you're there. When music plays it dances: a hop on every
 * beat, a turn every four, a loop every sixteen, its lamp pulsing in time.
 */
public final class Firefly {
    // k body · t back highlight · h head · E eye · w wing · v wing shade · L glow · l glow edge · a antenna
    static final String[] UP = {
            ".....ww.ww...",
            "....wvwwvww.a",
            ".....wwwww.a.",
            "..lktttkhhhh.",
            ".lLkkkkkhhEh.",
            "lLLLkkkkhhhh.",
            ".lLlkkkkkhh..",
            "..l..k..k....",
    };
    static final String[] DOWN = {
            ".............",
            "............a",
            "...........a.",
            "..lkwwwwhhhh.",
            ".lLwwvwwwhEh.",
            "lLLLkwwwkhhh.",
            ".lLlkkkkkhh..",
            "..l..k..k....",
    };
    static final String[] SLEEP = {
            ".............",
            ".............",
            ".............",
            "..lwwwwwhhh..",
            ".lLwwvwwhhhh.",
            "lLLLkkkkkhhha",
            ".lLlkkkkkhh..",
            "...k..k..k...",
    };

    public float x, y;                 // centre of the body
    float vx, vy, tx, ty;
    boolean placed;
    public boolean asleep;
    float lookFor;                  // glancing at Pip
    public int face = 1;
    float faceHold;                    // seconds a requested facing sticks
    float still;                       // seconds since it last moved
    float loop = -1;                   // 0..1 while doing a bored little loop
    float glance, glanceIn = 3f;       // eye looking up
    float joy;                         // happy flash after a selection
    float alert;                       // "!" after waking
    float zzz, trailT;
    float glassT = -1, glassIn = 120f;            // coming up to the glass (see update)

    /** Fly to a new spot. The very first placement just appears there. */
    public void perch(float px, float py) {
        if (!placed) {
            x = tx = px;
            y = ty = py;
            placed = true;
            return;
        }
        if (Math.abs(px - tx) > 0.5f || Math.abs(py - ty) > 0.5f) {
            glassT = -1;                                   // you moved: back to work
            tx = px;
            ty = py;
            still = 0;
            loop = -1;
        }
    }

    public boolean moving() {
        return vx * vx + vy * vy > 400f;
    }

    public void look(int dir) {
        face = dir;
        faceHold = 1.5f;
    }

    /** A little celebration when something is chosen. */
    public void cheer() {
        joy = 1f;
    }

    public void sleep(boolean s) {
        if (asleep && !s) alert = 0.7f;
        asleep = s;
    }

    void update(Stage s, float dt) {
        // A critically damped spring towards the target: quick, with a soft landing.
        float gx = tx, gy = ty + (asleep ? 2 : 0);
        if (asleep && s.dogOut && s.dog.state == Dog.SLEEPING) {     // curls up on Pip's back
            gx = s.dog.x - s.dog.face * 2;
            gy = Dog.ground() - 8;
        }
        // Now and then, left alone, Mote comes right up to the glass and taps on it.
        if (glassT < 0 && !asleep && !s.music && still > 20 && loop < 0 && s.wakeT < 0) {
            glassIn -= dt;
            if (glassIn <= 0) {
                glassT = 0;
                glassIn = 150f + s.rnd.nextFloat() * 180f;
                still = -40;
            }
        }
        if (glassT >= 0) {
            float before = glassT;
            glassT += dt;
            float e = glassEase();
            gx = gx + (160 - gx) * e;
            gy = gy + (84 - gy) * e;
            for (float tap : TAPS)
                if (before < tap && glassT >= tap && s.sound != null) s.sound.plip(1f);   // tink
            if (glassT > 4.2f) glassT = -1;
        }
        // At a party, around its perch: eights for chill, side-steps for a groove, a zigzag for rock,
        // a flutter for bright songs (dance songs get loops, below). Small enough it never loses its place.
        if (s.partyOn() && !asleep && glassT < 0 && !moving()) {
            float w = s.wonder();
            switch (s.party.mood) {
                case Party.CHILL:
                    gx += (float) Math.sin(s.t * 0.9f) * 5 * w;
                    gy += (float) Math.sin(s.t * 1.8f) * 2.5f * w;
                    break;
                case Party.GROOVE:
                    gx += ((s.beats & 1) == 0 ? -3 : 3) * w;
                    break;
                case Party.ROCK:
                    gx += (((int) (s.beatPhase() * 4) & 1) == 0 ? -4 : 4) * w;
                    gy -= s.beatPhase() < 0.2f ? 3 * w : 0;
                    break;
                case Party.BRIGHT:
                    gx += (float) Math.sin(s.t * 2.3f) * 3 * w;
                    gy += (float) Math.sin(s.t * 7f) * 1.5f * w;
                    break;
            }
        }
        if (loop >= 0) {
            loop += dt / (s.partyOn() && s.party.mood == Party.DANCE ? 0.9f : 1.4f);
            float a = loop * 6.283f;
            gx += (float) Math.sin(a) * 7;
            gy += (1 - (float) Math.cos(a)) * -5;
            if (loop >= 1) loop = -1;
        }
        if (dt == 0) return;
        float k = 90f, d = 2f * (float) Math.sqrt(k);
        for (float left = dt; left > 0; left -= 0.02f) {      // small steps keep the spring stable
            float h = Math.min(0.02f, left);
            vx += ((gx - x) * k - vx * d) * h;
            vy += ((gy - y) * k - vy * d) * h;
            x += vx * h;
            y += vy * h;
        }

        boolean dancing = s.music && !asleep && !moving();
        int every = s.partyOn() && s.party.mood == Party.DANCE ? 3 : 15;
        if (dancing && s.beatNow && (s.beats & every) == 0 && loop < 0) loop = 0;   // a twirl every 16 beats (4 to dance music)
        if (dancing && loop >= 0 && s.party.mood == Party.DANCE && s.partyOn()) {   // and a light trail as it goes
            trailT -= dt;
            if (trailT <= 0) {
                trailT = 0.03f;
                s.trail(x - 4 * face, y);
            }
        }
        if (faceHold > 0) faceHold -= dt;
        else if (vx > 25) face = 1;
        else if (vx < -25) face = -1;
        else if (dancing) face = ((s.beats >> 2) & 1) == 0 ? 1 : -1;   // turns every four beats
        else if (lookFor > 0 && s.dogOut) face = s.dog.x > x ? 1 : -1;   // a look down at Pip
        else if (!moving()) face = 1;   // at rest it faces the words it's reading

        if (moving()) {
            trailT -= dt;
            if (trailT <= 0) {
                trailT = 0.03f;
                s.trail(x - 4 * face, y);
            }
        } else if (!asleep) {
            still += dt;
            if (still > 24f && loop < 0 && !s.music) {
                loop = 0;
                still = -s.rnd.nextFloat() * 20f;
            }
        }
        glanceIn -= dt;
        if (glanceIn <= 0) {
            glance = 0.9f;
            glanceIn = 3f + s.rnd.nextFloat() * 6f;
        }
        if (glance > 0) glance -= dt;
        if (joy > 0) joy -= dt * 2.5f;
        if (lookFor > 0) lookFor -= dt;
        else if (!moving() && !asleep && still > 4 && s.rnd.nextFloat() < dt * 0.07f) lookFor = 1.5f + s.rnd.nextFloat() * 2f;
        if (alert > 0) alert -= dt;
        if (asleep) {
            zzz += dt;
            if (zzz > 1.6f) {
                zzz = 0;
                s.zed(x + 3, y - 8);
            }
        }
    }

    /** One pixel of the sprite, as a sc×sc block (Mote grows as it comes up to the glass). */
    void put(Stage s, int cx, int top, int w, int c, int r, int sc, int col) {
        int bx = face > 0 ? cx + (c - w / 2) * sc : cx - (c - w / 2) * sc - (sc - 1);
        for (int dy = 0; dy < sc; dy++)
            for (int dx = 0; dx < sc; dx++) s.px(bx + dx, top + r * sc + dy, col);
    }

    static boolean filled(String[] art, int r, int c) {
        return r >= 0 && r < art.length && c >= 0 && c < art[r].length() && art[r].charAt(c) != '.';
    }

    float pulse(Stage s) {
        if (s.music && !asleep) {
            float k = 1f - s.beatPhase();
            return 0.72f + 0.28f * k * k;          // a flash on every beat
        }
        return asleep ? 0.5f + 0.5f * (float) Math.sin(s.t * 1.2) : 0.85f + 0.15f * (float) Math.sin(s.t * 5.0);
    }

    void light(Stage s) {
        float lx = x - 5 * face, ly = y + 1;
        float p = pulse(s);
        if (asleep) s.light(lx, ly, 28, 0.35f * p, true);
        else s.light(lx, ly, 54 + joy * 30, (0.9f + joy * 0.8f) * p, true);
    }

    static final float[] TAPS = {2.0f, 2.45f};

    /** 0 at its spot, 1 right up against the glass. */
    float glassEase() {
        if (glassT < 0) return 0;
        float e = glassT < 1.2f ? glassT / 1.2f : glassT < 3f ? 1f : Math.max(0f, 1f - (glassT - 3f) / 1.2f);
        return e * e * (3 - 2 * e);
    }

    void draw(Stage s) {
        int sc = 1 + Math.round(2.4f * glassEase());          // nearer the glass, bigger
        String[] art = asleep ? SLEEP : (moving() ? ((int) (s.t * 22) & 1) == 0 : ((int) (s.t * 7) & 1) == 0) ? UP : DOWN;
        int bob;
        if (asleep) bob = 0;
        else if (s.music && !moving()) {
            float b = s.beatPhase();
            bob = b < 0.25f ? -2 : b < 0.45f ? -1 : 0;    // hop on the beat, settle between
        } else bob = Math.round((float) Math.sin(s.t * 3.1) * 1.2f);
        int cx = Math.round(x), top = Math.round(y) - 4 * sc + bob;
        int w = art[0].length(), h = art.length;
        boolean bright = pulse(s) > 0.9f || joy > 0;
        // A one-pixel dark outline first, so Mote reads against any wall.
        for (int r = -1; r <= h; r++)
            for (int c = -1; c <= w; c++) {
                if (filled(art, r, c)) continue;
                if (filled(art, r - 1, c) || filled(art, r + 1, c) || filled(art, r, c - 1) || filled(art, r, c + 1))
                    put(s, cx, top, w, c, r, sc, Pal.VOID);
            }
        for (int r = 0; r < h; r++) {
            String row = art[r];
            for (int c = 0; c < w; c++) {
                char ch = row.charAt(c);
                int col;
                switch (ch) {
                    case 'k': col = Pal.F_BODY; break;
                    case 't': col = Pal.F_BACK; break;
                    case 'h': case 'a': col = Pal.F_HEAD; break;
                    case 'E': col = glance > 0 ? Pal.F_HEAD : Pal.TEXT; break;
                    case 'w': col = Pal.F_WING; break;
                    case 'v': col = Pal.F_WING_DK; break;
                    case 'L': col = asleep ? Pal.EMBER : s.doorOpen ? (bright ? 0xFFFFE0A0 : Pal.AMBER)   // the Keeper's flame
                            : bright ? Pal.F_GLOW_HI : Pal.F_GLOW;
                        if (!asleep && s.partyOn() && s.party.mood == Party.DANCE)                   // a disco lamp
                            col = new int[]{Pal.F_GLOW_HI, Pal.PINK, Pal.MINT_HI, Pal.AMBER}[s.beats & 3];
                        break;
                    case 'l': col = asleep ? 0xFF5A2A1A : Pal.AMBER; break;
                    default: continue;
                }
                put(s, cx, top, w, c, r, sc, col);
                if (ch == 'E' && glance > 0) put(s, cx, top, w, c, r - 1, sc, Pal.TEXT);   // glancing up
            }
        }
        if (!asleep && s.seasons.christmas && sc == 1) {         // a tiny Santa hat, on its head
            put(s, cx, top, w, 8, 2, 1, Pal.TEXT);
            put(s, cx, top, w, 9, 2, 1, Pal.TEXT);
            put(s, cx, top, w, 10, 2, 1, Pal.TEXT);
            put(s, cx, top, w, 9, 1, 1, 0xFFE83A3A);
            put(s, cx, top, w, 8, 1, 1, 0xFFE83A3A);
            put(s, cx, top, w, 8, 0, 1, 0xFFE83A3A);
            put(s, cx, top, w, 7, 0, 1, Pal.TEXT);
        }
        // The taps: a ripple on the glass where its nose touches.
        for (float tap : TAPS) {
            float k = glassT - tap;
            if (k < 0 || k > 0.35f) continue;
            float rr = 3 + k * 46;
            int nx = cx + face * 6 * sc, ny = top + 4 * sc;
            for (int a = 0; a < 28; a++) {
                double ang = a * Math.PI * 2 / 28;
                int qx = nx + Math.round((float) Math.cos(ang) * rr), qy = ny + Math.round((float) Math.sin(ang) * rr * 0.8f);
                if (qx >= 0 && qx < Stage.W && qy >= 0 && qy < Stage.H)
                    s.out[qy * Stage.W + qx] = Pal.mix(s.out[qy * Stage.W + qx], Pal.TEXT, 0.55f * (1 - k / 0.35f));
            }
        }
        if (s.eggs.lost && !asleep) {         // (a private minute): not found
            int qx = cx + (face > 0 ? 1 : -2), qy = top - 7;
            s.px(qx, qy, Pal.TEXT);
            s.px(qx + 1, qy, Pal.TEXT);
            s.px(qx + 2, qy + 1, Pal.TEXT);
            s.px(qx + 1, qy + 2, Pal.TEXT);
            s.px(qx + 1, qy + 4, Pal.TEXT);
        }
        if (alert > 0) {                      // "!" — woken with a start
            int ax = cx + (face > 0 ? 2 : -3);
            for (int r = 0; r < 3; r++) s.px(ax, top - 7 + r, Pal.TEXT);
            s.px(ax, top - 3, Pal.TEXT);
        }
        if (joy > 0.5f) {                     // a ring of sparks
            float r = (1f - joy) * 16f + 4f;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4 + s.t;
                s.px(Math.round(x - 4 * face + (float) Math.cos(a) * r), Math.round(y + (float) Math.sin(a) * r), Pal.F_GLOW_HI);
            }
        }
    }
}
