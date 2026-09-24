package dev.mulcor.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Streaming (pull) NBT reader over a big-endian {@link ByteBuffer}: no tag tree is built. The caller walks the
 * structure it expects and {@link #skip}s the rest, so decoding a chunk touches each byte once and allocates only
 * what the caller asks for ({@link #readString()}, arrays). Field names are compared in place with
 * {@link #nameIs(String)}.
 *
 * <pre>{@code
 * r.reset(buf);
 * r.beginRoot();                        // file NBT: TAG_Compound with a (usually empty) name
 * for (int t; (t = r.nextField()) != Nbt.END; ) {
 *     if (t == Nbt.INT && r.nameIs("DataVersion")) version = r.readInt();
 *     else r.skip(t);
 * }
 * }</pre>
 *
 * Malformed input (truncation, negative lengths, unknown types, excessive nesting) throws {@link NbtException}.
 */
public final class NbtReader {
    private ByteBuffer b;
    private int nameOffset, nameLength;
    private int listType, listLength;
    private int depth;

    public NbtReader reset(ByteBuffer buffer) {
        this.b = buffer.order(ByteOrder.BIG_ENDIAN);
        this.depth = 0;
        return this;
    }

    public ByteBuffer buffer() { return b; }

    /** Read the root compound's header (type byte and name, as in files and region chunks). */
    public void beginRoot() {
        int t = u8();
        if (t != Nbt.COMPOUND) throw new NbtException("root tag is " + t + ", expected compound");
        readName();
    }

    /** Read a nameless root compound header (network NBT since 1.20.2). */
    public void beginUnnamedRoot() {
        int t = u8();
        if (t != Nbt.COMPOUND) throw new NbtException("root tag is " + t + ", expected compound");
    }

    /**
     * Next field of the current compound: returns its tag type and positions {@link #nameIs}/{@link #name} on its
     * name, or {@link Nbt#END} when the compound is finished.
     */
    public int nextField() {
        int t = u8();
        if (t == Nbt.END) return Nbt.END;
        if (t > Nbt.LONG_ARRAY) throw new NbtException("unknown tag type " + t);
        readName();
        return t;
    }

    private void readName() {
        int len = u16();
        nameOffset = b.position();
        nameLength = len;
        need(len);
        b.position(b.position() + len);
    }

    /** True if the current field's name is exactly {@code ascii} (compared byte for byte, no allocation). */
    public boolean nameIs(String ascii) {
        if (ascii.length() != nameLength) return false;
        for (int i = 0; i < nameLength; i++) {
            if (b.get(nameOffset + i) != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    /** The current field's name (allocates). */
    public String name() {
        return decodeUtf(nameOffset, nameLength);
    }

    // ---- scalars ----

    public byte readByte() { need(1); return b.get(); }
    public short readShort() { need(2); return b.getShort(); }
    public int readInt() { need(4); return b.getInt(); }
    public long readLong() { need(8); return b.getLong(); }
    public float readFloat() { need(4); return b.getFloat(); }
    public double readDouble() { need(8); return b.getDouble(); }

    /** Read a TAG_String payload (modified UTF-8). Allocates. */
    public String readString() {
        int len = u16();
        int off = b.position();
        need(len);
        b.position(off + len);
        return decodeUtf(off, len);
    }

    /** True if the next TAG_String payload equals {@code ascii}; consumes it either way. */
    public boolean readStringIs(String ascii) {
        int len = u16();
        int off = b.position();
        need(len);
        b.position(off + len);
        if (len != ascii.length()) return false;
        for (int i = 0; i < len; i++) if (b.get(off + i) != (byte) ascii.charAt(i)) return false;
        return true;
    }

    // ---- arrays ----

    /** Read the length of a byte/int/long array; the elements follow at {@link ByteBuffer#position()}. */
    public int arrayLength() {
        int n = readInt();
        if (n < 0) throw new NbtException("negative array length " + n);
        return n;
    }

    public byte[] readByteArray() {
        int n = arrayLength();
        need(n);
        byte[] a = new byte[n];
        b.get(a);
        return a;
    }

    public int[] readIntArray() {
        int n = arrayLength();
        need((long) n * 4);
        int[] a = new int[n];
        b.asIntBuffer().get(a);
        b.position(b.position() + n * 4);
        return a;
    }

    public long[] readLongArray() {
        int n = arrayLength();
        need((long) n * 8);
        long[] a = new long[n];
        b.asLongBuffer().get(a);
        b.position(b.position() + n * 8);
        return a;
    }

    // ---- lists ----

    /** Read a list header; see {@link #listType()} and {@link #listLength()}. Elements follow (unnamed). */
    public void beginList() {
        listType = u8();
        listLength = readInt();
        if (listLength < 0) throw new NbtException("negative list length " + listLength);
        if (listType > Nbt.LONG_ARRAY) throw new NbtException("unknown list element type " + listType);
        if (listType == Nbt.END && listLength > 0) throw new NbtException("non-empty list of TAG_End");
    }

    public int listType() { return listType; }
    public int listLength() { return listLength; }

    /** Enter a compound value (list element or field): its fields follow; finish with {@link #nextField()}=END. */
    public void enterCompound() {
        if (++depth > Nbt.MAX_DEPTH) throw new NbtException("NBT nested deeper than " + Nbt.MAX_DEPTH);
    }

    /** Leave a compound entered with {@link #enterCompound()} after its END was read. */
    public void exitCompound() {
        depth--;
    }

    /** Skip a value of tag type {@code type}. */
    public void skip(int type) {
        switch (type) {
            case Nbt.BYTE -> advance(1);
            case Nbt.SHORT -> advance(2);
            case Nbt.INT, Nbt.FLOAT -> advance(4);
            case Nbt.LONG, Nbt.DOUBLE -> advance(8);
            case Nbt.BYTE_ARRAY -> advance(arrayLength());
            case Nbt.INT_ARRAY -> advance((long) arrayLength() * 4);
            case Nbt.LONG_ARRAY -> advance((long) arrayLength() * 8);
            case Nbt.STRING -> advance(u16());
            case Nbt.LIST -> {
                beginList();
                int t = listType, n = listLength;
                enterCompound(); // lists nest too: count them against the depth limit
                for (int i = 0; i < n; i++) skip(t);
                exitCompound();
            }
            case Nbt.COMPOUND -> {
                enterCompound();
                for (int t; (t = nextField()) != Nbt.END; ) skip(t);
                exitCompound();
            }
            default -> throw new NbtException("cannot skip tag type " + type);
        }
    }

    private void advance(long n) {
        need(n);
        b.position(b.position() + (int) n);
    }

    private int u8() {
        need(1);
        return b.get() & 0xFF;
    }

    private int u16() {
        need(2);
        return b.getShort() & 0xFFFF;
    }

    private void need(long n) {
        if (n > b.remaining()) throw new NbtException("truncated NBT: need " + n + " bytes, " + b.remaining() + " left");
    }

    /** Decode Java's modified UTF-8 (NBT strings): like UTF-8, but NUL is 0xC0 0x80 and supplementary chars are surrogate pairs. */
    private String decodeUtf(int off, int len) {
        boolean ascii = true;
        for (int i = 0; i < len; i++) if (b.get(off + i) < 0) { ascii = false; break; }
        if (ascii) {
            byte[] raw = new byte[len];
            b.get(off, raw);
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
        char[] out = new char[len];
        int n = 0;
        for (int i = 0; i < len; ) {
            int c = b.get(off + i) & 0xFF;
            if (c < 0x80) {
                out[n++] = (char) c;
                i++;
            } else if ((c & 0xE0) == 0xC0) {
                if (i + 1 >= len) throw new NbtException("bad modified UTF-8");
                out[n++] = (char) (((c & 0x1F) << 6) | (b.get(off + i + 1) & 0x3F));
                i += 2;
            } else if ((c & 0xF0) == 0xE0) {
                if (i + 2 >= len) throw new NbtException("bad modified UTF-8");
                out[n++] = (char) (((c & 0x0F) << 12) | ((b.get(off + i + 1) & 0x3F) << 6) | (b.get(off + i + 2) & 0x3F));
                i += 3;
            } else {
                throw new NbtException("bad modified UTF-8 lead byte " + c);
            }
        }
        return new String(out, 0, n);
    }

    /** Thrown for malformed NBT. */
    public static final class NbtException extends RuntimeException {
        public NbtException(String message) {
            super(message);
        }
    }
}
