package dev.mulcor.memory;

/**
 * Fixed 64-byte wire layout of one entity. It is used for cross-region transfer messages and for bulk copies
 * in and out of an {@link EntityTable}.
 */
public final class EntityRecord {
    public static final int BYTES = 64;

    public static final long ID = 0;       // long: directory id
    public static final long X = 8;        // double
    public static final long Y = 16;       // double
    public static final long Z = 24;       // double
    public static final long VX = 32;      // float
    public static final long VY = 36;      // float
    public static final long VZ = 40;      // float
    public static final long TYPE = 44;    // int
    public static final long FLAGS = 48;   // int
    public static final long AUX0 = 52;    // int: behaviour state
    public static final long AUX1 = 56;    // int: behaviour target / timer
    public static final long AUX2 = 60;    // int: behaviour counter

    private EntityRecord() {}
}
