package dev.mulcor.net;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import io.netty.buffer.ByteBuf;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Zero-allocation play-state decoder for one connection.
 *
 * <p>It reads length-prefixed frames straight out of Netty's (pooled, direct) buffer and translates the hot
 * packets field by field into a 32-byte off-heap {@link Input} record, which it hands to the region ingress rings
 * through an {@link InputSink}. No packet objects, boxing or copies are created. Hot packets are dig, block
 * placement, the four movement packets (position, position+rotation, rotation, on-ground) and container clicks. Everything else is cold: it is counted and skipped, and could be
 * handed to Minestom's object-based parser off the hot path.
 *
 * <p>Backpressure: if the sink rejects a record, decoding stops <i>before</i> that frame, so the caller keeps the
 * unread bytes and retries later. Input is never dropped.
 */
public final class IngressDecoder {
    public enum Result { DRAINED, NEED_MORE, BACKPRESSURE, MALFORMED }

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    /** Largest frame accepted; bigger frames are a protocol violation. */
    public static final int MAX_FRAME = 2 * 1024 * 1024;

    private final MemorySegment record;
    private final InputSink sink;
    private final boolean compression;
    private int entity;
    private long hot, cold, frames;
    // Read by the connection's PlaySession on the same event loop: last reported position and the newest
    // block-action sequence number the client is waiting to have acknowledged.
    private int lastX1000, lastZ1000, lastSequence = -1;
    private boolean hasPosition;

    /**
     * @param compression if true, frames carry the post-compression-threshold header (VarInt dataLength). Frames
     *     with dataLength 0 (below the threshold, which is every hot packet) are decoded in place. Compressed
     *     frames are cold.
     */
    public IngressDecoder(int entity, InputSink sink, boolean compression) {
        this.entity = entity;
        this.sink = sink;
        this.compression = compression;
        this.record = NativeMemory.auto().allocate(Input.BYTES);
    }

    public void entity(int entity) { this.entity = entity; }
    public long hotPackets() { return hot; }
    public long coldPackets() { return cold; }
    public long frames() { return frames; }
    public boolean hasPosition() { return hasPosition; }
    public int lastX1000() { return lastX1000; }
    public int lastZ1000() { return lastZ1000; }
    /** Highest block-action sequence seen, or -1. */
    public int lastSequence() { return lastSequence; }

