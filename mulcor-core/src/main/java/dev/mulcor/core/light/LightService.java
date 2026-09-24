package dev.mulcor.core.light;

import dev.mulcor.memory.BlockStorage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs the {@link LightEngine} on its own thread, off the tick's critical path, like vanilla's
 * {@code ThreadedLevelLightEngine}: block changes are re-lit asynchronously and client-visible light lags the
 * blocks by a little, never the other way round.
 *
 * <p>The engine's commit phase (single-threaded) is the only producer: it {@link #offer}s the positions regions
 * changed in the epoch into a single-producer/single-consumer ring (release-publish the tail, acquire it on the
 * consumer), which costs a few nanoseconds per position. If the ring is full the service falls back to one full
 * relight, which reaches the same result.
 *
 * <p>Correctness with blocks changing underneath: the light thread reads blocks with
 * {@link BlockStorage#getShared} under the external-read guard, so a read may be one change stale. Every change
 * queues a re-check at its own position, and a re-check re-derives light around that position from the current
 * blocks, so once the queue drains the light is the exact fixed point of the current blocks (vanilla relies on the
 * same property). {@link #awaitIdle()} waits for that point.
 *
 * <p>Region threads never touch this class; the light thread may park (it is not a simulation thread).
 */
public final class LightService implements AutoCloseable {
    private final LightEngine engine;
    private final BlockStorage blocks;
    private final int minY;
    private final long[] ring;
    private final int mask;
    private final AtomicLong produced = new AtomicLong(), consumed = new AtomicLong();
    /** Full relights requested, and the request count the last completed one covered. */
    private final AtomicLong relightRequests = new AtomicLong();
    private volatile long relightsCovered;
    private volatile boolean running = true;
    private final Thread thread;
    /** Positions re-lit, and full relights (overflow fallbacks). Written by the light thread. */
    public volatile long checks, fullRelights;

    public LightService(LightEngine engine, BlockStorage blocks, int capacity) {
        this.engine = engine;
        this.blocks = blocks;
        this.minY = blocks.minY();
        int cap = Integer.highestOneBit(Math.max(2, capacity));
        this.ring = new long[cap];
        this.mask = cap - 1;
        this.thread = Thread.ofPlatform().daemon().name("mulcor-light").unstarted(this::run);
        thread.start();
    }

    /** x:26 | z:26 | y:12 (y relative to the world floor). */
    public static long pack(int x, int y, int z, int minY) {
        return (long) x << 38 | (long) z << 12 | (y - minY);
    }

    /** Queue a light check at a packed position. Commit phase only (single producer). */
    public void offer(long packed) {
        long p = produced.getPlain();
        if (p - consumed.getAcquire() >= ring.length) {
            requestRelightAll();
            return;
        }
        ring[(int) (p & mask)] = packed;
        produced.setRelease(p + 1);
    }

    /** Recompute all light (a producer lost positions). Any thread. */
    public void requestRelightAll() {
        relightRequests.incrementAndGet();
    }

    private void run() {
        int idle = 0;
        while (running) {
            long requests = relightRequests.get();
            boolean full = requests != relightsCovered;
            long c = consumed.getPlain(), p = produced.getAcquire();
            if (!full && c == p) {
                if (++idle < 200) Thread.onSpinWait();
                else LockSupport.parkNanos(20_000);
                continue;
            }
            idle = 0;
            blocks.beginExternalRead();
            try {
                if (full) {
                    engine.relightAll(); // covers every change made before it started, queued or dropped
                    fullRelights++;
                } else {
                    for (long i = c; i < p; i++) {
                        long v = ring[(int) (i & mask)];
                        engine.onBlockChanged((int) (v >>> 38), (int) (v & 0xFFF) + minY, (int) (v >>> 12) & 0x3FF_FFFF);
                    }
                    checks += p - c;
                }
            } finally {
                blocks.endExternalRead();
            }
            consumed.setRelease(p);
            if (full) relightsCovered = requests;
        }
    }

    /** Spin until every offered check has been applied (tests, saving). Not for simulation threads. */
    public void awaitIdle() {
        while (relightRequests.get() != relightsCovered || consumed.getAcquire() != produced.getAcquire()) {
            if (!thread.isAlive()) throw new IllegalStateException("light thread died");
            Thread.onSpinWait();
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
