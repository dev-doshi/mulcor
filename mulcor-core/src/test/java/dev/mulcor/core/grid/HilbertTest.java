package dev.mulcor.core.grid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HilbertTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void bijectiveAndContinuous(int n) {
        var seen = new HashSet<Long>();
        long prev = -1;
        for (long d = 0; d < (long) n * n; d++) {
            long xy = Hilbert.d2xy(n, d);
            int x = (int) xy, y = (int) (xy >>> 32);
            assertTrue(x >= 0 && y >= 0 && x < n && y < n);
            assertTrue(seen.add(xy), "duplicate cell");
            assertEquals(d, Hilbert.xy2d(n, x, y), "xy2d inverts d2xy");
            if (prev >= 0) {
                int px = (int) prev, py = (int) (prev >>> 32);
                assertEquals(1, Math.abs(px - x) + Math.abs(py - y), "consecutive indices must be neighbours");
            }
            prev = xy;
        }
    }
}
