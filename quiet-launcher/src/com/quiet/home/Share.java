package com.quiet.home;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Something sent from your phone (a link, a name, a few words): what should the TV do with it?
 *
 *  - links it knows go straight there: a YouTube video plays on YouTube; Apple Music plays in
 *    Listen; a Netflix title opens in Netflix
 *  - anything else (Spotify, Instagram, IMDb, Letterboxd, an article, plain words) is looked at
 *    (the page's own title and description) and the voice's brain decides, with the same actions:
 *    a film or show opens in Watch, a song or artist plays in Listen, a question is answered...
 */
final class Share {

    static final Pattern URL_IN = Pattern.compile("https?://\\S+");
    static final Pattern YOUTUBE = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})");
    static final Pattern APPLE = Pattern.compile("music\\.apple\\.com/[a-z]{2}/(album|playlist|song|station)/[^/]*/?([^?/#]+)(?:\\?(?:.*&)?i=(\\d+))?");

    /** The action to take, in the voice's terms ({"name": ..., "args": {...}}). */
    static JSONObject route(Voice v, String text, String subject, Brain brain) throws Exception {
        Matcher m = URL_IN.matcher(text);
        String url = m.find() ? m.group().replaceAll("[).,]+$", "") : null;
        if (url != null) {
            Matcher y = YOUTUBE.matcher(url);
            if (y.find()) return act("youtube_play", "id", y.group(1));
            Matcher a = APPLE.matcher(url);
            if (a.find()) {
                String kind = a.group(1), id = a.group(2);
                if (a.group(3) != null) {
                    kind = "song";
                    id = a.group(3);
                }
                return act("listen_play", "kind", kind, "id", id, "title", subject);
            }
            if (url.contains("netflix.com/")) return act("open_link", "url", url, "pkg", "com.netflix.ninja", "label", "NETFLIX");
        }
        // Look at it, then decide like the voice does.
        StringBuilder about = new StringBuilder("They shared this from their phone, to do the most useful thing with it on the TV: ");
        about.append(text);
        if (!subject.isEmpty()) about.append(" (subject: ").append(subject).append(')');
        if (url != null) {
            String[] meta = meta(url);
            if (meta != null) about.append("\nThe link's page: site ").append(meta[2]).append("; title ").append(meta[0])
                    .append("; description ").append(meta[1]);
        }
        about.append("\nA film or series → watch; a song, album, artist or music video's song → play_music; a YouTube "
                + "video → search youtube with its title; an app → open_app; an article, recipe or post → say its gist "
                + "in under 18 words. If it can't be done on a TV, say so briefly.");
        return brain.decide(about.toString(), v.context(false));
    }

    static JSONObject act(String name, String... kv) throws org.json.JSONException {
        JSONObject a = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) a.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        return new JSONObject().put("name", name).put("args", a);
    }

    /** The page's own title, description and site name (Open Graph tags, else its title). */
    static String[] meta(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
            if (c.getResponseCode() >= 400) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; b.size() < 400_000 && (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            }
            String html = b.toString("UTF-8");
            String title = tag(html, "og:title"), desc = tag(html, "og:description"), site = tag(html, "og:site_name");
            if (title.isEmpty()) {
                Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
                if (t.find()) title = t.group(1).trim();
            }
            if (desc.isEmpty()) desc = tag(html, "description");
            if (site.isEmpty()) site = c.getURL().getHost();
            return new String[]{clean(title, 200), clean(desc, 400), clean(site, 60)};
        } catch (Exception e) {
            return null;
        }
    }

    static String tag(String html, String name) {
        Matcher m = Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(name) + "[\"'][^>]*content=[\"']([^\"']*)[\"']").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(?is)<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + Pattern.quote(name) + "[\"']").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    static String clean(String s, int max) {
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
