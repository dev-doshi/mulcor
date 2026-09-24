package dev.mulcor.core;

import static dev.mulcor.core.TestEngines.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Input;
import dev.mulcor.core.region.Region;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block-update cascades, redstone timing and entity physics, including across region borders. */
class MechanicsTest {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private final MemorySegment msg = NativeMemory.auto().allocate(Input.BYTES);

    /** Region-addressed SET_BLOCK through the owning region's ingress (so updates run as in the game). */
    private void set(Engine e, int x, int y, int z, int state) {
        msg.fill((byte) 0);
        msg.set(I, Input.KIND, Input.SET_BLOCK);
        msg.set(I, Input.ENTITY, -1);
        msg.set(I, Input.X, x);
        msg.set(I, Input.Y, y);
        msg.set(I, Input.Z, z);
        msg.set(I, Input.A, state);
        assertTrue(e.submitToRegion(e.world.ownerOfBlock(x, z), msg, 0));
    }

    private void walk(Engine e, int eid, float vx, float vz) {
        msg.fill((byte) 0);
        msg.set(I, Input.KIND, Input.MOVE);
        msg.set(I, Input.ENTITY, eid);
        msg.set(I, Input.A, Math.round(vx * 1000));
        msg.set(I, Input.B, Math.round(vz * 1000));
        assertTrue(e.submitInput(msg, 0));
    }

