package com.quiet.settings;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import com.quiet.px.Font;
import com.quiet.px.IconBar;
import com.quiet.px.Menu;
import com.quiet.px.Pal;
import com.quiet.px.PxActivity;
import com.quiet.px.Stage;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings — the TV's settings, in the well.
 *
 * What we can change directly is changed right here (home music, motion, screensaver, sleep,
 * tidying memory). Everything else shows its current state and opens the matching system page;
 * those rows carry an arrow. The stock pages can't be restyled without rooting the TV.
 */
public class Main extends PxActivity {

    static final Uri PREFS = Uri.parse("content://com.quiet.home.prefs/all");
    static final String NETWORK = "NETWORK", PICTURE = "PICTURE", SOUND = "SOUND", MUSIC = "HOME MUSIC", LOUDNESS = "LOUDNESS", PARTY = "MUSIC PARTY",
            MOTION = "MOTION", SAVER = "SCREENSAVER", SAVER_AFTER = "STARTS AFTER", SLEEP = "SLEEP", STORAGE = "STORAGE", MEMORY = "MEMORY",
            APPS = "APPS", VOICE = "VOICE", WAKE = "WAKE WORD", REMOTES = "REMOTES", DATE = "DATE & TIME", LANGUAGE = "LANGUAGE", ABOUT = "ABOUT";
    static final String ALL = "\u0001all";
    static final String ARROW = "→";
    static final int[] SAVER_MIN = {5, 10, 15, 30, 60};
    static final String QUIET_SAVER = "com.quiet.home/com.quiet.home.Dream",
            AERIAL = "com.neilturner.aerialviews/com.neilturner.aerialviews.ui.screensaver.DreamActivity";
    static final int[] SLEEP_MIN = {30, 60, 120, 240, 480};

    final Handler ui = new Handler();
    String freed = "";
    long refreshAt;

    @Override
    protected Menu makeMenu() {
        return new Menu(26, 50, 104);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        stage.dogMin = 214;                  // the legend runs along the bank on the left
        stage.dogMax = 300;
        stage.placeDog(250);
        bar = new IconBar();
        List<IconBar.Icon> icons = new ArrayList<>();
        icons.add(new IconBar.Icon(IconBar.SETTINGS, "ALL SYSTEM SETTINGS", ALL));
        bar.set(icons);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    // ---------------------------------------------------------------- rows

    void rebuild() {
        refreshAt = SystemClock.uptimeMillis() + 5000;
        List<Menu.Item> items = new ArrayList<>();
        items.add(row(NETWORK, network(), false));
        items.add(Menu.Item.gap());
        items.add(row(PICTURE, ARROW, false));
        items.add(row(SOUND, ARROW, false));
        items.add(row(MUSIC, soundMode().equals("on") ? "MUSIC + NATURE" : soundMode().equals("nature") ? "NATURE ONLY" : "OFF", true));
        items.add(row(LOUDNESS, soundLevel().toUpperCase(java.util.Locale.ROOT), true));
        items.add(row(PARTY, pref("party", "on").equals("off") ? "OFF" : "ON · THE WORLD DANCES", true));
        items.add(row(MOTION, motion().equals("standard") ? "STANDARD" : "INSTANT", true));
        items.add(row(VOICE, pref("voice", "nokey").equals("ready") ? "READY · THE REMOTE'S VOICE BUTTON" : "NEEDS YOUR OPENAI KEY", false));
        String wake = pref("wake", "untaught");
        items.add(row(WAKE, wake.equals("untaught") ? "NOT TAUGHT YET" : wake.startsWith("on")
                ? "ON" + (wake.length() > 3 ? " · '" + Font.upper(wake.substring(3)) + "'" : "") : "OFF", !wake.equals("untaught")));
        items.add(Menu.Item.gap());
        items.add(row(SAVER, saver(), true));
        items.add(row(SAVER_AFTER, minutes(Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000)), true));
        String sleep = minutes(Settings.Secure.getInt(getContentResolver(), "sleep_timeout", 1_800_000));
        items.add(row(SLEEP, sleep.equals("NEVER") ? sleep : "AFTER " + sleep, true));
        items.add(Menu.Item.gap());
        items.add(row(STORAGE, storage(), false));
        items.add(row(MEMORY, freed.isEmpty() ? memory() + " · OK TO TIDY" : freed, !freed.isEmpty()));
        items.add(row(APPS, ARROW, false));
        items.add(Menu.Item.gap());
        items.add(row(REMOTES, ARROW, false));
        items.add(row(DATE, ARROW, false));
        items.add(row(LANGUAGE, ARROW, false));
        items.add(row(ABOUT, "ANDROID " + Build.VERSION.RELEASE + " · UP " + uptime(), false));
        menu.set(items, menu.focus);
    }

