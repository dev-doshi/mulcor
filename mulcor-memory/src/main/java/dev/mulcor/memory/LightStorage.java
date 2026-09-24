package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.INT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap light levels for a finite world: one 4-bit value per block, kept per 16³ section in vanilla's nibble
 * layout ({@code DataLayer}: 2048 bytes, index {@code y << 8 | z << 4 | x}, even index in the low nibble), so a
 * section can be copied to the wire or to disk as-is.
 *
 * <p>Sections cover the block storage's height plus one section below and one above, like vanilla's light
 * sections. A section whose every value is equal takes no memory: its table entry stores {@code -value} (0..-15)
 * instead of a pool reference. The first differing write materializes a 2048-byte array from a lock-free pool.
 *
 * <p>Access rules are {@link BlockStorage}'s: the owning region writes with plain accesses; other threads read
 * with {@link #getShared}, which acquires the table entry. Materialized sections are never returned to the pool
 * while the world lives (light rarely becomes uniform again), which keeps readers safe without reclamation.
 */
public final class LightStorage {
    public static final int SECTION_BYTES = 2048;

    private final int chunksX, chunksZ, sections, minSection;
    private final int defaultLevel;
    /** int per (chunk, light section): pool index + 1, or -(uniform value). */
    private final MemorySegment table;
    private final MemorySegment slab;
    private final TaggedFreeList pool;

    /**
     * @param minY the block storage's floor (a multiple of 16); light sections start one section lower
     * @param blockSections the block storage's section count; light has two more
     * @param defaultLevel the level of sections never written (0 for block light, 15 for sky light above ground
     *     is set explicitly by the engine; 0 is the usual default)
     */
    public LightStorage(NativeMemory memory, int chunksX, int chunksZ, int minY, int blockSections, int poolSections,
            int defaultLevel) {
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.sections = blockSections + 2;
        this.minSection = (minY >> 4) - 1;
        this.defaultLevel = defaultLevel;
        this.table = memory.allocate((long) chunksX * chunksZ * sections * Integer.BYTES);
        this.slab = memory.allocate((long) poolSections * SECTION_BYTES);
        this.pool = new TaggedFreeList(memory, poolSections);
        if (defaultLevel != 0) {
            for (long i = 0; i < (long) chunksX * chunksZ * sections; i++) table.set(ValueLayout.JAVA_INT, i * 4, -defaultLevel);
        }
    }

    public int chunksX() { return chunksX; }
    public int chunksZ() { return chunksZ; }
    /** Light sections, including the one below and the one above the block storage. */
    public int sections() { return sections; }
    public int minSection() { return minSection; }
    public int minY() { return minSection << 4; }
    public int maxYExclusive() { return (minSection + sections) << 4; }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && z >= 0 && x < chunksX * 16 && z < chunksZ * 16 && y >= minY() && y < maxYExclusive();
    }

    private long tableOffset(int x, int y, int z) {
        return ((((long) (z >> 4) * chunksX) + (x >> 4)) * sections + ((y >> 4) - minSection)) * Integer.BYTES;
    }

    private static int nibbleIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    private int read(int ref, int x, int y, int z) {
        if (ref <= 0) return -ref;
        int i = nibbleIndex(x, y, z);
        int b = slab.get(ValueLayout.JAVA_BYTE, (long) (ref - 1) * SECTION_BYTES + (i >> 1));
        return (b >> ((i & 1) << 2)) & 15;
    }

    /** Owner read. Out-of-bounds positions read as the storage default. */
    public int get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return defaultLevel;
        return read(table.get(ValueLayout.JAVA_INT, tableOffset(x, y, z)), x, y, z);
    }

    /** Read from a thread that does not own the chunk. */
    public int getShared(int x, int y, int z) {
        if (!inBounds(x, y, z)) return defaultLevel;
        return read((int) INT.getAcquire(table, tableOffset(x, y, z)), x, y, z);
    }

    /** Owner write. Returns false if out of bounds or the pool is exhausted. */
    public boolean set(int x, int y, int z, int level) {
        if (!inBounds(x, y, z)) return false;
        long to = tableOffset(x, y, z);
        int ref = table.get(ValueLayout.JAVA_INT, to);
        if (ref <= 0) {
            int uniform = -ref;
            if (uniform == level) return true;
            int section = pool.pop();
            if (section < 0) return false;
            fill((long) section * SECTION_BYTES, uniform);
            ref = section + 1;
            write(ref, x, y, z, level);
            INT.setRelease(table, to, ref);
            return true;
        }
        write(ref, x, y, z, level);
        return true;
    }

    private void write(int ref, int x, int y, int z, int level) {
        int i = nibbleIndex(x, y, z);
        long off = (long) (ref - 1) * SECTION_BYTES + (i >> 1);
        int b = slab.get(ValueLayout.JAVA_BYTE, off);
        int shift = (i & 1) << 2;
        slab.set(ValueLayout.JAVA_BYTE, off, (byte) ((b & ~(15 << shift)) | (level << shift)));
    }

    /** Fill one pooled section with {@code level} without allocating (no slice objects). */
    private void fill(long base, int level) {
        long nibbles = level & 15L;
        long b = nibbles | nibbles << 4;
        long word = b * 0x0101_0101_0101_0101L;
        for (long o = 0; o < SECTION_BYTES; o += 8) slab.set(ValueLayout.JAVA_LONG_UNALIGNED, base + o, word);
    }

    /** Set every value of one section to {@code level} (a fresh or relit section). Owner only. */
    public void fillSection(int chunkX, int chunkZ, int sectionY, int level) {
        long to = ((((long) chunkZ * chunksX) + chunkX) * sections + (sectionY - minSection)) * Integer.BYTES;
        int ref = table.get(ValueLayout.JAVA_INT, to);
        if (ref > 0) {
            fill((long) (ref - 1) * SECTION_BYTES, level);
        } else {
            INT.setRelease(table, to, -level);
        }
    }

    /**
     * Copy a section's 2048 nibble bytes (vanilla layout) into {@code dst}; a uniform section is expanded. Returns
     * the uniform value, or -1 if the section is materialized.
     */
    public int copySection(int chunkX, int chunkZ, int sectionY, byte[] dst) {
        long to = ((((long) chunkZ * chunksX) + chunkX) * sections + (sectionY - minSection)) * Integer.BYTES;
        int ref = (int) INT.getAcquire(table, to);
        if (ref <= 0) {
            java.util.Arrays.fill(dst, 0, SECTION_BYTES, (byte) (-ref | -ref << 4));
            return -ref;
        }
        MemorySegment.copy(slab, ValueLayout.JAVA_BYTE, (long) (ref - 1) * SECTION_BYTES, dst, 0, SECTION_BYTES);
        return -1;
    }

    /** Load a section from vanilla nibble bytes (2048), e.g. from disk. Owner only. */
    public boolean loadSection(int chunkX, int chunkZ, int sectionY, byte[] src) {
        long to = ((((long) chunkZ * chunksX) + chunkX) * sections + (sectionY - minSection)) * Integer.BYTES;
        int ref = table.get(ValueLayout.JAVA_INT, to);
        if (ref <= 0) {
            int section = pool.pop();
            if (section < 0) return false;
            ref = section + 1;
            MemorySegment.copy(src, 0, slab, ValueLayout.JAVA_BYTE, (long) section * SECTION_BYTES, SECTION_BYTES);
            INT.setRelease(table, to, ref);
            return true;
        }
        MemorySegment.copy(src, 0, slab, ValueLayout.JAVA_BYTE, (long) (ref - 1) * SECTION_BYTES, SECTION_BYTES);
        return true;
    }
}
