package com.quiet.px;

import java.util.ArrayList;
import java.util.List;

/**
 * The system bar: a small row of pixel icons in the top-right corner of every Quiet screen.
 * Content (apps, songs, files) lives in the main list; system controls live here. Press UP from
 * the top of the list to reach it. The label appears only under the icon you're on.
 */
public final class IconBar {

    /** 9×9 pixel icons, one family: 1-pixel strokes, square corners, no fills. */
    public static final String[] INPUTS = {
            "..#...#..", "..#...#..", ".#######.", ".#.....#.", ".#.....#.",
            "..#...#..", "...###...", "....#....", "....#...."};
    public static final String[] APPS = {
            "##.##.##.", "##.##.##.", ".........", "##.##.##.", "##.##.##.",
            ".........", "##.##.##.", "##.##.##.", "........."};
    public static final String[] SETTINGS = {
            "...###...", ".#.#.#.#.", "..#####..", "###...###", "#.#...#.#",
            "###...###", "..#####..", ".#.#.#.#.", "...###..."};
    public static final String[] SOUND_ON = {
            "...#.....", "..##...#.", "####.#..#", "#..#..#.#", "#..#..#.#",
            "####.#..#", "..##...#.", "...#.....", "........."};
    public static final String[] SOUND_OFF = {
            "...#.....", "..##.....", "####.#.#.", "#..#..#..", "#..#.#.#.",
            "####.....", "..##.....", "...#.....", "........."};
    public static final String[] PLAYING = {
            "...######", "...#....#", "...#....#", "...#....#", ".###..###",
            "####.####", ".##...##.", ".........", "........."};
    public static final String[] PLAY = {
            "..#......", "..##.....", "..#.#....", "..#..#...", "..#...#..",
            "..#..#...", "..#.#....", "..##.....", "..#......"};
    public static final String[] PAUSE = {
            ".........", ".###.###.", ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.",
            ".#.#.#.#.", ".#.#.#.#.", ".###.###.", "........."};
    public static final String[] MOON = {
            "...####..", "..##...#.", ".##......", ".##......", ".##......",
            ".##......", ".##......", "..##.....", "...####.."};
    public static final String[] FILM = {
            "#########", "#.#.#.#.#", "#########", "#.......#", "#.......#",
            "#.......#", "#########", "#.#.#.#.#", "#########"};
    public static final String[] REFRESH = {
            "..####...", ".#....#..", "#......#.", "#.....###", "#......#.",
            "#........", ".#....#..", "..####...", "........."};
    public static final String[] STORE = {
            "..#####..", "..#...#..", "#########", "#.......#", "#.......#",
            "#.......#", "#.......#", "#########", "........."};
    public static final String[] SEARCH = {
            ".####....", "#....#...", "#....#...", "#....#...", "#....#...",
            ".####....", ".....##..", "......##.", ".......##"};
    public static final String[] USER = {
            "...###...", "..#...#..", "..#...#..", "...###...", ".........",
            ".#######.", "#.......#", "#.......#", "#########"};

    public static final class Icon {
        public String[] art;
        public String label;
        public Object tag;
        public boolean lit;            // amber dot: something is on / happening

        public Icon(String[] art, String label, Object tag) {
            this.art = art;
            this.label = label;
            this.tag = tag;
        }
    }

    static final int CELL = 13, GAP = 4;

    public final List<Icon> icons = new ArrayList<>();
    public int focus;
    public int right = 308, top = 10;

    public void set(List<Icon> list) {
        Object keep = focused() == null ? null : focused().tag;
        icons.clear();
        icons.addAll(list);
        focus = 0;
        for (int i = 0; i < icons.size(); i++) if (icons.get(i).tag.equals(keep)) focus = i;
    }

    public Icon focused() {
        return focus >= 0 && focus < icons.size() ? icons.get(focus) : null;
    }

    public boolean move(int d) {
        int n = Math.max(0, Math.min(icons.size() - 1, focus + d));
        boolean moved = n != focus;
        focus = n;
        return moved;
    }

    int x(int i) {
        return right - (icons.size() - i) * (CELL + GAP) + GAP;
    }

    /** The dark stone lintel the icons are set into: always a calm ground, whatever the sky does. */
    void lintel(Stage s) {
        int x0 = x(0) - 4, x1 = right + 3, y0 = top - 3, y1 = top + CELL + 2;
        s.rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1, Pal.STONE[2]);
        s.rect(x0 + 1, y0, x1 - x0 - 1, 1, Pal.STONE[4]);          // the worn top edge catches the light
        s.rect(x0 + 1, y0 + 1, x1 - x0 - 1, 1, Pal.STONE[3]);
        s.rect(x0, y1 + 1, x1 - x0 + 1, 1, Pal.VOID);              // its shadow on the wall
        s.px(x0, y0, Pal.VOID);                                     // chipped corners
        s.px(x1, y0, Pal.VOID);
        s.px(x1, y0 + 1, Pal.STONE[1]);
        s.px(x0, y1, Pal.STONE[1]);
        for (int i = 0; i + 1 < icons.size(); i++) {                // hairline joints between the stones
            int jx = x(i) + CELL + GAP / 2 - 1;
            for (int y = y0 + 2; y <= y1; y++) if (((y + i) % 5) != 0) s.px(jx, y, Pal.STONE[1]);
        }
    }

    public void draw(Stage s, boolean active) {
        if (icons.isEmpty()) return;
        lintel(s);
        for (int i = 0; i < icons.size(); i++) {
            Icon ic = icons.get(i);
            int x = x(i), y = top;
            boolean f = active && i == focus;
            if (f) {
                s.rect(x, y, CELL, CELL, Pal.AMBER);
                s.rect(x + 1, y + 1, CELL - 2, CELL - 2, Pal.VOID);
            }
            int col = f ? Pal.TEXT : active ? Pal.SOFT : Pal.DIM;
            for (int r = 0; r < 9; r++)
                for (int c = 0; c < 9; c++)
                    if (ic.art[r].charAt(c) == '#') {
                        s.px(x + 2 + c, y + 3 + r, Pal.VOID);   // shadow
                        s.px(x + 2 + c, y + 2 + r, col);
                    }
            if (ic.lit) s.rect(x + CELL - 3, y + 1, 2, 2, Pal.AMBER);
        }
        Icon f = focused();
        if (active && f != null) {
            String label = f.label;
            int w = Font.microWidth(label, 2);
            int cx = x(focus) + CELL / 2;
            int lx = Math.min(right - w, cx - w / 2);
            s.small(label, lx, top + CELL + 6, 2, Pal.TEXT);
        }
    }

    /** Lights the selected icon, as if Mote were reading it. Call before resolve(). */
    public void lights(Stage s) {
        if (focused() == null) return;
        s.light(x(focus) + CELL / 2f, top + CELL / 2f, 30, 0.3f, true);
    }

    /** Mote hovers just under the selected icon's label. */
    public float perchX() {
        return x(focus) + CELL / 2f;
    }

    public float perchY() {
        return top + CELL + 17;
    }
}
