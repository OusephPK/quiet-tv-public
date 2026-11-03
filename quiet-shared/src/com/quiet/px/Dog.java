package com.quiet.px;

/**
 * Pip — a brown miniature pinscher with a teal collar, who lives on the bank under the list.
 *
 * Pip keeps himself busy: prances up and down (that high-stepping min pin trot), sits, sniffs,
 * drinks from the pool, scratches an ear, play-bows, and plays with his bone — carrying it about,
 * tossing it and pouncing, or lying down to chew it. He chases the mouse, leaps to snap at
 * fireflies that drift low, and watches the frog. When music plays he sits and nods along to
 * the beat, tail going, little notes rising. When the TV is left alone he curls up and sleeps
 * with Mote, and wakes when you come back.
 */
final class Dog {
    // B body · H highlight · S shade · E eye · N nose · C collar · T tag · M open mouth. Facing right.
    static final String[] STAND = {
            "...........S..S..",
            "...........BS.BS.",
            "...........BBBBB.",
            "..........BBHBEB.",
            "..........BBBBBBN",
            "S........CCTBSSS.",
            "BS.HHHHHHBBB.....",
            "SBHBBBBBBBBB.....",
            ".BBBBBBBBBBS.....",
            ".BBSSSSSSBBS.....",
            ".BS......BS......",
            ".BS......BS......",
            ".SS......SS......",
    };
    static final String[] WALK1 = {
            "...........S..S..",
            "...........BS.BS.",
            "...........BBBBB.",
            "..........BBHBEB.",
            "..........BBBBBBN",
            "S........CCTBSSS.",
            "BS.HHHHHHBBB.....",
            "SBHBBBBBBBBB.....",
            ".BBBBBBBBBBS.....",
            ".BBSSSSSSBBSB....",
            "B..S.....B..BS...",
            "B...S....B.......",
            "S....S...S.......",
    };
    static final String[] WALK2 = {
            "...........S..S..",
            "...........BS.BS.",
            "...........BBBBB.",
            "..........BBHBEB.",
            "..........BBBBBBN",
            "S........CCTBSSS.",
            "BS.HHHHHHBBB.....",
            "SBHBBBBBBBBB.....",
            ".BBBBBBBBBBS.....",
            ".BBSSSSSSBBS.....",
            "..BS.....BS......",
            "..B.S...B..S.....",
            "..S..S..S...S....",
    };
    static final String[] JUMP = {
            "...........S..S..",
            "...........BS.BS.",
            "...........BBBBB.",
            "..........BBHBEB.",
            "..........BBBBBBN",
            "S........CCTBMMM.",
            "BS.HHHHHHBBB.....",
            "SBHBBBBBBBBB.....",
            ".BBBBBBBBBBS.....",
            "BBSSSSSSSBBBS....",
            "S.........S.BS...",
    };
    static final String[] SIT = {
            "........S..S..",
            "........BS.BS.",
            "........BBBBB.",
            ".......BBHBEB.",
            ".......BBBBBBN",
            ".......CCTBSSS",
            "......HBBB....",
            "....HHBBBB....",
            "..HHBBBBBB....",
            "S.BBBBBBBB....",
            "BSBBBBBSBS....",
            ".SSSSSS.BS....",
            "........SS....",
    };
    static final String[] LIE = {
            "...........S..S..",
            "...........BS.BS.",
            "...........BBBBB.",
            "..........BBHBEB.",
            "...HHHHHHHBBBBBBN",
            "S.HBBBBBBBCCTBSS.",
            "BBBBBBBBBBBBBBBB.",
            ".SSSSSSSSSSSSSSS.",
    };
    static final String[] SLEEP = {
            ".........S......",
            "...HHHHHHBBS....",
            "..HBBBBBBBBBBB..",
            "S.BBBBBBBBBBSBB.",
            "BBBBBBBBBCCTBBBN",
            ".SSSSSSSSSSSSSS.",
    };
    static final String[] BOW = {
            "S................",
            "BS...............",
            "SBHH.............",
            ".BBBHH...........",
            ".BBBBBHH....S..S.",
            ".BS.SBBBHH..BS.BS",
            ".B...SBBBBCCBBBBB",
            ".B....SBBBBBBHBEB",
            ".S.....BBBBBBBBBBN",
            ".......SSSSSSSSSS.",
    };
    // Looking straight out at you.
    static final String[] FRONT = {
            "..S.....S..",
            ".BBS...SBB.",
            ".BBBBBBBBB.",
            ".BHBBBBBHB.",
            ".BEBBBBBEB.",
            "..BBBNBBB..",
            "..SBBBBBS..",
            "...CCTCC...",
            "..BBBBBBB..",
            ".BBBBBBBBB.",
            ".BS.BBB.SB.",
            ".SS.SSS.SS.",
    };
    static final String[] BONE = {"W...W", ".WWW.", "W...W"};

    static final int BROWN = 0xFF7A3E20, LIGHT = 0xFFA65A30, DARK = 0xFF4E2614, EYE = 0xFF120A08,
            NOSE = 0xFF1A0E0A, TEAL = 0xFF1FA6B0, TONGUE = 0xFFE07A90, IVORY = 0xFFE8E0C8;

    static final int SLEEPING = 0, IDLE = 1, WALK = 2, SIT_DOWN = 3, CHEW = 4, PLAY_BOW = 5, HUNT = 6,
            SNIFF = 7, DRINK = 8, VIBE = 9, SCRATCH = 10, CHASE = 11, FETCH = 12, TOSS = 13, NAP = 14, WATCH = 15,
            ZOOM = 16, COME = 17, LOOK = 18, TO_DRINK = 19, DANCE = 20,
            TO_SPOT = 21, EAT = 22, SHAKE = 23, SCARED = 24, SHELTER = 25;
    // His moves (see Party for which go with which music).
    static final int M_NOD = 0, M_HOP = 1, M_SPIN = 2, M_BANG = 3, M_TWIST = 4, M_LOUNGE = 5, M_TILT = 6, M_BOW = 7;
    static final int[][] MOVES = {
            {M_LOUNGE, M_NOD, M_LOUNGE},          // chill
            {M_NOD, M_TWIST, M_NOD},              // groove (in shades)
            {M_HOP, M_SPIN, M_TWIST, M_BOW},      // dance
            {M_BANG, M_HOP, M_BANG},              // rock
            {M_TILT, M_TWIST, M_BOW}};            // bright
    int move = M_NOD;
    float moveHome;

