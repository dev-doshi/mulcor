package dev.mulcor.core.region;

/** Entity type ids ({@code EntityTable.type}) and bot roles ({@code aux0}). */
public final class Entities {
    public static final int BOT = 1;
    public static final int TNT = 2;

    public static final int MINER = 0;
    public static final int NAVIGATOR = 1;
    public static final int CHESTER = 2;
    public static final int BOMBER = 3;
    /** A bot with no behaviour; used for static-load runs. */
    public static final int IDLE = 4;

    public static final int TNT_RADIUS = 3;

    private Entities() {}
}
