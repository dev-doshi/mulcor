package dev.mulcor.net;

import io.netty.buffer.ByteBuf;

/**
 * Cold play packets (chat, commands) parked by the {@link IngressDecoder} for the connection's {@link PlaySession}:
 * the decoder copies each frame's id and payload into a fixed byte array (no allocation on the ingress path); the
 * session, on the same event loop, parses and handles them at its next update with Minestom's object serializers.
 * When the array is full the decoder pushes back and the frame waits in Netty's buffer.
 *
 * <p>Entry layout: {@code [length int][packet id int][payload]}, big-endian.
 */
final class ColdMailbox {
    static final int CAPACITY = 64 * 1024;
    private final byte[] buf = new byte[CAPACITY];
    private int used;
    private long dropped;

    /** Park the rest of {@code in} (the frame's payload) as packet {@code id}; false if there is no room now. */
    boolean offer(int id, ByteBuf in) {
        int len = in.readableBytes();
        if (8 + len > CAPACITY) { // can never fit: a vanilla client never sends one
            in.skipBytes(len);
            dropped++;
            return true;
        }
        if (used + 8 + len > CAPACITY) return false;
        putInt(used, len);
        putInt(used + 4, id);
        in.readBytes(buf, used + 8, len);
        used += 8 + len;
        return true;
    }

    boolean isEmpty() { return used == 0; }
    long dropped() { return dropped; }
    byte[] array() { return buf; }
    int used() { return used; }
    void clear() { used = 0; }

    int getInt(int at) {
        return (buf[at] & 0xFF) << 24 | (buf[at + 1] & 0xFF) << 16 | (buf[at + 2] & 0xFF) << 8 | buf[at + 3] & 0xFF;
    }

    private void putInt(int at, int v) {
        buf[at] = (byte) (v >>> 24);
        buf[at + 1] = (byte) (v >>> 16);
        buf[at + 2] = (byte) (v >>> 8);
        buf[at + 3] = (byte) v;
    }
}
