package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void burstThenRefillAtRate() {
        long[] now = {0};
        var b = new TokenBucket(10, 1000, () -> now[0]); // 1 token per ms
        for (int i = 0; i < 10; i++) assertTrue(b.tryAcquire(1));
        assertFalse(b.tryAcquire(1));
        assertEquals(1000, b.microsUntil(1), 1);
        now[0] += 500_000; // 0.5 ms: not yet
        assertFalse(b.tryAcquire(1));
        now[0] += 500_000;
        assertTrue(b.tryAcquire(1));
        assertFalse(b.tryAcquire(1));
        now[0] += 1_000_000_000L; // long idle: capped at capacity
        for (int i = 0; i < 10; i++) assertTrue(b.tryAcquire(1));
        assertFalse(b.tryAcquire(1));
        b.refund(3);
        assertTrue(b.tryAcquire(3));
    }

    @Test
    void fractionalRefillIsNotLost() {
        long[] now = {0};
        var b = new TokenBucket(1, 3, () -> now[0]); // one token every 333.3 ms
        assertTrue(b.tryAcquire(1));
        int granted = 0;
        for (int i = 0; i < 3000; i++) { // poll every 1 ms for 3 s
            now[0] += 1_000_000;
            if (b.tryAcquire(1)) granted++;
        }
        assertEquals(9, granted, 1, "3 tokens/s over 3 s");
    }

    /** 8 threads hammer one global bucket; it must never grant more than burst + rate × elapsed. */
    @Test
    void concurrentAcquireNeverOvergrants() throws Exception {
        int threads = 8;
        var b = new TokenBucket(1000, 200_000);
        var granted = new AtomicLong();
        var start = new CyclicBarrier(threads);
        long t0 = System.nanoTime();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = Thread.ofPlatform().start(() -> {
                try { start.await(); } catch (Exception e) { throw new RuntimeException(e); }
                long n = 0, end = System.nanoTime() + 300_000_000L;
                while (System.nanoTime() < end) if (b.tryAcquire(1)) n++;
                granted.addAndGet(n);
            });
        }
        for (Thread t : ts) t.join();
        double seconds = (System.nanoTime() - t0) / 1e9;
        long bound = 1000 + (long) (200_000 * seconds) + 1;
        System.out.printf("token bucket: granted %d in %.3fs (bound %d)%n", granted.get(), seconds, bound);
        assertTrue(granted.get() <= bound, "over-granted: " + granted.get() + " > " + bound);
        assertTrue(granted.get() >= 0.8 * 200_000 * 0.3, "under-granted: " + granted.get());
    }
}
