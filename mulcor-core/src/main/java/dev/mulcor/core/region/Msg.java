package dev.mulcor.core.region;

/** Layout of a cross-region message (fixed {@link #BYTES}, stored in region inbox rings). */
public final class Msg {
    public static final long KIND = 0;
    /** Destination region, used only for overflow retries and probes. */
    public static final long DST = 4;
    public static final long A = 8, B = 12, C = 16, D = 20, E = 24, F = 28;
    public static final long WORD = 32;
    public static final long BODY = 40;
    public static final int BYTES = 104;

    /** WORD = expected IN_TRANSIT ownership, BODY = EntityRecord. */
    public static final int TRANSFER = 1;
    /** A,B,C = x,y,z; E = requesting entity. The owner breaks the block and delivers the item. */
    public static final int BLOCK_BREAK = 2;
    /** A,B,C = x,y,z; D = block/item; E = requesting entity (refunded if occupied). */
    public static final int BLOCK_PLACE = 3;
    /** A,B,C = centre; D = radius. The receiver carves its own cells only. */
    public static final int EXPLOSION = 4;
    /** A,B,C = x,y,z of a redstone component to re-evaluate. */
    public static final int REDSTONE = 5;
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

    private Msg() {}
}
