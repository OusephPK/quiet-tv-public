package com.quiet.px;

import java.util.Calendar;
import java.util.Random;

/**
 * Everything that makes the well feel inhabited, plus the sky.
 *
 * The sky is seen through a ragged opening above the arch and follows the real clock: night
 * indigo with stars and the actual phase of the moon, a pink-gold dawn, blue day with the sun
 * tracking the hour, an orange-to-violet dusk. Clouds drift; now and then it rains.
 *
 * The creatures keep their own hours. Birds by day (one sometimes lands on the keystone),
 * bats at dusk and night, butterflies and a dragonfly in daylight, glowworms on the ceiling
 * after dark. A spider lowers itself on a thread, a mouse scurries along the bank, fish glide
 * under the surface, lily pads drift, and the frog sometimes snaps a firefly.
 */
final class Life {
    // The opening to the sky, cut through the top of the near wall above the arch.
    static final int SKY_X0 = 192, SKY_X1 = 270, SKY_Y1 = 15;

    final Random rnd;
    float rainFor, rainIn = 300f, rainSound = 2f;

    // Time of day, continuous: how much daylight, how golden, how dark.
    float day, golden, dark, blue, moonPhase, hour = 12;
    // Weather (real, when we know it: see Weather).
    float flash, flashIn = 12f, thunderIn = -1;
    boolean flashWas;
    final float[][] mist = new float[6][3];       // x, y, speed
    final float[][] flakes = new float[40][3];
    final int[] skyRow = new int[SKY_Y1 + 2];
    final float[][] stars = new float[26][3];
    final float[][] clouds = new float[4][4];   // x, y, w, speed

    // Creatures. Each has a timer until it next appears and a clock while it's out.
    float birdIn = 12f, birdT = -1, birdX, birdY, birdV, birdPerch;
    float batIn = 20f, batT = -1;
    final float[][] bats = new float[5][4];
    float spiderIn = 40f, spiderT = -1, spiderX, spiderLen;
    float mouseIn = 30f, mouseT = -1, mouseX, mouseDir, mousePause;
    // The mouse lives in a hole at the foot of the wall. It peeks out, darts somewhere roomy, sniffs,
    // goes home; freezes when Pip moves near, bolts when he's close; bolder while he sleeps.
    static final float MOUSE_HOLE = 10;
    int mousePhase;                  // 0 peeking, 1 off somewhere, 2 sniffing about, 3 bolting home
    float mouseTx, mouseFrozen;
    boolean mouseParty;
    // The snail: along the foot of the wall, out in the damp, at night and in the morning; slow,
    // and into its shell when anyone comes near.
    float snailX = 160, snailTx = 160, shellFor;
    boolean snailOut;
    float rainedAgo = 99;
    float flyIn = 25f, flyT = -1, flyX, flyY;
    final float[][] butterflies = new float[1][4];
    final float[][] fish = new float[3][3];
    final float[][] pads = new float[3][2];
    float leafX = -1;                                  // a leaf from the road, drifting along our stream
    boolean leafRidden;
    int leafCol;
    float tongueT = -1, tongueX, tongueY;
    final int[][] crystals = new int[9][3];
    final int[][] glowworms = new int[22][2];

    Life(Random rnd) {
        this.rnd = rnd;
        for (float[] s : stars) {
            s[0] = SKY_X0 + 3 + rnd.nextInt(SKY_X1 - SKY_X0 - 6);
            s[1] = 1 + rnd.nextInt(SKY_Y1 - 3);
            s[2] = rnd.nextFloat() * 6.28f;
        }
        for (int i = 0; i < clouds.length; i++) {
            clouds[i][0] = SKY_X0 - 20 + rnd.nextInt(110);
            clouds[i][1] = 2 + rnd.nextInt(7);
            clouds[i][2] = 10 + rnd.nextInt(14);
            clouds[i][3] = 0.6f + rnd.nextFloat() * 1.2f;
        }
        for (int i = 0; i < fish.length; i++) {
            fish[i][0] = rnd.nextInt(Stage.W);
            fish[i][1] = Stage.WATER_Y + 4 + rnd.nextInt(8);
            fish[i][2] = (rnd.nextBoolean() ? 1 : -1) * (3f + rnd.nextFloat() * 4f);
        }
        for (int i = 0; i < pads.length; i++) {
            pads[i][0] = 40 + rnd.nextInt(240);
            pads[i][1] = (rnd.nextFloat() - 0.5f) * 0.6f;
        }
        for (float[] b : butterflies) {
            b[0] = 250 + rnd.nextInt(50);
            b[1] = 130 + rnd.nextInt(20);
            b[2] = rnd.nextFloat() * 6.28f;
            b[3] = rnd.nextBoolean() ? 1 : 0;
        }
        for (int[] c : crystals) {
            c[0] = 4 + rnd.nextInt(312);
            c[1] = 4 + rnd.nextInt(150);
            c[2] = rnd.nextInt(3);
        }
        for (int[] g : glowworms) {
            g[0] = rnd.nextInt(Stage.W);
            g[1] = 1 + rnd.nextInt(10);
        }
        readClock();
    }

    // ---------------------------------------------------------------- time of day

