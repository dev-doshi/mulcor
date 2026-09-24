package dev.mulcor.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A generic NBT tree for cold-path documents (level.dat, saved-data files, player data): compounds are insertion-
 * ordered maps, lists carry their element type. Values are {@code Byte, Short, Integer, Long, Float, Double,
 * byte[], String, NbtTree.ListTag, NbtTree.Compound, int[], long[]}. Chunks never use this (they stream through
 * {@link ChunkCodec}).
 */
public final class NbtTree {
    private NbtTree() {}

    /** A TAG_Compound: field order is preserved so documents round-trip byte for byte. */
    public static final class Compound extends LinkedHashMap<String, Object> {
        public Compound getCompound(String k) { return get(k) instanceof Compound c ? c : null; }
        public ListTag getList(String k) { return get(k) instanceof ListTag l ? l : null; }
        public String getString(String k) { return get(k) instanceof String s ? s : null; }
        public int getInt(String k, int def) { return get(k) instanceof Number n ? n.intValue() : def; }
        public long getLong(String k, long def) { return get(k) instanceof Number n ? n.longValue() : def; }
        public double getDouble(String k, double def) { return get(k) instanceof Number n ? n.doubleValue() : def; }
        public boolean getBoolean(String k, boolean def) { return get(k) instanceof Number n ? n.intValue() != 0 : def; }
        public int[] getIntArray(String k) { return get(k) instanceof int[] a ? a : null; }

        /** Deep equality including array contents. */
        public boolean deepEquals(Compound o) {
            return NbtTree.deepEquals(this, o);
        }
    }

    /** A TAG_List with its element type. */
    public static final class ListTag extends ArrayList<Object> {
        public int elementType;

        public ListTag(int elementType) {
            this.elementType = elementType;
        }
    }

    // ---- reading ----

    /** Read a named root compound (file format). */
    public static Compound readRoot(ByteBuffer buf) {
        NbtReader r = new NbtReader().reset(buf);
        r.beginRoot();
        return readCompound(r);
    }

    static Compound readCompound(NbtReader r) {
        r.enterCompound();
        Compound c = new Compound();
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            String name = r.name();
            c.put(name, read(r, t));
        }
        r.exitCompound();
        return c;
    }

    static Object read(NbtReader r, int type) {
        return switch (type) {
            case Nbt.BYTE -> r.readByte();
            case Nbt.SHORT -> r.readShort();
            case Nbt.INT -> r.readInt();
            case Nbt.LONG -> r.readLong();
            case Nbt.FLOAT -> r.readFloat();
            case Nbt.DOUBLE -> r.readDouble();
            case Nbt.BYTE_ARRAY -> r.readByteArray();
            case Nbt.STRING -> r.readString();
            case Nbt.INT_ARRAY -> r.readIntArray();
            case Nbt.LONG_ARRAY -> r.readLongArray();
            case Nbt.COMPOUND -> readCompound(r);
            case Nbt.LIST -> {
                r.beginList();
                ListTag l = new ListTag(r.listType());
                int n = r.listLength();
                r.enterCompound(); // count list nesting
                for (int i = 0; i < n; i++) l.add(read(r, l.elementType));
                r.exitCompound();
                yield l;
            }
            default -> throw new NbtReader.NbtException("tag type " + type);
        };
    }

    // ---- writing ----

    public static void writeRoot(NbtWriter w, String name, Compound c) {
        w.beginRoot(name);
        writeFields(w, c);
        w.end();
    }

    private static void writeFields(NbtWriter w, Compound c) {
        for (var e : c.entrySet()) writeNamed(w, e.getKey(), e.getValue());
    }

    public static int typeOf(Object v) {
        return switch (v) {
            case Byte b -> Nbt.BYTE;
            case Short s -> Nbt.SHORT;
            case Integer i -> Nbt.INT;
            case Long l -> Nbt.LONG;
            case Float f -> Nbt.FLOAT;
            case Double d -> Nbt.DOUBLE;
            case byte[] a -> Nbt.BYTE_ARRAY;
            case String s -> Nbt.STRING;
            case ListTag l -> Nbt.LIST;
            case Compound c -> Nbt.COMPOUND;
            case int[] a -> Nbt.INT_ARRAY;
            case long[] a -> Nbt.LONG_ARRAY;
            default -> throw new IllegalArgumentException("not an NBT value: " + v.getClass());
        };
    }

    private static void writeNamed(NbtWriter w, String name, Object v) {
        switch (v) {
            case Byte b -> w.byte_(name, b);
            case Short s -> w.short_(name, s);
            case Integer i -> w.int_(name, i);
            case Long l -> w.long_(name, l);
            case Float f -> w.float_(name, f);
            case Double d -> w.double_(name, d);
            case byte[] a -> w.byteArray(name, a);
            case String s -> w.string(name, s);
            case int[] a -> w.intArray(name, a);
            case long[] a -> w.longArray(name, a, a.length);
            case Compound c -> {
                w.beginCompound(name);
                writeFields(w, c);
                w.end();
            }
            case ListTag l -> {
                w.beginList(name, l.elementType, l.size());
                for (Object o : l) writeElement(w, l.elementType, o);
            }
            default -> throw new IllegalArgumentException("not an NBT value: " + v.getClass());
        }
    }

    private static void writeElement(NbtWriter w, int type, Object v) {
        switch (type) {
            case Nbt.COMPOUND -> {
                writeFields(w, (Compound) v);
                w.end();
            }
            case Nbt.LIST -> {
                ListTag l = (ListTag) v;
                w.rawListHeader(l.elementType, l.size());
                for (Object o : l) writeElement(w, l.elementType, o);
            }
            case Nbt.STRING -> w.stringElement((String) v);
            default -> {
                // Scalars and arrays: write as a named field into a scratch writer and copy the payload.
                NbtWriter tmp = new NbtWriter(64);
                writeNamed(tmp, "", v);
                w.rawBytes(tmp.buffer().array(), 3, tmp.size() - 3); // skip type byte + empty name length
            }
        }
    }

    // ---- files (vanilla NbtIo.writeCompressed + Util.safeReplaceFile) ----

    public static Compound readGzipFile(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return readRoot(ByteBuffer.wrap(in.readAllBytes()));
        }
    }

    /**
     * Write {@code root} gzip-compressed, safely: to a temp file first, then the old file becomes {@code <name>_old}
     * and the new one takes its place (vanilla's crash-safe replace).
     */
    public static void writeGzipFile(Path file, Compound root) throws IOException {
        NbtWriter w = new NbtWriter(4096);
        writeRoot(w, "", root);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(w.buffer().array(), 0, w.size());
        }
        Path old = file.resolveSibling(file.getFileName() + "_old");
        if (Files.exists(file)) Files.move(file, old, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean deepEquals(Object a, Object b) {
        if (a instanceof Compound ca && b instanceof Compound cb) {
            if (ca.size() != cb.size()) return false;
            for (var e : ca.entrySet()) if (!cb.containsKey(e.getKey()) || !deepEquals(e.getValue(), cb.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof ListTag la && b instanceof ListTag lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!deepEquals(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return Arrays.equals(x, y);
        return Objects.equals(a, b);
    }

}
