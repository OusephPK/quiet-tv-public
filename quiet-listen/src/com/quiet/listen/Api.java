package com.quiet.listen;

import android.os.Handler;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the hidden Apple player to make Apple Music API calls on our behalf and hands back the JSON.
 * Every call either answers or fails within 20 seconds.
 */
final class Api {

    interface Done {
        void ok(JSONObject data);

        default void fail(String why) {}

        /** The same success handler, plus something to do if the call fails. */
        default Done withFail(java.util.function.Consumer<String> onFail) {
            Done self = this;
            return new Done() {
                @Override public void ok(JSONObject d) { self.ok(d); }
                @Override public void fail(String why) { onFail.accept(why); }
            };
        }
    }

    final WebView web;
    final Handler ui;
    final Map<Integer, Done> pending = new HashMap<>();
    int next = 1;

    Api(WebView web, Handler ui) {
        this.web = web;
        this.ui = ui;
    }

    void get(String path, JSONObject params, Done done) {
        int id = next++;
        pending.put(id, done);
        send(id, path, params, 0);
    }

    // Asked before Apple's player has woken (a cold start), a request never leaves the page: ask
    // once more before giving up. A late answer to the first ask still lands, under the same id.
    void send(int id, String path, JSONObject params, int attempt) {
        web.evaluateJavascript("window.__quietApi&&__quietApi(" + id + "," + JSONObject.quote(path) + ","
                + (params == null ? "{}" : params.toString()) + ")", null);
        ui.postDelayed(() -> {
            if (!pending.containsKey(id)) return;
            if (attempt == 0) send(id, path, params, 1);
            else fail(id, "TIMED OUT");
        }, attempt == 0 ? 15_000 : 20_000);
    }

    void reply(int id, String json) {
        Done d = pending.remove(id);
        if (d == null) return;
        try {
            Object o = new org.json.JSONTokener(json).nextValue();
            d.ok(o instanceof JSONObject ? (JSONObject) o : new JSONObject().put("data", o));
        } catch (Exception e) {
            d.fail("BAD REPLY");
        }
    }

    void fail(int id, String why) {
        Done d = pending.remove(id);
        if (d != null) d.fail(why);
    }

    /** The "data" array of a reply as items. */
    static List<Item> items(JSONObject reply) {
        return items(reply.optJSONArray("data"));
    }

    static List<Item> items(JSONArray arr) {
        List<Item> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            Item it = Item.from(r);
            if (!it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    static JSONObject params(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }
}
