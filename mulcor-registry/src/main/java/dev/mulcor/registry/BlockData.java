package dev.mulcor.registry;

import static dev.mulcor.registry.Registry.*;

import java.util.Map;

/**
 * Vanilla block states. A state id is vanilla's global palette id (what the protocol, Anvil palettes via
 * {@link #parse} and Mulcor's 16-bit {@code BlockStorage} all use). Everything here is a {@code static final} array
 * read, so it is allocation-free and safe from any thread; the methods that take or return {@code String}s are for
 * cold paths (loading, commands) only.
 *
 * <h2>Properties</h2>
 * Vanilla lays out a block's states as a mixed-radix number over its properties in declaration order, the last
 * property varying fastest ({@code StateDefinition} order; checked for every state by the generator). So a property
 * value is pure arithmetic: {@code index = (state - firstState) / stride % valueCount}, and changing it adds
 * {@code (new - old) × stride}. Look a property up once by name ({@link #property}, cold) and keep the returned
 * handle in a {@code static final int}.
 */
public final class BlockData {
    public static final int SOLID = 1, SOLID_BLOCKING = 1 << 1, BLOCKS_MOTION = 1 << 2, REDSTONE_CONDUCTOR = 1 << 3,
            FLUID = 1 << 4, FLAMMABLE = 1 << 5, CAN_RESPAWN_IN = 1 << 6, AIR = 1 << 7, LIQUID = 1 << 8,
            REPLACEABLE = 1 << 9, OCCLUDES = 1 << 10, SIGNAL_SOURCE = 1 << 11, REQUIRES_TOOL = 1 << 12,
            GRAVITY = 1 << 13;
    /** Vanilla {@code BlockBehaviour.useShapeForLightOcclusion(state)} (slabs except double, stairs, snow, ...). */
    public static final int USE_SHAPE_FOR_LIGHT = 1 << 14;

    /** {@code PushReaction}: how pistons treat the block. */
    public static final int PUSH_NORMAL = 0, PUSH_DESTROY = 1, PUSH_BLOCK = 2, PUSH_IGNORE = 3, PUSH_ONLY = 4;

    /** Returned by {@link #property} when the block does not have that property. */
    public static final int NO_PROPERTY = -1;

    private BlockData() {}

    // ---- per state -------------------------------------------------------------------------------------------

    public static int block(int state) { return STATE_BLOCK[state]; }
    public static int flags(int state) { return STATE_FLAGS[state]; }
    public static boolean is(int state, int flag) { return (STATE_FLAGS[state] & flag) != 0; }
    public static boolean isAir(int state) { return (STATE_FLAGS[state] & AIR) != 0; }
    /** Vanilla {@code BlockState.getLightEmission()}: 0..15. */
    public static int lightEmission(int state) { return STATE_EMISSION[state]; }
    /** Vanilla {@code BlockState.getLightBlock()}: how much light the state absorbs, 0..15. */
    public static int lightBlock(int state) { return STATE_OPACITY[state]; }
    public static int collisionShape(int state) { return STATE_COLLISION[state]; }
    public static int outlineShape(int state) { return STATE_OUTLINE[state]; }
    public static int occlusionShape(int state) { return STATE_OCCLUSION[state]; }
    public static int interactionShape(int state) { return STATE_INTERACTION[state]; }
    public static boolean isValid(int state) { return state >= 0 && state < STATE_BLOCK.length; }

    // ---- per block -------------------------------------------------------------------------------------------

    public static int defaultState(int block) { return BLOCK_DEFAULT_STATE[block]; }
    public static int firstState(int block) { return BLOCK_FIRST_STATE[block]; }
    public static int stateCount(int block) { return BLOCK_STATE_COUNT[block]; }
    public static float hardness(int block) { return BLOCK_HARDNESS[block]; }
    public static float explosionResistance(int block) { return BLOCK_RESISTANCE[block]; }
    public static float friction(int block) { return BLOCK_FRICTION[block]; }
    public static float speedFactor(int block) { return BLOCK_SPEED_FACTOR[block]; }
    public static float jumpFactor(int block) { return BLOCK_JUMP_FACTOR[block]; }
    /** Item id of the block's item form, or -1. */
    public static int item(int block) { return BLOCK_ITEM[block]; }
    /** Block entity type id, or -1. */
    public static int blockEntityType(int block) { return BLOCK_ENTITY_TYPE[block]; }
    public static int pushReaction(int block) { return BLOCK_PUSH_REACTION[block]; }
    public static String name(int block) { return BLOCK_NAME[block]; }
    public static String soundType(int block) { return BLOCK_SOUND_TYPE[block]; }
    public static boolean isOf(int state, int block) { return STATE_BLOCK[state] == block; }

    // ---- properties ------------------------------------------------------------------------------------------

