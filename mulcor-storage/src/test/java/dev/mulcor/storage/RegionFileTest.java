package dev.mulcor.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Region files against Minestom's Anvil implementation (itself written against vanilla's) in both directions, plus
 * rewrites, reopening, oversized (external .mcc) chunks and deletion. Set {@code -Dmulcor.anvilWorld=<save dir>}
 * to also read every chunk of a real vanilla world.
 */
class RegionFileTest {
    @TempDir
    Path dir;

    static CompoundBinaryTag chunk(int cx, int cz, int payload, long seed) {
        var rnd = new SplittableRandom(seed);
        long[] data = new long[payload];
        for (int i = 0; i < data.length; i++) data[i] = rnd.nextLong();
        return CompoundBinaryTag.builder().putInt("DataVersion", 4903).putInt("xPos", cx).putInt("zPos", cz)
                .putString("Status", "minecraft:full").putLongArray("noise", data).build();
    }

    static ByteBuffer nbt(CompoundBinaryTag tag) throws Exception {
        var out = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, out);
        return ByteBuffer.wrap(out.toByteArray());
    }

    static CompoundBinaryTag parse(ByteBuffer buf) {
        var r = new NbtReader().reset(buf);
        r.beginRoot();
        r.enterCompound();
        var cb = CompoundBinaryTag.builder();
        for (int t; (t = r.nextField()) != Nbt.END; ) cb.put(r.name(), NbtCodecTest.read(r, t));
        return cb.build();
    }

    /** Minestom's package-private RegionFile, via reflection (test oracle only). */
    static final class Oracle implements AutoCloseable {
        final Object file;
        final Method read, write, close;

        Oracle(Path mca) throws Exception {
            Class<?> c = Class.forName("net.minestom.server.instance.anvil.RegionFile");
            Constructor<?> k = c.getDeclaredConstructor(Path.class);
            k.setAccessible(true);
            file = k.newInstance(mca);
            read = c.getDeclaredMethod("readChunkData", int.class, int.class);
            write = c.getDeclaredMethod("writeChunkData", int.class, int.class, CompoundBinaryTag.class);
            close = c.getDeclaredMethod("close");
            for (Method m : new Method[] {read, write, close}) m.setAccessible(true);
        }

        CompoundBinaryTag read(int x, int z) throws Exception { return (CompoundBinaryTag) read.invoke(file, x, z); }
        void write(int x, int z, CompoundBinaryTag t) throws Exception { write.invoke(file, x, z, t); }
        @Override public void close() throws Exception { close.invoke(file); }
    }

    @Test
    void readsWhatMinestomWritesAndViceVersa() throws Exception {
        Path mca = dir.resolve(RegionFile.fileName(-1, 2));
        try (var o = new Oracle(mca)) {
            for (int i = 0; i < 40; i++) o.write(-32 + i % 32, 64 + i / 32, chunk(-32 + i % 32, 64 + i / 32, 50 + i * 37, i));
        }
        try (var r = new RegionFile(dir, -1, 2)) {
            for (int i = 0; i < 40; i++) {
                int x = -32 + i % 32, z = 64 + i / 32;
                assertTrue(r.has(x, z));
                assertEquals(chunk(x, z, 50 + i * 37, i), parse(r.read(x, z)), "chunk " + x + "," + z);
            }
            assertNull(r.read(-1, 95), "absent chunk");
            // Now we write (overwriting half with bigger payloads) and Minestom reads.
            for (int i = 0; i < 40; i += 2) {
                int x = -32 + i % 32, z = 64 + i / 32;
                r.write(x, z, nbt(chunk(x, z, 3000 + i, 100 + i)));
            }
        }
        try (var o = new Oracle(mca)) {
            for (int i = 0; i < 40; i++) {
                int x = -32 + i % 32, z = 64 + i / 32;
                var expect = i % 2 == 0 ? chunk(x, z, 3000 + i, 100 + i) : chunk(x, z, 50 + i * 37, i);
                assertEquals(expect, o.read(x, z), "chunk " + x + "," + z);
            }
        }
    }

    @Test
    void rewritesReuseSpaceAndSurviveReopen() throws Exception {
        var rnd = new SplittableRandom(7);
        Map<Long, CompoundBinaryTag> expected = new HashMap<>();
        try (var r = new RegionFile(dir, 0, 0)) {
            for (int round = 0; round < 400; round++) {
                int x = rnd.nextInt(32), z = rnd.nextInt(32);
                var tag = chunk(x, z, rnd.nextInt(2000), round); // random sizes: grows and shrinks
                r.write(x, z, nbt(tag));
                expected.put(((long) x << 32) | z, tag);
            }
        }
        long size = Files.size(dir.resolve(RegionFile.fileName(0, 0)));
        try (var r = new RegionFile(dir, 0, 0)) {
            for (var e : expected.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
                assertEquals(e.getValue(), parse(r.read(x, z)));
                assertTrue(r.timestamp(x, z) > 0);
            }
        }
        // Freed sectors are reused: the file stays far below "every write appended".
        assertTrue(size < 400L * 20 * RegionFile.SECTOR, "file size " + size);
    }

    @Test
    void oversizedChunksGoToAnExternalFile() throws Exception {
        var big = chunk(3, 4, 200_000, 1); // 1.6 MB of random longs: > 255 sectors even compressed
        try (var r = new RegionFile(dir, 0, 0)) {
            r.write(3, 4, nbt(big));
            assertTrue(Files.exists(dir.resolve("c.3.4.mcc")));
            assertEquals(big, parse(r.read(3, 4)));
        }
        // (Minestom's RegionFile does not implement vanilla's external .mcc chunks, so it cannot be the oracle here.)
        try (var r = new RegionFile(dir, 0, 0)) {
            var small = chunk(3, 4, 10, 2);
            r.write(3, 4, nbt(small));
            assertFalse(Files.exists(dir.resolve("c.3.4.mcc")), "shrinking back removes the external file");
            assertEquals(small, parse(r.read(3, 4)));
            r.delete(3, 4);
            assertFalse(r.has(3, 4));
            assertNull(r.read(3, 4));
        }
    }

    @Test
    void corruptHeaderEntriesAreDroppedNotFollowed() throws Exception {
        try (var r = new RegionFile(dir, 0, 0)) {
            r.write(1, 1, nbt(chunk(1, 1, 10, 1)));
        }
        Path mca = dir.resolve(RegionFile.fileName(0, 0));
        try (var ch = java.nio.channels.FileChannel.open(mca, java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.allocate(4).putInt(0, 9999 << 8 | 3), 4L * (2 + 2 * 32)); // chunk (2,2) far past EOF
        }
        try (var r = new RegionFile(dir, 0, 0)) {
            assertFalse(r.has(2, 2), "out-of-file entry dropped, as vanilla does");
            assertEquals(chunk(1, 1, 10, 1), parse(r.read(1, 1)));
        }
    }

    /** Opt-in: read every chunk of a real vanilla world ({@code -Dmulcor.anvilWorld=/path/to/save}). */
    @Test
    void readsARealVanillaWorld() throws Exception {
        String world = System.getProperty("mulcor.anvilWorld");
        Assumptions.assumeTrue(world != null, "set -Dmulcor.anvilWorld to run");
        Path region = Path.of(world, "region");
        int chunks = 0;
        try (var files = Files.list(region)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".mca")).toList()) {
                String[] parts = f.getFileName().toString().split("\\.");
                try (var r = new RegionFile(region, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))) {
                    for (int i = 0; i < 1024; i++) {
                        int x = (Integer.parseInt(parts[1]) << 5) + (i & 31), z = (Integer.parseInt(parts[2]) << 5) + (i >> 5);
                        ByteBuffer b = r.read(x, z);
                        if (b == null) continue;
                        var nr = new NbtReader().reset(b);
                        nr.beginRoot();
                        nr.skip(Nbt.COMPOUND);
                        assertEquals(0, b.remaining(), f + " chunk " + x + "," + z);
                        chunks++;
                    }
                }
            }
        }
        System.out.printf("read %d chunks of %s%n", chunks, world);
        assertTrue(chunks > 0);
    }
}
