package dev.mulcor.memory;

/**
 * Fixed 88-byte wire layout of one entity. It is used for cross-region transfer messages, bulk copies in and out
 * of an {@link EntityTable}, and the per-region entity snapshots neighbouring regions read.
 *
 * <p>Position and velocity are doubles, like vanilla's {@code Entity.position} and {@code deltaMovement}
 * ({@code Vec3}), so physics can match vanilla bit for bit.
 */
public final class EntityRecord {
    public static final int BYTES = 88;

    public static final long ID = 0;       // long: directory id
    public static final long X = 8;        // double
    public static final long Y = 16;       // double
    public static final long Z = 24;       // double
    public static final long VX = 32;      // double
    public static final long VY = 40;      // double
    public static final long VZ = 48;      // double
    public static final long TYPE = 56;    // int
    public static final long FLAGS = 60;   // int
    public static final long AUX0 = 64;    // int: behaviour state
    public static final long AUX1 = 68;    // int: behaviour target / timer
    public static final long AUX2 = 72;    // int: behaviour counter
    public static final long IN_YAW = 76;  // float: movement input yaw (degrees, vanilla yRot convention)
    public static final long IN_FWD = 80;  // float: movement input forward (vanilla zza before the 0.98 scale)

    private EntityRecord() {}
}
