package dev.mulcor.net;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Lock-free token bucket. The whole state is one {@code long}, {@code tokens:24 | lastRefillMicros:40}, updated
 * by CAS, so any number of Netty event-loop threads can share one bucket (a global rate limit) without blocking.
 * Refill is computed lazily from elapsed time; the clock origin is the bucket's creation time.
 */
public final class TokenBucket {
    private static final VarHandle STATE;
    static {
        try {
            STATE = MethodHandles.lookup().findVarHandle(TokenBucket.class, "state", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static final int MAX_CAPACITY = (1 << 24) - 1;
    private static final long TIME_MASK = (1L << 40) - 1;

    /** Time source in nanoseconds, injectable for deterministic tests. */
    @FunctionalInterface
    public interface Clock {
        long nanos();
    }

    private final int capacity;
    private final long permitsPerSecond;
    private final Clock clock;
    private final long origin;
    @SuppressWarnings("unused") // via STATE
    private volatile long state;

    public TokenBucket(int capacity, long permitsPerSecond) {
        this(capacity, permitsPerSecond, System::nanoTime);
    }

    public TokenBucket(int capacity, long permitsPerSecond, Clock clock) {
        if (capacity <= 0 || capacity > MAX_CAPACITY || permitsPerSecond <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.permitsPerSecond = permitsPerSecond;
        this.clock = clock;
        this.origin = clock.nanos();
        this.state = pack(capacity, 0);
    }

    private static long pack(long tokens, long micros) {
        return (tokens << 40) | (micros & TIME_MASK);
    }

    private long nowMicros() {
        return (clock.nanos() - origin) / 1000;
    }

    /** Take {@code n} tokens if available. Never blocks. */
    public boolean tryAcquire(int n) {
        long now = nowMicros();
        for (;;) {
            long s = (long) STATE.getAcquire(this);
            long tokens = s >>> 40, last = s & TIME_MASK;
            long elapsed = Math.max(0, now - last);
            long refill = elapsed * permitsPerSecond / 1_000_000;
            long available = Math.min(capacity, tokens + refill);
            // Advance the refill clock only by the time actually converted into tokens, so no fraction is lost.
            long newLast = available == capacity ? now : last + refill * 1_000_000 / permitsPerSecond;
            if (available < n) {
                if (refill > 0) STATE.compareAndSet(this, s, pack(available, newLast));
                return false;
            }
            if (STATE.compareAndSet(this, s, pack(available - n, newLast))) return true;
        }
    }

    /** Give back tokens (for example when a downstream stage rejected the work they paid for). */
    public void refund(int n) {
        for (;;) {
            long s = (long) STATE.getAcquire(this);
            long tokens = Math.min(capacity, (s >>> 40) + n);
            if (STATE.compareAndSet(this, s, pack(tokens, s & TIME_MASK))) return;
        }
    }

    /** Microseconds until {@code n} tokens will be available (0 if they are available now). */
    public long microsUntil(int n) {
        long s = (long) STATE.getAcquire(this);
        long tokens = Math.min(capacity, (s >>> 40) + Math.max(0, nowMicros() - (s & TIME_MASK)) * permitsPerSecond / 1_000_000);
        return tokens >= n ? 0 : (n - tokens) * 1_000_000 / permitsPerSecond + 1;
    }

    public int capacity() { return capacity; }
}