    /** Cold: handle of {@code block}'s property {@code name}, or {@link #NO_PROPERTY}. */
    public static int property(int block, String name) {
        int base = BLOCK_PROP_BASE[block];
        for (int i = 0; i < BLOCK_PROP_COUNT[block]; i++) {
            if (PROP_NAME[base + i].equals(name)) return base + i;
        }
        return NO_PROPERTY;
    }

    /** Cold: index of {@code value} in the property's value list, or -1. */
    public static int valueIndex(int property, String value) {
        String[] values = PROP_VALUES[property];
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    public static int valueCount(int property) { return PROP_VALUE_COUNT[property]; }
    public static String propertyName(int property) { return PROP_NAME[property]; }
    public static String valueName(int property, int index) { return PROP_VALUES[property][index]; }

    /** Index of the property's value in {@code state}. The state must belong to the property's block. */
    public static int get(int state, int property) {
        return (state - BLOCK_FIRST_STATE[STATE_BLOCK[state]]) / PROP_STRIDE[property] % PROP_VALUE_COUNT[property];
    }

    /** {@code state} with the property set to value index {@code index}. */
    public static int with(int state, int property, int index) {
        return state + (index - get(state, property)) * PROP_STRIDE[property];
    }

    /**
     * Numeric value of an integer property (e.g. {@code power}, {@code level}, {@code age}, {@code delay}), whose
     * values are consecutive integers; {@code values[0]} is its minimum (0 for most, 1 for repeater delay).
     */
    public static int intValue(int state, int property) {
        return get(state, property) + PROP_MIN[property];
    }

    /** {@code state} with an integer property set to {@code value}. */
    public static int withInt(int state, int property, int value) {
        return with(state, property, value - PROP_MIN[property]);
    }

    /** Boolean property value (vanilla lists boolean values as {@code [true, false]}). */
    public static boolean boolValue(int state, int property) {
        return get(state, property) == PROP_TRUE[property];
    }

    public static int withBool(int state, int property, boolean value) {
        return with(state, property, value ? PROP_TRUE[property] : 1 - PROP_TRUE[property]);
    }

    /** Per property: numeric value of index 0 for integer properties (else 0). */
    private static final int[] PROP_MIN = new int[PROP_NAME.length];
    /** Per property: the index of "true" for boolean properties (else -1). */
    private static final int[] PROP_TRUE = new int[PROP_NAME.length];

    static {
        for (int p = 0; p < PROP_NAME.length; p++) {
            String[] v = PROP_VALUES[p];
            PROP_TRUE[p] = v.length == 2 && v[0].equals("true") ? 0 : v.length == 2 && v[1].equals("true") ? 1 : -1;
            try {
                PROP_MIN[p] = Integer.parseInt(v[0]);
            } catch (NumberFormatException notNumeric) {
                PROP_MIN[p] = 0;
            }
        }
    }

    // ---- names (cold) ----------------------------------------------------------------------------------------

    private static final Map<String, Integer> BLOCK_BY_NAME = new java.util.HashMap<>();

    static {
        for (int b = 0; b < BLOCK_NAME.length; b++) BLOCK_BY_NAME.put(BLOCK_NAME[b], b);
    }

    /** Cold: block id for a namespaced name ({@code "minecraft:stone"}, or bare {@code "stone"}), or -1. */
    public static int blockByName(String name) {
        Integer b = BLOCK_BY_NAME.get(name.indexOf(':') < 0 ? "minecraft:" + name : name);
        return b == null ? -1 : b;
    }

    /**
     * Cold: the state for a block name and property map, as in an Anvil palette entry ({@code Name} +
     * {@code Properties}). Missing properties keep the block's default; unknown names or values give -1.
     */
    public static int parse(String name, Map<String, String> properties) {
        int block = blockByName(name);
        if (block < 0) return -1;
        int state = BLOCK_DEFAULT_STATE[block];
        for (var e : properties.entrySet()) {
            int p = property(block, e.getKey());
            if (p == NO_PROPERTY) return -1;
            int idx = valueIndex(p, e.getValue());
            if (idx < 0) return -1;
            state = with(state, p, idx);
        }
        return state;
    }

    /** Cold: vanilla's string form of a state, e.g. {@code minecraft:redstone_wire[east=none,...,west=side]}. */
    public static String toString(int state) {
        int block = STATE_BLOCK[state];
        StringBuilder sb = new StringBuilder(BLOCK_NAME[block]);
        int n = BLOCK_PROP_COUNT[block];
        if (n > 0) {
            sb.append('[');
            for (int i = 0; i < n; i++) {
                int p = BLOCK_PROP_BASE[block] + i;
                if (i > 0) sb.append(',');
                sb.append(PROP_NAME[p]).append('=').append(PROP_VALUES[p][get(state, p)]);
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** Cold: number of properties of {@code block} and the handle of its i-th one (for palette writers). */
    public static int propertyCount(int block) { return BLOCK_PROP_COUNT[block]; }
    public static int propertyAt(int block, int i) { return BLOCK_PROP_BASE[block] + i; }
}
