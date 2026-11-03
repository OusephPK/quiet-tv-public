package com.quiet.px;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * One world across every Quiet page. Each app draws its own copy of the old road, so as you leave
 * a page it writes down where everyone is (home keeps it, in its prefs provider), and the next page
 * picks it up: Pip is where you left him (or trots in from the edge to catch you up), his bone
 * likewise, and the days you've come home and the door are the same everywhere.
 */
public final class World {

    /** Today's festival on the bank, in words ("Diwali"), or "". */
    public static String festival() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        Seasons s = new Seasons();
        s.read(c, c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
        if (s.diwali != Seasons.FAR) return "Diwali (a row of diyas on the bank, fireworks at night)";
        if (s.onam != Seasons.FAR) return "Onam";
        if (s.vishu != Seasons.FAR) return "Vishu";
        if (s.holi != Seasons.FAR) return "Holi";
        if (s.eid != Seasons.FAR) return "Eid";
        if (s.ramadan) return "Ramadan";
        if (s.halloween != Seasons.FAR) return "Halloween (jack-o'-lanterns, more bats)";
        if (s.christmas) return "Christmas (snow through the opening, a star on the keystone)";
        if (s.kites) return "Sankranti kites";
        return s.monsoon > 0.5f ? "the monsoon" : "";
    }

    /**
     * What Skye (the dog) and the others are most likely doing at this hour, in words, as the world
     * itself has them (LIFE.md): weather and music first, then the time of day.
     */
    public static String now(float hour, boolean music) {
        String skye;
        if (music) skye = "dancing on the bank with everyone, since music is playing";
        else if (Weather.storm()) skye = "hiding on his bed, flinching at the thunder, Mote beside him; the frog loves it";
        else if (Weather.rain()) skye = "sheltering under the arch at the road's mouth, staying dry; the frog is out croaking";
        else if (Weather.snow()) skye = "hopping at the snowflakes; the frog stays under";
        else if (hour >= 5 && hour < 7) skye = "just up: a stretch, then a drink at the road's mouth; birds at the opening";
        else if (hour >= 7 && hour < 12) skye = "patrolling the bank and playing with his bone; butterflies in the light";
        else if (hour >= 12 && hour < 16) skye = "napping in the shade by the wall; the frog sunbathes on its stone";
        else if (hour >= 16 && hour < 18.5f) skye = "about to have his evening mad dash; bats are coming out";
        else if (hour >= 18.5f && hour < 20.5f) skye = "fed (his bowl was found filled again) and getting sleepy; the frog has started croaking";
        else skye = "asleep on his bed with Mote curled on his back; the mouse is out, fireflies in the grove, frogs singing";
        return skye + (Weather.fog() ? "; it's foggy, so everyone stays close to home" : "");
    }

    /** The real moon tonight, in words. */
    public static String moon() {
        long days = (System.currentTimeMillis() - 947182440000L) / 86_400_000L;
        double p = (days % 29.530588) / 29.530588;
        return p < 0.03 || p > 0.97 ? "new moon" : p < 0.22 ? "waxing crescent" : p < 0.28 ? "first quarter"
                : p < 0.47 ? "waxing gibbous" : p < 0.53 ? "full moon" : p < 0.72 ? "waning gibbous"
                : p < 0.78 ? "last quarter" : "waning crescent";
    }
    static final Uri PREFS = Uri.parse("content://com.quiet.home.prefs/all");

    public static void save(Context c, Stage s) {
        try {
            ContentValues v = new ContentValues();
            v.put("world", s.snapshot());
            c.getContentResolver().update(PREFS, v, null, null);
        } catch (Exception ignored) {
        }
    }

    /** Apple Music as the home screen hears it, and the party switch: for pages that can't listen themselves. */
    public static void music(Context c, Stage s) {
        try (Cursor k = c.getContentResolver().query(PREFS, null, null, null, null)) {
            while (k != null && k.moveToNext()) {
                String key = k.getString(0), v = k.getString(1);
                if ("nowPlaying".equals(key)) {
                    s.music = v != null && v.startsWith("1");
                    s.genre = v != null && v.length() > 2 ? v.substring(2) : "";
                } else if ("party".equals(key)) s.partyAllowed = !"off".equals(v);
            }
        } catch (Exception ignored) {
        }
    }

    public static void load(Context c, Stage s) {
        try (Cursor k = c.getContentResolver().query(PREFS, null, null, null, null)) {
            while (k != null && k.moveToNext())
                if ("world".equals(k.getString(0))) s.restore(k.getString(1));
        } catch (Exception ignored) {
        }
    }
}
