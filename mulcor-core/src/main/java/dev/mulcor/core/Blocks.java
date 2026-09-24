package dev.mulcor.core;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import dev.mulcor.registry.Shapes;

/**
 * The block states Mulcor's mechanics use, as <b>vanilla global state ids</b> (the ids in {@code BlockStorage}, on
 * the wire and in Anvil palettes), with helpers over vanilla properties. Everything here is a {@code static final}
 * constant or an array lookup: allocation-free on the hot path.
 *
 * <p>Item ids in the prototype's inventories are still the placed block state (a placeholder until real item stacks
 * arrive with the inventory milestone).
 */
public final class Blocks {
    public static final int AIR = 0;
    public static final int BEDROCK = BlockData.defaultState(BlockId.BEDROCK);
    public static final int STONE = BlockData.defaultState(BlockId.STONE);
    public static final int DIRT = BlockData.defaultState(BlockId.DIRT);
    public static final int CHEST = BlockData.defaultState(BlockId.CHEST);
    /** {@code minecraft:tnt[unstable=false]}. */
    public static final int TNT = BlockData.defaultState(BlockId.TNT);
    public static final int REDSTONE_BLOCK = BlockData.defaultState(BlockId.REDSTONE_BLOCK);
    /** Falls when the block below is air. */
    public static final int SAND = BlockData.defaultState(BlockId.SAND);

    private static final int LAMP_LIT_P = BlockData.property(BlockId.REDSTONE_LAMP, "lit");
    public static final int LAMP = BlockData.withBool(BlockData.defaultState(BlockId.REDSTONE_LAMP), LAMP_LIT_P, false);
    public static final int LAMP_LIT = BlockData.withBool(LAMP, LAMP_LIT_P, true);

    private static final int TORCH_LIT_P = BlockData.property(BlockId.REDSTONE_TORCH, "lit");
    /** A lit redstone torch standing on the block below it. It turns off when that block is powered. */
    public static final int TORCH = BlockData.withBool(BlockData.defaultState(BlockId.REDSTONE_TORCH), TORCH_LIT_P, true);
    public static final int TORCH_OFF = BlockData.withBool(TORCH, TORCH_LIT_P, false);

    private static final int WIRE_POWER = BlockData.property(BlockId.REDSTONE_WIRE, "power");
    /** Redstone wire with power 0 and no side connections ({@code minecraft:redstone_wire} default state). */
    public static final int WIRE = BlockData.defaultState(BlockId.REDSTONE_WIRE);

    private static final int REP_FACING = BlockData.property(BlockId.REPEATER, "facing");
    private static final int REP_DELAY = BlockData.property(BlockId.REPEATER, "delay");
    private static final int REP_POWERED = BlockData.property(BlockId.REPEATER, "powered");
    private static final int REP_LOCKED = BlockData.property(BlockId.REPEATER, "locked");

