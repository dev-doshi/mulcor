package dev.mulcor.core.region;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;

/**
 * Per-state lookup tables for the redstone port, built once from the vanilla registry so the hot paths read one
 * array element instead of doing mixed-radix property arithmetic.
 *
 * <h2>Directions</h2>
 * Vanilla {@code Direction} ordinals: 0 DOWN, 1 UP, 2 NORTH, 3 SOUTH, 4 WEST, 5 EAST; {@code opposite(d) = d ^ 1}.
 *
 * <h2>Kinds and packed info</h2>
 * {@link #kind} classifies a state; {@link #info} packs its redstone-relevant properties:
 * <ul>
 *   <li>{@link #WIRE}: bits 0-3 {@code power}; bits 4+2i the side toward {@code HORIZONTAL[i]} (N, E, S, W):
 *       {@link #NONE}, {@link #SIDE} or {@link #UP_SIDE}.</li>
 *   <li>{@link #REPEATER}: bits 0-2 {@code facing} (a Direction ordinal: the INPUT side), bits 3-4 {@code delay - 1},
 *       bit 5 {@code locked}, bit 6 {@code powered}.</li>
 *   <li>{@link #TORCH}, {@link #LAMP}: bit 6 {@code lit}. {@link #WALL_TORCH}: bits 0-2 {@code facing}, bit 6 {@code lit}.</li>
 * </ul>
 */
final class RedstoneStates {
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final int[] OX = {0, 0, 0, 0, -1, 1};
    static final int[] OY = {-1, 1, 0, 0, 0, 0};
    static final int[] OZ = {0, 0, -1, 1, 0, 0};
    /** {@code Direction.Plane.HORIZONTAL}: NORTH, EAST, SOUTH, WEST. */
    static final int[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};
    /** Index of a horizontal direction in {@link #HORIZONTAL} (-1 for vertical ones). */
    static final int[] H_INDEX = {-1, -1, 0, 2, 3, 1};
    /** {@code Direction.getClockWise()} / {@code getCounterClockWise()} for horizontal directions. */
    static final int[] CW = {-1, -1, EAST, WEST, NORTH, SOUTH};
    static final int[] CCW = {-1, -1, WEST, EAST, SOUTH, NORTH};

    static final int OTHER = 0, WIRE = 1, REPEATER = 2, TORCH = 3, WALL_TORCH = 4, LAMP = 5, TNT = 6,
            REDSTONE_BLOCK = 7, FALLING = 8;
    static final int NONE = 0, SIDE = 1, UP_SIDE = 2;
    static final int POWERED_BIT = 1 << 6, LIT_BIT = 1 << 6, LOCKED_BIT = 1 << 5;

    private static final byte[] KIND;
    private static final int[] INFO;
    /** Wire state for {@code sides (8 bits, N E S W) << 4 | power}. */
    private static final int[] WIRE_STATE = new int[256 << 4];
    /** Repeater state for {@code facing << 5 | (delay-1) << 3 | locked << 1 | powered} (facing a Direction ordinal). */
    private static final int[] REPEATER_STATE = new int[6 << 5];
    private static final int[] WALL_TORCH_STATE = new int[6 << 1];
    static final int TORCH_ON, TORCH_OFF, LAMP_ON, LAMP_OFF, AIR = 0;
    /** Blocks the wire logic singles out: {@code HOPPER} (wire survives on it), trapdoors (wire climbs them). */
    private static final boolean[] TRAPDOOR_BLOCK;
    private static final int HOPPER = BlockId.HOPPER;

