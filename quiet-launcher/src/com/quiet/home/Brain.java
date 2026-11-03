package com.quiet.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The voice's two trips to the cloud (OpenAI, with the owner's own key, which lives only on the TV):
 *
 *  1. hear():   the recording → text. A transcription model that copes with Indian English, Hindi,
 *               Hinglish and Malayalam, primed with the names it's likely to hear (apps, artists,
 *               what's in Continue Watching) so a name on the shelf isn't heard as a more common one.
 *  2. decide(): the text + what's on the TV right now → exactly one action (a tool call).
 *
 * Plain HttpURLConnection (it keeps the TLS connection alive between calls); warm() opens it while
 * you're still speaking, so the real calls don't pay for the handshake.
 */
final class Brain {

    static final String HEAR_MODEL = "gpt-4o-transcribe";   // the most accent-robust; ~0.5-1 s for a short ask
    static final String THINK_MODEL = "gpt-4.1-mini";       // quick, dependable tool calls (the voice)
    static final String DEEP_MODEL = "gpt-4.1";             // for the slower, richer asks (meanings, picks, recaps)
    static final String API = "https://api.openai.com/v1/";

    final String key;

    Brain(String key) {
        this.key = key;
    }

    /** Opens the connection early (a free, tiny request); errors don't matter here. */
    void warm() {
        try {
            HttpURLConnection c = open("models/" + THINK_MODEL, "GET");
            c.getResponseCode();
            drain(c);
        } catch (Exception ignored) {
        }
    }

    String hear(byte[] wav, String hints) throws IOException {
        String t = hear(wav, hints, HEAR_MODEL);
        if (!theirs(t)) {                                      // a script they don't use: faint English, guessed wrong
            String en = hear(wav, hints, HEAR_MODEL, "en");
            android.util.Log.i(Voice.TAG, "heard '" + t + "' in a script they don't use; as English: '" + en + "'");
            return en;
        }
        if (!t.matches("[\\p{IsLatin}\\p{N}\\p{P}\\p{Z}\\p{S}]*")) {   // Hindi or Malayalam script: maybe English, heard so
            String en = hear(wav, hints, HEAR_MODEL, "en");
            if (english(en)) {
                android.util.Log.i(Voice.TAG, "heard '" + t + "'; as English it's '" + en + "': taken");
                return en;
            }
        }
        return t;
    }

    static final java.util.Set<String> COMMON = new java.util.HashSet<>(java.util.Arrays.asList(("the a an to of it is are "
            + "me my can you could would will please play open what what's whats this that give show tell summary search on in "
            + "and for about go home back next pause stop it's i i'm am watching listening music song video film movie show "
            + "of with youtube netflix apple tv turn volume up down how who where when which one first second third title "
            + "watch put some something".split(" "))));

    /** Mostly everyday English words (so a Devanagari transcript of it was a wrong guess of the language). */
    static boolean english(String t) {
        String[] w = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ").trim().split("\\s+");
        int n = 0;
        for (String x : w) if (COMMON.contains(x)) n++;
        return w.length > 0 && !w[0].isEmpty() && n * 10 >= w.length * 4;
    }

