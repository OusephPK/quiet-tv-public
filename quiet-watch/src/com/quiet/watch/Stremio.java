package com.quiet.watch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything Watch reads from Stremio: your account (sign-in, library, installed add-ons) from
 * Stremio's API, and catalogs, search and title details from the add-ons themselves, the same
 * way Stremio's own apps do. Your password is only ever sent to Stremio; Watch keeps the auth key.
 */
final class Stremio {
    static final String API = "https://api.strem.io/api/";
    static final String LINK = "https://link.stremio.com/";

    /** Stremio says the key is no longer valid (signed out elsewhere, expired). */
    static final class SignedOut extends IOException {
        SignedOut(String m) {
            super(m);
        }
    }

    static final class Video {
        String id, title, released;
        int season, episode;
    }

    /** A movie or series: what a catalog gives, filled in further by a title page. */
    static final class Meta {
        String id, type, name, poster = "", year = "", runtime = "", rating = "", genres = "", description = "",
                cast = "", director = "";
        List<Video> videos;            // series episodes, once the full details are in
        boolean full;
        // From your library, when it's there
        String videoId = "", lastWatched = "", added = "";
        long offset, duration;
        boolean inLibrary;
        JSONObject raw;                // the library item as Stremio keeps it, for changing it
        Video next;                    // when you've all but finished an episode: the one after it

        /** Nearly done with this episode? Then the next one is what's waiting. */
        boolean finishing() {
            return series() && duration > 0 && offset >= duration * 0.9f;
        }

        float progress() {
            return duration > 0 ? Math.min(1f, offset / (float) duration) : 0f;
        }

        boolean series() {
            return "series".equals(type);
        }

        /** "S2 E5" for the episode you're on, "" otherwise. */
        String episode() {
            if (videoId == null || !series()) return "";
            String[] p = videoId.split(":");
            return p.length >= 3 ? "S" + p[p.length - 2] + " E" + p[p.length - 1] : "";
        }

        /** For the band across a Continue Watching poster: "S2E5 23M", "1H42M LEFT", ">S2E6" (next up). */
        String badge() {
            if (next != null) return ">S" + next.season + "E" + next.episode;
            String t = "";
            if (duration > 0 && offset > 0) {
                long min = Math.max(1, (duration - offset) / 60000);
                t = min >= 60 ? min / 60 + "H" + (min % 60 > 0 ? min % 60 + "M" : "") : min + "M";
            }
            String ep = episode().replace(" ", "");
            if (!ep.isEmpty()) return t.isEmpty() ? ep : ep + " " + t;
            return t.isEmpty() ? "" : t + " LEFT";
        }

        String left() {
            if (duration <= 0 || offset <= 0) return "";
            long min = Math.max(1, (duration - offset) / 60000);
            return (min >= 60 ? min / 60 + "H " + min % 60 + "M" : min + " MIN") + " LEFT";
        }

        /** Stremio's own page for this title, at the sources for one episode (or the film). */
        String link(String video) {
            String v = video == null || video.isEmpty() ? (series() && !videoId.isEmpty() ? videoId : id) : video;
            return "stremio:///detail/" + type + "/" + id + "/" + v;
        }

        /** The short line under a title: year · genres, or where you are in it. */
        String line() {
            List<String> bits = new ArrayList<>();
            if (!year.isEmpty()) bits.add(year);
            if (!genres.isEmpty()) bits.add(genres);
            if (!rating.isEmpty()) bits.add("IMDB " + rating);
            return String.join(" · ", bits);
        }
    }

    static final class Catalog {
        Addon addon;
        String type, id, name;
        boolean search, board;

        String label() {
            String kind = "movie".equals(type) ? "MOVIES" : "series".equals(type) ? "SERIES" : type.toUpperCase(Locale.ROOT);
            String n = name == null || name.isEmpty() ? id : name;
            return (n + " " + kind + " · " + addon.name).toUpperCase(Locale.ROOT);
        }
    }

    static final class Addon {
        String base, name;
        final List<Catalog> catalogs = new ArrayList<>();
        final List<String> metaTypes = new ArrayList<>(), metaPrefixes = new ArrayList<>();
        boolean meta;

        boolean hasMeta(String type, String id) {
            if (!meta || (!metaTypes.isEmpty() && !metaTypes.contains(type))) return false;
            if (metaPrefixes.isEmpty()) return true;
            for (String p : metaPrefixes) if (id.startsWith(p)) return true;
            return false;
        }
    }

    // ---------------------------------------------------------------- http

