package com.quiet.px;

import java.util.Calendar;

/**
 * The year as it's kept here. The decorations go up when people really start getting ready (over
 * a day or two at most), stay through the day itself, and are gone the day after.
 *
 *   Onam        from Atham to Thiruvonam   the oonjal swing; the pookalam in the shallows, a ring each day (as it's laid)
 *   Vishu       the week before → the day  kani konna in bloom along the vines; the kani lamp on the morning
 *   Monsoon     June → September           drips from the rim, rain-rings, frogs (a season, so it comes and goes slowly)
 *   Christmas   1 Dec → New Year's Day     a Kerala paper star in the opening; fairy lights on the ivy from the 18th
 *   Diwali      Dhanteras → the day        diyas on the bank, a few more each night; fireworks the eve and the night
 *   Halloween   24 → 31 October            pumpkins; more bats
 *   Holi        Holika Dahan → Holi        a bonfire on the bank the eve, clouds of colour on the day
 *   Ramadan     the month → Eid            a lantern in the opening at night; on Eid, fairy lights too
 *   Sankranti   13 → 15 January            kites over the opening by day
 *
 * When Apple Music plays, the decorations join the party: baubles and fairy lights blink on the
 * beat, the swing goes in tempo, lanterns and the star sway, diyas and pumpkins flare, the pookalam's
 * colours turn, kites bob, Holi's colour bursts on every beat, fireworks go off in time.
 *
 * And at night in Christmas week a sleigh may cross the opening: rarely in the run-up (18th–23rd,
 * maybe once a night), every few minutes on Christmas Eve night. Pip stops to watch it go.
 *
 * The moving feasts are worked out from the sky the way the calendars define them (Thiruvonam:
 * the Moon in Shravana at sunrise in the month of Chingam; Vishu: the Sun into sidereal Aries; Holi:
 * the full moon of Phalgun), so the TV keeps up on its own. Diwali and Eid come from a table.
 * Test with debug.quiet.date (MMDD).
 */
final class Seasons {
    static final String[] DIWALI = {"20261108", "20271029", "20281017", "20291105", "20301026", "20311114", "20321102"};
    static final String[] EID = {"20260320", "20270310", "20280226", "20290214", "20300204", "20310124", "20320114"};

    // Today, relative to each festival (Integer.MIN_VALUE: nowhere near it).
    static final int FAR = Integer.MIN_VALUE;
    int onam = FAR, vishu = FAR, diwali = FAR, halloween = FAR, holi = FAR, eid = FAR;
    boolean ramadan, kites, christmas, fairyLights;
    float monsoon;                                         // 0..1
    int cachedYear = -1;
    long onamDay, vishuDay, holiDay;                       // day numbers (days since 1970) for this year

    final java.util.Random rnd = new java.util.Random(7);
    int month, dayOfMonth;
    float sleighT = -1, sleighIn = 20f;                    // Santa's sleigh across the opening
    final float[][] puffs = new float[10][4];              // Holi colour: x, y, age, hue

    void read(Calendar c, int month, int day) {
        this.month = month;
        this.dayOfMonth = day;
        int y = c.get(Calendar.YEAR);
        long today = dayNumber(y, month, day);
        if (y != cachedYear) {
            cachedYear = y;
            onamDay = onamDay(y);
            vishuDay = vishuDay(y);
            holiDay = holiDay(y);
        }
        onam = window(today - onamDay, -9, 0);
        vishu = window(today - vishuDay, -7, 0);
        holi = window(today - holiDay, -1, 0);
        diwali = FAR;
        for (String d : DIWALI) if (d.startsWith(String.valueOf(y))) diwali = window(today - day(d), -2, 0);
        eid = FAR;
        ramadan = false;
        for (String d : EID) {
            long e = day(d);
            if (today >= e - 29 && today < e) ramadan = true;
            int w = window(today - e, 0, 0);
            if (w != FAR) eid = w;
        }
        halloween = month == 10 && day >= 24 ? day - 31 : FAR;
        christmas = month == 12 || (month == 1 && day == 1);
        fairyLights = (month == 12 && day >= 18) || (month == 1 && day == 1);
        kites = month == 1 && day >= 13 && day <= 15;
        // The southwest monsoon: in with the first week of June, out through late September.
        long jun1 = dayNumber(y, 6, 1), oct1 = dayNumber(y, 10, 1);
        if (today < jun1 - 7 || today > oct1 + 7) monsoon = 0;
        else if (today < jun1 + 7) monsoon = (today - (jun1 - 7)) / 14f;
        else if (today > oct1 - 14) monsoon = Math.max(0, (oct1 + 7 - today) / 21f);
        else monsoon = 1;
    }

