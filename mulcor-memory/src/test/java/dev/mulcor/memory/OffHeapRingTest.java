package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OffHeapRingTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    private static boolean offerLong(OffHeapRing ring, long v) {
        long pos = ring.tryClaim();
        if (pos < 0) return false;
        ring.segment().set(ValueLayout.JAVA_LONG, ring.payloadOffset(pos), v);
        ring.publish(pos);
        return true;
    }

    private static long pollLong(OffHeapRing ring) {
        long pos = ring.tryAcquire();
        if (pos < 0) return Long.MIN_VALUE;
        long v = ring.segment().get(ValueLayout.JAVA_LONG, ring.payloadOffset(pos));
        ring.release(pos);
        return v;
    }

    @Test
    void rejectsNonPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new OffHeapRing(mem, 6, 8));
        assertThrows(IllegalArgumentException.class, () -> new OffHeapRing(mem, 1, 8), "Vyukov ring needs >= 2 slots");
    }

    @Test
    void fifoFullEmptyAndWrap() {
        var ring = new OffHeapRing(mem, 4, 8);
        assertEquals(Long.MIN_VALUE, pollLong(ring));
        for (long lap = 0; lap < 10; lap++) {
            for (int i = 0; i < 4; i++) assertTrue(offerLong(ring, lap * 10 + i));
            assertFalse(offerLong(ring, -1), "ring of 4 must reject a 5th record");
            assertEquals(4, ring.size());
            for (int i = 0; i < 4; i++) assertEquals(lap * 10 + i, pollLong(ring));
            assertEquals(Long.MIN_VALUE, pollLong(ring));
        }
    }

    @Test
    void offerPollCopyWholePayload() {
        var ring = new OffHeapRing(mem, 8, 20);
        MemorySegment src = mem.allocate(24);
        MemorySegment dst = mem.allocate(24);
        for (int i = 0; i < 20; i++) src.set(ValueLayout.JAVA_BYTE, i, (byte) (i + 1));
        assertTrue(ring.offer(src, 0, 20));
        assertTrue(ring.poll(dst, 0));
        for (int i = 0; i < 20; i++) assertEquals(i + 1, dst.get(ValueLayout.JAVA_BYTE, i));
        assertFalse(ring.poll(dst, 0));
    }

    @Test
    void drainRespectsMax() {
        var ring = new OffHeapRing(mem, 16, 8);
        for (int i = 0; i < 10; i++) offerLong(ring, i);
        long[] sum = {0};
        assertEquals(4, ring.drain((seg, off) -> sum[0] += seg.get(ValueLayout.JAVA_LONG, off), 4));
        assertEquals(0 + 1 + 2 + 3, sum[0]);
        assertEquals(6, ring.drain((seg, off) -> sum[0] += seg.get(ValueLayout.JAVA_LONG, off), 100));
        assertEquals(45, sum[0]);
    }

    /** 4 producers × 4 consumers: every record delivered exactly once and in per-producer order. */
    @Test
    void mpmcDeliversEveryRecordExactlyOnceInOrder() throws Exception {
        final int producers = 4, consumers = 4, perProducer = 250_000;
        var ring = new OffHeapRing(mem, 1024, 8);
        var start = new CyclicBarrier(producers + consumers);
        var producersDone = new CountDownLatch(producers);
        var failure = new AtomicReference<Throwable>();
        var stop = new AtomicBoolean();
        List<long[]> received = new ArrayList<>();
        List<int[]> receivedCount = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int p = 0; p < producers; p++) {
            final long pid = p;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (long seq = 0; seq < perProducer; seq++) {
                        while (!offerLong(ring, (pid << 32) | seq)) Thread.onSpinWait();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    producersDone.countDown();
                }
            }));
        }
        for (int c = 0; c < consumers; c++) {
            long[] buf = new long[producers * perProducer];
            int[] n = new int[1];
            received.add(buf);
            receivedCount.add(n);
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (;;) {
                        long v = pollLong(ring);
                        if (v != Long.MIN_VALUE) {
                            buf[n[0]++] = v;
                        } else if (stop.get() && ring.size() == 0) {
                            long last = pollLong(ring);
                            if (last == Long.MIN_VALUE) return;
                            buf[n[0]++] = last;
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        producersDone.await();
        stop.set(true);
        for (Thread t : threads) t.join();
        assertNull(failure.get());

        BitSet[] seen = new BitSet[producers];
        for (int p = 0; p < producers; p++) seen[p] = new BitSet(perProducer);
        long total = 0;
        for (int c = 0; c < consumers; c++) {
            long[] lastSeq = new long[producers];
            java.util.Arrays.fill(lastSeq, -1);
            long[] buf = received.get(c);
            for (int i = 0; i < receivedCount.get(c)[0]; i++) {
                int pid = (int) (buf[i] >>> 32);
                int seq = (int) buf[i];
                assertFalse(seen[pid].get(seq), "duplicate delivery " + pid + ":" + seq);
                seen[pid].set(seq);
                assertTrue(seq > lastSeq[pid], "per-producer order violated on consumer " + c);
                lastSeq[pid] = seq;
                total++;
            }
        }
        assertEquals((long) producers * perProducer, total, "lost records");
    }
}