    static {
        int blocks = BlockId.COUNT;
        int states = BlockData.firstState(blocks - 1) + BlockData.stateCount(blocks - 1);
        KIND = new byte[states];
        INFO = new int[states];
        TRAPDOOR_BLOCK = new boolean[blocks];
        for (int b = 0; b < blocks; b++) TRAPDOOR_BLOCK[b] = BlockData.name(b).endsWith("_trapdoor");

        // Wire: power + four sides.
        int wire = BlockId.REDSTONE_WIRE;
        int pPower = BlockData.property(wire, "power");
        int[] pSide = new int[4];
        String[] sideNames = {"north", "east", "south", "west"};
        for (int i = 0; i < 4; i++) pSide[i] = BlockData.property(wire, sideNames[i]);
        int[] sideValue = sideValueMap(pSide[0]); // value index -> NONE/SIDE/UP_SIDE
        for (int s = BlockData.firstState(wire), n = s + BlockData.stateCount(wire); s < n; s++) {
            int info = BlockData.intValue(s, pPower);
            int sides = 0;
            for (int i = 0; i < 4; i++) sides |= sideValue[BlockData.get(s, pSide[i])] << (2 * i);
            KIND[s] = WIRE;
            INFO[s] = info | sides << 4;
            WIRE_STATE[sides << 4 | info] = s;
        }

        // Repeater: facing, delay, locked, powered.
        int rep = BlockId.REPEATER;
        int pFacing = BlockData.property(rep, "facing"), pDelay = BlockData.property(rep, "delay");
        int pLocked = BlockData.property(rep, "locked"), pPowered = BlockData.property(rep, "powered");
        for (int s = BlockData.firstState(rep), n = s + BlockData.stateCount(rep); s < n; s++) {
            int facing = direction(BlockData.valueName(pFacing, BlockData.get(s, pFacing)));
            int delay = BlockData.intValue(s, pDelay) - 1;
            int locked = BlockData.boolValue(s, pLocked) ? 1 : 0, powered = BlockData.boolValue(s, pPowered) ? 1 : 0;
            KIND[s] = REPEATER;
            INFO[s] = facing | delay << 3 | locked << 5 | powered << 6;
            REPEATER_STATE[facing << 5 | delay << 3 | locked << 1 | powered] = s;
        }

        int torch = BlockId.REDSTONE_TORCH, pLit = BlockData.property(torch, "lit");
        int on = BlockData.withBool(BlockData.defaultState(torch), pLit, true);
        int off = BlockData.withBool(on, pLit, false);
        KIND[on] = TORCH;
        KIND[off] = TORCH;
        INFO[on] = LIT_BIT;
        TORCH_ON = on;
        TORCH_OFF = off;

        int wall = BlockId.REDSTONE_WALL_TORCH;
        int pwFacing = BlockData.property(wall, "facing"), pwLit = BlockData.property(wall, "lit");
        for (int s = BlockData.firstState(wall), n = s + BlockData.stateCount(wall); s < n; s++) {
            int facing = direction(BlockData.valueName(pwFacing, BlockData.get(s, pwFacing)));
            int lit = BlockData.boolValue(s, pwLit) ? 1 : 0;
            KIND[s] = WALL_TORCH;
            INFO[s] = facing | lit << 6;
            WALL_TORCH_STATE[facing << 1 | lit] = s;
        }

        int lamp = BlockId.REDSTONE_LAMP, plLit = BlockData.property(lamp, "lit");
        LAMP_ON = BlockData.withBool(BlockData.defaultState(lamp), plLit, true);
        LAMP_OFF = BlockData.withBool(LAMP_ON, plLit, false);
        KIND[LAMP_ON] = LAMP;
        KIND[LAMP_OFF] = LAMP;
        INFO[LAMP_ON] = LIT_BIT;

        for (int s = BlockData.firstState(BlockId.TNT), n = s + BlockData.stateCount(BlockId.TNT); s < n; s++) KIND[s] = TNT;
        KIND[BlockData.defaultState(BlockId.REDSTONE_BLOCK)] = REDSTONE_BLOCK;
        for (int s = 0; s < states; s++) {
            if (KIND[s] == OTHER && BlockData.is(s, BlockData.GRAVITY)) KIND[s] = FALLING;
        }
    }

    private RedstoneStates() {}

