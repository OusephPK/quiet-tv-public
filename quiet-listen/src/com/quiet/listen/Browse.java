package com.quiet.listen;

import android.os.SystemClock;
import android.view.KeyEvent;

import com.quiet.px.Font;
import com.quiet.px.IconBar;
import com.quiet.px.Pal;
import com.quiet.px.Stage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Music app as pixel art. A rail of sections on the left; covers, album pages and search on
 * the right. Apple's player does the work underneath; this only ever draws into the well.
 */
final class Browse {

    static final int RAIL = 0, GRID = 1, DETAIL = 2, KEYS = 3, RESULTS = 4;
    static final String LISTEN = "LISTEN NOW", PLAYLISTS = "PLAYLISTS", ALBUMS = "ALBUMS", RADIO = "RADIO",
            SEARCH = "SEARCH", PLAYING = "NOW PLAYING", SIGNIN = "SIGN IN", TOGGLE = "PLAY/PAUSE", SLEEP = "SLEEP";

    // Layout, in art pixels.
    static final int RAIL_X = 24, CX = 100, COLS = 4, TILE = 40, PITCH_X = 52, PITCH_Y = 62, GRID_Y = 36;
    static final int LIST_X = 182, TRACK_Y = 74, TRACK_H = 8;
    static final int KEY_X = 100, KEY_Y = 38, KEY_W = 10, KEY_H = 10, KEY_COLS = 7;
    static final String KEYS_TEXT = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final int K_SPACE = 36, K_DEL = 37, K_CLEAR = 38, K_COUNT = 39;

    final Main m;
    final IconBar bar = new IconBar();
    boolean inBar;
    String current = LISTEN, loaded = "";
    int area = RAIL;
    int token;

    final List<Item> grid = new ArrayList<>();
    int gf, gRow;
    String gridSub = "", status = "";

    Item detail;
    final List<Item> tracks = new ArrayList<>();
    int tf = -2, tTop, detailFrom = GRID;
    String detailStatus = "";

    String query = "", searched = "", searchStatus = "";
    long typedAt;
    int kf;
    final List<Item> results = new ArrayList<>();
    // Search suggestions: before you type, your recent searches and what's trending; as you type,
    // Apple's own completions (picking one searches it). The full results follow underneath.
    final List<Item> hints = new ArrayList<>(), trending = new ArrayList<>();
    String hinted = "";
    boolean trendAsked;

    /** What the list beside the keyboard holds right now. */
    List<Item> list() {
        List<Item> out = new ArrayList<>();
        if (query.trim().isEmpty()) {
            for (String r : recentSearches()) out.add(Item.term(r, "RECENT SEARCH"));
            out.addAll(trending);
        } else {
            out.addAll(hints);
            out.addAll(results);
        }
        return out;
    }

    List<String> recentSearches() {
        String raw = m.getSharedPreferences("listen", android.content.Context.MODE_PRIVATE).getString("recentSearches", "");
        List<String> out = new ArrayList<>();
        for (String r : raw.split("\n")) if (!r.trim().isEmpty()) out.add(r);
        return out;
    }

    void rememberSearch(String q) {
        q = q.trim();
        if (q.length() < 2) return;
        List<String> r = recentSearches();
        r.remove(q);
        r.add(0, q);
        while (r.size() > 5) r.remove(r.size() - 1);
        m.getSharedPreferences("listen", android.content.Context.MODE_PRIVATE).edit().putString("recentSearches", String.join("\n", r)).apply();
    }

    void hint() {
        hinted = query;
        String q = query.trim();
        m.api.get("/v1/catalog/" + m.storefront + "/search/hints", Api.params("term", q, "limit", 5), r -> {
            if (!hinted.equals(query)) return;
            hints.clear();
            JSONObject res = r.optJSONObject("results");
            org.json.JSONArray terms = res == null ? null : res.optJSONArray("terms");
            for (int i = 0; terms != null && i < terms.length() && hints.size() < 4; i++) {
                String t = terms.optString(i);
                if (!t.isEmpty() && !t.equalsIgnoreCase(q)) hints.add(Item.term(t, "SUGGESTION"));
            }
        });
    }

