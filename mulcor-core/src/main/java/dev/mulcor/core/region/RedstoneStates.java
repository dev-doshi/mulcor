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
 *   <li>{@link #COMPARATOR}: bits 0-2 {@code facing} (the input side, as for repeaters), bit 5 {@code mode=subtract},
 *       bit 6 {@code powered}.</li>
 *   <li>{@link #OBSERVER}: bits 0-2 {@code facing} (the observed side; the output is the opposite face), bit 6
 *       {@code powered}.</li>
 *   <li>{@link #LEVER}, {@link #BUTTON}: bits 0-2 the connected direction
 *       ({@code FaceAttachedHorizontalDirectionalBlock.getConnectedDirection}: UP on the floor, DOWN on the ceiling,
 *       {@code facing} on a wall; the support is the opposite side), bit 3 a wooden button (pressed for 30 ticks
 *       instead of 20), bit 6 {@code powered}.</li>
 * </ul>
 *
 * <p>{@link #analogOutput} is {@code BlockState.getAnalogOutputSignal} for the blocks whose value is a function of
 * the state, 0 for the ones whose value lives in a block entity (containers, jukebox, lectern, ...: not ported), and
 * -1 for blocks without {@code hasAnalogOutputSignal}.
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
            REDSTONE_BLOCK = 7, FALLING = 8, COMPARATOR = 9, OBSERVER = 10, LEVER = 11, BUTTON = 12,
            PISTON = 13, PISTON_HEAD = 14, MOVING_PISTON = 15;
    static final int NONE = 0, SIDE = 1, UP_SIDE = 2;
    static final int POWERED_BIT = 1 << 6, LIT_BIT = 1 << 6, LOCKED_BIT = 1 << 5, SUBTRACT_BIT = 1 << 5, WOODEN_BIT = 1 << 3;
    /** Pistons, heads and moving pistons: bits 0-2 {@code facing}; bit 6 {@code extended} (base); bit 7 sticky. */
    static final int EXTENDED_BIT = 1 << 6, STICKY_BIT = 1 << 7;
    /** {@code ButtonBlock.ticksToStayPressed}: stone and polished blackstone buttons, wooden buttons. */
    static final int STONE_PRESS_TICKS = 20, WOODEN_PRESS_TICKS = 30;

    private static final byte[] KIND;
    private static final int[] INFO;
    /** Wire state for {@code sides (8 bits, N E S W) << 4 | power}. */
    private static final int[] WIRE_STATE = new int[256 << 4];
    /** Repeater state for {@code facing << 5 | (delay-1) << 3 | locked << 1 | powered} (facing a Direction ordinal). */
    private static final int[] REPEATER_STATE = new int[6 << 5];
    private static final int[] WALL_TORCH_STATE = new int[6 << 1];
    /** The state with {@code powered} flipped (comparator, observer, lever, button), else the state itself. */
    private static final int[] TOGGLE_POWERED;
    /** {@code state.cycle(...)} of the property a player's use cycles: repeater {@code delay}, comparator {@code mode}. */
    private static final int[] USE_CYCLE;
    private static final byte[] ANALOG;
    /** Piston base for {@code sticky << 4 | facing << 1 | extended}; head (short=false) and moving piston for
     * {@code sticky << 3 | facing}. */
    /** The state with {@code waterlogged=false}, or the state itself. */
    private static final int[] UNWATERLOGGED;
    private static final int[] PISTON_STATE = new int[32], HEAD_STATE = new int[16], MOVING_STATE = new int[16];
    static final int TORCH_ON, TORCH_OFF, LAMP_ON, LAMP_OFF, AIR = 0;
    /** Blocks the wire logic singles out: {@code HOPPER} (wire survives on it), trapdoors (wire climbs them). */
    private static final boolean[] TRAPDOOR_BLOCK;
    private static final int HOPPER = BlockId.HOPPER;

    static {
        int blocks = BlockId.COUNT;
        int states = BlockData.firstState(blocks - 1) + BlockData.stateCount(blocks - 1);
        KIND = new byte[states];
        INFO = new int[states];
        TOGGLE_POWERED = new int[states];
        USE_CYCLE = new int[states];
        ANALOG = new byte[states];
        for (int s = 0; s < states; s++) {
            TOGGLE_POWERED[s] = s;
            USE_CYCLE[s] = s;
            ANALOG[s] = -1;
        }
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
            USE_CYCLE[s] = BlockData.withInt(s, pDelay, delay == 3 ? 1 : delay + 2);
        }

        // Comparator: facing, mode, powered.
        int cmp = BlockId.COMPARATOR;
        int pcFacing = BlockData.property(cmp, "facing"), pMode = BlockData.property(cmp, "mode");
        int pcPowered = BlockData.property(cmp, "powered");
        for (int s = BlockData.firstState(cmp), n = s + BlockData.stateCount(cmp); s < n; s++) {
            int facing = direction(BlockData.valueName(pcFacing, BlockData.get(s, pcFacing)));
            boolean subtract = BlockData.valueName(pMode, BlockData.get(s, pMode)).equals("subtract");
            boolean powered = BlockData.boolValue(s, pcPowered);
            KIND[s] = COMPARATOR;
            INFO[s] = facing | (subtract ? SUBTRACT_BIT : 0) | (powered ? POWERED_BIT : 0);
            TOGGLE_POWERED[s] = BlockData.withBool(s, pcPowered, !powered);
            USE_CYCLE[s] = BlockData.with(s, pMode, (BlockData.get(s, pMode) + 1) % BlockData.valueCount(pMode));
        }

        // Observer: facing, powered.
        int obs = BlockId.OBSERVER;
        int poFacing = BlockData.property(obs, "facing"), poPowered = BlockData.property(obs, "powered");
        for (int s = BlockData.firstState(obs), n = s + BlockData.stateCount(obs); s < n; s++) {
            int facing = direction(BlockData.valueName(poFacing, BlockData.get(s, poFacing)));
            boolean powered = BlockData.boolValue(s, poPowered);
            KIND[s] = OBSERVER;
            INFO[s] = facing | (powered ? POWERED_BIT : 0);
            TOGGLE_POWERED[s] = BlockData.withBool(s, poPowered, !powered);
        }

        // Lever and buttons: face, facing, powered.
        for (int b = 0; b < blocks; b++) {
            String name = BlockData.name(b);
            boolean lever = b == BlockId.LEVER, button = name.endsWith("_button");
            if (!lever && !button) continue;
            boolean wooden = button && !name.equals("minecraft:stone_button") && !name.equals("minecraft:polished_blackstone_button");
            int pFace = BlockData.property(b, "face"), pbFacing = BlockData.property(b, "facing");
            int pbPowered = BlockData.property(b, "powered");
            for (int s = BlockData.firstState(b), n = s + BlockData.stateCount(b); s < n; s++) {
                int conn = switch (BlockData.valueName(pFace, BlockData.get(s, pFace))) {
                    case "floor" -> UP;
                    case "ceiling" -> DOWN;
                    default -> direction(BlockData.valueName(pbFacing, BlockData.get(s, pbFacing)));
                };
                boolean powered = BlockData.boolValue(s, pbPowered);
                KIND[s] = (byte) (lever ? LEVER : BUTTON);
                INFO[s] = conn | (wooden ? WOODEN_BIT : 0) | (powered ? POWERED_BIT : 0);
                TOGGLE_POWERED[s] = BlockData.withBool(s, pbPowered, !powered);
            }
        }

        analogOutputs(blocks);

        UNWATERLOGGED = new int[states];
        for (int b = 0; b < blocks; b++) {
            int pw = BlockData.property(b, "waterlogged");
            for (int s = BlockData.firstState(b), n = s + BlockData.stateCount(b); s < n; s++) {
                UNWATERLOGGED[s] = pw != BlockData.NO_PROPERTY ? BlockData.withBool(s, pw, false) : s;
            }
        }

        // Pistons: facing, extended. Heads: facing, type (normal/sticky), short. Moving pistons: facing, type.
        for (int sticky = 0; sticky < 2; sticky++) {
            int base = sticky == 1 ? BlockId.STICKY_PISTON : BlockId.PISTON;
            int pf = BlockData.property(base, "facing"), pe = BlockData.property(base, "extended");
            for (int s = BlockData.firstState(base), n = s + BlockData.stateCount(base); s < n; s++) {
                int f = direction(BlockData.valueName(pf, BlockData.get(s, pf)));
                int ext = BlockData.boolValue(s, pe) ? 1 : 0;
                KIND[s] = PISTON;
                INFO[s] = f | ext * EXTENDED_BIT | sticky * STICKY_BIT;
                PISTON_STATE[sticky << 4 | f << 1 | ext] = s;
            }
        }
        for (int[] blk : new int[][] {{BlockId.PISTON_HEAD, PISTON_HEAD}, {BlockId.MOVING_PISTON, MOVING_PISTON}}) {
            int b = blk[0];
            int pf = BlockData.property(b, "facing"), pt = BlockData.property(b, "type");
            int pShort = BlockData.property(b, "short");
            for (int s = BlockData.firstState(b), n = s + BlockData.stateCount(b); s < n; s++) {
                int f = direction(BlockData.valueName(pf, BlockData.get(s, pf)));
                int sticky = BlockData.valueName(pt, BlockData.get(s, pt)).equals("sticky") ? 1 : 0;
                KIND[s] = (byte) blk[1];
                INFO[s] = f | sticky * STICKY_BIT;
                boolean isShort = pShort != BlockData.NO_PROPERTY && BlockData.boolValue(s, pShort);
                if (isShort) continue;
                (blk[1] == PISTON_HEAD ? HEAD_STATE : MOVING_STATE)[sticky << 3 | f] = s;
            }
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

    /** {@code getAnalogOutputSignal} of the blocks that have one ({@code hasAnalogOutputSignal}). */
    private static void analogOutputs(int blocks) {
        for (int b = 0; b < blocks; b++) {
            String name = BlockData.name(b);
            if (name.startsWith("minecraft:")) name = name.substring(10);
            int first = BlockData.firstState(b), n = first + BlockData.stateCount(b);
            for (int s = first; s < n; s++) {
                int v = switch (name) {
                    // CakeBlock.getOutputSignal: (7 - bites) * 2; CandleCakeBlock: CakeBlock.FULL_CAKE_SIGNAL.
                    case "cake" -> (7 - BlockData.intValue(s, BlockData.property(b, "bites"))) * 2;
                    case "composter" -> BlockData.intValue(s, BlockData.property(b, "level"));
                    case "water_cauldron", "powder_snow_cauldron" -> BlockData.intValue(s, BlockData.property(b, "level"));
                    case "lava_cauldron" -> 3;
                    case "cauldron" -> 0;
                    case "end_portal_frame" -> BlockData.boolValue(s, BlockData.property(b, "eye")) ? 15 : 0;
                    case "beehive", "bee_nest" -> BlockData.intValue(s, BlockData.property(b, "honey_level"));
                    // RespawnAnchorBlock.getScaledChargeLevel(state, 15): floor(charges / 4 * 15).
                    case "respawn_anchor" -> (int) Math.floor(BlockData.intValue(s, BlockData.property(b, "charges")) / 4.0f * 15f);
                    // Block-entity backed: the value is not ported, but the block still has an analog output.
                    case "chest", "trapped_chest", "barrel", "hopper", "dispenser", "dropper", "furnace", "blast_furnace",
                         "smoker", "brewing_stand", "jukebox", "lectern", "chiseled_bookshelf", "decorated_pot", "crafter",
                         "command_block", "chain_command_block", "repeating_command_block", "sculk_sensor",
                         "calibrated_sculk_sensor" -> 0;
                    default -> name.endsWith("candle_cake") ? 14 : name.endsWith("shulker_box") ? 0 : -1;
                };
                ANALOG[s] = (byte) v;
            }
        }
    }

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

    static boolean isDiode(int state) { return KIND[state] == REPEATER || KIND[state] == COMPARATOR; }
    static boolean subtract(int state) { return (INFO[state] & SUBTRACT_BIT) != 0; }
    /** Comparator, observer, lever, button: the state with {@code powered} set to {@code p}. */
    static int withPowered(int state, boolean p) { return powered(state) == p ? state : TOGGLE_POWERED[state]; }
    static int useCycle(int state) { return USE_CYCLE[state]; }
    /** Lever, button: the connected direction (the support is on the opposite side). */
    static int connected(int state) { return INFO[state] & 7; }
    static int pressTicks(int state) { return (INFO[state] & WOODEN_BIT) != 0 ? WOODEN_PRESS_TICKS : STONE_PRESS_TICKS; }
    /** {@code getAnalogOutputSignal}, or -1 without {@code hasAnalogOutputSignal}. */
    static int analogOutput(int state) { return ANALOG[state]; }

    static int unwaterlogged(int state) { return UNWATERLOGGED[state]; }
    static boolean extended(int state) { return (INFO[state] & EXTENDED_BIT) != 0; }
    static boolean sticky(int state) { return (INFO[state] & STICKY_BIT) != 0; }
    static int pistonState(boolean sticky, int facing, boolean extended) {
        return PISTON_STATE[(sticky ? 16 : 0) | facing << 1 | (extended ? 1 : 0)];
    }
    /** {@code PISTON_HEAD[facing, type, short=false]}. */
    static int headState(boolean sticky, int facing) { return HEAD_STATE[(sticky ? 8 : 0) | facing]; }
    static int movingState(boolean sticky, int facing) { return MOVING_STATE[(sticky ? 8 : 0) | facing]; }

    static boolean lit(int state) { return (INFO[state] & LIT_BIT) != 0; }
    static int wallTorchState(int facing, boolean lit) { return WALL_TORCH_STATE[facing << 1 | (lit ? 1 : 0)]; }

    static boolean isConductor(int state) { return BlockData.is(state, BlockData.REDSTONE_CONDUCTOR); }
    static boolean isSignalSource(int state) { return BlockData.is(state, BlockData.SIGNAL_SOURCE); }
    static boolean isAir(int state) { return BlockData.isAir(state); }
    static boolean isHopper(int state) { return BlockData.block(state) == HOPPER; }
    static boolean isTrapdoor(int state) { return TRAPDOOR_BLOCK[BlockData.block(state)]; }
    static int block(int state) { return BlockData.block(state); }
}
