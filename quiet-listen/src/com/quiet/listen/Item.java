package com.quiet.listen;

import org.json.JSONObject;

/** One thing from Apple Music: an album, playlist, station, song or artist. */
final class Item {
    String rid, type;          // resource id and type as Apple names them ("l.abc", "library-albums")
    String kind, playId;       // what to hand the player ("album", "a.123")
    String title = "", sub = "", art = "";
    long ms;
    boolean library;

    boolean container() {
        return "album".equals(kind) || "playlist".equals(kind) || "artist".equals(kind);
    }

    boolean playable() {
        return "song".equals(kind) || "station".equals(kind);
    }

    /** Words for the small line under a cover. */
    String label() {
        switch (kind == null ? "" : kind) {
            case "station": return sub.isEmpty() ? "RADIO" : sub;
            case "artist": return "ARTIST";
            default: return sub;
        }
    }

    /** A search suggestion: words to search for, not a thing to play (sub says where it came from). */
    static Item term(String words, String sub) {
        Item it = new Item();
        it.kind = "term";
        it.title = words;
        it.sub = sub;
        it.rid = "term:" + words;
        return it;
    }

    static Item from(JSONObject r) {
        Item it = new Item();
        it.rid = r.optString("id");
        it.type = r.optString("type");
        JSONObject a = r.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        it.title = a.optString("name");
        it.sub = a.optString("artistName", a.optString("curatorName", ""));
        it.ms = a.optLong("durationInMillis");
        JSONObject art = a.optJSONObject("artwork");
        if (art != null) it.art = art.optString("url");
        JSONObject pp = a.optJSONObject("playParams");
        it.kind = pp != null ? pp.optString("kind") : "";
        it.playId = pp != null ? pp.optString("id") : it.rid;
        it.library = it.type.startsWith("library-") || (pp != null && pp.optBoolean("isLibrary"));
        if (it.kind.isEmpty()) {
            String t = it.type.replace("library-", "");
            it.kind = t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
        }
        if (it.kind.equals("radioStation")) it.kind = "station";
        // "Apple Music Hits", "Apple Music Chill"...: keep the part that tells them apart.
        if (it.kind.equals("station") && it.title.startsWith("Apple Music ")) {
            String rest = it.title.substring("Apple Music ".length());
            it.title = rest.length() <= 2 ? "Music " + rest : rest;
            it.sub = "Live radio";
        }
        return it;
    }

    /** Where this container's songs live. */
    String tracksPath(String storefront) {
        if ("artist".equals(kind)) return "/v1/catalog/" + storefront + "/artists/" + rid + "/view/top-songs";
        String t = "album".equals(kind) ? "albums" : "playlists";
        return library ? "/v1/me/library/" + t + "/" + rid + "/tracks" : "/v1/catalog/" + storefront + "/" + t + "/" + rid + "/tracks";
    }

    /** Artwork URL at a given pixel size. */
    String art(int px) {
        return art.replace("{w}", String.valueOf(px)).replace("{h}", String.valueOf(px)).replace("{f}", "jpg");
    }
}
