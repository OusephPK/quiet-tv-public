package com.quiet.px;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

/**
 * A Quiet page's search box, for the voice. Our pages draw their own letters, so nothing outside can
 * read or type into them; this tells home (the voice) while the search screen is open, and takes the
 * words it hears ("com.quiet.TYPE", Quiet apps only) into the page's own search.
 */
public final class Box {

    /** The page's side: is its search showing, what's in it, and put these words in it (and search). */
    public interface Page {
        boolean searching();

        String query();

        void typed(String words);
    }

    /**
     * A search box's words after a spoken edit: "clear" empties it, "word" takes the last word off,
     * "add" puts more on the end; anything else is the new words themselves.
     */
    public static String edit(String now, String op, String words) {
        now = now == null ? "" : now.trim();
        words = words == null ? "" : words.trim();
        if ("clear".equals(op)) return "";
        if ("word".equals(op)) {
            int cut = now.lastIndexOf(' ');
            return cut < 0 ? "" : now.substring(0, cut).trim();
        }
        if ("add".equals(op)) return (now + " " + words).trim();
        return words;
    }

    static final Uri PREFS = Uri.parse("content://com.quiet.home.prefs");

    final Activity activity;
    final Page page;
    boolean open, listening;

    public Box(Activity a, Page p) {
        activity = a;
        page = p;
    }

    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String w = edit(page.query(), i.getStringExtra("op"), i.getStringExtra("text"));
            if (!w.isEmpty() || i.getStringExtra("op") != null) page.typed(w);
        }
    };

    public void resume() {
        open = false;                                        // said afresh on the next check
        try {
            activity.registerReceiver(rx, new IntentFilter("com.quiet.TYPE"), "com.quiet.permission.PREFS", null);
            listening = true;
        } catch (Exception ignored) {
        }
    }

    public void pause() {
        if (listening) {
            try {
                activity.unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
            listening = false;
        }
        if (open) tell(false);
        open = false;
    }

    /** Each frame: home hears about it only when the search screen opens or closes. */
    public void check() {
        boolean now = page.searching();
        if (now != open) {
            open = now;
            tell(now);
        }
    }

    void tell(boolean on) {
        Context app = activity.getApplicationContext();
        PxActivity.ASK.execute(() -> {
            try {
                app.getContentResolver().call(PREFS, "box", on ? "open" : "", null);
            } catch (Exception ignored) {
            }
        });
    }
}
