package com.quiet.px;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertical list of words. Two voices: names in the 7-pixel font, actions and notes in the
 * 5-pixel one. Mote hovers beside whichever row is selected.
 */
public final class Menu {

    public static final class Item {
        public String label;
        public String note = "";
        public boolean hot;            // note in amber
        public boolean small;          // an action in the small voice, like "ALL APPS"
        public boolean dot;            // amber marker, e.g. pinned
        public boolean gap;            // spacer, not selectable
        public boolean hint;           // the note is a quiet hint: only beside the focused row, faint
        public float progress = -1f;   // 0..1 draws a bar under the row
        public Object tag;

        public Item(String label) {
            this.label = label;
        }

        public static Item gap() {
            Item i = new Item("");
            i.gap = true;
            return i;
        }

        public static Item action(String label, Object tag) {
            Item i = new Item(Font.upper(label));
            i.small = true;
            i.tag = tag;
            return i;
        }

        int height() {
            return gap ? 6 : small ? 9 : 12;
        }

        int textHeight() {
            return small ? Font.MICRO_HEIGHT : Font.HEIGHT;
        }
    }

    public final List<Item> items = new ArrayList<>();
    public int focus;
    public int x, top, height;
    /** False while the icon bar has focus: the list keeps its place but stops glowing. */
    public boolean active = true;
    int scroll;

    public Menu(int x, int top, int height) {
        this.x = x;
        this.top = top;
        this.height = height;
    }

    public Item focused() {
        return focus >= 0 && focus < items.size() ? items.get(focus) : null;
    }

    /** 0 at the top of the list, 1 at the bottom. */
    public float fraction() {
        return items.size() <= 1 ? 0.5f : focus / (float) (items.size() - 1);
    }

    public void set(List<Item> list, int focusIndex) {
        items.clear();
        items.addAll(list);
        focus = Math.max(0, Math.min(focusIndex, items.size() - 1));
        if (focused() != null && focused().gap) move(1);
    }

    /** Moves selection by one selectable row; returns true if it moved. */
    public boolean move(int d) {
        int i = focus;
        do {
            i += d;
        } while (i >= 0 && i < items.size() && items.get(i).gap);
        if (i < 0 || i >= items.size()) return false;
        focus = i;
        return true;
    }

    int rowY(int index) {
        int y = 0;
        for (int i = 0; i < index; i++) y += items.get(i).height();
        return y;
    }

    void keepInView() {
        if (items.isEmpty()) return;
        int fy = rowY(focus), fh = items.get(focus).height();
        if (fy - scroll < 0) scroll = fy;
        if (fy + fh - scroll > height) scroll = fy + fh - height;
    }

    public void draw(Stage s) {
        if (items.isEmpty()) return;
        keepInView();
        int total = rowY(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int y = top + rowY(i) - scroll;
            if (it.gap || y < top || y + it.textHeight() > top + height) continue;
            boolean f = active && i == focus;
            int end;
            if (it.small) end = s.small(it.label, x, y, 2, f ? Pal.TEXT : Pal.DIM);
            else end = s.text(it.label, x, y, 1, f ? Pal.TEXT : Pal.SOFT);
            if (it.dot && !f) s.px(x - 4, y + 3, Pal.AMBER);
            if (!it.note.isEmpty() && (!it.hint || f)) {
                int ny = it.small ? y : y + 1;
                if (it.hint) s.small(it.note, end + 5, ny + 1, 1, Pal.FAINT);
                else s.small(it.note, end + 7, ny, 1, it.hot ? Pal.AMBER : f ? Pal.SOFT : Pal.DIM);
            }
            if (it.progress >= 0) {
                int bw = Math.max(40, end - x);
                s.rect(x, y + 9, bw, 1, Pal.FAINT);
                s.rect(x, y + 9, Math.round(bw * Math.min(1f, it.progress)), 1, Pal.AMBER);
            }
        }
        if (scroll > 0) s.small("▲", x, top - 7, 1, Pal.FAINT);
        if (total - scroll > height) s.small("▼", x, top + height + 1, 1, Pal.FAINT);
    }

    /** The selected row glows faintly, as if Mote's lamp were reading it. Call before resolve(). */
    public void lights(Stage s) {
        Item it = focused();
        if (it == null || !active) return;
        keepInView();
        int y = top + rowY(focus) - scroll;
        s.light(x + Font.width(it.label, 1) / 2f, y + 3, 30, 0.2f, true);
    }

    /** Where Mote hovers for the selected row: just left of the words, level with them. */
    public float perchX() {
        return x - 10;
    }

    public float perchY() {
        Item it = focused();
        int h = it == null ? Font.HEIGHT : it.textHeight();
        return top + rowY(focus) - scroll + h / 2f - 0.5f;
    }
}
