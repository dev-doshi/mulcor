package dev.mulcor.core.region;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;

/**
 * Vanilla {@code FluidState}s as packed ints, and the per-block-state tables the fluid port reads.
 *
 * <h2>Packed fluid state</h2>
 * bits 0-3 {@code amount} (0-8), bits 4-6 the {@code Fluid} ({@link #EMPTY}, {@link #WATER}, {@link #FLOWING_WATER},
 * {@link #LAVA}, {@link #FLOWING_LAVA}), bit 7 {@code FALLING}. Sources ({@code WATER}, {@code LAVA}) have amount 8.
 * Two packed states are equal exactly when vanilla's fluid states are the same object. The fluid "group"
 * ({@code Fluid.isSame}: water with flowing water, lava with flowing lava) is {@link #group}.
 *
 * <h2>Block states</h2>
 * <ul>
 *   <li>{@link #fluid}: {@code BlockState.getFluidState()}: water and lava blocks by {@code level}
 *       ({@code LiquidBlock}: 0 source, 1-7 flowing {@code 8 - level}, 8-15 falling 8); {@code waterlogged=true}
 *       states, bubble columns, kelp and seagrass hold a water source.</li>
 *   <li>{@link #container}: {@code instanceof LiquidBlockContainer}: waterloggable blocks accept a water source
 *       ({@code SimpleWaterloggedBlock.canPlaceLiquid}: {@code fluid == Fluids.WATER}); kelp and seagrass accept
 *       nothing.</li>
 *   <li>{@link #holdsAnyFluid}: {@code FlowingFluid.canHoldAnyFluid}.</li>
 * </ul>
 */
final class FluidStates {
    static final int EMPTY = 0, WATER = 1, FLOWING_WATER = 2, LAVA = 3, FLOWING_LAVA = 4;
    /** Groups ({@code Fluid.isSame}). */
    static final int NO_GROUP = 0, WATER_GROUP = 1, LAVA_GROUP = 2;
    static final int FALLING_BIT = 1 << 7;
    /** {@link #container} values. */
    static final int NOT_CONTAINER = 0, WATERLOGGABLE = 1, CLOSED_CONTAINER = 2;

    private static final short[] FLUID;
    private static final byte[] CONTAINER;
    private static final int[] WATERLOGGED;
    private static final boolean[] HOLDS_ANY;
    private static final boolean[] LIQUID_BLOCK;
    private static final int[] WATER_BLOCK = new int[16], LAVA_BLOCK = new int[16];

    static {
        int blocks = BlockId.COUNT;
        int states = BlockData.firstState(blocks - 1) + BlockData.stateCount(blocks - 1);
        FLUID = new short[states];
        CONTAINER = new byte[states];
        WATERLOGGED = new int[states];
        HOLDS_ANY = new boolean[states];
        LIQUID_BLOCK = new boolean[states];
        for (int b = 0; b < blocks; b++) {
            String name = BlockData.name(b);
            name = name.substring(name.indexOf(':') + 1);
            boolean water = b == BlockId.WATER, lava = b == BlockId.LAVA;
            int pLevel = water || lava ? BlockData.property(b, "level") : BlockData.NO_PROPERTY;
            int pWaterlogged = BlockData.property(b, "waterlogged");
            boolean plant = name.equals("kelp") || name.equals("kelp_plant") || name.equals("seagrass")
                    || name.equals("tall_seagrass");
            boolean waterSource = plant || name.equals("bubble_column");
            boolean holdsAny = holdsAnyFluidByBlock(name);
            for (int s = BlockData.firstState(b), n = s + BlockData.stateCount(b); s < n; s++) {
                WATERLOGGED[s] = s;
                if (water || lava) {
                    int level = BlockData.intValue(s, pLevel);
                    int group = water ? WATER_GROUP : LAVA_GROUP;
                    FLUID[s] = (short) (level == 0 ? source(group) : level < 8 ? flowing(group, 8 - level, false) : flowing(group, 8, true));
                    LIQUID_BLOCK[s] = true;
                    (water ? WATER_BLOCK : LAVA_BLOCK)[level] = s;
                } else if (pWaterlogged != BlockData.NO_PROPERTY) {
                    CONTAINER[s] = WATERLOGGABLE;
                    WATERLOGGED[s] = BlockData.withBool(s, pWaterlogged, true);
                    if (BlockData.boolValue(s, pWaterlogged)) FLUID[s] = (short) source(WATER_GROUP);
                } else if (waterSource) {
                    FLUID[s] = (short) source(WATER_GROUP);
                }
                if (plant) CONTAINER[s] = CLOSED_CONTAINER;
                // canHoldAnyFluid: containers always; else not if it blocks motion or is one of the listed blocks
                HOLDS_ANY[s] = CONTAINER[s] != NOT_CONTAINER || (!BlockData.is(s, BlockData.BLOCKS_MOTION) && holdsAny);
            }
        }
    }

