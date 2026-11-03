package com.quiet.watch;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.quiet.px.Fine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, drawn fine (see Fine) so a film is recognisable. Fetched in the background, cached by size.
 *
 * Most come from metahub, which keys them by the TMDB id Cinemeta has on file, and now and then
 * that id is wrong (Band of Brothers gets a film's poster at every size). So any title can switch to
 * IMDb's own poster instead (hold OK → TRY ANOTHER, remembered), and a poster that won't load at
 * all falls back to IMDb's by itself.
 */
final class Posters {
    final Map<String, Bitmap> cache = Collections.synchronizedMap(new java.util.LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 160;
        }
    });
    final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    final Set<String> imdb = Collections.synchronizedSet(new HashSet<>());       // titles you switched
    final Set<String> broken = Collections.synchronizedSet(new HashSet<>());     // first source failed
    final Map<String, String> imdbUrl = Collections.synchronizedMap(new java.util.HashMap<>());
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    SharedPreferences prefs;
    Runnable arrived;

    void remember(SharedPreferences p) {
        prefs = p;
        imdb.addAll(p.getStringSet("imdbPosters", new HashSet<>()));
    }

    boolean alternate(String id) {
        return imdb.contains(id);
    }

    /** Switch this title between its first poster and IMDb's. */
    void swap(String id) {
        if (!imdb.remove(id)) imdb.add(id);
        if (prefs != null) prefs.edit().putStringSet("imdbPosters", new HashSet<>(imdb)).apply();
        if (arrived != null) arrived.run();
    }

    Bitmap get(Stremio.Meta m, int w, int h) {
        boolean useImdb = m.id.startsWith("tt") && (imdb.contains(m.id) || broken.contains(m.id) || m.poster.isEmpty());
        String src = useImdb ? "imdb:" + m.id : m.poster;
        if (src.isEmpty()) return null;
        String key = w + "x" + h + "|" + src;
        Bitmap px = cache.get(key);
        if (px != null || !loading.add(key)) return px;
        pool.submit(() -> {
            try {
                String url = useImdb ? imdbPoster(m.id) : m.poster;
                Bitmap b = url == null ? null : fetch(url);
                if (b != null) {
                    cache.put(key, Fine.make(b, w, h));
                    if (arrived != null) arrived.run();
                } else if (!useImdb && m.id.startsWith("tt") && broken.add(m.id) && arrived != null) {
                    arrived.run();                              // ask again, from IMDb this time
                }
            } catch (Exception e) {
                android.util.Log.w("QuietWatch", "poster failed: " + e);
                if (!useImdb && m.id.startsWith("tt") && broken.add(m.id) && arrived != null) arrived.run();
            } finally {
                loading.remove(key);
            }
        });
        return null;
    }

    static Bitmap fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        }
    }

    /** IMDb's poster for a title, from the same lookup its search box uses, at a sensible size. */
    String imdbPoster(String id) throws Exception {
        String u = imdbUrl.get(id);
        if (u != null) return u;
        HttpURLConnection c = (HttpURLConnection) new URL("https://v3.sg.media-imdb.com/suggestion/t/" + id + ".json").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        JSONArray d = new JSONObject(Stremio.read(c)).optJSONArray("d");
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id"))) continue;
            JSONObject img = o.optJSONObject("i");
            String raw = img == null ? "" : img.optString("imageUrl", "");
            if (raw.isEmpty()) return null;
            u = raw.replace("._V1_.jpg", "._V1_UX300_.jpg");      // Amazon's image server resizes on request
            imdbUrl.put(id, u);
            return u;
        }
        return null;
    }
}
