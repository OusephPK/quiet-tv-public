package com.quiet.watch;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.quiet.px.Font;
import com.quiet.px.IconBar;
import com.quiet.px.Menu;
import com.quiet.px.Pal;
import com.quiet.px.PxActivity;
import com.quiet.px.Qr;
import com.quiet.px.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watch — Stremio, in the pixel world. Your Continue Watching, rows from your own add-ons'
 * catalogs, your library and search, and a page for every title with its seasons and episodes.
 * Choosing a film or an episode hands it to Stremio, opened right at that episode's sources;
 * Stremio plays it. The film icon up top opens Stremio itself.
 */
public class Main extends PxActivity {
    static final String STREMIO = "com.stremio.one";
    static final String OPEN = "\u0001open", AGAIN = "\u0001again", ACCOUNT = "\u0001account";
    static final String HOME = "HOME", MOVIES = "MOVIES", SERIES = "SERIES", LIBRARY = "LIBRARY", SEARCH = "SEARCH";
    static final String[] SECTIONS = {HOME, MOVIES, SERIES, LIBRARY, SEARCH};
    static final int RAIL = 0, ROWS = 1, TITLE = 2, KEYS = 3, RESULTS = 4;

    // Layout, in art pixels.
    static final int RAIL_X = 24, CX = 100, PW = 30, PH = 45, PITCH = 36, COLS = 6, ROW_Y = 34, ROW_PITCH = 62;
    static final int TP_W = 40, TP_H = 60, TX = 148, LIST_X = 182;
    static final int KEY_X = 100, KEY_Y = 38, KEY_W = 10, KEY_H = 10, KEY_COLS = 7;
    static final String KEYS_TEXT = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final int K_SPACE = 36, K_DEL = 37, K_CLEAR = 38, K_COUNT = 39;

    final Handler ui = new Handler();
    final Posters posters = new Posters();
    final ExecutorService work = Executors.newFixedThreadPool(3);
    SharedPreferences prefs;
    String authKey;
    String problem = "", notice = "";
    long noticeUntil;
    // Hold OK on a poster: a small menu of things to do with it.
    List<String> holdItems = new ArrayList<>();
    int holdFocus;
    Stremio.Meta holdFor;
    // OK on a title: a tap does what it always did, a hold opens the menu.
    final com.quiet.px.Hold hold = new com.quiet.px.Hold(new com.quiet.px.Hold.Target() {
        @Override
        public void tap() {
            view.poke();
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            view.now();
        }

        @Override
        public void hold() {
            if (area == ROWS || area == RESULTS || area == TITLE) openHold();
            view.now();
        }
    });

    // What Stremio told us.
    List<Stremio.Addon> addons = new ArrayList<>();
    List<Stremio.Meta> library = new ArrayList<>(), continuing = new ArrayList<>();
    boolean loading, loaded;

    /** One row of posters: a catalog, Continue Watching, or a slice of your library. */
    static final class Row {
        String title;
        Stremio.Catalog cat;          // null for rows we fill ourselves
        boolean resume;               // OK picks straight up in Stremio
        List<Stremio.Meta> items = new ArrayList<>();
        boolean asked, done;
        String status = "";
        int focus, scroll;
    }

    String current = HOME;
    int area = RAIL;
    final java.util.Map<String, List<Row>> rows = new java.util.HashMap<>();
    final java.util.Map<String, int[]> rowFocus = new java.util.HashMap<>();   // section -> {row, top}
    int rf, rTop;

    // A title page.
    Stremio.Meta title;
    int titleFrom = ROWS;
    String titleStatus = "";
    List<Integer> seasons = new ArrayList<>();
    int season, sf, ef = -1, eTop;   // ef -1: the season tabs (series) or the button (film)

    // Search.
    String query = "", searched = "", searchStatus = "";
    long typedAt;
    int kf, xf, xTop;
    List<Stremio.Meta> results = new ArrayList<>();
    // Search suggestions: before you type, recent searches and what's popular; as you type, IMDb's
    // instant title suggestions (real titles: OK opens the page). Your add-ons' results follow.
    List<Stremio.Meta> suggested = new ArrayList<>();
    String suggestedFor = "";

    /** What the list beside the keyboard holds right now. */
    List<Stremio.Meta> list() {
        List<Stremio.Meta> out = new ArrayList<>();
        if (query.trim().isEmpty()) {
            for (String r : recentSearches()) {
                Stremio.Meta t = new Stremio.Meta();
                t.type = "term";
                t.id = "term:" + r;
                t.name = r;
                out.add(t);
            }
            List<Row> home = rows.get(HOME);                 // what's popular: the top of your first catalog
            if (home != null)
                for (Row r : home)
                    if (r.cat != null && !r.items.isEmpty()) {
                        for (int i = 0; i < Math.min(5, r.items.size()); i++) out.add(r.items.get(i));
                        break;
                    }
            return out;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Stremio.Meta m : suggested) if (seen.add(m.id)) out.add(m);
        for (Stremio.Meta m : results) if (seen.add(m.id)) out.add(m);
        return out;
    }

