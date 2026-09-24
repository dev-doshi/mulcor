package dev.mulcor.core.region;

import dev.mulcor.memory.ScheduledTicks;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * {@code ComparatorBlockEntity.output} for the comparators one region owns: packed position
 * ({@link ScheduledTicks#pack}) → output signal 0-15. A comparator without an entry outputs 0, like a fresh block
 * entity, so only comparators that ever output a signal take a slot.
 *
 * <p>Open addressing with linear probing and backward-shift deletion, in one {@code long[]} of (key, value) pairs so
 * a reader always sees keys and values of the same table. Only the owning region writes, during its tick or the
 * commit phase. Other regions read with {@link #getShared} (a comparator's signal seen across a border), which races
 * with the owner's writes in the same way {@code BlockStorage.getShared} does: it sees the value of the current or a
 * previous epoch, or 0 while an entry is being moved.
 *
 * <p>Keys never equal {@code Long.MIN_VALUE} (the free-slot marker): world x is never negative.
 */
final class ComparatorOutputs {
    private static final long EMPTY = Long.MIN_VALUE;
    private static final VarHandle TABLE;

    static {
        try {
            TABLE = MethodHandles.lookup().findVarHandle(ComparatorOutputs.class, "table", long[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Pairs (key, value); {@code key == EMPTY} marks a free slot. Length is 2 × a power of two. */
    private long[] table;
    private int size;

    ComparatorOutputs() {
        table = emptyTable(256);
    }

    /** The output of the comparator at {@code pos}, for the owning region. */
    int get(long pos) {
        return lookup(table, pos);
    }

    /** {@link #get} for another region's thread (see the class notes). */
    int getShared(long pos) {
        return lookup((long[]) TABLE.getAcquire(this), pos);
    }

    private static int lookup(long[] t, long pos) {
        int mask = (t.length >>> 1) - 1;
        for (int i = slot(pos, mask); ; i = (i + 1) & mask) {
            long k = t[2 * i];
            if (k == pos) return (int) t[2 * i + 1];
            if (k == EMPTY) return 0;
        }
    }

    /** {@code ComparatorBlockEntity.setOutputSignal}; 0 frees the slot. */
    void set(long pos, int output) {
        if (output == 0) {
            remove(pos);
            return;
        }
        long[] t = table;
        int mask = (t.length >>> 1) - 1;
        int i = slot(pos, mask);
        while (t[2 * i] != EMPTY && t[2 * i] != pos) i = (i + 1) & mask;
        if (t[2 * i] == pos) {
            t[2 * i + 1] = output;
            return;
        }
        if ((size + 1) * 4 > (mask + 1) * 3) {
            grow();
            set(pos, output);
            return;
        }
        t[2 * i + 1] = output;
        t[2 * i] = pos;
        size++;
    }

    /** The block entity is gone (the comparator was removed or replaced). */
    void remove(long pos) {
        long[] t = table;
        int mask = (t.length >>> 1) - 1;
        int i = slot(pos, mask);
        while (t[2 * i] != pos) {
            if (t[2 * i] == EMPTY) return;
            i = (i + 1) & mask;
        }
        // Backward-shift deletion: pull later entries of the probe run into the hole.
        for (int j = (i + 1) & mask; t[2 * j] != EMPTY; j = (j + 1) & mask) {
            int home = slot(t[2 * j], mask);
            boolean movable = i <= j ? home <= i || home > j : home <= i && home > j;
            if (movable) {
                t[2 * i + 1] = t[2 * j + 1];
                t[2 * i] = t[2 * j];
                i = j;
            }
        }
        t[2 * i] = EMPTY;
        t[2 * i + 1] = 0;
        size--;
    }

    int size() {
        return size;
    }

    /** Move every entry into {@code to} (regions merging). Commit phase. */
    void drainInto(ComparatorOutputs to) {
        long[] t = table;
        for (int i = 0; i < t.length; i += 2) {
            if (t[i] != EMPTY) to.set(t[i], (int) t[i + 1]);
        }
        clear();
    }

    /** Move the entries of positions region {@code to} owns into {@code into} (region split). Commit phase. */
    void moveOwned(dev.mulcor.core.World world, int to, ComparatorOutputs into) {
        long[] t = table;
        for (int i = 0; i < t.length; ) {
            long p = t[i];
            if (p != EMPTY && world.ownerOfBlock(ScheduledTicks.x(p), ScheduledTicks.z(p)) == to) {
                into.set(p, (int) t[i + 1]);
                remove(p); // a later entry may shift into slot i: look at it again
            } else {
                i += 2;
            }
        }
    }

    private void clear() {
        java.util.Arrays.fill(table, EMPTY);
        for (int i = 1; i < table.length; i += 2) table[i] = 0;
        size = 0;
    }

    /** Capacity warm-up: doubles, never shrinks. The new table is published with release for {@link #getShared}. */
    private void grow() {
        long[] old = table;
        long[] t = emptyTable(old.length); // twice the slots: old.length is 2 × capacity
        int mask = (t.length >>> 1) - 1;
        for (int i = 0; i < old.length; i += 2) {
            long p = old[i];
            if (p == EMPTY) continue;
            int j = slot(p, mask);
            while (t[2 * j] != EMPTY) j = (j + 1) & mask;
            t[2 * j] = p;
            t[2 * j + 1] = old[i + 1];
        }
        TABLE.setRelease(this, t);
    }

    private static long[] emptyTable(int capacity) {
        long[] t = new long[2 * capacity];
        for (int i = 0; i < t.length; i += 2) t[i] = EMPTY;
        return t;
    }

    private static int slot(long pos, int mask) {
        long h = pos * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
