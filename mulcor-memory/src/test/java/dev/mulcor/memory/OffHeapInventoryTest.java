package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OffHeapInventoryTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    @Test
    void insertMergesThenFillsAndReportsLeftover() {
        var inv = new OffHeapInventory(mem, 1, 3);
        inv.set(0, 1, 5, 60);
        assertEquals(0, inv.insert(0, 5, 10));
        assertEquals(64, OffHeapInventory.count(inv.get(0, 1)));
        assertEquals(6, OffHeapInventory.count(inv.get(0, 0)));
        assertEquals(1000 - (58 + 64), inv.insert(0, 5, 1000));
        assertEquals(192, inv.total(0, 5));
        assertEquals(0, inv.insert(0, 0, 0));
    }

    @Test
    void takeAndMove() {
        var inv = new OffHeapInventory(mem, 2, 2);
        inv.set(0, 0, 7, 10);
        long taken = inv.take(0, 0, 4);
        assertEquals(7, OffHeapInventory.item(taken));
        assertEquals(4, OffHeapInventory.count(taken));
        assertEquals(6, OffHeapInventory.count(inv.get(0, 0)));
        assertEquals(0, inv.take(0, 1, 5));
        int[] overflow = new int[1];
        long moved = inv.move(0, 0, 1, 64, overflow);
        assertEquals(6, OffHeapInventory.count(moved));
        assertEquals(0, inv.get(0, 0), "emptied slot resets item id");
        assertEquals(6, inv.total(1, 7));
        assertEquals(0, overflow[0]);
    }

    /** 8 threads shuffle items randomly between 4 small inventories; the total must be conserved exactly. */
    @Test
    void concurrentMovesConserveItems() throws Exception {
        final int threads = 8, invs = 4, slots = 4, ops = 300_000;
        var inv = new OffHeapInventory(mem, invs, slots);
        long initial = 0;
        for (int i = 0; i < invs; i++) for (int s = 0; s < slots - 1; s++) {
            inv.set(i, s, 1 + (s % 2), 40);
            initial += 40;
        }
        var barrier = new CyclicBarrier(threads);
        var dropped = new AtomicLong();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final long seed = t;
            ts[t] = Thread.ofPlatform().start(() -> {
                var rnd = new SplittableRandom(seed);
                int[] overflow = new int[1];
                try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                for (int i = 0; i < ops; i++) {
                    overflow[0] = 0;
                    inv.move(rnd.nextInt(invs), rnd.nextInt(slots), rnd.nextInt(invs), 1 + rnd.nextInt(64), overflow);
                    dropped.addAndGet(overflow[0]);
                }
            });
        }
        for (Thread t : ts) t.join();
        long total = dropped.get();
        for (int i = 0; i < invs; i++) {
            total += inv.totalAll(i);
            for (int s = 0; s < slots; s++) {
                long w = inv.get(i, s);
                assertTrue(OffHeapInventory.count(w) >= 0 && OffHeapInventory.count(w) <= OffHeapInventory.MAX_STACK);
                assertEquals(OffHeapInventory.count(w) == 0, OffHeapInventory.item(w) == 0);
            }
        }
        assertEquals(initial, total, "items were duplicated or destroyed");
    }
}
