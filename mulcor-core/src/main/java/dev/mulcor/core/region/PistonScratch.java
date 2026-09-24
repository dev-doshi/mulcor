package dev.mulcor.core.region;

/**
 * Per-region scratch for {@link Pistons}: the lists of a {@code PistonStructureResolver} and the collections
 * {@code PistonBaseBlock.moveBlocks} builds, as preallocated primitive arrays (positions packed by
 * {@link dev.mulcor.memory.ScheduledTicks#pack}). Vanilla's push limit bounds {@code toPush}; {@code toDestroy}
 * gets at most one entry per block line, and every line starts at a pushed block or the start position.
 */
final class PistonScratch {
    /** {@code PistonStructureResolver.MAX_PUSH_DEPTH}. */
    static final int MAX_PUSH_DEPTH = 12;
    static final int MAX_DESTROY = 4 * MAX_PUSH_DEPTH + 4;

    final long[] toPush = new long[MAX_PUSH_DEPTH + 1];
    final long[] reorder = new long[MAX_PUSH_DEPTH + 1];
    final long[] toDestroy = new long[MAX_DESTROY];
    int pushCount, destroyCount;
    /** The structure touched a position another region owns (the piston then does not move). */
    boolean foreign;
    long pistonPos;
    int pistonDirection, pushDirection;

    // moveBlocks: the HashMap<BlockPos, BlockState> of pushed positions, in insertion order, plus the states list.
    final long[] mapPos = new long[MAX_PUSH_DEPTH];
    final int[] mapState = new int[MAX_PUSH_DEPTH];
    final boolean[] mapLive = new boolean[MAX_PUSH_DEPTH];
    final int[] mapOrder = new int[MAX_PUSH_DEPTH];
    final int[] mapBucket = new int[MAX_PUSH_DEPTH];
    final int[] pushedStates = new int[MAX_PUSH_DEPTH];
    final int[] states = new int[MAX_PUSH_DEPTH + MAX_DESTROY];
}
