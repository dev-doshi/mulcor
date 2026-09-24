package dev.mulcor.core.region;

import static dev.mulcor.core.region.Entities.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Rng;
import dev.mulcor.core.World;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.OffHeapInventory;
import dev.mulcor.memory.OffHeapRing;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Game mechanics, run by the thread currently ticking a region. Every write goes either to data the region
 * owns or into a message to the owning region. The code is simplified (not vanilla-accurate) but exercises
 * the same concurrency patterns: block mutation, entity movement across boundaries, explosions and shared
 * inventories.
 */
final class Sim {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final float WALK = 0.2f;

    private Sim() {}

    // ---- entity loop -------------------------------------------------------------------------------------------

    static void simulate(Region r) {
        EntityTable t = r.table;
        int i = 0;
        while (i < t.count()) {
            boolean removed;
            if (t.type(i) == TNT) {
                removed = tickTnt(r, i);
            } else {
                if (r.ai) bot(r, i);
                removed = physics(r, i);
            }
            if (!removed) i++;
        }
    }

    /** Gravity, walking with 1-block step-up, then hand-off if the entity crossed into another region. */
    private static boolean physics(Region r, int s) {
        EntityTable t = r.table;
        BlockStorage b = r.world.blocks;
        double x = t.x(s), y = t.y(s), z = t.z(s);
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (by > b.minY() + 1 && !Blocks.isSolid(b.getShared(bx, by - 1, bz))) {
            y -= 1;
            by--;
        }
        float vx = t.vx(s), vz = t.vz(s);
        if (vx != 0f || vz != 0f) {
            double nx = x + vx, nz = z + vz;
            if (nx < 0.5 || nx > r.world.sizeX() - 0.5) { vx = -vx; nx = x; }
            if (nz < 0.5 || nz > r.world.sizeZ() - 0.5) { vz = -vz; nz = z; }
            int nbx = (int) Math.floor(nx), nbz = (int) Math.floor(nz);
            if ((nbx != bx || nbz != bz) && Blocks.isSolid(b.getShared(nbx, by, nbz))) {
                if (by + 2 < b.maxYExclusive() && !Blocks.isSolid(b.getShared(nbx, by + 1, nbz))
                        && !Blocks.isSolid(b.getShared(nbx, by + 2, nbz))) {
                    y += 1; // step up
                    x = nx;
                    z = nz;
                } else {
                    vx = -vx;
                    vz = -vz;
                }
            } else {
                x = nx;
                z = nz;
            }
            t.setVel(s, vx, 0f, vz);
        }
        t.setPos(s, x, y, z);
        int owner = r.world.ownerOfBlock((int) Math.floor(x), (int) Math.floor(z));
        return owner != r.id && r.migrate(s, owner);
    }

    // ---- bots --------------------------------------------------------------------------------------------------

    private static void bot(Region r, int s) {
        EntityTable t = r.table;
        int eid = (int) t.id(s);
        long e = r.epoch;
        long h = Rng.mix(r.cfg.seed(), eid, e);
        switch (t.aux0(s)) {
            case MINER -> {
                if (((e + eid) & 3) == 0) {
                    int x = (int) Math.floor(t.x(s)) + Rng.bounded(h, 5) - 2;
                    int z = (int) Math.floor(t.z(s)) + Rng.bounded(h >>> 8, 5) - 2;
                    int y = (int) Math.floor(t.y(s)) - 1;
                    int st = r.world.blocks.getShared(x, y, z);
                    if (Blocks.isMineable(st)) {
                        dig(r, eid, x, y, z);
                    } else if (st == Blocks.AIR) {
                        int item = OffHeapInventory.item(r.world.players.get(eid, 0)) != 0
                                ? OffHeapInventory.item(r.world.players.get(eid, 0)) : Blocks.DIRT;
                        place(r, eid, x, y, z, item);
                    }
                }
                wander(r, s, e, eid, h, 40);
            }
            case NAVIGATOR -> {
                int target = t.aux1(s);
                int tx = target & 0xFFFF, tz = target >>> 16;
                double dx = tx + 0.5 - t.x(s), dz = tz + 0.5 - t.z(s);
                if (target == 0 || dx * dx + dz * dz < 4) {
                    tx = Rng.bounded(h, r.world.sizeX());
                    tz = Rng.bounded(h >>> 20, r.world.sizeZ());
                    t.setAux1(s, tx | (tz << 16));
                }
                if ((e + eid) % 10 == 0) {
                    int dir = r.path.step(r.world.blocks, (int) Math.floor(t.x(s)), (int) Math.floor(t.y(s)),
                            (int) Math.floor(t.z(s)), tx, tz);
                    if (dir >= 0) {
                        t.setVel(s, ((dir & 3) - 1) * WALK, 0f, (((dir >> 2) & 3) - 1) * WALK);
                    } else {
                        t.setAux1(s, 0); // unreachable from here: pick another waypoint
                    }
                }
            }
            case CHESTER -> {
                if ((e + eid) % 5 == 0) {
                    int chest = Rng.bounded(h, r.world.chestCount());
                    int slot = firstNonEmpty(r.world.players, eid);
                    if (slot >= 0 && (h & (1L << 40)) != 0) {
                        putIntoChest(r, eid, chest, 1 + Rng.bounded(h >>> 44, 8));
                    } else {
                        takeFromChest(r, eid, chest, Rng.bounded(h >>> 24, World.CHEST_SLOTS), 1 + Rng.bounded(h >>> 50, 8));
                    }
                }
                wander(r, s, e, eid, h, 60);
            }
            case BOMBER -> {
                if ((e + eid) % 400 == 0) {
                    spawnTnt(r, t.x(s), t.y(s), t.z(s), 30);
                }
                wander(r, s, e, eid, h, 30);
            }
            default -> { }
        }
    }

