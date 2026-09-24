package dev.mulcor.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A region's scheduled block ticks, like vanilla's {@code LevelTicks}/{@code LevelChunkTicks}: an off-heap binary
 * min-heap ordered by (due epoch, priority, scheduling order) — vanilla's {@code ScheduledTick.DRIFT_COMPARATOR}
 * (triggerTick, priority, subTickOrder) — plus an open-addressing set keyed by (position, block type), so a block is
 * never scheduled twice while a tick for it is pending ({@code LevelChunkTicks.schedule}: {@code ticksPerPosition}
 * is keyed by {@code ScheduledTick.UNIQUE_KEY_HASH} = position and type). The set also records each tick's due
 * epoch, for {@code willTickThisTick}.
 *
 * <p>Positions are packed longs (see {@link #pack}). Fixed capacity: {@link #schedule} returns false when full.
 * Confined to the thread running the owning region, like its entity table. No allocation.
 */
public final class ScheduledTicks {
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    /** Heap entry: due, order (priority:16 | seq:48), pos, block (int) + pad. */
    private static final long ENTRY = 32;
    /** Set slot: pos, block + 1 (int; 0 = empty) + pad, due. */
    private static final long SLOT = 24;

    private final int capacity;
    private final MemorySegment heap;
    private final MemorySegment set;
    private final int setMask;
    private int size;
    private long seq;

    public ScheduledTicks(NativeMemory memory, int capacity) {
        this.capacity = capacity;
        this.heap = memory.allocate(capacity * ENTRY);
        int slots = Integer.highestOneBit(Math.max(4, capacity * 2 - 1)) << 1;
        this.set = memory.allocate(slots * SLOT);
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

    public int peekBlock() {
        return heap.get(I, 24);
    }

    public boolean isScheduled(long pos, int block) {
        return find(pos, block) >= 0;
    }

    /** Due epoch of the pending tick for (pos, block), or {@link Long#MAX_VALUE} if there is none. */
    public long due(long pos, int block) {
        int i = find(pos, block);
        return i < 0 ? Long.MAX_VALUE : set.get(L, i * SLOT + 16);
    }

    /**
     * Schedule a tick for {@code block} at {@code pos}, due at epoch {@code due}. Lower priority values run first among
     * ticks due in the same epoch (vanilla: -3 extremely high … 3 extremely low), then earlier-scheduled ones.
     * Returns false if (pos, block) already has a tick pending or the queue is full.
     */
    public boolean schedule(long due, int priority, long pos, int block) {
        if (size == capacity || find(pos, block) >= 0) return false;
        insertKey(pos, block, due);
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
        heap.set(I, off + 24, block);
        return true;
    }

    /** Remove the next tick and return its position (read {@link #peekBlock} first). The queue must not be empty. */
    public long poll() {
        long pos = heap.get(L, 16);
        removeKey(pos, heap.get(I, 24));
        int last = --size;
        if (last > 0) {
            long lOff = last * ENTRY;
            long due = heap.get(L, lOff), order = heap.get(L, lOff + 8), p = heap.get(L, lOff + 16);
            int b = heap.get(I, lOff + 24);
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
            heap.set(I, off + 24, b);
        }
        return pos;
    }

    public void clear() {
        size = 0;
        set.fill((byte) 0);
    }

    private static boolean less(long due1, long order1, long due2, long order2) {
        return due1 < due2 || (due1 == due2 && order1 < order2);
    }

    private void copy(int from, int to) {
        MemorySegment.copy(heap, from * ENTRY, heap, to * ENTRY, ENTRY);
    }

    // ---- (pos, block) set: linear probing, backward-shift deletion (no tombstones) ----------------------------
    // A slot is empty when its block field (stored as block + 1) is 0, so every packed position is a valid key.

    private int home(long pos, int block) {
        long h = (pos ^ ((long) block << 32 | block)) * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & setMask;
    }

    private int find(long pos, int block) {
        for (int i = home(pos, block); ; i = (i + 1) & setMask) {
            int tag = set.get(I, i * SLOT + 8);
            if (tag == 0) return -1;
            if (tag == block + 1 && set.get(L, i * SLOT) == pos) return i;
        }
    }

    private void insertKey(long pos, int block, long due) {
        int i = home(pos, block);
        while (set.get(I, i * SLOT + 8) != 0) i = (i + 1) & setMask;
        set.set(L, i * SLOT, pos);
        set.set(I, i * SLOT + 8, block + 1);
        set.set(L, i * SLOT + 16, due);
    }

    private void removeKey(long pos, int block) {
        int hole = find(pos, block);
        if (hole < 0) return;
        int i = hole;
        for (;;) {
            i = (i + 1) & setMask;
            int tag = set.get(I, i * SLOT + 8);
            if (tag == 0) break;
            int h = home(set.get(L, i * SLOT), tag - 1);
            // Move the entry back into the hole if its home is not in the (cyclic) range (hole, i].
            boolean movable = hole <= i ? (h <= hole || h > i) : (h <= hole && h > i);
            if (movable) {
                MemorySegment.copy(set, i * SLOT, set, hole * SLOT, SLOT);
                hole = i;
            }
        }
        set.set(I, hole * SLOT + 8, 0);
    }
}