    List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        for (String r : prefs.getString("recentSearches", "").split("\n")) if (!r.trim().isEmpty()) out.add(r);
        return out;
    }

    void rememberSearch(String q) {
        q = q.trim();
        if (q.length() < 2) return;
        List<String> r = recentSearches();
        r.remove(q);
        r.add(0, q);
        while (r.size() > 4) r.remove(r.size() - 1);
        prefs.edit().putString("recentSearches", String.join("\n", r)).apply();
    }

    /** IMDb's suggestions for what's typed so far: films and series only, with their posters. */
    void suggest() {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        suggestedFor = query;
        String typed = query;
        work.submit(() -> {
            List<Stremio.Meta> got = new ArrayList<>();
            try {
                char c0 = q.charAt(0);
                String dir = Character.isLetterOrDigit(c0) ? String.valueOf(c0) : "x";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://v3.sg.media-imdb.com/suggestion/"
                        + dir + "/" + java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20") + ".json").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                org.json.JSONArray d = new org.json.JSONObject(Stremio.read(c)).optJSONArray("d");
                for (int i = 0; d != null && i < d.length() && got.size() < 4; i++) {
                    org.json.JSONObject o = d.getJSONObject(i);
                    String id = o.optString("id"), qid = o.optString("qid");
                    if (!id.startsWith("tt")) continue;
                    String type = qid.equals("movie") || qid.equals("tvMovie") ? "movie"
                            : qid.equals("tvSeries") || qid.equals("tvMiniSeries") ? "series" : "";
                    if (type.isEmpty()) continue;
                    Stremio.Meta m = new Stremio.Meta();
                    m.id = id;
                    m.type = type;
                    m.name = o.optString("l");
                    m.year = o.has("y") ? String.valueOf(o.optInt("y")) : "";
                    org.json.JSONObject img = o.optJSONObject("i");
                    if (img != null) m.poster = img.optString("imageUrl").replace("._V1_.jpg", "._V1_UX300_.jpg");
                    got.add(m);
                }
            } catch (Exception ignored) {
            }
            ui.post(() -> {
                if (!typed.equals(query)) return;            // typed on meanwhile
                markProgress(got);
                suggested = got;
                view.now();
            });
        });
    }

    // Signing in, typed: sent only to Stremio.
    LinearLayout panel;
    EditText typer;
    TextView panelTitle, panelError;
    String step = "", email = "";

    // Signing in from a phone: a code, its QR, and a quiet poll until the phone says yes.
    static final int QZ = 3, MOD = 3, TABLET_Y = 36;
    String linkCode;
    boolean[][] qr;
    long linkAt;
    boolean polling;

    @Override
    protected Menu makeMenu() {
        return new Menu(RAIL_X, 140, 10);      // unused: Watch draws its own rail and rows
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watch", MODE_PRIVATE);
        posters.remember(prefs);
        authKey = prefs.getString("authKey", null);
        menu.set(new ArrayList<>(), 0);
        posters.arrived = () -> view.post(view::now);
        buildPanel();
        rebuildBar();
        fromVoice(getIntent());
    }

    boolean openFirst;               // asked for by voice: open the best match as soon as it's found
    boolean askAi;                   // asked for by voice as a description: suggest, don't open

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        fromVoice(i);
    }

    @Override
    protected boolean searching() {
        return current.equals(SEARCH) && (area == KEYS || area == RESULTS);
    }

    /** Words from the voice or the phone: the search screen, with them in it (searched a moment later). */
    @Override
    protected String searchText() {
        return query;
    }

    @Override
    protected void typedIn(String words) {
        if (words.isEmpty()) {                            // "clear the search"
            current = SEARCH;
            area = KEYS;
            type(K_CLEAR);
            return;
        }
        String q = Font.upper(words).replaceAll("[^A-Z0-9 .,'&:!?-]", "").replaceAll("\\s+", " ").trim();
        current = SEARCH;
        inBar = false;
        area = KEYS;
        query = q.length() > 40 ? q.substring(0, 40).trim() : q;
        searched = "";
        typedAt = 0;
        askAi = openFirst = false;
    }

    /** "Watch Perfect Days": search for it and open its page (BACK shows the other matches). */
    void fromVoice(android.content.Intent i) {
        if (i == null || i.getComponent() == null || !i.getComponent().getClassName().endsWith(".FromHome")) return;
        String sec = i.getStringExtra("section");          // "what's popular right now": the live catalogs
        if (MOVIES.equals(sec) || SERIES.equals(sec)) {
            current = sec;
            area = RAIL;
            openFirst = askAi = false;
            return;
        }
        String q = i.getStringExtra("query");
        if (q == null || q.trim().isEmpty()) return;
        current = SEARCH;
        query = Font.upper(q);
        searched = "";
        typedAt = 0;
        area = RESULTS;
        xf = xTop = 0;
        askAi = i.getBooleanExtra("suggest", false);
        openFirst = !askAi;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (authKey != null) refresh();       // also catches up after watching something in Stremio
        else startLink();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        posters.pool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        ui.removeCallbacks(poll);
    }

    void rebuildBar() {
        bar = new IconBar();
        List<IconBar.Icon> icons = new ArrayList<>();
        icons.add(new IconBar.Icon(IconBar.FILM, "OPEN STREMIO", OPEN));
        if (authKey != null) icons.add(new IconBar.Icon(IconBar.REFRESH, "REFRESH", AGAIN));
        IconBar.Icon user = new IconBar.Icon(IconBar.USER, authKey != null ? "SIGN OUT" : "SIGN IN", ACCOUNT);
        user.lit = authKey == null;
        icons.add(user);
        bar.set(icons);
    }

    // ---------------------------------------------------------------- data

    void refresh() {
        if (loading) return;
        loading = true;
        String key = authKey;
        work.submit(() -> {
            List<Stremio.Meta> lib = null;
            List<Stremio.Addon> ads = null;
            String why = "";
            boolean out = false;
            try {
                lib = Stremio.library(key);
                ads = Stremio.addons(key);
            } catch (Stremio.SignedOut e) {
                out = true;
            } catch (Exception e) {
                why = "CAN'T REACH STREMIO";
            }
            List<Stremio.Meta> fl = lib;
            List<Stremio.Addon> fa = ads;
            String fw = why;
            boolean fo = out;
            ui.post(() -> {
                loading = false;
                problem = fw;
                if (fo) {
                    signOut();
                    problem = "SIGNED OUT · SIGN IN AGAIN";
                } else if (fl != null) {
                    boolean first = !loaded;
                    library = fl;
                    continuing = Stremio.continueWatching(fl);
                    findNextEpisodes(continuing);
                    saveShelf();
                    for (Row r : catalogCache.values()) markProgress(r.items);   // ribbons follow your library
                    if (fa != null && (first || addons.isEmpty())) addons = fa;
                    loaded = true;
                    buildRows(first);
                }
                view.now();
            });
        });
    }

    /** Continue Watching for home's shelf (see ShelfProvider): name, where you are, the link. */
    void saveShelf() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Stremio.Meta m : continuing) {
                if (a.length() >= 6) break;
                String sub = m.badge();
                a.put(new org.json.JSONObject().put("t", m.name).put("s", sub)
                        .put("u", m.link(m.next != null ? m.next.id : null)));
            }
        } catch (org.json.JSONException ignored) {
        }
        prefs.edit().putString("shelf", a.toString()).apply();
    }

    /** For anything you've all but finished, find the episode after it (in the background). */
    void findNextEpisodes(List<Stremio.Meta> list) {
        List<Stremio.Addon> ads = addons;
        for (Stremio.Meta m : list) {
            if (!m.finishing()) continue;
            work.submit(() -> {
                try {
                    if (!m.full) Stremio.details(ads.isEmpty() ? addons : ads, m);
                } catch (Exception ignored) {
                }
                if (m.videos == null) return;
                for (int i = 0; i + 1 < m.videos.size(); i++)
                    if (m.videos.get(i).id.equals(m.videoId)) {
                        Stremio.Video n = m.videos.get(i + 1);
                        ui.post(() -> {
                            m.next = n;
                            saveShelf();
                            view.now();
                        });
                        break;
                    }
            });
        }
    }

    /** Sections and their rows. Catalog rows keep what they've loaded across refreshes. */
    void buildRows(boolean first) {
        List<Stremio.Catalog> board = new ArrayList<>();
        for (Stremio.Addon a : addons) for (Stremio.Catalog c : a.catalogs) if (c.board) board.add(c);

        List<Row> home = new ArrayList<>(), movies = new ArrayList<>(), series = new ArrayList<>(), lib = new ArrayList<>();
        if (!continuing.isEmpty()) home.add(fixed("CONTINUE WATCHING", continuing, true));
        List<Stremio.Meta> recent = recentlyAdded();
        if (!recent.isEmpty()) home.add(fixed("RECENTLY ADDED", recent, false));
        for (Stremio.Catalog c : board) {
            Row r = catalogRow(c);
            if (home.size() < 7) home.add(r);
            if ("movie".equals(c.type)) movies.add(r);
            else series.add(r);
        }
        List<Stremio.Meta> libSeries = new ArrayList<>(), libMovies = new ArrayList<>();
        for (Stremio.Meta m : library) if (m.inLibrary) (m.series() ? libSeries : libMovies).add(m);
        if (!continuing.isEmpty()) lib.add(fixed("CONTINUE WATCHING", continuing, true));
        if (!recent.isEmpty()) lib.add(fixed("RECENTLY ADDED", recent, false));
        lib.add(fixed("SERIES · " + libSeries.size(), libSeries, false));
        lib.add(fixed("MOVIES · " + libMovies.size(), libMovies, false));
        keepFocus(HOME, home);
        keepFocus(MOVIES, movies);
        keepFocus(SERIES, series);
        keepFocus(LIBRARY, lib);
        if (first) area = RAIL;
    }

    /** What you've put in your library lately, newest first. */
    List<Stremio.Meta> recentlyAdded() {
        List<Stremio.Meta> out = new ArrayList<>();
        for (Stremio.Meta m : library) if (m.inLibrary && !m.added.isEmpty()) out.add(m);
        out.sort((a, b) -> b.added.compareTo(a.added));
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    final java.util.Map<String, Row> catalogCache = new java.util.HashMap<>();

    /** One row per catalog, shared by every section it appears in, and kept across refreshes. */
    Row catalogRow(Stremio.Catalog c) {
        String k = c.addon.base + "|" + c.type + "|" + c.id;
        Row r = catalogCache.get(k);
        if (r == null) {
            r = new Row();
            r.title = c.label();
            r.cat = c;
            catalogCache.put(k, r);
        }
        return r;
    }

    Row fixed(String t, List<Stremio.Meta> items, boolean resume) {
        Row r = new Row();
        r.title = t;
        r.items = items;
        r.resume = resume;
        r.asked = r.done = true;
        r.status = items.isEmpty() ? "NOTHING HERE YET" : "";
        return r;
    }

    void keepFocus(String section, List<Row> fresh) {
        List<Row> old = rows.get(section);
        if (old != null)
            for (Row n : fresh)
                for (Row o : old)
                    if (o.title.equals(n.title) || (o.cat != null && o.cat == n.cat)) {
                        n.focus = Math.min(o.focus, Math.max(0, n.items.size() - 1));
                        n.scroll = Math.min(o.scroll, n.focus);
                    }
        rows.put(section, fresh);
    }

    List<Row> section() {
        List<Row> r = rows.get(current);
        return r == null ? new ArrayList<>() : r;
    }

    /** Ask for the catalogs that are on screen (and the next one down). */
    void loadVisible() {
        List<Row> list = section();
        for (int i = rTop; i < Math.min(list.size(), rTop + 3); i++) {
            Row r = list.get(i);
            if (r.asked || r.cat == null) continue;
            r.asked = true;
            r.status = "LOADING";
            work.submit(() -> {
                List<Stremio.Meta> got = null;
                try {
                    got = Stremio.catalog(r.cat, "");
                } catch (Exception ignored) {
                }
                List<Stremio.Meta> g = got;
                ui.post(() -> {
                    r.done = true;
                    if (g == null) {
                        r.status = "COULDN'T LOAD";
                        r.asked = false;                // try again next time it's on screen
                    } else {
                        markProgress(g);
                        r.items = g;
                        r.status = g.isEmpty() ? "EMPTY" : "";
                    }
                    view.now();
                });
            });
        }
    }

    /** Catalog titles you've started get their progress bar too. */
    void markProgress(List<Stremio.Meta> list) {
        for (Stremio.Meta m : list) {
            if (m.raw == null) m.inLibrary = false;   // (a library item knows for itself)
            for (Stremio.Meta l : library)
                if (l.id.equals(m.id)) {
                    m.offset = l.offset;
                    m.duration = l.duration;
                    m.videoId = l.videoId;
                    m.inLibrary = l.inLibrary;
                }
        }
    }

    Row row() {
        List<Row> list = section();
        return rf >= 0 && rf < list.size() ? list.get(rf) : null;
    }

    Stremio.Meta focusedMeta() {
        if (area == TITLE) return title;
        if (area == RESULTS) {
            List<Stremio.Meta> l = list();
            return xf < l.size() && !"term".equals(l.get(xf).type) ? l.get(xf) : null;
        }
        Row r = row();
        return r == null || r.focus >= r.items.size() ? null : r.items.get(r.focus);
    }

    // ---------------------------------------------------------------- going places

    void handOff(Stremio.Meta m, String video) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.link(video))).setPackage(STREMIO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            openStremio();
        }
    }

    void openStremio() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(STREMIO);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(STREMIO);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else problem = "STREMIO ISN'T INSTALLED";
    }

    // "Previously on": for a series you're part-way through, where the story stands just before
    // your episode (spoiler-free beyond it), made once per episode by home's AI and kept.
    String recap = "", recapFor = "";

    void loadRecap(Stremio.Meta m) {
        String vid = m.videoId == null ? "" : m.videoId;
        String[] p = vid.split(":");
        if (!m.series() || p.length < 3) return;
        recapFor = vid;
        recap = prefs.getString("recap." + vid, "");
        if (!recap.isEmpty()) return;
        String season = p[p.length - 2], episode = p[p.length - 1];
        work.submit(() -> {
            try {
                android.os.Bundle b = new android.os.Bundle();
                b.putString("system", "You help someone pick a TV series back up. In at most 26 words, remind them where the "
                        + "story stands just before the episode they're about to watch: who is where, what's at stake. Never "
                        + "reveal anything from that episode or later. Plain English, no lists. If you don't know this series' "
                        + "episodes well enough to be accurate, reply exactly: UNKNOWN");
                b.putString("user", m.name + (m.year.isEmpty() ? "" : " (" + m.year + ")") + ", about to watch season "
                        + season + " episode " + episode + ".");
                b.putInt("max", 120);
                b.putBoolean("deep", true);
                android.os.Bundle r = getContentResolver().call(android.net.Uri.parse("content://com.quiet.home.prefs"), "ask", null, b);
                String text = r == null ? null : r.getString("text");
                if (text == null || text.trim().isEmpty() || text.contains("UNKNOWN")) text = "-";
                String t = text.trim();
                prefs.edit().putString("recap." + vid, t).apply();
                ui.post(() -> {
                    if (vid.equals(recapFor)) {
                        recap = t;
                        view.now();
                    }
                });
            } catch (Exception e) {
                android.util.Log.w("QuietWatch", "recap: " + e);
            }
        });
    }

    void openTitle(Stremio.Meta m, int from) {
        title = m;
        titleFrom = from;
        recap = "";
        recapFor = "";
        loadRecap(m);
        area = TITLE;
        ef = -1;
        eTop = 0;
        seasons.clear();
        if (m.full) {
            titleReady();
            return;
        }
        titleStatus = "LOADING";
        work.submit(() -> {
            String why = "";
            try {
                Stremio.details(addons, m);
            } catch (Exception e) {
                why = "COULDN'T LOAD THE DETAILS";
            }
            String w = why;
            ui.post(() -> {
                if (title != m) return;
                titleStatus = w;
                titleReady();
                view.now();
            });
        });
    }

    /** Seasons, and start on the one you're in the middle of. */
    void titleReady() {
        titleStatus = title.full ? "" : titleStatus;
        seasons.clear();
        if (title.videos != null)
            for (Stremio.Video v : title.videos) if (!seasons.contains(v.season)) seasons.add(v.season);
        if (seasons.size() > 1 && seasons.get(0) == 0) {           // specials go last
            seasons.remove(0);
            seasons.add(0);
        }
        sf = 0;
        if (!title.videoId.isEmpty() && title.videos != null)
            for (Stremio.Video v : title.videos)
                if (v.id.equals(title.videoId)) {
                    sf = Math.max(0, seasons.indexOf(v.season));
                    break;
                }
        season = seasons.isEmpty() ? 0 : seasons.get(sf);
        ef = -1;
        eTop = 0;
    }

    List<Stremio.Video> episodes() {
        List<Stremio.Video> out = new ArrayList<>();
        if (title == null || title.videos == null) return out;
        for (Stremio.Video v : title.videos) if (v.season == season) out.add(v);
        return out;
    }

    void signOut() {
        authKey = null;
        prefs.edit().remove("authKey").apply();
        library = new ArrayList<>();
        continuing = new ArrayList<>();
        addons = new ArrayList<>();
        rows.clear();
        catalogCache.clear();
        loaded = false;
        area = RAIL;
        rebuildBar();
        startLink();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (panel.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!down) closePanel();
                return true;
            }
            return getWindow().superDispatchKeyEvent(e) || super.onKeyDown(k, e);
        }
        if (inBar) return super.dispatchKeyEvent(e);
        if (authKey == null) {                       // the sign-in screen
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) enterBar(true);
                view.now();
                return true;
            }
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && !down) startSignIn("");
            if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
            return true;
        }
        boolean okKey = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (holdFor != null) {                       // the hold-OK menu is open
            if (okKey && !down) hold.cancel();       // the release of the hold that opened it
            else if (down && e.getRepeatCount() == 0) holdKey(k, okKey);   // still-held repeats choose nothing
            view.now();
            return true;
        }
        if (okKey && (area == ROWS || area == RESULTS || area == TITLE)) {   // tap: as before · hold: the menu
            hold.key(e);
            view.now();
            return true;
        }
        if (okKey && e.getRepeatCount() > 0) return true;          // a held OK never repeats
        if (!down) return k != KeyEvent.KEYCODE_BACK || area != RAIL ? true : super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_BACK && area == RAIL) return super.dispatchKeyEvent(e);
        view.poke();
        boolean handled = key(k);
        view.now();
        return handled || super.dispatchKeyEvent(e);
    }

    boolean key(int k) {
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, dn = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = k == KeyEvent.KEYCODE_BACK;
        int si = java.util.Arrays.asList(SECTIONS).indexOf(current);
        switch (area) {
            case RAIL:
                if (up && si == 0) enterBar(true);
                else if (up || dn) {
                    saveRowFocus();
                    current = SECTIONS[Math.max(0, Math.min(SECTIONS.length - 1, si + (up ? -1 : 1)))];
                    restoreRowFocus();
                } else if (right || ok) {
                    if (current.equals(SEARCH)) area = KEYS;
                    else if (!section().isEmpty()) area = ROWS;
                }
                return true;
            case ROWS: {
                List<Row> list = section();
                Row r = row();
                if (r == null) {
                    area = RAIL;
                    return true;
                }
                if (back) area = RAIL;
                else if (left) {
                    if (r.focus == 0) area = RAIL;
                    else r.focus--;
                } else if (right) {
                    if (r.focus + 1 < r.items.size()) r.focus++;
                    else stage.mote.look(1);
                } else if (up) {
                    if (rf == 0) enterBar(true);
                    else moveRow(list, -1);
                } else if (dn) {
                    if (rf + 1 < list.size()) moveRow(list, 1);
                } else if (ok) {
                    Stremio.Meta m = focusedMeta();
                    if (m != null) {
                        stage.mote.cheer();
                        if (r.resume) handOff(m, m.next != null ? m.next.id : m.videoId);   // straight back in (or on to the next)
                        else openTitle(m, ROWS);
                    }
                }
                if (r.focus < r.scroll) r.scroll = r.focus;
                if (r.focus >= r.scroll + COLS) r.scroll = r.focus - COLS + 1;
                return true;
            }
            case TITLE:
                return titleKey(up, dn, left, right, ok, back);
            case KEYS: {
                int col = kf % KEY_COLS;
                if (back || (left && col == 0)) area = RAIL;
                else if (left) kf--;
                else if (right && (col == KEY_COLS - 1 || kf == K_COUNT - 1)) {
                    if (!list().isEmpty()) area = RESULTS;
                } else if (right) kf = Math.min(K_COUNT - 1, kf + 1);
                else if (up && kf >= KEY_COLS) kf -= KEY_COLS;
                else if (up) enterBar(true);
                else if (dn && kf + KEY_COLS < K_COUNT) kf += KEY_COLS;
                else if (ok) type(kf);
                return true;
            }
            case RESULTS:
                if (back || left) area = KEYS;
                else if (up && xf > 0) xf--;
                else if (dn && xf + 1 < list().size()) xf++;
                else if (ok && xf < list().size()) {
                    Stremio.Meta pick = list().get(xf);
                    stage.mote.cheer();
                    if ("term".equals(pick.type)) {           // a recent search: search it again
                        query = pick.name;
                        xf = xTop = 0;
                        search();
                    } else {
                        if (!query.trim().isEmpty()) rememberSearch(query);
                        openTitle(pick, RESULTS);
                    }
                }
                return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- hold OK

    static final String H_PLAY = "PLAY IN STREMIO", H_NEXT = "PLAY THE NEXT EPISODE", H_PAGE = "OPEN ITS PAGE",
            H_DISMISS = "REMOVE FROM CONTINUE WATCHING", H_ADD = "ADD TO LIBRARY", H_REMOVE = "REMOVE FROM LIBRARY",
            H_POSTER = "WRONG POSTER? TRY ANOTHER", H_POSTER_BACK = "BACK TO THE FIRST POSTER";

    void openHold() {
        Stremio.Meta m = focusedMeta();
        if (m == null) return;
        stage.mote.cheer();
        holdFor = m;
        holdItems = new ArrayList<>();
        boolean cw = continuing.contains(m) || m.offset > 0;
        if (m.next != null) holdItems.add(H_NEXT);
        if (cw) holdItems.add(H_PLAY);
        if (area != TITLE) holdItems.add(H_PAGE);
        if (cw && m.raw != null) holdItems.add(H_DISMISS);
        holdItems.add(m.inLibrary ? H_REMOVE : H_ADD);
        if (m.id.startsWith("tt")) holdItems.add(posters.alternate(m.id) ? H_POSTER_BACK : H_POSTER);
        holdFocus = 0;
    }

    void holdKey(int k, boolean ok) {
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_LEFT) holdFor = null;
        else if (k == KeyEvent.KEYCODE_DPAD_UP) holdFocus = Math.max(0, holdFocus - 1);
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) holdFocus = Math.min(holdItems.size() - 1, holdFocus + 1);
        else if (ok) {
            Stremio.Meta m = holdFor;
            String what = holdItems.get(holdFocus);
            holdFor = null;
            switch (what) {
                case H_NEXT: handOff(m, m.next.id); break;
                case H_PLAY: handOff(m, m.videoId); break;
                case H_PAGE: openTitle(m, area); break;
                case H_POSTER:
                case H_POSTER_BACK:
                    posters.swap(m.id);
                    notice = what.equals(H_POSTER) ? "TRYING IMDB'S POSTER" : "BACK TO THE FIRST POSTER";
                    noticeUntil = SystemClock.uptimeMillis() + 3000;
                    break;
                default: change(m, what);
            }
        }
    }

    /** Write a library change to your Stremio account, then catch up. */
    void change(Stremio.Meta m, String what) {
        String key = authKey;
        work.submit(() -> {
            String done;
            try {
                if (what.equals(H_DISMISS)) Stremio.dismiss(key, m);
                else Stremio.library(key, m, what.equals(H_ADD));
                done = what.equals(H_DISMISS) ? "REMOVED FROM CONTINUE WATCHING" : what.equals(H_ADD) ? "ADDED TO YOUR LIBRARY" : "REMOVED FROM YOUR LIBRARY";
            } catch (Exception e) {
                done = "COULDN'T · " + (e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT));
            }
            String d = done;
            ui.post(() -> {
                notice = d;
                noticeUntil = SystemClock.uptimeMillis() + 4000;
                loading = false;
                refresh();
                view.now();
            });
        });
    }

    void holdMenu(Stage s) {
        if (holdFor == null) return;
        int w = 0;
        for (String it : holdItems) w = Math.max(w, Font.microWidth(it, 1));
        w += 16;
        int h = holdItems.size() * 10 + 14, x = Math.min(312 - w, CX + 20), y = 60;
        s.rect(x - 1, y - 1, w + 2, h + 2, Pal.FAINT);
        s.rect(x, y, w, h, Pal.VOID);
        s.over(x - 1, y - 1, w + 2, h + 2);
        s.small(fit(Font.upper(holdFor.name), w - 8), x + 6, y + 4, 1, Pal.DIM);
        for (int i = 0; i < holdItems.size(); i++) {
            boolean f = i == holdFocus;
            int yy = y + 14 + i * 10;
            if (f) s.rect(x + 3, yy - 2, w - 6, 9, Pal.AMBER);
            s.small(holdItems.get(i), x + 6, yy, 1, f ? Pal.VOID : Pal.SOFT);
        }
    }

    void moveRow(List<Row> list, int d) {
        Row from = list.get(rf);
        int col = from.focus - from.scroll;                  // keep roughly the same column
        rf += d;
        Row to = list.get(rf);
        if (!to.items.isEmpty()) {
            to.focus = Math.max(0, Math.min(to.items.size() - 1, to.scroll + col));
        }
        if (rf < rTop) rTop = rf;
        if (rf > rTop + 1) rTop = rf - 1;
    }

    void saveRowFocus() {
        rowFocus.put(current, new int[]{rf, rTop});
    }

    void restoreRowFocus() {
        int[] f = rowFocus.get(current);
        rf = f == null ? 0 : f[0];
        rTop = f == null ? 0 : f[1];
        if (rf >= section().size()) rf = rTop = 0;
    }

    boolean titleKey(boolean up, boolean dn, boolean left, boolean right, boolean ok, boolean back) {
        if (back) {
            area = titleFrom;
            return true;
        }
        if (title == null) return true;
        if (!title.series()) {
            if (ok) {
                stage.mote.cheer();
                handOff(title, title.id);
            } else if (up) enterBar(true);
            return true;
        }
        List<Stremio.Video> eps = episodes();
        if (ef < 0) {                                   // on the season tabs
            if (left && sf > 0) pickSeason(sf - 1);
            else if (right && sf + 1 < seasons.size()) pickSeason(sf + 1);
            else if ((dn || ok) && !eps.isEmpty()) ef = resumeIndex(eps);
            else if (up) enterBar(true);
        } else {
            if (up) {
                if (ef == 0) ef = -1;
                else ef--;
            } else if (dn && ef + 1 < eps.size()) ef++;
            else if (ok && ef < eps.size()) {
                stage.mote.cheer();
                handOff(title, eps.get(ef).id);
            }
            int rowsVisible = 5;
            if (ef >= 0 && ef < eTop) eTop = ef;
            if (ef >= eTop + rowsVisible) eTop = ef - rowsVisible + 1;
        }
        return true;
    }

    void pickSeason(int i) {
        sf = i;
        season = seasons.get(i);
        eTop = 0;
    }

    int resumeIndex(List<Stremio.Video> eps) {
        for (int i = 0; i < eps.size(); i++) if (eps.get(i).id.equals(title.videoId)) return i;
        return 0;
    }

    void type(int key) {
        if (key == K_SPACE) {
            if (!query.isEmpty() && !query.endsWith(" ")) query += " ";
        } else if (key == K_DEL) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
        } else if (key == K_CLEAR) {
            query = "";
            results.clear();
            suggested.clear();
            searched = "";
            searchStatus = "";
        } else if (query.length() < 32) {
            query += KEYS_TEXT.charAt(key);
        }
        typedAt = SystemClock.uptimeMillis();
    }

    void search() {
        String q = query.trim();
        searched = query;
        String reply = stage.answer(q);                   // a few words the well answers to
        if (reply != null) {
            results = new ArrayList<>();
            searchStatus = reply;
            return;
        }
        searchStatus = "SEARCHING";
        if (!loaded) {                                    // add-ons not here yet: the frame asks again
            searched = "";
            return;
        }
        List<Stremio.Addon> ads = addons;
        boolean describe = askAi || q.split("\\s+").length >= 4;
        askAi = false;
        if (describe) searchStatus = "THINKING ABOUT IT";
        work.submit(() -> {
            List<Stremio.Meta> got = Stremio.search(ads, q);
            if (describe || got.isEmpty()) {                  // a description, not a title: ask, then find each
                List<Stremio.Meta> picks = suggest(ads, q);
                java.util.Set<String> ids = new java.util.HashSet<>();
                List<Stremio.Meta> all = new ArrayList<>();
                for (Stremio.Meta m : picks) if (ids.add(m.id)) all.add(m);
                for (Stremio.Meta m : got) if (ids.add(m.id)) all.add(m);
                got = all;
            }
            List<Stremio.Meta> found = got;
            ui.post(() -> {
                if (!searched.equals(query)) return;        // typed more meanwhile
                markProgress(found);
                results = found;
                xf = xTop = 0;
                searchStatus = found.isEmpty() && suggested.isEmpty() ? "NOTHING FOUND" : "";
                if (!found.isEmpty()) rememberSearch(searched);
                if (openFirst) {
                    openFirst = false;
                    if (!found.isEmpty()) openTitle(found.get(0), RESULTS);
                }
                view.now();
            });
        });
    }

    /**
     * Search that understands: the request ('something light and funny in Malayalam', 'like
     * Kumbalangi Nights') becomes real titles (home's AI, the richer model), and each is found in
     * your own catalogs, in the order suggested.
     */
    List<Stremio.Meta> suggest(List<Stremio.Addon> ads, String q) {
        List<Stremio.Meta> out = new ArrayList<>();
        try {
            StringBuilder taste = new StringBuilder();
            for (Stremio.Meta m : continuing) if (taste.length() < 300) taste.append(m.name).append(", ");
            android.os.Bundle b = new android.os.Bundle();
            b.putString("system", "You suggest films and TV series for a household in Kerala, India, who watch Malayalam, "
                    + "Hindi, Tamil and English titles. Given a request, list up to 8 real, existing titles that best fit, best "
                    + "first. If the request names a title, include it first. Reply only as JSON: {\"titles\":[{\"name\": "
                    + "official English title as on IMDb, \"year\": number, \"type\": \"movie\" or \"series\"}]}");
            b.putString("user", "Request: " + q + (taste.length() > 0 ? "\nThey're in the middle of: " + taste : ""));
            b.putInt("max", 500);
            b.putBoolean("json", true);
            b.putBoolean("deep", true);
            android.os.Bundle r = getContentResolver().call(android.net.Uri.parse("content://com.quiet.home.prefs"), "ask", null, b);
            String text = r == null ? null : r.getString("text");
            if (text == null) return out;
            org.json.JSONArray titles = new org.json.JSONObject(text).optJSONArray("titles");
            if (titles == null) return out;
            List<java.util.concurrent.Future<Stremio.Meta>> finds = new ArrayList<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            for (int i = 0; i < Math.min(8, titles.length()); i++) {
                org.json.JSONObject t = titles.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name"), type = t.optString("type");
                int year = t.optInt("year");
                finds.add(pool.submit(() -> {
                    Stremio.Meta best = null;
                    for (Stremio.Meta m : Stremio.search(ads, name)) {
                        boolean sameType = type.isEmpty() || type.equals(m.type);
                        int y = 0;
                        try {
                            y = Integer.parseInt(m.year.replaceAll("[^0-9].*$", ""));
                        } catch (Exception ignored) {
                        }
                        boolean sameYear = year == 0 || y == 0 || Math.abs(y - year) <= 1;
                        if (sameType && sameYear && Stremio.match(m.name, name) > 0) return m;
                        if (best == null && Stremio.match(m.name, name) > 0) best = m;
                    }
                    return best;
                }));
            }
            for (java.util.concurrent.Future<Stremio.Meta> f : finds) {
                try {
                    Stremio.Meta m = f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    if (m != null) out.add(m);
                } catch (Exception ignored) {
                }
            }
            pool.shutdownNow();
        } catch (Exception e) {
            android.util.Log.w("QuietWatch", "suggest: " + e);
        }
        return out;
    }

    @Override
    protected void selectIcon(IconBar.Icon icon) {
        if (OPEN.equals(icon.tag)) openStremio();
        else if (AGAIN.equals(icon.tag)) refresh();
        else if (ACCOUNT.equals(icon.tag)) {
            enterBar(false);
            if (authKey != null) signOut();
            else startSignIn("");                  // the typed way; the QR is already on screen
        }
        view.now();
    }

    @Override
    protected void select(Menu.Item item) {}

    // ---------------------------------------------------------------- drawing

    @Override
    protected void lights(Stage s) {
        if (inBar) return;
        float[] p = focusPoint();
        s.light(p[0], p[1] + 10, 44, 0.32f, true);
    }

    @Override
    protected float[] restingPlace() {
        return focusPoint();
    }

    @Override
    protected void draw(Stage s) {
        if (authKey == null) {
            signInScreen(s);
            return;
        }
        if (current.equals(SEARCH) && query.trim().length() >= 2 && !query.equals(searched)
                && SystemClock.uptimeMillis() - typedAt > 700) search();
        if (current.equals(SEARCH) && !query.trim().isEmpty() && !query.equals(suggestedFor)
                && SystemClock.uptimeMillis() - typedAt > 200) suggest();
        if (query.trim().isEmpty() && !suggested.isEmpty()) suggested.clear();
        rail(s);
        if (!loaded) {
            s.small((problem.isEmpty() ? "LOADING" : problem) + dots(s), CX, 14, 1, problem.isEmpty() ? Pal.DIM : Pal.PINK);
            return;
        }
        if (!notice.isEmpty() && SystemClock.uptimeMillis() < noticeUntil) s.small(notice, CX, 156, 1, Pal.AMBER);
        if (area == TITLE) titlePage(s);
        else if (current.equals(SEARCH)) searchScreen(s);
        else rowsScreen(s);
    }

    void rail(Stage s) {
        s.text("WATCH", RAIL_X - 10, 12, 2, Pal.TEXT);
        for (int i = 0; i < SECTIONS.length; i++) {
            int y = 42 + i * 11;
            boolean here = SECTIONS[i].equals(current);
            int c = area == RAIL && here && !inBar ? Pal.TEXT : here ? Pal.AMBER : Pal.DIM;
            s.small(SECTIONS[i], RAIL_X, y, 2, c);
            if (here && area != RAIL) s.rect(RAIL_X - 5, y + 2, 2, 1, Pal.AMBER);
        }
        if (!problem.isEmpty() && loaded) s.small(fit(problem, 70), RAIL_X, 110, 1, Pal.PINK);
    }

    /** The selected title's name and a line about it, above the rows. */
    void header(Stage s, Stremio.Meta m, String fallback) {
        if (m == null) {
            s.text(fallback, CX, 12, 1, Pal.TEXT);
            return;
        }
        String n = Font.upper(m.name);
        s.text(clipWide(n, 212), CX, 12, 1, Pal.TEXT);
        String where = m.next != null ? "NEXT · S" + m.next.season + " E" + m.next.episode
                + (m.next.title.isEmpty() ? "" : " · " + Font.upper(m.next.title)) : m.episode();
        String left = m.next != null ? "" : m.left();
        String line = !where.isEmpty() || !left.isEmpty()
                ? (where + (where.isEmpty() || left.isEmpty() ? "" : " · ") + left)
                : Font.upper(m.line());
        s.small(fit(line, 212), CX, 23, 1, !where.isEmpty() || !left.isEmpty() ? Pal.AMBER : Pal.DIM);
    }

    void rowsScreen(Stage s) {
        List<Row> list = section();
        if (list.isEmpty()) {
            header(s, null, current);
            s.small(current.equals(LIBRARY) ? "NOTHING SAVED YET" : "NO CATALOGS FROM YOUR ADD-ONS", CX, ROW_Y, 1, Pal.DIM);
            return;
        }
        if (rf >= list.size()) rf = rTop = 0;
        loadVisible();
        header(s, area == ROWS ? focusedMeta() : null, current);
        for (int i = rTop; i < Math.min(list.size(), rTop + 2); i++) {
            Row r = list.get(i);
            int y = ROW_Y + (i - rTop) * ROW_PITCH;
            boolean rowHere = area == ROWS && i == rf && !inBar;
            s.small(fit(r.title, 212), CX, y, 1, rowHere ? Pal.SOFT : Pal.DIM);
            if (r.items.isEmpty()) {
                s.small(r.status.isEmpty() ? "" : r.status + (r.status.equals("LOADING") ? dots(s) : ""), CX, y + 14, 1, Pal.DIM);
                continue;
            }
            ribbons = r.cat != null;                     // rows from your library don't need telling
            badges = r.resume;                           // where you are, on the posters you're partway through
            for (int j = r.scroll; j < Math.min(r.items.size(), r.scroll + COLS); j++) {
                int x = CX + (j - r.scroll) * PITCH;
                poster(s, r.items.get(j), x, y + 8, PW, PH, rowHere && j == r.focus);
            }
            ribbons = true;
            badges = false;
            if (r.scroll > 0) s.small("<", CX - 6, y + 8 + PH / 2 - 2, 1, Pal.FAINT);
            if (r.scroll + COLS < r.items.size()) s.small(">", CX + COLS * PITCH - 3, y + 8 + PH / 2 - 2, 1, Pal.FAINT);
        }
        if (rTop > 0) s.small("▲", 312, ROW_Y - 2, 1, Pal.FAINT);
        if (rTop + 2 < list.size()) s.small("▼", 312, ROW_Y + 2 * ROW_PITCH - 8, 1, Pal.FAINT);
    }

    boolean ribbons = true, badges;

    void poster(Stage s, Stremio.Meta m, int x, int y, int w, int h, boolean f) {
        if (f) s.rect(x - 2, y - 2, w + 4, h + 4, Pal.AMBER);
        s.rect(x - 1, y - 1, w + 2, h + 2, Pal.VOID);
        android.graphics.Bitmap fine = posters.get(m, w, h);
        if (fine != null) {
            s.fine(fine, x, y, w, h, holdFor != null ? (f ? 0.3f : 0.55f) : f || area == TITLE ? 0f : 0.22f);
            if (m.inLibrary && ribbons && area != TITLE) {   // a ribbon: it's in your library
                int rx = x + w - 6;
                s.over(rx, y, 4, 7);
                s.rect(rx, y, 4, 6, f ? Pal.MINT_HI : Pal.MINT);
                s.rect(rx + 1, y + 6, 2, 1, Pal.VOID);
                s.rect(rx, y + 6, 1, 1, f ? Pal.MINT_HI : Pal.MINT);
                s.rect(rx + 3, y + 6, 1, 1, f ? Pal.MINT_HI : Pal.MINT);
            }
        } else {
            s.rect(x, y, w, h, Pal.COOL[2]);
            s.small(initials(m.name), x + 3, y + 4, 1, Pal.SOFT);
        }
        String badge = badges && fine != null ? m.badge() : "";
        if (!badge.isEmpty()) {                                     // a band across the foot of the poster
            int bw = Font.microWidth(badge, 1);
            if (bw > w - 2) badge = badge.contains(" ") ? badge.substring(0, badge.indexOf(' ')) : badge;
            bw = Font.microWidth(badge, 1);
            s.over(x, y + h - 7, w, 7);
            s.rect(x, y + h - 7, w, 7, Pal.VOID);
            s.small(badge, x + (w - bw) / 2, y + h - 6, 1, f ? Pal.AMBER : m.next != null ? Pal.MINT : Pal.SOFT);
        }
        if (m.offset > 0 && m.duration > 0) {                       // how far you got
            s.rect(x, y + h + 2, w, 1, Pal.FAINT);
            s.rect(x, y + h + 2, Math.max(1, Math.round(w * m.progress())), 1, f ? Pal.AMBER : Pal.DIM);
        }
    }

    void titlePage(Stage s) {
        Stremio.Meta m = title;
        if (m == null) return;
        s.scrim(CX - 4, 8, 320 - CX + 2, 156);
        String n = Font.upper(m.name);
        s.text(clipWide(n, 212), CX, 12, 1, Pal.TEXT);
        List<String> bits = new ArrayList<>();
        if (!m.year.isEmpty()) bits.add(m.year);
        if (!m.runtime.isEmpty()) bits.add(Font.upper(m.runtime));
        if (!m.rating.isEmpty()) bits.add("IMDB " + m.rating);
        if (!m.genres.isEmpty()) bits.add(Font.upper(m.genres));
        s.small(fit(String.join(" · ", bits), 212), CX, 23, 1, Pal.DIM);

        poster(s, m, CX, 32, TP_W, TP_H, false);
        int y = 33;
        if (!titleStatus.isEmpty()) {
            s.small(titleStatus + (titleStatus.equals("LOADING") ? dots(s) : ""), TX, y, 1, titleStatus.equals("LOADING") ? Pal.DIM : Pal.PINK);
            y += 10;
        }
        boolean previously = m.series() && recapFor.equals(m.videoId) && recap.length() > 1;
        if (previously) {                                     // where you left the story, instead of the blurb
            String[] p = recapFor.split(":");
            s.small("PREVIOUSLY · BEFORE S" + p[p.length - 2] + " E" + p[p.length - 1], TX, y, 1, Pal.AMBER);
            y += 9;
        }
        for (String line : wrap(Font.upper(previously ? recap : m.description), 164, previously ? 4 : 5)) {
            s.small(line, TX, y, 1, Pal.SOFT);
            y += 8;
        }
        if (!m.cast.isEmpty()) {
            s.small(fit("WITH " + Font.upper(m.cast), 164), TX, Math.max(y + 2, 76), 1, Pal.DIM);
        }
        if (!m.director.isEmpty() && !m.series()) s.small(fit("DIRECTED BY " + Font.upper(m.director), 164), TX, Math.max(y + 10, 84), 1, Pal.DIM);

        if (!m.series()) {
            String label = m.offset > 0 && !m.left().isEmpty() ? "RESUME · " + m.left() : "PLAY";
            button(s, label, CX, 100, area == TITLE && !inBar);
            s.small("OPENS ITS SOURCES IN STREMIO", CX, 112, 1, Pal.FAINT);
            return;
        }
        if (seasons.isEmpty()) {
            if (titleStatus.isEmpty()) s.small("NO EPISODES LISTED", CX, 104, 1, Pal.DIM);
            return;
        }
        // Season tabs.
        int x = CX;
        for (int i = 0; i < seasons.size(); i++) {
            String t = seasons.get(i) == 0 ? "EXTRAS" : "S" + seasons.get(i);
            int w = Font.microWidth(t, 1);
            if (x + w > 312) break;
            boolean here = i == sf;
            boolean f = here && ef < 0 && !inBar;
            if (f) s.rect(x - 2, 98, w + 4, 9, Pal.AMBER);
            s.small(t, x, 100, 1, f ? Pal.VOID : here ? Pal.AMBER : Pal.DIM);
            x += w + 8;
        }
        List<Stremio.Video> eps = episodes();
        int rowsVisible = 5;
        for (int i = eTop; i < Math.min(eps.size(), eTop + rowsVisible); i++) {
            Stremio.Video v = eps.get(i);
            int yy = 111 + (i - eTop) * 9;
            boolean f = i == ef && !inBar;
            boolean resume = v.id.equals(m.videoId);
            String num = "E" + v.episode;
            s.small(num, CX, yy, 1, resume ? Pal.AMBER : Pal.DIM);
            String name = Font.upper(v.title.isEmpty() ? "EPISODE " + v.episode : v.title);
            s.small(fit(name, 150), CX + 18, yy, 1, f ? Pal.TEXT : resume ? Pal.AMBER : Pal.SOFT);
            String right = resume && !m.left().isEmpty() ? m.left() : v.released;
            s.small(right, 312 - Font.microWidth(right, 1), yy, 1, resume ? Pal.AMBER : Pal.DIM);
            if (f) s.rect(CX - 5, yy + 2, 2, 1, Pal.AMBER);
        }
        if (eTop > 0) s.small("▲", 304, 100, 1, Pal.FAINT);
        if (eTop + rowsVisible < eps.size()) s.small("▼", 304, 156, 1, Pal.FAINT);
    }

    void button(Stage s, String label, int x, int y, boolean f) {
        int w = Font.microWidth(label, 1) + 7;
        s.rect(x, y, w, 9, f ? Pal.AMBER : Pal.FAINT);
        s.rect(x + 1, y + 1, w - 2, 7, f ? Pal.AMBER : Pal.VOID);
        s.small(label, x + 4, y + 2, 1, f ? Pal.VOID : Pal.SOFT);
    }

    void searchScreen(Stage s) {
        s.scrim(LIST_X - 14, 32, 320 - (LIST_X - 14), 132);
        header(s, area == RESULTS ? focusedMeta() : null, SEARCH);
        boolean caret = ((int) (s.t * 2) & 1) == 0 && area == KEYS;
        if (area != RESULTS) {
            int end = s.small(fit(query, 70), CX, 24, 1, Pal.TEXT);
            if (caret) s.rect(end + 1, 24, 1, 5, Pal.AMBER);
        }
        s.rect(CX, 31, KEY_COLS * KEY_W - 2, 1, Pal.FAINT);
        for (int k = 0; k < K_COUNT; k++) {
            int x = KEY_X + (k % KEY_COLS) * KEY_W, y = KEY_Y + (k / KEY_COLS) * KEY_H;
            boolean f = area == KEYS && k == kf && !inBar;
            if (f) {
                s.rect(x - 1, y - 1, KEY_W, KEY_H, Pal.AMBER);
                s.rect(x, y, KEY_W - 2, KEY_H - 2, Pal.VOID);
            }
            String label = k == K_SPACE ? "_" : k == K_DEL ? "<" : k == K_CLEAR ? "X" : String.valueOf(KEYS_TEXT.charAt(k));
            s.small(label, x + 2, y + 2, 1, f ? Pal.AMBER : k >= K_SPACE ? Pal.DIM : Pal.SOFT);
        }
        s.small("_ SPACE  < DELETE", KEY_X, KEY_Y + 6 * KEY_H + 3, 1, Pal.FAINT);
        s.small("X CLEAR", KEY_X, KEY_Y + 6 * KEY_H + 10, 1, Pal.FAINT);
        List<Stremio.Meta> list = list();
        if (list.isEmpty()) {
            String msg = !searchStatus.isEmpty() ? searchStatus + (searchStatus.equals("SEARCHING") ? dots(s) : "")
                    : query.trim().isEmpty() ? "TYPE TO SEARCH" : "";
            s.small(msg, LIST_X, 38, 1, searchStatus.isEmpty() || searchStatus.equals("SEARCHING") || searchStatus.equals("NOTHING FOUND") ? Pal.DIM : Pal.AMBER);
            return;
        }
        if (xf >= list.size()) xf = list.size() - 1;
        int rows = 7;
        if (xf < xTop) xTop = xf;
        if (xf >= xTop + rows) xTop = xf - rows + 1;
        boolean popular = query.trim().isEmpty();
        for (int i = xTop; i < Math.min(list.size(), xTop + rows); i++) {
            Stremio.Meta it = list.get(i);
            int y = 36 + (i - xTop) * 18;
            boolean f = area == RESULTS && i == xf && !inBar;
            if ("term".equals(it.type)) {                    // a recent search: just the words
                if (f) s.rect(LIST_X - 1, y - 1, 3, 16, Pal.AMBER);
                s.small(">", LIST_X + 4, y + 5, 1, f ? Pal.AMBER : Pal.FAINT);
                s.small(fit(Font.upper(it.name), 112), LIST_X + 16, y + 2, 1, f ? Pal.TEXT : Pal.SOFT);
                s.small("RECENT SEARCH", LIST_X + 16, y + 9, 1, Pal.DIM);
                continue;
            }
            if (popular) {
                poster(s, it, LIST_X, y, 10, 15, f);
                s.small(fit(Font.upper(it.name), 112), LIST_X + 16, y + 2, 1, f ? Pal.TEXT : Pal.SOFT);
                s.small(fit("POPULAR" + (it.year.isEmpty() ? "" : " · " + it.year), 112), LIST_X + 16, y + 9, 1, Pal.DIM);
                continue;
            }
            poster(s, it, LIST_X, y, 10, 15, f);
            s.small(fit(Font.upper(it.name), 112), LIST_X + 16, y + 2, 1, f ? Pal.TEXT : Pal.SOFT);
            String kind = it.series() ? "SERIES" : "FILM";
            s.small(fit(kind + (it.year.isEmpty() ? "" : " · " + it.year), 112), LIST_X + 16, y + 9, 1, Pal.DIM);
        }
    }

    void signInScreen(Stage s) {
        s.text("WATCH", 14, 12, 3, Pal.TEXT);
        s.small(problem.isEmpty() ? "STREMIO" : problem, 15, 40, 2, problem.isEmpty() ? Pal.DIM : Pal.PINK);
        s.text("SIGN IN TO STREMIO", 24, 62, 1, Pal.TEXT);
        s.small("SCAN WITH YOUR PHONE, OR OPEN", 24, 76, 1, Pal.DIM);
        s.text(linkCode == null ? "..." : "LINK.STREMIO.COM/" + linkCode, 24, 86, 1, Pal.AMBER);
        s.small("ANY STREMIO ACCOUNT WORKS:", 24, 102, 1, Pal.DIM);
        s.small("GOOGLE, APPLE, FACEBOOK, EMAIL", 24, 110, 1, Pal.DIM);
        s.small("OK · TYPE EMAIL + PASSWORD", 24, 126, 1, inBar ? Pal.FAINT : Pal.DIM);
    }

    int tabletSize() {
        return qr == null ? 0 : (qr.length + QZ * 2) * MOD;
    }

    @Override
    protected void overlay(Stage s) {
        holdMenu(s);                                    // above the creatures too
        if (authKey != null || qr == null) return;
        int sz = tabletSize(), x0 = 306 - sz;
        s.rect(x0 - 2, TABLET_Y - 2, sz + 4, sz + 4, Pal.COOL[2]);
        s.rect(x0 - 1, TABLET_Y - 1, sz + 2, sz + 2, Pal.COOL[4]);
        s.rect(x0, TABLET_Y, sz, sz, Pal.TEXT);
        s.rect(x0 - 1, TABLET_Y + sz + 2, sz + 2, 1, Pal.VOID);
        for (int y = 0; y < qr.length; y++)
            for (int x = 0; x < qr.length; x++)
                if (qr[y][x]) s.rect(x0 + (QZ + x) * MOD, TABLET_Y + (QZ + y) * MOD, MOD, MOD, Pal.VOID);
    }

    /** Where Mote hovers for whatever is selected. */
    float[] focusPoint() {
        if (authKey == null) return new float[]{14, 128};
        switch (area) {
            case ROWS: {
                Row r = row();
                if (r == null) break;
                int x = CX + (r.focus - r.scroll) * PITCH, y = ROW_Y + (rf - rTop) * ROW_PITCH + 8;
                return new float[]{x - 6, y + 7};       // beside the poster, clear of the row's label
            }
            case TITLE:
                if (title != null && !title.series()) return new float[]{CX - 9, 104};
                if (ef < 0) return new float[]{CX - 9, 101};
                return new float[]{CX - 11, 111 + (ef - eTop) * 9 + 2};
            case KEYS: {
                int x = KEY_X + (kf % KEY_COLS) * KEY_W, y = KEY_Y + (kf / KEY_COLS) * KEY_H;
                return new float[]{x + 4, y - 6};
            }
            case RESULTS:
                return new float[]{LIST_X - 9, 36 + (xf - xTop) * 18 + 6};
        }
        int i = java.util.Arrays.asList(SECTIONS).indexOf(current);
        return new float[]{RAIL_X - 12, 42 + i * 11 + 2};
    }

    // ---------------------------------------------------------------- text helpers

    static String dots(Stage s) {
        int n = ((int) (s.t * 3)) % 4;
        return n == 0 ? "" : n == 1 ? "." : n == 2 ? ".." : "...";
    }

    static String fit(String s, int w) {
        if (Font.microWidth(s, 1) <= w) return s;
        while (s.length() > 1 && Font.microWidth(s + "..", 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "..";
    }

    static String clipWide(String s, int w) {
        if (Font.width(s, 1, 1) <= w) return s;
        while (s.length() > 1 && Font.width(s + "...", 1, 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "...";
    }

    /** Word-wrap into at most n lines of micro text, the last one ending in ".." if cut. */
    static List<String> wrap(String text, int w, int n) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').split(" ")) {
            if (word.isEmpty()) continue;
            String next = line.length() == 0 ? word : line + " " + word;
            if (Font.microWidth(next, 1) <= w) {
                line = new StringBuilder(next);
                continue;
            }
            out.add(line.toString());
            line = new StringBuilder(word);
            if (out.size() == n) break;
        }
        if (out.size() < n && line.length() > 0) out.add(line.toString());
        else if (out.size() == n && line.length() > 0) out.set(n - 1, fit(out.get(n - 1) + " " + line, w));
        return out;
    }

    static String initials(String n) {
        StringBuilder b = new StringBuilder();
        for (String w : Font.upper(n).split(" ")) if (!w.isEmpty() && b.length() < 3) b.append(w.charAt(0));
        return b.toString();
    }

    // ---------------------------------------------------------------- signing in

    void startLink() {
        if (authKey != null) return;
        polling = true;
        ui.removeCallbacks(poll);
        boolean fresh = linkCode == null || System.currentTimeMillis() - linkAt > 4 * 60_000;
        if (!fresh) {
            ui.postDelayed(poll, 3000);
            return;
        }
        work.submit(() -> {
            try {
                String c = Stremio.linkCode();
                boolean[][] q = Qr.encode(Stremio.LINK + c);
                ui.post(() -> {
                    linkCode = c;
                    qr = q;
                    linkAt = System.currentTimeMillis();
                    view.now();
                    if (polling) ui.postDelayed(poll, 3000);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    problem = "CAN'T REACH STREMIO";
                    if (polling) ui.postDelayed(this::startLink, 10_000);
                });
            }
        });
    }

    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling || authKey != null || linkCode == null) return;
            if (System.currentTimeMillis() - linkAt > 4 * 60_000) {      // codes don't last forever
                startLink();
                return;
            }
            String code = linkCode;
            work.submit(() -> {
                String key = null;
                try {
                    key = Stremio.linkedKey(code);
                } catch (Exception ignored) {
                }
                String k = key;
                ui.post(() -> {
                    if (k != null && authKey == null) signedIn(k);
                    else if (polling) ui.postDelayed(poll, 3000);
                });
            });
        }
    };

    void signedIn(String key) {
        authKey = key;
        prefs.edit().putString("authKey", key).apply();
        problem = "";
        polling = false;
        ui.removeCallbacks(poll);
        linkCode = null;
        qr = null;
        stage.mote.cheer();
        rebuildBar();
        refresh();
        view.now();
    }

    void startSignIn(String error) {
        email = "";
        openPanel("email", error);
    }

    void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF00B0B10);
        panel.setPadding(120, 70, 120, 60);
        panel.setVisibility(View.GONE);
        android.graphics.Typeface mono = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        panelTitle = new TextView(this);
        panelTitle.setTypeface(mono);
        panelTitle.setTextColor(Pal.AMBER);
        panelTitle.setTextSize(22);
        panelTitle.setLetterSpacing(0.15f);
        panel.addView(panelTitle);
        panelError = new TextView(this);
        panelError.setTypeface(mono);
        panelError.setTextColor(Pal.PINK);
        panelError.setTextSize(16);
        panelError.setPadding(0, 14, 0, 0);
        panel.addView(panelError);
        typer = new EditText(this);
        typer.setTypeface(mono);
        typer.setTextColor(Pal.TEXT);
        typer.setTextSize(30);
        typer.setSingleLine(true);
        typer.setBackgroundColor(0x00000000);
        typer.setPadding(0, 30, 0, 20);
        typer.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        typer.setOnEditorActionListener((v, action, ev) -> {
            typed(typer.getText().toString().trim());
            return true;
        });
        panel.addView(typer, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(this);
        rule.setBackgroundColor(Pal.AMBER);
        panel.addView(rule, new LinearLayout.LayoutParams(-1, 4));
        TextView hint = new TextView(this);
        hint.setTypeface(mono);
        hint.setTextColor(Pal.DIM);
        hint.setTextSize(16);
        hint.setPadding(0, 24, 0, 0);
        hint.setText("SENT ONLY TO STREMIO  ·  WATCH KEEPS A SIGN-IN KEY, NEVER YOUR PASSWORD  ·  BACK TO CANCEL");
        panel.addView(hint);
        addContentView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    void openPanel(String kind, String error) {
        step = kind;
        if (kind.equals("email")) {
            panelTitle.setText("YOUR STREMIO EMAIL");
            typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        } else {
            panelTitle.setText("YOUR STREMIO PASSWORD");
            typer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        panelError.setText(error.toUpperCase(Locale.ROOT));
        panelError.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        typer.setText("");
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
        typer.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(typer, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    void closePanel() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(typer.getWindowToken(), 0);
        typer.setText("");
        panel.setVisibility(View.GONE);
        view.requestFocus();
    }

    void typed(String text) {
        if (text.isEmpty()) return;
        if (step.equals("email")) {
            email = text.toLowerCase(Locale.ROOT);       // TV keyboards love a capital first letter
            closePanel();
            ui.postDelayed(() -> openPanel("password", ""), 200);
            return;
        }
        String pass = text;
        closePanel();
        problem = "SIGNING IN";
        work.submit(() -> {
            String key = null, why = "";
            try {
                key = Stremio.login(email, pass);
            } catch (Exception e) {
                why = e.getMessage() == null ? "COULDN'T SIGN IN" : e.getMessage();
            }
            String k = key, w = why;
            ui.post(() -> {
                if (k != null) {
                    signedIn(k);
                } else {
                    problem = "";
                    boolean badPass = w.toLowerCase(Locale.ROOT).contains("pass");
                    openPanel(badPass ? "password" : "email", badPass ? "WRONG PASSWORD"
                            : w.toLowerCase(Locale.ROOT).contains("not found")
                            ? "NO STREMIO PASSWORD ACCOUNT FOR THAT EMAIL · GOOGLE/APPLE/FACEBOOK SIGN-IN? USE THE QR CODE INSTEAD" : w);
                }
                view.now();
            });
        });
    }
}
