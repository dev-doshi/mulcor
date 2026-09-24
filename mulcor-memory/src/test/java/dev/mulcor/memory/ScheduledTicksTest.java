package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ScheduledTicksTest {
    record Ref(long due, int priority, long seq, long pos) {}

    @Test
    void packRoundTripsSignedCoordinates() {
        for (int[] p : new int[][] {{0, 0, 0}, {-1, -64, -1}, {33554431, 2047, -33554432}, {5, -2048, 7}}) {
            long v = ScheduledTicks.pack(p[0], p[1], p[2]);
            assertEquals(p[0], ScheduledTicks.x(v));
            assertEquals(p[1], ScheduledTicks.y(v));
            assertEquals(p[2], ScheduledTicks.z(v));
        }
    }

    @Test
    void ordersByDueThenPriorityThenInsertionAndDeduplicates() {
        try (var mem = new NativeMemory()) {
            var q = new ScheduledTicks(mem, 512);
            var ref = new PriorityQueue<Ref>((a, b) -> a.due != b.due ? Long.compare(a.due, b.due)
                    : a.priority != b.priority ? Integer.compare(a.priority, b.priority) : Long.compare(a.seq, b.seq));
            Set<Long> scheduled = new HashSet<>();
            var rnd = new SplittableRandom(5);
            long seq = 0;
            for (int round = 0; round < 200_000; round++) {
                if (rnd.nextInt(3) != 0 || ref.isEmpty()) {
                    long pos = ScheduledTicks.pack(rnd.nextInt(-40, 40), rnd.nextInt(-8, 8), rnd.nextInt(-40, 40));
                    long due = rnd.nextInt(50);
                    int prio = rnd.nextInt(-3, 4);
                    boolean expect = !scheduled.contains(pos) && ref.size() < 512;
                    assertEquals(expect, q.schedule(due, prio, pos));
                    if (expect) {
                        ref.add(new Ref(due, prio, seq++, pos));
                        scheduled.add(pos);
                    }
                } else {
                    Ref r = ref.poll();
                    assertEquals(r.due, q.peekDue());
                    assertEquals(r.priority, q.peekPriority());
                    assertEquals(r.pos, q.poll());
                    scheduled.remove(r.pos);
                    assertFalse(q.isScheduled(r.pos));
                }
                assertEquals(ref.size(), q.size());
            }
            for (long p : scheduled) assertTrue(q.isScheduled(p));
        }
    }
}
