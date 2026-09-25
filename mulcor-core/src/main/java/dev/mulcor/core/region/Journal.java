package dev.mulcor.core.region;

/**
 * Layout of a region's egress journal record ({@code World.journals}, a {@code BroadcastJournal} per region slot):
 * what changed in the region's tick, for player sessions to forward to clients (roadmap §5.3).
 * <ul>
 *   <li>{@link #POS}: a packed block position ({@code ScheduledTicks.pack}, the protocol's position layout).</li>
 *   <li>{@link #META}: kind in bits 56-63; for {@link #BLOCK_EVENT}: event id a in bits 48-55, parameter b in bits
 *       40-47, block type id in bits 0-31.</li>
 * </ul>
 * A {@link #BLOCK} record only says the block at the position changed: readers send the state the block has when
 * they read it, so records from different journals (after a region split or merge) need no ordering.
 */
public final class Journal {
    public static final long POS = 0, META = 8;
    public static final int BYTES = 16;
    /** Records per region journal; a reader that falls further behind resynchronizes (resends its chunks). */
    public static final int CAPACITY = 1 << 14;
    public static final int BLOCK = 1, BLOCK_EVENT = 2;
    /** A player picked up an item entity: POS = player id << 32 | item entity id, META low bits = count. */
    public static final int COLLECT = 3;

    private Journal() {}

    public static int kind(long meta) { return (int) (meta >>> 56); }
    public static int eventA(long meta) { return (int) (meta >>> 48) & 0xFF; }
    public static int eventB(long meta) { return (int) (meta >>> 40) & 0xFF; }
    public static int eventBlock(long meta) { return (int) meta; }

    static long blockEvent(int a, int b, int block) {
        return (long) BLOCK_EVENT << 56 | (long) (a & 0xFF) << 48 | (long) (b & 0xFF) << 40 | (block & 0xFFFFFFFFL);
    }
}
