package dev.mulcor.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A region's scheduled block ticks: an off-heap binary min-heap ordered like vanilla's tick list, by
 * (due epoch, priority, insertion order), plus an open-addressing set of the scheduled positions so a block is
 * never scheduled twice (vanilla's {@code hasScheduledTick} check).
 *
 * <p>Positions are packed longs (see {@link #pack}). Fixed capacity: {@link #schedule} returns false when full.
 * Confined to the thread running the owning region, like its entity table. No allocation.
 */
public final class ScheduledTicks {
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    private static final long ENTRY = 24; // due, order, pos
    private static final long EMPTY = 0;

    private final int capacity;
    private final MemorySegment heap;
    private final MemorySegment set;
    private final int setMask;
    private int size;
    private long seq;
    /** pack(-1, -1, -1) == -1 maps to key 0, the empty marker, so that one position is tracked by a flag. */
    private boolean zeroKey;

    public ScheduledTicks(NativeMemory memory, int capacity) {
        this.capacity = capacity;
        this.heap = memory.allocate(capacity * ENTRY);
        int slots = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) << 1;
        this.set = memory.allocate((long) slots * Long.BYTES);
        this.setMask = slots - 1;
    }

    /** Block position as {@code x:26 | z:26 | y:12} (signed fields), the vanilla packed-position layout. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long pos) { return (int) (pos >> 38); }
    public static int y(long pos) { return (int) (pos << 52 >> 52); }
    public static int z(long pos) { return (int) (pos << 26 >> 38); }

    public int size() { return size; }
    public int capacity() { return capacity; }

    /** Due epoch of the next tick, or {@link Long#MAX_VALUE} if none. */
    public long peekDue() {
        return size == 0 ? Long.MAX_VALUE : heap.get(L, 0);
    }

    public long peekPos() {
        return heap.get(L, 16);
    }

    public int peekPriority() {
        return (int) (heap.get(L, 8) >> 48) - 8;
    }

    public boolean isScheduled(long pos) {
        return contains(pos);
    }

    /**
     * Schedule a tick for {@code pos} at epoch {@code due}. Lower priority values run first among ticks due in the
     * same epoch (vanilla: -3 extremely high … 3 extremely low). Returns false if the position already has a tick
     * scheduled or the queue is full.
     */
    public boolean schedule(long due, int priority, long pos) {
        if (size == capacity || contains(pos)) return false;
        insertKey(pos);
        long order = ((long) (priority + 8) << 48) | (seq++ & 0xFFFF_FFFF_FFFFL);
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            long pOff = parent * ENTRY;
            if (!less(due, order, heap.get(L, pOff), heap.get(L, pOff + 8))) break;
            copy(parent, i);
            i = parent;
        }
        long off = i * ENTRY;
        heap.set(L, off, due);
        heap.set(L, off + 8, order);
        heap.set(L, off + 16, pos);
        return true;
    }

    /** Remove the next tick and return its position. The queue must not be empty. */
    public long poll() {
        long pos = heap.get(L, 16);
        removeKey(pos);
        int last = --size;
        if (last > 0) {
            long lOff = last * ENTRY;
            long due = heap.get(L, lOff), order = heap.get(L, lOff + 8), p = heap.get(L, lOff + 16);
            int i = 0;
            for (;;) {
                int c = 2 * i + 1;
                if (c >= last) break;
                if (c + 1 < last && less(heap.get(L, (c + 1) * ENTRY), heap.get(L, (c + 1) * ENTRY + 8),
                        heap.get(L, c * ENTRY), heap.get(L, c * ENTRY + 8))) c++;
                if (!less(heap.get(L, c * ENTRY), heap.get(L, c * ENTRY + 8), due, order)) break;
                copy(c, i);
                i = c;
            }
            long off = i * ENTRY;
            heap.set(L, off, due);
            heap.set(L, off + 8, order);
            heap.set(L, off + 16, p);
        }
        return pos;
    }

    public void clear() {
        size = 0;
        zeroKey = false;
        set.fill((byte) 0);
    }

    private static boolean less(long due1, long order1, long due2, long order2) {
        return due1 < due2 || (due1 == due2 && order1 < order2);
    }

    private void copy(int from, int to) {
        MemorySegment.copy(heap, from * ENTRY, heap, to * ENTRY, ENTRY);
    }

    // ---- position set: linear probing, backward-shift deletion (no tombstones) ------------------------------

    private static long key(long pos) {
        return pos + 1; // pack(0, 0, 0) == 0 would collide with EMPTY
    }

    private int home(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & setMask;
    }

    private boolean contains(long pos) {
        return key(pos) == EMPTY ? zeroKey : find(pos) >= 0;
    }

    private int find(long pos) {
        long k = key(pos);
        for (int i = home(k); ; i = (i + 1) & setMask) {
            long v = set.get(L, (long) i * Long.BYTES);
            if (v == k) return i;
            if (v == EMPTY) return -1;
        }
    }

    private void insertKey(long pos) {
        long k = key(pos);
        if (k == EMPTY) {
            zeroKey = true;
            return;
        }
        int i = home(k);
        while (set.get(L, (long) i * Long.BYTES) != EMPTY) i = (i + 1) & setMask;
        set.set(L, (long) i * Long.BYTES, k);
    }

    private void removeKey(long pos) {
        if (key(pos) == EMPTY) {
            zeroKey = false;
            return;
        }
        int hole = find(pos);
        if (hole < 0) return;
        int i = hole;
        for (;;) {
            i = (i + 1) & setMask;
            long v = set.get(L, (long) i * Long.BYTES);
            if (v == EMPTY) break;
            int h = home(v);
            // Move v back into the hole if its home is not in the (cyclic) range (hole, i].
            boolean movable = hole <= i ? (h <= hole || h > i) : (h <= hole && h > i);
            if (movable) {
                set.set(L, (long) hole * Long.BYTES, v);
                hole = i;
            }
        }
        set.set(L, (long) hole * Long.BYTES, EMPTY);
    }
}
