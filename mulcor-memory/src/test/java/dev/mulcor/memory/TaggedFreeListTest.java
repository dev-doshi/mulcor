package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaggedFreeListTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    @Test
    void popsEveryIndexOnceThenEmpty() {
        var list = new TaggedFreeList(mem, 100);
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 100; i++) assertTrue(seen.add(list.pop()));
        assertEquals(-1, list.pop());
        assertTrue(list.isEmpty());
        list.push(42);
        assertEquals(42, list.pop());
        assertThrows(IndexOutOfBoundsException.class, () -> list.push(100));
    }

    /** 8 threads churn pop/push; an index must never be held by two threads at once. */
    @Test
    void concurrentChurnNeverDoubleAllocates() throws Exception {
        final int threads = 8, capacity = 16, ops = 500_000;
        var list = new TaggedFreeList(mem, capacity);
        var held = new AtomicIntegerArray(capacity);
        var barrier = new CyclicBarrier(threads);
        var failure = new AtomicReference<String>();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = Thread.ofPlatform().start(() -> {
                try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                for (int i = 0; i < ops && failure.get() == null; i++) {
                    int idx = list.pop();
                    if (idx < 0) continue;
                    if (held.getAndSet(idx, 1) != 0) failure.compareAndSet(null, "index " + idx + " double-allocated");
                    held.set(idx, 0);
                    list.push(idx);
                }
            });
        }
        for (Thread t : ts) t.join();
        assertNull(failure.get());
        var seen = new HashSet<Integer>();
        for (int i = 0; i < capacity; i++) assertTrue(seen.add(list.pop()), "index lost or duplicated in list");
        assertEquals(-1, list.pop());
    }
}
