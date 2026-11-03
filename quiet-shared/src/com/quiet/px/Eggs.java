package com.quiet.px;

import java.util.Calendar;

/**
 * Small surprises, kept quiet. Days of the year the well dresses up for, and (in the real one) a few
 * minutes of the day and remote tricks; in this shared copy those are left out.
 * Test a date with: adb shell setprop debug.quiet.date 1031   ('' to clear)
 */
final class Eggs {
    boolean halloween, diwali, christmas, newYear, wish, lost, midnight, festive;   // (the seasons' dressing is Seasons)
    // Diwali moves with the moon; the next few.
    static final String[] DIWALI = {"20261108", "20271029", "20281017", "20291105", "20301026"};

    // Fireworks over the opening: x, y, age, hue.
    final float[][] bursts = new float[3][4];
    float burstIn = 2f;

    // Keys: when each of the last few presses came.
    final long[] leftAt = new long[5], rightAt = new long[5], backAt = new long[3];

    int month = 1;                                            // 1..12, for the season's colours

    void readClock(Life l) {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH), h = c.get(Calendar.HOUR_OF_DAY),
                min = c.get(Calendar.MINUTE);
        String dbg = Life.debugString("debug.quiet.date");
        if (dbg.length() == 4) {
            m = Integer.parseInt(dbg.substring(0, 2));
            d = Integer.parseInt(dbg.substring(2));
        }
        String ymd = String.format(java.util.Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), m, d);
        month = m;
        halloween = m == 10 && d >= 30;
        diwali = false;
        for (String day : DIWALI) if (day.equals(ymd) || (dbg.length() == 4 && day.endsWith(dbg))) diwali = true;
        christmas = m == 12 && d >= 24 && d <= 26;
        newYear = (m == 12 && d == 31 && h >= 22) || (m == 1 && d == 1 && h < 2);
        wish = false;                                          // (private in this shared copy)
        lost = false;
    }

    void tick(Stage s, float dt) {
        if (wish && s.syncFor <= 0) s.syncFor = 3f;           // the fireflies blink as one all minute
        boolean night = s.life.dark > 0.5f;
        if ((newYear || diwali || festive) && night) {
            if (s.partyOn() && s.beatNow && (s.beats & 1) == 0) burstIn = 0;   // with music: fireworks in time
            burstIn -= dt;
            if (burstIn <= 0) {
                burstIn = 1.2f + s.rnd.nextFloat() * (newYear ? 1.5f : 3f);
                for (float[] b : bursts)
                    if (b[2] <= 0 || b[2] > 1.6f) {
                        b[0] = Life.SKY_X0 + 10 + s.rnd.nextInt(Life.SKY_X1 - Life.SKY_X0 - 20);
                        b[1] = 4 + s.rnd.nextInt(8);
                        b[2] = 0.001f;
                        b[3] = s.rnd.nextInt(4);
                        if (s.sound != null) s.sound.plip(0.15f);
                        break;
                    }
            }
        }
        for (float[] b : bursts) if (b[2] > 0 && b[2] <= 1.6f) b[2] += dt;
    }

    /** Fireworks, seen through the opening. */
    void sky(Stage s, Life l) {
        int[] hues = {0xFFFFB44A, 0xFFFF5C8A, 0xFF4EF0C8, 0xFFC8B8FF};
        for (float[] b : bursts) {
            if (b[2] <= 0 || b[2] > 1.6f) continue;
            float r = b[2] * 9f;
            int col = b[2] > 1.1f ? Pal.mix(hues[(int) b[3]], Pal.VOID, (b[2] - 1.1f) * 2f) : hues[(int) b[3]];
            for (int k = 0; k < 12; k++) {
                double a = k * Math.PI / 6;
                l.skyPx(s, Math.round(b[0] + (float) Math.cos(a) * r), Math.round(b[1] + (float) Math.sin(a) * r + b[2] * b[2] * 3), col);
            }
        }
    }

    /** (The bank's festival dressing is Seasons now.) */
    void bank(Stage s) {
    }

    void pumpkin(Stage s, int x, int y, float dark) {
        int o = 0xFFD8661E, od = 0xFF8A3A12, glow = dark > 0.3f ? 0xFFFFD060 : 0xFF3A1A08;
        for (int dx = -2; dx <= 2; dx++) {
            s.px(x + dx, y, od);
            s.px(x + dx, y - 1, o);
            s.px(x + dx, y - 2, o);
            if (Math.abs(dx) < 2) s.px(x + dx, y - 3, o);
        }
        s.px(x, y - 4, Pal.MOSS);                             // stalk
        s.px(x - 1, y - 2, glow);                             // eyes
        s.px(x + 1, y - 2, glow);
        s.px(x - 1, y - 1, glow);                             // grin
        s.px(x, y - 1, glow);
        s.px(x + 1, y - 1, glow);
    }

    void lights(Stage s) {
        float n = s.life.dark;
        Seasons se = s.seasons;
        if (se.halloween != Seasons.FAR && n > 0.3f) {
            s.light(152, Stage.WATER_Y - 3, 16, 0.3f * n, true);
            s.light(199, Stage.WATER_Y - 3, 16, 0.3f * n, true);
        }
        if (se.diwali != Seasons.FAR) s.light(148, Stage.WATER_Y - 3, 60, (0.25f + 0.2f * n) * (se.diwali == 0 ? 1f : 0.6f), true);
    }

    /** Remote tricks on the home screen (private in this shared copy). */
    void key(Stage s, int code) {
    }

    static boolean quick(long[] at, long now, long within) {
        System.arraycopy(at, 1, at, 0, at.length - 1);
        at[at.length - 1] = now;
        if (at[0] == 0 || now - at[0] > within) return false;
        java.util.Arrays.fill(at, 0);
        return true;
    }
}
