package com.quiet.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;

import com.quiet.px.Ambience;
import com.quiet.px.Font;
import com.quiet.px.Groove;
import com.quiet.px.IconBar;
import com.quiet.px.Menu;
import com.quiet.px.Pal;
import com.quiet.px.PxActivity;
import com.quiet.px.Stage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quiet — the home screen. A clock, your apps as words, Mote the firefly, and the well.
 * Hold OK on a pinned app to move it; hold OK in All Apps to pin or unpin.
 */
public class Home extends PxActivity {

    static final String[] DEFAULT_PINS = {
            "com.quiet.watch",
            "com.quiet.listen",
            "com.google.android.youtube.tv",
            "com.apple.atve.androidtv.appletv",
            "com.netflix.ninja",
    };
    /** Pins that earlier versions placed and this one takes back off the home list. */
    static final String[] RETIRED_PINS = {"in.startv.hotstar", "com.teamsmart.videomanager.tv", "org.smarttube.stable"};
    static final int PREFS_VERSION = 3;

    static final int MODE_HOME = 0, MODE_ALL = 1, MODE_INPUTS = 2, MODE_SHELF = 3;
    static final String WATCH = "com.quiet.watch", LISTEN = "com.quiet.listen";
    String shelfOf = "";                                // whose shelf is open (MODE_SHELF)
    final java.util.Map<String, List<String[]>> shelves = new java.util.HashMap<>();
    static final String ALL = "\u0001all", SETTINGS = "\u0001settings", INPUTS = "\u0001inputs", SOUND = "\u0001sound",
            PLAYING = "\u0001playing", TOGGLE = "\u0001toggle";
    static final String MUSIC_APP = "com.quiet.listen", WATCH_APP = "com.quiet.watch";

    final Map<String, String> labels = new HashMap<>();
    final List<String> installed = new ArrayList<>();
    final List<String> pins = new ArrayList<>();
    SharedPreferences prefs;
    int mode = MODE_HOME;
    int moving = -1;
    boolean appsDirty = true;

    // What's playing, from any app that publishes a media session.
    MediaSessionManager sessions;
    MediaController playing;
    Groove groove;
    String songTitle = "", songArtist = "";
    boolean songPlaying;

