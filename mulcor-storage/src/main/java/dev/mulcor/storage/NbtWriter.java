package dev.mulcor.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming NBT writer into a reusable, growable big-endian heap buffer. No tag objects: the caller emits fields
 * in order ({@code int_("DataVersion", v)}, {@code beginCompound("Level")} ... {@code end()}). One instance per I/O
 * thread; {@link #reset()} between documents keeps the buffer.
 *
 * <p>Inside a list, use the unnamed element methods ({@link #compoundElement()}, {@link #stringElement} ...).
 */
public final class NbtWriter {
    private ByteBuffer b;

    public NbtWriter(int initialCapacity) {
        b = ByteBuffer.allocate(initialCapacity).order(ByteOrder.BIG_ENDIAN);
    }

    public NbtWriter reset() {
        b.clear();
        return this;
    }

    /** The written bytes are {@code [0, position)} of the returned buffer (flipped view not applied). */
    public ByteBuffer buffer() { return b; }
    public int size() { return b.position(); }

    public byte[] toByteArray() {
        byte[] out = new byte[b.position()];
        b.get(0, out);
        return out;
    }

    private void ensure(int n) {
        if (b.remaining() < n) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(b.capacity() * 2, b.position() + n)).order(ByteOrder.BIG_ENDIAN);
            b.flip();
            bigger.put(b);
            b = bigger;
        }
    }

    private void header(int type, String name) {
        ensure(1);
        b.put((byte) type);
        utf(name);
    }

    /** Root compound as in files and region chunks (named, usually ""). */
    public NbtWriter beginRoot(String name) {
        header(Nbt.COMPOUND, name);
        return this;
    }

    public NbtWriter beginCompound(String name) {
        header(Nbt.COMPOUND, name);
        return this;
    }

    /** Close the current compound (TAG_End). */
    public NbtWriter end() {
        ensure(1);
        b.put((byte) Nbt.END);
        return this;
    }

    public NbtWriter byte_(String name, int v) { header(Nbt.BYTE, name); ensure(1); b.put((byte) v); return this; }
    public NbtWriter short_(String name, int v) { header(Nbt.SHORT, name); ensure(2); b.putShort((short) v); return this; }
    public NbtWriter int_(String name, int v) { header(Nbt.INT, name); ensure(4); b.putInt(v); return this; }
    public NbtWriter long_(String name, long v) { header(Nbt.LONG, name); ensure(8); b.putLong(v); return this; }
    public NbtWriter float_(String name, float v) { header(Nbt.FLOAT, name); ensure(4); b.putFloat(v); return this; }
    public NbtWriter double_(String name, double v) { header(Nbt.DOUBLE, name); ensure(8); b.putDouble(v); return this; }
    public NbtWriter string(String name, String v) { header(Nbt.STRING, name); utf(v); return this; }

    public NbtWriter byteArray(String name, byte[] v) {
        header(Nbt.BYTE_ARRAY, name);
        ensure(4 + v.length);
        b.putInt(v.length).put(v);
        return this;
    }

    public NbtWriter intArray(String name, int[] v) {
        header(Nbt.INT_ARRAY, name);
        ensure(4 + v.length * 4);
        b.putInt(v.length);
        for (int x : v) b.putInt(x);
        return this;
    }

    public NbtWriter longArray(String name, long[] v, int count) {
        header(Nbt.LONG_ARRAY, name);
        ensure(4 + count * 8);
        b.putInt(count);
        for (int i = 0; i < count; i++) b.putLong(v[i]);
        return this;
    }

    /** A named field whose payload is already-encoded NBT (as captured by a reader): header + raw bytes. */
    public NbtWriter raw(int type, String name, byte[] payload) {
        header(type, name);
        ensure(payload.length);
        b.put(payload);
        return this;
    }

    /** Start a list of {@code length} elements of {@code elementType}; write exactly that many unnamed elements. */
    public NbtWriter beginList(String name, int elementType, int length) {
        header(Nbt.LIST, name);
        ensure(5);
        b.put((byte) (length == 0 ? Nbt.END : elementType)).putInt(length);
        return this;
    }

    /** A compound element of a list: its fields follow, closed by {@link #end()}. */
    public NbtWriter compoundElement() { return this; }
    public NbtWriter stringElement(String v) { utf(v); return this; }
    public NbtWriter intElement(int v) { ensure(4); b.putInt(v); return this; }
    public NbtWriter doubleElement(double v) { ensure(8); b.putDouble(v); return this; }
    public NbtWriter floatElement(float v) { ensure(4); b.putFloat(v); return this; }

    /** Modified UTF-8, as {@code DataOutput.writeUTF}. */
    private void utf(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            len += c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        if (len > 0xFFFF) throw new IllegalArgumentException("NBT string longer than 65535 bytes");
        ensure(2 + len);
        b.putShort((short) len);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != 0 && c < 0x80) {
                b.put((byte) c);
            } else if (c < 0x800) {
                b.put((byte) (0xC0 | (c >> 6))).put((byte) (0x80 | (c & 0x3F)));
            } else {
                b.put((byte) (0xE0 | (c >> 12))).put((byte) (0x80 | ((c >> 6) & 0x3F))).put((byte) (0x80 | (c & 0x3F)));
            }
        }
    }
}