    private static int[] sideValueMap(int property) {
        int[] m = new int[BlockData.valueCount(property)];
        for (int i = 0; i < m.length; i++) {
            m[i] = switch (BlockData.valueName(property, i)) {
                case "up" -> UP_SIDE;
                case "side" -> SIDE;
                default -> NONE;
            };
        }
        return m;
    }

    private static int direction(String name) {
        return switch (name) {
            case "down" -> DOWN;
            case "up" -> UP;
            case "north" -> NORTH;
            case "south" -> SOUTH;
            case "west" -> WEST;
            case "east" -> EAST;
            default -> throw new IllegalStateException(name);
        };
    }

    // ---- queries -----------------------------------------------------------------------------------------------

    static int kind(int state) { return KIND[state]; }
    static int info(int state) { return INFO[state]; }

    static boolean isWire(int state) { return KIND[state] == WIRE; }
    static int wirePower(int state) { return INFO[state] & 15; }
    /** Side toward horizontal direction {@code dir}: {@link #NONE}, {@link #SIDE} or {@link #UP_SIDE}. */
    static int wireSide(int state, int dir) { return INFO[state] >>> (4 + 2 * H_INDEX[dir]) & 3; }
    static int wireSides(int state) { return INFO[state] >>> 4 & 0xFF; }
    static int wireState(int sides, int power) { return WIRE_STATE[sides << 4 | power]; }
    static int withWirePower(int state, int power) { return WIRE_STATE[(INFO[state] & ~15) | power]; }
    static int withWireSide(int state, int dir, int side) {
        int shift = 2 * H_INDEX[dir];
        int sides = wireSides(state) & ~(3 << shift) | side << shift;
        return WIRE_STATE[sides << 4 | wirePower(state)];
    }
    /** {@code RedStoneWireBlock.isDot}: all four sides NONE. */
    static boolean isDot(int state) { return wireSides(state) == 0; }
    /** {@code RedStoneWireBlock.isCross}: all four sides connected. */
    static boolean isCross(int state) {
        int s = wireSides(state);
        return (s & 3) != 0 && (s & 12) != 0 && (s & 48) != 0 && (s & 192) != 0;
    }
    /** All four sides SIDE: {@code RedStoneWireBlock.crossState}, with power {@code p}. */
    static int crossState(int p) { return WIRE_STATE[0x55 << 4 | p]; }
    static int defaultWire(int p) { return WIRE_STATE[p]; }

    static boolean isRepeater(int state) { return KIND[state] == REPEATER; }
    static int facing(int state) { return INFO[state] & 7; }
    static int repeaterDelayTicks(int state) { return ((INFO[state] >>> 3 & 3) + 1) * 2; }
    static boolean powered(int state) { return (INFO[state] & POWERED_BIT) != 0; }
    static boolean locked(int state) { return (INFO[state] & LOCKED_BIT) != 0; }
    static int withRepeater(int state, boolean locked, boolean powered) {
        int i = INFO[state];
        return REPEATER_STATE[(i & 7) << 5 | (i >>> 3 & 3) << 3 | (locked ? 2 : 0) | (powered ? 1 : 0)];
    }
    static int repeaterState(int facing, int delay, boolean locked, boolean powered) {
        return REPEATER_STATE[facing << 5 | (delay - 1) << 3 | (locked ? 2 : 0) | (powered ? 1 : 0)];
    }

    static boolean lit(int state) { return (INFO[state] & LIT_BIT) != 0; }
    static int wallTorchState(int facing, boolean lit) { return WALL_TORCH_STATE[facing << 1 | (lit ? 1 : 0)]; }

    static boolean isConductor(int state) { return BlockData.is(state, BlockData.REDSTONE_CONDUCTOR); }
    static boolean isSignalSource(int state) { return BlockData.is(state, BlockData.SIGNAL_SOURCE); }
    static boolean isAir(int state) { return BlockData.isAir(state); }
    static boolean isHopper(int state) { return BlockData.block(state) == HOPPER; }
    static boolean isTrapdoor(int state) { return TRAPDOOR_BLOCK[BlockData.block(state)]; }
    static int block(int state) { return BlockData.block(state); }
}