    final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
        }
    };

    @Override
    protected Menu makeMenu() {
        return new Menu(26, 72, 86);                    // a breath below the date
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("quiet", MODE_PRIVATE);
        // The firmware's own boot service resets animation speed a few seconds after boot; outlast it.
        view.postDelayed(this::snappier, 10_000);
        view.postDelayed(this::snappier, 30_000);
        loadPins();
        audio = new Ambience(this);
        applySoundMode();
        stage.sound = audio;
        groove = new Groove(stage);
        // What the well remembers.
        stage.doorOpen = prefs.getBoolean("door", false);
        stage.onDoorOpened = () -> prefs.edit().putBoolean("door", true).apply();
        // Quiet Settings changes these through the Prefs provider; apply them the moment they land.
        prefs.registerOnSharedPreferenceChangeListener(prefsChanged);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        // The TV coming on (or the screensaver ending) wakes the well.
        IntentFilter wf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wf.addAction(Intent.ACTION_DREAMING_STOPPED);
        wf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(waking, wf);
        Voice.ensureEnabled(this);                       // the voice, over every app (see Voice)
        stage.wake();
    }

    @Override
    protected void onResume() {
        opening = "";
        snappier();
        countDay();
        if (appsDirty) loadApps();
        moving = -1;
        if (mode == MODE_INPUTS) mode = MODE_HOME;
        watchSessions();
        rebuild(menu.focus);
        super.onResume();
        if (songPlaying) audio.stop();      // real music wins over the ambience
        groove.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        groove.stop();
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (Exception ignored) {
        }
        if (playing != null) playing.unregisterCallback(sessionCallback);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(waking);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged);
        audio.stop();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home while already home: back to the top of the pinned list, out of the icon bar.
        mode = MODE_HOME;
        moving = -1;
        opening = "";
        enterBar(false);
        rebuild(0);
    }

    @Override
    public void onBackPressed() {
        if (moving >= 0) {
            drop();
        } else if (mode == MODE_SHELF) {
            closeShelf();
        } else if (mode != MODE_HOME) {
            mode = MODE_HOME;
            rebuild(0);
        }
    }

    // ---------------------------------------------------------------- list

    final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> {
        if ("party".equals(key)) {
            stage.partyAllowed = !"off".equals(Prefs.get(this, "party"));
        } else if ("soundLevel".equals(key)) {
            applySoundMode();
        } else if ("soundMode".equals(key)) {
            applySoundMode();
            if (audio.enabled && !songPlaying && hasWindowFocus()) audio.start();
            rebuildBar();
        } else if ("motion".equals(key)) {
            snappier();
        }
    };

    void applySoundMode() {
        String mode = Prefs.get(this, "soundMode");
        audio.nature = mode.equals("nature");
        audio.level = Ambience.levelFor(Prefs.get(this, "soundLevel"));
        stage.partyAllowed = !"off".equals(Prefs.get(this, "party"));
        boolean on = !mode.equals("off");
        if (on != audio.enabled) audio.setEnabled(on);
        audio.enabled = on;
    }

    void rebuild(int focus) {
        List<Menu.Item> items = new ArrayList<>();
        if (mode == MODE_HOME) {
            // The list is only ever content: our own pair (Watch, Listen) first, a breath, then the rest.
            readShelf(WATCH);
            readShelf(LISTEN);
            readPlayed();
            int gap = gapAt();
            for (int i = 0; i < pins.size(); i++) {
                if (i == gap) items.add(Menu.Item.gap());
                Menu.Item it = app(pins.get(i));
                List<String[]> sh = shelves.get(pins.get(i));
                if (sh != null && !sh.isEmpty() && moving < 0) {   // → there's a shelf to the right (shown quietly)
                    it.note = "\u2192";
                    it.hint = true;
                }
                items.add(it);
            }
        } else if (mode == MODE_SHELF) {
            // Pick up where you left off: Continue Watching, or what you played last.
            List<String[]> sh = shelves.get(shelfOf);
            if (sh != null)
                for (String[] r : sh) {
                    Menu.Item it = new Menu.Item(fitLabel(Font.upper(r[0]), shelfOf.equals(WATCH) ? 82 : 110));
                    if (shelfOf.equals(WATCH)) it.note = Font.upper(r[1]);
                    it.tag = r.length > 2 && PLAYED.equals(r[2]) ? PLAYED + shelfOf + "\n" + r[0] + "\n" + r[1] : shelfIntent(r);
                    items.add(it);
                }
        } else if (mode == MODE_ALL) {
            for (String p : installed) {
                Menu.Item it = app(p);
                it.dot = pins.contains(p);
                items.add(it);
            }
        } else {
            items.addAll(inputs());
        }
        menu.set(items, focus);
        rebuildBar();
    }

    /** A Quiet app's shelf, from its provider: [title, sub, kind, id] (Listen) or [title, sub, uri] (Watch). */
    void readShelf(String pkg) {
        List<String[]> out = new ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(android.net.Uri.parse("content://" + pkg + ".shelf/all"), null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String[] r = new String[c.getColumnCount()];
                for (int k = 0; k < r.length; k++) r[k] = c.getString(k) == null ? "" : c.getString(k);
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        shelves.put(pkg, out);
    }

    /** Marks a shelf item from History (what played in another app): picked, it's played again. */
    static final String PLAYED = "\u0002";

    /**
     * Every other app's shelf: what played in it on this TV (History: YouTube's videos, Netflix's shows,
     * Apple TV...), newest first, six each. RIGHT on the app shows it; OK plays it again where it was left.
     */
    void readPlayed() {
        java.util.Map<String, List<String[]>> got = new java.util.HashMap<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Prefs.prefs(this).getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject e = a.getJSONObject(i);
                String pkg = e.optString("app"), title = e.optString("title");
                if (pkg.startsWith("com.quiet.") || title.isEmpty()) continue;   // ours have their own shelves
                List<String[]> l = got.computeIfAbsent(pkg, k -> new ArrayList<>());
                boolean seen = false;
                for (String[] r : l) seen |= r[0].equals(title);
                if (!seen && l.size() < 6) l.add(new String[]{title, e.optString("by"), PLAYED});
            }
        } catch (Exception ignored) {
        }
        for (java.util.Map.Entry<String, List<String[]>> e : got.entrySet())
            if (!e.getKey().equals(WATCH) && !e.getKey().equals(LISTEN)) shelves.put(e.getKey(), e.getValue());
    }

    /** Plays a History item again: YouTube by its title and channel (resumed where it was left), else its app. */
    void playAgain(String tag) {
        String[] p = tag.substring(1).split("\n", -1);
        String pkg = p[0], title = p.length > 1 ? p[1] : "", by = p.length > 2 ? p[2] : "";
        Voice v = Voice.self;
        if (pkg.equals("com.google.android.youtube.tv") && v != null && !title.isEmpty()) {
            try {
                v.showCard();
                v.act(new org.json.JSONObject().put("name", "youtube").put("args", new org.json.JSONObject()
                        .put("query", by.isEmpty() ? title : title + " " + by)));
                return;
            } catch (Exception ignored) {
            }
        }
        launch(pkg);                                       // Netflix, Apple TV...: they open on their own "continue"
    }

    Intent shelfIntent(String[] r) {
        if (shelfOf.equals(WATCH))
            return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r[2])).setPackage("com.stremio.one")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Intent().setComponent(new android.content.ComponentName(LISTEN, LISTEN + ".FromHome"))
                .putExtra("playKind", r[2]).putExtra("playId", r[3]).putExtra("playTitle", r[0]).putExtra("playSub", r[1])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static String fitLabel(String s, int w) {
        if (Font.width(s, 1, 1) <= w) return s;
        while (s.length() > 1 && Font.width(s + "..", 1, 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "..";
    }

    void openShelf(String pkg) {
        mode = MODE_SHELF;
        shelfOf = pkg;
        rebuild(0);
    }

    void closeShelf() {
        mode = MODE_HOME;
        rebuild(menuIndex(Math.max(0, pins.indexOf(shelfOf))));
    }

    /** Where the small gap goes: after the run of Quiet apps at the top, if anything follows. */
    int gapAt() {
        int n = 0;
        while (n < pins.size() && pins.get(n).startsWith("com.quiet.")) n++;
        return n > 0 && n < pins.size() ? n : -1;
    }

    /** A pin's row in the home list (the gap shifts everything after it down one). */
    int menuIndex(int pin) {
        int gap = gapAt();
        return gap >= 0 && pin >= gap ? pin + 1 : pin;
    }

    /** System controls live in the icon bar, top-right, never in the app list. */
    void rebuildBar() {
        if (bar == null) bar = new IconBar();
        List<IconBar.Icon> icons = new ArrayList<>();
        if (playing != null && !songTitle.isEmpty()) {
            icons.add(new IconBar.Icon(songPlaying ? IconBar.PAUSE : IconBar.PLAY, songPlaying ? "PAUSE" : "PLAY", TOGGLE));
            IconBar.Icon np = new IconBar.Icon(IconBar.PLAYING, songPlaying ? "NOW PLAYING" : "PAUSED", PLAYING);
            np.lit = songPlaying;
            icons.add(np);
        }
        icons.add(new IconBar.Icon(IconBar.INPUTS, "INPUTS", INPUTS));
        IconBar.Icon all = new IconBar.Icon(IconBar.APPS, mode == MODE_ALL ? "BACK TO HOME" : "ALL APPS", ALL);
        all.lit = mode == MODE_ALL;
        icons.add(all);
        String sm = Prefs.get(this, "soundMode");
        icons.add(new IconBar.Icon(sm.equals("off") ? IconBar.SOUND_OFF : IconBar.SOUND_ON,
                sm.equals("on") ? "MUSIC + NATURE" : sm.equals("nature") ? "NATURE ONLY" : "SOUND OFF", SOUND));
        icons.add(new IconBar.Icon(IconBar.SETTINGS, "SETTINGS", SETTINGS));
        bar.set(icons);
    }

    @Override
    protected void selectIcon(IconBar.Icon icon) {
        Object tag = icon.tag;
        if (ALL.equals(tag)) {
            mode = mode == MODE_ALL ? MODE_HOME : MODE_ALL;
            enterBar(false);
            rebuild(0);
        } else if (INPUTS.equals(tag)) {
            mode = MODE_INPUTS;
            enterBar(false);
            rebuild(0);
        } else if (TOGGLE.equals(tag)) {
            if (playing != null) {
                if (songPlaying) playing.getTransportControls().pause();
                else playing.getTransportControls().play();
            }
        } else if (SOUND.equals(tag)) {
            // Cycles: music + nature  ->  nature only  ->  off.
            String sm = Prefs.get(this, "soundMode");
            prefs.edit().putString("soundMode", sm.equals("on") ? "nature" : sm.equals("nature") ? "off" : "on").apply();
        } else {
            enterBar(false);                               // so the hint line can say what's opening
            opening = SETTINGS.equals(tag) ? "SETTINGS" : "NOW PLAYING";
            openingAt = android.os.SystemClock.uptimeMillis();
            Menu.Item proxy = new Menu.Item("");
            proxy.tag = tag;
            select(proxy);                                 // Now Playing, Settings: same as from a list
        }
    }

    Menu.Item app(String pkg) {
        Menu.Item it = new Menu.Item(Font.upper(labels.getOrDefault(pkg, pkg)));
        it.tag = pkg;
        return it;
    }

    /** HDMI and other sources, by the names the TV knows them by. */
    List<Menu.Item> inputs() {
        List<Menu.Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getParentId() != null) continue;
                CharSequence custom = info.loadCustomLabel(this);
                String name = (custom != null && custom.length() > 0 ? custom : info.loadLabel(this)).toString();
                Menu.Item it = new Menu.Item(Font.upper(name));
                it.tag = info;
                if (info.getType() == TvInputInfo.TYPE_HDMI) it.note = "HDMI";
                out.add(it);
            }
        }
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
        if (live != null) {
            Menu.Item it = Menu.Item.action("Live TV", live);
            if (!out.isEmpty()) out.add(Menu.Item.gap());
            out.add(it);
        }
        if (out.isEmpty()) {
            Menu.Item none = new Menu.Item("NO INPUTS FOUND");
            out.add(none);
        }
        return out;
    }

    @Override
    protected void select(Menu.Item item) {
        Object tag = item.tag;
        if (moving < 0 && !(tag instanceof String && ((String) tag).startsWith("\u0001")) && !ALL.equals(tag) && !INPUTS.equals(tag)) {
            opening = item.label;                         // said on the hint line while it loads
            openingAt = android.os.SystemClock.uptimeMillis();
        }
        if (moving >= 0) {
            drop();
        } else if (ALL.equals(tag)) {
            mode = MODE_ALL;
            rebuild(0);
        } else if (PLAYING.equals(tag)) {
            try {
                if (playing != null && playing.getSessionActivity() != null) playing.getSessionActivity().send();
                else if (playing != null) launch(playing.getPackageName());
            } catch (Exception ignored) {
            }
        } else if (INPUTS.equals(tag)) {
            mode = MODE_INPUTS;
            rebuild(0);
        } else if (SETTINGS.equals(tag)) {
            Intent quiet = getPackageManager().getLeanbackLaunchIntentForPackage("com.quiet.settings");
            start(quiet != null ? quiet : new Intent(Settings.ACTION_SETTINGS));
        } else if (tag instanceof TvInputInfo) {
            start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(((TvInputInfo) tag).getId())));
        } else if (tag instanceof Intent) {
            start((Intent) tag);
        } else if (tag instanceof String && ((String) tag).startsWith(PLAYED)) {
            stage.splash(stage.mote.x, 3f);
            playAgain((String) tag);
        } else if (tag instanceof String) {
            stage.splash(stage.mote.x, 3f);
            launch((String) tag);
        }
    }

    @Override
    protected void hold(Menu.Item item) {
        if (!(item.tag instanceof String)) return;
        String tag = (String) item.tag;
        if (tag.startsWith("\u0001")) return;
        if (mode == MODE_HOME) {
            moving = pins.indexOf(tag);
            item.hot = true;
            item.note = "▲▼";
        } else if (mode == MODE_ALL) {
            if (!pins.remove(tag)) pins.add(tag);
            savePins();
            item.dot = pins.contains(tag);
        }
    }

    void drop() {
        moving = -1;
        savePins();
        rebuild(menu.focus);
    }

    long offAt;                                     // when the TV last went off (for Pip's hello)

    final BroadcastReceiver waking = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long now = System.currentTimeMillis();
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                offAt = now;
                prefs.edit().putLong("offAt", now).apply();
                return;
            }
            long off = offAt > 0 ? offAt : prefs.getLong("offAt", 0);
            stage.wake(Intent.ACTION_SCREEN_ON.equals(i.getAction()) && off > 0 ? now - off : 0);
            view.poke();
        }
    };

    /** One mark on the tally stone for every day you come home. */
    void countDay() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date());
        int days = prefs.getInt("days", 0);
        if (!today.equals(prefs.getString("lastDay", ""))) {
            days++;
            prefs.edit().putString("lastDay", today).putInt("days", days).apply();
        }
        stage.days = days;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) stage.key(k);   // the well listens
        if (e.getAction() == KeyEvent.ACTION_DOWN && moving < 0 && !inBar) {          // the shelves
            Menu.Item f = menu.focused();
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT && mode == MODE_HOME && f != null && f.tag instanceof String && shelves.containsKey(f.tag)) {
                List<String[]> sh = shelves.get((String) f.tag);
                if (sh != null && !sh.isEmpty()) {
                    stage.mote.cheer();
                    openShelf((String) f.tag);
                    view.poke();
                    view.now();
                    return true;
                }
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && mode == MODE_SHELF) {
                closeShelf();
                view.poke();
                view.now();
                return true;
            }
        }
        if (moving >= 0 && e.getAction() == KeyEvent.ACTION_DOWN
                && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int to = moving + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (to >= 0 && to < pins.size()) {
                Collections.swap(pins, moving, to);
                moving = to;
                rebuild(menuIndex(to));
                menu.focused().hot = true;
                menu.focused().note = "▲▼";
            }
            view.poke();
            view.now();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected String carving() {
        Menu.Item it = menu.focused();
        if (mode == MODE_SHELF) return shelfOf.equals(WATCH) ? "WATCH" : shelfOf.equals(LISTEN) ? "LISTEN" : app(shelfOf).label;
        return it == null || it.small || mode == MODE_INPUTS ? "" : it.label;
    }

    @Override
    protected void lights(Stage s) {
        s.light(46, 24, 60, 0.2f, false);   // the clock glows like a sign
    }

    @Override
    protected void draw(Stage s) {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        String hh = new SimpleDateFormat(h24 ? "HH" : "h", Locale.getDefault()).format(now);
        String mm = new SimpleDateFormat("mm", Locale.getDefault()).format(now);
        boolean tick = (System.currentTimeMillis() / 1000) % 2 == 0;
        int x = s.text(hh, 14, 10, 4, Pal.TEXT);
        x = s.text(":", x, 10, 4, tick ? Pal.TEXT : Pal.FAINT);
        s.text(mm, x, 10, 4, Pal.TEXT);

        String sub;
        int subColor = Pal.DIM;
        if (mode == MODE_ALL) {
            sub = "ALL APPS · " + installed.size();
            subColor = Pal.AMBER;
        } else if (mode == MODE_INPUTS) {
            sub = "INPUTS";
            subColor = Pal.AMBER;
        } else if (mode == MODE_SHELF) {
            sub = shelfOf.equals(WATCH) ? "CONTINUE WATCHING" : shelfOf.equals(LISTEN) ? "RECENTLY PLAYED" : "PLAYED HERE";
            subColor = Pal.AMBER;
            List<String[]> sh = shelves.get(shelfOf);
            int fi = menu.focus;
            if (!shelfOf.equals(WATCH) && sh != null && fi >= 0 && fi < sh.size() && !sh.get(fi)[1].isEmpty())
                sub += "  ·  " + Font.upper(sh.get(fi)[1]);       // who made the one you're on
        } else {
            sub = Font.upper(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)) + "  ·  " + s.partOfDay()
                    + (com.quiet.px.Weather.temperature().isEmpty() ? "" : "  ·  " + com.quiet.px.Weather.temperature());
        }
        s.small(sub, 15, 43, 2, subColor);
        if (mode == MODE_HOME && !songTitle.isEmpty() && !inBar) {   // (in the icon bar its labels sit here)
            // Under the icons: ♪ bobbing while it plays, the song and who made it.
            int bob = songPlaying && ((int) (s.t * 2.5f) & 1) == 0 ? -1 : 0;
            String line = Font.upper(songTitle) + (songArtist.isEmpty() ? "" : "  \u00B7  " + Font.upper(songArtist));
            if (Font.microWidth(line, 1) > 140) {           // too long: cut at a word, with an ellipsis
                while (line.length() > 4 && Font.microWidth(line + "...", 1) > 140) line = line.substring(0, line.length() - 1);
                line = line.trim() + "...";
            }
            int lx = 306 - Font.microWidth(line, 1);
            s.small("\u266A", lx - 7, 31 + bob, 1, songPlaying ? Pal.MINT : Pal.FAINT);
            s.small(line, lx, 31, 1, songPlaying ? Pal.SOFT : Pal.DIM);
        }
    }

    String opening = "";
    long openingAt;

    boolean opening() {
        return !opening.isEmpty() && android.os.SystemClock.uptimeMillis() - openingAt < 8000;
    }

    @Override
    protected String hint() {                                 // only when there's something non-obvious to know
        if (opening()) return "OPENING " + Font.upper(opening);
        Menu.Item f = menu.focused();
        if (mode == MODE_HOME && moving < 0 && f != null && f.tag instanceof String && shelves.containsKey(f.tag)) {
            List<String[]> sh = shelves.get((String) f.tag);
            if (sh != null && !sh.isEmpty()) return WATCH.equals(f.tag) ? "RIGHT FOR CONTINUE WATCHING"
                    : LISTEN.equals(f.tag) ? "RIGHT FOR RECENTLY PLAYED" : "RIGHT FOR WHAT YOU PLAYED HERE";
        }
        return moving >= 0 ? "UP/DOWN TO MOVE · OK TO DROP" : mode == MODE_ALL ? "HOLD OK TO PIN"
                : mode == MODE_INPUTS ? "BACK TO RETURN" : mode == MODE_SHELF ? "OK TO PLAY · LEFT TO GO BACK" : "";
    }

    @Override
    protected int hintColor() {
        return moving >= 0 || opening() ? Pal.AMBER : Pal.FAINT;
    }

    /**
     * This TV's firmware puts animation speed back to 1x on every boot, and turns app-written window
     * and transition scales into 0 (off). So: instant window/transition changes, 0.5x for in-app
     * animation (fully off breaks some spinners). Needs WRITE_SECURE_SETTINGS, granted once over adb:
     *   adb shell pm grant com.quiet.home android.permission.WRITE_SECURE_SETTINGS
     */
    void snappier() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        try {
            boolean instant = !"standard".equals(Prefs.get(this, "motion"));
            set(Settings.Global.WINDOW_ANIMATION_SCALE, instant ? 0f : 1f);
            set(Settings.Global.TRANSITION_ANIMATION_SCALE, instant ? 0f : 1f);
            set(Settings.Global.ANIMATOR_DURATION_SCALE, instant ? 0.5f : 1f);
        } catch (Exception ignored) {
        }
    }

    void set(String key, float v) {
        if (Settings.Global.getFloat(getContentResolver(), key, 1f) != v) Settings.Global.putFloat(getContentResolver(), key, v);
    }

    // ---------------------------------------------------------------- now playing

    final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pick(list);

    final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { readSong(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { readSong(); }
        @Override public void onSessionDestroyed() { pick(null); }
    };

    void watchSessions() {
        try {
            ComponentName me = new ComponentName(this, Listener.class);
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, me);
            pick(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            // Listener not granted yet; the label simply stays hidden.
        }
    }

    /** Prefer a session that's actually playing; otherwise the most recent one. */
    void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c.getPackageName().equals(getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        if (playing != null) playing.unregisterCallback(sessionCallback);
        playing = best;
        if (playing != null) playing.registerCallback(sessionCallback);
        readSong();
    }

    void readSong() {
        String t = "", a = "";
        boolean p = false;
        if (playing != null) {
            MediaMetadata m = playing.getMetadata();
            if (m != null) {
                CharSequence ct = m.getText(MediaMetadata.METADATA_KEY_TITLE), ca = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
                t = ct == null ? "" : ct.toString();
                a = ca == null ? "" : ca.toString();
            }
            PlaybackState st = playing.getPlaybackState();
            p = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            stage.windDown = st != null && st.getExtras() != null ? st.getExtras().getFloat("quiet.winddown", 0f) : 0f;
        }
        boolean rows = t.isEmpty() != songTitle.isEmpty() || p != songPlaying;
        songTitle = t;
        songArtist = a;
        songPlaying = p;
        // Only Apple Music moves the world (Listen, or Apple's own app); a film or YouTube doesn't.
        String pkg = playing == null ? "" : playing.getPackageName();
        stage.music = p && (pkg.equals("com.quiet.listen") || pkg.equals("com.apple.android.music"));
        MediaMetadata gm = playing == null ? null : playing.getMetadata();
        String g = gm == null ? null : gm.getString(MediaMetadata.METADATA_KEY_GENRE);
        stage.genre = g == null ? "" : g;
        String np = stage.music ? "1|" + stage.genre : "0";      // for the other pages' party
        if (!np.equals(prefs.getString("nowPlaying", "0"))) prefs.edit().putString("nowPlaying", np).apply();
        menu.top = 72;                          // (the song line lives top right, under the icons)
        if (p) audio.stop();
        else if (!isFinishing() && hasWindowFocus()) audio.start();
        if (rows && mode == MODE_HOME && moving < 0) rebuild(menu.focus);
        else if (rows) rebuildBar();
    }

    // ---------------------------------------------------------------- data

    void loadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        labels.clear();
        installed.clear();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(pm, Intent.CATEGORY_LAUNCHER);
        installed.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        if (prefs.getInt("musicPinned", 0) == 0 && labels.containsKey(MUSIC_APP)) {
            if (!pins.contains(MUSIC_APP)) pins.add(Math.min(2, pins.size()), MUSIC_APP);
            prefs.edit().putInt("musicPinned", 1).apply();
            savePins();
        }
        // Watch is the pixel front door to Stremio: it takes Stremio's place in the list, once.
        if (prefs.getInt("watchPinned", 0) == 0 && labels.containsKey(WATCH_APP)) {
            int at = pins.indexOf("com.stremio.one");
            pins.remove(WATCH_APP);
            if (at >= 0) pins.set(at, WATCH_APP);
            else pins.add(Math.min(3, pins.size()), WATCH_APP);
            prefs.edit().putInt("watchPinned", 1).apply();
            savePins();
        }
        // The pair leads: Watch, Listen; then YouTube, Apple TV, Netflix; anything else after. Once.
        if (prefs.getInt("ownFirst", 0) < 2 && labels.containsKey(WATCH_APP) && labels.containsKey(MUSIC_APP)) {
            List<String> order = new ArrayList<>();
            for (String p : DEFAULT_PINS) if (pins.contains(p) || p.equals(WATCH_APP) || p.equals(MUSIC_APP)) order.add(p);
            for (String p : pins) if (!order.contains(p)) order.add(p);
            pins.clear();
            pins.addAll(order);
            prefs.edit().putInt("ownFirst", 2).apply();
            savePins();
        }
        if (pins.removeIf(p -> !labels.containsKey(p))) savePins();
    }

    void collect(PackageManager pm, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || labels.containsKey(pkg)) continue;
            labels.put(pkg, ri.loadLabel(pm).toString().trim());
            installed.add(pkg);
        }
    }

    void loadPins() {
        String saved = prefs.getString("pins", null);
        pins.clear();
        if (saved == null) Collections.addAll(pins, DEFAULT_PINS);
        else if (!saved.isEmpty()) Collections.addAll(pins, saved.split(","));
        if (prefs.getInt("v", 1) < PREFS_VERSION) {
            for (String r : RETIRED_PINS) pins.remove(r);
            savePins();
            prefs.edit().putInt("v", PREFS_VERSION).apply();
        }
    }

    void savePins() {
        prefs.edit().putString("pins", String.join(",", pins)).apply();
    }

    void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        start(i);
    }

    void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
