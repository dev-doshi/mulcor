package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.INT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap block states for a finite world: 16-bit state ids, with 16³ sections drawn from a shared slab.
 *
 * <p>All-air sections take no slab memory. A section is taken from a lock-free {@link TaggedFreeList} on its
 * first non-air write. When its last block becomes air (as when TNT carves it out) it is <i>retired</i>.
 * Any region thread may take or retire sections at the same time.
 *
 * <p><b>Epoch-based reclamation:</b> retired sections are only zeroed and returned to the pool by
 * {@link #reclaim()}, which the engine calls in its single-threaded commit phase once every region has
 * finished the epoch. So a concurrent {@link #getShared} can never see a section that has been recycled into
 * another chunk.
 *
 * <p><b>Access rules:</b>
 * <ul>
 *   <li>Only the region that owns a chunk writes its blocks. {@link #get}/{@link #set} are plain accesses for
 *       that owner; ownership moves between threads only across an epoch barrier, which gives happens-before.</li>
 *   <li>Other regions may read with {@link #getShared}. It acquires the section table entry, so it sees a fully
 *       initialized section and a value written in the current or a previous epoch. Cross-region block reads
 *       are therefore eventually consistent, like cross-region messages.</li>
 * </ul>
 *
 * <p>Coordinates: {@code x ∈ [0, chunksX·16)}, {@code z ∈ [0, chunksZ·16)},
 * {@code y ∈ [minY, minY + sections·16)}. Reads outside the world return air and writes outside it fail.
 */
public final class BlockStorage {
    public static final int SECTION_BLOCKS = 16 * 16 * 16;
    public static final long SECTION_BYTES = SECTION_BLOCKS * 2L;
    public static final int AIR = 0;
    /** Returned by {@link #set} when the write could not be applied. */
    public static final int FAILED = -1;

    private final int chunksX;
    private final int chunksZ;
    private final int sections;
    private final int minY;
    /** int per (chunk, section): pool index + 1, or 0 for an all-air section with no slab memory. */
    private final MemorySegment table;
    /** int per pool section: number of non-air blocks. */
    private final MemorySegment counts;
    private final MemorySegment slab;
    private final MemorySegment zeroSection;
    private final TaggedFreeList pool;
    private final TaggedFreeList retired;

    public BlockStorage(NativeMemory memory, int chunksX, int chunksZ, int minY, int sections, int poolSections) {
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.minY = minY;
        this.sections = sections;
        this.table = memory.allocate((long) chunksX * chunksZ * sections * Integer.BYTES);
        this.counts = memory.allocate((long) poolSections * Integer.BYTES);
        this.slab = memory.allocate(poolSections * SECTION_BYTES);
        this.zeroSection = memory.allocate(SECTION_BYTES);
        this.pool = new TaggedFreeList(memory, poolSections);
        this.retired = new TaggedFreeList(memory, poolSections, false);
    }

    public int chunksX() { return chunksX; }
    public int chunksZ() { return chunksZ; }
    public int sections() { return sections; }
    public int minY() { return minY; }
    public int maxYExclusive() { return minY + sections * 16; }
    public int sizeX() { return chunksX * 16; }
    public int sizeZ() { return chunksZ * 16; }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && z >= 0 && x < chunksX * 16 && z < chunksZ * 16 && y >= minY && y < minY + sections * 16;
    }

    private long tableOffset(int x, int y, int z) {
        long index = (((long) (z >> 4) * chunksX) + (x >> 4)) * sections + ((y - minY) >> 4);
        return index * Integer.BYTES;
    }

    private static long blockOffset(int ref, int x, int y, int z) {
        int local = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return (ref - 1) * SECTION_BYTES + ((long) local << 1);
    }

    /** Read from a thread that does not own the chunk. See the class notes. */
    public int getShared(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return AIR;
        }
        int ref = (int) INT.getAcquire(table, tableOffset(x, y, z));
        if (ref == 0) {
            return AIR;
        }
        return Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, blockOffset(ref, x, y, z)));
    }

    public int get(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return AIR;
        }
        int ref = table.get(ValueLayout.JAVA_INT, tableOffset(x, y, z));
        if (ref == 0) {
            return AIR;
        }
        return Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, blockOffset(ref, x, y, z)));
    }

    /**
     * Set a block state. Returns the previous state, or {@link #FAILED} if the position is out of bounds or
     * the section pool is exhausted.
     */
    public int set(int x, int y, int z, int state) {
        if (!inBounds(x, y, z) || state < 0 || state > 0xFFFF) {
            return FAILED;
        }
        long tableOff = tableOffset(x, y, z);
        int ref = table.get(ValueLayout.JAVA_INT, tableOff);
        if (ref == 0) {
            if (state == AIR) {
                return AIR;
            }
            int section = pool.pop();
            if (section < 0) {
                return FAILED;
            }
            ref = section + 1;
            slab.set(ValueLayout.JAVA_SHORT, blockOffset(ref, x, y, z), (short) state);
            counts.set(ValueLayout.JAVA_INT, (long) section * Integer.BYTES, 1);
            INT.setRelease(table, tableOff, ref);
            return AIR;
        }
        long off = blockOffset(ref, x, y, z);
        int prev = Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, off));
        if (prev == state) {
            return prev;
        }
        slab.set(ValueLayout.JAVA_SHORT, off, (short) state);
        long countOff = (long) (ref - 1) * Integer.BYTES;
        if (prev == AIR) {
            counts.set(ValueLayout.JAVA_INT, countOff, counts.get(ValueLayout.JAVA_INT, countOff) + 1);
        } else if (state == AIR) {
            int remaining = counts.get(ValueLayout.JAVA_INT, countOff) - 1;
            counts.set(ValueLayout.JAVA_INT, countOff, remaining);
            if (remaining == 0) {
                INT.setRelease(table, tableOff, 0);
                retired.push(ref - 1);
            }
        }
        return prev;
    }

    /**
     * Zero every retired section and return it to the pool. Call only when no thread can be reading blocks
     * (the engine's commit phase). Returns the number of sections reclaimed.
     */
    public int reclaim() {
        int n = 0;
        for (int section = retired.pop(); section >= 0; section = retired.pop()) {
            MemorySegment.copy(zeroSection, 0, slab, section * SECTION_BYTES, SECTION_BYTES);
            pool.push(section);
            n++;
        }
        return n;
    }

    /** Pool index + 1 of a section, or 0 if it is all air. For zero-copy encoders that read {@link #slab()}. */
    public int sectionRef(int chunkX, int chunkZ, int sectionY) {
        long index = (((long) chunkZ * chunksX) + chunkX) * sections + sectionY;
        return table.get(ValueLayout.JAVA_INT, index * Integer.BYTES);
    }

    /** Byte offset of a section's 4096 shorts (YZX order) in {@link #slab()}. */
    public static long sectionOffset(int ref) {
        return (ref - 1) * SECTION_BYTES;
    }

    public int nonAirCount(int ref) {
        return ref == 0 ? 0 : counts.get(ValueLayout.JAVA_INT, (long) (ref - 1) * Integer.BYTES);
    }

    public MemorySegment slab() {
        return slab;
    }

    /** Sections currently in use: O(pool) scan, for tests and reports. */
    public int allocatedSections() {
        int n = 0;
        long entries = table.byteSize() / Integer.BYTES;
        for (long i = 0; i < entries; i++) {
            if ((int) INT.get(table, i * Integer.BYTES) != 0) {
                n++;
            }
        }
        return n;
    }
}
