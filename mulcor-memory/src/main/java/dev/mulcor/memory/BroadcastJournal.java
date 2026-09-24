package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.CACHE_LINE;
import static dev.mulcor.memory.Mem.LONG;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Single-producer, many-reader broadcast ring of fixed-size records, entirely off-heap. A region appends the
 * events of its tick (block changes, entity moves, sounds, ...) and any number of readers (player sessions on
 * network threads) follow it at their own pace, each with its own cursor.
 *
 * <p><b>The producer never waits.</b> It overwrites the oldest record when the ring is full. A reader that falls
 * more than {@link #capacity()} records behind is <i>lapped</i>: {@link #read} reports {@link #LAPPED} instead of
 * returning overwritten data, and the reader resynchronizes (for a player session: resend the affected chunks
 * and entities) from {@link #oldest()}.
 *
 * <p><b>Per-slot seqlock.</b> Each slot starts with a sequence word: {@code -(pos + 1)} while record {@code pos} is
 * being written, {@code pos + 1} once it is complete. The writer stores the "writing" marker, fences stores, writes
 * the payload, then publishes {@code pos + 1} with a release store. A reader loads the word (acquire), copies the
 * payload into its own buffer, fences loads, and reloads the word: only if both loads saw {@code pos + 1} is the
 * copy exactly record {@code pos}. Records become visible in batches through {@link #publish()}, which advances
 * the published cursor with a release store (for regions: once, at the end of the tick).
 *
 * <p>Layout: {@code [pad][published cursor][pad][slot 0]...[slot n-1]}, slot = 8-byte sequence + payload.
 */
public final class BroadcastJournal {
    public static final int OK = 1, EMPTY = 0, LAPPED = -1;

    private static final long CURSOR = CACHE_LINE;
    private static final long SLOTS = 2 * CACHE_LINE;

    private final MemorySegment seg;
    private final int capacity;
    private final long mask;
    private final int payloadBytes;
    private final long stride;
    /** Producer-private: next position to write. */
    private long writePos;
    private boolean writing;

    public BroadcastJournal(NativeMemory memory, int capacity, int payloadBytes) {
        if (capacity < 2 || !Mem.isPowerOfTwo(capacity)) throw new IllegalArgumentException("capacity must be a power of two >= 2");
        if (payloadBytes <= 0) throw new IllegalArgumentException("payloadBytes must be positive");
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.payloadBytes = payloadBytes;
        this.stride = Long.BYTES + Mem.align(payloadBytes, Long.BYTES);
        this.seg = memory.allocate(SLOTS + stride * capacity);
        VarHandle.releaseFence(); // zeroed slots (sequence 0) are "never written" for every position
    }

    public int capacity() { return capacity; }
    public int payloadBytes() { return payloadBytes; }
    public MemorySegment segment() { return seg; }

    private long seqOffset(long pos) {
        return SLOTS + (pos & mask) * stride;
    }

    // ---- producer (one thread at a time; ownership may move across a happens-before edge such as an epoch) ----

    /**
     * Start writing the next record and return the offset of its payload in {@link #segment()}. Must be followed by
     * {@link #end()} before the next {@code begin}.
     */
    public long begin() {
        if (writing) throw new IllegalStateException("begin() without end()");
        writing = true;
        long pos = writePos;
        LONG.setOpaque(seg, seqOffset(pos), -(pos + 1));
        VarHandle.storeStoreFence(); // the marker is visible no later than any payload byte
        return seqOffset(pos) + Long.BYTES;
    }

    /** Finish the record started by {@link #begin()}. It becomes readable at the next {@link #publish()}. */
    public void end() {
        long pos = writePos++;
        LONG.setRelease(seg, seqOffset(pos), pos + 1);
        writing = false;
    }

    /** Copy {@code payloadBytes} from {@code src} as one record. */
    public void append(MemorySegment src, long srcOffset) {
        long off = begin();
        MemorySegment.copy(src, srcOffset, seg, off, payloadBytes);
        end();
    }

    /** Make every record written so far visible to readers. */
    public void publish() {
        LONG.setRelease(seg, CURSOR, writePos);
    }

    /** Producer view: records written (published or not). */
    public long written() {
        return writePos;
    }

    // ---- readers (any thread, any number) --------------------------------------------------------------------

    /** Number of records published so far: every position below this has been written at least once. */
    public long published() {
        return (long) LONG.getAcquire(seg, CURSOR);
    }

    /** The oldest position that may still be readable (older ones are certainly overwritten). */
    public long oldest() {
        return Math.max(0, published() - capacity);
    }

    /**
     * Copy record {@code pos} into {@code dst} at {@code dstOffset}. Returns {@link #OK}, {@link #EMPTY} if
     * {@code pos} is not published yet, or {@link #LAPPED} if it has been (or is being) overwritten.
     */
    public int read(long pos, MemorySegment dst, long dstOffset) {
        long pub = published();
        if (pos >= pub) return EMPTY;
        if (pub - pos > capacity) return LAPPED;
        long so = seqOffset(pos);
        long s1 = (long) LONG.getAcquire(seg, so);
        if (s1 != pos + 1) return LAPPED;
        MemorySegment.copy(seg, so + Long.BYTES, dst, dstOffset, payloadBytes);
        VarHandle.loadLoadFence(); // the payload loads complete before the re-check
        long s2 = (long) LONG.getOpaque(seg, so);
        return s2 == pos + 1 ? OK : LAPPED;
    }
}