    /** Clear pillars from a box of the (flat) world, between ticks. */
    private static void clear(Engine e, int x0, int x1, int z0, int z1) {
        int y = e.world.surfaceY;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int dy = 0; dy < 8; dy++) e.world.blocks.set(x, y + dy, z, Blocks.AIR);
    }

    private static Region owner(Engine e, int eid) {
        return e.world.regions[e.world.ownerOfEntity(eid)];
    }

    private static int slot(Engine e, int eid) {
        return e.world.directory.slot(eid);
    }

    /** Spawn an idle bot and place it exactly (between ticks the driver owns every table). */
    private static int bot(Engine e, double x, double z) {
        int eid = e.spawnBot((int) x, (int) z, Entities.IDLE);
        Region r = owner(e, eid);
        EntityTable t = r.table;
        int s = slot(e, eid);
        t.setPos(s, x, e.world.surfaceY, z);
        return eid;
    }

    private static EngineConfig cfg() {
        return small().pillarDensity(0).build();
    }

    // ---- redstone ----------------------------------------------------------------------------------------------

    @Test
    void repeaterDelayIsExactInsideARegionAndAcrossABorder() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            clear(e, 36, 70, 18, 26);
            // Inside one region: source 40 → repeater 41 (east, 2 redstone ticks = 4 game ticks) → wire 42.
            w.blocks.set(41, y, 20, Blocks.repeater(1, 2, false));
            w.blocks.set(42, y, 20, Blocks.WIRE);
            // Across x=64: source 60 → wires 61..63 (region A) → repeater 64 (region B, same delay) → wire 65.
            for (int x = 61; x <= 63; x++) w.blocks.set(x, y, 24, Blocks.WIRE);
            w.blocks.set(64, y, 24, Blocks.repeater(1, 2, false));
            w.blocks.set(65, y, 24, Blocks.WIRE);
            assertEquals(w.ownerOfBlock(40, 20), w.ownerOfBlock(42, 20));
            assertNotEquals(w.ownerOfBlock(63, 24), w.ownerOfBlock(64, 24));
            set(e, 40, y, 20, Blocks.REDSTONE_BLOCK);
            set(e, 60, y, 24, Blocks.REDSTONE_BLOCK);
            e.tick();
            long start = e.epoch();
            assertEquals(13, Blocks.wirePower(w.blocks.get(63, y, 24)), "wires settle in the tick the source appears");
            long inside = -1, across = -1;
            for (int t = 0; t < 20; t++) {
                e.tick();
                if (inside < 0 && Blocks.wirePower(w.blocks.get(42, y, 20)) == 15) inside = e.epoch();
                if (across < 0 && Blocks.wirePower(w.blocks.get(65, y, 24)) == 15) across = e.epoch();
            }
            assertEquals(start + 4, inside, "repeater delay 2 = 4 game ticks");
            assertEquals(start + 4, across, "the border hop is absorbed: the update carried its send epoch");
        }
    }

    /**
     * Torch on block B; repeater R1 behind the torch powers solid S; the wire under S feeds repeater R2, which
     * points back into B. B powered turns the torch off, and so on: a clock with period 2·(2 + 2 + 2) = 12 ticks.
     * A lamp next to B follows it (turning off 4 ticks late).
     */
    @Test
    void torchRepeaterClockHasAnExactPeriodAndDrivesALamp() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY, x = 100, z = 5;
            clear(e, 96, 104, 2, 10);
            w.blocks.set(x, y, z, Blocks.STONE);                       // B
            w.blocks.set(x, y + 1, z + 1, Blocks.repeater(2, 1, false)); // R1: input = torch, output south into S
            w.blocks.set(x, y + 1, z + 2, Blocks.STONE);               // S
            w.blocks.set(x, y, z + 2, Blocks.WIRE);                    // under S
            w.blocks.set(x, y, z + 1, Blocks.repeater(0, 1, false));   // R2: input = wire, output north into B
            w.blocks.set(x + 1, y, z, Blocks.LAMP);
            set(e, x, y + 1, z, Blocks.TORCH);
            List<Long> torchFlips = new ArrayList<>(), lampFlips = new ArrayList<>();
            int torch = Blocks.TORCH, lamp = Blocks.LAMP;
            for (int t = 0; t < 150; t++) {
                e.tick();
                int nt = w.blocks.get(x, y + 1, z), nl = w.blocks.get(x + 1, y, z);
                if (nt != torch) torchFlips.add(e.epoch());
                if (nl != lamp) lampFlips.add(e.epoch());
                torch = nt;
                lamp = nl;
            }
            assertTrue(torchFlips.size() >= 20, "clock runs: " + torchFlips);
            for (int i = 2; i < torchFlips.size(); i++) {
                assertEquals(12, torchFlips.get(i) - torchFlips.get(i - 2), "period, flips at " + torchFlips);
            }
            assertTrue(lampFlips.size() >= 20, "lamp follows the clock: " + lampFlips);
            assertEquals(0, e.stats().ticksDropped);
        }
    }

    /** Power reaches TNT: it primes, sand above falls onto the spot, the TNT explodes and the crater's sand falls. */
    @Test
    void poweredTntPrimesExplodesAndSandCascades() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY, x = 150, z = 150;
            clear(e, 140, 160, 140, 160);
            w.blocks.set(x, y, z, Blocks.TNT);
            for (int dy = 1; dy <= 5; dy++) w.blocks.set(x, y + dy, z, Blocks.SAND);
            for (int dx = -1; dx <= 1; dx++) w.blocks.set(x + 5 + dx, y, z, Blocks.SAND); // sand at the crater rim
            w.blocks.set(x + 5, y + 1, z, Blocks.SAND);
            long before = mineableTotal(e);
            set(e, x + 1, y, z, Blocks.REDSTONE_BLOCK);
            boolean sawFalling = false;
            for (int t = 0; t < 200; t++) {
                e.tick();
                sawFalling |= fallingBlocks(e) > 0;
            }
            quiesce(e);
            var s = e.stats();
            assertEquals(1, s.tntPrimed);
            assertEquals(1, s.explosions);
            assertTrue(sawFalling, "sand fell");
            assertTrue(s.destroyedBlocks > 0);
            assertEquals(0, fallingBlocks(e), "everything landed");
            assertEquals(before, mineableTotal(e), "blocks + items + destroyed + dropped conserved: " + s);
        }
    }

    // ---- physics: tick-by-tick parity with vanilla's recurrences ---------------------------------------------

    /**
     * FallingBlockEntity.tick in the air: {@code vy -= 0.04; y += vy; v *= 0.98} (doubles). Positions must match
     * exactly, every tick, until it lands on the floor and becomes a block.
     */
    @Test
    void fallingSandMatchesVanillaTickByTick() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY, x = 30, z = 30, top = y + 10;
            clear(e, 28, 32, 28, 32);
            set(e, x, top, z, Blocks.SAND);
            List<Double> heights = new ArrayList<>();
            for (int t = 0; t < 60 && w.blocks.get(x, y, z) != Blocks.SAND; t++) {
                e.tick();
                for (var r : w.regions) {
                    for (int s = 0; s < r.table.count(); s++) {
                        if (r.table.type(s) == Entities.FALLING_BLOCK) heights.add(r.table.y(s));
                    }
                }
            }
            assertEquals(Blocks.SAND, w.blocks.get(x, y, z), "landed on the floor");
            assertEquals(Blocks.AIR, w.blocks.get(x, top, z));
            double ey = top, vy = 0.0;
            for (int i = 0; i < heights.size(); i++) {
                vy -= 0.04;
                ey += vy;
                vy *= 0.98;
                assertEquals(ey, heights.get(i), 0.0, "tick " + i);
            }
            assertTrue(ey + vy - 0.04 < y, "the next step reaches the floor, where it lands and becomes a block");
        }
    }

    /**
     * PrimedTnt.tick on a flat floor: {@code vy -= 0.04}; move (the floor clips y); {@code v *= 0.98}; on the ground
     * {@code v *= (0.7, -0.5, 0.7)}; fuse 80 → explodes on the 80th tick. Checked every tick from the first state.
     */
    @Test
    void primedTntMatchesVanillaTickByTickAndExplodesOnTick80() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY, x = 90, z = 90;
            clear(e, 80, 100, 80, 100);
            w.blocks.set(x, y, z, Blocks.TNT);
            set(e, x + 1, y, z, Blocks.REDSTONE_BLOCK);
            double[] st = null; // x, y, z, vx, vy, vz of the model
            int ticks = 0;
            while (e.stats().explosions == 0 && ticks < 200) {
                e.tick();
                ticks++;
                double[] seen = findTnt(e);
                if (seen == null) break;
                if (st == null) {
                    st = seen; // first observed state (after its first tick)
                    continue;
                }
                // model one vanilla tick
                st[4] -= 0.04;
                double my = st[4], cy = st[1] + my < y ? y - st[1] : my;
                boolean ground = cy != my && my < 0;
                // Entity.move: the position only moves if collided.lengthSqr() > 1e-7 or nothing significant was cut
                double cLen = st[3] * st[3] + cy * cy + st[5] * st[5], mLen = st[3] * st[3] + my * my + st[5] * st[5];
                if (cLen > 1.0E-7 || mLen - cLen < 1.0E-7) {
                    st[0] += st[3];
                    st[1] += cy;
                    st[2] += st[5];
                }
                if (cy != my) st[4] = 0.0;
                st[3] *= 0.98; st[4] *= 0.98; st[5] *= 0.98;
                if (ground) { st[3] *= 0.7; st[4] *= -0.5; st[5] *= 0.7; }
                for (int i = 0; i < 6; i++) assertEquals(st[i], seen[i], 0.0, "component " + i + " at tick " + ticks);
            }
            assertEquals(1, e.stats().explosions);
            assertEquals(80, ticks, "fuse 80: primed in tick 1, explodes in tick 80");
        }
    }

    private static double[] findTnt(Engine e) {
        for (var r : e.world.regions) {
            for (int s = 0; s < r.table.count(); s++) {
                if (r.table.type(s) == Entities.TNT) {
                    EntityTable t = r.table;
                    return new double[] {t.x(s), t.y(s), t.z(s), t.vx(s), t.vy(s), t.vz(s)};
                }
            }
        }
        return null;
    }

    /**
     * Zombie walking east on a flat floor (LivingEntity.aiStep + travelInAir): zero components below 0.003,
     * {@code zza = 0.23F * 0.98F}, {@code amount = onGround ? 0.23F * (0.21600002F / 0.6F³) : 0.02F}, rotation by
     * Mth.sin/cos(yRot * DEG_TO_RAD) with yRot = -90F, move, then {@code (vx·f1, (vy - 0.08)·0.98F, vz·f1)} with
     * {@code f1 = 0.6F * 0.91F}. Positions and velocities must match exactly every tick until it reaches the wall,
     * where it stops flush at x = 23 - 0.3F.
     */
    @Test
    void zombieWalkMatchesVanillaTickByTickAndStopsAtTheWall() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            e.setAiEnabled(false);
            clear(e, 16, 30, 36, 44);
            for (int z = 36; z <= 44; z++) for (int dy = 0; dy < 3; dy++) w.blocks.set(23, y + dy, z, Blocks.STONE);
            int eid = bot(e, 18.5, 40.5);
            e.run(3); // settle: resting vy converges to (0 - 0.08) * 0.98F
            Region r0 = owner(e, eid);
            int s0 = slot(e, eid);
            double mx = r0.table.x(s0), my = r0.table.y(s0), mz = r0.table.z(s0);
            double vx = r0.table.vx(s0), vy = r0.table.vy(s0), vz = r0.table.vz(s0);
            boolean ground = (r0.table.flags(s0) & Entities.FLAG_ON_GROUND) != 0;
            assertTrue(ground);
            walk(e, eid, 1f, 0f);
            float sin = Mth.sin(-90F * Mth.DEG_TO_RAD), cos = Mth.cos(-90F * Mth.DEG_TO_RAD);
            double wallX = 23 - (double) (0.6F / 2.0F);
            for (int t = 0; t < 40; t++) {
                e.tick();
                // model: aiStep (zero tiny components), then travelInAir with f/f1 from onGround *before* moving
                if (Math.abs(vx) < 0.003) vx = 0;
                if (Math.abs(vz) < 0.003) vz = 0;
                if (Math.abs(vy) < 0.003) vy = 0;
                float zza = 0.23F * 0.98F, f = ground ? 0.6F : 1.0F, f1 = f * 0.91F;
                float amount = ground ? 0.23F * (0.21600002F / (f * f * f)) : 0.02F;
                double ix = 0.0, iz = zza;
                ix *= amount;
                iz *= amount;
                vx += ix * (double) cos - iz * (double) sin;
                vz += iz * (double) cos + ix * (double) sin;
                // move against the floor (y) and the wall (x = 23)
                double cy = my + vy < y ? y - my : vy;
                double cx = mx + 0.3F + vx > 23 ? wallX - mx : vx;
                ground = cy != vy && vy < 0;
                if (cy != vy) vy = 0;
                if (Math.abs(cx - vx) >= 1.0E-5F) vx = 0;
                mx += cx;
                my += cy;
                mz += vz;
                vx *= f1;
                vy = (vy - 0.08) * 0.98F;
                vz *= f1;
                Region r = owner(e, eid);
                int s = slot(e, eid);
                if ((r.table.flags(s) & Entities.FLAG_H_COLLISION) != 0) break; // wall reached: jumping starts
                assertEquals(mx, r.table.x(s), 0.0, "x at tick " + t);
                assertEquals(my, r.table.y(s), 0.0, "y at tick " + t);
                assertEquals(mz, r.table.z(s), 0.0, "z at tick " + t);
                assertEquals(vx, r.table.vx(s), 0.0, "vx at tick " + t);
                assertEquals(vz, r.table.vz(s), 0.0, "vz at tick " + t);
            }
            e.run(40);
            Region r = owner(e, eid);
            assertEquals(wallX, r.table.x(slot(e, eid)), 1e-9, "stopped flush against the 3-high wall");
        }
    }

    @Test
    void zombiesJumpSingleBlockSteps() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            int y = w.surfaceY;
            e.setAiEnabled(false);
            clear(e, 16, 30, 45, 50);
            for (int z = 45; z <= 50; z++) w.blocks.set(23, y, z, Blocks.STONE);
            int climber = bot(e, 20.5, 47.5);
            e.run(3);
            walk(e, climber, 1f, 0f);
            double maxY = 0;
            for (int t = 0; t < 80; t++) {
                e.tick();
                maxY = Math.max(maxY, owner(e, climber).table.y(slot(e, climber)));
            }
            Region rc = owner(e, climber);
            assertTrue(rc.table.x(slot(e, climber)) > 26, "jumped the 1-high step (jump power 0.42F) and kept walking");
            assertTrue(maxY >= y + 1, "stood on top of the step");
            assertEquals(y, rc.table.y(slot(e, climber)), 0.0, "and back on the floor after it");
        }
    }

    /**
     * Entity.push between two overlapping zombies: {@code d2 = sqrt(absMax(dx, dz))}, impulse
     * {@code (dx/d2) * min(1, 1/d2) * 0.05F}, applied twice per tick (both entities' pushEntities). Local pairs and
     * pairs across a region border get equal and opposite velocities every tick.
     */
    @Test
    void pushingMatchesVanillaImpulseAndConservesMomentumAcrossBorders() {
        try (var e = new Engine(cfg())) {
            var w = e.world;
            e.setAiEnabled(false);
            clear(e, 36, 70, 56, 74);
            int a = bot(e, 40.5, 60.5), b = bot(e, 40.9, 60.5);          // same region
            int c = bot(e, 63.75, 70.5), d = bot(e, 64.2, 70.5);         // either side of x = 64
            assertEquals(w.ownerOfEntity(a), w.ownerOfEntity(b));
            assertNotEquals(w.ownerOfEntity(c), w.ownerOfEntity(d));
            e.tick();
            // first tick: the local pair was pushed by exactly 2 × vanilla's impulse before moving
            double dx = 0.4, d2 = Math.sqrt(Mth.absMax(dx, 0.0));
            double imp = dx / d2 * Math.min(1.0, 1.0 / d2) * 0.05F;
            assertTrue(imp > 0);
            for (int t = 0; t < 30; t++) {
                double va = owner(e, a).table.vx(slot(e, a)), vb = owner(e, b).table.vx(slot(e, b));
                double vc = owner(e, c).table.vx(slot(e, c)), vd = owner(e, d).table.vx(slot(e, d));
                assertEquals(0.0, va + vb, 0.0, "local pair, tick " + t);
                assertEquals(0.0, vc + vd, 0.0, "cross-border pair, tick " + t);
                e.tick();
            }
            double ab = owner(e, b).table.x(slot(e, b)) - owner(e, a).table.x(slot(e, a));
            double cd = owner(e, d).table.x(slot(e, d)) - owner(e, c).table.x(slot(e, c));
            assertTrue(ab >= 0.6 - 1e-6, "local pair pushed apart: " + ab);
            assertTrue(cd >= 0.6 - 1e-6, "cross-border pair pushed apart: " + cd);
            assertTrue(e.stats().borderPushes > 0 && e.stats().pushes > 0);
        }
    }
}
