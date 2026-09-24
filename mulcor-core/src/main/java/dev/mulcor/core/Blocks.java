package dev.mulcor.core;

/**
 * Block state ids used by the prototype. Item ids equal block ids. Each id maps to a vanilla block state for
 * clients (see {@code dev.mulcor.net.Protocol}).
 */
public final class Blocks {
    public static final int AIR = 0;
    public static final int BEDROCK = 1;
    public static final int STONE = 2;
    public static final int DIRT = 3;
    public static final int CHEST = 4;
    public static final int TNT = 5;
    public static final int REDSTONE_BLOCK = 6;
    /** Falls when the block below is air. */
    public static final int SAND = 7;
    public static final int LAMP = 8;
    public static final int LAMP_LIT = 9;
    /** A lit redstone torch standing on the block below it. It turns off when that block is powered. */
    public static final int TORCH = 10;
    public static final int TORCH_OFF = 11;
    /** Redstone wire with power p is {@code WIRE + p}, p ∈ [0, 15]. */
    public static final int WIRE = 16;
    /**
     * Repeaters: {@code REPEATER + dir * 8 + (delay - 1) * 2 + powered}. {@code dir} is the direction the signal
     * leaves in (see {@link #DX}/{@link #DZ}); delay is 1..4 redstone ticks (2..8 game ticks).
     */
    public static final int REPEATER = 32;
    public static final int REPEATER_END = REPEATER + 32;

    /** Horizontal directions: 0 north (-z), 1 east (+x), 2 south (+z), 3 west (-x). */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};

    private Blocks() {}

    private static final int TABLE = REPEATER_END;
    private static final boolean[] SOLID = new boolean[TABLE], REACTS = new boolean[TABLE];

    static {
        for (int st = 0; st < TABLE; st++) {
            SOLID[st] = st != AIR && !isWire(st) && !isTorch(st) && !isRepeater(st);
            REACTS[st] = isWire(st) || isTorch(st) || isRepeater(st) || isLamp(st) || st == TNT || st == SAND;
        }
    }

    /**
     * Full cubes that stop entities and conduct redstone power. Wires, torches and repeaters are passable. A table
     * lookup: this runs in every collision sweep and pathfinder step. Unknown ids count as solid.
     */
    public static boolean isSolid(int state) {
        return state >= TABLE || SOLID[state];
    }

    public static boolean isWire(int state) {
        return state >= WIRE && state <= WIRE + 15;
    }

    public static int wirePower(int state) {
        return state - WIRE;
    }

    public static boolean isTorch(int state) {
        return state == TORCH || state == TORCH_OFF;
    }

    public static boolean isLamp(int state) {
        return state == LAMP || state == LAMP_LIT;
    }

    public static boolean isRepeater(int state) {
        return state >= REPEATER && state < REPEATER_END;
    }

    public static int repeater(int dir, int delay, boolean powered) {
        return REPEATER + dir * 8 + (delay - 1) * 2 + (powered ? 1 : 0);
    }

    public static int repeaterDir(int state) { return (state - REPEATER) >> 3; }
    public static int repeaterDelay(int state) { return (((state - REPEATER) >> 1) & 3) + 1; }
    public static boolean repeaterPowered(int state) { return ((state - REPEATER) & 1) != 0; }

    public static int withRepeaterPowered(int state, boolean powered) {
        return (state & ~1) | (powered ? 1 : 0);
    }

    /** Blocks that re-evaluate themselves when a neighbour changes. */
    public static boolean reactsToUpdates(int state) {
        return state < TABLE && REACTS[state];
    }

    /** Blocks that mining can turn into items and explosions can destroy. */
    public static boolean isMineable(int state) {
        return state == STONE || state == DIRT || state == SAND;
    }
}
