package dev.mulcor.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mulcor.memory.ScheduledTicks;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link ComparatorOutputs} against a {@code HashMap}: set, overwrite, remove (0), growth, drain. */
class ComparatorOutputsTest {
    @Test
    void matchesHashMap() {
        ComparatorOutputs m = new ComparatorOutputs();
        Map<Long, Integer> ref = new HashMap<>();
        Random rnd = new Random(42);
        for (int op = 0; op < 200_000; op++) {
            // A small coordinate range forces collisions, long probe runs and backward shifts.
            long pos = ScheduledTicks.pack(rnd.nextInt(40), rnd.nextInt(8) - 4, rnd.nextInt(40));
            int v = rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(15);
            m.set(pos, v);
            if (v == 0) ref.remove(pos);
            else ref.put(pos, v);
            if (op % 1000 == 0) check(m, ref);
        }
        check(m, ref);

        ComparatorOutputs to = new ComparatorOutputs();
        m.drainInto(to);
        assertEquals(0, m.size());
        check(to, ref);
    }

    private static void check(ComparatorOutputs m, Map<Long, Integer> ref) {
        assertEquals(ref.size(), m.size());
        for (int x = 0; x < 40; x++) {
            for (int y = -4; y < 4; y++) {
                for (int z = 0; z < 40; z++) {
                    long p = ScheduledTicks.pack(x, y, z);
                    assertEquals(ref.getOrDefault(p, 0).intValue(), m.get(p), "at " + x + "," + y + "," + z);
                    assertEquals(m.get(p), m.getShared(p));
                }
            }
        }
    }
}