    float x = 140, tx;
    int face = 1;
    int state = IDLE;
    float stT, stDur = 2f;
    float jumpT = -1;          // seconds into a leap
    int prey = -1;             // the firefly being hunted
    float huntCool = 15f, chaseCool = 0f, playCool = 20f;
    float vx, turnT, stepPh;   // walking: eased speed, a beat before turning round, leg rhythm
    int side;                  // which side of the bone (or mouse) he's making for: -1 left, +1 right
    int tosses;
    float hopT = -1;           // a little hop of happiness
    int laps, zoomDay = -1, drinkDay = -1;
    boolean greet;
    boolean tossed;            // just threw it: the next thing is to go and get it
    float alert;               // "!" after waking
    float zzz, noteIn;
    boolean holding;           // bone in mouth
    float bx = 110, by, bvx, bvy;   // the bone: x, height above the bank, velocity
    boolean boneFlying;
    // His day and moods (LIFE.md): how much go he has, whether he's eaten, the weather, a big hello.
    float energy = 0.8f;
    int thenState;             // where TO_SPOT is taking him, and what for
    float thenDur;
    boolean sheltered, bigGreet, comeAfterZoom;
    float glanceIn = 3f, glanceFor, tilt;     // little looks at whatever's about
    float treatT = -1, treatFromX;            // the Keeper's treat, in the air

    static int ground() {
        return Stage.WATER_Y - 1;
    }

    /** Walk to x, then do `then` for `dur`. */
    void toSpot(Stage s, float x, int then, float dur) {
        tx = Math.max(s.dogMin, Math.min(s.dogMax, x));
        thenState = then;
        thenDur = dur;
        start(s, TO_SPOT, 1e9f);
    }

    /** How fast he goes: slower when tired, slower in fog. */
    float pace(float speed, Stage s) {
        return speed * (0.65f + 0.35f * energy) * (Weather.fog() ? 0.75f : 1f);
    }

    static boolean lying(int st) {
        return st == CHEW || st == SHELTER || st == SCARED || st == EAT;
    }

    void start(Stage s, int st, float dur) {
        state = st;
        stT = 0;
        stDur = dur;
    }

    int heardVoice;

    boolean busy() {
        return state == HUNT || state == CHASE || state == TOSS || jumpT >= 0;
    }

    // ---------------------------------------------------------------- thinking