    private FluidStates() {}

    /** The blocks {@code canHoldAnyFluid} excludes by type (doors, signs, ladders, sugar cane, bubble columns, portals). */
    private static boolean holdsAnyFluidByBlock(String name) {
        if (name.endsWith("_door") || name.endsWith("_sign")) return false;
        return switch (name) {
            case "ladder", "sugar_cane", "bubble_column", "nether_portal", "end_portal", "end_gateway", "structure_void" -> false;
            default -> true;
        };
    }

    // ---- packed fluid states --------------------------------------------------------------------------------------

    static int source(int group) { return (group == WATER_GROUP ? WATER : LAVA) << 4 | 8; }

    /** {@code FlowingFluid.getFlowing(amount, falling)}. */
    static int flowing(int group, int amount, boolean falling) {
        return (group == WATER_GROUP ? FLOWING_WATER : FLOWING_LAVA) << 4 | amount | (falling ? FALLING_BIT : 0);
    }

    static int type(int fs) { return fs >> 4 & 7; }
    static int amount(int fs) { return fs & 15; }
    static boolean falling(int fs) { return (fs & FALLING_BIT) != 0; }
    static boolean isEmpty(int fs) { return type(fs) == EMPTY; }
    static boolean isSource(int fs) { int t = type(fs); return t == WATER || t == LAVA; }
    static int group(int fs) { return groupOfType(type(fs)); }
    static int groupOfType(int type) { return (type + 1) >> 1; }
    /** The flowing type of a group ({@code FlowingFluid.getFlowing()}). */
    static int flowingType(int group) { return group == WATER_GROUP ? FLOWING_WATER : FLOWING_LAVA; }
    /** {@code FlowingFluid.isSourceBlockOfThisType}. */
    static boolean isSourceOf(int fs, int group) { return isSource(fs) && group(fs) == group; }
    /** {@code FluidState.getOwnHeight}: {@code amount / 9}. */
    static float ownHeight(int fs) { return amount(fs) / 9.0F; }

    /** {@code FluidState.createLegacyBlock}: the water or lava block with {@code level = getLegacyLevel}, or air. */
    static int legacyBlock(int fs) {
        if (isEmpty(fs)) return RedstoneStates.AIR;
        int level = isSource(fs) ? 0 : 8 - Math.min(amount(fs), 8) + (falling(fs) ? 8 : 0);
        return (group(fs) == WATER_GROUP ? WATER_BLOCK : LAVA_BLOCK)[level];
    }

    // ---- block states ---------------------------------------------------------------------------------------------

    /** {@code BlockState.getFluidState()}. */
    static int fluid(int state) { return FLUID[state]; }
    static boolean isLiquidBlock(int state) { return LIQUID_BLOCK[state]; }
    static int container(int state) { return CONTAINER[state]; }
    /** A waterloggable state with {@code waterlogged=true}. */
    static int waterlogged(int state) { return WATERLOGGED[state]; }
    static boolean holdsAnyFluid(int state) { return HOLDS_ANY[state]; }

    /** {@code FlowingFluid.canHoldSpecificFluid}: not a container, or one that accepts this fluid. */
    static boolean canHoldSpecificFluid(int state, int type) {
        return switch (CONTAINER[state]) {
            case NOT_CONTAINER -> true;
            case WATERLOGGABLE -> type == WATER;
            default -> false;
        };
    }

    /** {@code FlowingFluid.canHoldFluid}. */
    static boolean canHoldFluid(int state, int type) {
        return HOLDS_ANY[state] && canHoldSpecificFluid(state, type);
    }
}
