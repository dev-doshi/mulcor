package dev.mulcor.net;

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
    private final ColdMailbox mailbox = new ColdMailbox();
    private int entity;
    private long hot, cold, frames;
    // Read by the connection's PlaySession on the same event loop: last reported position and the newest
    // block-action sequence number the client is waiting to have acknowledged.
    private int lastX1000, lastY1000, lastZ1000, lastSequence = -1;
    private boolean hasPosition;
    /** A server teleport the client has not confirmed yet (-1: none); its movement is ignored until it does. */
    private int awaitTeleport = -1;
    private int windowCloses;

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
    /** The server sent teleport {@code id}: like vanilla, ignore movement until the client confirms it. */
    void expectTeleport(int id) { awaitTeleport = id; }
    public long hotPackets() { return hot; }
    public long coldPackets() { return cold; }
    public long frames() { return frames; }
    /** Chat and command frames waiting for the session (same event loop). */
    ColdMailbox mailbox() { return mailbox; }
    public boolean hasPosition() { return hasPosition; }
    public int lastX1000() { return lastX1000; }
    public int lastY1000() { return lastY1000; }
    public int lastZ1000() { return lastZ1000; }
    /** Highest block-action sequence seen, or -1. */
    public int lastSequence() { return lastSequence; }
    /** Close Window packets decoded so far: a window the client closed itself needs no Close Window back. */
    public int windowCloses() { return windowCloses; }

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
        if (compression) {
            int dataLength = VarInts.read(in);
            if (dataLength != 0) {
                // Compressed (at or above the threshold): parked for the session, which inflates it off the hot path
                // and feeds it back through packet(). Entry id ~dataLength (negative) marks it.
                if (dataLength < 0 || dataLength > MAX_FRAME) throw new IllegalArgumentException("dataLength");
                if (!mailbox.offer(~dataLength, in)) return false;
                cold++;
                return true;
            }
        }
        return packet(in);
    }

    /** One uncompressed packet (id, then payload) bounded by {@code in}'s writer index; false on backpressure. */
    boolean packet(ByteBuf in) {
        int id = VarInts.read(in);
        if (id == Protocol.DIG) {
            int status = VarInts.read(in);
            long pos = in.readLong();
            in.readByte(); // face
            lastSequence = Math.max(lastSequence, VarInts.read(in));
            // ServerboundPlayerActionPacket.Action: 3 DROP_ITEM, 4 DROP_ALL_ITEMS, 6 SWAP_ITEM_WITH_OFFHAND
            if (status == 3 || status == 4) return emit(Input.DROP, 0, 0, 0, status == 4 ? 1 : 0, 0, 0);
            if (status == 6) return emit(Input.SWAP_HANDS, 0, 0, 0, 0, 0, 0);
            if (status != 0 && status != 2) { cold++; return true; } // only start/finish digging break blocks
            return emit(Input.DIG, VarInts.blockX(pos), VarInts.blockY(pos), VarInts.blockZ(pos), status + 1, 0, 0);
        }
        if (id == Protocol.PLACE) {
            VarInts.read(in); // hand
            long pos = in.readLong();
            int face = VarInts.read(in);
            int cursor = cursor(in.readFloat()) | cursor(in.readFloat()) << 10 | cursor(in.readFloat()) << 20;
            in.skipBytes(2); // inside block, hit world border
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
            return emit(Input.PLACE, x, y, z, Input.HELD_ITEM, face + 1, cursor);
        }
        if (id == Protocol.HELD_ITEM) {
            return emit(Input.HELD_SLOT, 0, 0, 0, in.readShort(), 0, 0);
        }
        if (id == Protocol.CREATIVE_SLOT) {
            // slot, then the item stack: count, item id, component patch; of the patch only minecraft:damage is kept
            short slot = in.readShort();
            int count = VarInts.read(in);
            int item = count > 0 ? VarInts.read(in) : 0;
            int damage = count > 0 ? damage(in) : 0;
            return emit(Input.CREATIVE_SLOT, 0, 0, 0, slot, item, count | damage << 16);
        }
        if (id == Protocol.TELEPORT_CONFIRM) {
            if (VarInts.read(in) == awaitTeleport) awaitTeleport = -1;
            cold++;
            return true;
        }
        if (awaitTeleport >= 0 && (id == Protocol.POSITION || id == Protocol.POSITION_ROTATION || id == Protocol.ROTATION
                || id == Protocol.GROUND)) {
            cold++; // moves from before the client saw the teleport
            return true;
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
        if (id == Protocol.SWING) {
            return emit(Input.SWING, 0, 0, 0, VarInts.read(in), 0, 0);
        }
        if (id == Protocol.PLAYER_INPUT) {
            return emit(Input.PLAYER_INPUT, 0, 0, 0, in.readUnsignedByte(), 0, 0);
        }
        if (id == Protocol.PLAYER_COMMAND) {
            VarInts.read(in); // the player's own entity id
            int action = VarInts.read(in);
            if (action != 1 && action != 2) { cold++; return true; } // only START/STOP_SPRINTING change what others see
            return emit(Input.PLAYER_COMMAND, 0, 0, 0, action, 0, 0);
        }
        if (id == Protocol.ABILITIES) {
            return emit(Input.ABILITIES, 0, 0, 0, in.readUnsignedByte(), 0, 0);
        }
        if (id == Protocol.SETTINGS) {
            in.skipBytes(VarInts.read(in)); // language
            in.readByte(); // view distance
            VarInts.read(in); // chat visibility
            in.readByte(); // chat colours
            int skin = in.readUnsignedByte();
            int hand = VarInts.read(in);
            return emit(Input.SETTINGS, 0, 0, 0, skin, hand, 0);
        }
        if (id == Protocol.CHAT || id == Protocol.COMMAND || id == Protocol.SIGNED_COMMAND || id == Protocol.CLIENT_STATUS) {
            return mailbox.offer(id, in);
        }
        if (id == Protocol.CLICK_WINDOW) {
            // container id, state id, slot, button, click type; the client's predicted slot changes and carried item
            // are not needed: the region applies the click itself and the session resyncs any difference
            int container = VarInts.read(in);
            VarInts.read(in); // state id
            short slot = in.readShort();
            byte button = in.readByte();
            int mode = VarInts.read(in);
            return emit(Input.CLICK, container, 0, 0, slot, button, mode);
        }
        if (id == Protocol.CLOSE_WINDOW) {
            windowCloses++;
            return emit(Input.CLOSE_WINDOW, VarInts.read(in), 0, 0, 0, 0, 0);
        }
        cold++;
        return true;
    }

    /**
     * The {@code minecraft:damage} value (component 3) of an item's component patch, or 0. Added components come first,
     * as (type id, value); max_stack_size (1) and max_damage (2) are single VarInts, so they are skipped; any other
     * component before damage has a value of unknown length here and ends the scan.
     */
    private static int damage(ByteBuf in) {
        int added = VarInts.read(in);
        VarInts.read(in); // removed count
        for (int i = 0; i < added; i++) {
            int type = VarInts.read(in);
            if (type == 3) return Math.clamp(VarInts.read(in), 0, 0xFFFF);
            if (type != 1 && type != 2) return 0;
            VarInts.read(in);
        }
        return 0;
    }

    /** A click coordinate inside a block (0-1) in thousandths, 10 bits. */
    private static int cursor(float f) {
        return Math.clamp(Math.round(f * 1000), 0, 1000);
    }

    private static int ground(byte flags) {
        return (flags & 1) != 0 ? Input.ON_GROUND : 0;
    }

    private boolean position(double x, double y, double z, int flags, float yaw, float pitch) {
        int x1000 = (int) Math.round(x * 1000), z1000 = (int) Math.round(z * 1000);
        if (!emit(Input.POSITION, x1000, (int) Math.round(y * 1000), z1000, flags,
                Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(pitch))) return false;
        lastX1000 = x1000;
        lastY1000 = (int) Math.round(y * 1000);
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