    private static void wander(Region r, int s, long e, int eid, long h, int period) {
        if ((e + eid) % period == 0) {
            r.table.setVel(s, (Rng.bounded(h >>> 12, 3) - 1) * WALK * 0.5f, 0f,
                    (Rng.bounded(h >>> 16, 3) - 1) * WALK * 0.5f);
        }
    }

    private static int firstNonEmpty(OffHeapInventory inv, int index) {
        for (int s = 0; s < inv.slotsPerInventory(); s++) {
            if (OffHeapInventory.count(inv.get(index, s)) > 0) return s;
        }
        return -1;
    }

    // ---- block interaction -------------------------------------------------------------------------------------

    /** Entity {@code eid} (owned by {@code r}) breaks a block and receives it as an item. */
    static void dig(Region r, int eid, int x, int y, int z) {
        int owner = r.world.ownerOfBlock(x, z);
        if (owner == r.id) {
            breakOwned(r, eid, x, y, z);
        } else {
            MemorySegment m = r.begin(Msg.BLOCK_BREAK);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(I, Msg.E, eid);
            r.send(owner);
        }
    }

    private static void breakOwned(Region r, int eid, int x, int y, int z) {
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (!Blocks.isMineable(st)) return; // already gone: whoever arrived first got it
        b.set(x, y, z, Blocks.AIR);
        deliver(r, eid, st, 1);
        updateNeighbours(r, x, y, z);
    }

    /** Entity {@code eid} (owned by {@code r}) places one {@code item} from its inventory. */
    static void place(Region r, int eid, int x, int y, int z, int item) {
        if (!Blocks.isMineable(item) || !takeOne(r.world.players, eid, item)) return;
        int owner = r.world.ownerOfBlock(x, z);
        if (owner == r.id) {
            placeOwned(r, eid, x, y, z, item);
        } else {
            MemorySegment m = r.begin(Msg.BLOCK_PLACE);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(I, Msg.D, item);
            m.set(I, Msg.E, eid);
            r.send(owner);
        }
    }

    private static void placeOwned(Region r, int eid, int x, int y, int z, int item) {
        BlockStorage b = r.world.blocks;
        if (b.inBounds(x, y, z) && b.get(x, y, z) == Blocks.AIR && b.set(x, y, z, item) != BlockStorage.FAILED) {
            updateNeighbours(r, x, y, z);
        } else {
            deliver(r, eid, item, 1); // occupied or out of world: refund
        }
    }

    private static boolean takeOne(OffHeapInventory inv, int index, int item) {
        for (int s = 0; s < inv.slotsPerInventory(); s++) {
            long w = inv.get(index, s);
            if (OffHeapInventory.item(w) == item && OffHeapInventory.count(inv.take(index, s, 1)) == 1) return true;
        }
        return false;
    }

    /** Give items to an entity wherever it currently lives. Anything that does not fit is dropped (and counted). */
    static void deliver(Region r, int eid, int item, int count) {
        if (count <= 0) return;
        if (r.ownsEntity(eid)) {
            r.droppedItems += r.world.players.insert(eid, item, count);
            return;
        }
        int owner = r.world.ownerOfEntity(eid);
        if (owner < 0) {
            r.droppedItems += count;
            return;
        }
        MemorySegment m = r.begin(Msg.INV_DELIVER);
        m.set(I, Msg.A, eid);
        m.set(I, Msg.B, item);
        m.set(I, Msg.C, count);
        r.send(owner);
    }