    void update(Stage s, float dt) {
        if (dt <= 0) return;
        stT += dt;
        if (alert > 0) alert -= dt;
        if (huntCool > 0) huntCool -= dt;
        if (chaseCool > 0) chaseCool -= dt;
        if (playCool > 0) playCool -= dt;
        bone(s, dt);
        if (!holding && !boneFlying) bx = Math.max(s.dogMin, Math.min(s.dogMax, bx));   // his toy stays on his patch

        // Sleep and wake with Mote.
        boolean toBed = state == TO_SPOT && thenState == SLEEPING;
        if (s.mote.asleep && !s.partyOn() && state != SLEEPING && !toBed) {
            if (holding) drop();
            if (Math.abs(x - s.bedX()) > 8) toSpot(s, s.bedX(), SLEEPING, 1e9f);   // off to bed first
            else start(s, SLEEPING, 1e9f);
        } else if ((!s.mote.asleep || s.partyOn()) && (state == SLEEPING || toBed)) {   // the music wakes him too
            alert = 0.8f;
            greet = true;
            start(s, PLAY_BOW, 1.4f);           // a big stretch, then over to say hello
        }
        // Go: spent by running about and dancing, restored by lying down and sleeping.
        boolean resting = state == SLEEPING || state == NAP || lying(state) || state == SIT_DOWN;
        energy += dt * (resting ? 0.0045f : state == ZOOM ? -0.012f : state == DANCE ? -0.0025f : -0.0008f);
        energy = Math.max(0.1f, Math.min(1f, energy));

        Life life = s.life;
        // The mouse! Off he goes.
        if (state != SLEEPING && state != CHASE && chaseCool <= 0 && life.mouseT >= 0
                && life.mouseX > s.dogMin - 10 && life.mouseX < s.dogMax + 4) {
            chaseCool = 25f + s.rnd.nextFloat() * 20f;
            if (s.rnd.nextInt(5) < 3) {                     // he doesn't always bother
                if (holding) drop();
                side = life.mouseX > x ? -1 : 1;            // comes at it from where he is
                start(s, CHASE, 3.5f);
            }
        }
        // A firefly drifting low within reach.
        if (!busy() && state != SLEEPING && huntCool <= 0) {
            for (int i = 0; i < Stage.FLIES; i++) {
                if (s.glow(i) < 0.3f) continue;
                if (s.fy[i] > ground() - 30 && s.fy[i] < ground() - 8 && Math.abs(s.fx[i] - x) < 26
                        && s.fx[i] > s.dogMin && s.fx[i] < s.dogMax + 6) {
                    if (holding) drop();
                    prey = i;
                    start(s, HUNT, 4f);
                    break;
                }
            }
        }
        // The frog surfaces: watch it. Too close and it's gone.
        if (s.frogT >= 0 && s.frogT < 0.2f && !busy() && state != SLEEPING) {
            face = s.frogX > x ? 1 : -1;
            start(s, WATCH, 2.5f);
        }
        if (!s.frogAfloat && s.frogT > 0.5f && s.frogT < s.frogSit && Math.abs(s.frogX - x) < 16 && !s.frogBrave) s.frogT = s.frogSit;   // plop (not off its leaf: it's out of reach)
        if (s.frogBrave && s.frogT > 0.5f && Math.abs(s.frogX - x) < 18 && state == WATCH) tilt = 1;   // a frog that stares back
        // Someone at the opening on a full-moon night: he knows them.
        if (s.lore.keeperHere() && state != SLEEPING && !busy() && state != WATCH
                && !(state == TO_SPOT && thenState == WATCH)) {
            alert = 0.6f;
            toSpot(s, s.dogMax - 4, WATCH, 9f);          // to the mouth of the road, where the lantern is
        }
        if (state == WATCH && s.lore.keeperHere() && treatT < 0 && fedToday(s) == false && x > s.dogMax - 12) {
            face = 1;
            if (s.lore.keeperT > 6f && s.lore.keeperT < 6.2f) {   // something tossed out of the dark
                treatT = 0;
                treatFromX = s.dogMax + 30;
            }
        }
        if (treatT >= 0 && (treatT += dt) > 0.8f) {
            treatT = -1;
            hopT = 0;                                    // caught it
            s.fedDay = Stage.today();
            s.bowlFull = false;
            energy = Math.min(1f, energy + 0.2f);
        }
        // Snow: he hops at the flakes.
        if (Weather.snow() && !busy() && state != SLEEPING && state != SCARED && hopT < 0 && s.rnd.nextFloat() < dt * 0.25f) {
            hopT = 0;
            face = s.rnd.nextBoolean() ? 1 : -1;
        }
        // Thunder: a flinch.
        if (s.life.flash > 0.8f && state == SCARED && hopT < 0) hopT = 0;
        // Little looks at whatever's about: not reactions, just noticing (LIFE.md).
        if (glanceFor > 0) glanceFor -= dt;
        else tilt = 0;
        if ((glanceIn -= dt) <= 0) {
            glanceIn = 2.5f + s.rnd.nextFloat() * 5f;
            boolean calm = state == IDLE || state == SIT_DOWN || state == SNIFF || state == WATCH || lying(state);
            if (calm && Math.abs(vx) < 1f) {
                float g = Ties.worthALook(s, x, Ties.PIP);
                if (!Float.isNaN(g)) {
                    glanceFor = 1.2f + s.rnd.nextFloat() * 2f;
                    if (lying(state)) tilt = 1;                  // lying down: just lifts and turns his head
                    else face = g > x ? 1 : -1;
                }
            }
        }
        // A bird lands on the keystone: worth a look.
        if (s.life.birdT > 3.5f && s.life.birdT < 3.6f && s.life.birdPerch > 0 && state != SLEEPING && !busy()) {
            face = Stage.ARCH_CX > x ? 1 : -1;
            start(s, WATCH, 3f);
        }
        // You're talking to the TV: he turns and looks out at you; when it's done, a little hop.
        if ((s.voice == 1 || s.voice == 2) && state != SLEEPING && state != LOOK && !busy()) start(s, LOOK, 6f);
        if (s.voice == 3 && heardVoice != 3 && state != SLEEPING && hopT < 0) hopT = 0;
        heardVoice = s.voice;
        // Mote's at the glass: Pip turns and looks out too.
        if (s.mote.glassT > 0.8f && s.mote.glassT < 0.9f && state != SLEEPING && !busy()) start(s, LOOK, 4f);
        // You picked something: a hop.
        if (s.mote.joy > 0.9f && hopT < 0 && state != SLEEPING && !busy()) hopT = 0;
        if (hopT >= 0 && (hopT += dt) > 0.35f) hopT = -1;

        switch (state) {
            case WALK:
                if (walk(s, dt, 13)) decide(s, false);
                break;
            case FETCH: {
                // Stand beside the bone on the side he chose, facing it; the side never flips mid-walk.
                tx = Math.max(s.dogMin, Math.min(s.dogMax, bx + side * 9));
                boolean there = walk(s, dt, 24);
                if (there && !boneFlying) {
                    face = -side;                                // face the bone
                    if (Math.abs(bx - (x + face * 9)) < 4) {
                        holding = true;
                        decide(s, true);
                    } else if (Math.abs(bx + side * 9 - tx) > 1) {
                        side = -side;                        // that side's off his patch: go round
                    } else decide(s, false);
                }
                break;
            }
            case CHASE: {
                float mx = life.mouseT >= 0 ? life.mouseX : x;
                tx = Math.max(s.dogMin, Math.min(s.dogMax, mx + side * 6));
                walk(s, dt, 42);
                if (life.mouseT < 0 || stT > stDur) start(s, PLAY_BOW, 1.2f);
                break;
            }
            case HUNT: {
                if (prey < 0 || s.glow(prey) < 0.15f) {
                    decide(s, false);
                    break;
                }
                face = s.fx[prey] > x ? 1 : -1;
                if (jumpT < 0 && stT > 0.6f) jumpT = 0;           // a crouch, then the leap
                if (jumpT >= 0) {
                    float before = jumpT;
                    jumpT += dt;
                    x += face * 14 * dt;
                    x = Math.max(s.dogMin, Math.min(s.dogMax, x));
                    if (before < 0.22f && jumpT >= 0.22f) {        // snap at the top of the leap
                        float dx = s.fx[prey] - (x + face * 8), dy = s.fy[prey] - (ground() - 14);
                        if (dx * dx + dy * dy < 160 && s.rnd.nextInt(3) > 0) {
                            s.fx[prey] = Stage.ARCH_CX + s.rnd.nextInt(40) - 20;   // it blinks away
                            s.fy[prey] = 40;
                        }
                    }
                    if (jumpT > 0.45f) {
                        jumpT = -1;
                        huntCool = 18f + s.rnd.nextFloat() * 17f;
                        prey = -1;
                        start(s, SIT_DOWN, 1.5f);
                    }
                }
                break;
            }
            case TOSS:
                if (stT > 0.35f && holding) {                     // flick of the head
                    holding = false;
                    boneFlying = true;
                    bx = x + face * 9;
                    by = 6;
                    bvx = face * (14 + s.rnd.nextFloat() * 16);
                    tosses++;
                    tossed = true;
                    bvy = 55 + s.rnd.nextFloat() * 20;
                }
                if (stT > 0.9f) {
                    start(s, PLAY_BOW, 0.8f);
                }
                break;
            case ZOOM: {
                // Flat out to one end and back, a couple of times, then a sit to get his breath.
                float lo = s.dogMin + 6, hi = s.dogMax - 6;
                tx = laps % 2 == 0 ? hi : lo;
                if (walk(s, dt, 62)) {
                    laps++;
                    if (laps >= 4) {
                        if (comeAfterZoom) {
                            comeAfterZoom = false;
                            come(s);
                        } else start(s, SIT_DOWN, 4f);
                    }
                }
                break;
            }
            case COME:
                // Called: trots over under Mote and sits looking up.
                tx = Math.max(s.dogMin, Math.min(s.dogMax, s.mote.x));
                if (walk(s, dt, 30)) {
                    face = s.mote.x > x ? 1 : -1;
                    start(s, SIT_DOWN, 6f);
                }
                break;
            case PLAY_BOW:
                if (stT > stDur && greet) {                     // morning stretch done: come and say hello
                    greet = false;
                    if (bigGreet) {                              // been a while: a happy dash first
                        bigGreet = false;
                        comeAfterZoom = true;
                        laps = 2;
                        start(s, ZOOM, 1e9f);
                    } else come(s);
                    break;
                }
                if (stT > stDur) {
                    if (!holding && tossed && Math.abs(bx - x) < 90) fetch(s);       // after a toss: go get it
                    else decide(s, false);
                }
                break;
            case TO_SPOT:
                if (walk(s, dt, pace(state == TO_SPOT && thenState == SCARED ? 40 : 18, s))) {
                    if (thenState == EAT) face = s.bowlX() > x ? 1 : -1;
                    if (thenState == WATCH && s.lore.keeperHere()) face = 1;
                    else if (thenState == WATCH && s.frogT >= 0 && Math.abs(s.frogX - x) < 30) face = s.frogX > x ? 1 : -1;
                    else if (thenState == SNIFF && s.life.snailOut) face = s.life.snailX > x ? 1 : -1;
                    start(s, thenState, thenDur);
                }
                break;
            case EAT:
                if (((int) (stT * 4)) != ((int) ((stT - dt) * 4)) && s.rnd.nextInt(3) == 0)
                    s.spawn(s.bowlX() + s.rnd.nextInt(5) - 2, ground() - 2, (s.rnd.nextFloat() - 0.5f) * 0.4f, -0.4f, 0.5f, Stage.P_DROPLET);
                if (stT > stDur) {
                    s.bowlFull = false;
                    s.fedDay = Stage.today();
                    energy = Math.min(1f, energy + 0.15f);
                    start(s, CHEW, 12f + s.rnd.nextFloat() * 10f);   // full, and lies down to let it settle
                }
                break;
            case SHAKE:
                if (s.rnd.nextFloat() < 0.6f)
                    s.spawn(x + s.rnd.nextInt(13) - 6, ground() - 4 - s.rnd.nextInt(5), (s.rnd.nextFloat() - 0.5f) * 1.6f, -0.6f - s.rnd.nextFloat() * 0.6f, 0.6f, Stage.P_DROPLET);
                if (stT > stDur) decide(s, false);
                break;
            case SCARED:
            case SHELTER:
                if (stT > stDur) decide(s, false);
                break;
            case TO_DRINK:
                if (walk(s, dt, 16)) {
                    face = s.channelX() > x ? 1 : -1;
                    start(s, DRINK, stDur);
                }
                break;
            case DRINK:
                if (((int) (stT * 3)) != ((int) ((stT - dt) * 3))) s.splash(x + face * 9, 0.25f);
                if (stT > stDur) decide(s, false);
                break;
            case SLEEPING:
                zzz += dt;
                if (zzz > 2.2f) {
                    zzz = 0;
                    s.zed(x + face * 6, ground() - 9);
                }
                break;
            case DANCE:
                if (!s.partyOn() || stT > stDur) {
                    decide(s, false);
                    break;
                }
                if (move == M_HOP && s.beatNow && hopT < 0) hopT = 0;
                if (move == M_SPIN && s.beatNow) face = -face;
                if (move == M_TWIST) {                          // a step either side, on the beat
                    float want = moveHome + ((s.beats & 1) == 0 ? 3 : -3);
                    x += (want - x) * Math.min(1f, dt * 14f);
                    stepPh += dt * 2f;
                }
                noteIn -= dt;
                if (noteIn <= 0 && move != M_BANG && move != M_HOP) {
                    noteIn = 1.4f + s.rnd.nextFloat() * 1.4f;
                    s.spawn(x + face * 5, ground() - 14, (s.rnd.nextFloat() - 0.5f) * 0.1f, -0.2f, 2.4f, Stage.P_NOTE);
                }
                break;
            case VIBE:
                noteIn -= dt;
                if (noteIn <= 0) {
                    noteIn = 1.6f + s.rnd.nextFloat() * 1.6f;
                    s.spawn(x + face * 5, ground() - 14, (s.rnd.nextFloat() - 0.5f) * 0.1f, -0.2f, 2.4f, Stage.P_NOTE);
                }
                if (!s.music || stT > stDur) decide(s, false);
                break;
            default:
                if (stT > stDur) decide(s, false);
        }
        if (holding) {
            bx = x + face * 9;
            by = 0;
        }
    }

