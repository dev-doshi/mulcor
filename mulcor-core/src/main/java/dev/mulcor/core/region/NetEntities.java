package dev.mulcor.core.region;

import dev.mulcor.memory.Mem;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A region's network entity snapshot (roadmap §3.5, widened to every entity): after each tick the region copies all
 * its entities into one of two buffers (by epoch parity), for player sessions on network threads to track entities.
 *
 * <h2>Buffer layout</h2>
 * {@code [seq long][count int][pad]} then {@code count} records of {@link #BYTES}. {@code seq} is {@code 2e + 1} while
 * the buffer is being written for epoch {@code e} and {@code 2e + 2} once it is complete (release store). A reader
 * takes the buffer with the higher complete sequence, reads it in place, and re-checks the sequence afterwards: an
 * unchanged value means what it read is exactly that epoch's snapshot (a seqlock; the writer never waits).
 *
 * <h2>Record</h2>
 * entity id (int), type ({@link Entities}), position, velocity (doubles), yaw and pitch (floats, degrees), flags
 * ({@link Entities#FLAG_ON_GROUND}), data (a falling block's state); for players the packed {@link Presence}, the
 * swing counters and the held item id (0 for other entities).
 */
public final class NetEntities {
    public static final long SEQ = 0, COUNT = 8, HEADER = 16;
    public static final long ID = 0, TYPE = 4, X = 8, Y = 16, Z = 24, VX = 32, VY = 40, VZ = 48, YAW = 56, PITCH = 60,
            FLAGS = 64, DATA = 68, META = 72, SWING = 76, HELD = 80;
    public static final int BYTES = 88; // a multiple of 8: the doubles stay aligned

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private NetEntities() {}

    /** Buffer {@code b} (0 or 1) of a snapshot segment holding {@code capacity} records per buffer. */
    public static long bufferOffset(int b, int capacity) {
        return b * (HEADER + (long) capacity * BYTES);
    }

    /** Sequence word of the buffer at {@code base} (acquire). */
    public static long seq(MemorySegment seg, long base) {
        return (long) Mem.LONG.getAcquire(seg, base + SEQ);
    }

    public static int count(MemorySegment seg, long base) {
        return seg.get(I, base + COUNT);
    }

    /** Offset of record {@code i} of the buffer at {@code base}. */
    public static long record(long base, int i) {
        return base + HEADER + (long) i * BYTES;
    }

    static void beginWrite(MemorySegment seg, long base, long epoch) {
        Mem.LONG.setRelease(seg, base + SEQ, 2 * epoch + 1);
        java.lang.invoke.VarHandle.storeStoreFence();
    }

    static void endWrite(MemorySegment seg, long base, long epoch, int count) {
        seg.set(I, base + COUNT, count);
        Mem.LONG.setRelease(seg, base + SEQ, 2 * epoch + 2);
    }
}
