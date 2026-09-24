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
            var bl = new dev.mulcor.memory.LightStorage(mem, 4, 4, 0, 4, 4 * 4 * 6, 0);
            var sl = new dev.mulcor.memory.LightStorage(mem, 4, 4, 0, 4, 4 * 4 * 6, 0);
            new dev.mulcor.core.light.LightEngine(s, bl, sl).relightAll();
            ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(1 << 20);
            try {
                int[] n = {0};
                Runnable round = () -> {
                    out.clear();
                    int i = n[0]++;
                    w.batchStart(out);
                    w.chunk(s, bl, sl, i & 3, (i >> 2) & 3, out);
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
    /**
     * Protocol encryption on a connection's hot path: every inbound read (a direct socket buffer) is decrypted and
     * every outbound batch encrypted in place, through the JDK's AES/CFB8, with no heap garbage. Runs on a Netty
     * thread, as the codec does in production (it keeps its staging array in a {@code FastThreadLocal}).
     */
    @Test
    void encryptionBothWaysAllocatesNothing() throws Exception {
        long[] result = {-1};
        Throwable[] failure = {null};
        Thread t = new io.netty.util.concurrent.FastThreadLocalThread(() -> {
            try {
                result[0] = encryptionRounds();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        t.start();
        t.join();
        if (failure[0] != null) throw new AssertionError(failure[0]);
        assertEquals(0, result[0]);
    }

    private static long encryptionRounds() throws Exception {
        byte[] secret = new byte[16];
        new SplittableRandom(5).nextBytes(secret);
        var key = Crypto.secret(secret);
        var client = new CipherCodec(Crypto.cipher(javax.crypto.Cipher.DECRYPT_MODE, key), Crypto.cipher(javax.crypto.Cipher.ENCRYPT_MODE, key));
        var server = new CipherCodec(Crypto.cipher(javax.crypto.Cipher.DECRYPT_MODE, key), Crypto.cipher(javax.crypto.Cipher.ENCRYPT_MODE, key));
        byte[] payload = new byte[100_000];
        new SplittableRandom(6).nextBytes(payload);
        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(payload.length);
        ByteBuf heap = PooledByteBufAllocator.DEFAULT.heapBuffer(payload.length);
        try {
            int[] sizes = {17, 300, 4096, 65_536, 100_000};
            int[] n = {0};
            Runnable round = () -> {
                int i = n[0]++;
                int size = sizes[i % sizes.length];
                ByteBuf buf = (i & 1) == 0 ? direct : heap;
                buf.clear().writeBytes(payload, 0, size);
                client.encryptInPlace(buf); // what the server's outbound path does to a packet batch
                server.decryptInPlace(buf); // what the server's inbound path does to a socket read
                if (buf.getByte(size - 1) != payload[size - 1]) throw new AssertionError("round trip mismatch");
            };
            for (int i = 0; i < 30_000; i++) round.run();
            long before = AllocationMeter.currentThread();
            for (int i = 0; i < 5000; i++) round.run();
            long allocated = AllocationMeter.currentThread() - before;
            System.out.printf("AES/CFB8: 5000 in-place encrypt+decrypt rounds (17 B .. 100 KB), %d B allocated%n", allocated);
            return allocated;
        } finally {
            direct.release();
            heap.release();
        }
    }
}
