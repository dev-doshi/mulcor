package dev.mulcor.core.region;

/**
 * Layout of a client input record: 32 bytes, produced by the network decoder or by headless virtual clients
 * and consumed from a region's ingress ring.
 */
public final class Input {
    public static final long KIND = 0;
    public static final long ENTITY = 4;
    public static final long X = 8, Y = 12, Z = 16;
    public static final long A = 20, B = 24, C = 28;
    public static final int BYTES = 32;

    /** A,B = velocity x,z in thousandths of a block per tick. */
    public static final int MOVE = 1;
    /** X,Y,Z = block to break. */
    public static final int DIG = 2;
    /** X,Y,Z = position; A = block/item from the player's inventory. */
    public static final int PLACE = 3;
    /** A = chest; B = slot; C = count (> 0 take into player, < 0 put from player). */
    public static final int CHEST = 4;
    /** X,Y,Z = spawn a primed TNT; A = fuse ticks. */
    public static final int IGNITE = 5;
    /** Region-addressed (ENTITY ignored). X,Y,Z = position; A = block state; sets the block and updates neighbours. */
    public static final int SET_BLOCK = 6;
    /** Region-addressed. A = target region: send it a PROBE message this epoch. */
    public static final int PROBE_EMIT = 7;
    /** X,Y,Z = absolute position in thousandths of a block (client movement packet). */
    public static final int POSITION = 8;

    private Input() {}
}
