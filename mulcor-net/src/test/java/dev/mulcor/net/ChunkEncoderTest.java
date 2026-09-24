package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.NativeMemory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.SplittableRandom;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Our off-heap encoder's output is decoded by Minestom's own chunk-section codec and must reproduce every block. */
class ChunkEncoderTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    private static void assertMinestomDecodes(BlockStorage s, int cx, int cz, int biome, ByteBuf encoded) {
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.getBytes(encoded.readerIndex(), bytes);
        NetworkBuffer nb = NetworkBuffer.wrap(bytes, 0, bytes.length);
        var sectionType = ChunkData.Section.networkType(64);
        for (int sy = 0; sy < s.sections(); sy++) {
            ChunkData.Section section = nb.read(sectionType);
            Palette p = section.blockStates();
            int nonAir = 0, fluids = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int ours = s.get(cx * 16 + x, s.minY() + sy * 16 + y, cz * 16 + z);
                if (ours != 0) nonAir++;
                if (Protocol.isFluid(ours)) fluids++;
                assertEquals(Protocol.vanillaState(ours), p.get(x, y, z), "section " + sy + " at " + x + "," + y + "," + z);
            }
            assertEquals(nonAir, section.blockCount(), "non-air count of section " + sy);
            assertEquals(fluids, section.liquidCount(), "fluid count of section " + sy);
            assertEquals(biome, section.biomes().get(0, 0, 0));
        }
        assertEquals(bytes.length, nb.readIndex(), "no trailing bytes");
    }

    @Test
    void allPaletteShapesDecodeInMinestom() {
        var s = new BlockStorage(mem, 1, 1, 0, 6, 16);
        var rnd = new SplittableRandom(4);
        // section 0: empty; 1: single state; 2: 2 states (4 bits); 3: 20 states (5 bits); 4: 200 states (8 bits);
        // 5: 1000 distinct Mulcor ids (direct palette; unknown ids map to air in vanilla)
        for (int y = 16; y < 32; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, Blocks.STONE);
        for (int y = 32; y < 48; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, rnd.nextBoolean() ? Blocks.DIRT : Blocks.AIR);
        for (int y = 48; y < 64; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, Blocks.wire(rnd.nextInt(16)));
        s.set(0, 48, 0, Blocks.STONE); s.set(1, 48, 0, Blocks.DIRT); s.set(2, 48, 0, Blocks.TNT); s.set(3, 48, 0, Blocks.CHEST);
        for (int y = 64; y < 80; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, 1 + rnd.nextInt(200));
        for (int y = 80; y < 96; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) s.set(x, y, z, 1 + ((y * 256 + z * 16 + x) % 1000));
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer();
        try {
            new ChunkEncoder().encodeColumn(s, 0, 0, 5, out);
            assertMinestomDecodes(s, 0, 0, 5, out);
        } finally {
            out.release();
        }
    }

    @Test
    void compressionRoundTripsThroughDirectBuffers() throws Exception {
        var s = new BlockStorage(mem, 2, 2, 0, 4, 64);
        var rnd = new SplittableRandom(8);
        for (int i = 0; i < 20_000; i++) s.set(rnd.nextInt(32), rnd.nextInt(64), rnd.nextInt(32), 1 + rnd.nextInt(8));
        ByteBuf raw = PooledByteBufAllocator.DEFAULT.directBuffer(), packed = PooledByteBufAllocator.DEFAULT.directBuffer(),
                back = PooledByteBufAllocator.DEFAULT.directBuffer();
        try (var c = new ChunkCompressor(4)) {
            new ChunkEncoder().encodeColumn(s, 1, 1, 0, raw);
            int n = c.compress(raw, packed);
            assertTrue(n > 0 && n < raw.readableBytes(), "compressed " + raw.readableBytes() + " -> " + n);
            c.decompress(packed, back, raw.readableBytes());
            assertEquals(raw, back, "lossless");
            assertMinestomDecodes(s, 1, 1, 0, back);
        } finally {
            raw.release();
            packed.release();
            back.release();
        }
    }
}
