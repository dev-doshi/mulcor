package dev.mulcor.memory;

import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/** Shared constants and VarHandles for off-heap access. */
public final class Mem {
    /**
     * Padding unit for contended words. Apple M-series cores use 128-byte lines (and x86 adjacent-line
     * prefetch pairs 64-byte lines), so 128 is the safe choice on every target.
     */
    public static final long CACHE_LINE = 128;

    /** {@code (MemorySegment, long offset) -> long}. */
    public static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    /** {@code (MemorySegment, long offset) -> int}. */
    public static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

    private Mem() {}

    public static long align(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    public static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
