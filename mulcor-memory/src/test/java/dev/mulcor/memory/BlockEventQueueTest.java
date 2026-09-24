package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class BlockEventQueueTest {
    record Ev(long pos, int block, int a, int b) {}

    @Test
    void fifoWithoutDuplicatesOfQueuedEvents() {
        try (var mem = new NativeMemory()) {
            var q = new BlockEventQueue(mem, 16);
            long p = ScheduledTicks.pack(1, 2, 3);
            assertTrue(q.add(p, 7, 0, 5));
            assertTrue(q.add(p, 7, 1, 5));
            assertTrue(q.add(p, 7, 0, 5)); // equal to a queued event: ignored
            assertEquals(2, q.size());
            assertTrue(q.poll());
            assertEquals(0, q.a());
            assertTrue(q.add(p, 7, 0, 5)); // no longer queued: added again, at the tail
            assertTrue(q.poll());
            assertEquals(1, q.a());
            assertTrue(q.poll());
            assertEquals(0, q.a());
            assertEquals(p, q.pos());
            assertEquals(7, q.block());
            assertEquals(5, q.b());
            assertFalse(q.poll());
        }
    }

    /** Against a LinkedHashSet (vanilla's ObjectLinkedOpenHashSet semantics) under random adds and polls. */
    @Test
    void matchesLinkedHashSetUnderChurn() {
        try (var mem = new NativeMemory()) {
            var q = new BlockEventQueue(mem, 256);
            var ref = new LinkedHashSet<Ev>();
            var rnd = new SplittableRandom(7);
            for (int i = 0; i < 100_000; i++) {
                if (rnd.nextInt(5) < 3 && ref.size() < 256) {
                    var ev = new Ev(ScheduledTicks.pack(rnd.nextInt(4), rnd.nextInt(4), rnd.nextInt(4)), rnd.nextInt(3),
                            rnd.nextInt(3), rnd.nextInt(6));
                    assertTrue(q.add(ev.pos, ev.block, ev.a, ev.b));
                    ref.add(ev);
                } else if (!ref.isEmpty()) {
                    Iterator<Ev> it = ref.iterator();
                    Ev first = it.next();
                    it.remove();
                    assertTrue(q.poll());
                    assertEquals(first, new Ev(q.pos(), q.block(), q.a(), q.b()));
                }
                assertEquals(ref.size(), q.size());
            }
        }
    }

    @Test
    void fullQueueRejects() {
        try (var mem = new NativeMemory()) {
            var q = new BlockEventQueue(mem, 16); // 32 records
            for (int i = 0; i < 32; i++) assertTrue(q.add(ScheduledTicks.pack(i, 0, 0), 1, 0, 0));
            assertFalse(q.add(ScheduledTicks.pack(99, 0, 0), 1, 0, 0));
            assertTrue(q.add(ScheduledTicks.pack(0, 0, 0), 1, 0, 0), "a duplicate is not an overflow");
        }
    }
}
