package dev.mulcor.net;

import dev.mulcor.memory.BlockStorage;
import io.netty.buffer.ByteBuf;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Serializes chunk sections from off-heap {@link BlockStorage} into the vanilla chunk-section wire format, as
 * defined by Minestom's {@code Palette} serializer, writing straight into a (pooled, direct) Netty buffer.
 *
 * <p>Per section: {@code short nonAirCount}, {@code short fluidCount} (since 1.21.5), then the block paletted
 * container, then the biome container.
 * A paletted container is:
 * <ul>
 *   <li>1 distinct state: bits 0 and a single VarInt value.</li>
 *   <li>Up to 2^8 states: an indirect palette at max(4, ⌈log2 n⌉) bits, written as VarInt count plus VarInt
 *       entries.</li>
 *   <li>More: direct global ids at {@link Protocol#PALETTE_DIRECT_BITS}.</li>
 * </ul>
 * Values are packed little-end-first into longs without spanning, and the longs are written raw (no length
 * prefix).
 *
 * <p>Allocation-free per call: the palette index is a reusable stamp array. One encoder per thread.
 */
public final class ChunkEncoder {
    private static final int DIM = 4096;
    private final int[] indexOf = new int[65536];
    private final int[] stamp = new int[65536];
    private final int[] palette = new int[DIM];
    private int generation;

    /** Encode one 16³ section. {@code biomeId} fills the section's biome container. */
    public void encodeSection(BlockStorage blocks, int chunkX, int chunkZ, int sectionY, int biomeId, ByteBuf out) {
        int ref = blocks.sectionRef(chunkX, chunkZ, sectionY);
        if (ref == 0) {
            out.writeShort(0);
            out.writeShort(0);
            writeSingle(out, Protocol.vanillaState(0));
        } else {
            MemorySegment slab = blocks.slab();
            long base = BlockStorage.sectionOffset(ref);
            if (++generation == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                generation = 1;
            }
            int distinct = 0, nonAir = 0, fluids = 0;
            for (int i = 0; i < DIM; i++) {
                int st = Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, base + 2L * i));
                if (st != 0) nonAir++;
                if (Protocol.isFluid(st)) fluids++;
                if (stamp[st] != generation) {
                    stamp[st] = generation;
                    indexOf[st] = distinct;
                    palette[distinct++] = st;
                }
            }
            out.writeShort(nonAir);
            out.writeShort(fluids);
            if (distinct == 1) {
                writeSingle(out, Protocol.vanillaState(palette[0]));
            } else {
                int bits = Math.max(Protocol.PALETTE_MIN_BITS, 32 - Integer.numberOfLeadingZeros(distinct - 1));
                boolean direct = bits > Protocol.PALETTE_MAX_BITS;
                if (direct) bits = Protocol.PALETTE_DIRECT_BITS;
                out.writeByte(bits);
                if (!direct) {
                    VarInts.write(out, distinct);
                    for (int p = 0; p < distinct; p++) VarInts.write(out, Protocol.vanillaState(palette[p]));
                }
                int perLong = 64 / bits;
                long word = 0;
                int filled = 0;
                for (int i = 0; i < DIM; i++) {
                    int st = Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, base + 2L * i));
                    long v = direct ? Protocol.vanillaState(st) : indexOf[st];
                    word |= v << (filled * bits);
                    if (++filled == perLong) {
                        out.writeLong(word);
                        word = 0;
                        filled = 0;
                    }
                }
                if (filled > 0) out.writeLong(word);
            }
        }
        writeSingle(out, biomeId); // biomes: one value for the whole section
    }

    /** Encode every section of a chunk column, bottom to top. */
    public void encodeColumn(BlockStorage blocks, int chunkX, int chunkZ, int biomeId, ByteBuf out) {
        for (int s = 0; s < blocks.sections(); s++) encodeSection(blocks, chunkX, chunkZ, s, biomeId, out);
    }

    /**
     * Encode a full vanilla column of {@code vanillaSections} sections starting at {@code vanillaMinY}. Sections
     * outside the stored height are sent as empty air. The storage's floor must be a multiple of 16.
     */
    public void encodeVanillaColumn(BlockStorage blocks, int chunkX, int chunkZ, int biomeId, int vanillaMinY,
            int vanillaSections, ByteBuf out) {
        for (int s = 0; s < vanillaSections; s++) {
            int y0 = vanillaMinY + 16 * s;
            if (y0 >= blocks.minY() && y0 < blocks.maxYExclusive()) {
                encodeSection(blocks, chunkX, chunkZ, (y0 - blocks.minY()) >> 4, biomeId, out);
            } else {
                out.writeShort(0);
                out.writeShort(0);
                writeSingle(out, Protocol.vanillaState(0));
                writeSingle(out, biomeId);
            }
        }
    }

    /**
     * Per-column heights for the WORLD_SURFACE (any block) and MOTION_BLOCKING (solid blocks) heightmaps, as
     * {@code highest y + 1 - vanillaMinY}, or 0 for an empty column. Index = z * 16 + x.
     */
    public static void heightmaps(BlockStorage blocks, int chunkX, int chunkZ, int vanillaMinY, int[] surface, int[] motion) {
        java.util.Arrays.fill(surface, 0);
        java.util.Arrays.fill(motion, 0);
        MemorySegment slab = blocks.slab();
        int remaining = 2 * 256;
        for (int s = blocks.sections() - 1; s >= 0 && remaining > 0; s--) {
            int ref = blocks.sectionRef(chunkX, chunkZ, s);
            if (ref == 0) continue;
            long base = BlockStorage.sectionOffset(ref);
            int y0 = blocks.minY() + 16 * s - vanillaMinY;
            for (int ly = 15; ly >= 0; ly--) {
                for (int i = 0; i < 256; i++) {
                    if (surface[i] != 0 && motion[i] != 0) continue;
                    int st = Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, base + 2L * ((ly << 8) | i)));
                    if (st == 0) continue;
                    if (surface[i] == 0) { surface[i] = y0 + ly + 1; remaining--; }
                    if (motion[i] == 0 && dev.mulcor.core.Blocks.isSolid(st)) { motion[i] = y0 + ly + 1; remaining--; }
                }
            }
        }
    }

    private static void writeSingle(ByteBuf out, int value) {
        out.writeByte(0);
        VarInts.write(out, value);
    }
}
