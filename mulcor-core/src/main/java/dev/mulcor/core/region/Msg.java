package dev.mulcor.core.region;

/** Layout of a cross-region message (fixed {@link #BYTES}, stored in region inbox rings). */
public final class Msg {
    public static final long KIND = 0;
    /** Destination region, used only for overflow retries and probes. */
    public static final long DST = 4;
    public static final long A = 8, B = 12, C = 16, D = 20, E = 24, F = 28;
    public static final long WORD = 32;
    /**
     * Epoch at which the effect is due. Stamped with the sender's epoch by default; a delayed effect carries
     * {@code sendEpoch + delay}, so it fires at the same epoch as it would have inside one region.
     */
    public static final long DEADLINE = 40;
    public static final long BODY = 48;
    public static final int BYTES = 48 + dev.mulcor.memory.EntityRecord.BYTES;

    /** WORD = expected IN_TRANSIT ownership, BODY = EntityRecord. */
    public static final int TRANSFER = 1;
    /** A,B,C = x,y,z; E = requesting entity. The owner breaks the block and delivers the item. */
    public static final int BLOCK_BREAK = 2;
    /** A,B,C = x,y,z; D = block/item; E = requesting entity (refunded if occupied). */
    public static final int BLOCK_PLACE = 3;
    /** BODY = centre x, y, z (doubles) and power (float). The receiver pushes its own entities (knockback). */
    public static final int EXPLOSION = 4;
    /**
     * A,B,C = x,y,z of a block that gets a neighbour update ({@code neighborChanged}) crossing a region border.
     * DEADLINE = the sender's game time, which the receiver runs the update at (so ticks it schedules are due when
     * they would have been inside one region).
     */
    public static final int NEIGHBOR_UPDATE = 5;
    /** A = chest, B = slot, C = count, D = requesting entity. */
    public static final int INV_TAKE = 6;
    /** A = chest, B = item, C = count, D = requesting entity. Carries items. */
    public static final int INV_PUT = 7;
    /** A = entity, B = item, C = count. Carries items to the entity's owner. */
    public static final int INV_DELIVER = 8;
    /** BODY = an {@link Input} record forwarded to the entity's current owner. */
    public static final int INPUT = 9;
    /** WORD = epoch sent, DST = target. Test-only: measures delivery latency in epochs. */
    public static final int PROBE = 10;
    /**
     * A,B,C = x,y,z; D = priority; E = block id; DEADLINE = due epoch. A scheduled block tick whose position changed
     * owner (region split or merge) is handed to the new owner with its due epoch intact.
     */
    public static final int SCHEDULED_TICK = 11;
    /**
     * A = count (≤ {@link #BATCH}), WORD = explosion seed, BODY = packed block positions an explosion selected in
     * the receiver's cells. The exploding region computes the whole selection from the world as it was before the
     * blast (as vanilla does) and each owner destroys its share.
     */
    public static final int EXPLOSION_BLOCKS = 12;
    /**
     * A,B,C = x,y,z of a block that gets a shape update crossing a region border; D = direction (Direction ordinal)
     * of the neighbour that changed, E = that neighbour's state, F = update flags, WORD = recursion left;
     * DEADLINE = the sender's game time.
     */
    public static final int SHAPE_UPDATE = 13;
    /** A,B,C = x,y,z; E = fluid type ({@code FluidStates}); DEADLINE = due epoch. A fluid tick that changed owner. */
    public static final int FLUID_TICK = 14;
    /**
     * A,B,C = x,y,z a fluid spreads into across a region border; D = direction it flows; E = the packed fluid state;
     * F = the spreading fluid's group; DEADLINE = the sender's game time.
     */
    public static final int FLUID_SPREAD = 15;
    /** Positions per {@link #EXPLOSION_BLOCKS} message. */
    public static final int BATCH = dev.mulcor.memory.EntityRecord.BYTES / 8;

    private Msg() {}
}