    static String read(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        if (in != null) for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    static JSONObject call(String method, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + method).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject r = new JSONObject(read(c));
            JSONObject err = r.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "SOMETHING WENT WRONG");
                if (err.optInt("code") == 1) throw new SignedOut(m);
                throw new IOException(m);
            }
            return r;
        } catch (JSONException e) {
            throw new IOException("STREMIO SENT SOMETHING ODD");
        } finally {
            c.disconnect();
        }
    }

    static JSONObject get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        try {
            return new JSONObject(read(c));
        } catch (JSONException e) {
            throw new IOException("ODD REPLY");
        } finally {
            c.disconnect();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- signing in

    /** Returns the auth key. */
    static String login(String email, String password) throws IOException {
        try {
            JSONObject b = new JSONObject().put("type", "Login").put("email", email).put("password", password)
                    .put("facebook", false);
            JSONObject res = call("login", b).optJSONObject("result");
            String key = res == null ? "" : res.optString("authKey", "");
            if (key.isEmpty()) throw new IOException("NO KEY CAME BACK");
            return key;
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** A fresh four-letter code; you open link.stremio.com/CODE on your phone and sign in there. */
    static String linkCode() throws IOException {
        JSONObject r = get(LINK + "api/create?type=Create");
        String code = r.optString("code", "");
        if (code.isEmpty() && r.optJSONObject("result") != null) code = r.optJSONObject("result").optString("code", "");
        if (code.isEmpty()) throw new IOException("NO CODE");
        return code;
    }

    /** The auth key once the code has been used on a phone; null while it's still waiting. */
    static String linkedKey(String code) throws IOException {
        JSONObject r = get(LINK + "api/read?type=Read&code=" + code);
        JSONObject res = r.optJSONObject("result");
        String key = res == null ? "" : res.optString("authKey", "");
        if (key.isEmpty()) key = r.optString("authKey", "");
        return key.isEmpty() ? null : key;
    }

    // ---------------------------------------------------------------- your account

    /** Everything in your library (movies and series), newest activity first. */
    static List<Meta> library(String authKey) throws IOException {
        JSONArray all;
        try {
            JSONObject b = new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                    .put("ids", new JSONArray()).put("all", true);
            all = call("datastoreGet", b).optJSONArray("result");
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        List<Meta> out = new ArrayList<>();
        if (all == null) return out;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type");
            if (!"movie".equals(type) && !"series".equals(type)) continue;
            JSONObject st = o.optJSONObject("state");
            Meta m = new Meta();
            m.id = o.optString("_id");
            m.type = type;
            m.name = o.optString("name");
            m.poster = o.optString("poster", "");
            if (m.poster.isEmpty() && m.id.startsWith("tt"))
                m.poster = "https://images.metahub.space/poster/small/" + m.id + "/img";
            boolean removed = o.optBoolean("removed"), temp = o.optBoolean("temp");
            m.inLibrary = !removed && !temp;
            m.raw = o;
            if (st != null) {
                m.videoId = st.optString("video_id", "");
                m.offset = st.optLong("timeOffset");
                m.duration = st.optLong("duration");
                m.lastWatched = st.optString("lastWatched", "");
            }
            if (m.lastWatched.isEmpty()) m.lastWatched = o.optString("_mtime", "");
            m.added = o.optString("_ctime", "");
            // Keep it if it's in the library, or if it's something you're partway through.
            if (m.inLibrary || ((!removed || temp) && m.offset > 0)) out.add(m);
        }
        out.sort((a, b) -> b.lastWatched.compareTo(a.lastWatched));
        return out;
    }

    // ---------------------------------------------------------------- changing your library

    static String now() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    static void put(String authKey, JSONObject item) throws IOException {
        try {
            item.put("_mtime", now());
            JSONObject b = new JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                    .put("changes", new JSONArray().put(item));
            call("datastorePut", b);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Off Continue Watching: back to the start (as Stremio's own "dismiss" does). */
    static void dismiss(String authKey, Meta m) throws IOException {
        if (m.raw == null) return;
        try {
            JSONObject it = new JSONObject(m.raw.toString());
            JSONObject st = it.optJSONObject("state");
            if (st != null) st.put("timeOffset", 0);
            if (it.optBoolean("temp")) it.put("removed", true);
            put(authKey, it);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Into the library (or out of it). */
    static void library(String authKey, Meta m, boolean in) throws IOException {
        try {
            JSONObject it;
            if (m.raw != null) it = new JSONObject(m.raw.toString());
            else {
                it = new JSONObject().put("_id", m.id).put("name", m.name).put("type", m.type).put("poster", m.poster)
                        .put("posterShape", "poster").put("_ctime", now()).put("behaviorHints", new JSONObject())
                        .put("state", new JSONObject().put("lastWatched", JSONObject.NULL).put("timeWatched", 0)
                                .put("timeOffset", 0).put("overallTimeWatched", 0).put("timesWatched", 0)
                                .put("flaggedWatched", 0).put("duration", 0).put("video_id", JSONObject.NULL)
                                .put("watched", JSONObject.NULL).put("noNotif", false));
            }
            it.put("removed", !in).put("temp", false);
            put(authKey, it);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Stremio's own rule for Continue Watching: not removed (or only temporarily), and started. */
    static List<Meta> continueWatching(List<Meta> library) {
        List<Meta> out = new ArrayList<>();
        for (Meta m : library) if (m.offset > 0) out.add(m);
        return out.size() > 30 ? new ArrayList<>(out.subList(0, 30)) : out;
    }

    /** Your installed add-ons, in your order, with their catalogs. */
    static List<Addon> addons(String authKey) throws IOException {
        JSONArray list;
        try {
            JSONObject b = new JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true);
            JSONObject res = call("addonCollectionGet", b).optJSONObject("result");
            list = res == null ? null : res.optJSONArray("addons");
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        List<Addon> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            JSONObject man = a == null ? null : a.optJSONObject("manifest");
            if (man == null) continue;
            Addon ad = new Addon();
            ad.base = a.optString("transportUrl").replaceAll("/manifest\\.json$", "");
            if (!ad.base.startsWith("http")) continue;
            ad.name = man.optString("name", "ADD-ON");
            JSONArray addonTypes = man.optJSONArray("types"), addonPrefixes = man.optJSONArray("idPrefixes");
            JSONArray res = man.optJSONArray("resources");
            if (res != null)
                for (int r = 0; r < res.length(); r++) {
                    Object x = res.opt(r);
                    if ("meta".equals(x)) {
                        ad.meta = true;
                        addAll(ad.metaTypes, addonTypes);
                        addAll(ad.metaPrefixes, addonPrefixes);
                    } else if (x instanceof JSONObject && "meta".equals(((JSONObject) x).optString("name"))) {
                        ad.meta = true;
                        JSONObject o = (JSONObject) x;
                        addAll(ad.metaTypes, o.has("types") ? o.optJSONArray("types") : addonTypes);
                        addAll(ad.metaPrefixes, o.has("idPrefixes") ? o.optJSONArray("idPrefixes") : addonPrefixes);
                    }
                }
            JSONArray cats = man.optJSONArray("catalogs");
            if (cats != null)
                for (int c = 0; c < cats.length(); c++) {
                    JSONObject co = cats.optJSONObject(c);
                    if (co == null) continue;
                    Catalog cat = new Catalog();
                    cat.addon = ad;
                    cat.type = co.optString("type");
                    cat.id = co.optString("id");
                    cat.name = co.optString("name", "");
                    boolean required = false;
                    JSONArray extra = co.optJSONArray("extra");
                    if (extra != null)
                        for (int e = 0; e < extra.length(); e++) {
                            JSONObject ex = extra.optJSONObject(e);
                            if (ex == null) continue;
                            if ("search".equals(ex.optString("name"))) cat.search = true;
                            if (ex.optBoolean("isRequired")) required = true;
                        }
                    JSONArray req = co.optJSONArray("extraRequired"), sup = co.optJSONArray("extraSupported");
                    if (req != null && req.length() > 0) required = true;
                    if (sup != null) for (int e = 0; e < sup.length(); e++) if ("search".equals(sup.optString(e))) cat.search = true;
                    if (req != null) for (int e = 0; e < req.length(); e++) if ("search".equals(req.optString(e))) cat.search = true;
                    cat.board = !required && ("movie".equals(cat.type) || "series".equals(cat.type));
                    ad.catalogs.add(cat);
                }
            out.add(ad);
        }
        return out;
    }

    static void addAll(List<String> to, JSONArray a) {
        if (a != null) for (int i = 0; i < a.length(); i++) to.add(a.optString(i));
    }

    // ---------------------------------------------------------------- add-ons

    static List<Meta> catalog(Catalog c, String extra) throws IOException {
        String url = c.addon.base + "/catalog/" + c.type + "/" + enc(c.id) + (extra.isEmpty() ? "" : "/" + extra) + ".json";
        JSONArray metas = get(url).optJSONArray("metas");
        List<Meta> out = new ArrayList<>();
        if (metas == null) return out;
        for (int i = 0; i < metas.length() && out.size() < 40; i++) {
            JSONObject o = metas.optJSONObject(i);
            if (o == null || o.optString("id").isEmpty()) continue;
            Meta m = new Meta();
            fill(m, o, c.type);
            out.add(m);
        }
        return out;
    }

    /** Every add-on that can search, films and series taken in turns, closest names first. */
    static List<Meta> search(List<Addon> addons, String query) {
        List<List<Meta>> lists = new ArrayList<>();
        for (Addon a : addons)
            for (Catalog c : a.catalogs) {
                if (!c.search || !("movie".equals(c.type) || "series".equals(c.type))) continue;
                try {
                    lists.add(catalog(c, "search=" + enc(query)));
                } catch (IOException ignored) {
                }
            }
        List<Meta> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; out.size() < 30; i++) {
            boolean any = false;
            for (List<Meta> l : lists)
                if (i < l.size()) {
                    any = true;
                    if (seen.add(l.get(i).id) && out.size() < 30) out.add(l.get(i));
                }
            if (!any) break;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        out.sort((x, y) -> Integer.compare(match(x.name, q), match(y.name, q)));   // stable: keeps the turns
        return out;
    }

    static int match(String name, String q) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.equals(q)) return 0;
        if (n.startsWith(q)) return 1;
        if ((" " + n).contains(" " + q)) return 2;
        return 3;
    }

    /** The full details for a title, from the first add-on that describes that kind of id. */
    static void details(List<Addon> addons, Meta m) throws IOException {
        IOException last = null;
        for (Addon a : addons) {
            if (!a.hasMeta(m.type, m.id)) continue;
            try {
                JSONObject o = get(a.base + "/meta/" + m.type + "/" + enc(m.id) + ".json").optJSONObject("meta");
                if (o == null) continue;
                fill(m, o, m.type);
                JSONArray vs = o.optJSONArray("videos");
                List<Video> list = new ArrayList<>();
                if (vs != null)
                    for (int i = 0; i < vs.length(); i++) {
                        JSONObject v = vs.optJSONObject(i);
                        if (v == null) continue;
                        Video vid = new Video();
                        vid.id = v.optString("id");
                        vid.season = v.optInt("season");
                        vid.episode = v.optInt("episode", v.optInt("number"));
                        vid.title = v.optString("name", v.optString("title", ""));
                        String rel = v.optString("released", v.optString("firstAired", ""));
                        vid.released = rel.length() >= 10 ? rel.substring(0, 10) : rel;
                        list.add(vid);
                    }
                list.sort((x, y) -> x.season != y.season ? Integer.compare(x.season, y.season) : Integer.compare(x.episode, y.episode));
                m.videos = list;
                m.full = true;
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        m.full = true;                      // nothing describes it; show what we have
    }

    static void fill(Meta m, JSONObject o, String fallbackType) {
        m.id = o.optString("id", m.id);
        m.type = o.optString("type", m.type == null ? fallbackType : m.type);
        m.name = o.optString("name", m.name == null ? "" : m.name);
        String poster = o.optString("poster", "");
        if (!poster.isEmpty()) m.poster = poster;
        else if (m.poster.isEmpty() && m.id.startsWith("tt"))
            m.poster = "https://images.metahub.space/poster/small/" + m.id + "/img";
        String y = o.optString("releaseInfo", o.optString("year", ""));
        if (!y.isEmpty()) {
            y = y.replace("–", "-").trim();
            m.year = y.endsWith("-") ? "SINCE " + y.substring(0, y.length() - 1) : y;   // still running
        }
        if (o.has("runtime")) m.runtime = o.optString("runtime", m.runtime);
        if (o.has("imdbRating")) m.rating = o.optString("imdbRating", m.rating);
        JSONArray g = o.optJSONArray("genres");
        if (g == null) g = o.optJSONArray("genre");
        if (g != null && g.length() > 0) {
            List<String> gs = new ArrayList<>();
            for (int i = 0; i < Math.min(2, g.length()); i++) gs.add(g.optString(i));
            m.genres = String.join(", ", gs);
        }
        if (o.has("description")) m.description = o.optString("description", m.description);
        JSONArray cast = o.optJSONArray("cast");
        if (cast != null && cast.length() > 0) {
            List<String> cs = new ArrayList<>();
            for (int i = 0; i < Math.min(3, cast.length()); i++) cs.add(cast.optString(i));
            m.cast = String.join(", ", cs);
        }
        JSONArray dir = o.optJSONArray("director");
        if (dir != null && dir.length() > 0) m.director = dir.optString(0);
    }
}
