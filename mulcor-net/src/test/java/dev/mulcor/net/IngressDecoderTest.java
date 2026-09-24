package dev.mulcor.net;

import static dev.mulcor.net.RecordingSink.Rec;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.region.Input;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType;
import org.junit.jupiter.api.Test;

class IngressDecoderTest {
    private static ByteBuf direct() {
        return PooledByteBufAllocator.DEFAULT.directBuffer(1 << 16);
    }

    @Test
    void decodesMinestomEncodedHotPacketsFieldByField() {
        var sink = new RecordingSink();
        var dec = new IngressDecoder(77, sink, false);
        ByteBuf buf = direct();
        try {
            Packets.write(buf, Packets.dig(10, 5, -20), Packets.place(3, 64, 4, BlockFace.EAST), Packets.position(12.25, 70.5, -3.125),
                    Packets.click(5, 7, 0, ClickType.PICKUP), Packets.click(5, 8, 1, ClickType.PICKUP),
                    Packets.click(2, 0, 0, ClickType.QUICK_MOVE), Packets.cold(), Packets.cancelDig(1, 2, 3));
            assertEquals(IngressDecoder.Result.DRAINED, dec.decode(buf));
            assertEquals(List.of(
                    new Rec(Input.DIG, 77, 10, 5, -20, 0, 0, 0),
                    new Rec(Input.PLACE, 77, 4, 64, 4, Blocks.DIRT, 0, 0),
                    new Rec(Input.POSITION, 77, 12250, 70500, -3125, Input.ON_GROUND, 0, 0),
                    new Rec(Input.CHEST, 77, 0, 0, 0, 4, 7, 64),
                    new Rec(Input.CHEST, 77, 0, 0, 0, 4, 8, 32),
                    new Rec(Input.CHEST, 77, 0, 0, 0, 1, 0, -64)), sink.records);
            assertEquals(6, dec.hotPackets());
            assertEquals(2, dec.coldPackets());
            assertEquals(8, dec.frames());
        } finally {
            buf.release();
        }
    }

    @Test
    void allFourMovementPacketsBecomePositionRecords() {
        var sink = new RecordingSink();
        var dec = new IngressDecoder(9, sink, false);
        ByteBuf buf = direct();
        try {
            Packets.write(buf, Packets.positionRotation(1.5, 70, -2.25, 90f, -45f, true), Packets.rotation(180f, 10f, false),
                    Packets.ground(true));
            assertEquals(IngressDecoder.Result.DRAINED, dec.decode(buf));
            int yaw90 = Float.floatToRawIntBits(90f), pitchM45 = Float.floatToRawIntBits(-45f);
            assertEquals(List.of(
                    new Rec(Input.POSITION, 9, 1500, 70000, -2250, Input.HAS_ROTATION | Input.ON_GROUND, yaw90, pitchM45),
                    new Rec(Input.POSITION, 9, 0, 0, 0, Input.NO_POSITION | Input.HAS_ROTATION,
                            Float.floatToRawIntBits(180f), Float.floatToRawIntBits(10f)),
                    new Rec(Input.POSITION, 9, 0, 0, 0, Input.NO_POSITION | Input.ON_GROUND, 0, 0)), sink.records);
            assertEquals(1500, dec.lastX1000(), "rotation-only packets keep the last position");
            assertEquals(-2250, dec.lastZ1000());
        } finally {
            buf.release();
        }
    }

    @Test
    void blockActionSequencesAreTrackedForAcknowledgement() {
        var dec = new IngressDecoder(1, new RecordingSink(), false);
        ByteBuf buf = direct();
        try {
            assertEquals(-1, dec.lastSequence());
            Packets.write(buf, Packets.dig(1, 2, 3), Packets.placeSeq(4, 5, 6, 41), Packets.cancelDig(1, 2, 3));
            assertEquals(IngressDecoder.Result.DRAINED, dec.decode(buf));
            assertEquals(41, dec.lastSequence(), "highest of 7 (dig), 41 (place), 8 (cancel)");
        } finally {
            buf.release();
        }
    }

    @Test
    void negativeAndLargeBlockPositionsRoundTrip() {
        var sink = new RecordingSink();
        var dec = new IngressDecoder(1, sink, false);
        ByteBuf buf = direct();
        try {
            Packets.write(buf, Packets.dig(-33554432, -2048, 33554431), Packets.dig(0, 2047, -1));
            dec.decode(buf);
            assertEquals(new Rec(Input.DIG, 1, -33554432, -2048, 33554431, 0, 0, 0), sink.records.get(0));
            assertEquals(new Rec(Input.DIG, 1, 0, 2047, -1, 0, 0, 0), sink.records.get(1));
        } finally {
            buf.release();
        }
    }

