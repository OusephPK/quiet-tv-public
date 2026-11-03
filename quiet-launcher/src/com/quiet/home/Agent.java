package com.quiet.home;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The voice's thinking for anything more than a plain command: an agent that works in steps, like someone with
 * the remote and a phone in their hand. It may find things out first (YouTube's results, Apple Music's catalogue,
 * the web, a look at the screen, what they told it about their taste), then does exactly one thing: the same
 * actions the voice always had (play, open, search, answer...). "Play me the kind of music I'd like", "not Hindi,
 * something else", "some meal time videos": it decides what that means for them, checks what exists, and picks.
 *
 * Plain commands ("pause", "louder", "open Netflix") never come here: they're instant (Voice.quick/fast).
 * Everything it reads stays words in the request; nothing it finds is ever bought, signed into or typed into.
 */
final class Agent {

    /** The model that thinks (the owner's key can use these; chosen by the bench: speed and sense together). */
    static String model(android.content.Context c) {
        String m = Prefs.prefs(c).getString("agentModel", "");
        return m.isEmpty() ? DEFAULT : m;                      // "model@effort" sets how hard it thinks ("none" by default)
    }

    static final String DEFAULT = "gpt-5.4-mini";
    static final int STEPS = 6;                                 // looks before it must act (a few seconds each at most)

    interface Progress {
        void step(String what);
    }

    final Voice v;
    final Brain b;
    final String model;
    boolean dry;                                               // a test: nothing is kept (taste notes)

    Agent(Voice v, Brain b, String model) {
        this.v = v;
        this.b = b;
        this.model = model;
    }

    static final String RULES = "You are Quiet, the voice of a TV at home in India. Work out what they really mean, the "
            + "way a thoughtful friend holding the remote would, and then do it. You have two kinds of tools: ones that "
            + "find things out (youtube_search, music_search, film_search, web search, see_screen, remember, forget) and ones that do "
            + "something (all the others). Use the finding-out tools whenever the ask is open, descriptive or about "
            + "taste ('the kind of music I'd like', 'something else, not Hindi', 'meal time videos', 'a good thriller', "
            + "'what's new from AR Rahman'): decide what it means for them (their taste notes, their habits, what played "
            + "here, the time of day, what they just turned down), search, read the results, and choose the one best "
            + "fit; never search their words literally when they describe a kind of thing. Then call exactly one doing "
            + "tool. Plain asks need no finding out. Read every ask the way an adult at home means it, not word for word: "
            + "'meal time videos' are videos to watch while eating (light, absorbing, 10-25 minutes, the kinds of channels "
            + "they actually watch), not videos about meals or for children; 'something to fall asleep to' is calm and long; "
            + "'put something on' or 'the usual' is what they usually play at this time of day. Ground anything about taste "
            + "in what they really play here (their habits and 'Played on this TV': the artists, channels and apps), not "
            + "in generic popular results. When they turn something down or say what they like or dislike ('I don't like "
            + "Hindi music', 'not this', 'I love lo-fi'), first remember it as a lasting taste note (the language, genre, "
            + "artist or kind), then choose something clearly different that still fits them, by name (an artist or song "
            + "you found or know they play), never a vague 'something else'. A question only ever gets an answer (say, "
            + "or choose for several), never a play. 'It', 'that', 'this one', 'the second one' mean what's on screen "
            + "or on the card or just said, never a guess from history. Facts that change (new releases, scores, "
            + "today's news, a show's latest season, who's in a new film) come from a web search, never memory. To play "
            + "a YouTube video you found, use youtube with its id. Music is Apple Music (play_music) unless they name "
            + "another app; for music found with music_search, play_music with the song and artist (kind song) or the "
            + "artist (kind artist), or a mood or genre playlist (kind playlist). Be brief in what you say. If it truly "
            + "can't be done, say so plainly, and what they could say instead. Reference notes on this TV follow.\n\n";

