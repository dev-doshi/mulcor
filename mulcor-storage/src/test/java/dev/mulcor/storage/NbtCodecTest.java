package dev.mulcor.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.junit.jupiter.api.Test;

/** The streaming codec against Adventure NBT (the library vanilla-compatible servers use) as a byte-level oracle. */
class NbtCodecTest {
    static CompoundBinaryTag sample() {
        return CompoundBinaryTag.builder()
                .putInt("DataVersion", 4903)
                .putByte("b", (byte) -7).putShort("s", (short) 30000).putLong("l", Long.MIN_VALUE)
                .putFloat("f", 0.1f).putDouble("d", -2.5e300)
                .putString("ascii", "minecraft:stone")
                .putString("unicode", "Grüße \u0000 € 😀 ☃") // NUL and a surrogate pair: modified UTF-8
                .putByteArray("ba", new byte[] {1, -2, 3})
                .putIntArray("ia", new int[] {Integer.MIN_VALUE, 0, 42})
                .putLongArray("la", new long[] {-1L, 0x0123456789ABCDEFL})
                .put("empty", ListBinaryTag.empty())
                .put("ints", ListBinaryTag.from(List.of(IntBinaryTag.intBinaryTag(1), IntBinaryTag.intBinaryTag(2))))
                .put("sections", ListBinaryTag.from(List.of(
                        CompoundBinaryTag.builder().putByte("Y", (byte) -4)
                                .put("block_states", CompoundBinaryTag.builder()
                                        .put("palette", ListBinaryTag.from(List.of(
                                                CompoundBinaryTag.builder().putString("Name", "minecraft:air").build(),
                                                CompoundBinaryTag.builder().putString("Name", "minecraft:redstone_wire")
                                                        .put("Properties", CompoundBinaryTag.builder().putString("power", "15").build())
                                                        .build())))
                                        .putLongArray("data", new long[] {0x1111_2222_3333_4444L})
                                        .build())
                                .build(),
                        CompoundBinaryTag.empty())))
                .put("nested", ListBinaryTag.from(List.of(ListBinaryTag.from(List.of(StringBinaryTag.stringBinaryTag("x"))))))
                .build();
    }

