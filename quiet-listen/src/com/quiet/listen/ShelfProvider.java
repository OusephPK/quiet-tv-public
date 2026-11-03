package com.quiet.listen;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

/**
 * Home's LISTEN shelf: the last few things you played (Listen Now's recently played, as last
 * fetched), each with what to hand the player. Only Quiet apps may read it (signature permission).
 */
public class ShelfProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"title", "sub", "kind", "id"});
        try {
            String recent = getContext().getSharedPreferences("listen", android.content.Context.MODE_PRIVATE).getString("recent", "");
            if (!recent.isEmpty())
                for (Item it : Api.items(new JSONObject(recent))) {
                    if (!it.playable()) continue;
                    c.addRow(new Object[]{it.title, it.sub, it.kind, it.playId});
                    if (c.getCount() >= 6) break;
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