    /** One ask, to one action ({"name", "args"}). Throws if the cloud can't be reached at all. */
    JSONObject run(String said, String context, Progress p) throws IOException {
        long t0 = System.currentTimeMillis();
        try {
            JSONArray tools = tools();
            JSONArray input = new JSONArray().put(new JSONObject().put("role", "user").put("content", context + "\n\nThey said: " + said));
            String previous = null;
            int webs = 0;
            boolean retried = false;
            for (int step = 0; step < STEPS; step++) {
                boolean last = step == STEPS - 1;
                JSONObject r = b.respond(model, RULES + Brain.SYSTEM + Brain.WORLD, input, previous, last ? doing(tools) : tools, last);
                previous = r.optString("id", null);
                JSONArray out = r.optJSONArray("output");
                JSONObject call = null;
                StringBuilder text = new StringBuilder();
                for (int i = 0; out != null && i < out.length(); i++) {
                    JSONObject it = out.getJSONObject(i);
                    String type = it.optString("type");
                    if (type.equals("function_call") && call == null) call = it;
                    else if (type.equals("web_search_call")) webs++;
                    else if (type.equals("message")) {
                        JSONArray parts = it.optJSONArray("content");
                        for (int j = 0; parts != null && j < parts.length(); j++) text.append(parts.getJSONObject(j).optString("text"));
                    }
                }
                if (webs > 0) p.step("LOOKING IT UP");
                if (call == null) {                                // words instead of a tool: an answer
                    String t = text.toString().replaceAll("\\(\\[[^]]*]\\([^)]*\\)\\)", "").trim();
                    return Voice.ask(t.isEmpty() ? "I couldn't work that out" : t);
                }
                String name = call.getString("name");
                JSONObject args = new JSONObject(call.optString("arguments", "{}"));
                if (!FINDING.contains(name) && !twoThings(said)) args.remove("then");   // only their own "and then"
                String again = FINDING.contains(name) || retried ? null : sameAgain(said, name, args);
                if (again != null) {                           // "something else", and it chose what's playing: once more
                    retried = true;
                    Log.i(Voice.TAG, "agent chose what's playing for '" + said + "': asked again");
                    input = new JSONArray().put(new JSONObject().put("type", "function_call_output").put("call_id", call.getString("call_id"))
                            .put("output", again));
                    continue;
                }
                if (!FINDING.contains(name)) {
                    Log.i(Voice.TAG, "agent (" + model + ") decided in " + (System.currentTimeMillis() - t0) + " ms, " + step
                            + " look(s)" + (webs > 0 ? ", " + webs + " web" : "") + ": " + name + " " + args);
                    args.put("_agent", true);                  // chosen knowing what it found: the old rewrites leave it be
                    return new JSONObject().put("name", name).put("args", args);
                }
                String result = find(name, args, p);
                Log.i(Voice.TAG, "agent " + name + " " + args + " → " + (result.length() > 300 ? result.substring(0, 300) + "…" : result));
                input = new JSONArray().put(new JSONObject().put("type", "function_call_output").put("call_id", call.getString("call_id"))
                        .put("output", result));
            }
            return Voice.ask("I couldn't work that out");
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /** They asked for more than one thing ("go home and pause it", "open YouTube then play..."). */
    static boolean twoThings(String said) {
        return Voice.plain(said).matches(".*\\b(and( then)?|then|after that|aur|phir|uske baad)\\b.*\\b(play|pause|open|go|search|turn|put|show|stop|watch|set|mute)\\b.*");
    }

    /**
     * They want something different ("something else", "not this", "another one") and the choice is what's playing
     * now (its artist or title): the words to send it back with, else null.
     */
    String sameAgain(String said, String name, JSONObject args) {
        if (!name.matches("play_music|youtube|watch")) return null;
        if (!Voice.plain(said).matches(".*\\b(else|different|another|other|not this|not that|don t like|dont like|change|skip this)\\b.*")) return null;
        String[] now = v.nowPlaying();
        if (now == null) return null;
        String q = Voice.plain(args.optString("query", args.optString("title", "")));
        for (String part : now) {
            String p = Voice.plain(part);
            if (p.length() > 2 && (q.contains(p) || !q.isEmpty() && p.contains(q)))
                return "That's what is playing now (\"" + now[0] + "\" by " + now[1] + "). They want something different: choose "
                        + "something else that fits their taste notes, not this artist or song.";
        }
        return null;
    }

    static final java.util.Set<String> FINDING = new java.util.HashSet<>(java.util.Arrays.asList(
            "youtube_search", "music_search", "film_search", "web_search", "see_screen", "remember", "forget"));

    /** The doing tools only (the last step must act). */
    static JSONArray doing(JSONArray all) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null && !FINDING.contains(t.optJSONObject("function").optString("name"))) out.put(t);
        }
        return out;
    }

    static JSONArray tools() throws org.json.JSONException {
        JSONArray t = new JSONArray();
        JSONArray act = Brain.tools(false);
        for (int i = 0; i < act.length(); i++) t.put(act.get(i));
        t.put(Brain.tool("youtube_search", "Find videos on YouTube: the top results with title, channel, length, views and age, "
                + "and each one's id (to play it with youtube).", Brain.props("query", Brain.str("What to search YouTube for."))));
        t.put(Brain.tool("music_search", "Look in Apple Music's catalogue: songs or artists with genre and year, to choose from.",
                Brain.props("query", Brain.str("An artist, song, genre or mood words."),
                        "kind", Brain.en("What to look for.", "song", "artist", "album"))));
        t.put(Brain.tool("film_search", "Look up films and series by name in Stremio's catalogue (what Watch plays): titles "
                + "with year, film or series. To confirm a title exists before watch.", Brain.props("query", Brain.str("A title or part of one."))));
        t.put(Brain.tool("see_screen", "A description of what's on the TV screen right now, for deciding (not an action).",
                Brain.props("question", Brain.str("What you need to know about the screen."))));
        t.put(Brain.tool("remember", "Keep something they told you about their taste or habits for every later ask ('doesn't like "
                + "Hindi music', 'watches cooking videos at lunch').", Brain.props("fact", Brain.str("It, in a few words."))));
        t.put(Brain.tool("forget", "Drop a taste note they want gone.", Brain.props("about", Brain.str("Which note."))));
        for (int i = 0; i < t.length(); i++) {                 // finding out has no "rest of the ask"
            JSONObject f = t.getJSONObject(i).getJSONObject("function");
            if (FINDING.contains(f.getString("name"))) {
                f.getJSONObject("parameters").getJSONObject("properties").remove("then");
                JSONArray req = f.getJSONObject("parameters").optJSONArray("required"), keep = new JSONArray();
                for (int k = 0; req != null && k < req.length(); k++) if (!req.getString(k).equals("then")) keep.put(req.get(k));
                f.getJSONObject("parameters").put("required", keep);
            }
        }
        return t;
    }

    /** A finding-out tool, carried out: words for the model. */
    String find(String name, JSONObject args, Progress p) {
        try {
            switch (name) {
                case "youtube_search":
                    p.step("SEARCHING YOUTUBE");
                    return youtube(args.optString("query"));
                case "music_search":
                    p.step("LOOKING IN APPLE MUSIC");
                    return music(args.optString("query"), args.optString("kind", "song"));
                case "film_search":
                    p.step("LOOKING FOR IT");
                    return films(args.optString("query"));
                case "web_search":
                    p.step("LOOKING IT UP");
                    return b.web(args.optString("query"));
                case "see_screen":
                    p.step("LOOKING AT THE SCREEN");
                    return v.describeScreen(args.optString("question"));
                case "remember":
                    return dry ? "Remembered: " + args.optString("fact") : Taste.remember(v, args.optString("fact"));
                case "forget":
                    return dry ? "Forgot it." : Taste.forget(v, args.optString("about"));
            }
        } catch (Exception e) {
            Log.w(Voice.TAG, "agent " + name + ": " + e);
            return "That didn't work (" + e.getMessage() + ").";
        }
        return "No such tool.";
    }

    /** YouTube's results for some words (read as a guest): numbered lines with title, channel, length, views, age, id. */
    static String youtube(String q) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        c.setRequestProperty("Accept-Language", "en");
        StringBuilder page = new StringBuilder();
        try (java.io.Reader in = new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[16384];
            for (int n; page.length() < 1_500_000 && (n = in.read(buf)) > 0; ) page.append(buf, 0, n);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (String[] v : Voice.videos(page.toString(), 10))
            out.append(++i).append(". \"").append(v[1]).append("\" by ").append(v[2]).append(", ").append(v[3])
                    .append(v[4].isEmpty() ? "" : ", " + v[4]).append(v[5].isEmpty() ? "" : ", " + v[5]).append(" [id ").append(v[0]).append("]\n");
        return out.length() == 0 ? "No videos found." : out.toString();
    }

    /** Stremio's catalogue (Cinemeta, what Watch searches): films and series with their years. */
    static String films(String q) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String type : new String[]{"movie", "series"}) {
            HttpURLConnection c = (HttpURLConnection) new URL("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search="
                    + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + ".json").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            try {
                JSONArray m = new JSONObject(new String(Brain.drain(c), StandardCharsets.UTF_8)).optJSONArray("metas");
                for (int i = 0; m != null && i < m.length() && i < 6; i++) {
                    JSONObject x = m.getJSONObject(i);
                    out.append("- \"").append(x.optString("name")).append("\" (").append(type.equals("movie") ? "film" : "series")
                            .append(x.optString("releaseInfo").isEmpty() ? "" : ", " + x.optString("releaseInfo")).append(")\n");
                }
            } catch (org.json.JSONException ignored) {
            }
        }
        return out.length() == 0 ? "Nothing found." : out.toString();
    }

    /** Apple Music's catalogue (its public search, India's storefront): songs or artists, with genre and year. */
    static String music(String q, String kind) throws IOException {
        String entity = kind.equals("artist") ? "musicArtist" : kind.equals("album") ? "album" : "song";
        HttpURLConnection c = (HttpURLConnection) new URL("https://itunes.apple.com/search?country=in&media=music&limit=12&entity="
                + entity + "&term=" + URLEncoder.encode(q, "UTF-8")).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        try {
            JSONArray r = new JSONObject(new String(Brain.drain(c), StandardCharsets.UTF_8)).getJSONArray("results");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.getJSONObject(i);
                out.append(i + 1).append(". ");
                if (entity.equals("song")) out.append('"').append(x.optString("trackName")).append("\" by ").append(x.optString("artistName"))
                        .append(" (").append(x.optString("collectionName")).append(")");
                else if (entity.equals("album")) out.append('"').append(x.optString("collectionName")).append("\" by ").append(x.optString("artistName"));
                else out.append(x.optString("artistName"));
                out.append(", ").append(x.optString("primaryGenreName"));
                String year = x.optString("releaseDate");
                if (year.length() >= 4) out.append(", ").append(year, 0, 4);
                out.append('\n');
            }
            return out.length() == 0 ? "Nothing found." : out.toString();
        } catch (org.json.JSONException e) {
            throw new IOException("odd reply from Apple Music");
        }
    }
}