    /**
     * Walk towards tx: eases into a stroll, slows on arrival, and stops for a beat before turning
     * round rather than flipping on the spot. Returns true once he's there.
     */
    boolean walk(Stage s, float dt, float speed) {
        float d = tx - x;
        if (Math.abs(d) < 0.8f) {
            vx = 0;
            return true;
        }
        int want = d > 0 ? 1 : -1;
        if (want != face) {
            vx += (0 - vx) * Math.min(1f, dt * 8f);      // slow to a stop...
            turnT += dt;
            if (turnT < 0.4f || Math.abs(vx) > 2f) return false;
            face = want;                                 // ...then turn round
            turnT = 0;
        }
        float cruise = Math.min(speed, Math.abs(d) * 2f + 5f) * want;
        vx += (cruise - vx) * Math.min(1f, dt * 3.5f);
        x += vx * dt;
        if ((tx - x) * want < 0) x = tx;                 // never overshoot
        stepPh += Math.abs(vx) * dt * 0.32f;
        return false;
    }

    void fetch(Stage s) {
        tossed = false;
        side = x < bx ? -1 : 1;                          // approach from the side he's on
        if (bx + side * 9 < s.dogMin || bx + side * 9 > s.dogMax) side = -side;
        start(s, FETCH, 1e9f);
    }