    Menu.Item row(String name, String note, boolean adjustable) {
        Menu.Item it = new Menu.Item(name);
        it.tag = name;
        it.note = note;
        it.hot = adjustable;          // amber: OK changes it right here
        return it;
    }

    @Override
    protected void select(Menu.Item item) {
        String tag = (String) item.tag;
        switch (tag) {
            case WAKE: {                                      // the voice (in home) teaches it or switches it
                String w = pref("wake", "untaught");
                wakeSay(w.equals("untaught") ? "teach" : w.startsWith("on") ? "off" : "on");
                ui.postDelayed(this::rebuild, 600);
                break;
            }
            case VOICE: {                                     // the key travels from a phone through Drop
                Intent d = getPackageManager().getLeanbackLaunchIntentForPackage("com.quiet.drop");
                if (d != null) startActivity(d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            }
            case NETWORK: open(Settings.ACTION_WIFI_SETTINGS); break;
            case PICTURE: open(Settings.ACTION_DISPLAY_SETTINGS); break;
            case SOUND: open(Settings.ACTION_SOUND_SETTINGS); break;
            case MUSIC: {
                String m = soundMode();
                setPref("soundMode", m.equals("on") ? "nature" : m.equals("nature") ? "off" : "on");
                break;
            }
            case LOUDNESS: {
                String l = soundLevel();
                setPref("soundLevel", l.equals("soft") ? "normal" : l.equals("normal") ? "loud" : "soft");
                break;
            }
            case PARTY:
                setPref("party", pref("party", "on").equals("off") ? "on" : "off");
                break;
            case MOTION:
                setPref("motion", motion().equals("standard") ? "instant" : "standard");
                break;
            case SAVER: switchSaver(); break;
            case SAVER_AFTER: cycleSaver(); break;
            case SLEEP: cycleSleep(); break;
            case STORAGE: open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); break;
            case MEMORY: tidy(); break;
            case APPS: open(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS); break;
            case REMOTES:
                start(new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accessories.AddAccessoryActivity")));
                break;
            case DATE: open(Settings.ACTION_DATE_SETTINGS); break;
            case LANGUAGE: open(Settings.ACTION_LOCALE_SETTINGS); break;
            case ABOUT: open(Settings.ACTION_DEVICE_INFO_SETTINGS); break;
        }
        rebuild();
    }

    /** Hold OK on the screensaver to open the screensaver's own settings. */
    void wakeSay(String action) {
        sendBroadcast(new Intent("com.quiet.WAKE").setPackage("com.quiet.home").putExtra("action", action), "com.quiet.permission.PREFS");
    }

    @Override
    protected void hold(Menu.Item item) {
        if (WAKE.equals(item.tag)) wakeSay("teach");
        if (SAVER.equals(item.tag)) {
            String c = Settings.Secure.getString(getContentResolver(), "screensaver_components");
            ComponentName cn = c == null ? null : ComponentName.unflattenFromString(c.split(",")[0]);
            Intent i = cn == null ? null : getPackageManager().getLeanbackLaunchIntentForPackage(cn.getPackageName());
            if (i == null && cn != null) i = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
            if (i != null) start(i);
        }
    }

    @Override
    protected void selectIcon(IconBar.Icon icon) {
        if (ALL.equals(icon.tag)) open(Settings.ACTION_SETTINGS);
    }

    void open(String action) {
        start(new Intent(action));
    }

    void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            freed = "";
        }
    }

    // ---------------------------------------------------------------- shared home prefs

    String pref(String key, String fallback) {
        try (Cursor c = getContentResolver().query(PREFS, null, null, null, null)) {
            while (c != null && c.moveToNext()) if (key.equals(c.getString(0))) return c.getString(1);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    void setPref(String key, String value) {
        ContentValues v = new ContentValues();
        v.put(key, value);
        try {
            getContentResolver().update(PREFS, v, null, null);
        } catch (Exception ignored) {
        }
    }

    String soundMode() {
        return pref("soundMode", "on");
    }

    String soundLevel() {
        return pref("soundLevel", "normal");
    }

    String motion() {
        return pref("motion", "instant");
    }

    // ---------------------------------------------------------------- screensaver and sleep

    String saver() {
        String c = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        String name = "NONE";
        if (Settings.Secure.getInt(getContentResolver(), "screensaver_enabled", 1) != 0 && c != null && !c.isEmpty()) {
            ComponentName cn = ComponentName.unflattenFromString(c.split(",")[0]);
            name = cn == null ? "ON" : label(cn.getPackageName());
        }
        if (c != null && c.startsWith("com.quiet.home/")) name = "QUIET · THE OLD ROAD";
        return Font.upper(name);
    }

    /** Quiet's own screensaver, or whichever one you had before (Aerial Views). */
    void switchSaver() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        String c = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        boolean quiet = c != null && c.startsWith("com.quiet.home/");
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (!quiet && c != null && !c.isEmpty()) p.edit().putString("otherSaver", c).apply();
        String next = quiet ? p.getString("otherSaver", AERIAL) : QUIET_SAVER;
        Settings.Secure.putString(getContentResolver(), "screensaver_components", next);
        Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
    }

    void cycleSaver() {
        if (!Settings.System.canWrite(this)) return;
        int now = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000;
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next(SAVER_MIN, now) * 60_000);
    }

    void cycleSleep() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        int now = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", 1_800_000) / 60_000;
        Settings.Secure.putInt(getContentResolver(), "sleep_timeout", next(SLEEP_MIN, now) * 60_000);
    }

    static int next(int[] steps, int now) {
        for (int s : steps) if (s > now) return s;
        return steps[0];
    }

    static String minutes(int ms) {
        if (ms <= 0 || ms == Integer.MAX_VALUE) return "NEVER";
        int m = ms / 60_000;
        return m >= 60 && m % 60 == 0 ? (m / 60) + (m == 60 ? " HOUR" : " HOURS") : m + " MIN";
    }

    String label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- live state

    String network() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return "NOT CONNECTED";
        String ip = ip(cm);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET · " + ip;
        String ssid = "WI-FI";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            String s = wm.getConnectionInfo().getSSID();
            if (s != null && !s.contains("unknown")) ssid = s.replace("\"", "");
        } catch (Exception ignored) {
        }
        return Font.upper(ssid) + " · " + ip;
    }

    /** The TV's address on your network (Android 11 hides interface lists from apps; ask Connectivity). */
    static String ip(ConnectivityManager cm) {
        try {
            android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null)
                for (android.net.LinkAddress la : lp.getLinkAddresses())
                    if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
        } catch (Exception ignored) {
        }
        return "";
    }

    String storage() {
        StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
        return gb(fs.getAvailableBytes()) + " FREE OF " + gb(fs.getTotalBytes());
    }

    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
    }

    long freeMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    String memory() {
        return freeMemory() / 1_000_000 + " MB FREE";
    }

    /** Ask every background app to let go of its memory. Music keeps playing; the home screen stays. */
    void tidy() {
        long before = freeMemory();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
            String p = ai.packageName;
            if (p.equals(getPackageName()) || p.equals("com.quiet.home") || p.equals("com.quiet.listen")) continue;
            am.killBackgroundProcesses(p);
        }
        stage.splash(stage.mote.x, 4f);
        freed = "TIDYING";
        ui.postDelayed(() -> {
            long gained = Math.max(0, freeMemory() - before) / 1_000_000;
            freed = "FREED " + gained + " MB · " + memory();
            rebuild();
            ui.postDelayed(() -> {
                freed = "";
                rebuild();
            }, 6000);
        }, 1500);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected String carving() {
        Menu.Item it = menu.focused();
        if (it == null || inBar) return "";
        return Font.width(it.label, 2, 1) <= 112 ? it.label : "";     // only names that fit the arch
    }

    @Override
    protected void draw(Stage s) {
        if (SystemClock.uptimeMillis() > refreshAt && freed.isEmpty()) rebuild();   // live values
        s.text("SETTINGS", 14, 12, 3, Pal.TEXT);
        s.small(Font.upper(Build.MODEL.replace('_', ' ')), 15, 38, 2, Pal.DIM);
    }

    @Override
    protected String hint() {                                 // what OK does here, for this row only
        Menu.Item f = menu.focused();
        if (f == null) return "";
        if (VOICE.equals(f.tag)) return "OK OPENS DROP · PASTE THE KEY ON YOUR PHONE";
        if (WAKE.equals(f.tag)) return pref("wake", "untaught").equals("untaught") ? "OK TEACHES IT YOUR VOICE"
                : "OK SWITCHES · HOLD TO TEACH IT AGAIN";
        return SAVER.equals(f.tag) ? "OK SWITCHES · HOLD FOR ITS OWN SETTINGS"
                : MEMORY.equals(f.tag) ? "OK TIDIES · MUSIC KEEPS PLAYING"
                : f.hot ? "OK CHANGES IT" : "OK OPENS THE SYSTEM PAGE";
    }

    static String uptime() {
        long h = SystemClock.elapsedRealtime() / 3_600_000;
        return h >= 48 ? (h / 24) + " DAYS" : h + " H";
    }
}