    @Test
    void partialFramesWaitForMoreBytes() {
        var sink = new RecordingSink();
        var dec = new IngressDecoder(1, sink, false);
        byte[] f = Packets.dig(1, 2, 3);
        ByteBuf buf = direct();
        try {
            buf.writeBytes(f, 0, f.length - 1);
            assertEquals(IngressDecoder.Result.NEED_MORE, dec.decode(buf));
            assertEquals(0, buf.readerIndex(), "partial frame must not be consumed");
            buf.writeBytes(f, f.length - 1, 1);
            assertEquals(IngressDecoder.Result.DRAINED, dec.decode(buf));
            assertEquals(1, sink.records.size());
        } finally {
            buf.release();
        }
    }

    @Test
    void backpressureLeavesFrameUnreadAndResumesExactly() {
        var sink = new RecordingSink();
        var dec = new IngressDecoder(1, sink, false);
        ByteBuf buf = direct();
        try {
            Packets.write(buf, Packets.dig(1, 1, 1), Packets.dig(2, 2, 2), Packets.dig(3, 3, 3));
            sink.rejectEvery = 2;
            assertEquals(IngressDecoder.Result.BACKPRESSURE, dec.decode(buf));
            assertEquals(1, sink.records.size());
            sink.rejectEvery = 0;
            assertEquals(IngressDecoder.Result.DRAINED, dec.decode(buf));
            assertEquals(List.of(1, 2, 3), sink.records.stream().map(Rec::x).toList(), "no loss, no duplicate, in order");
        } finally {
            buf.release();
        }
    }

    @Test
    void malformedLengthIsRejected() {
        var dec = new IngressDecoder(1, new RecordingSink(), false);
        ByteBuf buf = direct();
        try {
            buf.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1});
            assertEquals(IngressDecoder.Result.MALFORMED, dec.decode(buf));
        } finally {
            buf.release();
        }
    }

    /** Random TCP fragmentation plus random backpressure and rate limiting through the real Netty handler. */
    @Test
    void handlerSurvivesFragmentationBackpressureAndRateLimits() {
        var rnd = new SplittableRandom(3);
        var sink = new RecordingSink();
        long[] now = {0};
        TokenBucket.Clock clock = () -> now[0];
        var handler = new IngressHandler(new IngressDecoder(9, sink, false),
                new TokenBucket(50, 100_000, clock), new TokenBucket(80, 1_000_000, clock));
        var ch = new EmbeddedChannel(handler);
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 2000; i++) {
            all.writeBytes(rnd.nextInt(5) == 0 ? Packets.cold() : Packets.dig(i, 1, 1));
        }
        byte[] bytes = all.toByteArray();
        int pos = 0;
        while (pos < bytes.length) {
            int n = Math.min(bytes.length - pos, 1 + rnd.nextInt(40));
            ByteBuf chunk = ch.alloc().directBuffer(n).writeBytes(bytes, pos, n);
            pos += n;
            if (rnd.nextInt(50) == 0) sink.rejectBudget = 1 + rnd.nextInt(5);
            ch.writeInbound(chunk);
            now[0] += 20_000; // 20 µs of wall time per chunk
            ch.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            ch.runScheduledPendingTasks();
        }
        for (int i = 0; i < 10_000 && sink.records.size() < countDigs(bytes); i++) {
            now[0] += 1_000_000;
            ch.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            ch.runScheduledPendingTasks();
            ch.runPendingTasks();
        }
        List<Integer> got = sink.records.stream().map(Rec::x).toList();
        assertEquals(countDigs(bytes), got.size(), "every dig delivered exactly once");
        for (int i = 1; i < got.size(); i++) assertTrue(got.get(i) > got.get(i - 1), "order preserved");
        assertTrue(handler.pauses() > 0, "rate limit / backpressure should have paused reading");
        assertEquals(0, handler.malformed());
        assertTrue(ch.isOpen());
        ch.finishAndReleaseAll();
    }

    private static int countDigs(byte[] bytes) {
        var sink = new RecordingSink();
        ByteBuf b = direct().writeBytes(bytes);
        new IngressDecoder(0, sink, false).decode(b);
        b.release();
        return sink.records.size();
    }

    @Test
    void handlerClosesOnProtocolViolation() {
        var handler = new IngressHandler(new IngressDecoder(1, new RecordingSink(), false),
                new TokenBucket(100, 1000), new TokenBucket(100, 1000));
        var ch = new EmbeddedChannel(handler);
        ch.writeInbound(ch.alloc().directBuffer().writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0}));
        assertFalse(ch.isOpen());
        assertEquals(1, handler.malformed());
    }
}