    void drop() {
        holding = false;
        by = 0;
    }

    /** Choose the next thing to do. justFetched: he's got the bone in his mouth. */
    void decide(Stage s, boolean justFetched) {
        java.util.Random r = s.rnd;
        float night = s.life.dark;
        if (justFetched) {
            int k = r.nextInt(10);
            if (k < 3 && tosses < 2) start(s, TOSS, 1f);         // a couple of throws at most...
            else if (k < 8) {
                start(s, CHEW, 8f + r.nextFloat() * 10f);         // ...then he settles with it
                tosses = 0;
                playCool = 50f + r.nextFloat() * 50f;
            } else wander(s, WALK);
            return;
        }
        float hr = s.life.hour;
        // Weather first: a storm sends him to bed, ears down; rain, under the arch; after rain, a shake.
        if (Weather.storm() || s.life.flash > 0) {
            if (holding) drop();
            if (Math.abs(x - s.bedX()) > 6) toSpot(s, s.bedX(), SCARED, 15f + r.nextFloat() * 10f);
            else start(s, SCARED, 15f + r.nextFloat() * 10f);
            return;
        }
        if (s.life.rainFor > 0 && !s.partyOn()) {
            sheltered = true;
            float dry = s.dogMax - 8;                           // the mouth of the road: under the arch
            if (Math.abs(x - dry) > 6) toSpot(s, dry, SHELTER, 20f + r.nextFloat() * 20f);
            else start(s, SHELTER, 20f + r.nextFloat() * 20f);
            return;
        }
        if (sheltered) {
            sheltered = false;
            start(s, SHAKE, 1.3f);
            return;
        }
        // Supper, once the bowl's filled.
        if (s.bowlFull && !s.partyOn()) {
            if (holding) drop();
            toSpot(s, s.bowlX() - 9, EAT, 6f);
            return;
        }
        if (s.partyOn()) {                                // the party: somewhere with room, a new move every eight beats
            int[] set = MOVES[s.party.mood];
            if (energy < 0.3f || hr >= 23 || hr < 5) set = MOVES[Party.CHILL];   // too tired to go wild
            int m = set[r.nextInt(set.length)];
            for (int k = 0; k < set.length && m == move; k++) m = set[(k + 1) % set.length];   // something new
            move = m;
            if (Ties.clearance(s, x, Ties.PIP) < 18 || stT > 60) {     // too close to someone: find room first
                float spot = Ties.roomy(s, s.dogMin + 8, s.dogMax - 8, x, Ties.PIP, 40);
                moveHome = spot;
                toSpot(s, spot, DANCE, 8 * 60f / Math.max(70f, s.bpm > 40 ? s.bpm : 100f));
            } else {
                moveHome = Math.max(s.dogMin + 6, Math.min(s.dogMax - 6, x));
                start(s, DANCE, 8 * 60f / Math.max(70f, s.bpm > 40 ? s.bpm : 100f));
            }
            noteIn = 0.6f;
            return;
        }
        // Hungry before supper: hangs about near his bowl, looking down the road now and then.
        if (hr >= 17.5f && hr < 18.5f && !fedToday(s) && r.nextInt(3) == 0) {
            if (Math.abs(x - s.bowlX()) > 20) toSpot(s, s.bowlX() + 8, SIT_DOWN, 6f + r.nextFloat() * 6f);
            else {
                face = 1;
                start(s, WATCH, 4f);
            }
            return;
        }
        // The heat of the day: into the shade by the wall, panting. Tired after lunch: a nap.
        boolean hot = !Float.isNaN(Weather.temp) && Weather.temp >= 31 && s.life.day > 0.6f;
        if ((hot || (hr >= 12.5f && hr < 15f && energy < 0.6f)) && r.nextInt(3) == 0) {
            toSpot(s, s.bedX() + r.nextInt(14), hot ? CHEW : NAP, 20f + r.nextFloat() * 25f);
            return;
        }
        if (s.music && r.nextInt(10) < 6) {
            start(s, VIBE, 8f + r.nextFloat() * 10f);
            noteIn = 0.5f;
            return;
        }
        // His day: a drink first thing, and one mad dash every evening.
        int today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        float h = s.life.hour;
        if (h >= 6 && h < 10 && drinkDay != today) {
            drinkDay = today;
            drink(s, 4f);
            return;
        }
        if (h >= 18 && h < 21 && zoomDay != today && r.nextInt(3) == 0) {
            zoomDay = today;
            zoomies(s);
            return;
        }
        // He goes to see who's about: the frog on the bank, the snail on the stones.
        if (!s.frogAfloat && s.frogT > 0.6f && s.frogT < s.frogSit - 3 && r.nextInt(5) == 0) {
            int side = s.frogX > x ? -1 : 1;
            float spot = s.frogX + side * 15;
            if (spot > s.dogMin && spot < s.dogMax) {
                toSpot(s, spot, WATCH, 4f);
                return;
            }
        }
        if (s.life.snailOut && s.life.shellFor <= 0 && r.nextInt(7) == 0) {
            int side = s.life.snailX > x ? -1 : 1;
            float spot = s.life.snailX + side * 9;
            if (spot > s.dogMin && spot < s.dogMax) {
                toSpot(s, spot, SNIFF, 3f);
                return;
            }
        }
        // Now and then he wanders over to where Mote is.
        if (Math.abs(s.mote.x - x) > 40 && r.nextInt(6) == 0 && s.mote.x > s.dogMin && s.mote.x < s.dogMax) {
            come(s);
            return;
        }
        // Now and then he just turns and looks at you, head on one side.
        if (r.nextInt(100) < 7) {
            start(s, LOOK, 3f + r.nextFloat() * 3f);
            return;
        }
        // Mostly an easy-going dog: strolls, sits, sniffs, lies about; plays now and then.
        int k = r.nextInt(100);
        if (energy < 0.35f && k < 40) {                    // worn out: lies about
            start(s, k < 15 ? NAP : CHEW, 12f + r.nextFloat() * 18f);
        } else if (night > 0.6f && k < 30) {
            start(s, NAP, 15f + r.nextFloat() * 25f);
        } else if (k < 20) {
            wander(s, WALK);
        } else if (k < 36) {
            start(s, SIT_DOWN, 4f + r.nextFloat() * 7f);
        } else if (k < 48) {
            start(s, SNIFF, 2.5f + r.nextFloat() * 2.5f);
        } else if (k < 54) {
            drink(s, 3f + r.nextFloat() * 2f);
        } else if (k < 59) {
            start(s, SCRATCH, 1.6f);
        } else if (k < 72 && playCool <= 0) {
            playCool = 40f + r.nextFloat() * 40f;
            if (holding) start(s, TOSS, 1f);
            else fetch(s);
        } else if (k < 86) {
            start(s, CHEW, 8f + r.nextFloat() * 10f);   // lies down for a while (chews if he has the bone)
        } else {
            start(s, IDLE, 3f + r.nextFloat() * 4f);
        }
    }

