package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BlockStorageTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    @Test
    void getSetAndBounds() {
        var s = new BlockStorage(mem, 4, 4, -64, 8, 64);
        assertEquals(0, s.get(5, 10, 5));
        assertEquals(0, s.set(5, 10, 5, 7));
        assertEquals(7, s.get(5, 10, 5));
        assertEquals(7, s.set(5, 10, 5, 65535));
        assertEquals(65535, s.get(5, 10, 5));
        assertEquals(0, s.get(-1, 0, 0));
        assertEquals(0, s.get(0, 64, 0), "above world is air");
        assertEquals(BlockStorage.FAILED, s.set(64, 0, 0, 1));
        assertEquals(BlockStorage.FAILED, s.set(0, -65, 0, 1));
        assertEquals(BlockStorage.FAILED, s.set(0, 0, 0, 70000));
        s.set(0, -64, 0, 3);
        s.set(63, 63, 63, 4);
        assertEquals(3, s.get(0, -64, 0));
        assertEquals(4, s.get(63, 63, 63));
    }

    @Test
    void airSectionsHoldNoMemoryAndAreReclaimed() {
        var s = new BlockStorage(mem, 2, 2, 0, 4, 8);
        assertEquals(0, s.allocatedSections());
        assertEquals(0, s.set(1, 1, 1, 0), "writing air to an air section allocates nothing");
        assertEquals(0, s.allocatedSections());
        s.set(1, 1, 1, 5);
        s.set(2, 2, 2, 6);
        assertEquals(1, s.allocatedSections());
        assertEquals(2, s.nonAirCount(s.sectionRef(0, 0, 0)));
        s.set(1, 1, 1, 0);
        assertEquals(1, s.allocatedSections());
        s.set(2, 2, 2, 0);
        assertEquals(0, s.allocatedSections(), "all-air section is unmapped immediately");
        assertEquals(1, s.reclaim(), "and recycled at the next reclaim");
        // A recycled section must come back zeroed.
        s.set(20, 20, 20, 9);
        for (int y = 16; y < 32; y++) for (int z = 16; z < 32; z++) for (int x = 16; x < 32; x++) {
            assertEquals(x == 20 && y == 20 && z == 20 ? 9 : 0, s.get(x, y, z));
        }
    }

    @Test
    void sharedReadSeesOwnerWrites() {
        var s = new BlockStorage(mem, 1, 1, 0, 1, 1);
        assertEquals(0, s.getShared(3, 3, 3));
        s.set(3, 3, 3, 12);
        assertEquals(12, s.getShared(3, 3, 3));
        assertEquals(0, s.getShared(-1, 3, 3));
    }

    @Test
    void poolExhaustionFailsCleanly() {
        var s = new BlockStorage(mem, 4, 1, 0, 1, 2);
        assertEquals(0, s.set(0, 0, 0, 1));
        assertEquals(0, s.set(16, 0, 0, 1));
        assertEquals(BlockStorage.FAILED, s.set(32, 0, 0, 1));
        assertEquals(0, s.get(32, 0, 0));
        s.set(0, 0, 0, 0);
        assertEquals(BlockStorage.FAILED, s.set(32, 0, 0, 1), "retired section is not reusable before reclaim");
        s.reclaim();
        assertEquals(0, s.set(32, 0, 0, 1), "reclaimed section is reusable");
    }

    /** Threads own disjoint chunks (like regions) and allocate sections from the shared pool concurrently. */
    @Test
    void concurrentOwnersShareThePool() throws Exception {
        final int threads = 8;
        var s = new BlockStorage(mem, threads, 1, 0, 4, threads * 4);
        var barrier = new CyclicBarrier(threads);
        var failure = new AtomicReference<Throwable>();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int cx = t;
            ts[t] = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    for (int round = 0; round < 200; round++) {
                        for (int y = 0; y < 64; y += 3) for (int z = 0; z < 16; z++) {
                            s.set(cx * 16 + (z & 15), y, z, 1 + cx);
                        }
                        for (int y = 0; y < 64; y += 3) for (int z = 0; z < 16; z++) {
                            assertEquals(1 + cx, s.get(cx * 16 + (z & 15), y, z));
                            s.set(cx * 16 + (z & 15), y, z, 0);
                        }
                        barrier.await(); // epoch barrier: nobody is touching blocks while one thread reclaims
                        if (cx == 0) s.reclaim();
                        barrier.await();
                    }
                    s.set(cx * 16, 0, 0, 100 + cx);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }
        for (Thread t : ts) t.join();
        assertNull(failure.get());
        assertEquals(threads, s.allocatedSections());
        for (int t = 0; t < threads; t++) assertEquals(100 + t, s.get(t * 16, 0, 0));
    }
}
