package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ScheduledTicksTest {
    record Ref(long due, int priority, long seq, long pos, int block) {}
    record Key(long pos, int block) {}

    @Test
    void packRoundTripsSignedCoordinates() {
        for (int[] p : new int[][] {{0, 0, 0}, {-1, -64, -1}, {33554431, 2047, -33554432}, {5, -2048, 7}}) {
            long v = ScheduledTicks.pack(p[0], p[1], p[2]);
            assertEquals(p[0], ScheduledTicks.x(v));
            assertEquals(p[1], ScheduledTicks.y(v));
            assertEquals(p[2], ScheduledTicks.z(v));
        }
    }

    /** Vanilla LevelTicks order (due, priority, scheduling order); one pending tick per (position, block type). */
    @Test
    void ordersByDueThenPriorityThenInsertionAndDeduplicatesPerBlock() {
        try (var mem = new NativeMemory()) {
            var q = new ScheduledTicks(mem, 512);
            var ref = new PriorityQueue<Ref>((a, b) -> a.due != b.due ? Long.compare(a.due, b.due)
                    : a.priority != b.priority ? Integer.compare(a.priority, b.priority) : Long.compare(a.seq, b.seq));
            Map<Key, Long> scheduled = new HashMap<>();
            var rnd = new SplittableRandom(5);
            long seq = 0;
            for (int round = 0; round < 200_000; round++) {
                if (rnd.nextInt(3) != 0 || ref.isEmpty()) {
                    long pos = ScheduledTicks.pack(rnd.nextInt(-40, 40), rnd.nextInt(-8, 8), rnd.nextInt(-40, 40));
                    int block = rnd.nextInt(3);
                    long due = rnd.nextInt(50);
                    int prio = rnd.nextInt(-3, 4);
                    Key key = new Key(pos, block);
                    boolean expect = !scheduled.containsKey(key) && ref.size() < 512;
                    assertEquals(expect, q.schedule(due, prio, pos, block));
                    if (expect) {
                        ref.add(new Ref(due, prio, seq++, pos, block));
                        scheduled.put(key, due);
                    }
                } else {
                    Ref r = ref.poll();
                    assertEquals(r.due, q.peekDue());
                    assertEquals(r.priority, q.peekPriority());
                    assertEquals(r.block, q.peekBlock());
                    assertEquals(r.pos, q.poll());
                    scheduled.remove(new Key(r.pos, r.block));
                    assertFalse(q.isScheduled(r.pos, r.block));
                    assertEquals(Long.MAX_VALUE, q.due(r.pos, r.block));
                }
                assertEquals(ref.size(), q.size());
            }
            for (var e : scheduled.entrySet()) {
                assertTrue(q.isScheduled(e.getKey().pos, e.getKey().block));
                assertEquals(e.getValue(), q.due(e.getKey().pos, e.getKey().block));
            }
        }
    }

    /** Every packed position is a valid key, including the all-ones ones (no reserved empty marker). */
    @Test
    void extremePositionsAreDistinctKeys() {
        try (var mem = new NativeMemory()) {
            var q = new ScheduledTicks(mem, 16);
            long minusOne = ScheduledTicks.pack(-1, -1, -1), minX = ScheduledTicks.pack(-33554432, 0, 0);
            long zero = ScheduledTicks.pack(0, 0, 0);
            assertTrue(q.schedule(3, 0, minusOne, 0));
            assertTrue(q.schedule(2, 0, minX, 0));
            assertTrue(q.schedule(1, 0, zero, 0));
            assertTrue(q.schedule(1, 0, zero, 7), "same position, other block type");
            assertFalse(q.schedule(9, 0, minusOne, 0));
            assertEquals(zero, q.poll());
            assertEquals(zero, q.poll());
            assertEquals(minX, q.poll());
            assertTrue(q.isScheduled(minusOne, 0));
            assertFalse(q.isScheduled(minX, 0));
            assertEquals(minusOne, q.poll());
            assertEquals(0, q.size());
        }
    }
}
