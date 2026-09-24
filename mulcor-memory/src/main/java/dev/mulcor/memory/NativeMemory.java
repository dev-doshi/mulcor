package dev.mulcor.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owner of all long-lived native memory for one world. Segments are zero-filled, cache-line aligned, readable
 * from any thread, and released together on {@link #close()} rather than by the garbage collector.
 *
 * <p>Allocation is a setup-time operation: it creates a {@link MemorySegment} object and must never be called
 * from a tick loop.
 */
public final class NativeMemory implements AutoCloseable {
    private final Arena arena;
    private final boolean closeable;
    private final AtomicLong reservedBytes = new AtomicLong();

    /** Explicitly scoped memory, freed by {@link #close()}. */
    public NativeMemory() {
        this(Arena.ofShared(), true);
    }

    private NativeMemory(Arena arena, boolean closeable) {
        this.arena = arena;
        this.closeable = closeable;
    }

    /**
     * GC-scoped memory for short-lived, high-churn owners (jcstress states, throwaway fixtures): freed when
     * unreachable, and {@link #close()} is a no-op.
     */
    public static NativeMemory auto() {
        return new NativeMemory(Arena.ofAuto(), false);
    }

    public MemorySegment allocate(long bytes) {
        return allocate(bytes, Mem.CACHE_LINE);
    }

    public MemorySegment allocate(long bytes, long alignment) {
        reservedBytes.addAndGet(bytes);
        return arena.allocate(bytes, alignment);
    }

    public long reservedBytes() {
        return reservedBytes.get();
    }

    @Override
    public void close() {
        if (closeable) {
            arena.close();
        }
    }
}
