package dev.mulcor.core.region;

import static dev.mulcor.core.region.Entities.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Rng;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.EntityTable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Vanilla's explosion algorithm ({@code ServerExplosion}), split across regions.
 *
 * <ol>
 *   <li><b>Blocks:</b> 1352 rays from the centre (the surface of a 16×16×16 grid of directions). Each starts with
 *       intensity {@code power · (0.7 + 0.6·rand)} and steps 0.3 blocks at a time, losing
 *       {@code (resistance + 0.3) · 0.3} in every non-air block and 0.225 per step. Every block a ray reaches
 *       with intensity left is collected first, then all are destroyed, so rays see the world as it was.</li>
 *   <li><b>Entities:</b> everything within {@code 2·power} of the centre is pushed away with impact
 *       {@code (1 - d/2power) · exposure}. Exposure is the fraction of sample points on the entity's box with a
 *       clear line to the centre (vanilla {@code getSeenPercent}, full-cube occluders).</li>
 *   <li>TNT blocks caught in the blast are primed with a 10–29 tick fuse (chain reactions).</li>
 * </ol>
 *
 * <b>Across regions:</b> the exploding region handles its own blocks and entities and sends an {@link Msg#EXPLOSION}
 * to every other region in reach. Each receiver re-casts the same rays (the random intensities come from a seed
 * in the message, so every region draws the same numbers) and handles only what it owns, one epoch later.
 * Blocks near the border are read with shared reads, so the regions' views can differ by one epoch.
 */
final class Explosion {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    static final float TNT_POWER = 4.0f;
    static final int CACHE_R = 8, CACHE_D = 2 * CACHE_R + 1;
    private static final int RAYS;
    private static final double[] RX, RY, RZ;

    static {
        int n = 0;
        double[] x = new double[16 * 16 * 16], y = new double[x.length], z = new double[x.length];
        for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) for (int l = 0; l < 16; l++) {
            if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
            // Computed in float, as in vanilla: (double) ((float) j / 15.0F * 2.0F - 1.0F)
            double dx = (float) j / 15.0F * 2.0F - 1.0F, dy = (float) k / 15.0F * 2.0F - 1.0F, dz = (float) l / 15.0F * 2.0F - 1.0F;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            x[n] = dx / len;
            y[n] = dy / len;
            z[n] = dz / len;
            n++;
        }
        RAYS = n;
        RX = java.util.Arrays.copyOf(x, n);
        RY = java.util.Arrays.copyOf(y, n);
        RZ = java.util.Arrays.copyOf(z, n);
    }

    private Explosion() {}

    /** {@code Block.getExplosionResistance()} of each Mulcor block (vanilla values; air is skipped by the caller). */
    static float resistance(int state) {
        return state >= 0 && state < RESISTANCE.length ? RESISTANCE[state] : 0f;
    }

    private static final float[] RESISTANCE = new float[Blocks.REPEATER_END];

    static {
        for (int st = 0; st < RESISTANCE.length; st++) {
            float v = 0f; // TNT, wire, torches, repeaters
            if (st == Blocks.BEDROCK) v = 3_600_000f;
            else if (st == Blocks.STONE || st == Blocks.REDSTONE_BLOCK) v = 6.0f;
            else if (st == Blocks.DIRT || st == Blocks.SAND) v = 0.5f;
            else if (st == Blocks.CHEST) v = 2.5f;
            else if (Blocks.isLamp(st)) v = 0.3f;
            RESISTANCE[st] = v;
        }
    }

    /**
     * Block state at (x, y, z) through the current explosion's prefetched neighbourhood when it is inside it
     * (identical to a shared read made at prefetch time), else a shared read. -1 outside the world.
     */
    private static int stateAt(Region r, int x, int y, int z) {
        int dx = x - r.blastOx, dy = y - r.blastOy, dz = z - r.blastOz;
        if (dx >= 0 && dy >= 0 && dz >= 0 && dx < CACHE_D && dy < CACHE_D && dz < CACHE_D) {
            return r.blastCache[(dx * CACHE_D + dy) * CACHE_D + dz];
        }
        BlockStorage b = r.world.blocks;
        return b.inBounds(x, y, z) ? b.getShared(x, y, z) : -1;
    }

    /** Prefetch the neighbourhood of an explosion centre (every explosion entry point calls this first). */
    private static void prefetch(Region r, double cx, double cy, double cz) {
        BlockStorage b = r.world.blocks;
        int ox = (int) Math.floor(cx) - CACHE_R, oy = (int) Math.floor(cy) - CACHE_R, oz = (int) Math.floor(cz) - CACHE_R;
        r.blastOx = ox;
        r.blastOy = oy;
        r.blastOz = oz;
        int[] cache = r.blastCache;
        for (int dx = 0; dx < CACHE_D; dx++) for (int dy = 0; dy < CACHE_D; dy++) for (int dz = 0; dz < CACHE_D; dz++) {
            int bx = ox + dx, by = oy + dy, bz = oz + dz;
            cache[(dx * CACHE_D + dy) * CACHE_D + dz] = b.inBounds(bx, by, bz) ? b.getShared(bx, by, bz) : -1;
        }
    }

    /**
     * {@code ServerExplosion.explode()}: {@code calculateExplodedPositions()}, then {@code hurtEntities()}, then
     * {@code interactWithBlocks()}.
     *
     * <p>The exploding region runs all three in its own tick, from the world as it is (blocks in neighbouring
     * cells through shared reads): it selects every position, pushes its own entities, destroys its own blocks,
     * and sends each other owner an {@link Msg#EXPLOSION_BLOCKS} list, so the selection is exactly vanilla's even
     * across borders. Neighbours in reach also get an {@link Msg#EXPLOSION} to push their entities; that happens
     * one epoch later, with exposure measured after the origin's blocks are gone (the one cross-border difference).
     */
    static void explode(Region r, double x, double y, double z, float power) {
        r.explosions++;
        long seed = Rng.mix(r.epoch, Double.doubleToLongBits(x) ^ Double.doubleToLongBits(z), Double.doubleToLongBits(y));
        select(r, x, y, z, power, seed);
        knockback(r, x, y, z, power);
        destroySelection(r, seed);
        double reach = 2.0 * power + 1.0;
        int cb = r.cfg.cellBlocks();
        int gx0 = Math.max(0, (int) Math.floor(x - reach)) / cb, gx1 = Math.min(r.world.sizeX() - 1, (int) Math.floor(x + reach)) / cb;
        int gz0 = Math.max(0, (int) Math.floor(z - reach)) / cb, gz1 = Math.min(r.world.sizeZ() - 1, (int) Math.floor(z + reach)) / cb;
        long sent = 0; // regions already told (ids < 64 in a bitmask; larger ids are told once per cell)
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                int owner = r.world.partition.regionAt(gx, gz);
                if (owner == r.id || (owner < 64 && (sent & (1L << owner)) != 0)) continue;
                if (owner < 64) sent |= 1L << owner;
                MemorySegment m = r.begin(Msg.EXPLOSION);
                m.set(ValueLayout.JAVA_DOUBLE, Msg.BODY, x);
                m.set(ValueLayout.JAVA_DOUBLE, Msg.BODY + 8, y);
                m.set(ValueLayout.JAVA_DOUBLE, Msg.BODY + 16, z);
                m.set(ValueLayout.JAVA_FLOAT, Msg.BODY + 24, power);
                r.send(owner);
            }
        }
    }

    /** An EXPLOSION message from a neighbouring region: push our entities. */
    static void receive(Region r, MemorySegment seg, long off) {
        prefetch(r, seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY), seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + 8),
                seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + 16));
        knockback(r, seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY), seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + 8),
                seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + 16), seg.get(ValueLayout.JAVA_FLOAT, off + Msg.BODY + 24));
    }

    /** An EXPLOSION_BLOCKS message: destroy the listed positions we own. */
    static void receiveBlocks(Region r, MemorySegment seg, long off) {
        int n = seg.get(I, off + Msg.A);
        long seed = seg.get(ValueLayout.JAVA_LONG, off + Msg.WORD);
        for (int i = 0; i < n; i++) {
            long pos = seg.get(ValueLayout.JAVA_LONG, off + Msg.BODY + 8L * i);
            int x = dev.mulcor.memory.ScheduledTicks.x(pos), y = dev.mulcor.memory.ScheduledTicks.y(pos), z = dev.mulcor.memory.ScheduledTicks.z(pos);
            int owner = r.world.ownerOfBlock(x, z);
            if (owner == r.id) {
                destroy(r, x, y, z, seed);
            } else { // the cell changed owner since: pass it on
                MemorySegment m = r.begin(Msg.EXPLOSION_BLOCKS);
                m.set(I, Msg.A, 1);
                m.set(ValueLayout.JAVA_LONG, Msg.WORD, seed);
                m.set(ValueLayout.JAVA_LONG, Msg.BODY, pos);
                r.send(owner);
            }
        }
    }

    /** Exploded positions (packed + 1) of the current blast, in {@link Region#blastSet}. Test hook. */
    static void applyOwned(Region r, double cx, double cy, double cz, float power, long seed) {
        select(r, cx, cy, cz, power, seed);
        knockback(r, cx, cy, cz, power);
        destroySelection(r, seed);
    }

    /**
     * {@code ServerExplosion.calculateExplodedPositions}: for each of the 1352 surface directions
     * {@code d = ((float) j / 15.0F * 2.0F - 1.0F, …)} normalized, {@code f = radius * (0.7F + nextFloat() * 0.6F)};
     * walk from the centre while {@code f > 0}: {@code pos = BlockPos.containing(x, y, z)}; stop outside the world;
     * if the block is not air: {@code f -= (resistance + 0.3F) * 0.3F}, and if still {@code f > 0} it explodes;
     * advance by {@code d * (double) 0.3F}; {@code f -= 0.22500001F}.
     */
    private static void select(Region r, double cx, double cy, double cz, float power, long seed) {
        BlockStorage b = r.world.blocks;
        long[] hit = r.blastSet;
        int mask = hit.length - 1;
        clearHits(r);
        int hits = 0;
        // Rays from one centre revisit the same blocks many times: read the neighbourhood once. A ray walks at most
        // power * 1.3 / 0.225 steps of 0.3 blocks (6.9 blocks at power 4), inside CACHE_R = 8 for power ≤ 4.5;
        // stronger blasts read directly outside the box.
        prefetch(r, cx, cy, cz);
        for (int i = 0; i < RAYS; i++) {
            float f = power * (0.7F + nextFloat(seed, i) * 0.6F);
            double x = cx, y = cy, z = cz;
            for (; f > 0.0F; f -= 0.22500001F) {
                int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                int st = stateAt(r, bx, by, bz);
                if (st < 0) break; // outside the world
                if (st != Blocks.AIR) {
                    f -= (resistance(st) + 0.3F) * 0.3F;
                    if (f > 0.0F && hits < r.blastUsed.length) {
                        long key = dev.mulcor.memory.ScheduledTicks.pack(bx, by, bz) + 1;
                        int h = (int) (Rng.mix(key) >>> 40) & mask;
                        while (hit[h] != 0 && hit[h] != key) h = (h + 1) & mask;
                        if (hit[h] == 0) {
                            hit[h] = key;
                            r.blastUsed[hits++] = h;
                        }
                    }
                }
                x += RX[i] * (double) 0.3F;
                y += RY[i] * (double) 0.3F;
                z += RZ[i] * (double) 0.3F;
            }
        }
        r.blastHits = hits;
    }

    /** Empty the hit set by clearing only the slots the previous blast used. */
    private static void clearHits(Region r) {
        for (int i = 0; i < r.blastHits; i++) r.blastSet[r.blastUsed[i]] = 0L;
        r.blastHits = 0;
    }

    /** {@code interactWithBlocks}: destroy our share of the selection now; ship each other owner its share. */
    private static void destroySelection(Region r, long seed) {
        long[] hit = r.blastSet;
        int[] owners = r.blastOwners;
        int nOwners = 0;
        for (int h = 0; h < hit.length; h++) {
            if (hit[h] == 0) continue;
            long pos = hit[h] - 1;
            int x = dev.mulcor.memory.ScheduledTicks.x(pos), y = dev.mulcor.memory.ScheduledTicks.y(pos), z = dev.mulcor.memory.ScheduledTicks.z(pos);
            int owner = r.world.ownerOfBlock(x, z);
            if (owner == r.id) {
                destroy(r, x, y, z, seed);
                continue;
            }
            boolean known = false;
            for (int i = 0; i < nOwners; i++) known |= owners[i] == owner;
            if (!known && nOwners < owners.length) owners[nOwners++] = owner;
        }
        for (int i = 0; i < nOwners; i++) {
            int owner = owners[i], n = 0;
            MemorySegment m = null;
            for (int h = 0; h < hit.length; h++) {
                if (hit[h] == 0) continue;
                long pos = hit[h] - 1;
                if (r.world.ownerOfBlock(dev.mulcor.memory.ScheduledTicks.x(pos), dev.mulcor.memory.ScheduledTicks.z(pos)) != owner) continue;
                if (m == null) {
                    m = r.begin(Msg.EXPLOSION_BLOCKS);
                    m.set(ValueLayout.JAVA_LONG, Msg.WORD, seed);
                    n = 0;
                }
                m.set(ValueLayout.JAVA_LONG, Msg.BODY + 8L * n, pos);
                if (++n == Msg.BATCH) {
                    m.set(I, Msg.A, n);
                    r.send(owner);
                    m = null;
                }
            }
            if (m != null) {
                m.set(I, Msg.A, n);
                r.send(owner);
            }
        }
    }

    /**
     * One exploded block: TNT is primed ({@code TntBlock.wasExploded}: fuse {@code nextInt(80 / 4) + 80 / 8});
     * a chest spills its contents; everything else is removed (vanilla drops it as an item with chance
     * {@code 1 / radius}; Mulcor has no item entities and counts it as destroyed). Neighbours get updates.
     */
    private static void destroy(Region r, int x, int y, int z, long seed) {
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (st == Blocks.AIR) return;
        b.set(x, y, z, Blocks.AIR);
        if (st == Blocks.TNT) {
            long hh = Rng.mix(seed, dev.mulcor.memory.ScheduledTicks.pack(x, y, z), 7);
            Sim.spawnTnt(r, x + 0.5, y, z + 0.5, 10 + Rng.bounded(hh, 20), hh);
            r.tntPrimed++;
        } else if (Blocks.isMineable(st)) {
            r.destroyedBlocks++;
        } else {
            if (st == Blocks.CHEST) r.droppedItems += r.world.destroyChestAt(x, y, z);
            r.destroyedOther++;
        }
        Redstone.blockChanged(r, x, y, z);
    }

    /** Uniform float in [0, 1) with 24 random bits, like {@code RandomSource.nextFloat()}; one per ray. */
    static float nextFloat(long seed, int i) {
        return (Rng.mix(seed + i * 0x9E3779B97F4A7C15L) >>> 40) * 0x1.0p-24f;
    }

    /**
     * {@code ServerExplosion.hurtEntities}: {@code f = radius * 2.0F}; for each entity,
     * {@code d0 = sqrt(distanceToSqr(center)) / f}; if {@code d0 <= 1}: direction
     * {@code (x - cx, (TNT ? y : eyeY) - cy, z - cz)} normalized (skipped if its length is 0);
     * {@code d5 = (1 - d0) * (double) seenPercent * (double) knockbackMultiplier(1.0F)}, times
     * {@code (1 - EXPLOSION_KNOCKBACK_RESISTANCE)} (0 for zombies); {@code entity.push(dir * d5)} adds it to the
     * velocity.
     */
    private static void knockback(Region r, double cx, double cy, double cz, float power) {
        EntityTable t = r.table;
        float f = power * 2.0F;
        for (int s = 0; s < t.count(); s++) {
            int type = t.type(s);
            if (type == PLAYER) continue; // client-authoritative
            double ex = t.x(s), ey = t.y(s), ez = t.z(s);
            double d0 = Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz)) / (double) f;
            if (!(d0 <= 1.0)) continue;
            double d1 = ex - cx, d2 = (type == TNT ? ey : ey + (double) Physics.eyeHeight(type)) - cy, d3 = ez - cz;
            double d4 = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
            if (d4 == 0.0) continue;
            d1 /= d4;
            d2 /= d4;
            d3 /= d4;
            double d5 = (1.0 - d0) * (double) exposure(r, cx, cy, cz, s) * (double) 1.0F;
            t.setVel(s, t.vx(s) + d1 * d5, t.vy(s) + d2 * d5, t.vz(s) + d3 * d5);
        }
    }

    static double eyeHeight(int type) {
        return Physics.eyeHeight(type);
    }

    /**
     * {@code ServerExplosion.getSeenPercent}: {@code d0 = 1 / ((maxX - minX) * 2 + 1)} (likewise d1, d2);
     * {@code d3 = (1 - floor(1/d0) * d0) / 2}, {@code d4} likewise for z; for {@code d5, d6, d7} from 0 to 1 in
     * those steps, the point {@code (lerp(d5, minX, maxX) + d3, lerp(d6, minY, maxY), lerp(d7, minZ, maxZ) + d4)}
     * counts as seen if a block-collider clip to the centre misses. Returns {@code (float) seen / (float) total}.
     */
    private static float exposure(Region r, double cx, double cy, double cz, int s) {
        EntityTable t = r.table;
        int type = t.type(s);
        double hw = Physics.halfWidth(type), h = Physics.height(type);
        double minX = t.x(s) - hw, minY = t.y(s), minZ = t.z(s) - hw;
        double maxX = t.x(s) + hw, maxY = t.y(s) + h, maxZ = t.z(s) + hw;
        double d0 = 1.0 / ((maxX - minX) * 2.0 + 1.0), d1 = 1.0 / ((maxY - minY) * 2.0 + 1.0), d2 = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        double d3 = (1.0 - Math.floor(1.0 / d0) * d0) / 2.0, d4 = (1.0 - Math.floor(1.0 / d2) * d2) / 2.0;
        if (d0 < 0.0 || d1 < 0.0 || d2 < 0.0) return 0.0F;
        int seen = 0, total = 0;
        for (double d5 = 0.0; d5 <= 1.0; d5 += d0) {
            for (double d6 = 0.0; d6 <= 1.0; d6 += d1) {
                for (double d7 = 0.0; d7 <= 1.0; d7 += d2) {
                    double px = dev.mulcor.core.Mth.lerp(d5, minX, maxX) + d3, py = dev.mulcor.core.Mth.lerp(d6, minY, maxY);
                    double pz = dev.mulcor.core.Mth.lerp(d7, minZ, maxZ) + d4;
                    if (!blocked(r, px, py, pz, cx, cy, cz)) seen++;
                    total++;
                }
            }
        }
        return (float) seen / (float) total;
    }

    /** Voxel walk (Amanatides–Woo) from p to q: true if a solid full block lies on the segment. */
    private static boolean blocked(Region r, double px, double py, double pz, double qx, double qy, double qz) {
        BlockStorage b = r.world.blocks;
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
        int ex = (int) Math.floor(qx), ey = (int) Math.floor(qy), ez = (int) Math.floor(qz);
        double dx = qx - px, dy = qy - py, dz = qz - pz;
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - px : px - x) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - py : py - y) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - pz : pz - z) * tdz);
        for (int guard = 0; guard < 256; guard++) {
            int st = stateAt(r, x, y, z);
            if (st >= 0 && Blocks.isSolid(st)) return true;
            if (x == ex && y == ey && z == ez) return false;
            if (tmx < tmy && tmx < tmz) {
                if (tmx > 1) return false;
                x += stepX;
                tmx += tdx;
            } else if (tmy < tmz) {
                if (tmy > 1) return false;
                y += stepY;
                tmy += tdy;
            } else {
                if (tmz > 1) return false;
                z += stepZ;
                tmz += tdz;
            }
        }
        return false;
    }
}