    void zoomies(Stage s) {
        if (state == SLEEPING) return;
        if (holding) drop();
        laps = 0;
        start(s, ZOOM, 1e9f);
    }

    /** To where the channel runs into the stream, if that's in his patch; else right here. */
    void drink(Stage s, float dur) {
        float m = s.channelX();
        if (m > s.dogMin + 10 && m < s.dogMax - 10) {
            int side = m > x ? 1 : -1;                       // the side he's already on
            tx = m - side * 9;
            start(s, TO_DRINK, dur);
        } else start(s, DRINK, dur);
    }

    void come(Stage s) {
        if (state == SLEEPING) return;
        start(s, COME, 1e9f);
    }

    /** A short stroll, usually on the way he's already facing. */
    void wander(Stage s, int st) {
        float lo = s.dogMin + 6, hi = s.dogMax - 6;
        // Somewhere with a bit of room, not miles off (he doesn't park on top of the others).
        tx = Ties.roomy(s, Math.max(lo, x - 55), Math.min(hi, x + 55), x, Ties.PIP, 26);
        if (Math.abs(tx - x) < 10) {
            int dir = s.rnd.nextInt(10) < 7 ? face : -face;
            float t = x + dir * (18 + s.rnd.nextFloat() * 32);
            if (t < lo || t > hi) t = x - dir * 30;
            tx = Math.max(lo, Math.min(hi, t));
        }
        start(s, st, 1e9f);
    }

    boolean fedToday(Stage s) {
        return s.fedDay == Stage.today();
    }

