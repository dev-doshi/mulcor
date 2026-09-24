package dev.mulcor.net;

import dev.mulcor.memory.BlockStorage;
import io.netty.buffer.ByteBuf;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Serializes chunk sections from off-heap {@link BlockStorage} into the vanilla chunk-section wire format, as
 * defined by Minestom's {@code Palette} serializer, writing straight into a (pooled, direct) Netty buffer.
 *
 * <p>Per section: {@code short nonAirCount}, then the block paletted container, then the biome container.
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
            writeSingle(out, Protocol.vanillaState(0));
        } else {
            MemorySegment slab = blocks.slab();
            long base = BlockStorage.sectionOffset(ref);
            if (++generation == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                generation = 1;
            }
            int distinct = 0, nonAir = 0;
            for (int i = 0; i < DIM; i++) {
                int st = Short.toUnsignedInt(slab.get(ValueLayout.JAVA_SHORT, base + 2L * i));
                if (st != 0) nonAir++;
                if (stamp[st] != generation) {
                    stamp[st] = generation;
                    indexOf[st] = distinct;
                    palette[distinct++] = st;
                }
            }
            out.writeShort(nonAir);
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

    private static void writeSingle(ByteBuf out, int value) {
        out.writeByte(0);
        VarInts.write(out, value);
    }
}
