package dev.mulcor.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A region's pending block events, like vanilla's {@code ServerLevel.blockEvents}: an
 * {@code ObjectLinkedOpenHashSet<BlockEventData>}, i.e. a FIFO in which an event equal to one still queued (same
 * position, block type and both parameters) is not added again. Events are taken from the head
 * ({@code removeFirst}); an event added while the queue is being drained runs in the same drain.
 *
 * <p>Off-heap: a ring of fixed records plus an open-addressing set (linear probing, backward-shift deletion) over the
 * queued events. Fixed capacity: {@link #add} returns false when full. Confined to the thread running the owning
 * region (or the commit phase). No allocation.
 */
public final class BlockEventQueue {
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    /** Ring record: pos (long), block, a, b, pad. */
    private static final long REC = 24;
    /** Set slot: pos (long), key = packed (block, a, b) + 1 (int; 0 = empty), pad. */
    private static final long SLOT = 16;

    private final MemorySegment ring, set;
    private final int ringMask, setMask;
    private long head, tail;

    // The event last removed by poll().
    private long pos;
    private int block, a, b;

    /** {@code capacity} is rounded up to a power of two. */
    public BlockEventQueue(NativeMemory memory, int capacity) {
        int n = Integer.highestOneBit(Math.max(16, capacity - 1)) << 1;
        this.ring = memory.allocate(n * REC);
        this.ringMask = n - 1;
        this.set = memory.allocate(2L * n * SLOT);
        this.setMask = 2 * n - 1;
    }

    public int size() { return (int) (tail - head); }
    public boolean isEmpty() { return head == tail; }

    /** Block type ids and parameters fit: block below 2^20, a and b below 2^5 (vanilla uses 0-2 and a direction). */
    private static int key(int block, int a, int b) {
        return (block << 10 | (a & 31) << 5 | (b & 31)) + 1;
    }

    private static int hash(long pos, int key) {
        long h = (pos ^ (long) key * 0x9E3779B97F4A7C15L) * 0xC2B2AE3D27D4EB4FL;
        return (int) (h ^ (h >>> 29));
    }

    /**
     * {@code ServerLevel.blockEvent(pos, block, a, b)}: queue the event unless an equal one is queued. Returns false
     * only when the queue is full (the event is lost).
     */
    public boolean add(long pos, int block, int a, int b) {
        int k = key(block, a, b);
        int i = hash(pos, k) & setMask;
        while (true) {
            long off = (long) i * SLOT;
            int sk = set.get(I, off + 8);
            if (sk == 0) break;
            if (sk == k && set.get(L, off) == pos) return true; // already queued
            i = (i + 1) & setMask;
        }
        if (size() > ringMask) return false;
        long off = (long) i * SLOT;
        set.set(L, off, pos);
        set.set(I, off + 8, k);
        long r = (tail & ringMask) * REC;
        ring.set(L, r, pos);
        ring.set(I, r + 8, block);
        ring.set(I, r + 12, a);
        ring.set(I, r + 16, b);
        tail++;
        return true;
    }

    /** {@code removeFirst}: take the oldest event; read it with {@link #pos()}, {@link #block()}, {@link #a()}, {@link #b()}. */
    public boolean poll() {
        if (head == tail) return false;
        long r = (head & ringMask) * REC;
        pos = ring.get(L, r);
        block = ring.get(I, r + 8);
        a = ring.get(I, r + 12);
        b = ring.get(I, r + 16);
        head++;
        removeFromSet(pos, key(block, a, b));
        return true;
    }

    public long pos() { return pos; }
    public int block() { return block; }
    public int a() { return a; }
    public int b() { return b; }

    private void removeFromSet(long p, int k) {
        int i = hash(p, k) & setMask;
        while (true) {
            long off = (long) i * SLOT;
            int sk = set.get(I, off + 8);
            if (sk == 0) return;
            if (sk == k && set.get(L, off) == p) break;
            i = (i + 1) & setMask;
        }
        // Backward-shift deletion: pull later entries of the probe chain into the hole.
        int hole = i;
        int j = i;
        while (true) {
            j = (j + 1) & setMask;
            long off = (long) j * SLOT;
            int sk = set.get(I, off + 8);
            if (sk == 0) break;
            int home = hash(set.get(L, off), sk) & setMask;
            // Move j into the hole unless its home lies cyclically in (hole, j].
            boolean stays = hole <= j ? (home > hole && home <= j) : (home > hole || home <= j);
            if (stays) continue;
            long h = (long) hole * SLOT;
            set.set(L, h, set.get(L, off));
            set.set(I, h + 8, sk);
            hole = j;
        }
        set.set(I, (long) hole * SLOT + 8, 0);
    }

    /** Move every queued event into {@code to}, in order (region merge). Events that do not fit in {@code to} stay. */
    public void moveTo(BlockEventQueue to) {
        int n = size();
        for (int i = 0; i < n; i++) {
            poll();
            if (!to.add(pos, block, a, b)) add(pos, block, a, b);
        }
    }
}