    static int window(long rel, int from, int to) {
        return rel >= from && rel <= to ? (int) rel : FAR;
    }

    // ---------------------------------------------------------------- the sky's calendar

    static long dayNumber(int y, int m, int d) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d);
        return c.getTimeInMillis() / 86_400_000L;
    }

    static long day(String ymd) {
        return dayNumber(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(4, 6)), Integer.parseInt(ymd.substring(6)));
    }

    /** Julian day for a day number at an hour (UTC). */
    static double jd(long dayNo, double hourUtc) {
        return dayNo + 2440587.5 + hourUtc / 24.0;
    }

    static double moon(double j) {
        double d = j - 2451545.0;
        double L = 218.316 + 13.176396 * d, M = 134.963 + 13.064993 * d, F = 93.272 + 13.229350 * d,
                D = 297.850 + 12.190749 * d, Ms = 357.529 + 0.98560028 * d;
        double lon = L + 6.289 * sin(M) + 1.274 * sin(2 * D - M) + 0.658 * sin(2 * D) + 0.214 * sin(2 * M)
                - 0.186 * sin(Ms) - 0.114 * sin(2 * F);
        return ((lon % 360) + 360) % 360;
    }

    static double sun(double j) {
        double d = j - 2451545.0, g = 357.529 + 0.98560028 * d, q = 280.459 + 0.98564736 * d;
        return (((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360) + 360) % 360;
    }

    static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    /** Lahiri: how far the stars have slipped from the equinox. */
    static double sidereal(double lon, double j) {
        double ayan = 23.85 + (j - 2451545.0) / 365.25 * 0.01397;
        return ((lon - ayan) % 360 + 360) % 360;
    }

    /** Thiruvonam: the Moon in Shravana (280°–293°20') at sunrise, the Sun in Chingam (Leo). The last such day. */
    static long onamDay(int y) {
        long found = dayNumber(y, 8, 26);
        for (long d = dayNumber(y, 8, 1); d < dayNumber(y, 9, 25); d++) {
            double j = jd(d, 0.5);                              // 06:00 in Kerala
            double s = sidereal(sun(j), j), m = sidereal(moon(j), j);
            if (s >= 120 && s < 150 && m >= 280 && m < 293.333) found = d;
        }
        return found;
    }

    /** Vishu: the first day the Sun is in sidereal Aries (Medam 1). */
    static long vishuDay(int y) {
        for (long d = dayNumber(y, 4, 1); d < dayNumber(y, 4, 30); d++) {
            double j = jd(d, 0.5);
            if (sidereal(sun(j), j) < 5) return d;
        }
        return dayNumber(y, 4, 14);
    }

    /** Holi: the day after the full moon of Phalgun (the Sun in Kumbha/Meena). */
    static long holiDay(int y) {
        for (long d = dayNumber(y, 2, 10); d < dayNumber(y, 4, 20); d++) {
            double a = jd(d, 0.5), b = jd(d + 1, 0.5);
            double e1 = ((moon(a) - sun(a)) % 360 + 360) % 360, e2 = ((moon(b) - sun(b)) % 360 + 360) % 360;
            if (e1 < 180 && e2 >= 180) {
                double s = sidereal(sun(a), a);
                if (s >= 315 && s <= 350) return d + 1;
            }
        }
        return dayNumber(y, 3, 10);
    }

    // ---------------------------------------------------------------- the world, dressed for it

    /** Every second or so: the monsoon's drips and frogs, Holi's colour, kites. */
    void tick(Stage s, float dt) {
        Life l = s.life;
        if (monsoon > 0.05f) {
            if (rnd.nextFloat() < dt * 1.6f * monsoon) {     // drips off the rim of the opening
                int x = Life.SKY_X0 + 4 + rnd.nextInt(Life.SKY_X1 - Life.SKY_X0 - 8);
                s.spawn(x, s.openTop(x) + 2, 0, 0.6f, 6f, Stage.P_DRIP);
            }
            if (rnd.nextFloat() < dt * 0.8f * monsoon) s.splash(8 + rnd.nextInt(Stage.W - 16), 0.35f);   // rain-rings
            if (s.frogT < 0) s.frogIn -= dt * 2f * monsoon;                                          // frog weather
        }
        boolean holiDay = holi == 0;
        boolean burst = s.partyOn() ? s.beatNow : rnd.nextFloat() < dt * 1.2f;    // to the music: a burst on every beat
        if (holiDay && l.day > 0.2f && burst) {
            for (float[] p : puffs)
                if (p[2] <= 0) {
                    p[0] = 20 + rnd.nextInt(Stage.W - 40);
                    p[1] = Stage.WATER_Y - 4 - rnd.nextInt(30);
                    p[2] = 0.001f;
                    p[3] = rnd.nextInt(5);
                    break;
                }
        }
        for (float[] p : puffs) if (p[2] > 0 && (p[2] += dt) > 2.4f) p[2] = 0;
        sleigh(s, dt);
    }

    /** 0..1 on the beat (for one of two groups) while the party's on; 0 otherwise. */
    static float beat(Stage s, int group) {
        return s.won > 0.2f ? s.pulse(group) * s.won : 0f;
    }

    /** A sway in time with the music: -1..1, one way per beat. */
    static float sway(Stage s) {
        return s.won > 0.2f ? (float) Math.sin(Math.PI * (s.beats + s.beatPh)) * s.won : 0f;
    }

    /** Christmas Eve night: every few minutes. The nights before (from the 18th): once in a long while. */
    void sleigh(Stage s, float dt) {
        float h = s.life.hour;
        boolean eve = (month == 12 && dayOfMonth == 24 && h >= 20) || (month == 12 && dayOfMonth == 25 && h < 6);
        boolean runUp = month == 12 && dayOfMonth >= 18 && dayOfMonth <= 23 && (h >= 21 || h < 5);
        if (sleighT >= 0) {
            float before = sleighT;
            sleighT += dt / 4.2f;
            if (before < 0.35f && sleighT >= 0.35f && s.dog.state != Dog.SLEEPING && !s.dog.busy()) {
                s.dog.alert = 1f;                                // ears up: what was that?
                s.dog.start(s, Dog.WATCH, 3.5f);
            }
            if (sleighT >= 1) sleighT = -1;
            return;
        }
        if (!eve && !runUp) {
            sleighIn = 20f;                                      // (the first of the night comes soon after dark)
            return;
        }
        if ((sleighIn -= dt) > 0) return;
        sleighT = 0;
        sleighIn = eve ? 360f + rnd.nextFloat() * 360f : 3600f + rnd.nextFloat() * 4 * 3600f;
        if (s.sound != null) s.sound.chime();
        s.mote.cheer();
    }

    void lights(Stage s) {
        float n = s.life.dark;
        if (christmas) {
            float[] st = starAt(s);
            s.light(st[0], st[1], 30, 0.35f + 0.3f * n, true);
            s.light(TREE_X, Stage.WATER_Y - 12, 24, 0.2f + 0.25f * n + 0.35f * beat(s, 0), true);     // the tree's lights
        }
        if (ramadan || eid != FAR) {
            if (n > 0.3f) s.light(LANTERN_X, lanternY(s) + 3, 26, 0.4f * n, true);
        }
        if (vishu == 0) s.light(KANI_X, Stage.WATER_Y - 4, 22, 0.35f + 0.2f * n, true);
        if (holi == -1) {                                    // Holika Dahan: the bonfire, the eve
            if (n > 0.2f || s.life.golden > 0.3f) s.light(BONFIRE_X, Stage.WATER_Y - 6, 50, 0.55f + 0.1f * (float) Math.sin(s.t * 9), true);
        }
        if (onam != FAR) s.light(POOKALAM_X, Stage.WATER_Y + 4, 30, 0.15f * pookalamFade(), true);
        if (diwali != FAR) {                                 // each diya throws its own small, flickering glow
            int k = diwali == -2 ? 4 : diwali == -1 ? 6 : 9;
            for (int i = 0; i < k; i++) {
                float flicker = 0.85f + 0.15f * (float) Math.sin(s.t * 11f + i * 2.3f);
                s.light(106 + i * 11, Stage.WATER_Y - 3, 18, (0.12f + 0.26f * n) * flicker, true);
            }
        }
    }

    static final int POOKALAM_X = 96, KANI_X = 126, BONFIRE_X = 120, LANTERN_X = 252, SWING_X = 214, TREE_X = 188;

    /** In the sky: kites by day, the Christmas star, Ramadan's lantern. Called from Life's sky. */
    void sky(Stage s, Life l) {
        if (sleighT >= 0) drawSleigh(s, l);
        if (kites && l.day > 0.3f) {
            int[][] k = {{205, 9, 0}, {238, 5, 1}, {258, 12, 2}};
            int[] cols = {0xFFFF5C5C, 0xFFFFD040, 0xFF4EA0F0};
            for (int[] kk : k) {
                int x = kk[0] + Math.round((float) Math.sin(s.t * 0.9f + kk[2]) * 3 + sway(s) * 2 * ((kk[2] & 1) == 0 ? 1 : -1)),
                        y = kk[1] + Math.round((float) Math.cos(s.t * 1.3f + kk[2]) * 1.5f - beat(s, kk[2] & 1) * 2);
                int c = cols[kk[2]];
                l.skyPx(s, x, y - 2, c);
                l.skyPx(s, x - 1, y - 1, c); l.skyPx(s, x, y - 1, c); l.skyPx(s, x + 1, y - 1, c);
                l.skyPx(s, x - 2, y, c); l.skyPx(s, x - 1, y, c); l.skyPx(s, x, y, Pal.TEXT); l.skyPx(s, x + 1, y, c); l.skyPx(s, x + 2, y, c);
                l.skyPx(s, x - 1, y + 1, c); l.skyPx(s, x, y + 1, c); l.skyPx(s, x + 1, y + 1, c);
                l.skyPx(s, x, y + 2, c);
                for (int t = 0; t < 4; t++) l.skyPx(s, x + (t & 1), y + 3 + t, 0xFFE8E0C8);              // the tail
                for (int t = 0; t < 30; t += 3) l.skyPx(s, x - t / 4, y + 6 + t, 0xFF8A8478);           // the string, down to someone
            }
        }
    }

    static final String[] SLEIGH = {               // facing right: Santa in the sleigh, the reins, two reindeer
            ".R................a.a....a.a",
            "RWR...............##.....##N",
            "SSSS.SSS.--------####--####.",
            "SSSSSSSS........#.#.#..#.#..",
            "G......G....................",
    };
    static final String[] SLEIGH2 = {              // the other half of the gallop
            ".R................a.a....a.a",
            "RWR...............##.....##N",
            "SSSS.SSS.--------####--####.",
            "SSSSSSSS.........#.#....#.#.",
            "G......G....................",
    };

    void drawSleigh(Stage s, Life l) {
        float k = sleighT;
        int x0 = Life.SKY_X0 - 30 + Math.round(k * (Life.SKY_X1 - Life.SKY_X0 + 40));
        int y0 = 9 - Math.round((float) Math.sin(k * Math.PI) * 5 + beat(s, 0) * 1.5f);   // a gentle arc (bouncing, to music)
        String[] art = ((int) (s.t * 8) & 1) == 0 ? SLEIGH : SLEIGH2;
        for (int r = 0; r < art.length; r++)
            for (int c = 0; c < art[r].length(); c++) {
                char ch = art[r].charAt(c);
                int col;
                switch (ch) {
                    case 'R': col = 0xFFE83A3A; break;                  // Santa
                    case 'W': col = 0xFFF4F0E0; break;                  // his beard
                    case 'a': col = 0xFFD8C8A0; break;                  // antlers
                    case 'N': col = ((int) (s.t * 5) & 1) == 0 ? 0xFFFF3A3A : 0xFFC82A2A; break;   // a red nose
                    case 'G': col = 0xFFFFD040; break;                  // gold runners
                    case '-': col = 0xFF4A4038; break;                  // reins
                    case '#': col = 0xFF8A5A34; break;                  // reindeer
                    case 'S': col = 0xFFB02A2A; break;                  // the sleigh
                    default: continue;
                }
                l.skyPx(s, x0 + c, y0 + r, col);
            }
        for (int t = 1; t < 9; t++) {                                   // gold dust behind
            int tx = x0 - t * 3 + (t & 1), ty = y0 + 3 + Math.round((float) Math.sin(k * Math.PI + t * 0.4f) * 1.5f);
            if (((int) (s.t * 10) + t) % 3 != 0) l.skyPx(s, tx, ty, t < 4 ? 0xFFFFE48A : 0xFFB08830);
        }
    }

    float[] starAt(Stage s) {
        return new float[]{226 + sway(s) * 2f, 34 + (float) Math.sin(s.t * 0.7f) * 1.2f};
    }

    int lanternY(Stage s) {
        return 30 + Math.round((float) Math.sin(s.t * 0.8f) * 1.2f - beat(s, 1) * 2f);
    }

    /** On the stone and the bank (before the creatures). */
    void decor(Stage s) {
        Life l = s.life;
        int y = Stage.WATER_Y - 1;
        // Christmas: a paper star (nakshatram) hung in the opening, its tails stirring.
        if (christmas) {
            float[] st = starAt(s);
            int x = Math.round(st[0]), sy = Math.round(st[1]);
            int top = s.openTop(x);
            for (int yy = Math.max(0, top); yy < sy - 5; yy++) s.px(x, yy, 0xFF5A6078);
            int red = 0xFFE83A3A, warm = 0xFFFFC870;
            int[][] pts = {{0, -5}, {-1, -4}, {1, -4}, {-1, -3}, {1, -3}, {-5, -2}, {-4, -2}, {-3, -2}, {-2, -2}, {-1, -2}, {0, -2}, {1, -2}, {2, -2}, {3, -2}, {4, -2}, {5, -2},
                    {-3, -1}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {2, -1}, {3, -1}, {-2, 0}, {-1, 0}, {0, 0}, {1, 0}, {2, 0},
                    {-3, 1}, {-2, 1}, {2, 1}, {3, 1}, {-4, 2}, {4, 2}};
            for (int[] p : pts) s.px(x + p[0], sy + p[1], Math.abs(p[0]) + Math.abs(p[1]) <= 2 ? warm : red);
            for (int t = 0; t < 6; t++) {                     // paper tails
                int sw = Math.round((float) Math.sin(s.t * 1.5f + t * 0.6f));
                s.px(x - 2 + sw, sy + 3 + t, t % 2 == 0 ? red : warm);
                s.px(x + 2 + sw, sy + 3 + t, t % 2 == 0 ? warm : red);
            }
        }
        // Christmas: a tree on the bank at the road's mouth, baubles twinkling, a star on top; presents from the Eve.
        if (christmas) {
            int x = TREE_X, base = y;
            int g = 0xFF2E7A3E, gd = 0xFF1E5A2E, gl = 0xFF4E9A4E;
            s.px(x, base, 0xFF5A3418);
            s.px(x, base - 1, 0xFF5A3418);
            int[][] tiers = {{2, 8, 6}, {7, 6, 5}, {11, 4, 4}};      // from height, half-width at its foot, rows
            for (int[] t : tiers)
                for (int r = 0; r < t[2]; r++) {
                    int hw = t[1] - r * t[1] / t[2], yy = base - t[0] - r;
                    for (int dx = -hw; dx <= hw; dx++) s.px(x + dx, yy, dx == -hw ? gd : dx == hw ? gd : (dx + r) % 4 == 0 ? gl : g);
                }
            int[] baubles = {0xFFE83A3A, 0xFFFFD040, 0xFF4EA0F0, 0xFFFF8AD0, 0xFFF4F0E0};
            int[][] at = {{-5, 3}, {3, 4}, {-2, 6}, {4, 8}, {-3, 9}, {1, 11}, {-1, 13}, {2, 14}};
            for (int k = 0; k < at.length; k++) {
                boolean on = s.won > 0.2f ? beat(s, k & 1) > 0.2f || ((s.beats + k) & 3) == 0   // on the beat, trading
                        : ((int) (s.t * 1.6f) + k) % 4 != 0;          // twinkling, a few at a time
                s.px(x + at[k][0], base - at[k][1], on ? baubles[k % baubles.length] : Pal.mix(baubles[k % baubles.length], Pal.VOID, 0.5f));
            }
            boolean tw = s.won > 0.2f ? beat(s, 0) > 0.3f : ((int) (s.t * 2) & 1) == 0;   // the star on top
            int ty = base - 16;
            s.px(x, ty - 1, 0xFFFFD040); s.px(x - 1, ty, 0xFFFFD040); s.px(x, ty, tw ? Pal.TEXT : 0xFFFFE070); s.px(x + 1, ty, 0xFFFFD040); s.px(x, ty + 1, 0xFFFFD040);
            if (eggsEve(s)) {                                         // presents, from Christmas Eve
                for (int dx = -9; dx <= -6; dx++) for (int h = 0; h < 3; h++) s.px(x + dx, base - h, h == 1 || dx == -8 ? 0xFFFFD040 : 0xFFE83A3A);
                for (int dx = 5; dx <= 8; dx++) for (int h = 0; h < 2; h++) s.px(x + dx, base - h, dx == 6 ? Pal.TEXT : 0xFF4EA0F0);
            }
        }
        // Fairy lights along the ivy on the broken edge (Christmas, from mid-December; and Eid).
        if (fairyLights || eid != FAR) {
            int[] cols = {0xFFFF5C5C, 0xFF4EF0C8, 0xFFFFD040, 0xFFB08CFF};
            for (int yy = 8, k = 0; yy < 140; yy += 6, k++) {
                if (s.won > 0.2f ? (k + s.beats) % 3 != 0 : ((int) (s.t * 2) + k) % 3 == 0) continue;   // twinkling; chasing to music
                s.px(Stage.jambEdge(yy) - 1, yy, cols[k % cols.length]);
            }
        }
        // Ramadan: a lantern (fanoos) hung in the opening, lit at night.
        if (ramadan || eid != FAR) {
            int ly = lanternY(s), x = LANTERN_X;
            int top = s.openTop(x);
            for (int yy = Math.max(0, top); yy < ly; yy++) s.px(x, yy, 0xFF5A6078);
            boolean lit = l.dark > 0.3f || eid != FAR;
            int metal = 0xFF8A6A3A, glass = lit ? 0xFFFFD890 : 0xFF6A5A48;
            s.px(x, ly, metal);
            s.px(x - 1, ly + 1, metal); s.px(x, ly + 1, metal); s.px(x + 1, ly + 1, metal);
            for (int r = 2; r <= 5; r++) { s.px(x - 2, ly + r, metal); s.px(x - 1, ly + r, glass); s.px(x, ly + r, glass); s.px(x + 1, ly + r, glass); s.px(x + 2, ly + r, metal); }
            s.px(x - 1, ly + 6, metal); s.px(x, ly + 6, metal); s.px(x + 1, ly + 6, metal);
        }
        // Onam: the oonjal, a swing hung from the rim, going gently.
        if (onam != FAR) {
            float a = s.won > 0.2f ? sway(s) * 0.4f : (float) Math.sin(s.t * 1.1f) * 0.25f;   // in tempo when there's music
            int top = s.openTop(SWING_X), len = 58;
            for (int side = -4; side <= 4; side += 8)
                for (int j = 0; j < len; j += 1) {
                    int xx = SWING_X + side + Math.round((float) Math.sin(a) * j);
                    if ((j & 1) == 0) s.px(xx, top + j, 0xFF8A7A5A);
                }
            int sx = SWING_X + Math.round((float) Math.sin(a) * len), sy = top + len;
            for (int dx = -6; dx <= 6; dx++) s.px(sx + dx, sy, 0xFF7A4A22);
            for (int dx = -5; dx <= 5; dx++) s.px(sx + dx, sy + 1, 0xFF5A3418);
            for (int j = 0; j < len; j += 7) s.px(SWING_X - 4 + Math.round((float) Math.sin(a) * j), top + j, 0xFFFFC040);   // marigold on the ropes
        }
        // Vishu: kani konna, golden blossom hanging from the vines (in full bloom the week before).
        if (vishu != FAR) {
            float k = vishu == -7 ? 0.5f : 1f;
            for (int v = 0; v < s.vineX.length; v++)
                for (int j = 3; j <= s.vineLen[v]; j += 4) {
                    if (((v * 31 + j * 7) % 10) / 10f > k) continue;
                    int x = s.vineX[v] + s.vineSway(v, j), yy = s.vineTop[v] + j;
                    if (beat(s, v & 1) > 0.3f) s.px(x, yy - 1, 0xFFFFF0A0);     // shimmering on the beat
                    s.px(x - 1, yy, 0xFFFFD020);
                    s.px(x + 1, yy + 1, 0xFFFFE060);
                    s.px(x, yy + 2, 0xFFE8B010);
                }
            if (vishu == 0) {                                 // the kani: a brass lamp on the bank, lit
                int x = KANI_X;
                s.px(x - 2, y, 0xFFB08830); s.px(x - 1, y, 0xFFD0A040); s.px(x, y, 0xFFD0A040); s.px(x + 1, y, 0xFFD0A040); s.px(x + 2, y, 0xFFB08830);
                s.px(x, y - 1, 0xFFB08830); s.px(x, y - 2, 0xFFB08830); s.px(x - 1, y - 3, 0xFFD0A040); s.px(x, y - 3, 0xFFD0A040); s.px(x + 1, y - 3, 0xFFD0A040);
                boolean f = ((int) (s.t * 7) % 4) != 0;
                s.px(x, y - 4, f ? Pal.AMBER : Pal.EMBER);
                s.px(x, y - 5, 0xFFFFE48A);
                if (beat(s, 0) > 0.3f) s.px(x, y - 6, 0xFFFFF0C0);
            }
        }
        // Diwali: diyas along the bank from Dhanteras, a few more each night, every one lit on the day.
        if (diwali != FAR) {
            int n = diwali == -2 ? 4 : diwali == -1 ? 6 : 9;
            for (int k = 0; k < n; k++) {
                int x = 106 + k * 11;
                s.px(x - 1, y, 0xFF8A4A22); s.px(x, y, 0xFFB0602A); s.px(x + 1, y, 0xFF8A4A22);
                boolean f = ((int) (s.t * 7 + k * 5) % 5) != 0;
                s.px(x, y - 1, f ? Pal.AMBER : Pal.EMBER);
                if (f) s.px(x, y - 2, 0xFFFFE48A);
                if (beat(s, k & 1) > 0.3f) s.px(x, y - 3, 0xFFFFF0C0);   // flames leap on the beat
            }
        }
        // Halloween: pumpkins out for the week (one more on the night itself).
        if (halloween != FAR) {
            int n = halloween == 0 ? 3 : 2;
            int[] px = {152, 199, 128};
            for (int k = 0; k < n; k++) s.eggs.pumpkin(s, px[k], y, s.won > 0.2f ? (beat(s, k & 1) > 0.25f ? 1f : 0.15f) : l.dark);   // grins flash in time
        }
        // Holi: the Holika bonfire on the eves.
        if (holi == -1 && (l.dark > 0.2f || l.golden > 0.3f)) {
            int x = BONFIRE_X;
            for (int dx = -4; dx <= 4; dx++) s.px(x + dx, y, 0xFF4A2A18);
            for (int dx = -3; dx <= 3; dx += 2) s.px(x + dx, y - 1, 0xFF6A3A1A);
            for (int f = 0; f < 7; f++) {
                int fx = x - 2 + rnd.nextInt(5), fh = 2 + rnd.nextInt(6) + Math.round(s.bassE * s.won * 4);   // flaring with the bass
                for (int h = 1; h <= fh; h++) s.px(fx, y - h, h > fh - 2 ? Pal.AMBER : h > fh / 2 ? 0xFFFF8A20 : 0xFFE83A1A);
            }
            if (rnd.nextFloat() < 0.3f) s.spawn(x, y - 7, (rnd.nextFloat() - 0.5f) * 0.2f, -0.5f, 2f, Stage.P_SPORE);   // sparks
        }
    }

    /** Christmas Eve on (the snow days, 24–26, are the Eggs' own). */
    static boolean eggsEve(Stage s) {
        return s.eggs.christmas;
    }

    float pookalamFade() {
        return 1f;
    }

    /** Over the water, after the reflections: Onam's pookalam and Holi's colour. */
    void overWater(Stage s) {
        if (onam != FAR) {
            // One ring for each day from Atham to Thiruvonam, floating in the shallows; wilting after.
            int rings = onam <= 0 ? onam + 10 : 10;
            int[] cols = {0xFFFFD040, 0xFFFF8A20, 0xFFE83A1A, 0xFFF4F0E0, 0xFF9A50C8, 0xFF58A848, 0xFFFF6A9A, 0xFFFFB020, 0xFFE8E070, 0xFFC83030};
            float fade = pookalamFade();
            int cx = POOKALAM_X, cy = Stage.WATER_Y + 5;
            for (int r = rings - 1; r >= 0; r--) {
                float rx = 2.5f + r * 2.6f, ry = 0.7f + r * 0.48f;
                int col = Pal.mix(cols[(r + (s.won > 0.2f ? s.beats : 0)) % cols.length], Pal.VOID, 1 - fade);   // the colours turn in time
                for (int a = 0; a < 64; a++) {
                    double ang = a * Math.PI * 2 / 64;
                    int px = cx + Math.round((float) Math.cos(ang) * rx), py = cy + Math.round((float) Math.sin(ang) * ry);
                    if (py >= Stage.WATER_Y && py < Stage.H) s.px(px, py, col);
                }
            }
            s.px(cx, cy, Pal.mix(0xFFFFE48A, Pal.VOID, 1 - fade));
        }
        for (float[] p : puffs) {                             // Holi: clouds of gulal, drifting and thinning
            if (p[2] <= 0) continue;
            int[] cols = {0xFFFF3A8A, 0xFFFFD020, 0xFF3AC8F0, 0xFF6AE04A, 0xFFB050F0};
            float r = 2 + p[2] * 5, a = Math.min(1f, 1.4f - p[2] * 0.55f);
            for (int k = 0; k < 18; k++) {
                double ang = k * 0.35 + p[3];
                float rr = r * (0.4f + (k % 3) * 0.3f);
                int x = Math.round(p[0] + (float) Math.cos(ang) * rr), yy = Math.round(p[1] - p[2] * 3 + (float) Math.sin(ang) * rr * 0.7f);
                if (x < 0 || x >= Stage.W || yy < 0 || yy >= Stage.H) continue;
                s.out[yy * Stage.W + x] = Pal.mix(s.out[yy * Stage.W + x], cols[(int) p[3]], 0.7f * a);
            }
        }
    }
}