    /**
     * The tossed bone's flight. Stepped in small, fixed slices so it lands the same at any frame rate
     * (the screensaver draws slower, and in big steps the bounces never died away: the bone hopped
     * on the spot forever and Pip stood waiting to fetch it). Two or three bounces, then it lies still.
     */
    void bone(Stage s, float dt) {
        if (!boneFlying) return;
        for (float left = dt; left > 0 && boneFlying; left -= 1 / 60f) {
            float h = Math.min(left, 1 / 60f);
            bx += bvx * h;
            by += bvy * h;
            bvy -= 160 * h;
            if (bx < s.dogMin - 8) { bx = s.dogMin - 8; bvx = Math.abs(bvx) * 0.5f; }
            if (bx > s.dogMax + 8) { bx = s.dogMax + 8; bvx = -Math.abs(bvx) * 0.5f; }
            if (by <= 0) {
                by = 0;
                bvy = -bvy * 0.35f;               // a little bounce...
                bvx *= 0.6f;
                if (bvy < 14) {                   // ...until one would rise less than a pixel
                    boneFlying = false;
                    bvx = bvy = 0;
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    void draw(Stage s) {
        String[] art;
        int headCol, headRows, tailRows = 0;
        int hx = 0, hy = 0;                  // head offset (nodding, sniffing)
        boolean mouth = false, closedEye = false, wag = false;
        float beat = s.beatPhase();
        boolean walking = (state == WALK || state == FETCH || state == CHASE || state == ZOOM || state == COME)
                && Math.abs(vx) > 1.5f;
        float lift = hopT >= 0 ? (float) Math.sin(Math.PI * hopT / 0.35f) * 3 : 0;
        switch (state) {
            case LOOK:
                art = FRONT;
                headCol = 0;
                headRows = 7;
                hx = ((int) (stT * 0.7f) & 1) == 0 ? 0 : 1;     // the head tilt
                closedEye = ((int) (s.t * 0.5f + x) % 7) == 0 && ((int) (s.t * 8) & 1) == 0;
                break;
            case SLEEPING:
            case NAP:
                art = SLEEP;
                headCol = 99;
                headRows = 0;
                closedEye = true;
                break;
            case SIT_DOWN:
            case VIBE:
            case SCRATCH:
            case WATCH:
                art = SIT;
                headCol = 6;
                headRows = 6;
                tailRows = 11;
                if (state == VIBE) {
                    hy = beat < 0.35f ? 1 : 0;           // nodding along
                    wag = beat < 0.5f;
                    mouth = (s.beats & 7) == 7;
                } else if (state == SCRATCH) {
                    hy = ((int) (stT * 10) & 1);
                } else if (state == SIT_DOWN && stT > stDur - 1.2f && stT < stDur - 0.4f) {
                    mouth = true;                        // a yawn
                    closedEye = true;
                } else wag = ((int) (stT * 4) & 1) == 0 && state == WATCH;
                break;
            case CHEW:
            case SHELTER:
            case SCARED:
                art = LIE;
                headCol = 10;
                headRows = 6;
                tailRows = 6;
                if (state == CHEW && (holding || Math.abs(bx - (x + face * 9)) < 3)) hy = ((int) (stT * 5) & 1);
                if (state == SCARED) {                  // flat, still, eyes squeezed at each flash
                    hy = 1;
                    closedEye = s.life.flash > 0.2f;
                }
                if (tilt > 0) hy = -1;                  // lifts his head to look
                boolean hotNow = !Float.isNaN(Weather.temp) && Weather.temp >= 31 && s.life.day > 0.6f;
                if (state == CHEW && hotNow) mouth = ((int) (s.t * 4) & 1) == 0;   // panting
                break;
            case EAT:
                art = STAND;
                headCol = 8;
                headRows = 6;
                tailRows = 8;
                hx = 1;
                hy = 3 + (((int) (stT * 5)) & 1);
                wag = ((int) (stT * 6) & 1) == 0;
                break;
            case SHAKE:
                art = STAND;
                headCol = 8;
                headRows = 6;
                tailRows = 8;
                hx = ((int) (stT * 16) & 1) == 0 ? -1 : 1;
                wag = true;
                break;
            case PLAY_BOW:
                art = BOW;
                headCol = 99;
                headRows = 0;
                tailRows = 3;
                wag = ((int) (stT * 10) & 1) == 0;
                break;
            case HUNT:
                if (jumpT >= 0) {
                    art = JUMP;
                    lift = (float) Math.sin(Math.PI * Math.min(1f, jumpT / 0.45f)) * 9;
                    mouth = true;
                } else art = BOW;           // crouched, eyes on it
                headCol = 99;
                headRows = 0;
                break;
            case DANCE:
                headCol = 8;
                headRows = 6;
                tailRows = 8;
                if (move == M_LOUNGE) {                         // stretched out, nodding, tail going
                    art = LIE;
                    headCol = 10;
                    tailRows = 6;
                    hy = (s.beats & 1) == 0 && beat < 0.4f ? 1 : 0;
                    wag = ((int) (s.t * 3) & 1) == 0;
                } else if (move == M_BOW) {
                    boolean down = (s.beats & 1) == 0;
                    art = down ? BOW : STAND;
                    if (down) {
                        headCol = 99;
                        headRows = 0;
                        tailRows = 3;
                    }
                    wag = true;
                } else if (move == M_TILT) {                    // looking right out at you, head going side to side
                    art = FRONT;
                    headCol = 0;
                    headRows = 7;
                    hx = (s.beats & 1);
                } else if (move == M_BANG) {
                    art = STAND;
                    hy = ((int) (beat * 2) & 1) == 0 ? 3 : 0;   // twice a beat
                    hx = 1;
                    mouth = true;
                    wag = true;
                } else {
                    art = move == M_TWIST && (s.beats & 1) == 0 ? WALK1 : STAND;
                    hy = beat < 0.3f ? 1 : 0;
                    wag = beat < 0.5f;
                    mouth = move == M_HOP && beat < 0.3f;
                }
                break;
            case SNIFF:
            case DRINK:
                art = STAND;
                headCol = 8;
                headRows = 6;
                tailRows = 8;
                hx = 1;
                hy = state == DRINK ? 4 : 2 + (((int) (stT * 6) & 1));
                wag = state == SNIFF && ((int) (stT * 5) & 1) == 0;
                break;
            default:
                if (walking) {
                    // the hackney trot; in time with the music when there is some
                    boolean a = s.music ? (s.beats & 1) == 0 : ((int) stepPh & 1) == 0;
                    art = a ? WALK1 : WALK2;
                } else art = STAND;
                headCol = 8;
                headRows = 6;
                tailRows = 8;
                wag = walking || ((int) (s.t * 2) & 1) == 0 && state == IDLE;
                if (s.music && !walking) hy = beat < 0.3f ? 1 : 0;
                if (state == ZOOM) mouth = true;
        }

        int h = art.length, w = art[0].length();
        int bottom = ground() - Math.round(lift);
        int top = bottom - h + 1;
        int left = Math.round(x) - w / 2;
        float shade = shadeAt(s);
        int nx = 0, ny = 0, ex = 0, ey = 0, cx0 = 9999, cx1 = -1, cy = 0;
        Seasons se = s.seasons;
        boolean holi = se.holi == 0;
        // outline pass, then colour
        for (int pass = 0; pass < 2; pass++)
            for (int r = -1; r <= h; r++)
                for (int c = -1; c <= w; c++) {
                    boolean head = r < headRows && c >= headCol;
                    boolean tail = c == 0 && r < tailRows;
                    int ox = head ? hx : 0, oy = head ? hy : tail && wag ? -1 : 0;
                    char ch = at(art, r, c);
                    int px = face > 0 ? left + c + ox : left + w - 1 - c - ox, py = top + r + oy;
                    if (pass == 0) {
                        if (ch == '.' && (at(art, r - 1, c) != '.' || at(art, r + 1, c) != '.'
                                || at(art, r, c - 1) != '.' || at(art, r, c + 1) != '.')) s.px(px, py, Pal.VOID);
                        continue;
                    }
                    int col;
                    switch (ch) {
                        case 'B': col = holi && ((px * 7 + py * 13) % 7 == 0) ? HOLI[(px + py) % HOLI.length] : BROWN; break;   // Holi: covered in colour
                        case 'H': col = LIGHT; break;
                        case 'S': col = DARK; break;
                        case 'E': col = closedEye ? DARK : EYE; ex = px; ey = py; break;
                        case 'N': col = NOSE; nx = px; ny = py; break;
                        case 'C': col = TEAL; cx0 = Math.min(cx0, px); cx1 = Math.max(cx1, px); cy = Math.max(cy, py); break;
                        case 'T': col = s.doorOpen && ((int) (s.t * 0.7f) % 5 == 0) ? Pal.TEXT : Pal.AMBER; break;
                        case 'M': col = TONGUE; break;
                        default: continue;
                    }
                    if (ch == 'E' && !closedEye && alert <= 0 && ((int) (s.t * 0.4f + x) % 9 == 0)) col = DARK;  // blink
                    s.px(px, py, ch == 'C' || ch == 'T' ? col : Pal.mix(col, Pal.VOID, shade));
                }
        if (ex != 0) outfit(s, ex, ey, cx0, cx1, cy);
        // In a groove, he wears shades.
        if (state == DANCE && s.party.mood == Party.GROOVE && ex != 0 && move != M_TILT) {
            s.px(ex - 1, ey, 0xFF0C0C14);
            s.px(ex, ey, 0xFF0C0C14);
            s.px(ex + 1, ey, 0xFF0C0C14);
            s.px(ex + face, ey - 1, 0xFF0C0C14);
            s.px(ex, ey, 0xFF7A86B0);                    // the glint
        }
        if (mouth && nx != 0) {
            s.px(nx - face, ny + 1, TONGUE);
            s.px(nx, ny + 1, TONGUE);
        }
        // His bowl, by his bed: full in the evening till he's eaten.
        int bwx = Math.round(s.bowlX()), bgy = ground();
        for (int dx = -2; dx <= 2; dx++) s.px(bwx + dx, bgy, 0xFF1F7A86);
        for (int dx = -3; dx <= 3; dx++) s.px(bwx + dx, bgy - 1, dx == -3 || dx == 3 ? 0xFF1F7A86 : s.bowlFull ? 0xFF9A6A3A : 0xFF14505A);
        if (s.bowlFull) { s.px(bwx - 1, bgy - 2, 0xFFB07A44); s.px(bwx + 1, bgy - 2, 0xFF8A5A30); }
        // The Keeper's treat, tossed out of the road.
        if (treatT >= 0) {
            float k = treatT / 0.8f;
            int tx2 = Math.round(treatFromX + (x + face * 8 - treatFromX) * k), ty2 = Math.round(ground() - 14 - (float) Math.sin(Math.PI * k) * 14 + k * 8);
            s.px(tx2, ty2, 0xFFE8C890);
            s.px(tx2 + 1, ty2, 0xFFC8A060);
        }
        // The bone: in his mouth, under his paws, or wherever it landed.
        int bxi = holding ? nx - face : Math.round(bx), byi = holding ? ny + 1 : ground() - 1 - Math.round(by);
        if (holding && nx == 0) {
            bxi = Math.round(x + face * 8);
            byi = top + 5;
        }
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 5; c++)
                if (BONE[r].charAt(c) == 'W') s.px(bxi - 2 + c, byi - 1 + r, Pal.mix(IVORY, Pal.VOID, shade * 0.7f));
        if (alert > 0) {
            int ax = Math.round(x) + face * 4;
            for (int r = 0; r < 3; r++) s.px(ax, top - 6 + r, Pal.TEXT);
            s.px(ax, top - 2, Pal.TEXT);
        }
    }

    static final int[] HOLI = {0xFFFF3A8A, 0xFFFFD020, 0xFF3AC8F0, 0xFF6AE04A, 0xFFB050F0};

    /** What he's wearing for the season: hats on his head, garlands on his collar, a flower behind his ear. */
    void outfit(Stage s, int ex, int ey, int cx0, int cx1, int cy) {
        Seasons se = s.seasons;
        int back = -face;                                       // hats tip back, towards his tail
        int hx = ex + back, hy = ey - 2;                        // the top of his head, above the eye
        if (s.eggs.newYear) {                                   // a striped party hat
            int[] c = {0xFFFF5C8A, 0xFFFFD040};
            for (int r = 0; r < 4; r++)
                for (int k = -(3 - r) / 2; k <= (3 - r) / 2; k++) s.px(hx + k, hy - r, c[r & 1]);
            s.px(hx, hy - 4, Pal.TEXT);
        } else if (se.christmas) {                              // a Santa hat
            for (int k = -2; k <= 1; k++) s.px(hx + k * face, hy, Pal.TEXT);
            s.px(hx - face, hy - 1, 0xFFE83A3A); s.px(hx, hy - 1, 0xFFE83A3A); s.px(hx + back, hy - 1, 0xFFE83A3A);
            s.px(hx + back, hy - 2, 0xFFE83A3A); s.px(hx + 2 * back, hy - 2, 0xFFC82A2A);
            s.px(hx + 3 * back, hy - 1, Pal.TEXT);              // the bobble, flopped over
        } else if (se.halloween != Seasons.FAR) {               // a little witch hat
            int p = 0xFF4A2A6A, b = 0xFF2A1A3A;
            for (int k = -2; k <= 2; k++) s.px(hx + k, hy, b);
            s.px(hx - 1, hy - 1, p); s.px(hx, hy - 1, p); s.px(hx + 1, hy - 1, p);
            s.px(hx, hy - 2, p); s.px(hx + back, hy - 3, p);
            s.px(hx - 1, hy - 1, 0xFFFFB020);                   // the buckle
        } else if (se.eid != Seasons.FAR) {                     // a small white cap
            s.px(hx - 1, hy, Pal.TEXT); s.px(hx, hy, Pal.TEXT); s.px(hx + 1, hy, Pal.TEXT);
            s.px(hx, hy - 1, Pal.TEXT); s.px(hx + face, hy - 1, 0xFFE8D8A0);
        } else if (se.vishu != Seasons.FAR) {                   // a konna flower behind his ear
            s.px(hx + back, hy, 0xFFFFD020); s.px(hx + 2 * back, hy, 0xFFFFE060); s.px(hx + back, hy - 1, 0xFFFFE060);
            s.px(hx + 2 * back, hy + 1, 0xFF58A848);
        }
        if (cx1 >= 0 && (se.onam != Seasons.FAR || se.diwali != Seasons.FAR)) {   // a garland on his collar
            boolean onam = se.onam != Seasons.FAR;               // jasmine for Onam, marigold for Diwali
            for (int x = cx0 - 1; x <= cx1 + 1; x++) {
                int c = onam ? ((x & 1) == 0 ? 0xFFF8F4E8 : 0xFFE8E0C8) : ((x & 1) == 0 ? 0xFFFF9A20 : 0xFFFFC840);
                s.px(x, cy + 1, c);
            }
            int mid = (cx0 + cx1) / 2;
            s.px(mid, cy + 2, onam ? 0xFFF8F4E8 : 0xFFFF9A20);
            s.px(mid, cy + 3, onam ? 0xFF58A848 : 0xFFE83A1A);
        }
    }

    static char at(String[] art, int r, int c) {
        return r >= 0 && r < art.length && c >= 0 && c < art[r].length() ? art[r].charAt(c) : '.';
    }

    /** Darker at night, unless something nearby is glowing on him. */
    float shadeAt(Stage s) {
        int i = Math.max(0, Math.min(Stage.W * Stage.H - 1, (ground() - 6) * Stage.W + Math.round(x)));
        float lit = s.warm[i] + s.cool[i] + s.amb[i];
        return Math.max(0f, Math.min(0.3f, s.life.dark * 0.3f - lit * 0.3f));
    }
}
