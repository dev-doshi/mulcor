package dev.mulcor.core.region;

/** Entity type ids ({@code EntityTable.type}) and bot roles ({@code aux0}). */
public final class Entities {
    public static final int BOT = 1;
    public static final int TNT = 2;
    /** A network player: its position is client-authoritative, so the simulation only hands it between regions. */
    public static final int PLAYER = 3;

    public static final int MINER = 0;
    public static final int NAVIGATOR = 1;
    public static final int CHESTER = 2;
    public static final int BOMBER = 3;
    /** A bot with no behaviour; used for static-load runs. */
    public static final int IDLE = 4;

    public static final int TNT_RADIUS = 3;

    /** EntityTable flags bit: the entity is standing on the ground. */
    public static final int FLAG_ON_GROUND = 1;

    private Entities() {}
}
