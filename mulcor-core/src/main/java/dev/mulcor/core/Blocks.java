package dev.mulcor.core;

/** Block state ids used by the prototype. Item ids equal block ids. */
public final class Blocks {
    public static final int AIR = 0;
    public static final int BEDROCK = 1;
    public static final int STONE = 2;
    public static final int DIRT = 3;
    public static final int CHEST = 4;
    public static final int TNT = 5;
    public static final int REDSTONE_BLOCK = 6;
    /** Redstone wire with power p is {@code WIRE + p}, p ∈ [0, 15]. */
    public static final int WIRE = 16;

    private Blocks() {}

    public static boolean isSolid(int state) {
        return state != AIR && !isWire(state);
    }

    public static boolean isWire(int state) {
        return state >= WIRE && state <= WIRE + 15;
    }

    public static int wirePower(int state) {
        return state - WIRE;
    }

    /** Blocks that mining can turn into items and explosions can destroy. */
    public static boolean isMineable(int state) {
        return state == STONE || state == DIRT;
    }
}
