package com.quiet.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What's been played on this TV, in every app: every app that plays tells Android what it's playing
 * (YouTube's video and channel, Netflix's show, Apple Music's song, Stremio's film), and home's Listener
 * hears all of them. Kept here, newest first, with where it was left, so the TV knows "the video I was
 * watching", "the show from last night", "that song again", whatever app it was in. Stays in home's
 * private prefs (the last 80), never leaves the TV except as words for the AI when you ask.
 */
final class History {

    static final int KEEP = 300;                              // enough to see habits by time of day
    static final java.util.Set<String> quiet = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private History() {
    }

    /** What a session is playing (or just stopped on), noted: a new entry, or the same one updated. */
    static synchronized void note(Context c, MediaController m) {
        try {
            MediaMetadata md = m.getMetadata();
            PlaybackState st = m.getPlaybackState();
            String title = md == null ? "" : first(md.getString(MediaMetadata.METADATA_KEY_TITLE), md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            if (title.isEmpty()) {                             // an app that doesn't say what it plays (once per app)
                if (quiet.add(m.getPackageName()))
                    android.util.Log.i(Voice.TAG, "played: [" + m.getPackageName() + "] says no title (state "
                            + (st == null ? "none" : st.getState()) + (md == null ? ", no metadata" : ", keys " + md.keySet()) + ")");
                return;
            }
            String by = first(md.getString(MediaMetadata.METADATA_KEY_ARTIST), md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            long len = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long at = st == null ? -1 : st.getPosition();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            String pkg = m.getPackageName();
            SharedPreferences p = Prefs.prefs(c);
            JSONArray old = new JSONArray(p.getString("history", "[]"));
            JSONObject top = old.length() > 0 ? old.getJSONObject(0) : null;
            long now = System.currentTimeMillis();
            boolean same = top != null && top.optString("app").equals(pkg) && top.optString("title").equals(title);
            if (!same && !playing) return;                     // only what actually played starts an entry
            JSONObject e = same ? top : new JSONObject().put("app", pkg).put("title", title).put("from", now);
            if (!same) android.util.Log.i(Voice.TAG, "played: [" + pkg + "] '" + title + "'" + (by.isEmpty() ? "" : " by " + by));
            e.put("by", by).put("last", now);
            if (len > 0) e.put("len", len);
            if (at >= 0) e.put("at", at);
            JSONArray out = new JSONArray().put(e);
            for (int i = same ? 1 : 0; i < old.length() && out.length() < KEEP; i++) out.put(old.get(i));
            p.edit().putString("history", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static String first(String... s) {
        for (String x : s) if (x != null && !x.trim().isEmpty()) return x.trim();
        return "";
    }

    /** The recent ones, as lines for the AI: app, title, by, when, where it was left. */
    static String recent(Context c, int n) {
        StringBuilder b = new StringBuilder();
        try {
            JSONArray a = new JSONArray(Prefs.prefs(c).getString("history", "[]"));
            SimpleDateFormat when = new SimpleDateFormat("EEE d MMM h:mm a", Locale.ENGLISH);
            for (int i = 0; i < a.length() && i < n; i++) {
                JSONObject e = a.getJSONObject(i);
                b.append("- [").append(app(c, e.optString("app"))).append("] \"").append(e.optString("title")).append('"');
                if (!e.optString("by").isEmpty()) b.append(" by ").append(e.optString("by"));
                b.append(", ").append(when.format(new Date(e.optLong("last"))));
                long at = e.optLong("at", -1), len = e.optLong("len", -1);
                if (at > 0 && len > 0) b.append(at > len * 0.95 ? ", finished" : ", left at " + clock(at) + " of " + clock(len));
                else if (at > 0) b.append(", left at ").append(clock(at));
                b.append('\n');
            }
        } catch (Exception ignored) {
        }
        return b.toString();
    }

    /** Where this was left, in seconds, if it was played here and not finished (for resuming); else -1. */
    static long leftAt(Context c, String pkg, String title) {
        try {
            JSONArray a = new JSONArray(Prefs.prefs(c).getString("history", "[]"));
            String want = norm(title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                if (!e.optString("app").equals(pkg)) continue;
                String t = norm(e.optString("title"));
                if (t.isEmpty() || !(t.equals(want) || t.contains(want) || want.contains(t))) continue;
                long at = e.optLong("at", -1), len = e.optLong("len", -1);
                if (at < 30_000 || len > 0 && at > len * 0.95) return -1;
                return at / 1000;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static String app(Context c, String pkg) {
        switch (pkg) {
            case "com.google.android.youtube.tv": return "YouTube";
            case "com.netflix.ninja": return "Netflix";
            case "com.quiet.listen": return "Apple Music";
            case "com.stremio.one": return "Stremio";
            default:
                try {
                    return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
                } catch (Exception e) {
                    return pkg;
                }
        }
    }
}
