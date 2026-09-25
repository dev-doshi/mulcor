package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.light.LightEngine;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
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
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.WindowItemsPacket;
import net.minestom.server.network.packet.server.play.SetCursorItemPacket;
import net.minestom.server.network.packet.server.play.OpenWindowPacket;
import net.minestom.server.network.packet.server.play.CloseWindowPacket;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.entity.Metadata;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.component.DataComponents;
import net.kyori.adventure.text.Component;
import dev.mulcor.core.region.Stacks;
import java.util.List;
import java.util.Map;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Everything {@link PlayWriter} emits is parsed back with Minestom's (vanilla-mirroring) serializers. */
class PlayWriterTest {
    private final NativeMemory mem = new NativeMemory();
    private LightStorage blockLight, skyLight;

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
            if (rnd.nextInt(5) == 0) s.set(x, 4, z, Blocks.wire(rnd.nextInt(16))); // not motion-blocking
        }
        for (int i = 0; i < 4000; i++) s.set(16 + rnd.nextInt(16), 16 + rnd.nextInt(48), rnd.nextInt(16), 1 + rnd.nextInt(300));
        s.set(3, 63, 3, Blocks.TNT);
        s.set(40, 10, 20, dev.mulcor.registry.BlockData.defaultState(dev.mulcor.registry.BlockId.GLOWSTONE));
        blockLight = new LightStorage(mem, 3, 2, 0, 4, 3 * 2 * 6, 0);
        skyLight = new LightStorage(mem, 3, 2, 0, 4, 3 * 2 * 6, 0);
        new LightEngine(s, blockLight, skyLight).relightAll();
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

    /**
     * Every light section is in exactly one of the two masks; arrays hold the stored nibbles (above the storage,
     * {@code above}; below it, 0), and empty-mask sections are all zero.
     */
    private static void assertLight(int cx, int cz, java.util.BitSet mask, java.util.BitSet empty,
            java.util.List<byte[]> arrays, LightStorage ls, int above) {
        int wireMin = (Vanilla.MIN_Y >> 4) - 1, k = 0;
        for (int i = 0; i < Vanilla.SECTIONS + 2; i++) {
            int sy = wireMin + i;
            assertTrue(mask.get(i) ^ empty.get(i), "section " + sy + " in exactly one mask");
            byte[] a = mask.get(i) ? arrays.get(k++) : new byte[LightStorage.SECTION_BYTES];
            assertEquals(LightStorage.SECTION_BYTES, a.length);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int idx = y << 8 | z << 4 | x;
                int got = (a[idx >> 1] >> ((idx & 1) << 2)) & 15;
                int wy = sy * 16 + y;
                int want = wy >= ls.maxYExclusive() ? above : wy < ls.minY() ? 0 : ls.get(cx * 16 + x, wy, cz * 16 + z);
                assertEquals(want, got, "light at " + (cx * 16 + x) + "," + wy + "," + (cz * 16 + z));
            }
        }
        assertEquals(k, arrays.size());
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
                    w.chunk(s, blockLight, skyLight, cx, cz, out);
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
                    assertLight(cx, cz, light.skyMask(), light.emptySkyMask(), light.skyLight(), skyLight, 15);
                    assertLight(cx, cz, light.blockMask(), light.emptyBlockMask(), light.blockLight(), blockLight, 0);
                    assertTrue(p.chunkData().blockEntities().isEmpty());
                } finally {
                    out.release();
                }
            }
        }
    }

    private static void assertSections(BlockStorage s, int cx, int cz, byte[] data) {
        NetworkBuffer nb = NetworkBuffer.wrap(data, 0, data.length);
        var sectionType = ChunkData.Section.networkType(64);
        for (int vs = 0; vs < Vanilla.SECTIONS; vs++) {
            ChunkData.Section section = nb.read(sectionType);
            Palette p = section.blockStates();
            int nonAir = 0, fluids = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int ours = s.get(cx * 16 + x, Vanilla.MIN_Y + vs * 16 + y, cz * 16 + z);
                if (ours != 0) nonAir++;
                if (Protocol.isFluid(ours)) fluids++;
                assertEquals(Protocol.vanillaState(ours), p.get(x, y, z));
            }
            assertEquals(nonAir, section.blockCount(), "non-air count, vanilla section " + vs);
            assertEquals(fluids, section.liquidCount(), "fluid count, vanilla section " + vs);
            assertEquals(Vanilla.PLAINS_BIOME, section.biomes().get(0, 0, 0));
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
                if (mb == 0 && Blocks.isMotionBlocking(st)) mb = y + 1 - Vanilla.MIN_Y; // Heightmap.Types.MOTION_BLOCKING
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

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void inventoryPacketsMatchMinestom(int threshold) throws Exception {
        long stone = Stacks.of(Material.STONE.id(), 5);
        long pick = Stacks.of(Material.IRON_PICKAXE.id(), 1, 17);
        ItemStack mStone = ItemStack.of(Material.STONE, 5);
        ItemStack mPick = ItemStack.of(Material.IRON_PICKAXE).with(DataComponents.DAMAGE, 17);
        try (var w = new PlayWriter(threshold)) {
            assertSame(threshold, new SetSlotPacket(0, 1, (short) 36, mStone), ours(w, (p, o) -> p.setSlot(0, 1, 36, stone, o)));
            assertSame(threshold, new SetSlotPacket(2, 7, (short) 3, mPick), ours(w, (p, o) -> p.setSlot(2, 7, 3, pick, o)));
            assertSame(threshold, new SetSlotPacket(0, 1, (short) 5, ItemStack.AIR), ours(w, (p, o) -> p.setSlot(0, 1, 5, Stacks.EMPTY, o)));
            long[] stacks = {Stacks.EMPTY, stone, pick, Stacks.EMPTY};
            assertSame(threshold, new WindowItemsPacket(0, 4, List.of(ItemStack.AIR, mStone, mPick), mPick),
                    ours(w, (p, o) -> p.windowItems(0, 4, stacks, 0, 3, pick, o)));
            assertSame(threshold, new SetCursorItemPacket(mStone), ours(w, (p, o) -> p.setCursor(stone, o)));
            assertSame(threshold, new SetCursorItemPacket(ItemStack.AIR), ours(w, (p, o) -> p.setCursor(Stacks.EMPTY, o)));
            Component title = Component.translatable("container.crafting");
            byte[] titleBytes = Vanilla.component(title);
            assertSame(threshold, new OpenWindowPacket(1, 12, title), ours(w, (p, o) -> p.openWindow(1, 12, titleBytes, o)));
            assertSame(threshold, new CloseWindowPacket(1), ours(w, (p, o) -> p.closeWindow(1, o)));
            assertSame(threshold, new CollectItemPacket(40, 7, 3), ours(w, (p, o) -> p.collect(40, 7, 3, o)));
            assertSame(threshold, new UpdateHealthPacket(13.5f, 17, 2.5f), ours(w, (p, o) -> p.updateHealth(13.5f, 17, 2.5f, o)));
            assertSame(threshold, new EntityMetaDataPacket(40, Map.of(8, Metadata.ItemStack(mPick))), ours(w, (p, o) -> p.itemMeta(40, pick, o)));
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
