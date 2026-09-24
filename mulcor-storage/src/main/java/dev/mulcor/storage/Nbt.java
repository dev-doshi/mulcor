package dev.mulcor.storage;

/** NBT tag type ids (the Java Edition binary format). */
public final class Nbt {
    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Vanilla's nesting limit ({@code NbtAccounter}); deeper input is rejected. */
    public static final int MAX_DEPTH = 512;

    private Nbt() {}
}