    // ---- chests ------------------------------------------------------------------------------------------------

    static void takeFromChest(Region r, int eid, int chest, int slot, int count) {
        if (chest < 0 || chest >= r.world.chestCount() || slot < 0 || slot >= World.CHEST_SLOTS) return;
        int owner = r.world.ownerOfChest(chest);
        if (owner == r.id) {
            long taken = r.world.chests.take(chest, slot, count);
            deliver(r, eid, OffHeapInventory.item(taken), OffHeapInventory.count(taken));
        } else {
            MemorySegment m = r.begin(Msg.INV_TAKE);
            m.set(I, Msg.A, chest);
            m.set(I, Msg.B, slot);
            m.set(I, Msg.C, count);
            m.set(I, Msg.D, eid);
            r.send(owner);
        }
    }

    static void putIntoChest(Region r, int eid, int chest, int count) {
        if (chest < 0 || chest >= r.world.chestCount()) return;
        int slot = firstNonEmpty(r.world.players, eid);
        if (slot < 0) return;
        long taken = r.world.players.take(eid, slot, count);
        int item = OffHeapInventory.item(taken), n = OffHeapInventory.count(taken);
        if (n == 0) return;
        int owner = r.world.ownerOfChest(chest);
        if (owner == r.id) {
            int left = r.world.chests.insert(chest, item, n);
            deliver(r, eid, item, left);
        } else {
            MemorySegment m = r.begin(Msg.INV_PUT);
            m.set(I, Msg.A, chest);
            m.set(I, Msg.B, item);
            m.set(I, Msg.C, n);
            m.set(I, Msg.D, eid);
            r.send(owner);
        }
    }

    // ---- TNT ---------------------------------------------------------------------------------------------------

    static void spawnTnt(Region r, double x, double y, double z, int fuse) {
        int eid = r.spawn(x, y, z, TNT, 0);
        if (eid >= 0) r.table.setAux1(r.world.directory.slot(eid), fuse);
    }

    private static boolean tickTnt(Region r, int s) {
        EntityTable t = r.table;
        int fuse = t.aux1(s) - 1;
        if (fuse > 0) {
            t.setAux1(s, fuse);
            return physics(r, s);
        }
        int cx = (int) Math.floor(t.x(s)), cy = (int) Math.floor(t.y(s)), cz = (int) Math.floor(t.z(s));
        r.despawn(s);
        explode(r, cx, cy, cz, TNT_RADIUS);
        return true;
    }

    /** Carve our own cells, push our own entities, and tell every neighbouring owner to do the same next epoch. */
    private static void explode(Region r, int cx, int cy, int cz, int radius) {
        r.explosions++;
        carveOwned(r, cx, cy, cz, radius);
        World w = r.world;
        int cb = r.cfg.cellBlocks();
        int x0 = Math.max(0, cx - radius) / cb, x1 = Math.min(w.sizeX() - 1, cx + radius) / cb;
        int z0 = Math.max(0, cz - radius) / cb, z1 = Math.min(w.sizeZ() - 1, cz + radius) / cb;
        int sent0 = -1, sent1 = -1, sent2 = -1;
        for (int gx = x0; gx <= x1; gx++) {
            for (int gz = z0; gz <= z1; gz++) {
                int owner = w.partition.regionAt(gx, gz);
                if (owner == r.id || owner == sent0 || owner == sent1 || owner == sent2) continue;
                if (sent0 < 0) sent0 = owner; else if (sent1 < 0) sent1 = owner; else sent2 = owner;
                MemorySegment m = r.begin(Msg.EXPLOSION);
                m.set(I, Msg.A, cx);
                m.set(I, Msg.B, cy);
                m.set(I, Msg.C, cz);
                m.set(I, Msg.D, radius);
                r.send(owner);
            }
        }
    }

