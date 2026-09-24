package dev.mulcor.core.region;

/**
 * {@code RedstoneTorchBlock.RECENT_TOGGLES} for one region: (position, game time) of recent torch turn-offs, used for
 * burnout (8 toggles of one torch within 60 ticks).
 *
 * <p>Vanilla keeps one list per level in insertion (= time) order and prunes it from the head at the start of every
 * torch tick while {@code gameTime - head.when > 60}. That removes exactly the entries older than 60 ticks, so
 * {@link #prune} removing every such entry is equivalent, also for lists concatenated when regions merge (whose
 * order is no longer by time). A torch's count only depends on its own entries, which live in the region that owns
 * it, so per-region lists give the same counts as vanilla's per-level one.
 */
final class TorchToggles {
    private long[] pos = new long[64];
    private long[] when = new long[64];
    private int size;

    /** Drop every entry with {@code gameTime - when > timer}. */
    void prune(long gameTime, int timer) {
        int w = 0;
        for (int i = 0; i < size; i++) {
            if (gameTime - when[i] > timer) continue;
            pos[w] = pos[i];
            when[w] = when[i];
            w++;
        }
        size = w;
    }

    /** {@code isToggledTooFrequently(level, pos, true)}'s list part: add (pos, now), return the entries for pos. */
    int addAndCount(long p, long gameTime) {
        if (size == pos.length) {
            pos = java.util.Arrays.copyOf(pos, size * 2);
            when = java.util.Arrays.copyOf(when, size * 2);
        }
        pos[size] = p;
        when[size] = gameTime;
        size++;
        return count(p);
    }

    /** Entries for {@code p}. */
    int count(long p) {
        int n = 0;
        for (int i = 0; i < size; i++) if (pos[i] == p) n++;
        return n;
    }

    /** Move every entry into {@code to} (regions merging). */
    void drainInto(TorchToggles to) {
        for (int i = 0; i < size; i++) {
            if (to.size == to.pos.length) {
                to.pos = java.util.Arrays.copyOf(to.pos, to.size * 2);
                to.when = java.util.Arrays.copyOf(to.when, to.size * 2);
            }
            to.pos[to.size] = pos[i];
            to.when[to.size] = when[i];
            to.size++;
        }
        size = 0;
    }

    /** Move the entries of positions region {@code to} owns into {@code into} (region split). */
    void moveOwned(dev.mulcor.core.World world, int to, TorchToggles into) {
        int w = 0;
        for (int i = 0; i < size; i++) {
            long p = pos[i];
            int x = dev.mulcor.memory.ScheduledTicks.x(p), z = dev.mulcor.memory.ScheduledTicks.z(p);
            if (world.ownerOfBlock(x, z) == to) {
                into.addAndCount(p, when[i]);
            } else {
                pos[w] = p;
                when[w] = when[i];
                w++;
            }
        }
        size = w;
    }

    int size() {
        return size;
    }
}