    /** English, Hindi or Malayalam letters only (what the owner speaks); Urdu, Chinese, Korean... are guesses. */
    static boolean theirs(String t) {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (Character.isLetter(cp) && sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.DEVANAGARI
                    && sc != Character.UnicodeScript.MALAYALAM) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /** The wake check: a second of sound, the quicker model. */
    String hearQuick(byte[] wav, String phrase) throws IOException {
        return hear(wav, "", HEAR_MODEL, "en");             // English: no guessing in other languages
    }

    String hear(byte[] wav, String hints, String model) throws IOException {
        return hear(wav, hints, model, null);
    }

    String hear(byte[] wav, String hints, String model, String language) throws IOException {
        String b = "----quiet" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 2048);
        field(body, b, "model", model);
        if (language != null) field(body, b, "language", language);
        field(body, b, "response_format", "json");
        if (!hints.isEmpty()) field(body, b, "prompt", hints);
        body.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ask.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] data = body.toByteArray();
        JSONObject r = sendTwice(() -> {
            try {
                HttpURLConnection c = open("audio/transcriptions", "POST");
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                return c;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, data);
        String text = r.optString("text", "").trim();
        // With no speech in it, the model may just say back the names it was primed with.
        String head = hints.length() > 24 ? hints.substring(0, 24) : hints;
        if (!head.isEmpty() && (text.startsWith(head) || hints.contains(text.replaceAll("[.\\s]+$", "")) && text.length() > 3
                && hints.startsWith(text.substring(0, Math.min(8, text.length()))))) return "";
        return text;
    }

    /** The action to take: {"name": tool, "args": {...}}. */
    JSONObject decide(String heard, String context) throws IOException {
        return choose(THINK_MODEL, SYSTEM + WORLD, TOOLS, context + "\n\nThey said: " + heard);
    }

    /**
     * After look: the choice again, seeing the screen (a JPEG of it), among what acts on a screen (select,
     * tap, press, youtube, say). The bigger model: it reads screens and follows steps more surely
     * (2 Nov: the quick one paused a video it was asked to leave).
     */
    JSONObject see(String heard, String context, byte[] jpeg) throws IOException {
        return see(heard, context, jpeg, false);
    }

    /** answerOnly: they asked a question; seeing the screen, the AI may only answer (say), never press or open. */
    JSONObject see(String heard, String context, byte[] jpeg, boolean answerOnly) throws IOException {
        try {
            return choose(DEEP_MODEL, SYSTEM + WORLD + SEEING + (answerOnly ? ANSWER_ONLY : ""), answerOnly ? SAY_ONLY : SEE_TOOLS, new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", context + "\n\nThey said: " + heard))
                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("detail", "high")
                            .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))));
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    JSONObject choose(String model, String system, JSONArray tools, Object content) throws IOException {
        try {
            JSONObject req = new JSONObject()
                    .put("model", model)
                    .put("temperature", 0)
                    .put("tool_choice", "required")
                    .put("parallel_tool_calls", false)
                    .put("max_tokens", 800)                   // a list of choices needs room (200 cut it off: "ODD REPLY")
                    .put("tools", tools)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", content)));
            JSONObject r = sendTwice(() -> {
                try {
                    HttpURLConnection c = open("chat/completions", "POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    return c;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, req.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject call = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function");
            return new JSONObject().put("name", call.getString("name")).put("args", new JSONObject(call.optString("arguments", "{}")));
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /** The reasoning models (GPT-5 on): no temperature, their own token cap, and how hard to think. */
    static boolean reasons(String model) {
        return model.matches("(gpt-5|gpt-6|o\\d).*") && !model.contains("chat");
    }

    /**
     * One turn of a conversation with tools (the agent): the reply message, with its tool_calls. A reasoning model
     * thinks only as its tool calls need ("none": 5.4 refuses more with tools in chat completions); a setting a model
     * refuses is dropped and the turn asked again, once.
     */
    JSONObject chat(String model, JSONArray messages, JSONArray tools) throws IOException {
        try {
            String effort = model.contains("@") ? model.substring(model.indexOf('@') + 1) : "none";   // with tools, 5.4 takes only "none" here
            model = model.contains("@") ? model.substring(0, model.indexOf('@')) : model;
            JSONObject req = new JSONObject().put("model", model).put("messages", messages).put("tools", tools)
                    .put("tool_choice", "required").put("parallel_tool_calls", false);
            if (reasons(model)) req.put("max_completion_tokens", 4000).put("reasoning_effort", effort);
            else req.put("temperature", 0).put("max_tokens", 800);
            JSONObject r;
            try {
                r = sendLong(req);
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("CLOUD SAID 400")) throw e;
                req.remove("reasoning_effort");
                req.remove("parallel_tool_calls");
                android.util.Log.w(Voice.TAG, "agent: " + model + " refused a setting, asking plainly");
                r = sendLong(req);
            }
            return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /**
     * One turn with the Responses API (the agent): it may think ("low" by default), use the web itself, and call our
     * tools. previous: the turn before (its id), with our tool's output as input; else the opening input.
     */
    JSONObject respond(String model, String instructions, JSONArray input, String previous, JSONArray tools, boolean mustAct) throws IOException {
        try {
            String effort = model.contains("@") ? model.substring(model.indexOf('@') + 1) : "low";
            model = model.contains("@") ? model.substring(0, model.indexOf('@')) : model;
            JSONArray t = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {                // chat's tool shape to the Responses one
                JSONObject f = tools.getJSONObject(i).getJSONObject("function");
                t.put(new JSONObject().put("type", "function").put("name", f.getString("name"))
                        .put("description", f.optString("description")).put("parameters", f.getJSONObject("parameters")));
            }
            if (!mustAct) t.put(new JSONObject().put("type", "web_search"));
            JSONObject req = new JSONObject().put("model", model).put("instructions", instructions).put("input", input)
                    .put("tools", t).put("tool_choice", "required").put("parallel_tool_calls", false).put("max_output_tokens", 6000);
            if (reasons(model)) req.put("reasoning", new JSONObject().put("effort", effort));
            if (previous != null) req.put("previous_response_id", previous);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            for (int i = 0; ; i++) {
                try {
                    HttpURLConnection c = open("responses", "POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setReadTimeout(40000);
                    return send(c, body);
                } catch (IOException e) {
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (i > 0 || m.startsWith("THE KEY") || m.startsWith("OUT OF") || m.startsWith("CLOUD SAID 4")) throw e;
                    android.util.Log.w(Voice.TAG, "cloud hiccup, trying again: " + e);
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /** A chat request that may take a while (a reasoning model): 30 s, and once more if the network hiccups. */
    JSONObject sendLong(JSONObject req) throws IOException {
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; ; i++) {
            try {
                HttpURLConnection c = open("chat/completions", "POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setReadTimeout(30000);
                return send(c, body);
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (i > 0 || m.startsWith("THE KEY") || m.startsWith("OUT OF") || m.startsWith("CLOUD SAID 4")) throw e;
                android.util.Log.w(Voice.TAG, "cloud hiccup, trying again: " + e);
            }
        }
    }

    static final String WEB_MODEL = "gpt-5.4-mini";

    /** The web, searched by OpenAI's own search (the same key): a short, factual answer with no links. */
    String web(String question) throws IOException {
        try {
            JSONObject req = new JSONObject().put("model", WEB_MODEL).put("max_output_tokens", 900)
                    .put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")))
                    .put("input", "For a TV assistant in India: answer briefly with the facts (names, titles, dates), no links. " + question);
            HttpURLConnection c = open("responses", "POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setReadTimeout(30000);
            JSONObject r = send(c, req.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            JSONArray items = r.optJSONArray("output");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (!"message".equals(it.optString("type"))) continue;
                JSONArray parts = it.optJSONArray("content");
                for (int j = 0; parts != null && j < parts.length(); j++)
                    if ("output_text".equals(parts.getJSONObject(j).optString("type"))) out.append(parts.getJSONObject(j).optString("text"));
            }
            String t = out.toString().replaceAll("\\(\\[[^]]*]\\([^)]*\\)\\)", "").trim();
            return t.isEmpty() ? "Nothing found." : t.length() > 1500 ? t.substring(0, 1500) : t;
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /**
     * A plain question for the other Quiet apps (through home's Prefs provider, so the key never
     * leaves home): a system note, the question, a cap on the answer's length. JSON when asked.
     */
    static final String BOX = "Someone at their TV in India is in a search box and said this (speech recognition of English "
            + "with an Indian accent, sometimes Hindi or Malayalam words). Decide what they want and reply with JSON only: "
            + "{\"do\": \"type\" | \"clear\" | \"word\" | \"add\" | \"command\", \"text\": \"...\"}. "
            + "type: words to search for (a title, a name, a topic); text is the search, with misheard names of actors, films, "
            + "series, shows, songs, artists, YouTubers and places put right ('given heart' means 'Kevin Hart') and filler like "
            + "'search for', 'can you find', 'on YouTube' dropped; never add words of your own. To replace what's there with "
            + "other words is type too. clear: empty the box ('clear this', 'get rid of that', 'erase what I typed', 'start "
            + "again', 'sab hatao'). word: take off the last word ('remove the last word', 'undo that'). add: put more on the "
            + "end ('add trailer', 'and season 2'); text is just the words to add. command: something for the TV rather than "
            + "the box (play or pause, open an app, volume, go back or home, a question), and a search in a different app "
            + "than the one given ('search Netflix for Dune' while in YouTube).";

    /** One look, one question, JSON back (vision): the plain call the classifiers below share. */
    JSONObject glance(byte[] jpeg, String question) throws IOException {
        return glance(jpeg, question, 300);
    }

    JSONObject glance(byte[] jpeg, String question, int tokens) throws IOException {
        try {
            JSONObject req = new JSONObject()
                    .put("model", DEEP_MODEL)
                    .put("temperature", 0)
                    .put("max_tokens", tokens)
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", "This is a TV screen. " + question + " Reply with JSON only."))
                            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("detail", "high")
                                            .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))))));
            HttpURLConnection c = open("chat/completions", "POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setReadTimeout(20000);
            JSONObject r = send(c, req.toString().getBytes(StandardCharsets.UTF_8));
            return new JSONObject(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}"));
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    /** Which kind of screen this is, one word from the kinds given (the TV then presses a fixed route for it). */
    String screen(byte[] jpeg, String app, String kinds) throws IOException {
        String k = glance(jpeg, "The app is " + app + ". Which kind of screen is this? Kinds: " + kinds
                + ". {\"screen\": one word from the kinds}").optString("screen", "other").toLowerCase(java.util.Locale.ROOT).trim();
        return k.isEmpty() ? "other" : k.split("[^a-z]")[0];
    }

    /**
     * What's highlighted (the item with the focus: a bright outline, enlarged, or lit) and where it sits, and the
     * main row's titles in order: so 'this one', 'the second title' and arrows are judged from fact, not a guess.
     */
    String highlighted(byte[] jpeg) throws IOException {
        JSONObject j = glance(jpeg, "Find the one item that has the focus (TV focus: a bright or white outline, a "
                + "lighter or enlarged card, or a lit button). {\"focused\": its full title or label exactly as shown, "
                + "\"where\": which row (from the top) and position (from the left), \"row\": the titles in that row, left "
                + "to right, exactly as shown}");
        return "Highlighted now: \"" + j.optString("focused") + "\" (" + j.optString("where") + "). That row, left to right: "
                + j.opt("row");
    }

    /** What words said in a search box mean: {"do": type|clear|word|add|command, "text": ...}. */
    JSONObject boxAsk(String heard, String app, String now) throws IOException {
        String r = ask(BOX, "App: " + app + "\nThe box holds: \"" + now + "\"\nThey said: " + heard, 80, true, false);
        try {
            return new JSONObject(r);
        } catch (org.json.JSONException e) {
            throw new IOException("not JSON: " + r);
        }
    }

    /**
     * The words in the search box on this screen, read from a picture of it, exactly as shown; "" if the
     * box is empty; null if there's no search box to see. (Deleting one letter too many in YouTube goes
     * back a page, so the count must be right.)
     */
    String readBox(byte[] jpeg) throws IOException {
        try {
            JSONObject req = new JSONObject()
                    .put("model", DEEP_MODEL)
                    .put("temperature", 0)
                    .put("max_tokens", 80)
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", "This is a TV screen. Find the search box "
                                    + "(the field where search words are typed, usually at the top with a keyboard or suggestions "
                                    + "near it). Reply with JSON only: {\"box\": true or false, \"text\": the characters typed in it "
                                    + "exactly as shown, including spaces; empty if it only shows a grey placeholder like 'Search'}."))
                            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("detail", "high")
                                            .put("url", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))))));
            HttpURLConnection c = open("chat/completions", "POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setReadTimeout(20000);
            JSONObject r = send(c, req.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject got = new JSONObject(r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}"));
            return got.optBoolean("box", false) ? got.optString("text", "") : null;
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    String ask(String system, String user, int maxTokens, boolean json, boolean deep) throws IOException {
        try {
            JSONObject req = new JSONObject()
                    .put("model", deep ? DEEP_MODEL : THINK_MODEL)
                    .put("temperature", 0.3)
                    .put("max_tokens", Math.max(32, Math.min(2000, maxTokens)))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", user)));
            if (json) req.put("response_format", new JSONObject().put("type", "json_object"));
            HttpURLConnection c = open("chat/completions", "POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setReadTimeout(25000);
            JSONObject r = send(c, req.toString().getBytes(StandardCharsets.UTF_8));
            return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    // ---------------------------------------------------------------- http

    HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        if (method.equals("POST")) c.setDoOutput(true);
        return c;
    }

    /** Once more if the network hiccups (not for a refused key or no credit). */
    static JSONObject sendTwice(java.util.function.Supplier<HttpURLConnection> open, byte[] body) throws IOException {
        try {
            // A voice ask normally takes 0.5-2 s: one that hangs is retried after 5 s (it once waited 12 s).
            HttpURLConnection first = open.get();
            first.setReadTimeout(5000);
            return send(first, body);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.startsWith("THE KEY") || m.startsWith("OUT OF")) throw e;
            android.util.Log.w("QuietVoice", "cloud hiccup, trying again: " + e);
            return send(open.get(), body);
        }
    }

    static JSONObject send(HttpURLConnection c, byte[] body) throws IOException {
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body);
        }
        int code = c.getResponseCode();
        String text = new String(drain(c), StandardCharsets.UTF_8);
        if (code == 401) throw new IOException("THE KEY WAS REFUSED");
        if (code == 429) throw new IOException("OUT OF CREDIT OR TOO MANY ASKS");
        if (code >= 400) {
            String why = "";
            try {
                why = new JSONObject(text).getJSONObject("error").optString("message");
            } catch (Exception ignored) {
            }
            if (!why.isEmpty()) android.util.Log.w(Voice.TAG, "cloud said " + code + ": " + why);
            throw new IOException("CLOUD SAID " + code);
        }
        try {
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IOException("ODD REPLY");
        }
    }

    static byte[] drain(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in == null) return out.toByteArray();
        try (InputStream i = in) {
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static void field(ByteArrayOutputStream body, String b, String name, String value) throws IOException {
        body.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- what it may do

    /**
     * The world on the screen, as the voice knows it: places, who lives there, their days. Its mysteries
     * stay mysteries: only ever hinted at, never explained (nothing of how anything opens or what the
     * old script says is given here).
     */
    static final String WORLD = "\n\nThe world on the screen (home and every Quiet page) is 'the old road': an old underground "
            + "road taken back by green, seen from its bank. A near wall of carved stone holds the clock and the app plaques; the "
            + "road runs away under arches into the dark, with side passages (one lantern-lit, one full of mushrooms, one dark) "
            + "and, far off at the end, a door with an ember glow. Light falls through a collapse in the roof: the real sky, "
            + "sun, moon, weather, birds and bats. A shallow stream runs along the bank and mirrors everything. Who lives there: "
            + "Skye, the dog (brown, a teal collar, an amber tag with one letter of an old script on it). His day: a stretch and "
            + "a drink at the road's mouth at dawn; patrols and plays with his bone in the morning; naps in the shade by the "
            + "wall at midday; one mad dash every evening, then supper (his bowl is always found filled; nobody sees who fills "
            + "it); asleep on his bed at night with Mote curled on his back. In rain he shelters under the arch and shakes off "
            + "after; in a storm he hides on his bed; he hops at snowflakes; he chases the mouse and watches the frog; when the "
            + "TV has been off a long while he is extra glad to see you. Mote is the firefly with the lamp: it lights whatever "
            + "you choose, and sometimes flies right up to the glass and taps it. The frog is old, sits on its stone by the "
            + "lily pond, croaks at dusk and in rain, snaps at fireflies and plops into the water when Skye comes close. A "
            + "mouse lives in a hole at the foot of the wall (out at night), a snail in the rubble among small mushrooms (out "
            + "after damp nights); fireflies gather in the grove of big mushrooms; moths, fish, butterflies by day, bats at "
            + "dusk, sometimes a fox or a hare far down the road. A tally of days is scratched near the bottom of the wall; an "
            + "inscription in an old script runs down its edge and changes with the moon; lamps along the road relight as the "
            + "weeks go by. Festivals come to the bank, and everyone dances when Apple Music plays. Asked about the world or "
            + "anyone in it, answer with say, warmly, like a neighbour who watches it: what they're doing right now "
            + "(the 'Skye now' line in the context; say it as likely, not certain), their habits, small details. Never make up new "
            + "creatures or events. Its mysteries (who made the road, who fills the bowl, who carries a lantern on some "
            + "nights, what the old script says, what is behind the door, how it opens) are never explained: answer those only "
            + "with a short hint, a little poetic and unsure ('Some say the road remembers.', 'Ask the moon.'), never a straight "
            + "answer, never a meaning, never any buttons or codes.";

    static final String SYSTEM = "Always answer in English (Latin letters), whatever language they spoke in. You are the voice of Quiet, a calm TV at home in India. The person speaks Indian English, "
            + "Hindi, Hinglish or Malayalam; the transcript may be in Latin, Devanagari or Malayalam script. Call exactly one tool, "
            + "for the first thing to do. If they ask for more than one thing ('go home and pause the music', 'open YouTube and "
            + "play something'), put the rest in that tool's then, in their words: it's done next, with a fresh look at the "
            + "screen. A request asked as a question ('can you go home?', 'വീട്ടിലേക്ക് പോകാമോ?', 'ghar ja sakte ho?') is a "
            + "command: do it, never just answer yes. Ghar, veedu and home mean the home screen; switch the TV's input only "
            + "when they name one (HDMI 1). An app's own home ('YouTube home', 'home on Netflix', 'Prime's home page') is "
            + "open_app with fresh=true, not the TV's home screen. If they mean something on screen that 'On screen' doesn't "
            + "show (an app with no readable words, 'the second video', 'that poster', 'what is this?'), call look. "
            + "Write names and titles in their usual Latin spelling (Kesariya, Arijit Singh, Manjummel Boys). "
            + "Music goes to play_music (Apple Music) unless they name YouTube or another app. Films and series go to watch "
            + "(Stremio) unless they name Netflix, YouTube, Prime, Hotstar or another app; 'continue', 'resume', 'aage dekho' "
            + "means resume=true. Searching inside YouTube or Netflix uses search. A YouTube video named by its channel or "
            + "topic ('the latest AI Explained video', 'a MrBeast video') is youtube with those words as the query. 'That', 'it', "
            + "'this one', 'the same', 'again' mean the last thing named in 'Just before', your own answers included: after "
            + "you said what they were listening to, 'play that' plays that song (play_music with its title and artist); "
            + "after naming a film, 'watch it' watches it. Never turn that into a bare play or pause. What "
            + "they played here is in 'Played on this TV' (every app; trust it over any other list): asked to play "
            + "('play the video I was watching', 'continue the show from last night', 'that song again'), play those exact "
            + "entries: youtube with that exact title and channel (it resumes where they left it), watch or open_app for a "
            + "show, play_music for a song. A question ('what was I watching?', 'which song was that?') only gets an answer "
            + "(say, or choose for several): never start anything they didn't ask to play. 'What am I watching (now)?' is "
            + "about the app in front: its 'Playing' line, or if it doesn't tell the TV, look; never answer with another app's "
            + "history. Anything else "
            + "only an app's own screens know, their own (the video they were watching, their history, watch later, their "
            + "library or lists, a profile) is look: it sees the screen and presses the remote's buttons to get there. If that "
            + "app isn't in front, open_app first with the rest in then ('open YouTube' then 'play the AI Explained video I "
            + "was watching from History'); never a search for it, that finds a stranger's video. The TV's microphone is faint and often mishears "
            + "the start of a sentence: 'on your left', 'you too', 'open you' before a video ask most likely meant 'on YouTube' or 'open YouTube'. If a text box is focused and they are "
            + "dictating words rather than giving a command, use type. If the keyboard is up in a box this app hides, they are "
            + "dictating a search there: use search in the app in front with their words (unless it's clearly a command). Questions you can answer from the context (time, "
            + "weather, what's playing) or anything you can't do: say, in plain English, short (longer only when the question needs it). When it's 'which one?' or a list, use choose; "
            + "while a list is on the card ('On the card'), 'the second one', 'that one', or a name from it picks it: do what that "
            + "option's ask says. Otherwise 'the first one', 'the second one', 'the last one' always mean what's on screen in the "
            + "app in front ('On screen', counted without menus; or look), never an entry from 'Played on this TV' or any list "
            + "in this context, and never in another app. Only open apps from the "
            + "list. Short commands like 'ruko', 'band karo' mean pause; 'chalao' means play; 'awaaz badhao/kam karo' means "
            + "volume up/down; 'peeche' means back. If they describe a film, series, song or artist instead of naming it "
            + "('that Malayalam thriller with Fahadh'), use the title you're confident of. Follow-ups refer to 'Just before', "
            + "each recent ask with what the TV showed and, in brackets, what it did ('louder', 'no, the other one', 'next "
            + "episode'). A correction or reversal ('no...', 'actually...', 'not that', '...it back', 'undo that') changes that "
            + "same thing (see 'Last done'): after a home sound change, 'mute it', 'unmute it back' or 'turn it back on' is "
            + "home_sound again (off, or on), never the TV's volume. Questions about what's playing, a film, an artist or a song "
            + "may use what you know: answer with say in under 18 words, and say so if you're not sure. Home sound, its "
            + "loudness, the music party (the world dancing to music) and motion are settings; 'stop the music in 30 minutes' "
            + "or 'turn off the TV in an hour' are timers. A YouTube channel, creator or kind of video ('open MrBeast's "
            + "channel', 'cricket highlights on YouTube') is a YouTube search for it, not just opening the app. Playing on "
            + "YouTube ('play cricket highlights on YouTube', 'play something from my recommendations', 'play the first video' "
            + "after a YouTube search: that search's words) uses youtube, which opens YouTube itself (no open_app first; "
            + "search too); YouTube shows nothing readable, so select can't "
            + "press anything in it. 'On screen' lists the words visible in the app in front: use select "
            + "to press one of them ('skip intro', 'Season 2', 'open Settings'), only with words that are there (else look), "
            + "and use them to answer 'what is "
            + "this'. Voice works over every app: playback, volume, back and home always apply to whatever is in front. "
            + "'Play X by Y' is the song or album X by the artist Y: keep X in the query even if you don't know it ('play USB "
            + "by Fred again' is query 'USB Fred again..'), kind song or album only if you're sure which, else any. With YouTube "
            + "or another video app in front, 'continue', 'resume' or 'play' without a title is media play, and 'continue X' "
            + "(X not in Continue watching) is a search in that app for X. "
            + "What works on THIS TV: YouTube shows nothing you can read or press (use youtube to play a video by its title; "
            + "search youtube for results); Netflix ignores search links (search netflix types into Netflix's own search); "
            + "Stremio is reached through watch. You can't know what's trending, new, popular or top today: for that use "
            + "watch with title 'trending' (films) or 'trending series', or play_music with query 'Top 100: India'; the TV "
            + "fills these from live catalogs. Never leave a title, query or app empty: ask with say instead. Never search "
            + "a description word for word: 'good music', 'my favourites', 'something I like' or 'play some music' with "
            + "nothing specific is play_music kind mine (their own taste); a mood ('something relaxing', 'romantic songs') is "
            + "play_music kind playlist with that mood.";

    /** Added for see(): what to do with the picture. */
    static final String SEEING = " Now you can see the screen (the picture). Count things in the main rows left to right, "
            + "then down; leave out menus and the side bar. To play or open something shown: select it if its words are in "
            + "'On screen'; in YouTube, youtube with its title exactly as shown; if no titles show (inside a video), press "
            + "back first to see the lists. To reach an app's own home page: press back (it leaves a video or a search). "
            + "After pressing you see the screen again; when it's done, say so in a few words. tap works only in some apps, "
            + "never YouTube. Questions about the screen: answer with say. Never "
            + "identify a real person from their face: for 'who's that actor?', name the show or film (from the screen or "
            + "'Playing') and its main cast, and who they play if you know. You can press the remote's arrows and OK too, in "
            + "every app (YouTube and Netflix included): find the highlighted item (the one with a bright outline or "
            + "enlarged), move from it with arrows, OK presses it. Go a few presses at a time and look again. Press "
            + "ok only in a round where the thing they want is already the highlighted one; if it isn't, move to it with "
            + "arrows (count from the highlighted one) and look again first: never ok on whatever happens to be highlighted. "
            + "To search in an app: get to its search (a search icon, the menu on the left, a box at the top), OK, then use "
            + "type with the words (it types into any app); the results show as it types. If you truly can't get there, say "
            + "so plainly: never claim it's done when it isn't. For what's "
            + "theirs (what they were watching, history, library, watch later): in YouTube it's in History (the left menu: "
            + "press left until the menu opens, then find History or Library) or a 'Continue watching' row; open it and press "
            + "OK on the one they mean (the most recent is first). Never give up after one look if pressing could get there. Still loading (a logo, a spinner, a blank page): "
            + "press wait, never give up or ask them to tell you. A profile picker ('Who's watching?', 'Choose a profile'): "
            + "press ok on the lit profile (it's the usual one), never left or right to another.";

    static final JSONArray TOOLS = tools(false), SEE_TOOLS = tools(true), SAY_ONLY = sayOnly();

    static final String ANSWER_ONLY = " They asked a question: answer it with say, from what you see and what you know "
            + "(what a show or film is about, who's in it, how many seasons, whether it's well liked); don't press, open or "
            + "play anything. 'The second title' counts along the row that has the highlight, from the left; 'this one' is "
            + "the highlighted one. Always begin with its full title exactly as shown, in double quotes ('\"Dark Matter\" is...', never 'Dark'), "
            + "then the answer. If they then want it, they'll ask.";

    static JSONArray sayOnly() {
        JSONArray t = new JSONArray(), all = tools(true);
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.optJSONObject(i);
            if (f != null && f.optJSONObject("function") != null && f.optJSONObject("function").optString("name").equals("say")) t.put(f);
        }
        return t;
    }

    /** What it may do; while seeing the screen (see), tap instead of look. */
    static JSONArray tools(boolean seeing) {
        try {
            JSONObject number = new JSONObject().put("type", "number");
            JSONArray t = new JSONArray()
                    .put(seeing
                            ? tool("tap", "Press a place on the screen you see, when it has no words in 'On screen' to select.",
                            props("x", new JSONObject(number.toString()).put("description", "Its centre: 0 (left edge) to 1 (right edge)."),
                                    "y", new JSONObject(number.toString()).put("description", "Its centre: 0 (top) to 1 (bottom)."),
                                    "what", str("What it is (its title).")))
                            : tool("look", "See the screen, when they mean something on it that 'On screen' doesn't show. You "
                            + "get a picture of it and choose again.", props("what", str("What to look for."))))
                    .put(tool("open_app", "Open an app installed on the TV.",
                            props("app", str("The app's name, as in the list."),
                                    "fresh", new JSONObject().put("type", "boolean")
                                    .put("description", "True for the app's own home page: it starts afresh, not where it was left."))))
                    .put(tool("play_music", "Play music in Listen (Apple Music).",
                            props("query", str("What to play: a song, artist, album, playlist, mood or genre."),
                                    "kind", en("What the query is (mine: their own taste, for 'my favourites', 'good music').", "song", "artist", "album", "playlist", "station", "any", "mine"))))
                    .put(tool("watch", "Watch a film or series in Watch (Stremio).",
                            props("title", str("The title, or their description when they want suggestions."),
                                    "resume", new JSONObject().put("type", "boolean")
                                    .put("description", "Continue where they left off (it's in Continue watching)."),
                                    "suggest", new JSONObject().put("type", "boolean")
                                    .put("description", "True when they want ideas ('something light', 'films like X') rather than one title."))))
                    .put(tool("search", "Search inside another app (YouTube, Netflix, Prime Video, Apple TV, Hotstar, any app in the list).",
                            props("app", str("youtube, netflix, or the app's name as in the list."), "query", str("What to search for."))))
                    .put(tool("youtube", "Play on YouTube: a video by its id (from youtube_search), the top video for what they ask, or "
                            + "(query and id empty) their own YouTube mix, made from what they watch.",
                            props("query", str("What to play (with an id: its title); empty for something from their recommendations."),
                                    "id", str("The video's id from youtube_search, when you chose one."))))
                    .put(tool("select", "Press something shown on screen in the app in front (a title, a tab, 'Skip intro', an episode).",
                            props("text", str("Its words exactly as they appear on screen."))))
                    .put(tool("media", "Control what's playing, in any app.",
                            props("action", en("What to do.", "play", "pause", "toggle", "next", "previous", "forward", "rewind", "restart"),
                                    "seconds", new JSONObject().put("type", "integer").put("description", "For forward or rewind; default 10."))))
                    .put(tool("volume", "The TV's volume.",
                            props("action", en("What to do.", "up", "down", "mute", "unmute", "set"),
                                    "level", new JSONObject().put("type", "integer").put("description", "0-100, for set."))))
                    .put(tool("go", "Navigate.", props("where", en("Where to.", "back", "home"))))
                    .put(tool("type", "Type words into the focused text box (dictation), or edit what's in it.", props("text", str("Exactly what to type "
                            + "(for add: the words to put on the end; empty for clear and word)."),
                            "op", en("Only to edit the box: clear (empty it, 'clear the search'), word (take off the last word), "
                                    + "add (put the text on the end).", "clear", "word", "add"))))
                    .put(tool("tv", "The TV itself.", props("action", en("What to do.", "off", "input"),
                            "input", str("For input: which one, e.g. HDMI 1."))))
                    .put(tool("setting", "Change one of the TV's own settings.",
                            props("name", en("Which setting.", "home_sound", "loudness", "music_party", "motion"),
                                    "value", str("home_sound: music, nature or off. loudness: soft, normal or loud. music_party: on or off. motion: instant or standard."))))
                    .put(tool("timer", "A sleep timer: the music stops, or the TV goes to sleep.",
                            props("what", en("What stops.", "music", "tv"), "minutes", new JSONObject().put("type", "integer")
                                    .put("description", "Minutes from now; 0 cancels; -1 (music only) means after this song."))))
                    .put(tool("wake", "The hands-free wake word: teach it (learn my voice saying it), or switch it on or off.",
                            props("action", en("What to do.", "teach", "on", "off"))))
                    .put(tool("say", "Answer, or say why it can't be done. The card grows for a longer answer.",
                            props("text", str("Plain English: short for simple things; up to 45 words when the question needs it "
                                    + "(about the world, what they played, a recap)."))))
                    .put(tool("choose", "Show a short list on the card to pick from (with the remote, or by saying which): when "
                            + "there are a few real candidates (which of the videos they watched, which episode, which of several "
                            + "matches) or they asked for a list ('what did I watch this week?'). Never for one obvious answer.",
                            props("text", str("A short heading."), "options", new JSONObject().put("type", "array")
                                    .put("description", "2 to 6 choices, best first.").put("items", new JSONObject().put("type", "object")
                                            .put("properties", new JSONObject().put("label", str("What the card shows: at most 5 words, "
                                                    + "enough to tell this choice from the others ('match highlights, Hindi')."))
                                                    .put("ask", str("What to do if picked, said as a full ask ('play the YouTube video "
                                                            + "\"X\" by Y').")))
                                            .put("required", new JSONArray().put("label").put("ask"))))));
            if (seeing) {                                      // seeing: only what acts on the screen
                JSONArray only = new JSONArray();
                for (int i = 0; i < t.length(); i++) {
                    String n = t.getJSONObject(i).getJSONObject("function").getString("name");
                    if (n.matches("tap|select|youtube|say|type")) only.put(t.get(i));
                }
                t = only;
            }
            // The remote's buttons, pressed through the TV's own developer connection (Shell); back and home
            // also through accessibility if that's refused.
            if (seeing) t.put(tool("press", "Press the remote's buttons: arrows move the highlight, ok presses what's highlighted, "
                    + "back leaves a video or a search, home goes to the home screen, wait gives a screen that's still loading two seconds. "
                    + "Afterwards you see the screen again.",
                    props("keys", new JSONObject().put("type", "array").put("description", "The buttons, in order (at most 6).")
                            .put("items", new JSONObject().put("type", "string").put("enum", new JSONArray()
                                    .put("up").put("down").put("left").put("right").put("ok").put("back").put("home").put("wait"))))));
            return t;
        } catch (org.json.JSONException e) {
            return new JSONArray();
        }
    }

    static JSONObject tool(String name, String about, JSONObject props) throws org.json.JSONException {
        // Every action but an answer can carry the rest of a longer ask (Voice.next does it, step by step).
        if (!name.equals("say")) props.put("then", str("Only when they asked for more than one thing: the rest of it, in their words."));
        JSONArray required = new JSONArray();
        java.util.Iterator<String> k = props.keys();
        while (k.hasNext()) {
            String n = k.next();
            if (!n.equals("seconds") && !n.equals("level") && !n.equals("resume") && !n.equals("input") && !n.equals("kind") && !n.equals("suggest")
                    && !n.equals("then") && !n.equals("fresh") && !n.equals("done") && !n.equals("op") && !n.equals("id") && !(name.equals("timer") && n.equals("minutes"))) required.put(n);
        }
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", about)
                .put("parameters", new JSONObject().put("type", "object").put("properties", props).put("required", required)));
    }

    static JSONObject props(Object... kv) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    static JSONObject str(String about) throws org.json.JSONException {
        return new JSONObject().put("type", "string").put("description", about);
    }

    static JSONObject en(String about, String... values) throws org.json.JSONException {
        JSONArray v = new JSONArray();
        for (String s : values) v.put(s);
        return new JSONObject().put("type", "string").put("description", about).put("enum", v);
    }
}
