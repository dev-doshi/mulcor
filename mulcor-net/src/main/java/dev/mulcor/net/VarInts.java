package dev.mulcor.net;

import io.netty.buffer.ByteBuf;

/** Minecraft VarInt codec on Netty buffers, allocation-free. */
public final class VarInts {
    /** Returned by {@link #peek} when the buffer does not yet hold a complete VarInt. */
    public static final long INCOMPLETE = -1;
    /** Returned by {@link #peek} when the bytes cannot be a valid VarInt (> 5 bytes). */
    public static final long MALFORMED = -2;

    private VarInts() {}

    /** Read a VarInt at {@code index} without moving indices: returns {@code value | size << 32}, or INCOMPLETE/MALFORMED. */
    public static long peek(ByteBuf buf, int index, int limit) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (index + i >= limit) return INCOMPLETE;
            byte b = buf.getByte(index + i);
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return (value & 0xFFFF_FFFFL) | ((long) (i + 1) << 32);
        }
        return MALFORMED;
    }

    public static int read(ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("VarInt too long");
    }

    public static void write(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static void writeLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    public static int size(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            n++;
            value >>>= 7;
        }
        return n;
    }

    /** Block position packed as x:26 | z:26 | y:12 (signed). */
    public static int blockX(long packed) { return (int) (packed >> 38); }
    public static int blockY(long packed) { return (int) (packed << 52 >> 52); }
    public static int blockZ(long packed) { return (int) (packed << 26 >> 38); }
}
