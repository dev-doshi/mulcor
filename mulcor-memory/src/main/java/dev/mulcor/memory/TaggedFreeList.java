package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.CACHE_LINE;
import static dev.mulcor.memory.Mem.INT;
import static dev.mulcor.memory.Mem.LONG;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Lock-free pool of integer indices {@code [0, capacity)}: a Treiber stack stored off-heap. The head word is
 * {@code tag:32 | top:32}, and the tag increments on every change to rule out ABA. An ABA failure would need
 * exactly 2^32 successful operations to land inside one pop's read-to-CAS window.
 */
public final class TaggedFreeList {
    private static final long HEAD = CACHE_LINE;
    private static final long NEXT = 2 * CACHE_LINE;
    private static final int EMPTY = -1;

    private final MemorySegment seg;
    private final int capacity;

    /** A full pool: every index in {@code [0, capacity)} is available. */
    public TaggedFreeList(NativeMemory memory, int capacity) {
        this(memory, capacity, true);
    }

    /** @param full whether the list starts holding every index, or starts empty (filled later by pushes) */
    public TaggedFreeList(NativeMemory memory, int capacity, boolean full) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.seg = memory.allocate(NEXT + (long) capacity * Integer.BYTES);
        for (int i = 0; i < capacity; i++) {
            INT.set(seg, next(i), i + 1 < capacity ? i + 1 : EMPTY);
        }
        LONG.set(seg, HEAD, pack(0, full ? 0 : EMPTY));
        VarHandle.releaseFence();
    }

    private static long next(int index) {
        return NEXT + (long) index * Integer.BYTES;
    }

    private static long pack(long tag, int top) {
        return (tag << 32) | (top & 0xFFFF_FFFFL);
    }

    public int capacity() {
        return capacity;
    }

    /** Take a free index, or {@code -1} if the pool is exhausted. */
    public int pop() {
        for (;;) {
            long head = (long) LONG.getAcquire(seg, HEAD);
            int top = (int) head;
            if (top == EMPTY) {
                return -1;
            }
            int below = (int) INT.getAcquire(seg, next(top));
            if (LONG.compareAndSet(seg, HEAD, head, pack((head >>> 32) + 1, below))) {
                return top;
            }
        }
    }

    /** Return an index. The caller must own it (obtained from {@link #pop()} and not yet pushed). */
    public void push(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(index);
        }
        for (;;) {
            long head = (long) LONG.getAcquire(seg, HEAD);
            INT.setRelease(seg, next(index), (int) head);
            if (LONG.compareAndSet(seg, HEAD, head, pack((head >>> 32) + 1, index))) {
                return;
            }
        }
    }

    public boolean isEmpty() {
        return (int) (long) LONG.getAcquire(seg, HEAD) == EMPTY;
    }
}
