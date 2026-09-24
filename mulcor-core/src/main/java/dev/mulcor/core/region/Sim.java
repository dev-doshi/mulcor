package dev.mulcor.core.region;

import static dev.mulcor.core.region.Entities.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Rng;
import dev.mulcor.core.World;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.OffHeapInventory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Game mechanics, run by the thread currently ticking a region: bot behaviour, mining and placing, TNT, chests.
 * Movement and collisions live in {@link Physics}, block updates and redstone in {@link Redstone}. Every write goes
 * either to data the region owns or into a message to the owning region. The code is simplified (not
 * vanilla-accurate) but exercises the same concurrency patterns.
 */
final class Sim {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private Sim() {}

    // ---- entity loop -------------------------------------------------------------------------------------------

    static void simulate(Region r) {
        EntityTable t = r.table;
        int i = 0;
        while (i < t.count()) {
            boolean removed;
            int type = t.type(i);
            if (type == TNT) {
                removed = tickTnt(r, i);
            } else if (type == PLAYER) {
                removed = Physics.handOff(r, i); // client-authoritative: no AI, no physics
            } else if (type == FALLING_BLOCK) {
                removed = Physics.step(r, i);
            } else {
                if (r.ai) bot(r, i);
                removed = Physics.step(r, i);
            }
            if (!removed) i++;
        }
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
                    int z = (int) Math.floor(t.z(s)) + Rng.pick(h, 8, 5) - 2;
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
                    tz = Rng.pick(h, 20, r.world.sizeZ());
                    t.setAux1(s, tx | (tz << 16));
                }
                if ((e + eid) % 10 == 0) {
                    int dir = r.path.step(r.world.blocks, (int) Math.floor(t.x(s)), (int) Math.floor(t.y(s)),
                            (int) Math.floor(t.z(s)), tx, tz);
                    if (dir >= 0) {
                        walk(t, s, (dir & 3) - 1, ((dir >> 2) & 3) - 1);
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
                        putIntoChest(r, eid, chest, 1 + Rng.pick(h, 44, 8));
                    } else {
                        takeFromChest(r, eid, chest, Rng.pick(h, 24, World.CHEST_SLOTS), 1 + Rng.pick(h, 50, 8));
                    }
                }
                wander(r, s, e, eid, h, 60);
            }
            case BOMBER -> {
                if ((e + eid) % 400 == 0) {
                    spawnTnt(r, t.x(s), t.y(s), t.z(s), 30, h);
                }
                wander(r, s, e, eid, h, 30);
            }
            default -> { }
        }
    }

    private static void wander(Region r, int s, long e, int eid, long h, int period) {
        if ((e + eid) % period == 0) {
            walk(r.table, s, Rng.pick(h, 12, 3) - 1, Rng.pick(h, 16, 3) - 1);
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
        Redstone.blockChanged(r, x, y, z);
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
            Redstone.blockChanged(r, x, y, z);
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

    /**
     * {@code new PrimedTnt(level, x, y, z, igniter)}: fuse 80 by default, and
     * {@code d0 = random.nextDouble() * (double) ((float) Math.PI * 2F)};
     * {@code deltaMovement = (-Math.sin(d0) * 0.02D, (double) 0.2F, -Math.cos(d0) * 0.02D)}.
     * {@code random} is a hash standing in for the level RNG (same distribution, not the same sequence).
     */
    static void spawnTnt(Region r, double x, double y, double z, int fuse, long random) {
        int eid = r.spawn(x, y, z, TNT, 0);
        if (eid < 0) return;
        int s = r.world.directory.slot(eid);
        double d0 = (Rng.mix(random) >>> 11) * 0x1.0p-53 * (double) ((float) Math.PI * 2F);
        r.table.setVel(s, -Math.sin(d0) * 0.02, (double) 0.2F, -Math.cos(d0) * 0.02);
        r.table.setAux1(s, fuse);
    }

    static void spawnTnt(Region r, double x, double y, double z, int fuse) {
        spawnTnt(r, x, y, z, fuse, Rng.mix(r.epoch, Double.doubleToLongBits(x), Double.doubleToLongBits(z)));
    }

    /**
     * {@code PrimedTnt.tick}: gravity, move, drag and bounce ({@link Physics#gravityMoveDrag}); then
     * {@code fuse = getFuse() - 1}; at {@code fuse <= 0} it is discarded and explodes at {@code getY(0.0625D)}
     * ({@code y + 0.98F * 0.0625}) with power 4.0F.
     */
    private static boolean tickTnt(Region r, int s) {
        EntityTable t = r.table;
        int fuse = t.aux1(s) - 1;
        t.setAux1(s, fuse);
        if (fuse > 0) return Physics.step(r, s);
        Physics.gravityMoveDrag(r, s);
        double x = t.x(s), y = t.y(s) + (double) Physics.height(TNT) * 0.0625, z = t.z(s);
        r.despawn(s);
        Explosion.explode(r, x, y, z, Explosion.TNT_POWER);
        return true;
    }

    /**
     * Yaw that faces (dx, dz), as {@code MoveControl.tick} computes it:
     * {@code (float) (atan2(dz, dx) * (double) (180F / (float) Math.PI)) - 90.0F}. (Vanilla uses its table-based
     * {@code Mth.atan2}; AI direction choice is Mulcor's own, see {@link Physics}.)
     */
    static float yawTowards(double dx, double dz) {
        return (float) (Math.atan2(dz, dx) * (double) (180F / (float) Math.PI)) - 90.0F;
    }

    /** What {@code MoveControl} leaves for {@code travel}: {@code yRot} toward the direction, {@code zza = speed}. */
    static void walk(EntityTable t, int s, double dx, double dz) {
        if (dx == 0 && dz == 0) {
            t.setInput(s, t.inputYaw(s), 0F);
        } else {
            t.setInput(s, yawTowards(dx, dz), Physics.ZOMBIE_SPEED);
        }
    }

    // ---- messages addressed to data this region owns -----------------------------------------------------------

    static void applyOwned(Region r, MemorySegment seg, long off, int kind) {
        int a = seg.get(I, off + Msg.A), b = seg.get(I, off + Msg.B), c = seg.get(I, off + Msg.C);
        int d = seg.get(I, off + Msg.D);
        switch (kind) {
            case Msg.BLOCK_BREAK -> breakOwned(r, seg.get(I, off + Msg.E), a, b, c);
            case Msg.BLOCK_PLACE -> placeOwned(r, seg.get(I, off + Msg.E), a, b, c, d);
            case Msg.EXPLOSION -> Explosion.receive(r, seg, off);
            case Msg.EXPLOSION_BLOCKS -> Explosion.receiveBlocks(r, seg, off);
            case Msg.NEIGHBOR_UPDATE -> Redstone.update(r, a, b, c, seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.SCHEDULED_TICK -> Redstone.scheduleAt(r, a, b, c, seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE), d);
            case Msg.INV_TAKE -> {
                long taken = r.world.chests.take(a, b, c);
                deliver(r, d, OffHeapInventory.item(taken), OffHeapInventory.count(taken));
            }
            case Msg.INV_PUT -> deliver(r, d, b, r.world.chests.insert(a, b, c));
            default -> r.stateViolations++;
        }
    }

    // ---- commands ----------------------------------------------------------------------------------------------

    /** SET_BLOCK input: set a block this region owns and run the resulting updates. */
    static void setBlockAndUpdate(Region r, int x, int y, int z, int state) {
        if (r.world.ownerOfBlock(x, z) != r.id || r.world.blocks.set(x, y, z, state) == BlockStorage.FAILED) return;
        Redstone.blockChanged(r, x, y, z);
    }
}
