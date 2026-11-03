package com.quiet.watch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home's WATCH shelf: Continue Watching as Watch last saw it, each with where you are and the
 * Stremio link that picks it up (the next episode when you'd all but finished one). Quiet apps only.
 */
public class ShelfProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"title", "sub", "uri"});
        try {
            JSONArray a = new JSONArray(getContext().getSharedPreferences("watch", android.content.Context.MODE_PRIVATE).getString("shelf", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                c.addRow(new Object[]{o.optString("t"), o.optString("s"), o.optString("u")});
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.quiet.shelf"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
