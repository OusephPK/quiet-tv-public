package com.quiet.listen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.quiet.px.Pal;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album art, drawn fine (see Fine) so you can tell what it is. Fetched two at a time in the
 * background and kept in a small memory cache. Covers without art get a generated pixel cover.
 */
final class Covers {

    final Map<String, Bitmap> cache = Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 240;
        }
    });
    final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** This cover, size×size grid pixels drawn fine, or null while it's still on its way. */
    Bitmap get(Item it, int size) {
        if (it.art == null || it.art.isEmpty()) return null;
        String url = it.art(Math.max(64, size * 2));
        String key = size + "|" + url;
        Bitmap px = cache.get(key);
        if (px != null || !loading.add(key)) return px;
        pool.submit(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                }
                if (b != null) cache.put(key, com.quiet.px.Fine.make(b, size, size));
            } catch (Exception e) {
                android.util.Log.w("QuietMusic", "cover failed: " + url + " " + e);
            } finally {
                loading.remove(key);
            }
        });
        return null;
    }

    /**
     * For things without artwork (many library playlists): a generated cover. A dithered two-tone
     * field from the palette, chosen by the title, with a small note in the corner.
     */
    static int[] placeholder(String title, int size) {
        int h = title.hashCode();
        int[][] pairs = {{Pal.COOL[2], Pal.COOL[5]}, {Pal.WARM[2], Pal.WARM[5]}, {Pal.FAR[2], Pal.FAR[6]},
                {Pal.MOSS_DK, Pal.MOSS_HI}, {0xFF2A1830, 0xFF6A3A60}};
        int[] p = pairs[Math.abs(h) % pairs.length];
        int[] px = new int[size * size];
        boolean diag = (h & 8) != 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float t = diag ? (x + y) / (2f * size) : y / (float) size;
                float d = Pal.BAYER[((y & 3) << 2) | (x & 3)] + 0.5f;
                px[y * size + x] = t > d ? p[0] : p[1];
            }
        // A little note, bottom right.
        int nx = size - 8, ny = size - 9;
        String[] note = {"..##", "..#.", "..#.", ".##.", "###.", ".#.."};
        for (int r = 0; r < note.length; r++)
            for (int c = 0; c < 4; c++) if (note[r].charAt(c) == '#') px[(ny + r) * size + nx + c] = Pal.TEXT;
        return px;
    }
}
