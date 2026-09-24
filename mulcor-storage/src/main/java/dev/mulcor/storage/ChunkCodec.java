package dev.mulcor.storage;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.Registry;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla chunk NBT ({@code ChunkSerializer} / {@code SerializableChunkData}, 1.18+ layout) to and from
 * {@link ChunkData}. Runs on storage I/O threads (cold path); one instance per thread reuses its scratch arrays.
 *
 * <h2>Paletted containers (vanilla {@code PalettedContainer} codec)</h2>
 * <ul>
 *   <li>{@code palette} lists the distinct values; {@code data} packs one palette index per entry, little end of
 *       each long first, {@code 64 / bits} entries per long, <b>never spanning two longs</b> (since 1.16).</li>
 *   <li>A one-entry palette has no {@code data} (every entry is that value).</li>
 *   <li>Bits on disk ({@code Strategy.calculateBitsForSerialization}): block states use
 *       {@code max(4, ceilLog2(paletteSize))}; biomes use {@code ceilLog2(paletteSize)}.</li>
 *   <li>Block states: 4096 entries, YZX order. Biomes: 64 entries (4×4×4), YZX order.</li>
 * </ul>
 * Reading derives the bit width from the palette size and checks it against the data length, falling back to the
 * width the length implies (vanilla re-packs such data too).
 */
public final class ChunkCodec {
    /** First DataVersion with the 1.18 chunk layout ({@code sections}, {@code block_states}, {@code yPos}). */
    public static final int FIRST_1_18_DATA_VERSION = 2844;

    private final NbtReader r = new NbtReader();
    private final int[] palette = new int[4096];
    private final int[] indices = new int[4096];
    private final Map<String, String> props = new HashMap<>();
    private final Map<Integer, Integer> paletteIndex = new HashMap<>();
    private final long[] packed = new long[4096];

    /** Thrown when a chunk cannot be loaded as-is (for example a pre-1.18 world that vanilla must upgrade first). */
    public static final class ChunkFormatException extends RuntimeException {
        public ChunkFormatException(String message) {
            super(message);
        }
    }

    // ================================================================================================ decode

