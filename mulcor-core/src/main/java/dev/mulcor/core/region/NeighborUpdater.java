package dev.mulcor.core.region;

import static dev.mulcor.core.region.RedstoneStates.*;

/**
 * Vanilla's {@code net.minecraft.world.level.redstone.CollectingNeighborUpdater}, one per region, on primitive arrays.
 *
 * <h2>Vanilla semantics (26.2)</h2>
 * <ul>
 *   <li>{@code addAndRun(update)}: {@code nested = count > 0}; {@code overflow = count >= maxChainedNeighborUpdates}
 *       (gamerule, default 1,000,000); {@code count++}. Unless overflowing, a nested update goes to
 *       {@code addedThisLayer}, a top-level one onto the stack, which is then run by {@code runUpdates}.</li>
 *   <li>{@code runUpdates}: while the stack or {@code addedThisLayer} has entries: push {@code addedThisLayer} onto the
 *       stack in reverse (so its entries run in insertion order), clear it, and run the top entry one step at a time
 *       until it is exhausted (pop) or one step added updates (go deeper first). Then reset {@code count} to 0.</li>
 *   <li>Entries: a neighbour update of one position ({@code SimpleNeighborUpdate}); the six neighbours of a position
 *       in {@code UPDATE_ORDER} W, E, D, U, N, S, optionally skipping one direction ({@code MultiNeighborUpdate});
 *       a shape update ({@code ShapeUpdate}).</li>
 * </ul>
 * Updates addressed to a position another region owns are sent to it as messages instead (see {@link Redstone}).
 *
 * <p>Entry layout ({@link #STRIDE} ints): {@code kind, x, y, z, a, b, c, d}. SIMPLE: target. MULTI: source position,
 * a = skipped direction (-1: none), b = next index into {@link #UPDATE_ORDER}. SHAPE: target, a = direction (the
 * neighbour that changed is at target + a), b = that neighbour's state, c = flags, d = recursion left.
 * The arrays grow on demand and are then reused: a steady-state tick allocates nothing.
 */
final class NeighborUpdater {
    static final int SIMPLE = 0, MULTI = 1, SHAPE = 2;
    static final int STRIDE = 8;
    /** {@code NeighborUpdater.UPDATE_ORDER}. */
    static final int[] UPDATE_ORDER = {WEST, EAST, DOWN, UP, NORTH, SOUTH};
    /** {@code GameRules.MAX_COMMAND_CHAIN_LENGTH}'s sibling {@code maxChainedNeighborUpdates} default. */
    static final int MAX_CHAINED = 1_000_000;

    private int[] stack = new int[STRIDE * 256];
    private int stackSize;
    private int[] layer = new int[STRIDE * 64];
    private int layerSize;
    private int count;
    boolean running() {
        return count > 0;
    }

    void add(Region r, int kind, int x, int y, int z, int a, int b, int c, int d) {
        boolean nested = count > 0;
        boolean overflow = count >= MAX_CHAINED;
        count++;
        if (!overflow) {
            if (nested) {
                if ((layerSize + 1) * STRIDE > layer.length) layer = java.util.Arrays.copyOf(layer, layer.length * 2);
                put(layer, layerSize++, kind, x, y, z, a, b, c, d);
            } else {
                push(kind, x, y, z, a, b, c, d);
            }
        } else {
            r.updatesDropped++; // vanilla logs "Too many chained neighbor updates" and drops them
        }
        if (!nested) run(r);
    }

    private void push(int kind, int x, int y, int z, int a, int b, int c, int d) {
        if ((stackSize + 1) * STRIDE > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
        put(stack, stackSize++, kind, x, y, z, a, b, c, d);
    }

    private static void put(int[] arr, int i, int kind, int x, int y, int z, int a, int b, int c, int d) {
        int o = i * STRIDE;
        arr[o] = kind;
        arr[o + 1] = x;
        arr[o + 2] = y;
        arr[o + 3] = z;
        arr[o + 4] = a;
        arr[o + 5] = b;
        arr[o + 6] = c;
        arr[o + 7] = d;
    }

    private void run(Region r) {
        try {
            while (stackSize > 0 || layerSize > 0) {
                for (int i = layerSize - 1; i >= 0; i--) {
                    int o = i * STRIDE;
                    push(layer[o], layer[o + 1], layer[o + 2], layer[o + 3], layer[o + 4], layer[o + 5], layer[o + 6], layer[o + 7]);
                }
                layerSize = 0;
                int top = stackSize - 1;
                while (layerSize == 0) {
                    if (!runNext(r, top)) {
                        stackSize--;
                        break;
                    }
                }
            }
        } finally {
            stackSize = 0;
            layerSize = 0;
            count = 0;
        }
    }

    /** One step of the entry at stack index {@code i}; false when it is exhausted. */
    private boolean runNext(Region r, int i) {
        int o = i * STRIDE;
        int[] s = stack;
        switch (s[o]) {
            case SIMPLE -> {
                Redstone.executeUpdate(r, s[o + 1], s[o + 2], s[o + 3]);
                return false;
            }
            case MULTI -> {
                // MultiNeighborUpdate.runNext: update pos + UPDATE_ORDER[idx++], then skip the skipped direction.
                int x = s[o + 1], y = s[o + 2], z = s[o + 3], skip = s[o + 4];
                int dir = UPDATE_ORDER[s[o + 5]++];
                if (s[o + 5] < 6 && UPDATE_ORDER[s[o + 5]] == skip) s[o + 5]++;
                boolean more = s[o + 5] < 6;
                Redstone.executeUpdate(r, x + OX[dir], y + OY[dir], z + OZ[dir]);
                return more;
            }
            default -> {
                Redstone.executeShapeUpdate(r, s[o + 1], s[o + 2], s[o + 3], s[o + 4], s[o + 5], s[o + 6], s[o + 7]);
                return false;
            }
        }
    }

    /** Index the MULTI entry starts at: past {@code skip} if it is first. */
    static int firstIndex(int skip) {
        return UPDATE_ORDER[0] == skip ? 1 : 0;
    }
}
