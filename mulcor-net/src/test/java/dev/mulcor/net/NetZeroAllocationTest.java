package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.AllocationMeter;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapRing;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.SplittableRandom;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Network hot paths allocate nothing once warm. Decoding goes from a pooled direct buffer into an off-heap
 * ingress ring. Encoding goes from off-heap blocks into a pooled direct buffer, and compression goes from direct
 * buffer to direct buffer.
 */
@Tag("alloc")
class NetZeroAllocationTest {
    @Test
    void ingressDecodeIntoRingAllocatesNothing() {
        try (var mem = new NativeMemory()) {
            OffHeapRing ring = new OffHeapRing(mem, 4096, Input.BYTES);
            InputSink sink = (r, o) -> ring.offer(r, o, Input.BYTES);
            var dec = new IngressDecoder(5, sink, false);
            ByteBuf frames = PooledByteBufAllocator.DEFAULT.directBuffer(1 << 16);
            try {
                for (int i = 0; i < 100; i++) {
                    Packets.write(frames, Packets.dig(i, 3, -i), Packets.position(i + 0.5, 64, -i), Packets.place(i, 4, i, BlockFace.NORTH),
                            Packets.click(3, i % 27, 0, ClickType.PICKUP), Packets.cold());
                }
                OffHeapRing.SlotHandler drop = (s, o) -> { };
                Runnable round = () -> {
                    frames.readerIndex(0);
                    dec.decode(frames);
                    ring.drain(drop, Integer.MAX_VALUE);
                };
                for (int i = 0; i < 20_000; i++) round.run();
                long before = AllocationMeter.currentThread();
                for (int i = 0; i < 20_000; i++) round.run(); // 10M frames
                long allocated = AllocationMeter.currentThread() - before;
                System.out.printf("decode: %d frames, %d B allocated%n", 20_000L * 500, allocated);
                assertEquals(0, allocated);
            } finally {
                frames.release();
            }
        }
    }

    @Test
    void chunkEncodeAndCompressAllocateNothing() {
        try (var mem = new NativeMemory(); var comp = new ChunkCompressor(4)) {
            var s = new BlockStorage(mem, 2, 2, 0, 4, 64);
            var rnd = new SplittableRandom(1);
            for (int i = 0; i < 30_000; i++) s.set(rnd.nextInt(32), rnd.nextInt(64), rnd.nextInt(32), 1 + rnd.nextInt(6));
            s.set(0, 0, 0, Blocks.STONE);
            var enc = new ChunkEncoder();
            ByteBuf raw = PooledByteBufAllocator.DEFAULT.directBuffer(1 << 16), packed = PooledByteBufAllocator.DEFAULT.directBuffer(1 << 16);
            try {
                Runnable round = () -> {
                    raw.clear();
                    packed.clear();
                    enc.encodeColumn(s, rnd.nextInt(2), rnd.nextInt(2), 0, raw);
                    comp.compress(raw, packed);
                };
                // Netty's writeLong/ensureWritable paths need a long warm-up to leave C1 (JFR showed tier-transition
                // allocations in AbstractByteBuf.ensureWritable0 during shorter warm-ups).
                for (int i = 0; i < 20_000; i++) round.run();
                long before = AllocationMeter.currentThread();
                for (int i = 0; i < 3000; i++) round.run();
                long allocated = AllocationMeter.currentThread() - before;
                System.out.printf("chunk encode+compress: 3000 columns, %d B allocated (last column %d -> %d bytes)%n",
                        allocated, raw.readableBytes(), packed.readableBytes());
                assertEquals(0, allocated);
            } finally {
                raw.release();
                packed.release();
            }
        }
    }

    /** A connection's steady-state output: chunk columns streamed from off-heap storage plus the small packets. */
    @Test
    void playStreamingAllocatesNothing() {
        try (var mem = new NativeMemory(); var w = new PlayWriter(256)) {
            var s = new BlockStorage(mem, 4, 4, 0, 4, 64);
            var rnd = new SplittableRandom(2);
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) for (int y = 0; y < 4; y++) s.set(x, y, z, Blocks.STONE);
            for (int i = 0; i < 20_000; i++) s.set(rnd.nextInt(64), 4 + rnd.nextInt(60), rnd.nextInt(64), 1 + rnd.nextInt(20));
            ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(1 << 20);
            try {
                int[] n = {0};
                Runnable round = () -> {
                    out.clear();
                    int i = n[0]++;
                    w.batchStart(out);
                    w.chunk(s, i & 3, (i >> 2) & 3, out);
                    w.batchFinished(1, out);
                    w.viewCenter(i & 3, (i >> 2) & 3, out);
                    w.acknowledgeBlockChange(i, out);
                    w.keepAlive(i, out);
                };
                for (int i = 0; i < 20_000; i++) round.run();
                long before = AllocationMeter.currentThread();
                for (int i = 0; i < 3000; i++) round.run();
                long allocated = AllocationMeter.currentThread() - before;
                System.out.printf("play streaming: 3000 chunk packets + 15000 small packets, %d B allocated%n", allocated);
                assertEquals(0, allocated);
            } finally {
                out.release();
            }
        }
    }
}