    static String debugString(String prop) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, prop);
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static float debugFloat(String prop) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, prop);
            return v == null || v.isEmpty() ? -1 : Float.parseFloat(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    static float debugHour() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "debug.quiet.hour");
            return v == null || v.isEmpty() ? -1 : Float.parseFloat(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Reads the clock; returns true if the light changed enough to re-bake. */
    boolean readClock() {
        Calendar c = Calendar.getInstance();
        float h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
        float dbg = debugHour();
        if (dbg >= 0) h = dbg;
        h = Math.round(h * 10) / 10f;                 // six-minute steps
        boolean changed = h != hour;
        hour = h;
        day = h > 6 && h < 18.6f ? (float) Math.sin((h - 6) / 12.6f * Math.PI) : 0f;
        golden = Math.max(peak(h, 6.7f, 1.2f), peak(h, 17.9f, 1.3f));
        dark = 1f - Math.min(1f, day * 2.5f + golden * 0.6f);
        blue = Math.max(peak(h, 5.7f, 0.9f), peak(h, 19.2f, 1.0f)) * (1f - golden * 0.6f);
        // Days since a known new moon (6 Jan 2000), over the synodic month.
        long days = (c.getTimeInMillis() - 947182440000L) / 86_400_000L;
        moonPhase = (float) ((days % 29.530588) / 29.530588);
        float dm = debugFloat("debug.quiet.moon");
        if (dm >= 0) moonPhase = dm;
        computeSky();
        return changed;
    }

    static float peak(float h, float at, float width) {
        return Math.max(0f, 1f - Math.abs(h - at) / width);
    }

    /** Sky gradient, top to bottom, from keyframes through the day. */
    void computeSky() {
        float[] hours = {0, 4.8f, 6.0f, 7.2f, 9.5f, 13f, 16.2f, 17.8f, 18.8f, 20f, 24};
        int[][] keys = {
                {0xFF05060F, 0xFF0C1230}, {0xFF0A0A22, 0xFF1C1640}, {0xFF2A2350, 0xFF8A4A62},
                {0xFF4A6AA8, 0xFFF0A070}, {0xFF3478C8, 0xFFA8D4F0}, {0xFF2C70C8, 0xFF98CCF0},
                {0xFF3C6CB8, 0xFFE8C890}, {0xFF2E2A6A, 0xFFF0703A}, {0xFF1C1A48, 0xFF8A3A5A},
                {0xFF0A0C26, 0xFF1E1A48}, {0xFF05060F, 0xFF0C1230}};
        int k = 0;
        while (k < hours.length - 2 && hour >= hours[k + 1]) k++;
        float f = (hour - hours[k]) / (hours[k + 1] - hours[k]);
        int top = Pal.mix(keys[k][0], keys[k + 1][0], f), bottom = Pal.mix(keys[k][1], keys[k + 1][1], f);
        float grey = Math.max(rainFor > 0 ? 0.5f : 0f, Weather.overcast() * 0.45f);
        if (Weather.fog()) grey = Math.max(grey, 0.6f);
        if (grey > 0) {                               // cloud and rain grey the sky out
            int g = Pal.mix(0xFF2A303C, 0xFF8A929E, day);
            top = Pal.mix(top, g, grey);
            bottom = Pal.mix(bottom, Pal.mix(g, 0xFFFFFFFF, 0.12f), grey);
        }
        for (int y = 0; y < skyRow.length; y++) skyRow[y] = Pal.mix(top, bottom, y / (float) SKY_Y1);
    }

    boolean raining() {
        return rainFor > 0;
    }

    // ---------------------------------------------------------------- per frame

    void tick(Stage s, float dt) {
        // Weather: the real weather when we know it, otherwise a rain spell every so often.
        boolean wasRaining = rainFor > 0;
        if (Weather.known()) {
            if (Weather.rain()) rainFor = Math.max(rainFor, 8f);      // keeps raining while it rains outside
            else if (rainFor > 6f) rainFor = 6f;                       // eases off
        } else {
            rainIn -= dt;
            if (rainIn <= 0 && rainFor <= 0) {
                rainFor = 60f + rnd.nextFloat() * 90f;
                rainIn = 420f + rnd.nextFloat() * 600f;
            }
        }
        if ((rainFor > 0) != wasRaining) computeSky();
        storm(s, dt);
        if (s.sound != null) s.sound.rain(rainFor > 0 ? Math.min(1f, rainFor / 6f) : 0f);   // eases out at the end
        if (rainFor > 0) {
            rainFor -= dt;
            int drops = !Weather.known() ? 2 : Weather.heavy() ? 4 : Weather.now() >= 51 && Weather.now() <= 57 ? 1 : 2;
            for (int i = 0; i < drops; i++)
                s.spawn(SKY_X0 + 4 + rnd.nextInt(SKY_X1 - SKY_X0 - 8), SKY_Y1 - 1, -0.3f, 3.2f, 3f, Stage.P_RAIN);
            if (rainFor <= 0) computeSky();
        }
        float gust = 1f + Math.min(2f, Weather.wind / 15f);
        for (float[] c : clouds) {
            c[0] += c[3] * dt * (rainFor > 0 ? 2.5f : 1f) * gust;
            if (c[0] > SKY_X1 + 16) {
                c[0] = SKY_X0 - c[2] - 10;
                c[1] = 2 + rnd.nextInt(7);
            }
        }

        birds(s, dt);
        bats(s, dt);
        spider(s, dt);
        mouse(s, dt);
        snail(s, dt);
        dragonfly(s, dt);
        for (float[] b : butterflies) {
            b[2] += dt;
            b[0] += (float) Math.sin(b[2] * 0.7f + b[3]) * 0.5f;
            b[1] += (float) Math.sin(b[2] * 1.3f) * 0.35f;
            b[0] = Math.max(232, Math.min(312, b[0]));
            b[1] = Math.max(108, Math.min(158, b[1]));
        }
        for (float[] f : fish) {
            f[0] += f[2] * dt;
            if (f[0] < -10 || f[0] > Stage.W + 10) {
                f[2] = -f[2];
                f[1] = Stage.WATER_Y + 4 + rnd.nextInt(8);
            }
        }
        for (float[] p : pads) {
            p[0] += p[1] * dt;
            if (p[0] < 8 || p[0] > 312) p[1] = -p[1];
        }
        if (leafX >= 0 && (leafX -= dt * 2.4f) < -4) leafX = -1;

        // The frog, when it's out, snaps at fireflies that come close.
        if (s.frogT > 0.6f && !s.frogAfloat && tongueT < 0) {
            for (int i = 0; i < Stage.FLIES; i++) {
                if (s.glow(i) < 0.2f) continue;
                float dx = s.fx[i] - s.frogX, dy = s.fy[i] - (Stage.WATER_Y - 3);
                if (dx * dx + dy * dy < 500 && rnd.nextInt(30) == 0) {
                    tongueT = 0;
                    tongueX = s.fx[i];
                    tongueY = s.fy[i];
                    s.fx[i] = Stage.ARCH_CX + rnd.nextInt(40) - 20;      // the firefly blinks away
                    s.fy[i] = 40;
                    if (s.sound != null) s.sound.croak();
                    break;
                }
            }
        }
        if (tongueT >= 0 && (tongueT += dt) > 0.25f) tongueT = -1;

        // Light from the sky opening: daylight, golden hour, or moonlight.
        float sx = (SKY_X0 + SKY_X1) / 2f;
        if (day > 0.05f) {                                // neutral daylight: equal parts of both
            s.light(sx, 6, 110, 0.55f * day, false);
            s.light(sx, 6, 110, 0.55f * day, true);
        }
        if (golden > 0.05f) s.light(sx, 6, 90, 0.6f * golden, true);
        if (dark > 0.5f && moonUp()) s.light(moonX(), 4, 70, 0.3f * dark, false);
        for (float[] b : butterflies) if (day > 0.3f) s.light(b[0], b[1], 6, 0.1f, true);
    }

    boolean moonUp() {
        return hour >= 18.5f || hour < 6.5f;
    }

    float moonX() {
        float t = hour >= 18.5f ? (hour - 18.5f) / 12f : (hour + 5.5f) / 12f;
        return SKY_X0 + 6 + t * (SKY_X1 - SKY_X0 - 12);
    }

    void birds(Stage s, float dt) {
        if (day < 0.2f || rainFor > 0) return;
        birdIn -= dt;
        if (birdIn <= 0 && birdT < 0) {
            birdT = 0;
            birdX = SKY_X0 - 6;
            birdY = 3 + rnd.nextInt(8);
            birdV = 16 + rnd.nextFloat() * 10;
            birdPerch = rnd.nextInt(3) == 0 ? 2.5f + rnd.nextFloat() * 4 : -1;   // sometimes it lands
        }
        if (birdT < 0) return;
        birdT += dt;
        boolean perched = birdPerch > 0 && birdX > Stage.ARCH_CX - 2 && birdPerch > birdT - 3.5f;
        if (birdPerch > 0 && Math.abs(birdX - Stage.ARCH_CX) < 2 && birdT < 3.5f + birdPerch) {
            birdY += (19 - birdY) * Math.min(1f, dt * 4);           // drop onto the keystone
            if (birdT > 3.4f && (int) (birdT * 3) % 4 == 0 && s.sound != null && rnd.nextInt(8) == 0) s.sound.chirp();
            return;
        }
        if (!perched) {
            birdX += birdV * dt;
            if (birdPerch > 0 && birdT > 3.5f + birdPerch) birdY -= 10 * dt;
        }
        if (birdX > SKY_X1 + 8) {
            birdT = -1;
            birdIn = 18f + rnd.nextFloat() * 30f;
        }
    }

    void bats(Stage s, float dt) {
        if (dark < 0.4f && golden < 0.4f) return;
        batIn -= dt * (s.eggs.halloween ? 3f : 1f);
        if (batIn <= 0 && batT < 0) {
            batT = 0;
            for (float[] b : bats) {
                b[0] = Stage.INNER_CX - Stage.PAD / 2f + rnd.nextInt(16) - 8;
                b[1] = Stage.INNER_SPRING - Stage.PAD / 2f + 8 + rnd.nextInt(10);
                b[2] = (rnd.nextFloat() - 0.6f) * 50;
                b[3] = -20 - rnd.nextFloat() * 25;
            }
            if (s.sound != null) s.sound.squeak();
        }
        if (batT < 0) return;
        batT += dt;
        for (float[] b : bats) {
            b[2] += (rnd.nextFloat() - 0.5f) * 140 * dt;
            b[3] += (rnd.nextFloat() - 0.55f) * 120 * dt;
            b[0] += b[2] * dt;
            b[1] += b[3] * dt;
        }
        if (batT > 6f) {
            batT = -1;
            batIn = 35f + rnd.nextFloat() * 50f;
        }
    }

    void spider(Stage s, float dt) {
        spiderIn -= dt;
        if (spiderIn <= 0 && spiderT < 0) {
            spiderT = 0;
            spiderX = rnd.nextBoolean() ? 168 + rnd.nextInt(10) : 308 + rnd.nextInt(6);
            spiderLen = 30 + rnd.nextInt(40);
        }
        if (spiderT < 0) return;
        spiderT += dt;
        if (spiderT > 22f) {
            spiderT = -1;
            spiderIn = 50f + rnd.nextFloat() * 70f;
        }
    }

    float spiderY() {
        float t = spiderT;
        if (t < 6) return spiderLen * (t / 6f);                     // lowering
        if (t < 16) return spiderLen + (float) Math.sin(t * 1.4f) * 2f;  // dangling
        return spiderLen * (1f - (t - 16) / 6f);                   // climbing back
    }

    void mouse(Stage s, float dt) {
        if (mouseT < 0) {
            if (rainFor > 0 && !mouseParty) return;                     // stays in when it rains
            boolean pipAsleep = s.dog.state == Dog.SLEEPING;
            mouseIn -= dt * (dark > 0.5f ? 1.8f : 1f) * (pipAsleep ? 1.6f : 1f) * (mouseParty ? 6f : 1f);
            if (mouseIn > 0) return;
            mouseT = 0;
            mousePhase = 0;
            mouseX = MOUSE_HOLE;
            mouseDir = 1;
            mouseFrozen = 0;
            return;
        }
        mouseT += dt;
        float pip = s.dogOut ? s.dog.x : -999;
        boolean awake = s.dog.state != Dog.SLEEPING;
        float dPip = Math.abs(pip - mouseX);
        boolean close = awake && dPip < 30 && !mouseParty;
        boolean stirring = awake && dPip < 64 && Math.abs(s.dog.vx) > 3 && !mouseParty;
        if (mouseFrozen > 0) mouseFrozen -= dt;
        switch (mousePhase) {
            case 0:                                                   // just its nose out of the hole
                if (mouseT > 1.6f) {
                    if (close || (awake && dPip < 45 && rnd.nextBoolean())) {   // not with him right there
                        mouseT = -1;
                        mouseIn = 20f + rnd.nextFloat() * 40f;
                    } else {
                        mousePhase = 1;
                        mouseTx = target(s);
                    }
                }
                break;
            case 1:
            case 2:
                if (close) {                                          // too near: bolt
                    mousePhase = 3;
                    if (s.sound != null && rnd.nextInt(3) == 0) s.sound.squeak();
                    break;
                }
                if (stirring && mouseFrozen <= 0 && rnd.nextFloat() < dt * 3) mouseFrozen = 0.8f + rnd.nextFloat();   // he moved: freeze
                if (mouseFrozen > 0) {
                    mouseDir = pip > mouseX ? 1 : -1;                 // and watch him
                    break;
                }
                if (mousePhase == 1) {
                    float d = mouseTx - mouseX;
                    mouseDir = d > 0 ? 1 : -1;
                    mouseX += mouseDir * Math.min(Math.abs(d), 34 * dt);
                    if (Math.abs(d) < 0.5f) {
                        mousePhase = 2;
                        mousePause = 1.5f + rnd.nextFloat() * 3.5f;
                        mouseT = 0;
                    }
                } else if (!mouseParty && mouseT > mousePause) {      // sniffed enough: on, or home
                    if (rnd.nextInt(3) == 0) {
                        mousePhase = 1;
                        mouseTx = target(s);
                    } else mousePhase = 3;
                }
                break;
            case 3:                                                   // home, quick
                mouseDir = -1;
                mouseX -= 62 * dt;
                if (mouseX <= MOUSE_HOLE) {
                    mouseT = -1;
                    mouseIn = (mouseParty ? 3f : 30f) + rnd.nextFloat() * 60f;
                }
                break;
        }
    }

    /** Where the mouse goes next: Pip's bowl if he's asleep and there's something in it, else somewhere roomy. */
    float target(Stage s) {
        if (s.dog.state == Dog.SLEEPING && s.bowlFull && s.dogOut) return s.bowlX() + 6;
        float hi = Math.min(Math.max(60, s.dogMax - 20), 170);
        return Ties.roomy(s, 22, hi, mouseX, Ties.MOUSE, 40);
    }

    void snail(Stage s, float dt) {
        if (rainFor > 0) rainedAgo = 0;
        else rainedAgo += dt / 60f;
        boolean wantOut = rainFor > 0 || rainedAgo < 20 || dark > 0.4f || hour < 9.5f || mouseParty;
        if (!snailOut) {
            if (wantOut && rnd.nextFloat() < dt * 0.04f) {
                snailOut = true;
                snailX = Ties.roomy(s, 120, 215, 165, Ties.SNAIL, 30);
                snailTx = snailX;
            }
            return;
        }
        if (!wantOut && shellFor <= 0 && rnd.nextFloat() < dt * 0.01f) {
            snailOut = false;
            return;
        }
        if (Ties.clearance(s, snailX, Ties.SNAIL) < 12) shellFor = 6f + rnd.nextFloat() * 4f;   // someone close: in it goes
        if (shellFor > 0) {
            shellFor -= dt;
            return;
        }
        float d = snailTx - snailX;
        if (Math.abs(d) < 0.3f) snailTx = Ties.roomy(s, Math.max(110, snailX - 30), Math.min(220, snailX + 30), snailX, Ties.SNAIL, 30);
        else snailX += Math.signum(d) * 0.35f * dt * (s.partyOn() && s.party.mood == Party.DANCE ? 10f : 1f);   // (a snail at a rave: it zooms)
    }

    void dragonfly(Stage s, float dt) {
        if (day < 0.3f) return;
        flyIn -= dt;
        if (flyIn <= 0 && flyT < 0) {
            flyT = 0;
            flyX = 330;
            flyY = 150;
        }
        if (flyT < 0) return;
        flyT += dt;
        // Darts between hover points over the water, then leaves.
        float tx = flyT < 14 ? 200 + 60 * (float) Math.sin((int) (flyT / 1.6f) * 2.4f) : 340;
        float ty = flyT < 14 ? 150 + 7 * (float) Math.cos((int) (flyT / 1.6f) * 1.7f) : 120;
        flyX += (tx - flyX) * Math.min(1f, dt * 6);
        flyY += (ty - flyY) * Math.min(1f, dt * 6);
        if (flyT > 17f) {
            flyT = -1;
            flyIn = 30f + rnd.nextFloat() * 40f;
        }
    }

    // ---------------------------------------------------------------- drawing

    /** The sky, glowworms, crystals, webs and ferns. Runs right after the stone is lit. */
    void decor(Stage s) {
        // Sky gradient, dithered so it stays pixel art.
        for (int y = 0; y <= SKY_Y1; y++)
            for (int x = SKY_X0; x <= SKY_X1; x++) {
                int i = y * Stage.W + x;
                if (!s.isSky(i)) continue;
                float d = Pal.BAYER[((y & 3) << 2) | (x & 3)];
                int r = Math.max(0, Math.min(SKY_Y1, y + (d > 0.2f ? 1 : d < -0.2f ? -1 : 0)));
                s.out[i] = skyRow[r];
            }
        float over = Weather.overcast();
        if (flash > 0) {                                  // lightning: the sky goes white
            for (int y = 0; y <= SKY_Y1; y++)
                for (int x = SKY_X0; x <= SKY_X1; x++) skyPx(s, x, y, Pal.mix(skyRow[Math.min(y, SKY_Y1)], 0xFFE8ECFF, flash));
        }
        if (dark > 0.3f && over < 0.7f) {
            for (float[] st : stars) {
                float tw = 0.5f + 0.5f * (float) Math.sin(s.t * 1.7f + st[2]);
                if (s.wonder() > 0.2f) tw = Math.min(1f, tw + s.highE * s.wonder() * (0.5f + 0.5f * (float) Math.sin(s.t * 9f + st[2] * 3f)));
                if (tw * dark < 0.35f) continue;
                skyPx(s, (int) st[0], (int) st[1], tw > 0.8f ? 0xFFFFF6E0 : 0xFF9AA4C8);
            }
        }
        if (moonUp() && dark > 0.2f && over < 0.9f) moon(s);
        if ((day > 0.05f || golden > 0.1f) && over < 0.8f) sun(s);
        int n = !Weather.known() ? clouds.length : Math.max(Weather.overcast() > 0.1f ? 1 : 0, Math.round(Weather.overcast() * clouds.length));
        for (int i = 0; i < n; i++) cloud(s, clouds[i]);
        s.eggs.sky(s, this);
        s.seasons.sky(s, this);
        weatherDecor(s);
        if (birdT >= 0) bird(s);

        // Glowworms on the ceiling, after dark.
        if (dark > 0.4f)
            for (int[] g : glowworms) {
                if (s.isSky(g[1] * Stage.W + g[0])) continue;
                float tw = 0.5f + 0.5f * (float) Math.sin(s.t * 0.9f + g[0]);
                if (tw > 0.45f) s.px(g[0], g[1], tw > 0.85f ? Pal.MINT_HI : Pal.COOL[5]);
            }
        // Crystals in the stone: mostly dark, a slow glint now and then.
        for (int[] c : crystals) {
            int i = c[1] * Stage.W + c[0];
            if (s.near[i] == 0) continue;
            int col = c[2] == 0 ? 0xFF6AD8F0 : c[2] == 1 ? 0xFFE07AC8 : 0xFFB8E86A;
            float g = (float) Math.sin(s.t * 0.6f + c[0] * 0.13f);
            s.px(c[0], c[1], g > 0.97f ? Pal.TEXT : Pal.mix(Pal.VOID, col, 0.35f + 0.25f * g));
            if (g > 0.9f) {
                s.px(c[0] - 1, c[1], Pal.mix(Pal.VOID, col, 0.5f));
                s.px(c[0] + 1, c[1], Pal.mix(Pal.VOID, col, 0.5f));
            }
        }
        // Cobwebs in the top corners of the arch opening.
        web(s, Stage.ARCH_CX - Stage.ARCH_HW + 2, Stage.SPRING - 2, 1);
        web(s, Stage.ARCH_CX + Stage.ARCH_HW - 3, Stage.SPRING - 2, -1);
        // Ferns on a few ledges.
        fern(s, 176, Stage.WATER_Y - 1);
        fern(s, 312, 112);
        fern(s, 188, 40);
    }

    // ---------------------------------------------------------------- real weather

    /** Lightning in a storm: a flash, and a few seconds later the thunder. */
    void storm(Stage s, float dt) {
        flashWas = flash > 0;
        if (flash > 0) flash = Math.max(0f, flash - dt * 5f);
        if (thunderIn > 0 && (thunderIn -= dt) <= 0 && s.sound != null) s.sound.thunder();
        if (!Weather.storm()) return;
        flashIn -= dt;
        if (flashIn <= 0) {
            flash = 1f;
            flashIn = 9f + rnd.nextFloat() * 22f;
            thunderIn = 1f + rnd.nextFloat() * 2.5f;
        }
    }

    /** Mist drifting low over the water, or snow falling through the opening. */
    void weatherDecor(Stage s) {
        if (Weather.fog()) {
            for (int k = 0; k < mist.length; k++) {
                float[] m = mist[k];
                if (m[2] == 0) {
                    m[0] = rnd.nextInt(Stage.W);
                    m[1] = 100 + rnd.nextInt(60);
                    m[2] = 2f + rnd.nextFloat() * 4f;
                }
                m[0] += m[2] * 0.05f;
                if (m[0] > Stage.W + 60) m[0] = -60;
                int col = Pal.mix(0xFF3A4252, 0xFFB8C0CA, day);
                for (int x = -40; x < 40; x++)
                    for (int y = -3; y <= 3; y++) {
                        int px = Math.round(m[0]) + x, py = Math.round(m[1]) + y;
                        float edge = 1f - Math.abs(x) / 40f - Math.abs(y) / 4f;
                        if (px < 0 || px >= Stage.W || py < 0 || py >= Stage.WATER_Y) continue;
                        if (edge > 0.2f && Pal.BAYER[((py & 3) << 2) | (px & 3)] + 0.5f < edge * 0.45f) {
                            int i = py * Stage.W + px;
                            s.out[i] = Pal.mix(s.out[i], col, 0.5f);
                        }
                    }
            }
        }
        if (Weather.snow() || s.eggs.christmas) {
            for (float[] f : flakes) {
                if (f[2] == 0 || f[1] > Stage.WATER_Y) {
                    f[0] = SKY_X0 - 30 + rnd.nextInt(SKY_X1 - SKY_X0 + 60);
                    f[1] = rnd.nextInt(20);
                    f[2] = 0.3f + rnd.nextFloat() * 0.5f;
                }
                f[1] += f[2];
                f[0] += (float) Math.sin(s.t + f[2] * 20) * 0.3f;
                s.px(Math.round(f[0]), Math.round(f[1]), 0xFFE8EEF8);
            }
        }
    }

    void skyPx(Stage s, int x, int y, int c) {
        if (x >= 0 && x < Stage.W && y >= 0 && y < Stage.H && s.isSky(y * Stage.W + x)) s.out[y * Stage.W + x] = c;
    }

    void moon(Stage s) {
        int cx = Math.round(moonX()), cy = 4 + Math.round((float) Math.abs(Math.sin(hour)) * 2);
        // Lit fraction from the phase: a terminator that sweeps across the disc.
        float p = moonPhase;
        for (int y = -3; y <= 3; y++)
            for (int x = -3; x <= 3; x++) {
                if (x * x + y * y > 10) continue;
                float u = x / 3.2f;
                boolean lit = p < 0.5f ? u > 1f - p * 4f : u < 1f - (p - 0.5f) * 4f;
                skyPx(s, cx + x, cy + y, lit ? 0xFFF4EED0 : 0xFF2A3050);
            }
    }

    void sun(Stage s) {
        float t = Math.max(0f, Math.min(1f, (hour - 6f) / 12.6f));
        int cx = Math.round(SKY_X0 + 6 + t * (SKY_X1 - SKY_X0 - 12));
        int cy = 2 + Math.round((1f - day) * 10);
        int core = golden > 0.4f ? 0xFFFF9A50 : 0xFFFFF4C8, halo = golden > 0.4f ? 0xFFF06A3A : 0xFFE8E0A0;
        for (int y = -4; y <= 4; y++)
            for (int x = -4; x <= 4; x++) {
                int d2 = x * x + y * y;
                if (d2 <= 5) skyPx(s, cx + x, cy + y, core);
                else if (d2 <= 13 && ((x + y) & 1) == 0) skyPx(s, cx + x, cy + y, halo);
            }
    }

    void cloud(Stage s, float[] c) {
        int col = rainFor > 0 ? 0xFF4A5060 : dark > 0.6f ? 0xFF161A30 : golden > 0.4f ? 0xFFF0A08A : 0xFFE8EEF4;
        int shade = Pal.mix(col, Pal.VOID, 0.3f);
        int x0 = Math.round(c[0]), y0 = Math.round(c[1]), w = Math.round(c[2]);
        for (int x = 0; x < w; x++) {
            float f = x / (float) w;
            int h = 1 + Math.round((float) Math.sin(f * Math.PI) * 3 + (x % 5 == 2 ? 1 : 0));
            for (int y = 0; y < h; y++) skyPx(s, x0 + x, y0 + 3 - y, y == 0 ? shade : col);
        }
    }

    void bird(Stage s) {
        int x = Math.round(birdX), y = Math.round(birdY);
        boolean flap = ((int) (s.t * 8) & 1) == 0;
        int c = 0xFF1A1E2A;
        if (y >= SKY_Y1) {                                // perched on the keystone, against the stone
            s.px(x, y, 0xFF3A2A20);
            s.px(x + 1, y, 0xFF3A2A20);
            s.px(x + 2, y - 1, 0xFF3A2A20);
            s.px(x + 1, y + 1, 0xFFC8A880);
            s.px(x + 3, y - 1, Pal.AMBER);
            return;
        }
        skyPx(s, x, y, c);
        skyPx(s, x - 1, y + (flap ? -1 : 1), c);
        skyPx(s, x + 1, y + (flap ? -1 : 1), c);
    }

    void web(Stage s, int x, int y, int dir) {
        int c = 0xFF3A4058;
        for (int k = 0; k < 9; k++) s.px(x + dir * k, y - 9 + k / 2, c);
        for (int k = 0; k < 7; k++) s.px(x + dir * k / 2, y - 9 + k, c);
        s.px(x + dir * 3, y - 6, c);
        s.px(x + dir * 5, y - 7, c);
        s.px(x + dir * 2, y - 4, c);
    }

    void fern(Stage s, int x, int y) {
        int[][] leaves = {{0, 0}, {-1, -1}, {1, -1}, {-2, -2}, {0, -2}, {2, -2}, {-1, -3}, {1, -3}, {0, -4}, {-3, -2}, {3, -3}};
        for (int[] l : leaves) s.px(x + l[0], y + l[1], (l[0] + l[1]) % 2 == 0 ? Pal.MOSS : Pal.MOSS_HI);
    }

    /** Creatures that move. Drawn with the other actors, after the UI. */
    void actors(Stage s) {
        if (batT >= 0)
            for (float[] b : bats) {
                int x = Math.round(b[0]), y = Math.round(b[1]);
                boolean up = ((int) (s.t * 14 + b[0]) & 1) == 0;
                int c = 0xFF14101C;
                s.px(x, y, c);
                s.px(x - 1, y + (up ? -1 : 0), c);
                s.px(x + 1, y + (up ? -1 : 0), c);
                s.px(x - 2, y + (up ? -1 : 1), c);
                s.px(x + 2, y + (up ? -1 : 1), c);
            }
        if (spiderT >= 0) {
            int x = Math.round(spiderX), y = Math.round(spiderY());
            if (s.partyOn()) y += Math.round(s.pulse() * (s.party.mood == Party.ROCK || s.party.mood == Party.DANCE ? 4 : 2));
            for (int j = 0; j < y; j++) if ((j & 1) == 0) s.px(x, j, 0xFF5A6078);
            s.px(x, y, 0xFF2A2030);
            s.px(x, y + 1, 0xFF2A2030);
            s.px(x - 1, y, 0xFF3A3040);
            s.px(x + 1, y, 0xFF3A3040);
            s.px(x - 1, y + 2, 0xFF3A3040);
            s.px(x + 1, y + 2, 0xFF3A3040);
            s.px(x - 2, y + 1, 0xFF3A3040);
            s.px(x + 2, y + 1, 0xFF3A3040);
        }
        // The mouse hole, at the foot of the wall.
        {
            int hx = Math.round(MOUSE_HOLE), gy = Stage.WATER_Y - 1;
            for (int dx = -2; dx <= 2; dx++) s.px(hx + dx, gy, 0xFF05050A);
            for (int dx = -2; dx <= 2; dx++) s.px(hx + dx, gy - 1, 0xFF05050A);
            s.px(hx - 1, gy - 2, 0xFF05050A); s.px(hx, gy - 2, 0xFF05050A); s.px(hx + 1, gy - 2, 0xFF05050A);
        }
        if (mouseT >= 0 && mousePhase == 0) {                          // a nose and an ear at the hole
            int x = Math.round(MOUSE_HOLE) + 2, y = Stage.WATER_Y - 1;
            boolean twitch = ((int) (s.t * 7) & 1) == 0;
            s.px(x, y - 1, 0xFF7A7488);
            s.px(x + 1, y - (twitch ? 1 : 0), 0xFFE0A0B0);
            s.px(x - 1, y - 2, 0xFFE0A0B0);
        }
        if (snailOut) {
            int x = Math.round(snailX), y = Stage.WATER_Y - 1;
            boolean bob = s.partyOn() && s.beatPhase() < 0.3f && shellFor <= 0;
            int sy = y - (bob ? 1 : 0);
            int shell = 0xFF8A5A3A, rim = 0xFFC08858, body = 0xFF8A9A88;
            if (shellFor <= 0) {
                s.px(x - 2, y, body); s.px(x - 1, y, body); s.px(x, y, body); s.px(x + 1, y, body); s.px(x + 2, y, body);
                s.px(x + 2, y - 1, body); s.px(x + 3, y - 2, body); s.px(x + 2, y - 3, body);   // eye stalks
            }
            boolean spin = s.partyOn() && s.party.mood == Party.DANCE && ((s.beats & 1) == 0);
            s.px(x - 2, sy - 1, shell); s.px(x - 1, sy - 1, spin ? rim : shell); s.px(x, sy - 1, shell);
            s.px(x - 2, sy - 2, spin ? shell : rim); s.px(x - 1, sy - 2, shell); s.px(x, sy - 2, rim);
            s.px(x - 1, sy - 3, shell);
        }
        if (mouseT >= 0 && mousePhase > 0) {
            int x = Math.round(mouseX), y = Stage.WATER_Y - 1, d = mouseDir > 0 ? 1 : -1;
            boolean stopped = mousePhase == 2 || mouseFrozen > 0;
            boolean sniff = mousePhase == 2 && mouseFrozen <= 0 && ((int) (s.t * 6) & 1) == 0;
            if (stopped && s.partyOn()) {                      // breaking: spins on the beat, little jumps
                int m = s.party.mood;
                if (m == Party.GROOVE || m == Party.DANCE) d = ((s.beats + (s.beatPhase() > 0.5f ? 1 : 0)) & 1) == 0 ? 1 : -1;
                if (m != Party.CHILL && s.beatPhase() < 0.25f) y -= 2;
                sniff = false;
            }
            int g = 0xFF7A7488, gd = 0xFF5A5468;
            s.px(x, y, gd);
            s.px(x + d, y, g);
            s.px(x + 2 * d, y, g);
            s.px(x, y - 1, g);
            s.px(x + d, y - 1, g);
            s.px(x + 2 * d, y - 1, g);
            s.px(x + 3 * d, y - (sniff ? 0 : 1), g);
            s.px(x + 4 * d, y - (sniff ? 0 : 1), 0xFFE0A0B0);   // nose
            s.px(x + 2 * d, y - 2, 0xFFE0A0B0);                 // ear
            s.px(x - d, y, gd);
            s.px(x - 2 * d, y - 1, gd);                          // tail
        }
        if (flyT >= 0) {
            int x = Math.round(flyX), y = Math.round(flyY);
            boolean w = ((int) (s.t * 20) & 1) == 0;
            s.px(x, y, 0xFF2AA8C8);
            s.px(x + 1, y, 0xFF2AA8C8);
            s.px(x + 2, y, 0xFF1A7890);
            s.px(x + 3, y, 0xFF1A7890);
            s.px(x, y - 1, w ? 0xFFC8E8F0 : 0xFF8AB0C0);
            s.px(x + 1, y - 1, w ? 0xFFC8E8F0 : 0xFF8AB0C0);
        }
        if (day > 0.3f)
            for (float[] b : butterflies) {
                int x = Math.round(b[0]), y = Math.round(b[1]);
                boolean open = ((int) (s.t * 7 + b[2]) & 1) == 0;
                int c = b[3] > 0 ? 0xFFFFB44A : 0xFFE89AD8;
                s.px(x, y, 0xFF2A2018);
                s.px(x - 1, y - (open ? 1 : 0), c);
                s.px(x + 1, y - (open ? 1 : 0), c);
                if (open) {
                    s.px(x - 1, y, c);
                    s.px(x + 1, y, c);
                }
            }
        if (tongueT >= 0) {
            float fx = s.frogX, fy = Stage.WATER_Y - 3, k = 1f - Math.abs(tongueT - 0.12f) / 0.13f;
            for (int i = 0; i <= 10; i++) {
                float f = i / 10f * Math.max(0, k);
                s.px(Math.round(fx + (tongueX - fx) * f), Math.round(fy + (tongueY - fy) * f), 0xFFE07A90);
            }
        }
    }

    /** Things in and on the water. Drawn after the reflection. */
    void pool(Stage s) {
        for (float[] f : fish) {
            int x = Math.round(f[0]), y = Math.round(f[1]), d = f[2] > 0 ? 1 : -1;
            for (int k = -3; k <= 3; k++) darken(s, x + k * d, y, k == 3 || k == -3 ? 0.25f : 0.45f);
            darken(s, x - 4 * d, y - 1, 0.3f);
            darken(s, x - 4 * d, y + 1, 0.3f);
        }
        if (leafX >= 0) {                                 // out in the drift lane, below the bank's reflection line
            int x = Math.round(leafX), y = Stage.LANE + Math.round(s.wh[Math.max(0, Math.min(Stage.W - 1, x))] * 0.5f);
            int dk = Pal.mix(leafCol, Pal.VOID, 0.35f), hi = Pal.mix(leafCol, Pal.TEXT, 0.2f);
            for (int k = -2; k <= 2; k++) s.px(x + k, y, leafCol);
            for (int k = -1; k <= 1; k++) s.px(x + k, y - 1, k == 0 ? hi : leafCol);
            s.px(x - 3, y, dk);                           // the tip, and the stem trailing behind
            s.px(x + 3, y, dk);
            s.px(x + 4, y + 1, dk);
            s.px(x, y + 1, Pal.mix(leafCol, Pal.VOID, 0.6f)); // its shadow on the water
        }
        for (float[] p : pads) {
            int x = Math.round(p[0]), y = Stage.WATER_Y + Math.round(s.wh[Math.max(0, Math.min(Stage.W - 1, x))] * 0.5f);
            for (int k = -2; k <= 2; k++) s.px(x + k, y, Pal.MOSS);
            s.px(x - 1, y - 1, Pal.MOSS_HI);
            s.px(x + 1, y - 1, Pal.MOSS_HI);
            s.px(x, y - 1, Pal.MOSS);
        }
    }

    void darken(Stage s, int x, int y, float a) {
        if (x < 0 || x >= Stage.W || y < Stage.WATER_Y + 1 || y >= Stage.H) return;
        int i = y * Stage.W + x;
        s.out[i] = Pal.mix(s.out[i], Pal.VOID, a);
    }

    /**
     * Rain hitting the pool ripples it everywhere; you hear it as a soft bed (Ambience), with
     * just the odd drop picked out now and then, never a patter of plips.
     */
    void rainDrop(Stage s, float x) {
        s.splash(x, 0.6f);
        rainSound -= 1;
        if (rainSound <= 0 && s.sound != null) {
            rainSound = 40 + rnd.nextInt(60);
            s.sound.rainDrop();
        }
    }
}
