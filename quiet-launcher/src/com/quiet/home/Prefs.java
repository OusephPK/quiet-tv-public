package com.quiet.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The home screen's preferences, shared with Quiet Settings. Guarded by a signature permission,
 * so only apps signed with the same key as this launcher can read or change them.
 *
 *   soundMode  on | nature | off     the home-screen soundtrack
 *   motion     instant | standard    animation speed across the TV
 *   soundLevel soft | normal | loud  how loud the soundtrack sits
 *   party      on | off              the world dances to Apple Music
 *   world      (see px.World)        where Pip is, his bone, the days, the door: one world across pages
 *   nowPlaying 1|genre or 0          Apple Music playing (for the other pages' party)
 *   voiceKey   (write only)          the OpenAI key for the voice, sent from a phone through Drop;
 *                                    it's never read back out: queries see only voice = ready | nokey
 */
public class Prefs extends ContentProvider {

    static final String[] KEYS = {"soundMode", "motion", "soundLevel", "party", "world", "nowPlaying"};
    static final String[] DEFAULTS = {"on", "instant", "normal", "on", "", "0"};

    static SharedPreferences prefs(android.content.Context c) {
        return c.getSharedPreferences("quiet", android.content.Context.MODE_PRIVATE);
    }

    static String get(android.content.Context c, String key) {
        SharedPreferences p = prefs(c);
        if (key.equals("soundMode") && !p.contains("soundMode"))      // older builds stored a boolean
            return p.getBoolean("sound", true) ? "on" : "off";
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return p.getString(key, DEFAULTS[i]);
        return "";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        for (String k : KEYS) c.addRow(new Object[]{k, get(getContext(), k)});
        SharedPreferences p = prefs(getContext());
        c.addRow(new Object[]{"voice", p.getString("voiceKey", "").isEmpty() ? "nokey" : "ready"});
        c.addRow(new Object[]{"inbox", Inbox.PORT + ":" + Inbox.token(getContext())});   // for Drop's pairing link
        c.addRow(new Object[]{"wake", p.getString("wakeModel", "").isEmpty() ? "untaught"
                : "on".equals(p.getString("wakeOn", "")) ? "on:" + p.getString("wakeText", "") : "off"});
        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        SharedPreferences.Editor e = prefs(getContext()).edit();
        int n = 0;
        for (String k : KEYS) {
            if (values.containsKey(k)) {
                e.putString(k, values.getAsString(k));
                n++;
            }
        }
        if (values.containsKey("voiceKey")) {
            String k = values.getAsString("voiceKey");
            e.putString("voiceKey", k == null ? "" : k.trim());
            n++;
        }
        e.commit();                                     // the home screen listens and applies it
        return n;
    }

    /**
     * The other Quiet apps' way to the AI (method "ask"; extras system, user, max, json): the call
     * runs here, with the key that only home holds. Quiet apps only; callers wait on a background
     * thread. Answers come back as "text", or "error" (NO KEY, or what went wrong).
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        android.os.Bundle out = new android.os.Bundle();
        if (getContext().checkCallingPermission("com.quiet.permission.PREFS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Quiet apps only");
        if ("box".equals(method)) {                     // a Quiet page's search opened ("open") or closed ("")
            String pkg = getCallingPackage();
            if (pkg != null) {
                if (arg != null && !arg.isEmpty()) Voice.quietBox = pkg;
                else if (pkg.equals(Voice.quietBox)) Voice.quietBox = "";
            }
            return out;
        }
        if (!"ask".equals(method) || extras == null) return out;
        String key = prefs(getContext()).getString("voiceKey", "");
        if (key.isEmpty()) {
            out.putString("error", "NO KEY");
            return out;
        }
        try {
            out.putString("text", new Brain(key).ask(extras.getString("system", ""), extras.getString("user", ""),
                    extras.getInt("max", 600), extras.getBoolean("json", false), extras.getBoolean("deep", false)));
        } catch (Exception e) {
            out.putString("error", e.getMessage() == null ? "COULDN'T REACH THE CLOUD" : e.getMessage());
        }
        return out;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.quiet.prefs"; }
    @Override public Uri insert(Uri uri, ContentValues values) { update(uri, values, null, null); return uri; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
