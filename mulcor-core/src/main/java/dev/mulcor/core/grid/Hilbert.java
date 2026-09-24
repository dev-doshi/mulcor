package dev.mulcor.core.grid;

/** Hilbert curve on an n×n grid (n a power of two). Consecutive indices are always 4-neighbours. */
public final class Hilbert {
    private Hilbert() {}

    public static long xy2d(int n, int x, int y) {
        long d = 0;
        for (int s = n / 2; s > 0; s /= 2) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += (long) s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    x = n - 1 - x;
                    y = n - 1 - y;
                }
                int t = x;
                x = y;
                y = t;
            }
        }
        return d;
    }

    /** Returns {@code x | y << 32}. */
    public static long d2xy(int n, long d) {
        int x = 0, y = 0;
        long t = d;
        for (int s = 1; s < n; s *= 2) {
            int rx = (int) (1 & (t / 2));
            int ry = (int) (1 & (t ^ rx));
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                int tmp = x;
                x = y;
                y = tmp;
            }
            x += s * rx;
            y += s * ry;
            t /= 4;
        }
        return (x & 0xFFFF_FFFFL) | ((long) y << 32);
    }

    public static int ceilPow2(int v) {
        int n = 1;
        while (n < v) n <<= 1;
        return n;
    }
}