    void trend() {
        trendAsked = true;
        m.api.get("/v1/catalog/" + m.storefront + "/charts", Api.params("types", "songs", "limit", 6), r -> {
            JSONObject res = r.optJSONObject("results");
            org.json.JSONArray songs = res == null ? null : res.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return;
            trending.clear();
            for (Item it : Api.items(songs.optJSONObject(0).optJSONArray("data"))) {
                it.sub = "TRENDING · " + it.sub;
                trending.add(it);
            }
        });
    }
    int rf, rTop;

    long loadAt;

    Browse(Main m) {
        this.m = m;
    }

    List<String> sections() {
        List<String> s = new ArrayList<>();
        s.add(LISTEN);
        s.add(PLAYLISTS);
        s.add(ALBUMS);
        s.add(RADIO);
        s.add(SEARCH);
        return s;
    }

    /** Now Playing and the account sit in the system bar, top-right, as in every Quiet app. */
    void refreshBar() {
        List<IconBar.Icon> icons = new ArrayList<>();
        if (!m.title.isEmpty()) {
            icons.add(new IconBar.Icon(m.playing ? IconBar.PAUSE : IconBar.PLAY, m.playing ? "PAUSE" : "PLAY", TOGGLE));
            IconBar.Icon np = new IconBar.Icon(IconBar.PLAYING, m.playing ? "NOW PLAYING" : "PAUSED", PLAYING);
            np.lit = m.playing;
            icons.add(np);
            IconBar.Icon moon = new IconBar.Icon(IconBar.MOON, m.sleepLabel(), SLEEP);
            moon.lit = m.sleep != 0;
            icons.add(moon);
        }
        IconBar.Icon user = new IconBar.Icon(IconBar.USER, m.authorized ? "SIGNED IN" : "SIGN IN", SIGNIN);
        user.lit = !m.authorized && m.stateKnown;
        icons.add(user);
        if (icons.size() != bar.icons.size() || !labels(icons).equals(labels(bar.icons))) bar.set(icons);
    }

    static String labels(List<IconBar.Icon> icons) {
        StringBuilder b = new StringBuilder();
        for (IconBar.Icon i : icons) b.append(i.label).append(i.lit).append('|');
        return b.toString();
    }

    /** Called when sign-in state or storefront changes. */
    void refresh() {
        loaded = "";
        loadAt = SystemClock.uptimeMillis();
    }

    // ---------------------------------------------------------------- loading

    void load(String name) {
        loaded = name;
        int t = ++token;
        grid.clear();
        gf = 0;
        gRow = 0;
        status = "LOADING";
        String sf = m.storefront;
        switch (name) {
            case LISTEN:
                if (m.authorized) {
                    gridSub = "RECENTLY PLAYED";
                    m.api.get("/v1/me/recent/played", Api.params("limit", 10), ((Api.Done) r -> {
                        if (t != token) return;
                        add(Api.items(r));
                        remember("recent", r);
                        m.api.get("/v1/me/history/heavy-rotation", Api.params("limit", 10), r2 -> {
                            if (t == token) add(Api.items(r2));
                            remember("heavy", r2);
                        });
                    }).withFail(why -> failed(t, why)));
                } else {
                    gridSub = "TOP CHARTS · SIGN IN FOR YOUR OWN";
                    m.api.get("/v1/catalog/" + sf + "/charts", Api.params("types", "albums,playlists", "limit", 12),
                            ((Api.Done) r -> {
                                if (t != token) return;
                                JSONObject res = r.optJSONObject("results");
                                if (res != null) {
                                    add(charts(res.optJSONArray("playlists")));
                                    add(charts(res.optJSONArray("albums")));
                                }
                            }).withFail(why -> failed(t, why)));
                }
                break;
            case PLAYLISTS:
            case ALBUMS:
                gridSub = name.equals(PLAYLISTS) ? "YOUR PLAYLISTS" : "YOUR ALBUMS";
                if (!m.authorized) {
                    status = "SIGN IN FROM THE BAR ABOVE TO SEE YOUR LIBRARY";
                    break;
                }
                // What it held last time shows at once; the fresh list replaces it when it arrives.
                android.content.SharedPreferences sp = m.getSharedPreferences("listen", android.content.Context.MODE_PRIVATE);
                String key = "lib." + name;
                try {
                    if (sp.contains(key)) add(Api.items(new JSONObject(sp.getString(key, "{}"))));
                } catch (Exception ignored) {
                }
                m.api.get("/v1/me/library/" + (name.equals(PLAYLISTS) ? "playlists" : "albums"), Api.params("limit", 60),
                        ((Api.Done) r -> {
                            if (t != token) return;
                            sp.edit().putString(key, r.toString()).apply();
                            Object keep = gf < grid.size() ? grid.get(gf).rid : null;
                            grid.clear();
                            add(Api.items(r));
                            for (int i = 0; i < grid.size(); i++) if (grid.get(i).rid.equals(keep)) gf = i;   // stay on the same cover
                            if (gf >= grid.size()) gf = Math.max(0, grid.size() - 1);
                        }).withFail(why -> failed(t, why)));
                break;
            case RADIO:
                gridSub = "STATIONS";
                if (m.authorized) {
                    m.api.get("/v1/catalog/" + sf + "/stations", Api.params("filter[identity]", "personal"), r -> {
                        if (t == token) add(Api.items(r));
                    });
                }
                m.api.get("/v1/catalog/" + sf + "/stations", Api.params("filter[featured]", "apple-music-live-radio"),
                        ((Api.Done) r -> {
                            if (t == token) add(Api.items(r));
                        }).withFail(why -> failed(t, why)));
                break;
            default:
                status = "";
        }
    }

