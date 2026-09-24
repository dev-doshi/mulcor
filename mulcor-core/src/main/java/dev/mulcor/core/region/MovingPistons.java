package dev.mulcor.core.region;

import dev.mulcor.memory.ScheduledTicks;

/**
 * The {@code PistonMovingBlockEntity}s of one region: one per {@code moving_piston} block, holding the block being
 * moved ({@code movedState}), the piston's facing, whether it is extending, whether it is the retracting piston base
 * itself ({@code isSourcePiston}), its progress and the game time it last ticked.
 *
 * <p>Entries are kept in creation order, which is vanilla's block-entity ticker order ({@code Level.blockEntityTickers}:
 * new tickers are appended). Removing an entry marks it; {@link #compact} drops marked entries after the tick loop,
 * like vanilla's ticker list removing tickers whose block entity was removed. A position index finds the live entry
 * at a position. Progress is stored in half steps: vanilla's progress moves 0 → 0.5 → 1.0.
 *
 * <p>Fixed capacity ({@link #add} fails when full: the piston does not move). Confined to the owning region's
 * thread, or the commit phase. No allocation after construction.
 */
final class MovingPistons {
    static final int EXTENDING = 1, SOURCE = 2;

    private final long[] pos, lastTicked;
    private final int[] moved;
    /** Facing (Direction ordinal) in bits 0-2, flags in bits 3-4, progress half steps in 8-9, progressO in 10-11. */
    private final int[] bits;
    private final int capacity;
    private final boolean[] removed;
    private int size;
    /** Open-addressing index: packed position → entry + 1 (0 = empty), linear probing, backward-shift deletion. */
    private final long[] keys;
    private final int[] vals;
    private final int mask;

    MovingPistons(int capacity) {
        this.capacity = capacity;
        pos = new long[capacity];
        lastTicked = new long[capacity];
        moved = new int[capacity];
        bits = new int[capacity];
        removed = new boolean[capacity];
        int n = Integer.highestOneBit(Math.max(16, capacity * 2 - 1)) << 1;
        keys = new long[n];
        vals = new int[n];
        mask = n - 1;
    }

    int size() { return size; }
    boolean full() { return size == capacity; }

    long pos(int e) { return pos[e]; }
    int moved(int e) { return moved[e]; }
    int facing(int e) { return bits[e] & 7; }
    boolean extending(int e) { return (bits[e] >> 3 & EXTENDING) != 0; }
    boolean source(int e) { return (bits[e] >> 3 & SOURCE) != 0; }
    /** Progress in half steps (0, 1, 2 = 0.0, 0.5, 1.0). */
    int progress(int e) { return bits[e] >> 8 & 3; }
    int progressO(int e) { return bits[e] >> 10 & 3; }
    long lastTicked(int e) { return lastTicked[e]; }
    boolean removed(int e) { return removed[e]; }

    void setProgress(int e, int progress, int progressO) {
        bits[e] = bits[e] & ~(0xF << 8) | progress << 8 | progressO << 10;
    }

    void setLastTicked(int e, long gameTime) { lastTicked[e] = gameTime; }

    /**
     * {@code level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(...))}: a new entity at {@code p} replaces
     * any there. Returns the entry, or -1 when full.
     */
    int add(long p, int movedState, int facing, boolean extending, boolean source, long gameTime) {
        remove(p);
        if (size == capacity) return -1;
        int e = size++;
        pos[e] = p;
        moved[e] = movedState;
        bits[e] = facing | ((extending ? EXTENDING : 0) | (source ? SOURCE : 0)) << 3;
        lastTicked[e] = 0; // PistonMovingBlockEntity.lastTicked starts at 0
        removed[e] = false;
        put(p, e);
        return e;
    }

    /** The live entry at {@code p}, or -1. */
    int find(long p) {
        for (int i = slot(p); ; i = (i + 1) & mask) {
            int v = vals[i];
            if (v == 0) return -1;
            if (keys[i] == p) return v - 1;
        }
    }

    /** {@code level.removeBlockEntity(p)}: mark the entry at {@code p} removed (no-op if none). */
    void remove(long p) {
        int e = find(p);
        if (e < 0) return;
        removed[e] = true;
        unindex(p);
    }

    /** Drop removed entries, keeping the order of the rest (after the block-entity tick loop). */
    void compact() {
        int w = 0;
        for (int e = 0; e < size; e++) {
            if (removed[e]) continue;
            if (w != e) {
                pos[w] = pos[e];
                moved[w] = moved[e];
                bits[w] = bits[e];
                lastTicked[w] = lastTicked[e];
                removed[w] = false;
                put(pos[w], w);
            }
            w++;
        }
        size = w;
    }

    /** Move every live entry into {@code to} (region merge), or those whose position {@code to} now owns (split). */
    void moveTo(MovingPistons to, dev.mulcor.core.World world, int onlyOwner) {
        for (int e = 0; e < size; e++) {
            if (removed[e]) continue;
            long p = pos[e];
            if (onlyOwner >= 0 && world.ownerOfBlock(ScheduledTicks.x(p), ScheduledTicks.z(p)) != onlyOwner) continue;
            int n = to.add(p, moved[e], bits[e] & 7, extending(e), source(e), lastTicked[e]);
            if (n < 0) continue; // the receiving table is full: the entry stays here and is forwarded on a later pass
            to.bits[n] = bits[e];
            to.lastTicked[n] = lastTicked[e];
            remove(p);
        }
        compact();
    }

    private int slot(long p) {
        long h = p * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    private void put(long p, int e) {
        for (int i = slot(p); ; i = (i + 1) & mask) {
            if (vals[i] == 0 || keys[i] == p) {
                keys[i] = p;
                vals[i] = e + 1;
                return;
            }
        }
    }

    private void unindex(long p) {
        int i = slot(p);
        while (vals[i] != 0 && keys[i] != p) i = (i + 1) & mask;
        if (vals[i] == 0) return;
        int hole = i, j = i;
        while (true) {
            j = (j + 1) & mask;
            if (vals[j] == 0) break;
            int home = slot(keys[j]);
            boolean stays = hole <= j ? (home > hole && home <= j) : (home > hole || home <= j);
            if (stays) continue;
            keys[hole] = keys[j];
            vals[hole] = vals[j];
            hole = j;
        }
        vals[hole] = 0;
    }
}