    private static void carveOwned(Region r, int cx, int cy, int cz, int radius) {
        BlockStorage b = r.world.blocks;
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ()) continue;
                if (r.world.ownerOfBlock(x, z) != r.id) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    int y = cy + dy;
                    if (Blocks.isMineable(b.get(x, y, z))) {
                        b.set(x, y, z, Blocks.AIR);
                        r.destroyedBlocks++;
                    }
                }
            }
        }
        EntityTable t = r.table;
        double reach = radius + 2;
        for (int s = 0; s < t.count(); s++) {
            double dx = t.x(s) - (cx + 0.5), dz = t.z(s) - (cz + 0.5);
            double d2 = dx * dx + dz * dz;
            if (d2 < reach * reach && d2 > 1e-6) {
                double inv = 0.6 / Math.sqrt(d2);
                t.setVel(s, (float) (dx * inv), 0f, (float) (dz * inv));
            }
        }
    }

    // ---- messages addressed to data this region owns -----------------------------------------------------------

    static void applyOwned(Region r, MemorySegment seg, long off, int kind) {
        int a = seg.get(I, off + Msg.A), b = seg.get(I, off + Msg.B), c = seg.get(I, off + Msg.C);
        int d = seg.get(I, off + Msg.D);
        switch (kind) {
            case Msg.BLOCK_BREAK -> breakOwned(r, seg.get(I, off + Msg.E), a, b, c);
            case Msg.BLOCK_PLACE -> placeOwned(r, seg.get(I, off + Msg.E), a, b, c, d);
            case Msg.EXPLOSION -> carveOwned(r, a, b, c, d);
            case Msg.REDSTONE -> scheduleUpdate(r, a, b, c);
            case Msg.INV_TAKE -> {
                long taken = r.world.chests.take(a, b, c);
                deliver(r, d, OffHeapInventory.item(taken), OffHeapInventory.count(taken));
            }
            case Msg.INV_PUT -> deliver(r, d, b, r.world.chests.insert(a, b, c));
            default -> r.stateViolations++;
        }
    }

    // ---- redstone ----------------------------------------------------------------------------------------------

    static void setBlockAndUpdate(Region r, int x, int y, int z, int state) {
        if (r.world.ownerOfBlock(x, z) != r.id) return;
        r.world.blocks.set(x, y, z, state);
        scheduleUpdate(r, x, y, z);
        updateNeighbours(r, x, y, z);
    }

    /** Queue a re-evaluation of (x,y,z) with its owner: locally, or via a REDSTONE message next epoch. */
    static void scheduleUpdate(Region r, int x, int y, int z) {
        int owner = r.world.ownerOfBlock(x, z);
        if (owner != r.id) {
            MemorySegment m = r.begin(Msg.REDSTONE);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            r.send(owner);
            return;
        }
        OffHeapRing q = r.updates;
        long pos = q.tryClaim();
        if (pos < 0) {
            r.updatesDropped++;
            return;
        }
        long o = q.payloadOffset(pos);
        q.segment().set(I, o, x);
        q.segment().set(I, o + 4, y);
        q.segment().set(I, o + 8, z);
        q.publish(pos);
    }

    /** Schedule updates for adjacent redstone wires only, so ordinary mining does not flood the queue. */
    private static void updateNeighbours(Region r, int x, int y, int z) {
        BlockStorage b = r.world.blocks;
        if (Blocks.isWire(b.getShared(x + 1, y, z))) scheduleUpdate(r, x + 1, y, z);
        if (Blocks.isWire(b.getShared(x - 1, y, z))) scheduleUpdate(r, x - 1, y, z);
        if (Blocks.isWire(b.getShared(x, y, z + 1))) scheduleUpdate(r, x, y, z + 1);
        if (Blocks.isWire(b.getShared(x, y, z - 1))) scheduleUpdate(r, x, y, z - 1);
    }

    /** Process the updates queued before this tick; updates they schedule run next tick. */
    static void processUpdates(Region r) {
        OffHeapRing q = r.updates;
        int n = q.size();
        for (int i = 0; i < n; i++) {
            long pos = q.tryAcquire();
            if (pos < 0) break;
            long o = q.payloadOffset(pos);
            int x = q.segment().get(I, o), y = q.segment().get(I, o + 4), z = q.segment().get(I, o + 8);
            q.release(pos);
            updateWire(r, x, y, z);
        }
    }

    /** Wire power = max(15 next to a redstone block, neighbour wire power − 1). Neighbours may be foreign (shared read). */
    private static void updateWire(Region r, int x, int y, int z) {
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (!Blocks.isWire(st)) return;
        int power = Math.max(Math.max(source(b, x + 1, y, z), source(b, x - 1, y, z)),
                Math.max(source(b, x, y, z + 1), source(b, x, y, z - 1)));
        if (power == Blocks.wirePower(st)) return;
        b.set(x, y, z, Blocks.WIRE + power);
        updateNeighbours(r, x, y, z);
    }

    private static int source(BlockStorage b, int x, int y, int z) {
        int st = b.getShared(x, y, z);
        if (st == Blocks.REDSTONE_BLOCK) return 15;
        return Blocks.isWire(st) ? Math.max(0, Blocks.wirePower(st) - 1) : 0;
    }
}