    /** Decode as many complete frames as possible, advancing {@code in}'s reader index past each consumed frame. */
    public Result decode(ByteBuf in) {
        while (true) {
            int start = in.readerIndex(), limit = in.writerIndex();
            if (start == limit) return Result.DRAINED;
            long len = VarInts.peek(in, start, limit);
            if (len == VarInts.INCOMPLETE) return Result.NEED_MORE;
            if (len == VarInts.MALFORMED) return Result.MALFORMED;
            int frameLen = (int) len, headerLen = (int) (len >>> 32);
            if (frameLen < 0 || frameLen > MAX_FRAME) return Result.MALFORMED;
            int body = start + headerLen, end = body + frameLen;
            if (end > limit) return Result.NEED_MORE;
            int save = in.writerIndex();
            in.readerIndex(body).writerIndex(end); // bound reads to this frame
            boolean accepted;
            try {
                accepted = frame(in);
            } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
                in.writerIndex(save).readerIndex(start);
                return Result.MALFORMED;
            }
            in.writerIndex(save);
            if (!accepted) {
                in.readerIndex(start); // leave the frame unread; caller retries after backpressure clears
                return Result.BACKPRESSURE;
            }
            in.readerIndex(end);
            frames++;
        }
    }

    /** Returns false only when the sink pushed back. */
    private boolean frame(ByteBuf in) {
        if (compression && VarInts.read(in) != 0) {
            cold++; // compressed: never a hot packet (all hot packets are far below any threshold)
            return true;
        }
        int id = VarInts.read(in);
        if (id == Protocol.DIG) {
            int status = VarInts.read(in);
            long pos = in.readLong();
            in.readByte(); // face
            lastSequence = Math.max(lastSequence, VarInts.read(in));
            if (status != 0 && status != 2) { cold++; return true; } // only start/finish digging break blocks
            return emit(Input.DIG, VarInts.blockX(pos), VarInts.blockY(pos), VarInts.blockZ(pos), 0, 0, 0);
        }
        if (id == Protocol.PLACE) {
            VarInts.read(in); // hand
            long pos = in.readLong();
            int face = VarInts.read(in);
            in.skipBytes(3 * Float.BYTES + 2); // cursor x/y/z, inside block, hit world border
            lastSequence = Math.max(lastSequence, VarInts.read(in));
            int x = VarInts.blockX(pos), y = VarInts.blockY(pos), z = VarInts.blockZ(pos);
            switch (face) { // Minestom BlockFace order: BOTTOM, TOP, NORTH, SOUTH, WEST, EAST
                case 0 -> y--;
                case 1 -> y++;
                case 2 -> z--;
                case 3 -> z++;
                case 4 -> x--;
                case 5 -> x++;
                default -> throw new IllegalArgumentException("face");
            }
            return emit(Input.PLACE, x, y, z, Blocks.DIRT, face + 1, 0);
        }
        if (id == Protocol.POSITION) {
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            return position(x, y, z, ground(in.readByte()), 0f, 0f);
        }
        if (id == Protocol.POSITION_ROTATION) {
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat();
            return position(x, y, z, Input.HAS_ROTATION | ground(in.readByte()), yaw, pitch);
        }
        if (id == Protocol.ROTATION) {
            float yaw = in.readFloat(), pitch = in.readFloat();
            int flags = Input.NO_POSITION | Input.HAS_ROTATION | ground(in.readByte());
            return emit(Input.POSITION, 0, 0, 0, flags, Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(pitch));
        }
        if (id == Protocol.GROUND) {
            return emit(Input.POSITION, 0, 0, 0, Input.NO_POSITION | ground(in.readByte()), 0, 0);
        }
        if (id == Protocol.CLICK_WINDOW) {
            int window = VarInts.read(in);
            VarInts.read(in); // state id
            short slot = in.readShort();
            byte button = in.readByte();
            int mode = VarInts.read(in);
            // Window ids map to chests: window n shows chest n - 1. PICKUP left = take a stack, right = half;
            // QUICK_MOVE (shift-click) = move a stack from the player into the chest.
            int count = mode == 0 ? (button == 0 ? 64 : 32) : mode == 1 ? -64 : 0;
            if (count == 0 || window <= 0 || slot < 0) { cold++; return true; }
            return emit(Input.CHEST, 0, 0, 0, window - 1, slot, count);
        }
        cold++;
        return true;
    }

    private static int ground(byte flags) {
        return (flags & 1) != 0 ? Input.ON_GROUND : 0;
    }

    private boolean position(double x, double y, double z, int flags, float yaw, float pitch) {
        int x1000 = (int) Math.round(x * 1000), z1000 = (int) Math.round(z * 1000);
        if (!emit(Input.POSITION, x1000, (int) Math.round(y * 1000), z1000, flags,
                Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(pitch))) return false;
        lastX1000 = x1000;
        lastZ1000 = z1000;
        hasPosition = true;
        return true;
    }

    private boolean emit(int kind, int x, int y, int z, int a, int b, int c) {
        record.set(I, Input.KIND, kind);
        record.set(I, Input.ENTITY, entity);
        record.set(I, Input.X, x);
        record.set(I, Input.Y, y);
        record.set(I, Input.Z, z);
        record.set(I, Input.A, a);
        record.set(I, Input.B, b);
        record.set(I, Input.C, c);
        if (!sink.offer(record, 0)) return false;
        hot++;
        return true;
    }
}
