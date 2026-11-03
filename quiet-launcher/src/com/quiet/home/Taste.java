package com.quiet.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What they've told the voice about their taste ("doesn't like Hindi music", "loves lo-fi in the evening"), kept in
 * home's private prefs and given with every ask, and their habits as they show in what played here (History): which
 * apps and artists at which time of day. Never leaves the TV except as words for the AI when they ask something.
 */
final class Taste {

    static final int KEEP = 30;

    private Taste() {
    }

    static JSONArray notes(Context c) {
        try {
            return new JSONArray(Prefs.prefs(c).getString("taste", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized String remember(Context c, String fact) {
        fact = fact == null ? "" : fact.trim();
        if (fact.isEmpty()) return "Nothing to remember.";
        try {
            JSONArray old = notes(c), out = new JSONArray();
            for (int i = 0; i < old.length(); i++)
                if (!Voice.plain(old.getJSONObject(i).optString("fact")).equals(Voice.plain(fact))) out.put(old.get(i));
            out.put(new JSONObject().put("fact", fact).put("at", System.currentTimeMillis()));
            while (out.length() > KEEP) out.remove(0);
            Prefs.prefs(c).edit().putString("taste", out.toString()).apply();
            android.util.Log.i(Voice.TAG, "taste: remembered '" + fact + "'");
            return "Remembered: " + fact;
        } catch (Exception e) {
            return "Couldn't remember that.";
        }
    }

    /** Drops the note that shares the most words with what they named. */
    static synchronized String forget(Context c, String about) {
        try {
            JSONArray old = notes(c);
            java.util.Set<String> want = new java.util.HashSet<>(java.util.Arrays.asList(Voice.plain(about).split(" ")));
            int best = -1, bestScore = 0;
            for (int i = 0; i < old.length(); i++) {
                int score = 0;
                for (String w : Voice.plain(old.getJSONObject(i).optString("fact")).split(" ")) if (w.length() > 2 && want.contains(w)) score++;
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) return "No note like that.";
            String gone = old.getJSONObject(best).optString("fact");
            old.remove(best);
            Prefs.prefs(c).edit().putString("taste", old.toString()).apply();
            android.util.Log.i(Voice.TAG, "taste: forgot '" + gone + "'");
            return "Forgot: " + gone;
        } catch (Exception e) {
            return "Couldn't forget that.";
        }
    }

    /** The notes, for the AI: "- doesn't like Hindi music (3 Nov)". */
    static String told(Context c) {
        StringBuilder b = new StringBuilder();
        JSONArray n = notes(c);
        SimpleDateFormat d = new SimpleDateFormat("d MMM", Locale.ENGLISH);
        for (int i = 0; i < n.length(); i++) {
            JSONObject x = n.optJSONObject(i);
            if (x != null) b.append("- ").append(x.optString("fact")).append(" (").append(d.format(new Date(x.optLong("at")))).append(")\n");
        }
        return b.toString();
    }

    /**
     * Their habits from what played here, by time of day: for each part of the day, the apps used most and the
     * artists or channels that came up most ("evenings: YouTube (a couple of channels); Apple Music (an artist or two)").
     */
    static String habits(Context c) {
        try {
            JSONArray a = new JSONArray(Prefs.prefs(c).getString("history", "[]"));
            String[] parts = {"mornings (5-12)", "afternoons (12-17)", "evenings (17-22)", "nights (22-5)"};
            java.util.List<java.util.Map<String, java.util.Map<String, Integer>>> by = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) by.add(new java.util.LinkedHashMap<>());
            java.util.Calendar cal = java.util.Calendar.getInstance();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                cal.setTimeInMillis(e.optLong("from", e.optLong("last")));
                int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
                int p = h >= 5 && h < 12 ? 0 : h >= 12 && h < 17 ? 1 : h >= 17 && h < 22 ? 2 : 3;
                String app = History.app(c, e.optString("app"));
                java.util.Map<String, Integer> who = by.get(p).computeIfAbsent(app, k -> new java.util.LinkedHashMap<>());
                String w = e.optString("by").trim();
                who.merge(w.isEmpty() ? "" : w, 1, Integer::sum);
            }
            StringBuilder b = new StringBuilder();
            for (int p = 0; p < 4; p++) {
                if (by.get(p).isEmpty()) continue;
                java.util.List<java.util.Map.Entry<String, java.util.Map<String, Integer>>> apps = new java.util.ArrayList<>(by.get(p).entrySet());
                apps.sort((x, y) -> total(y.getValue()) - total(x.getValue()));
                b.append(parts[p]).append(": ");
                for (int i = 0; i < apps.size() && i < 3; i++) {
                    java.util.List<java.util.Map.Entry<String, Integer>> w = new java.util.ArrayList<>(apps.get(i).getValue().entrySet());
                    w.sort((x, y) -> y.getValue() - x.getValue());
                    StringBuilder names = new StringBuilder();
                    for (int k = 0, shown = 0; k < w.size() && shown < 4; k++)
                        if (!w.get(k).getKey().isEmpty()) {
                            names.append(shown++ > 0 ? ", " : "").append(w.get(k).getKey());
                        }
                    b.append(i > 0 ? "; " : "").append(apps.get(i).getKey()).append(" x").append(total(apps.get(i).getValue()))
                            .append(names.length() > 0 ? " (" + names + ")" : "");
                }
                b.append('\n');
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static int total(java.util.Map<String, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }
}
