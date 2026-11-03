package com.quiet.px;

import android.graphics.Bitmap;

/**
 * Pictures that need to be recognisable — posters, album covers — drawn finer than the world:
 * DENSITY pixels for every pixel of the 320×180 grid. Still a touch of pixel texture (32 levels a
 * channel, a light ordered dither), but a film or an album reads at a glance.
 */
public final class Fine {
    public static final int DENSITY = 3;

    /** Crop to the w:h shape (centred), scale to w×h grid pixels at DENSITY, and give it its texture. */
    public static Bitmap make(Bitmap b, int w, int h) {
        int fw = w * DENSITY, fh = h * DENSITY;
        float want = w / (float) h, have = b.getWidth() / (float) b.getHeight();
        int cw = b.getWidth(), ch = b.getHeight(), cx = 0, cy = 0;
        if (have > want) {
            cw = Math.round(ch * want);
            cx = (b.getWidth() - cw) / 2;
        } else {
            ch = Math.round(cw / want);
            cy = (b.getHeight() - ch) / 2;
        }
        Bitmap s = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, cx, cy, cw, ch), fw, fh, true);
        int[] px = new int[fw * fh];
        s.getPixels(px, 0, fw, 0, 0, fw, fh);
        for (int y = 0; y < fh; y++)
            for (int x = 0; x < fw; x++) {
                int i = y * fw + x, c = px[i];
                float d = Pal.BAYER[((y & 3) << 2) | (x & 3)];
                px[i] = 0xFF000000 | (q((c >> 16) & 255, d) << 16) | (q((c >> 8) & 255, d) << 8) | q(c & 255, d);
            }
        Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, fw, 0, 0, fw, fh);
        return out;
    }

    static int q(int v, float d) {
        int l = Math.round(v / 255f * 31f + d * 0.5f);
        return Math.max(0, Math.min(31, l)) * 255 / 31;
    }
}
