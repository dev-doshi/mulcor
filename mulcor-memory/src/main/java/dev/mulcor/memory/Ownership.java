package dev.mulcor.memory;

/**
 * Packing for an entity's 64-bit ownership word: {@code epoch:32 | region:24 | state:8}.
 *
 * <p>Every ownership change is one CAS on this word. So a record that carries a stale word (an old epoch, the
 * wrong region, or a replayed transfer) can never claim the entity, and at most one region owns it in any epoch.
 */
public final class Ownership {
    public static final int FREE = 0;
    public static final int OWNED = 1;
    public static final int IN_TRANSIT = 2;
    public static final int DEAD = 3;

    public static final int MAX_REGION = (1 << 24) - 1;

    private Ownership() {}

    public static long pack(int state, int region, int epoch) {
        return ((long) epoch << 32) | ((long) (region & MAX_REGION) << 8) | (state & 0xFF);
    }

    public static int state(long word) {
        return (int) (word & 0xFF);
    }

    public static int region(long word) {
        return (int) ((word >>> 8) & MAX_REGION);
    }

    public static int epoch(long word) {
        return (int) (word >>> 32);
    }

    public static String toString(long word) {
        String s = switch (state(word)) {
            case FREE -> "FREE";
            case OWNED -> "OWNED";
            case IN_TRANSIT -> "IN_TRANSIT";
            case DEAD -> "DEAD";
            default -> "?" + state(word);
        };
        return s + "(region=" + region(word) + ", epoch=" + epoch(word) + ")";
    }
}
