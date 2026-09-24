package dev.mulcor.core.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ServerExplosion parity: block selection by vanilla's ray algorithm (checked against an independent
 * re-implementation of the spec, across a region border), exact knockback, exposure occlusion, TNT chain priming.
 */
class ExplosionParityTest {
    private static EngineConfig cfg() {
        return EngineConfig.builder().world(16, 16).cellChunks(2).workers(4).maxRegions(64).initialRegions(16)
                .regionEntityCapacity(4096).maxEntities(8192).inboxCapacity(8192).ingressCapacity(8192)
                .rebalanceInterval(0).sampleCapacity(1024).pillarDensity(0).build();
    }

    private static Region regionAt(Engine e, double x, double z) {
        return e.world.regions[e.world.ownerOfBlock((int) Math.floor(x), (int) Math.floor(z))];
    }

    /** The spec, written independently of Explosion.applyOwned: which positions does a blast select? */
    private static Set<Long> reference(Engine e, double cx, double cy, double cz, float power, long seed) {
        Set<Long> out = new HashSet<>();
        var b = e.world.blocks;
        int n = 0;
        for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) for (int l = 0; l < 16; l++) {
            if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
            double d0 = (float) j / 15.0F * 2.0F - 1.0F, d1 = (float) k / 15.0F * 2.0F - 1.0F, d2 = (float) l / 15.0F * 2.0F - 1.0F;
            double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
            d0 /= d3;
            d1 /= d3;
            d2 /= d3;
            float f = power * (0.7F + Explosion.nextFloat(seed, n++) * 0.6F);
            double x = cx, y = cy, z = cz;
            for (; f > 0.0F; f -= 0.22500001F) {
                int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                if (!b.inBounds(bx, by, bz)) break;
                int st = b.get(bx, by, bz);
                if (st != Blocks.AIR) {
                    f -= (Explosion.resistance(st) + 0.3F) * 0.3F;
                    if (f > 0.0F) out.add(dev.mulcor.memory.ScheduledTicks.pack(bx, by, bz));
                }
                x += d0 * (double) 0.3F;
                y += d1 * (double) 0.3F;
                z += d2 * (double) 0.3F;
            }
        }
        return out;
    }

    @Test
    void blastAcrossARegionBorderDestroysExactlyTheSpecifiedBlocks() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            // Mixed terrain around x = 64 (a region border): stone, dirt, sand, bedrock and a wall.
            for (int x = 52; x <= 76; x++) for (int z = 20; z <= 44; z++) {
                for (int dy = 0; dy < 6; dy++) w.blocks.set(x, y + dy, z, Blocks.AIR);
                if ((x + z) % 7 == 0) w.blocks.set(x, y, z, Blocks.STONE);
                if ((x * 3 + z) % 11 == 0) w.blocks.set(x, y + 1, z, Blocks.SAND);
            }
            for (int z = 28; z <= 36; z++) for (int dy = 0; dy < 3; dy++) w.blocks.set(66, y + dy, z, Blocks.STONE);
            double cx = 63.7, cy = y + 0.06125, cz = 32.3;
            Region origin = regionAt(e, cx, cz);
            assertNotEquals(origin.id, w.ownerOfBlock(64, 32));
            origin.epoch = e.epoch();
            long seed = dev.mulcor.core.Rng.mix(origin.epoch, Double.doubleToLongBits(cx) ^ Double.doubleToLongBits(cz), Double.doubleToLongBits(cy));
            Set<Long> expected = reference(e, cx, cy, cz, 4.0F, seed);
            int[][][] before = new int[40][20][40];
            for (int x = 0; x < 40; x++) for (int dy = 0; dy < 20; dy++) for (int z = 0; z < 40; z++) {
                before[x][dy][z] = w.blocks.get(44 + x, y - 8 + dy, 12 + z);
            }
            Explosion.explode(origin, cx, cy, cz, 4.0F); // between ticks: the driver owns every region
            e.tick(); // the other region handles its share one epoch later
            Set<Long> destroyed = new HashSet<>();
            for (int x = 0; x < 40; x++) for (int dy = 0; dy < 20; dy++) for (int z = 0; z < 40; z++) {
                int bx = 44 + x, by = y - 8 + dy, bz = 12 + z;
                if (before[x][dy][z] != Blocks.AIR && w.blocks.get(bx, by, bz) == Blocks.AIR
                        && before[x][dy][z] != Blocks.SAND) { // sand may have fallen away, not been blasted
                    destroyed.add(dev.mulcor.memory.ScheduledTicks.pack(bx, by, bz));
                }
            }
            expected.removeIf(p -> w.blocks.get(dev.mulcor.memory.ScheduledTicks.x(p), dev.mulcor.memory.ScheduledTicks.y(p),
                    dev.mulcor.memory.ScheduledTicks.z(p)) == Blocks.AIR && false);
            Set<Long> expectedNonSand = new HashSet<>();
            for (long p : expected) {
                int bx = dev.mulcor.memory.ScheduledTicks.x(p), by = dev.mulcor.memory.ScheduledTicks.y(p), bz = dev.mulcor.memory.ScheduledTicks.z(p);
                if (before[bx - 44][by - y + 8][bz - 12] != Blocks.SAND) expectedNonSand.add(p);
            }
            assertFalse(expectedNonSand.isEmpty());
            assertEquals(expectedNonSand, destroyed, "both regions together destroyed exactly the spec's selection");
            boolean acrossBorder = expectedNonSand.stream().anyMatch(p -> dev.mulcor.memory.ScheduledTicks.x(p) >= 64);
            assertTrue(acrossBorder, "the selection reaches into the neighbouring region");
            for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) assertEquals(Blocks.BEDROCK, w.blocks.get(44 + x, w.cfg.minY(), 12 + z));
        }
    }

    /**
     * hurtEntities for a fully exposed zombie: velocity += normalize(x - cx, eyeY - cy, z - cz) * (1 - d0), with
     * {@code d0 = |feet - c| / (4F * 2F)}. A zombie behind 3 blocks of stone (every sample ray blocked) gets nothing.
     */
    @Test
    void exposedZombieGetsExactlyVanillasKnockbackAndAHiddenOneNone() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            e.setAiEnabled(false);
            for (int x = 90; x <= 120; x++) for (int z = 90; z <= 120; z++) for (int dy = 0; dy < 6; dy++) w.blocks.set(x, y + dy, z, Blocks.AIR);
            for (int x = 101; x <= 103; x++) for (int z = 100; z <= 110; z++) for (int dy = 0; dy < 4; dy++) w.blocks.set(x, y + dy, z, Blocks.STONE);
            int exposed = e.spawnBot(97, 103, Entities.IDLE), hidden = e.spawnBot(104, 105, Entities.IDLE);
            e.run(3);
            double cx = 99.5, cy = y + 0.06125, cz = 105.5;
            Region origin = regionAt(e, cx, cz);
            assertEquals(origin.id, w.ownerOfEntity(exposed));
            assertEquals(origin.id, w.ownerOfEntity(hidden));
            int s = w.directory.slot(exposed), sh = w.directory.slot(hidden);
            var t = origin.table;
            double x = t.x(s), fy = t.y(s), z = t.z(s), vx = t.vx(s), vy = t.vy(s), vz = t.vz(s);
            double hvx = t.vx(sh), hvy = t.vy(sh), hvz = t.vz(sh);
            origin.epoch = e.epoch();
            Explosion.applyOwned(origin, cx, cy, cz, 4.0F, 1L);
            double d0 = Math.sqrt((x - cx) * (x - cx) + (fy - cy) * (fy - cy) + (z - cz) * (z - cz)) / (double) (4.0F * 2.0F);
            assertTrue(d0 <= 1.0);
            double d1 = x - cx, d2 = fy + (double) 1.74F - cy, d3 = z - cz, d4 = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
            double d5 = (1.0 - d0) * (double) 1.0F * (double) 1.0F;
            assertEquals(vx + d1 / d4 * d5, t.vx(s), 0.0, "vx");
            assertEquals(vy + d2 / d4 * d5, t.vy(s), 0.0, "vy");
            assertEquals(vz + d3 / d4 * d5, t.vz(s), 0.0, "vz");
            assertEquals(hvx, t.vx(sh), 0.0, "hidden zombie: exposure 0");
            assertEquals(hvy, t.vy(sh), 0.0);
            assertEquals(hvz, t.vz(sh), 0.0);
        }
    }

    @Test
    void tntBlocksInTheBlastArePrimedWithAShortFuse() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            for (int x = 140; x <= 160; x++) for (int z = 140; z <= 160; z++) for (int dy = 0; dy < 6; dy++) w.blocks.set(x, y + dy, z, Blocks.AIR);
            w.blocks.set(152, y, 150, Blocks.TNT);
            Region origin = regionAt(e, 150.5, 150.5);
            origin.epoch = e.epoch();
            Explosion.applyOwned(origin, 150.5, y + 0.06125, 150.5, 4.0F, 99L);
            assertEquals(Blocks.AIR, w.blocks.get(152, y, 150));
            int fuses = 0;
            for (int s = 0; s < origin.table.count(); s++) {
                if (origin.table.type(s) == Entities.TNT) {
                    int fuse = origin.table.aux1(s);
                    assertTrue(fuse >= 10 && fuse <= 29, "fuse = nextInt(80 / 4) + 80 / 8: " + fuse);
                    assertEquals((double) 0.2F, origin.table.vy(s), 0.0, "PrimedTnt launch");
                    fuses++;
                }
            }
            assertEquals(1, fuses);
        }
    }
}
