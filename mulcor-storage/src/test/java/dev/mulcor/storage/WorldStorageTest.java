package dev.mulcor.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.registry.Registry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStorageTest {
    @TempDir
    Path dir;

    static int await(ChunkIO io, int ticket) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        int s;
        while ((s = io.status(ticket)) == ChunkIO.PENDING) {
            if (System.nanoTime() > deadline) throw new AssertionError("I/O ticket " + ticket + " never completed");
            LockSupport.parkNanos(50_000);
        }
        return s;
    }

    @Test
    void layoutsAndLevelDat() throws Exception {
        var modern = new WorldDirectory(dir.resolve("fresh"));
        assertTrue(modern.usesDimensionsLayout(), "new worlds use dimensions/");
        assertEquals(dir.resolve("fresh/dimensions/minecraft/the_nether/region"), modern.regions(WorldDirectory.NETHER));
        assertEquals(dir.resolve("fresh/players/data/" + new UUID(1, 2) + ".dat"), modern.playerData(new UUID(1, 2)));

        Files.createDirectories(dir.resolve("legacy/region"));
        var legacy = new WorldDirectory(dir.resolve("legacy"));
        assertFalse(legacy.usesDimensionsLayout());
        assertEquals(dir.resolve("legacy"), legacy.dimension(WorldDirectory.OVERWORLD));
        assertEquals(dir.resolve("legacy/DIM-1/region"), legacy.regions(WorldDirectory.NETHER));
        assertEquals(dir.resolve("legacy/DIM1"), legacy.dimension(WorldDirectory.END));

        var data = new NbtTree.Compound();
        data.put("DataVersion", Registry.DATA_VERSION);
        data.put("LevelName", "Mulcor");
        data.put("hardcore", (byte) 0);
        var spawn = new NbtTree.Compound();
        spawn.put("pos", new int[] {0, 64, 0});
        spawn.put("dimension", "minecraft:overworld");
        data.put("spawn", spawn);
        var packs = new NbtTree.ListTag(Nbt.STRING);
        packs.add("vanilla");
        data.put("Enabled", packs);
        Files.createDirectories(modern.root());
        modern.writeLevel(data);
        assertTrue(modern.readLevel().deepEquals(data));
        modern.writeLevel(data);
        assertTrue(Files.exists(modern.root().resolve("level.dat_old")), "crash-safe replace keeps the previous file");

        var rules = new NbtTree.Compound();
        rules.put("minecraft:keep_inventory", (byte) 1);
        WorldDirectory.writeSavedData(modern.savedData("game_rules"), Registry.DATA_VERSION, rules);
        assertTrue(WorldDirectory.readSavedData(modern.savedData("game_rules")).deepEquals(rules));
        assertNull(WorldDirectory.readSavedData(modern.savedData("missing")));
    }

    @Test
    void asyncSaveAndLoadAcrossShards() throws Exception {
        Path regions = dir.resolve("region");
        List<ChunkData> written = new ArrayList<>();
        try (var io = new ChunkIO(regions, 24, 4, 512)) {
            List<Integer> tickets = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                ChunkData c = ChunkCodecTest.sample(i);
                c.x = (i % 30) - 40; // spans several region files (and negative coordinates)
                c.z = (i / 30) * 7 - 20;
                written.add(c);
                int t;
                while ((t = io.save(c)) < 0) Thread.onSpinWait();
                tickets.add(t);
            }
            for (int t : tickets) {
                assertEquals(ChunkIO.DONE, await(io, t), String.valueOf(io.error(t)));
                io.release(t);
            }
        }
        // Reopen and load everything concurrently from 4 submitting threads.
        try (var io = new ChunkIO(regions, 24, 4, 512)) {
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            Thread[] submitters = new Thread[4];
            for (int k = 0; k < 4; k++) {
                int part = k;
                submitters[k] = Thread.ofPlatform().start(() -> {
                    try {
                        for (int i = part; i < written.size(); i += 4) {
                            ChunkData expect = written.get(i);
                            int t;
                            while ((t = io.load(expect.x, expect.z)) < 0) Thread.onSpinWait();
                            assertEquals(ChunkIO.DONE, await(io, t));
                            ChunkCodecTest.assertSameChunk(expect, io.result(t));
                            io.release(t);
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
            for (Thread t : submitters) t.join();
            assertTrue(failures.isEmpty(), String.valueOf(failures.peek()));
            int t = io.load(1000, 1000);
            assertEquals(ChunkIO.ABSENT, await(io, t));
            io.release(t);
        }
    }

    @Test
    void installAndSnapshotRoundTrip() {
        try (var mem = new NativeMemory()) {
            var blocks = new BlockStorage(mem, 2, 1, -64, 24, 48);
            var blockLight = new LightStorage(mem, 2, 1, -64, 24, 64, 0);
            var skyLight = new LightStorage(mem, 2, 1, -64, 24, 64, 0);
            ChunkData c = ChunkCodecTest.sample(5);
            assertTrue(ChunkInstaller.install(c, blocks, blockLight, skyLight, 1, 0));
            ChunkData out = new ChunkData();
            out.x = c.x;
            out.z = c.z;
            out.biomeNames.addAll(c.biomeNames);
            out.extra.addAll(c.extra);
            out.reset(24, -4);
            out.biomeNames.addAll(c.biomeNames);
            out.extra.addAll(c.extra);
            out.biomes = c.biomes;
            out.lastUpdate = c.lastUpdate;
            out.inhabitedTime = c.inhabitedTime;
            ChunkInstaller.snapshot(blocks, blockLight, skyLight, 1, 0, out);
            for (int y = -64; y < 320; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertEquals(c.state(x, y, z), out.state(x, y, z), "block " + x + "," + y + "," + z);
            }
            for (int li = 0; li < 26; li++) {
                if (c.blockLight[li] != null) assertArrayEquals(c.blockLight[li], out.blockLight[li], "block light " + li);
                if (c.skyLight[li] != null) assertArrayEquals(c.skyLight[li], out.skyLight[li], "sky light " + li);
            }
            // The neighbouring storage chunk stayed empty.
            assertEquals(0, blocks.get(3, 0, 3));
        }
    }

    /** Opt-in: a real 26.x world ({@code -Dmulcor.world262=<save root>}). */
    @Test
    void realWorld() throws Exception {
        String root = System.getProperty("mulcor.world262");
        Assumptions.assumeTrue(root != null, "set -Dmulcor.world262 to run");
        var world = new WorldDirectory(Path.of(root));
        assertTrue(world.usesDimensionsLayout());
        var level = world.readLevel();
        assertEquals(Registry.DATA_VERSION, level.getInt("DataVersion", -1));
        assertNotNull(level.getString("LevelName"));
        var rules = WorldDirectory.readSavedData(world.savedData("game_rules"));
        assertNotNull(rules);
        assertTrue(rules.size() > 40, "game rules: " + rules.size());
        // Round-trip level.dat and game rules through our writer.
        WorldDirectory.writeSavedData(dir.resolve("g.dat"), Registry.DATA_VERSION, rules);
        assertTrue(WorldDirectory.readSavedData(dir.resolve("g.dat")).deepEquals(rules));
        var copy = new WorldDirectory(dir.resolve("copy"));
        Files.createDirectories(copy.root());
        copy.writeLevel(level);
        assertTrue(copy.readLevel().deepEquals(level));

        // Every overworld chunk through the async shards equals a direct synchronous decode.
        Path regions = world.regions(WorldDirectory.OVERWORLD);
        int loaded = 0;
        var codec = new ChunkCodec();
        try (var io = new ChunkIO(regions, 24, 4, 256); var files = Files.list(regions)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".mca")).toList()) {
                String[] parts = f.getFileName().toString().split("\\.");
                int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                try (var rf = new RegionFile(regions, rx, rz)) {
                    for (int i = 0; i < 1024; i++) {
                        int x = (rx << 5) + (i & 31), z = (rz << 5) + (i >> 5);
                        ByteBuffer b = rf.read(x, z);
                        if (b == null) continue;
                        ChunkData direct = codec.decode(b, 24, new ChunkData());
                        int t;
                        while ((t = io.load(x, z)) < 0) Thread.onSpinWait();
                        assertEquals(ChunkIO.DONE, await(io, t));
                        ChunkCodecTest.assertSameChunk(direct, io.result(t));
                        io.release(t);
                        loaded++;
                    }
                }
            }
        }
        System.out.printf("real world %s: level %s, %d game rules, %d chunks via ChunkIO%n", root, level.getString("LevelName"),
                rules.size(), loaded);
        assertTrue(loaded > 0);
    }
}
