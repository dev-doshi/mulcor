package dev.mulcor.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import dev.mulcor.registry.Registry;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ChunkCodecTest {
    static final int SECTIONS = 24;

    static ChunkData sample(long seed) {
        var rnd = new SplittableRandom(seed);
        var c = new ChunkData().reset(SECTIONS, -4);
        c.x = -7;
        c.z = 12;
        c.dataVersion = Registry.DATA_VERSION;
        c.lastUpdate = 123456789L;
        c.inhabitedTime = 42;
        c.lightOn = true;
        c.biomeNames.add("minecraft:plains");
        c.biomeNames.add("minecraft:river");
        c.biomeNames.add("minecraft:dripstone_caves");
        // Palette regimes: single (0 bits), 2..16 (4 bits), 17..256 (5..8 bits), >256 (9+ bits).
        int[] distinct = {1, 2, 16, 17, 200, 256, 257, 1000, 4096};
        for (int si = 0; si < distinct.length; si++) {
            short[] s = new short[4096];
            int[] choices = new int[distinct[si]];
            for (int i = 0; i < choices.length; i++) choices[i] = 1 + rnd.nextInt(Registry.stateCount() - 1);
            for (int i = 0; i < 4096; i++) s[i] = (short) choices[i < choices.length ? i : rnd.nextInt(choices.length)];
            c.states[si] = s;
            byte[] b = new byte[64];
            for (int i = 0; i < 64; i++) b[i] = (byte) rnd.nextInt(si % 3 + 1);
            c.biomes[si] = b;
        }
        byte[] light = new byte[2048];
        rnd.nextBytes(light);
        c.skyLight[0] = light; // the light-only section below the world
        c.blockLight[5] = light.clone();
        c.extra.add(new ChunkData.RawField(Nbt.INT, "custom_field", new byte[] {0, 0, 0, 7}));
        return c;
    }

    static void assertSameChunk(ChunkData a, ChunkData b) {
        assertEquals(a.x, b.x);
        assertEquals(a.z, b.z);
        assertEquals(a.minSection, b.minSection);
        assertEquals(a.lightOn, b.lightOn);
        assertEquals(a.lastUpdate, b.lastUpdate);
        assertEquals(a.inhabitedTime, b.inhabitedTime);
        for (int y = a.minSection * 16; y < (a.minSection + a.sections()) * 16; y++) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertEquals(a.state(x, y, z), b.state(x, y, z), "block " + x + "," + y + "," + z);
            }
        }
        for (int si = 0; si < a.sections(); si++) {
            if (a.biomes[si] == null) continue;
            for (int i = 0; i < 64; i++) {
                assertEquals(a.biomeNames.get(a.biomes[si][i]), b.biomeNames.get(b.biomes[si][i]), "biome s" + si + " " + i);
            }
        }
        for (int li = 0; li < a.sections() + 2; li++) {
            assertArrayEquals(a.skyLight[li], b.skyLight[li], "sky light " + li);
            assertArrayEquals(a.blockLight[li], b.blockLight[li], "block light " + li);
        }
        assertEquals(a.extra.size(), b.extra.size());
        for (int i = 0; i < a.extra.size(); i++) {
            assertEquals(a.extra.get(i).name(), b.extra.get(i).name());
            assertArrayEquals(a.extra.get(i).payload(), b.extra.get(i).payload());
        }
    }

    @Test
    void roundTripsEveryPaletteRegime() {
        var codec = new ChunkCodec();
        var w = new NbtWriter(1 << 16);
        ChunkData c = sample(1);
        codec.encode(c, w);
        ChunkData back = codec.decode(ByteBuffer.wrap(w.toByteArray()), SECTIONS, new ChunkData());
        assertEquals(0, back.unknownStates);
        assertSameChunk(c, back);
    }

    /** The encoded NBT has vanilla's structure and bit widths ({@code calculateBitsForSerialization}). */
    @Test
    void encodedLayoutMatchesVanillaRules() throws Exception {
        var codec = new ChunkCodec();
        var w = new NbtWriter(1 << 16);
        codec.encode(sample(2), w);
        CompoundBinaryTag root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(w.toByteArray()));
        assertEquals(Registry.DATA_VERSION, root.getInt("DataVersion"));
        assertEquals(-4, root.getInt("yPos"));
        assertEquals(7, root.getInt("custom_field"), "unmodelled fields survive");
        ListBinaryTag sections = root.getList("sections");
        int checked = 0;
        for (var t : sections) {
            CompoundBinaryTag s = (CompoundBinaryTag) t;
            if (!s.keySet().contains("block_states")) continue;
            CompoundBinaryTag bs = s.getCompound("block_states");
            int size = bs.getList("palette").size();
            long[] data = bs.getLongArray("data");
            if (size == 1) {
                assertEquals(0, data.length, "single-value palette has no data");
            } else {
                int bits = Math.max(4, ChunkCodec.ceilLog2(size));
                assertEquals(ChunkCodec.longsFor(4096, bits), data.length, "palette " + size + " → " + bits + " bits");
            }
            CompoundBinaryTag first = (CompoundBinaryTag) bs.getList("palette").get(0);
            assertTrue(first.getString("Name").startsWith("minecraft:"));
            checked++;
        }
        assertEquals(SECTIONS, checked);
    }

    @Test
    void packingIsNonSpanning() {
        // 5 bits: 12 values per long, the top 4 bits of every long unused (vanilla since 1.16).
        int[] v = new int[4096];
        for (int i = 0; i < v.length; i++) v[i] = i % 32;
        long[] packed = new long[4096];
        int longs = ChunkCodec.pack(v, 4096, 5, packed);
        assertEquals((4096 + 11) / 12, longs);
        for (int l = 0; l < longs; l++) assertEquals(0, packed[l] >>> 60, "no value crosses a long boundary");
        int[] back = new int[4096];
        ChunkCodec.unpack(java.util.Arrays.copyOf(packed, longs), 5, 4096, back);
        assertArrayEquals(v, back);
        assertEquals(0, ChunkCodec.ceilLog2(1));
        assertEquals(4, ChunkCodec.ceilLog2(16));
        assertEquals(5, ChunkCodec.ceilLog2(17));
    }

    @Test
    void pre118ChunksAreRefusedWithUpgradeAdvice() {
        var w = new NbtWriter(256);
        w.beginRoot("").int_("DataVersion", 2730).beginCompound("Level").int_("xPos", 0).end().end();
        var e = assertThrows(ChunkCodec.ChunkFormatException.class,
                () -> new ChunkCodec().decode(ByteBuffer.wrap(w.toByteArray()), SECTIONS, new ChunkData()));
        assertTrue(e.getMessage().contains("--forceUpgrade"), e.getMessage());
    }

    @Test
    void unknownPaletteEntriesBecomeAirAndAreCounted() {
        var w = new NbtWriter(256);
        w.beginRoot("").int_("DataVersion", Registry.DATA_VERSION).int_("yPos", -4);
        w.beginList("sections", Nbt.COMPOUND, 1);
        w.compoundElement().byte_("Y", 0).beginCompound("block_states");
        w.beginList("palette", Nbt.COMPOUND, 2);
        w.compoundElement().string("Name", "minecraft:stone").end();
        w.compoundElement().string("Name", "some_mod:mystery").end();
        long[] data = new long[256];
        java.util.Arrays.fill(data, 0x1010101010101010L); // alternating 0/1 in 4-bit fields
        w.longArray("data", data, 256);
        w.end().end().end();
        ChunkData c = new ChunkCodec().decode(ByteBuffer.wrap(w.toByteArray()), SECTIONS, new ChunkData());
        assertEquals(1, c.unknownStates);
        assertEquals(BlockData.defaultState(BlockId.STONE), c.state(0, 0, 0));
        assertEquals(0, c.state(1, 0, 0), "unknown → air");
    }

    /** Opt-in: decode, re-encode and re-decode every chunk of a 1.18+ vanilla world ({@code -Dmulcor.anvilWorld118}). */
    @Test
    void realWorldRoundTrip() throws Exception {
        String world = System.getProperty("mulcor.anvilWorld118");
        Assumptions.assumeTrue(world != null, "set -Dmulcor.anvilWorld118 to run");
        Path region = Path.of(world, "region");
        var codec = new ChunkCodec();
        var w = new NbtWriter(1 << 20);
        int chunks = 0, unknown = 0, bedrock = 0, full = 0;
        int bedrockState = BlockData.defaultState(BlockId.BEDROCK);
        try (var files = Files.list(region)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".mca")).toList()) {
                String[] parts = f.getFileName().toString().split("\\.");
                int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                try (var rf = new RegionFile(region, rx, rz)) {
                    for (int i = 0; i < 1024; i++) {
                        int x = (rx << 5) + (i & 31), z = (rz << 5) + (i >> 5);
                        ByteBuffer b = rf.read(x, z);
                        if (b == null) continue;
                        ChunkData c = codec.decode(b, SECTIONS, new ChunkData());
                        unknown += c.unknownStates;
                        codec.encode(c, w);
                        ChunkData back = codec.decode(ByteBuffer.wrap(w.toByteArray()), SECTIONS, new ChunkData());
                        assertSameChunk(c, back);
                        if (c.status.equals("minecraft:full")) {
                            full++;
                            if (c.state(0, -64, 0) == bedrockState) bedrock++;
                        }
                        chunks++;
                    }
                }
            }
        }
        System.out.printf("round-tripped %d chunks (%d full, %d with bedrock at y=-64), %d unknown palette entries%n",
                chunks, full, bedrock, unknown);
        assertTrue(chunks > 0);
        assertEquals(full, bedrock, "every full overworld chunk has bedrock at y = -64");
    }
}
