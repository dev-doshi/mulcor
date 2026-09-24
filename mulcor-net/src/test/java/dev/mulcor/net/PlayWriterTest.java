package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mulcor.core.Blocks;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.NativeMemory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;
import java.util.zip.Inflater;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.packet.server.play.ChunkBatchStartPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Everything {@link PlayWriter} emits is parsed back with Minestom's (vanilla-mirroring) serializers. */
class PlayWriterTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    /** A 64-block-tall world (y 0..63) with every palette shape, wires above solids, and empty columns. */
    private BlockStorage world() {
        var s = new BlockStorage(mem, 3, 2, 0, 4, 3 * 2 * 4);
        var rnd = new SplittableRandom(11);
        for (int x = 0; x < 48; x++) for (int z = 0; z < 32; z++) {
            if (x == 20 && z == 5) continue; // one empty column
            s.set(x, 0, z, Blocks.BEDROCK);
            for (int y = 1; y < 4; y++) s.set(x, y, z, rnd.nextBoolean() ? Blocks.STONE : Blocks.DIRT);
            if (rnd.nextInt(5) == 0) s.set(x, 4, z, Blocks.WIRE + rnd.nextInt(16)); // not motion-blocking
        }
        for (int i = 0; i < 4000; i++) s.set(16 + rnd.nextInt(16), 16 + rnd.nextInt(48), rnd.nextInt(16), 1 + rnd.nextInt(300));
        s.set(3, 63, 3, Blocks.TNT);
        return s;
    }

    private static byte[] unframe(ByteBuf out, int threshold) throws Exception {
        byte[] bytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), bytes);
        NetworkBuffer nb = NetworkBuffer.wrap(bytes, 0, bytes.length);
        int len = nb.read(NetworkBuffer.VAR_INT);
        assertEquals(bytes.length, nb.readIndex() + len, "exactly one frame");
        if (threshold <= 0) return nb.read(NetworkBuffer.RAW_BYTES);
        int dataLength = nb.read(NetworkBuffer.VAR_INT);
        byte[] rest = nb.read(NetworkBuffer.RAW_BYTES);
        if (dataLength == 0) {
            assertTrue(rest.length < threshold, "uncompressed only below the threshold");
            return rest;
        }
        assertTrue(dataLength >= threshold);
        var inf = new Inflater();
        inf.setInput(rest);
        byte[] body = new byte[dataLength];
        assertEquals(dataLength, inf.inflate(body));
        assertTrue(inf.finished());
        inf.end();
        return body;
    }

    private static int packed(long[] longs, int bits, int i) {
        int perLong = 64 / bits;
        return (int) ((longs[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void chunkPacketParsesInMinestomAndMatchesStorage(int threshold) throws Exception {
        BlockStorage s = world();
        try (var w = new PlayWriter(threshold)) {
            for (int cx = 0; cx < 3; cx++) for (int cz = 0; cz < 2; cz++) {
                ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer();
                try {
                    w.chunk(s, cx, cz, out);
                    byte[] body = unframe(out, threshold);
                    NetworkBuffer nb = NetworkBuffer.wrap(body, 0, body.length, Vanilla.REGISTRIES);
                    assertEquals(Protocol.OUT_CHUNK_DATA, nb.read(NetworkBuffer.VAR_INT));
                    ChunkDataPacket p = nb.read(ChunkDataPacket.SERIALIZER);
                    assertEquals(0, nb.readableBytes(), "no trailing bytes");
                    assertEquals(cx, p.chunkX());
                    assertEquals(cz, p.chunkZ());
                    assertSections(s, cx, cz, p.chunkData().data());
                    assertHeightmaps(s, cx, cz, p);
                    var light = p.lightData();
                    assertEquals(Vanilla.SECTIONS + 2, light.skyMask().cardinality());
                    assertEquals(Vanilla.SECTIONS + 2, light.skyLight().size());
                    for (byte[] a : light.skyLight()) for (byte b : a) assertEquals((byte) 0xFF, b);
                    assertTrue(light.blockLight().isEmpty());
                    assertTrue(p.chunkData().blockEntities().isEmpty());
                } finally {
                    out.release();
                }
            }
        }
    }

    private static void assertSections(BlockStorage s, int cx, int cz, byte[] data) {
        NetworkBuffer nb = NetworkBuffer.wrap(data, 0, data.length);
        for (int vs = 0; vs < Vanilla.SECTIONS; vs++) {
            short count = nb.read(NetworkBuffer.SHORT);
            Palette p = nb.read(Palette.BLOCK_SERIALIZER);
            int nonAir = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int ours = s.get(cx * 16 + x, Vanilla.MIN_Y + vs * 16 + y, cz * 16 + z);
                if (ours != 0) nonAir++;
                assertEquals(Protocol.vanillaState(ours), p.get(x, y, z));
            }
            assertEquals(nonAir, count, "non-air count, vanilla section " + vs);
            assertEquals(Vanilla.PLAINS_BIOME, nb.read(Palette.biomeSerializer(64)).get(0, 0, 0));
        }
        assertEquals(data.length, nb.readIndex(), "exactly " + Vanilla.SECTIONS + " sections");
    }

    private static void assertHeightmaps(BlockStorage s, int cx, int cz, ChunkDataPacket p) {
        long[] surface = p.chunkData().heightmaps().get(Heightmap.Type.WORLD_SURFACE);
        long[] motion = p.chunkData().heightmaps().get(Heightmap.Type.MOTION_BLOCKING);
        int bits = 32 - Integer.numberOfLeadingZeros(Vanilla.SECTIONS * 16);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int ws = 0, mb = 0;
            for (int y = s.maxYExclusive() - 1; y >= s.minY(); y--) {
                int st = s.get(cx * 16 + x, y, cz * 16 + z);
                if (ws == 0 && st != 0) ws = y + 1 - Vanilla.MIN_Y;
                if (mb == 0 && Blocks.isSolid(st)) mb = y + 1 - Vanilla.MIN_Y;
            }
            assertEquals(ws, packed(surface, bits, z * 16 + x), "WORLD_SURFACE " + x + "," + z);
            assertEquals(mb, packed(motion, bits, z * 16 + x), "MOTION_BLOCKING " + x + "," + z);
        }
    }

    private static byte[] minestom(ServerPacket packet, int threshold) {
        NetworkBuffer nb = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
        PacketWriting.writeFramedPacket(nb, ConnectionState.PLAY, packet, threshold);
        byte[] bytes = new byte[(int) nb.writeIndex()];
        nb.copyTo(0, bytes, 0, bytes.length);
        return bytes;
    }

    private static byte[] ours(PlayWriter w, BiConsumer<PlayWriter, ByteBuf> write) {
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer();
        try {
            write.accept(w, out);
            byte[] bytes = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), bytes);
            return bytes;
        } finally {
            out.release();
        }
    }

    /**
     * Packet bodies are byte-identical to Minestom's. (Frames differ only in VarInt width: Minestom back-fills
     * fixed 3-byte length prefixes, we write minimal ones. Vanilla accepts both.)
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void smallPacketsMatchMinestom(int threshold) throws Exception {
        try (var w = new PlayWriter(threshold)) {
            assertSame(threshold, new KeepAlivePacket(123456789L), ours(w, (p, o) -> p.keepAlive(123456789L, o)));
            assertSame(threshold, new UpdateViewPositionPacket(3, -4), ours(w, (p, o) -> p.viewCenter(3, -4, o)));
            assertSame(threshold, new ChunkBatchStartPacket(), ours(w, PlayWriter::batchStart));
            assertSame(threshold, new ChunkBatchFinishedPacket(17), ours(w, (p, o) -> p.batchFinished(17, o)));
            assertSame(threshold, new AcknowledgeBlockChangePacket(99), ours(w, (p, o) -> p.acknowledgeBlockChange(99, o)));
        }
    }

    private static void assertSame(int threshold, ServerPacket expected, byte[] ours) throws Exception {
        byte[] theirs = minestom(expected, threshold);
        assertArrayEquals(unframe(io.netty.buffer.Unpooled.wrappedBuffer(theirs), threshold),
                unframe(io.netty.buffer.Unpooled.wrappedBuffer(ours), threshold), expected.getClass().getSimpleName());
        assertTrue(ours.length <= theirs.length, "minimal VarInts");
    }

    @Test
    void externalReadsDeferReclaimInsteadOfRacingIt() {
        var s = new BlockStorage(mem, 1, 1, 0, 1, 1);
        s.set(0, 0, 0, Blocks.STONE);
        s.set(0, 0, 0, Blocks.AIR); // section retired
        s.beginExternalRead();
        assertEquals(0, s.reclaim(), "a chunk encoder is reading: reclaim must wait");
        assertEquals(1, s.reclaimDeferrals());
        s.endExternalRead();
        assertEquals(1, s.reclaim(), "and goes ahead once it is done");
    }
}