    /**
     * Decode a chunk. {@code sectionCount} is the dimension's height in sections (24 in the overworld); the lowest
     * section comes from the chunk's {@code yPos}.
     */
    public ChunkData decode(ByteBuffer nbt, int sectionCount, ChunkData out) {
        r.reset(nbt);
        r.beginRoot();
        r.enterCompound();
        // Pass 1 needs yPos/DataVersion before sections; they may appear in any order, so remember the sections'
        // position and come back to it.
        int sectionsPos = -1, dataVersion = -1, yPos = Integer.MIN_VALUE, x = 0, z = 0;
        boolean sawLevel = false;
        out.extra.clear();
        java.util.List<ChunkData.RawField> extra = new java.util.ArrayList<>();
        String status = "minecraft:full";
        long lastUpdate = 0, inhabited = 0;
        boolean lightOn = false;
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.INT && r.nameIs("DataVersion")) dataVersion = r.readInt();
            else if (t == Nbt.INT && r.nameIs("xPos")) x = r.readInt();
            else if (t == Nbt.INT && r.nameIs("zPos")) z = r.readInt();
            else if (t == Nbt.INT && r.nameIs("yPos")) yPos = r.readInt();
            else if (t == Nbt.STRING && r.nameIs("Status")) status = r.readString();
            else if (t == Nbt.LONG && r.nameIs("LastUpdate")) lastUpdate = r.readLong();
            else if (t == Nbt.LONG && r.nameIs("InhabitedTime")) inhabited = r.readLong();
            else if (t == Nbt.BYTE && r.nameIs("isLightOn")) lightOn = r.readByte() != 0;
            else if (t == Nbt.LIST && r.nameIs("sections")) {
                sectionsPos = r.buffer().position();
                r.skip(t);
            } else {
                if (t == Nbt.COMPOUND && r.nameIs("Level")) sawLevel = true;
                String name = r.name();
                int start = r.buffer().position();
                r.skip(t);
                byte[] raw = new byte[r.buffer().position() - start];
                r.buffer().get(start, raw);
                extra.add(new ChunkData.RawField(t, name, raw));
            }
        }
        if (sawLevel || (dataVersion >= 0 && dataVersion < FIRST_1_18_DATA_VERSION)) {
            throw new ChunkFormatException("chunk uses the pre-1.18 format (DataVersion " + dataVersion
                    + "); upgrade the world with vanilla --forceUpgrade first");
        }
        if (yPos == Integer.MIN_VALUE) yPos = -4; // vanilla's default for the overworld when absent
        out.reset(sectionCount, yPos);
        out.extra.addAll(extra);
        out.x = x;
        out.z = z;
        out.dataVersion = dataVersion;
        out.status = status;
        out.lastUpdate = lastUpdate;
        out.inhabitedTime = inhabited;
        out.lightOn = lightOn;
        if (sectionsPos >= 0) {
            r.buffer().position(sectionsPos);
            r.beginList();
            int n = r.listLength();
            if (r.listType() == Nbt.COMPOUND) {
                for (int i = 0; i < n; i++) section(out, sectionCount);
            } else {
                for (int i = 0; i < n; i++) r.skip(r.listType());
            }
        }
        return out;
    }

    private void section(ChunkData out, int sectionCount) {
        r.enterCompound();
        int y = Integer.MIN_VALUE;
        short[] states = null;
        byte[] biomes = null;
        boolean biomesSeen = false;
        byte[] blockLight = null, skyLight = null;
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.BYTE && r.nameIs("Y")) y = r.readByte();
            else if (t == Nbt.COMPOUND && r.nameIs("block_states")) states = blockStates(out);
            else if (t == Nbt.COMPOUND && r.nameIs("biomes")) {
                biomes = biomes(out);
                biomesSeen = true;
            } else if (t == Nbt.BYTE_ARRAY && r.nameIs("BlockLight")) blockLight = r.readByteArray();
            else if (t == Nbt.BYTE_ARRAY && r.nameIs("SkyLight")) skyLight = r.readByteArray();
            else r.skip(t);
        }
        r.exitCompound();
        if (y == Integer.MIN_VALUE) return;
        int li = y - out.minSection + 1;
        if (li >= 0 && li < sectionCount + 2) {
            if (blockLight != null && blockLight.length == 2048) out.blockLight[li] = blockLight;
            if (skyLight != null && skyLight.length == 2048) out.skyLight[li] = skyLight;
        }
        int si = y - out.minSection;
        if (si >= 0 && si < sectionCount) {
            out.states[si] = states;
            if (biomesSeen) out.biomes[si] = biomes;
        }
    }

    /** Returns 4096 states, or null for an all-air section. */
    private short[] blockStates(ChunkData out) {
        r.enterCompound();
        int size = 0;
        long[] data = null;
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.LIST && r.nameIs("palette")) {
                r.beginList();
                int n = r.listLength();
                if (r.listType() != Nbt.COMPOUND && n > 0) throw new ChunkFormatException("block palette is not a compound list");
                if (n > 4096) throw new ChunkFormatException("block palette larger than 4096");
                for (int i = 0; i < n; i++) palette[i] = paletteEntry(out);
                size = n;
            } else if (t == Nbt.LONG_ARRAY && r.nameIs("data")) {
                data = r.readLongArray();
            } else {
                r.skip(t);
            }
        }
        r.exitCompound();
        if (size == 0) return null;
        if (size == 1 || data == null) {
            int s = palette[0];
            if (s == 0) return null;
            short[] out1 = new short[4096];
            java.util.Arrays.fill(out1, (short) s);
            return out1;
        }
        int bits = bitsFor(data.length, 4096, Math.max(4, ceilLog2(size)));
        unpack(data, bits, 4096, indices);
        short[] states = new short[4096];
        boolean any = false;
        for (int i = 0; i < 4096; i++) {
            int idx = indices[i];
            int s = idx < size ? palette[idx] : 0;
            states[i] = (short) s;
            any |= s != 0;
        }
        return any ? states : null;
    }

    private int paletteEntry(ChunkData out) {
        r.enterCompound();
        String name = null;
        props.clear();
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.STRING && r.nameIs("Name")) {
                name = r.readString();
            } else if (t == Nbt.COMPOUND && r.nameIs("Properties")) {
                r.enterCompound();
                for (int pt; (pt = r.nextField()) != Nbt.END; ) {
                    if (pt == Nbt.STRING) props.put(r.name(), r.readString());
                    else r.skip(pt);
                }
                r.exitCompound();
            } else {
                r.skip(t);
            }
        }
        r.exitCompound();
        int state = name == null ? -1 : BlockData.parse(name, props);
        if (state < 0) {
            out.unknownStates++;
            return 0;
        }
        return state;
    }

    private byte[] biomes(ChunkData out) {
        r.enterCompound();
        int size = 0;
        long[] data = null;
        int[] local = new int[64];
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.LIST && r.nameIs("palette")) {
                r.beginList();
                size = r.listLength();
                if (size > 64) throw new ChunkFormatException("biome palette larger than 64");
                for (int i = 0; i < size; i++) {
                    String name = r.readString();
                    int idx = out.biomeNames.indexOf(name);
                    if (idx < 0) {
                        idx = out.biomeNames.size();
                        out.biomeNames.add(name);
                    }
                    local[i] = idx;
                }
            } else if (t == Nbt.LONG_ARRAY && r.nameIs("data")) {
                data = r.readLongArray();
            } else {
                r.skip(t);
            }
        }
        r.exitCompound();
        byte[] result = new byte[64];
        if (size == 0) return result;
        if (size == 1 || data == null) {
            java.util.Arrays.fill(result, (byte) local[0]);
            return result;
        }
        int bits = bitsFor(data.length, 64, ceilLog2(size));
        unpack(data, bits, 64, indices);
        for (int i = 0; i < 64; i++) result[i] = (byte) (indices[i] < size ? local[indices[i]] : local[0]);
        return result;
    }

    // ================================================================================================ encode

    /** Encode {@code c} as vanilla chunk NBT into {@code w} (reset first). */
    public void encode(ChunkData c, NbtWriter w) {
        w.reset();
        w.beginRoot("");
        w.int_("DataVersion", c.dataVersion > 0 ? c.dataVersion : Registry.DATA_VERSION);
        w.int_("xPos", c.x).int_("yPos", c.minSection).int_("zPos", c.z);
        w.string("Status", c.status);
        w.long_("LastUpdate", c.lastUpdate).long_("InhabitedTime", c.inhabitedTime);
        w.byte_("isLightOn", c.lightOn ? 1 : 0);
        int n = c.sections();
        int count = 0;
        for (int li = 0; li < n + 2; li++) if (hasSection(c, li)) count++;
        w.beginList("sections", Nbt.COMPOUND, count);
        for (int li = 0; li < n + 2; li++) {
            if (!hasSection(c, li)) continue;
            int si = li - 1;
            w.compoundElement().byte_("Y", c.minSection + si);
            if (si >= 0 && si < n) {
                writeBlockStates(w, c.states[si]);
                writeBiomes(w, c, c.biomes[si]);
            }
            if (c.blockLight[li] != null) w.byteArray("BlockLight", c.blockLight[li]);
            if (c.skyLight[li] != null) w.byteArray("SkyLight", c.skyLight[li]);
            w.end();
        }
        for (ChunkData.RawField f : c.extra) w.raw(f.type(), f.name(), f.payload()); // verbatim
        w.end();
    }

    private static boolean hasSection(ChunkData c, int li) {
        int si = li - 1;
        boolean inRange = si >= 0 && si < c.sections();
        return inRange || c.blockLight[li] != null || c.skyLight[li] != null;
    }

    private void writeBlockStates(NbtWriter w, short[] states) {
        w.beginCompound("block_states");
        if (states == null) {
            w.beginList("palette", Nbt.COMPOUND, 1);
            paletteEntry(w, 0);
            w.end();
            return;
        }
        paletteIndex.clear();
        int size = 0;
        for (int i = 0; i < 4096; i++) {
            int s = Short.toUnsignedInt(states[i]);
            Integer idx = paletteIndex.get(s);
            if (idx == null) {
                idx = size;
                palette[size++] = s;
                paletteIndex.put(s, idx);
            }
            indices[i] = idx;
        }
        w.beginList("palette", Nbt.COMPOUND, size);
        for (int i = 0; i < size; i++) paletteEntry(w, palette[i]);
        if (size > 1) {
            int bits = Math.max(4, ceilLog2(size));
            int longs = pack(indices, 4096, bits, packed);
            w.longArray("data", packed, longs);
        }
        w.end();
    }

    private static void paletteEntry(NbtWriter w, int state) {
        int block = BlockData.block(state);
        w.compoundElement().string("Name", BlockData.name(block));
        int np = BlockData.propertyCount(block);
        if (np > 0) {
            w.beginCompound("Properties");
            for (int i = 0; i < np; i++) {
                int p = BlockData.propertyAt(block, i);
                w.string(BlockData.propertyName(p), BlockData.valueName(p, BlockData.get(state, p)));
            }
            w.end();
        }
        w.end();
    }

    private void writeBiomes(NbtWriter w, ChunkData c, byte[] biomes) {
        if (c.biomeNames.isEmpty()) return;
        w.beginCompound("biomes");
        if (biomes == null) {
            w.beginList("palette", Nbt.STRING, 1).stringElement(c.biomeNames.get(0));
            w.end();
            return;
        }
        paletteIndex.clear();
        int size = 0;
        for (int i = 0; i < 64; i++) {
            int b = biomes[i];
            Integer idx = paletteIndex.get(b);
            if (idx == null) {
                idx = size;
                palette[size++] = b;
                paletteIndex.put(b, idx);
            }
            indices[i] = idx;
        }
        w.beginList("palette", Nbt.STRING, size);
        for (int i = 0; i < size; i++) w.stringElement(c.biomeNames.get(palette[i]));
        if (size > 1) {
            int longs = pack(indices, 64, ceilLog2(size), packed);
            w.longArray("data", packed, longs);
        }
        w.end();
    }

    // ================================================================================================ packing

    /** {@code ceil(log2(n))} for n ≥ 1 (vanilla {@code Mth.ceillog2}). */
    public static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    /** Longs needed for {@code count} entries of {@code bits} bits, not spanning longs. */
    public static int longsFor(int count, int bits) {
        int perLong = 64 / bits;
        return (count + perLong - 1) / perLong;
    }

    private static int bitsFor(int longs, int count, int expected) {
        if (expected > 0 && longsFor(count, expected) == longs) return expected;
        for (int b = 1; b <= 32; b++) if (longsFor(count, b) == longs) return b;
        throw new ChunkFormatException("packed data of " + longs + " longs fits no bit width for " + count + " entries");
    }

    /** Unpack {@code count} non-spanning {@code bits}-bit values from {@code data} into {@code out}. */
    public static void unpack(long[] data, int bits, int count, int[] out) {
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        int i = 0;
        for (int l = 0; l < data.length && i < count; l++) {
            long word = data[l];
            for (int k = 0; k < perLong && i < count; k++, i++) {
                out[i] = (int) ((word >>> (k * bits)) & mask);
            }
        }
    }

    /** Pack {@code count} values into {@code out}; returns the number of longs used. */
    public static int pack(int[] values, int count, int bits, long[] out) {
        int perLong = 64 / bits;
        int longs = longsFor(count, bits);
        for (int l = 0, i = 0; l < longs; l++) {
            long word = 0;
            for (int k = 0; k < perLong && i < count; k++, i++) word |= (long) values[i] << (k * bits);
            out[l] = word;
        }
        return longs;
    }
}
