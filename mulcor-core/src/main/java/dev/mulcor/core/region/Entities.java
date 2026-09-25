package dev.mulcor.core.region;

/** Entity type ids ({@code EntityTable.type}) and bot roles ({@code aux0}). */
public final class Entities {
    public static final int BOT = 1;
    public static final int TNT = 2;
    /** A network player: its position is client-authoritative, so the simulation only hands it between regions. */
    public static final int PLAYER = 3;
    /** A block falling under gravity (aux1 = block state); it becomes a block again when it lands. */
    public static final int FALLING_BLOCK = 4;
    /**
     * A dropped item ({@link ItemEntities}): aux1 = item | count << 16, aux0 = damage | tickCount % 40 << 16 |
     * health << 24, aux2 = age (a signed short) | pickup delay << 16. A count of 0 marks it dead.
     */
    public static final int ITEM = 5;

    public static final int MINER = 0;
    public static final int NAVIGATOR = 1;
    public static final int CHESTER = 2;
    public static final int BOMBER = 3;
    /** A bot with no behaviour; used for static-load runs. */
    public static final int IDLE = 4;


    /** EntityTable flags bit: the entity is standing on the ground. */
    public static final int FLAG_ON_GROUND = 1;
    /** EntityTable flags: the last move was blocked horizontally (mobs jump), and per-axis collision bits. */
    public static final int FLAG_H_COLLISION = 2, FLAG_X_COLLISION = 4, FLAG_Y_COLLISION = 8, FLAG_Z_COLLISION = 16;

    private Entities() {}
}
