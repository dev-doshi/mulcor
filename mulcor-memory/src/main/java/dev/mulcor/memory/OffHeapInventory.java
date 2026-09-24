package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.LONG;

import java.lang.foreign.MemorySegment;

/**
 * A fixed set of inventories (chests, player inventories). Each slot is one off-heap {@code long}:
 * {@code itemId:32 | count:32}, where itemId 0 means empty.
 *
 * <p>Every mutation is a single-slot CAS. Items therefore move between slots but are never created or copied:
 * each unit is always in exactly one slot or in the hands of exactly one in-flight operation. The engine still
 * sends all clicks for an inventory through its owning region, so the CAS is uncontended in practice. The CAS
 * is the backstop that the jcstress tests attack directly.
 */
public final class OffHeapInventory {
    public static final int MAX_STACK = 64;

    private final MemorySegment seg;
    private final int inventories;
    private final int slots;

    public OffHeapInventory(NativeMemory memory, int inventories, int slotsPerInventory) {
        this.inventories = inventories;
        this.slots = slotsPerInventory;
        this.seg = memory.allocate((long) inventories * slotsPerInventory * Long.BYTES);
    }

    public int inventories() { return inventories; }
    public int slotsPerInventory() { return slots; }

    public static long pack(int item, int count) {
        return count == 0 ? 0L : ((long) item << 32) | (count & 0xFFFF_FFFFL);
    }

    public static int item(long word) { return (int) (word >>> 32); }

    public static int count(long word) { return (int) word; }

    private long offset(int inv, int slot) {
        if (inv < 0 || inv >= inventories || slot < 0 || slot >= slots) {
            throw new IndexOutOfBoundsException("inv=" + inv + " slot=" + slot);
        }
        return ((long) inv * slots + slot) * Long.BYTES;
    }

    public long get(int inv, int slot) {
        return (long) LONG.getAcquire(seg, offset(inv, slot));
    }

    /** Unconditional write, for setup only. */
    public void set(int inv, int slot, int item, int count) {
        LONG.setRelease(seg, offset(inv, slot), pack(item, count));
    }

    public boolean cas(int inv, int slot, long expected, long update) {
        return LONG.compareAndSet(seg, offset(inv, slot), expected, update);
    }

    /**
     * Atomically take up to {@code max} items from a slot. Returns {@code pack(item, taken)}, or 0 if the slot
     * was empty.
     */
    public long take(int inv, int slot, int max) {
        long off = offset(inv, slot);
        for (;;) {
            long w = (long) LONG.getAcquire(seg, off);
            int c = count(w);
            if (c == 0 || max <= 0) {
                return 0L;
            }
            int n = Math.min(c, max);
            if (LONG.compareAndSet(seg, off, w, pack(item(w), c - n))) {
                return pack(item(w), n);
            }
        }
    }

    /** Insert items: first merge into matching stacks, then fill empty slots. Returns the count that did not fit. */
    public int insert(int inv, int item, int count) {
        if (item == 0 || count <= 0) {
            return count;
        }
        int left = count;
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int s = 0; s < slots && left > 0; s++) {
                long off = offset(inv, s);
                for (;;) {
                    long w = (long) LONG.getAcquire(seg, off);
                    int c = count(w);
                    boolean eligible = pass == 0 ? (c > 0 && item(w) == item && c < MAX_STACK) : c == 0;
                    if (!eligible) {
                        break;
                    }
                    int n = Math.min(left, MAX_STACK - c);
                    if (LONG.compareAndSet(seg, off, w, pack(item, c + n))) {
                        left -= n;
                        break;
                    }
                }
            }
        }
        return left;
    }

    /**
     * Move up to {@code max} items from one slot into another inventory. Items that do not fit are put back
     * into the source inventory. Returns {@code pack(item, moved)}. If even the put-back fails, the leftover is
     * reported through {@code overflowOut[0]} so the caller can drop it into the world: nothing is lost.
     */
    public long move(int srcInv, int srcSlot, int dstInv, int max, int[] overflowOut) {
        long taken = take(srcInv, srcSlot, max);
        int n = count(taken);
        if (n == 0) {
            return 0L;
        }
        int item = item(taken);
        int rejected = insert(dstInv, item, n);
        int overflow = rejected > 0 ? insert(srcInv, item, rejected) : 0;
        if (overflowOut != null) {
            overflowOut[0] = overflow;
        }
        return pack(item, n - rejected);
    }

    /** Total count of an item across one inventory. */
    public long total(int inv, int item) {
        long sum = 0;
        for (int s = 0; s < slots; s++) {
            long w = get(inv, s);
            if (item(w) == item) {
                sum += count(w);
            }
        }
        return sum;
    }

    /** Total count of all items across one inventory. */
    public long totalAll(int inv) {
        long sum = 0;
        for (int s = 0; s < slots; s++) {
            sum += count(get(inv, s));
        }
        return sum;
    }
}