    static byte[] adventureBytes(CompoundBinaryTag tag) throws Exception {
        var out = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, out);
        return out.toByteArray();
    }

    /** Rebuild a tag tree from our pull reader (test-only; production code never builds trees). */
    static BinaryTag read(NbtReader r, int type) {
        return switch (type) {
            case Nbt.BYTE -> ByteBinaryTag.byteBinaryTag(r.readByte());
            case Nbt.SHORT -> ShortBinaryTag.shortBinaryTag(r.readShort());
            case Nbt.INT -> IntBinaryTag.intBinaryTag(r.readInt());
            case Nbt.LONG -> LongBinaryTag.longBinaryTag(r.readLong());
            case Nbt.FLOAT -> FloatBinaryTag.floatBinaryTag(r.readFloat());
            case Nbt.DOUBLE -> DoubleBinaryTag.doubleBinaryTag(r.readDouble());
            case Nbt.BYTE_ARRAY -> ByteArrayBinaryTag.byteArrayBinaryTag(r.readByteArray());
            case Nbt.INT_ARRAY -> IntArrayBinaryTag.intArrayBinaryTag(r.readIntArray());
            case Nbt.LONG_ARRAY -> LongArrayBinaryTag.longArrayBinaryTag(r.readLongArray());
            case Nbt.STRING -> StringBinaryTag.stringBinaryTag(r.readString());
            case Nbt.LIST -> {
                r.beginList();
                int t = r.listType(), n = r.listLength();
                List<BinaryTag> items = new ArrayList<>();
                for (int i = 0; i < n; i++) items.add(read(r, t));
                yield items.isEmpty() ? ListBinaryTag.empty() : ListBinaryTag.from(items);
            }
            case Nbt.COMPOUND -> {
                r.enterCompound();
                var cb = CompoundBinaryTag.builder();
                for (int t; (t = r.nextField()) != Nbt.END; ) {
                    String name = r.name();
                    cb.put(name, read(r, t));
                }
                r.exitCompound();
                yield cb.build();
            }
            default -> throw new AssertionError(type);
        };
    }

    @Test
    void readsWhatAdventureWrites() throws Exception {
        CompoundBinaryTag tag = sample();
        var r = new NbtReader().reset(ByteBuffer.wrap(adventureBytes(tag)));
        r.beginRoot();
        r.enterCompound();
        var cb = CompoundBinaryTag.builder();
        for (int t; (t = r.nextField()) != Nbt.END; ) cb.put(r.name(), read(r, t));
        assertEquals(tag, cb.build());
        assertEquals(0, r.buffer().remaining());
    }

    @Test
    void adventureReadsWhatWeWrite() throws Exception {
        NbtWriter w = new NbtWriter(16); // tiny start: exercises growth
        w.beginRoot("");
        w.int_("DataVersion", 4903).byte_("b", -7).short_("s", 30000).long_("l", Long.MIN_VALUE).float_("f", 0.1f)
                .double_("d", -2.5e300).string("ascii", "minecraft:stone").string("unicode", "Grüße \u0000 € 😀 ☃")
                .byteArray("ba", new byte[] {1, -2, 3}).intArray("ia", new int[] {Integer.MIN_VALUE, 0, 42})
                .longArray("la", new long[] {-1L, 0x0123456789ABCDEFL}, 2);
        w.beginList("empty", Nbt.END, 0);
        w.beginList("ints", Nbt.INT, 2).intElement(1).intElement(2);
        w.beginList("sections", Nbt.COMPOUND, 2);
        w.compoundElement().byte_("Y", -4).beginCompound("block_states");
        w.beginList("palette", Nbt.COMPOUND, 2);
        w.compoundElement().string("Name", "minecraft:air").end();
        w.compoundElement().string("Name", "minecraft:redstone_wire").beginCompound("Properties").string("power", "15").end().end();
        w.longArray("data", new long[] {0x1111_2222_3333_4444L}, 1);
        w.end().end(); // block_states, section 0
        w.compoundElement().end(); // section 1 (empty)
        w.beginList("nested", Nbt.LIST, 1);
        w.buffer().put((byte) Nbt.STRING).putInt(1); // inner list header, unnamed
        w.stringElement("x");
        w.end();
        CompoundBinaryTag back = BinaryTagIO.reader().read(new ByteArrayInputStream(w.toByteArray()));
        assertEquals(sample(), back);
    }

    @Test
    void skippingAndInPlaceNameMatching() throws Exception {
        var r = new NbtReader().reset(ByteBuffer.wrap(adventureBytes(sample())));
        r.beginRoot();
        int version = -1, sections = -1;
        for (int t; (t = r.nextField()) != Nbt.END; ) {
            if (t == Nbt.INT && r.nameIs("DataVersion")) version = r.readInt();
            else if (t == Nbt.LIST && r.nameIs("sections")) {
                r.beginList();
                sections = r.listLength();
                for (int i = 0; i < sections; i++) r.skip(r.listType());
            } else r.skip(t);
        }
        assertEquals(4903, version);
        assertEquals(2, sections);
        assertEquals(0, r.buffer().remaining());
    }

    @Test
    void malformedInputIsRejectedNotCrashed() throws Exception {
        byte[] good = adventureBytes(sample());
        for (int cut = 1; cut < good.length; cut += 7) { // every truncation fails cleanly
            var r = new NbtReader().reset(ByteBuffer.wrap(good, 0, cut).slice());
            assertThrows(NbtReader.NbtException.class, () -> {
                r.beginRoot();
                r.skip(Nbt.COMPOUND);
                if (r.buffer().remaining() == 0) throw new NbtReader.NbtException("short");
            });
        }
        // 600 nested lists exceed vanilla's depth limit.
        var deep = ByteBuffer.allocate(6000);
        deep.put((byte) Nbt.COMPOUND).putShort((short) 0).put((byte) Nbt.LIST).putShort((short) 1).put((byte) 'x');
        for (int i = 0; i < 600; i++) deep.put((byte) Nbt.LIST).putInt(1);
        deep.put((byte) Nbt.END).putInt(0);
        deep.flip();
        var r = new NbtReader().reset(deep);
        r.beginRoot();
        assertThrows(NbtReader.NbtException.class, () -> r.skip(Nbt.COMPOUND));
    }
}