    /** Horizontal directions: 0 north (-z), 1 east (+x), 2 south (+z), 3 west (-x). */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};
    private static final String[] DIR_NAMES = {"north", "east", "south", "west"};
    /** Mulcor direction → value index of the repeater's {@code facing} property, and back. */
    private static final int[] FACING_INDEX = new int[4];
    private static final int[] FACING_DIR = new int[4];

    static {
        for (int d = 0; d < 4; d++) {
            int idx = BlockData.valueIndex(REP_FACING, DIR_NAMES[d]);
            FACING_INDEX[d] = idx;
            FACING_DIR[idx] = d;
        }
    }

    private Blocks() {}

    /**
     * Interim collision test for Mulcor's full-cube physics: the state blocks motion ({@code blocksMotion}) and its
     * collision shape is taller than a quarter block (so wires, torches, repeaters and carpets stay passable).
     * Replaced by per-box shape collision.
     */
    public static boolean isSolid(int state) {
        return SOLID[state];
    }

    /** Vanilla {@code BlockState.isRedstoneConductor()}: full solid blocks that carry (weak) power. */
    public static boolean isConductor(int state) {
        return BlockData.is(state, BlockData.REDSTONE_CONDUCTOR);
    }

    /** The {@code MOTION_BLOCKING} heightmap predicate: blocks motion or holds a fluid ({@code Heightmap.Types}). */
    public static boolean isMotionBlocking(int state) {
        return BlockData.is(state, BlockData.BLOCKS_MOTION) || BlockData.is(state, BlockData.FLUID);
    }

    private static final boolean[] SOLID;

    static {
        SOLID = new boolean[dev.mulcor.registry.Registry.stateCount()];
        for (int s = 0; s < SOLID.length; s++) {
            SOLID[s] = BlockData.is(s, BlockData.BLOCKS_MOTION) && Shapes.maxY(BlockData.collisionShape(s)) > 0.25;
        }
    }

    // ---- redstone wire ----

    public static boolean isWire(int state) {
        return BlockData.isOf(state, BlockId.REDSTONE_WIRE);
    }

    /** A wire state with the given power and no side connections. */
    public static int wire(int power) {
        return BlockData.withInt(WIRE, WIRE_POWER, power);
    }

    public static int wirePower(int state) {
        return BlockData.intValue(state, WIRE_POWER);
    }

    /** {@code state} (a wire) with its power set, keeping its side connections. */
    public static int withWirePower(int state, int power) {
        return BlockData.withInt(state, WIRE_POWER, power);
    }

    // ---- torches and lamps ----

    public static boolean isTorch(int state) {
        return BlockData.isOf(state, BlockId.REDSTONE_TORCH);
    }

    public static boolean isLamp(int state) {
        return BlockData.isOf(state, BlockId.REDSTONE_LAMP);
    }

    // ---- repeaters ----

    public static boolean isRepeater(int state) {
        return BlockData.isOf(state, BlockId.REPEATER);
    }

    /**
     * A repeater whose signal leaves toward {@code dir}. Vanilla's {@code facing} points at the input
     * ({@code DiodeBlock.getInputSignal} reads {@code pos.relative(facing)}), so it is the opposite direction.
     */
    public static int repeater(int dir, int delay, boolean powered) {
        int s = BlockData.defaultState(BlockId.REPEATER);
        s = BlockData.with(s, REP_FACING, FACING_INDEX[(dir + 2) & 3]);
        s = BlockData.withInt(s, REP_DELAY, delay);
        s = BlockData.withBool(s, REP_LOCKED, false);
        return BlockData.withBool(s, REP_POWERED, powered);
    }

    /** Direction the signal leaves in (opposite of vanilla's {@code facing}). */
    public static int repeaterDir(int state) {
        return (FACING_DIR[BlockData.get(state, REP_FACING)] + 2) & 3;
    }

    /** Delay in redstone ticks, 1..4 (2..8 game ticks). */
    public static int repeaterDelay(int state) {
        return BlockData.intValue(state, REP_DELAY);
    }

    public static boolean repeaterPowered(int state) {
        return BlockData.boolValue(state, REP_POWERED);
    }

    public static int withRepeaterPowered(int state, boolean powered) {
        return BlockData.withBool(state, REP_POWERED, powered);
    }

    // ---- behaviour classes ----

    /** Blocks that re-evaluate themselves when a neighbour changes. */
    public static boolean reactsToUpdates(int state) {
        int b = BlockData.block(state);
        return b == BlockId.REDSTONE_WIRE || b == BlockId.REDSTONE_TORCH || b == BlockId.REPEATER
                || b == BlockId.REDSTONE_LAMP || b == BlockId.TNT || b == BlockId.SAND;
    }

    /** Blocks that mining can turn into items and explosions can destroy. */
    public static boolean isMineable(int state) {
        return state == STONE || state == DIRT || state == SAND;
    }
}