    static List<Item> charts(JSONArray groups) {
        List<Item> out = new ArrayList<>();
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null) out.addAll(Api.items(g.optJSONArray("data")));
        }
        return out;
    }

    void add(List<Item> items) {
        Set<String> seen = new HashSet<>();
        for (Item g : grid) seen.add(g.rid);
        for (Item it : items) if (seen.add(it.rid)) grid.add(it);
        status = grid.isEmpty() ? "NOTHING HERE YET" : "";
    }

    void failed(int t, String why) {
        if (t != token) return;
        status = grid.isEmpty() ? "COULDN'T LOAD · " + why : "";
    }

    void openDetail(Item it, int from) {
        detail = it;
        detailFrom = from;
        tracks.clear();
        tf = -2;
        tTop = 0;
        detailStatus = "LOADING";
        area = DETAIL;
        int t = ++token;
        m.api.get(it.tracksPath(m.storefront), Api.params("limit", 100), ((Api.Done) r -> {
            if (t != token) return;
            tracks.addAll(Api.items(r));
            detailStatus = tracks.isEmpty() ? "NO SONGS" : "";
        }).withFail(why -> {
            if (t == token) detailStatus = "COULDN'T LOAD · " + why;
        }));
    }

    void search() {
        searched = query;
        results.clear();
        rf = 0;
        rTop = 0;
        String reply = m.stage.answer(query);             // a few words the well answers to
        if (reply != null) {
            searchStatus = reply;
            return;
        }
        searchStatus = "SEARCHING";
        int t = ++token;
        m.api.get("/v1/catalog/" + m.storefront + "/search",
                Api.params("term", query, "types", "artists,songs,albums,playlists", "limit", 5), ((Api.Done) r -> {
                    if (t != token) return;
                    JSONObject res = r.optJSONObject("results");
                    if (res != null) {
                        for (String type : new String[]{"artists", "songs", "albums", "playlists"}) {
                            JSONObject g = res.optJSONObject(type);
                            List<Item> got = g == null ? new ArrayList<>() : Api.items(g.optJSONArray("data"));
                            results.addAll(type.equals("artists") && got.size() > 2 ? got.subList(0, 2) : got);
                        }
                    }
                    searchStatus = results.isEmpty() ? "NO RESULTS" : "";
                    if (!results.isEmpty()) rememberSearch(searched);
                }).withFail(why -> {
                    if (t == token) searchStatus = "COULDN'T SEARCH · " + why;
                }));
    }

    // ---------------------------------------------------------------- playing

    void open(Item it, int from) {
        if ("term".equals(it.kind)) {                     // a suggestion: search it
            query = it.title;
            hinted = query;
            hints.clear();
            rf = 0;
            search();
            return;
        }
        if (it.container()) openDetail(it, from);
        else if (it.playable()) m.play(it.kind, it.playId, 0, false);
    }

    void playDetail(int index, boolean shuffle) {
        if (detail == null) return;
        if ("artist".equals(detail.kind)) {
            StringBuilder ids = new StringBuilder();
            for (Item t : tracks) ids.append(ids.length() == 0 ? "" : ",").append(t.playId);
            if (ids.length() > 0) m.play("songs", ids.toString(), index, shuffle);
        } else {
            m.play(detail.kind, detail.playId, index, shuffle);
        }
    }

    // ---------------------------------------------------------------- keys

    boolean key(int k) {
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = k == KeyEvent.KEYCODE_BACK;
        List<String> secs = sections();
        int si = Math.max(0, secs.indexOf(current));
        if (inBar) {
            if (down || back) inBar = false;
            else if (left || right) bar.move(left ? -1 : 1);
            else if (ok && bar.focused() != null) {
                Object tag = bar.focused().tag;
                if (TOGGLE.equals(tag)) {
                    m.control("toggle");                  // stay in the bar: press again to undo
                    return true;
                }
                if (SLEEP.equals(tag)) {
                    m.cycleSleep();                       // stay in the bar: press again for the next setting
                    return true;
                }
                inBar = false;
                if (PLAYING.equals(tag)) m.showNowPlaying(true);
                else if (SIGNIN.equals(tag) && !m.authorized) m.signIn();
            }
            return true;
        }
        if (up && ((area == RAIL && si == 0) || (area == GRID && gf < COLS))) {
            inBar = true;                              // past the top: the system bar
            return true;
        }
        switch (area) {
            case RAIL:
                if (up || down) {
                    int n = Math.max(0, Math.min(secs.size() - 1, si + (up ? -1 : 1)));
                    if (n != si) {
                        current = secs.get(n);
                        loadAt = SystemClock.uptimeMillis();   // load once the rail settles
                    }
                } else if (right || ok) {
                    enter();
                } else if (back) {
                    m.leave();
                }
                return true;
            case GRID:
                if (back) area = RAIL;
                else if (left && gf % COLS == 0) area = RAIL;
                else if (left) gf--;
                else if (right && gf + 1 < grid.size() && (gf + 1) % COLS != 0) gf++;
                else if (up && gf >= COLS) gf -= COLS;
                else if (down && gf + COLS < grid.size()) gf += COLS;
                else if (down && gf / COLS < (grid.size() - 1) / COLS) gf = grid.size() - 1;
                else if (ok && gf < grid.size()) open(grid.get(gf), GRID);
                return true;
            case DETAIL:
                if (back) {
                    area = detailFrom;
                } else if (left && tf == -1) {
                    tf = -2;
                } else if (right && tf == -2) {
                    tf = -1;
                } else if (left && tf <= -1) {
                    area = detailFrom == GRID ? GRID : detailFrom;
                } else if (up) {
                    tf = tf <= 0 ? -2 : tf - 1;
                } else if (down) {
                    if (tf < 0 && !tracks.isEmpty()) tf = 0;
                    else if (tf + 1 < tracks.size()) tf++;
                } else if (ok) {
                    if (tf == -2) playDetail(0, false);
                    else if (tf == -1) playDetail(0, true);
                    else playDetail(tf, false);
                }
                return true;
            case KEYS: {
                int col = kf % KEY_COLS;
                if (back) area = RAIL;
                else if (left && col == 0) area = RAIL;
                else if (left) kf--;
                else if (right && col == KEY_COLS - 1 || right && kf == K_COUNT - 1) {
                    if (!list().isEmpty()) area = RESULTS;
                } else if (right) kf = Math.min(K_COUNT - 1, kf + 1);
                else if (up && kf >= KEY_COLS) kf -= KEY_COLS;
                else if (down && kf + KEY_COLS < K_COUNT) kf += KEY_COLS;
                else if (ok) type(kf);
                return true;
            }
            case RESULTS:
                if (back || left) area = KEYS;
                else if (up && rf > 0) rf--;
                else if (down && rf + 1 < list().size()) rf++;
                else if (ok && rf < list().size()) open(list().get(rf), RESULTS);
                return true;
        }
        return false;
    }

    void enter() {
        switch (current) {
            case SEARCH:
                area = KEYS;
                break;
            case PLAYING:
                m.showNowPlaying(true);
                break;
            case SIGNIN:
                m.signIn();
                break;
            default:
                // Step in even while it's loading; the selection lands on the first cover when it arrives.
                if (!grid.isEmpty() || status.startsWith("LOADING") || status.startsWith("WAKING")) area = GRID;
        }
    }

    /** Words from the voice or the phone: the search screen, with them in it (searched a moment later). */
    void typedIn(String words) {
        current = SEARCH;
        inBar = false;
        area = KEYS;
        if (words.isEmpty()) {                            // "clear the search"
            type(K_CLEAR);
            return;
        }
        query = searchable(words);
        searched = "";
        typedAt = 0;
    }

    /** What our letters can show, as the keyboard would type it. */
    static String searchable(String words) {
        String q = com.quiet.px.Font.upper(words).replaceAll("[^A-Z0-9 .,'&:!?-]", "").replaceAll("\\s+", " ").trim();
        return q.length() > 40 ? q.substring(0, 40).trim() : q;
    }

    void type(int key) {
        if (key == K_SPACE) {
            if (!query.isEmpty() && !query.endsWith(" ")) query += " ";
        } else if (key == K_DEL) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
        } else if (key == K_CLEAR) {
            query = "";
            results.clear();
            searched = "";
            searchStatus = "";
        } else if (query.length() < 32) {
            query += KEYS_TEXT.charAt(key);
        }
        typedAt = SystemClock.uptimeMillis();
    }

    // ---------------------------------------------------------------- drawing

    /** Lights for the selected thing; runs before the stone is lit. */
    void lights(Stage s) {
        if (inBar) {
            bar.lights(s);
            return;
        }
        float[] p = focusPoint();
        s.light(p[0], p[1], 40, 0.3f, true);
    }

    /** Last session's Listen Now, kept so the first screen is there straight away next time. */
    void remember(String key, JSONObject r) {
        m.getSharedPreferences("listen", android.content.Context.MODE_PRIVATE).edit().putString(key, r.toString()).apply();
    }

    boolean showedCache;

    void tick() {
        long now = SystemClock.uptimeMillis();
        if (!m.stateKnown) {                     // Apple's player is still waking: show last time's Listen Now
            if (!showedCache && current.equals(LISTEN)) {
                showedCache = true;
                android.content.SharedPreferences p = m.getSharedPreferences("listen", android.content.Context.MODE_PRIVATE);
                try {
                    if (p.contains("recent")) add(Api.items(new JSONObject(p.getString("recent", "{}"))));
                    if (p.contains("heavy")) add(Api.items(new JSONObject(p.getString("heavy", "{}"))));
                } catch (Exception ignored) {
                }
                if (!grid.isEmpty()) gridSub = "RECENTLY PLAYED";
            }
            status = grid.isEmpty() ? "WAKING THE PLAYER" : "";
            return;
        }
        if (!current.equals(loaded) && now - loadAt > 250
                && !current.equals(SEARCH) && !current.equals(PLAYING) && !current.equals(SIGNIN)) load(current);
        if (current.equals(SEARCH) && !query.trim().isEmpty() && !query.equals(hinted) && now - typedAt > 220) hint();
        if (current.equals(SEARCH) && query.trim().isEmpty() && !hints.isEmpty()) hints.clear();
        if (current.equals(SEARCH) && !trendAsked && m.stateKnown) trend();
        if (current.equals(SEARCH) && query.trim().length() >= 2 && !query.equals(searched) && now - typedAt > 700) search();
        if (!sections().contains(current)) current = LISTEN;
    }

    void draw(Stage s) {
        tick();
        refreshBar();
        rail(s);
        switch (area == RAIL ? areaFor(current) : area) {
            case GRID: grid(s); break;
            case DETAIL: detail(s); break;
            case KEYS:
            case RESULTS: searchScreen(s); break;
            default: message(s); break;
        }
        bar.draw(s, inBar);
        float[] p = inBar ? new float[]{bar.perchX(), bar.perchY()} : focusPoint();
        s.mote.perch(p[0], p[1]);
    }

    int areaFor(String section) {
        switch (section) {
            case SEARCH: return KEYS;
            case PLAYING:
            case SIGNIN: return -1;
            default: return GRID;
        }
    }

    void rail(Stage s) {
        s.text("LISTEN", RAIL_X - 10, 12, 2, Pal.TEXT);
        List<String> secs = sections();
        for (int i = 0; i < secs.size(); i++) {
            String name = secs.get(i);
            int y = 42 + i * 11;
            boolean here = name.equals(current);
            int c = area == RAIL && here && !inBar ? Pal.TEXT : here ? Pal.AMBER : Pal.DIM;
            s.small(name, RAIL_X, y, 2, c);
            if (here && area != RAIL) s.rect(RAIL_X - 5, y + 2, 2, 1, Pal.AMBER);
        }
        if (!m.title.isEmpty()) {
            // up off the bank: Pip has it, under the rail
            s.small("♪", RAIL_X - 4, 112, 1, m.playing ? Pal.MINT : Pal.FAINT);
            s.small(fit(Font.upper(m.title), 62), RAIL_X + 2, 112, 1, m.playing ? Pal.SOFT : Pal.DIM);
            s.small(fit(Font.upper(m.artist), 62), RAIL_X + 2, 119, 1, Pal.DIM);
        }
    }

    void header(Stage s, String title, String sub) {
        String t = Main.fit(title, 160);
        s.text(t, CX, 12, 1, Pal.TEXT);
        if (!sub.isEmpty()) s.small(fit(sub, 160), CX, 23, 1, Pal.DIM);
    }

    void grid(Stage s) {
        header(s, current, gridSub);
        if (!status.isEmpty()) {
            s.small(status + dots(s), CX, GRID_Y + 4, 1, Pal.DIM);
            return;
        }
        int row = gf / COLS;
        if (row < gRow) gRow = row;
        if (row > gRow + 1) gRow = row - 1;
        for (int i = gRow * COLS; i < Math.min(grid.size(), (gRow + 2) * COLS); i++) {
            int x = CX + (i % COLS) * PITCH_X, y = GRID_Y + (i / COLS - gRow) * PITCH_Y;
            tile(s, grid.get(i), x, y, area == GRID && i == gf && !inBar);
        }
        if (gRow > 0) s.small("▲", CX + COLS * PITCH_X - 8, GRID_Y - 8, 1, Pal.FAINT);
        if ((gRow + 2) * COLS < grid.size()) s.small("▼", CX + COLS * PITCH_X - 8, GRID_Y + 2 * PITCH_Y - 4, 1, Pal.FAINT);
    }

    void tile(Stage s, Item it, int x, int y, boolean f) {
        s.scrim(x - 2, y + TILE + 2, PITCH_X - 2, 16);
        int lift = f ? 2 : 0;
        y -= lift;
        s.rect(x - 1, y - 1, TILE + 2, TILE + 2, f ? Pal.AMBER : Pal.COOL[2]);
        cover(s, it, x, y, TILE);
        s.small(fit(Font.upper(it.title), PITCH_X - 4), x, y + TILE + 3 + lift, 1, f ? Pal.TEXT : Pal.SOFT);
        s.small(fit(Font.upper(it.label()), PITCH_X - 4), x, y + TILE + 10 + lift, 1, Pal.DIM);
    }

    void cover(Stage s, Item it, int x, int y, int size) {
        android.graphics.Bitmap fine = m.covers.get(it, size);
        if (fine != null) {
            s.fine(fine, x, y, size, size, 0f);
            return;
        }
        int[] px = Covers.placeholder(it.title, size);
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) s.px(x + i, y + j, px[j * size + i]);
    }

    void detail(Stage s) {
        if (detail == null) return;
        int cx = CX, cy = 30;
        s.scrim(LIST_X - 14, 26, 320 - (LIST_X - 14), 138);
        s.rect(cx - 1, cy - 1, 66, 66, Pal.COOL[3]);
        cover(s, detail, cx, cy, 64);
        String dt = Main.fit(Font.upper(detail.title), 134);   // a long title ends in an ellipsis, not mid-word
        s.text(dt, LIST_X, 30, 1, Pal.TEXT);
        s.small(fit(Font.upper(detail.sub), 134), LIST_X, 41, 1, Pal.SOFT);
        String meta = !detailStatus.isEmpty() ? detailStatus + dots(s)
                : tracks.size() + (tracks.size() == 1 ? " SONG" : " SONGS") + " · " + minutes();
        s.small(meta, LIST_X, 49, 1, Pal.DIM);
        button(s, "PLAY", LIST_X, 59, tf == -2);
        button(s, "SHUFFLE", LIST_X + 32, 59, tf == -1);

        int rows = (160 - TRACK_Y) / TRACK_H;
        if (tf >= 0) {
            if (tf < tTop) tTop = tf;
            if (tf >= tTop + rows) tTop = tf - rows + 1;
        }
        for (int i = tTop; i < Math.min(tracks.size(), tTop + rows); i++) {
            Item t = tracks.get(i);
            int y = TRACK_Y + (i - tTop) * TRACK_H;
            boolean f = i == tf;
            boolean now = !m.title.isEmpty() && t.title.equalsIgnoreCase(m.title);
            String n = String.valueOf(i + 1);
            s.small(n, LIST_X + 8 - Font.microWidth(n, 1), y, 1, now ? Pal.MINT : Pal.DIM);
            s.small(fit(Font.upper(t.title), 100), LIST_X + 12, y, 1, f ? Pal.TEXT : now ? Pal.MINT : Pal.SOFT);
            String d = Main.time(t.ms / 1000f);
            if (t.ms > 0) s.small(d, 314 - Font.microWidth(d, 1), y, 1, Pal.DIM);
        }
        if (tTop > 0) s.small("▲", 308, TRACK_Y - 8, 1, Pal.FAINT);
        if (tTop + rows < tracks.size()) s.small("▼", 308, 160, 1, Pal.FAINT);
    }

    String minutes() {
        long ms = 0;
        for (Item t : tracks) ms += t.ms;
        return ms == 0 ? "" : (ms / 60000) + " MIN";
    }

    void button(Stage s, String label, int x, int y, boolean f) {
        int w = Font.microWidth(label, 1) + 7;
        s.rect(x, y, w, 9, f ? Pal.AMBER : Pal.FAINT);
        s.rect(x + 1, y + 1, w - 2, 7, f ? Pal.AMBER : Pal.VOID);
        s.small(label, x + 4, y + 2, 1, f ? Pal.VOID : Pal.SOFT);
    }

    void searchScreen(Stage s) {
        s.scrim(LIST_X - 14, 32, 320 - (LIST_X - 14), 132);
        header(s, SEARCH, "");
        boolean caret = ((int) (s.t * 2) & 1) == 0 && area == KEYS;
        int end = s.small(fit(query, 70), CX, 24, 1, Pal.TEXT);
        if (caret) s.rect(end + 1, 24, 1, 5, Pal.AMBER);
        s.rect(CX, 31, KEY_COLS * KEY_W - 2, 1, Pal.FAINT);
        for (int k = 0; k < K_COUNT; k++) {
            int x = KEY_X + (k % KEY_COLS) * KEY_W, y = KEY_Y + (k / KEY_COLS) * KEY_H;
            boolean f = area == KEYS && k == kf;
            if (f) {
                s.rect(x - 1, y - 1, KEY_W, KEY_H, Pal.AMBER);
                s.rect(x, y, KEY_W - 2, KEY_H - 2, Pal.VOID);
            }
            String label = k == K_SPACE ? "_" : k == K_DEL ? "<" : k == K_CLEAR ? "X" : String.valueOf(KEYS_TEXT.charAt(k));
            s.small(label, x + 2, y + 2, 1, f ? Pal.AMBER : k >= K_SPACE ? Pal.DIM : Pal.SOFT);
        }
        s.small("_ SPACE  < DELETE", KEY_X, KEY_Y + 6 * KEY_H + 3, 1, Pal.FAINT);
        s.small("X CLEAR", KEY_X, KEY_Y + 6 * KEY_H + 10, 1, Pal.FAINT);

        List<Item> list = list();
        if (list.isEmpty()) {
            String msg = !searchStatus.isEmpty() ? searchStatus + dots(s) : query.trim().isEmpty() ? "TYPE TO SEARCH" : "";
            s.small(msg, LIST_X, 38, 1, searchStatus.isEmpty() ? Pal.DIM : Pal.DIM);
            return;
        }
        if (rf >= list.size()) rf = list.size() - 1;
        int rows = (160 - 36) / 15;
        if (rf < rTop) rTop = rf;
        if (rf >= rTop + rows) rTop = rf - rows + 1;
        for (int i = rTop; i < Math.min(list.size(), rTop + rows); i++) {
            Item it = list.get(i);
            int y = 36 + (i - rTop) * 15;
            boolean f = area == RESULTS && i == rf;
            if ("term".equals(it.kind)) {                     // a suggestion: just the words
                if (f) s.rect(LIST_X - 1, y - 1, 3, 14, Pal.AMBER);
                s.small(">", LIST_X + 4, y + 4, 1, f ? Pal.AMBER : Pal.FAINT);
                s.small(fit(Font.upper(it.title), 116), LIST_X + 16, y + 1, 1, f ? Pal.TEXT : Pal.SOFT);
                s.small(it.sub, LIST_X + 16, y + 8, 1, Pal.DIM);
                continue;
            }
            if (f) s.rect(LIST_X - 1, y - 1, 14, 14, Pal.AMBER);
            cover(s, it, LIST_X, y, 12);
            s.small(fit(Font.upper(it.title), 116), LIST_X + 16, y + 1, 1, f ? Pal.TEXT : Pal.SOFT);
            String kind = it.kind.equals("song") ? "SONG" : it.kind.equals("album") ? "ALBUM"
                    : it.kind.equals("playlist") ? "PLAYLIST" : it.kind.equals("artist") ? "ARTIST" : Font.upper(it.kind);
            String sub = it.kind.equals("artist") ? kind : it.sub.startsWith("TRENDING") ? Font.upper(it.sub) : kind + " · " + Font.upper(it.sub);
            s.small(fit(sub, 116), LIST_X + 16, y + 8, 1, Pal.DIM);
        }
    }

    void message(Stage s) {
        if (current.equals(PLAYING)) {
            header(s, PLAYING, "");
            s.small(fit(Font.upper(m.title), 200), CX, 30, 1, Pal.TEXT);
            s.small(fit(Font.upper(m.artist), 200), CX, 38, 1, Pal.SOFT);
            s.small("OK TO OPEN", CX, 52, 2, Pal.FAINT);
        } else if (current.equals(SIGNIN)) {
            s.scrim(CX - 4, 32, 222, 42);
            header(s, "SIGN IN", "WITH YOUR APPLE ACCOUNT");
            s.small("YOUR PLAYLISTS, ALBUMS AND FULL SONGS", CX, 36, 1, Pal.SOFT);
            s.small("NEED YOUR APPLE MUSIC SUBSCRIPTION.", CX, 44, 1, Pal.SOFT);
            s.small("OK OPENS APPLE'S OWN SIGN-IN PAGE.", CX, 58, 1, Pal.DIM);
            s.small("YOU TYPE YOUR DETAILS THERE. THIS APP NEVER SEES THEM.", CX, 66, 1, Pal.DIM);
        }
    }

    /** Where Mote should hover for whatever is selected. */
    float[] focusPoint() {
        List<String> secs = sections();
        switch (area) {
            case GRID: {
                if (grid.isEmpty()) break;
                int x = CX + (gf % COLS) * PITCH_X, y = GRID_Y + (gf / COLS - gRow) * PITCH_Y;
                return new float[]{x - 6, y + 10};             // beside the cover, as in Watch
            }
            case DETAIL:
                if (tf == -2) return new float[]{LIST_X - 9, 63};
                if (tf == -1) return new float[]{LIST_X + 24, 54};
                return new float[]{LIST_X - 9, TRACK_Y + (tf - tTop) * TRACK_H + 2};
            case KEYS: {
                int x = KEY_X + (kf % KEY_COLS) * KEY_W, y = KEY_Y + (kf / KEY_COLS) * KEY_H;
                return new float[]{x + 4, y - 6};
            }
            case RESULTS:
                return new float[]{LIST_X - 9, 36 + (rf - rTop) * 15 + 5};
        }
        int i = Math.max(0, secs.indexOf(current));
        return new float[]{RAIL_X - 12, 42 + i * 11 + 2};
    }

    static String dots(Stage s) {
        int n = ((int) (s.t * 3)) % 4;
        return n == 0 ? "" : n == 1 ? "." : n == 2 ? ".." : "...";
    }

    static String fit(String s, int w) {
        if (Font.microWidth(s, 1) <= w) return s;
        while (s.length() > 1 && Font.microWidth(s + "..", 1) > w) s = s.substring(0, s.length() - 1);
        return s.trim() + "..";
    }
}
