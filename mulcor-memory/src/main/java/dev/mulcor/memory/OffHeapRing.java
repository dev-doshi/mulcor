package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.CACHE_LINE;
import static dev.mulcor.memory.Mem.LONG;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Bounded multi-producer / multi-consumer ring of fixed-size records, stored entirely off-heap
 * (Vyukov's sequence-per-slot algorithm).
 *
 * <p>Layout: {@code [pad][head][pad][tail][pad][slot 0]...[slot n-1]}. Each slot is an 8-byte sequence word
 * followed by the payload. Head and tail are on separate {@link Mem#CACHE_LINE}s.
 *
 * <p>Protocol:
 * <ul>
 *   <li>Producers {@link #tryClaim()}, write the payload at {@link #payloadOffset(long)} in {@link #segment()},
 *       then {@link #publish(long)}.</li>
 *   <li>Consumers {@link #tryAcquire()}, read, then {@link #release(long)}.</li>
 * </ul>
 * Nothing allocates on the heap. A failed claim means the ring is full, and the caller decides what to do
 * (for example apply backpressure). No operation ever blocks or parks.
 *
 * <p>Progress: every CAS is lock-free. However, a producer preempted between claim and publish delays
 * consumers at that one slot; they see "empty" and retry later. Region inboxes tolerate this because
 * whatever is left over is simply drained in the next epoch.
 */
public final class OffHeapRing {
    private static final long HEAD = CACHE_LINE;
    private static final long TAIL = 2 * CACHE_LINE;
    private static final long SLOTS = 3 * CACHE_LINE;
    private static final long SEQ_BYTES = Long.BYTES;

    /** Callback for {@link #drain}; implementations must be allocation-free. */
    @FunctionalInterface
    public interface SlotHandler {
        void onSlot(MemorySegment segment, long payloadOffset);
    }

    private final MemorySegment seg;
    private final long mask;
    private final int capacity;
    private final int payloadBytes;
    private final long stride;

    public OffHeapRing(NativeMemory memory, int capacity, int payloadBytes) {
        // With one slot, a published sequence (pos + 1) equals the next producer's expected sequence, so a second
        // producer would overwrite an unconsumed record. The algorithm needs at least two slots.
        if (capacity < 2 || !Mem.isPowerOfTwo(capacity)) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("payloadBytes must be positive: " + payloadBytes);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.payloadBytes = payloadBytes;
        this.stride = SEQ_BYTES + Mem.align(payloadBytes, Long.BYTES);
        this.seg = memory.allocate(SLOTS + stride * capacity + CACHE_LINE);
        for (long i = 0; i < capacity; i++) {
            LONG.set(seg, seqOffset(i), i);
        }
        VarHandle.releaseFence();
    }

    public MemorySegment segment() {
        return seg;
    }

    public int capacity() {
        return capacity;
    }

    public int payloadBytes() {
        return payloadBytes;
    }

    private long seqOffset(long pos) {
        return SLOTS + (pos & mask) * stride;
    }

    /** Byte offset of the payload for a claimed or acquired position. */
    public long payloadOffset(long pos) {
        return seqOffset(pos) + SEQ_BYTES;
    }

    /** Producer: reserve a slot. Returns its position, or {@code -1} if the ring is full. */
    public long tryClaim() {
        long pos = (long) LONG.getOpaque(seg, TAIL);
        for (;;) {
            long seq = (long) LONG.getAcquire(seg, seqOffset(pos));
            long dif = seq - pos;
            if (dif == 0) {
                if (LONG.weakCompareAndSet(seg, TAIL, pos, pos + 1)) {
                    return pos;
                }
            } else if (dif < 0) {
                return -1;
            }
            pos = (long) LONG.getOpaque(seg, TAIL);
        }
    }

    /** Producer: make a claimed slot visible to consumers. */
    public void publish(long pos) {
        LONG.setRelease(seg, seqOffset(pos), pos + 1);
    }

    /** Consumer: take the next published slot. Returns its position, or {@code -1} if none is ready. */
    public long tryAcquire() {
        long pos = (long) LONG.getOpaque(seg, HEAD);
        for (;;) {
            long seq = (long) LONG.getAcquire(seg, seqOffset(pos));
            long dif = seq - (pos + 1);
            if (dif == 0) {
                if (LONG.weakCompareAndSet(seg, HEAD, pos, pos + 1)) {
                    return pos;
                }
            } else if (dif < 0) {
                return -1;
            }
            pos = (long) LONG.getOpaque(seg, HEAD);
        }
    }

    /** Consumer: hand an acquired slot back to producers. */
    public void release(long pos) {
        LONG.setRelease(seg, seqOffset(pos), pos + capacity);
    }

    /** Copy {@code bytes} (at most {@link #payloadBytes()}) from {@code src} into a new record. */
    public boolean offer(MemorySegment src, long srcOffset, long bytes) {
        long pos = tryClaim();
        if (pos < 0) {
            return false;
        }
        MemorySegment.copy(src, srcOffset, seg, payloadOffset(pos), bytes);
        publish(pos);
        return true;
    }

    /** Copy the next record's full payload into {@code dst}. */
    public boolean poll(MemorySegment dst, long dstOffset) {
        long pos = tryAcquire();
        if (pos < 0) {
            return false;
        }
        MemorySegment.copy(seg, payloadOffset(pos), dst, dstOffset, payloadBytes);
        release(pos);
        return true;
    }

    /** Consume up to {@code max} records in place. Returns how many were handled. */
    public int drain(SlotHandler handler, int max) {
        int n = 0;
        while (n < max) {
            long pos = tryAcquire();
            if (pos < 0) {
                break;
            }
            handler.onSlot(seg, payloadOffset(pos));
            release(pos);
            n++;
        }
        return n;
    }

    /** Approximate number of claimed-but-not-released records. */
    public int size() {
        long tail = (long) LONG.getOpaque(seg, TAIL);
        long head = (long) LONG.getOpaque(seg, HEAD);
        return (int) Math.max(0, Math.min(capacity, tail - head));
    }
}
