package dev.mulcor.core;

import static dev.mulcor.core.TestEngines.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapInventory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EngineTest {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private static void input(MemorySegment m, int kind, int entity, int x, int y, int z, int a, int b, int c) {
        m.set(I, Input.KIND, kind);
        m.set(I, Input.ENTITY, entity);
        m.set(I, Input.X, x);
        m.set(I, Input.Y, y);
        m.set(I, Input.Z, z);
        m.set(I, Input.A, a);
        m.set(I, Input.B, b);
        m.set(I, Input.C, c);
    }

    @Test
    void navigatorsCrossRegionsWithoutLossOrDuplication() {
        try (var engine = new Engine(small().build())) {
            var rnd = new SplittableRandom(1);
            int spawned = 0;
            for (int i = 0; i < 1500; i++) {
                if (engine.spawnBot(rnd.nextInt(256), rnd.nextInt(256), Entities.NAVIGATOR) >= 0) spawned++;
            }
            for (int t = 0; t < 600; t++) {
                engine.tick();
                if (t % 50 == 0) assertEquals(spawned, assertDirectoryConsistent(engine), "tick " + t);
            }
            var s = engine.stats();
            assertEquals(spawned, assertDirectoryConsistent(engine));
            assertTrue(s.transfersOut > 1000, "navigators should cross region boundaries: " + s);
            assertEquals(s.transfersOut, s.transfersIn + s.pending, "every sent entity arrives: " + s);
            assertEquals(0, s.stateViolations, s.toString());
            assertEquals(0, s.transferRejects, "no stale transfer records in a healthy run");
        }
    }

    @Test
    void crossRegionMessagesArriveExactlyOneEpochLater() {
        try (var engine = new Engine(small().build())) {
            var msg = NativeMemory.auto().allocate(Input.BYTES);
            int regions = engine.activeRegions();
            for (int t = 0; t < 50; t++) {
                for (int r = 0; r < regions; r++) {
                    input(msg, Input.PROBE_EMIT, -1, 0, 0, 0, (r + 1 + t) % regions, 0, 0);
                    assertTrue(engine.submitToRegion(r, msg, 0));
                }
                engine.tick();
            }
            engine.run(3);
            long received = 0;
            for (var r : engine.world.regions) {
                for (int i = 0; i < r.probeCount(); i++) {
                    long sent = r.probe(i) >>> 32, got = r.probe(i) & 0xFFFF_FFFFL;
                    assertEquals(sent + 1, got, "probe sent in epoch " + sent + " received in " + got);
                    received++;
                }
            }
            assertEquals(50L * regions, received);
        }
    }

    /** A wire line settles within one tick inside a region, and one epoch later on the far side of a border. */
    @Test
    void redstoneSettlesInstantlyInsideARegionAndOneEpochPerBorder() {
        try (var engine = new Engine(small().build())) {
            var w = engine.world;
            int y = w.surfaceY + 1, z = 5;
            // Source at x=56; a 14-wire line x=57..70 crosses the region boundary at x=64.
            int src = 56, first = 57, last = 70;
            for (int x = src; x <= last; x++) w.blocks.set(x, y - 1, z, Blocks.STONE);
            for (int x = first; x <= last; x++) w.blocks.set(x, y, z, Blocks.WIRE);
            assertNotEquals(w.ownerOfBlock(63, z), w.ownerOfBlock(64, z), "line must cross regions");
            var msg = NativeMemory.auto().allocate(Input.BYTES);
            input(msg, Input.SET_BLOCK, -1, src, y, z, Blocks.REDSTONE_BLOCK, 0, 0);
            assertTrue(engine.submitToRegion(w.ownerOfBlock(src, z), msg, 0));
            long[] poweredAt = new long[last + 1];
            for (int t = 0; t < 40; t++) {
                engine.tick();
                for (int x = first; x <= last; x++) {
                    if (poweredAt[x] == 0 && Blocks.wirePower(w.blocks.get(x, y, z)) == 15 - (x - first)) {
                        poweredAt[x] = engine.epoch();
                    }
                }
            }
            int nearRegion = w.ownerOfBlock(first, z);
            for (int x = first; x <= last; x++) {
                assertEquals(15 - (x - first), Blocks.wirePower(w.blocks.get(x, y, z)), "power at x=" + x);
                assertTrue(poweredAt[x] > 0, "wire " + x + " never reached final power");
                long expected = poweredAt[first] + (w.ownerOfBlock(x, z) == nearRegion ? 0 : 1);
                assertEquals(expected, poweredAt[x], "x=" + x + ": same tick in the source's region, +1 epoch across x=64");
            }
        }
    }

    /**
     * 8 threads flood one chest with take/put clicks for bots living in different regions at the same moment,
     * while the engine ticks. No item may be duplicated or destroyed.
     */
    @Test
    void chestFloodFromManyRegionsConservesItems() throws Exception {
        try (var engine = new Engine(small().build())) {
            var rnd = new SplittableRandom(3);
            int[] bots = new int[400];
            for (int i = 0; i < bots.length; i++) bots[i] = engine.spawnBot(rnd.nextInt(256), rnd.nextInt(256), Entities.IDLE);
            engine.setAiEnabled(false);
            long before = mineableTotal(engine);
            var failure = new AtomicReference<Throwable>();
            var stop = new AtomicBoolean();
            var accepted = new AtomicLong();
            int producers = 8;
            var start = new CyclicBarrier(producers + 1);
            Thread[] ts = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                final long seed = p;
                ts[p] = Thread.ofPlatform().start(() -> {
                    var r = new SplittableRandom(seed);
                    var m = NativeMemory.auto().allocate(Input.BYTES);
                    try {
                        start.await();
                        while (!stop.get()) {
                            int bot = bots[r.nextInt(bots.length)];
                            int count = r.nextBoolean() ? 1 + r.nextInt(16) : -(1 + r.nextInt(16));
                            input(m, Input.CHEST, bot, 0, 0, 0, 0 /* the same chest */, r.nextInt(9), count);
                            if (engine.submitInput(m, 0)) accepted.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
            }
            start.await();
            for (int t = 0; t < 400; t++) engine.tick();
            stop.set(true);
            for (Thread t : ts) t.join();
            assertNull(failure.get());
            quiesce(engine);
            var s = engine.stats();
            assertTrue(accepted.get() > 10_000, "flood too small: " + accepted.get());
            assertEquals(before, mineableTotal(engine), "items duplicated or destroyed: " + s);
            for (int b : bots) {
                for (int slot = 0; slot < World.PLAYER_SLOTS; slot++) {
                    long word = engine.world.players.get(b, slot);
                    assertTrue(OffHeapInventory.count(word) <= OffHeapInventory.MAX_STACK);
                }
            }
            assertEquals(0, s.stateViolations);
            System.out.println("chest flood: accepted inputs=" + accepted.get() + " " + s);
        }
    }

    /** Bots in 4 different regions all dig the same block in the same epoch: exactly one gets the item. */
    @Test
    void simultaneousDigOfOneBlockYieldsOneItem() {
        try (var engine = new Engine(small().build())) {
            var w = engine.world;
            int bx = 32, bz = 32, by = w.surfaceY - 1; // corner where four cells meet
            int[] bots = {
                engine.spawnBot(30, 30, Entities.IDLE), engine.spawnBot(34, 30, Entities.IDLE),
                engine.spawnBot(30, 34, Entities.IDLE), engine.spawnBot(34, 34, Entities.IDLE)};
            engine.setAiEnabled(false);
            long before = mineableTotal(engine);
            long dirtBefore = w.countItems(Blocks.DIRT);
            var m = NativeMemory.auto().allocate(Input.BYTES);
            long placed = 0;
            for (int round = 0; round < 50; round++) {
                if (w.blocks.get(bx, by, bz) == Blocks.AIR) placed++;
                w.blocks.set(bx, by, bz, Blocks.DIRT);
                long totalBefore = mineableTotal(engine);
                for (int b : bots) {
                    input(m, Input.DIG, b, bx, by, bz, 0, 0, 0);
                    assertTrue(engine.submitInput(m, 0));
                }
                quiesce(engine);
                assertEquals(Blocks.AIR, w.blocks.get(bx, by, bz));
                assertEquals(totalBefore, mineableTotal(engine), "round " + round);
            }
            assertEquals(dirtBefore + 50, w.countItems(Blocks.DIRT), "exactly one item per broken block");
            assertEquals(before + placed, mineableTotal(engine), "only the blocks the test placed are new");
        }
    }

    @Test
    void fullBehaviourMixConservesBlocksAndItems() {
        try (var engine = new Engine(small().rebalanceInterval(20).build())) {
            var rnd = new SplittableRandom(9);
            for (int i = 0; i < 2000; i++) {
                engine.spawnBot(rnd.nextInt(256), rnd.nextInt(256), i % 4);
            }
            long before = mineableTotal(engine);
            engine.run(1000);
            quiesce(engine);
            var s = engine.stats();
            assertEquals(2000, assertDirectoryConsistent(engine) - tntCount(engine), s.toString());
            assertEquals(before, mineableTotal(engine), "world-level duplication or loss: " + s);
            assertEquals(0, s.stateViolations, s.toString());
            assertTrue(s.explosions > 0 && s.transfersOut > 0 && s.messages > 0, s.toString());
            System.out.println("behaviour mix: " + s);
        }
    }

    private static int tntCount(Engine e) {
        int n = 0;
        for (var r : e.world.regions) for (int s = 0; s < r.table.count(); s++) if (r.table.type(s) == Entities.TNT) n++;
        return n;
    }

    @Test
    void hotspotTriggersSplitsAndMergesKeepInvariants() {
        var cfg = small().rebalanceInterval(10).tickBudgetNanos(400_000).build();
        try (var engine = new Engine(cfg)) {
            var rnd = new SplittableRandom(5);
            int n = 0;
            for (int i = 0; i < 3000; i++) {
                if (engine.spawnBot(rnd.nextInt(64), rnd.nextInt(64), Entities.NAVIGATOR) >= 0) n++;
            }
            engine.run(400);
            var s = engine.stats();
            assertTrue(engine.splits > 0, "hotspot should force splits: " + s);
            engine.setAiEnabled(false);
            engine.run(200);
            assertEquals(n, assertDirectoryConsistent(engine), s.toString());
            for (int r = 0; r < cfg.maxRegions(); r++) {
                if (engine.world.partition.isActive(r)) {
                    assertTrue(engine.world.partition.compactness(r) <= cfg.maxCompactness() + 1e-9);
                }
            }
            assertEquals(0, engine.stats().stateViolations);
            System.out.println("rebalance: splits=" + engine.splits + " merges=" + engine.merges + " rehomed="
                    + engine.rehomed + " regions=" + engine.activeRegions() + " boundaryEdges="
                    + engine.world.partition.boundaryEdges());
        }
    }
}
