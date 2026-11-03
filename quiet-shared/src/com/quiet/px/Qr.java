package com.quiet.px;

import java.nio.charset.StandardCharsets;

/**
 * A small QR encoder: byte mode, error correction level M, versions 1–6 (up to ~100 bytes).
 * Enough for a URL, and small enough to draw one module per few art pixels.
 */
public final class Qr {
    static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16};
    static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4};
    static final int[][] ALIGN = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}};

    final int size;
    final boolean[][] dark, fixed;

    /** Returns modules as [y][x], true = dark. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 6; v++) {
            int capBits = dataCodewords(v) * 8;
            if (4 + 8 + data.length * 8 <= capBits) return new Qr(v, data).dark;
        }
        throw new IllegalArgumentException("too long for a small QR");
    }

    static int rawModules(int v) {
        int r = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int n = v / 7 + 2;
            r -= (25 * n - 10) * n - 55;
        }
        return r;
    }

    static int dataCodewords(int v) {
        return rawModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v];
    }

    Qr(int v, byte[] text) {
        size = v * 4 + 17;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];

        // 1. Bit stream: mode, length, bytes, terminator, padding.
        int cap = dataCodewords(v);
        int[] bits = new int[cap * 8];
        int n = 0;
        n = put(bits, n, 0b0100, 4);
        n = put(bits, n, text.length, 8);
        for (byte b : text) n = put(bits, n, b & 255, 8);
        n += Math.min(4, cap * 8 - n);
        n = (n + 7) / 8 * 8;
        byte[] cw = new byte[cap];
        for (int i = 0; i < n && i < bits.length; i++) cw[i >> 3] |= bits[i] << (7 - (i & 7));
        for (int i = n / 8, pad = 0xEC; i < cap; i++, pad ^= 0xEC ^ 0x11) cw[i] = (byte) pad;

        // 2. Split into blocks, add Reed–Solomon codewords, interleave.
        int blocks = BLOCKS[v], ecc = ECC_PER_BLOCK[v], raw = rawModules(v) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[][] dat = new byte[blocks][], eccs = new byte[blocks][];
        byte[] gen = divisor(ecc);
        for (int b = 0, k = 0; b < blocks; b++) {
            int len = shortLen - ecc + (b < shortBlocks ? 0 : 1);
            dat[b] = new byte[len];
            System.arraycopy(cw, k, dat[b], 0, len);
            k += len;
            eccs[b] = remainder(dat[b], gen);
        }
        byte[] all = new byte[raw];
        int k = 0;
        for (int i = 0; i <= shortLen - ecc; i++)
            for (int b = 0; b < blocks; b++) if (i < dat[b].length) all[k++] = dat[b][i];
        for (int i = 0; i < ecc; i++)
            for (int b = 0; b < blocks; b++) all[k++] = eccs[b][i];

        // 3. Function patterns, data, best mask.
        functionPatterns(v);
        place(all);
        int best = 0, bestScore = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            mask(m);
            format(m);
            int s = penalty();
            if (s < bestScore) {
                bestScore = s;
                best = m;
            }
            mask(m);
        }
        mask(best);
        format(best);
    }

    static int put(int[] bits, int n, int value, int len) {
        for (int i = len - 1; i >= 0; i--) if (n < bits.length) bits[n++] = (value >>> i) & 1;
        return n;
    }

    void set(int x, int y, boolean d) {
        dark[y][x] = d;
        fixed[y][x] = true;
    }

    void functionPatterns(int v) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] a = ALIGN[v];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == a.length - 1) || (i == a.length - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        set(a[i] + dx, a[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        format(0);
    }

    void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
    }

    void format(int mask) {
        int data = mask;               // level M has format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    void place(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fixed[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    void mask(int m) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (fixed[y][x]) continue;
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv) dark[y][x] = !dark[y][x];
            }
    }

    /** Runs, 2×2 blocks and dark/light balance (finder-lookalike rule omitted). */
    int penalty() {
        int score = 0, darkCount = 0;
        for (int pass = 0; pass < 2; pass++)
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean prev = pass == 0 ? dark[a][b - 1] : dark[b - 1][a];
                    boolean cur = pass == 0 ? dark[a][b] : dark[b][a];
                    if (cur == prev) {
                        run++;
                    } else {
                        if (run >= 5) score += 3 + run - 5;
                        run = 1;
                    }
                }
                if (run >= 5) score += 3 + run - 5;
            }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) darkCount++;
                if (x < size - 1 && y < size - 1) {
                    boolean c = dark[y][x];
                    if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) score += 3;
                }
            }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return score + k * 10;
    }

    // ---------------------------------------------------------------- Reed–Solomon over GF(256)

    static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 255;
    }

    static byte[] divisor(int degree) {
        int[] r = new int[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = mul(r[j], root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        byte[] b = new byte[degree];
        for (int i = 0; i < degree; i++) b[i] = (byte) r[i];
        return b;
    }

    static byte[] remainder(byte[] data, byte[] div) {
        int[] r = new int[div.length];
        for (byte d : data) {
            int factor = (d & 255) ^ r[0];
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(div[i] & 255, factor);
        }
        byte[] b = new byte[r.length];
        for (int i = 0; i < r.length; i++) b[i] = (byte) r[i];
        return b;
    }
}
