package dev.mulcor.core.region;

import static dev.mulcor.core.Blocks.*;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.OffHeapRing;
import dev.mulcor.memory.ScheduledTicks;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Block updates, redstone and scheduled block ticks for one region, run by the thread ticking it.
 *
 * <h2>Two kinds of update</h2>
 * <ul>
 *   <li><b>Neighbour updates</b> (0-delay): when a block changes, reacting neighbours are re-evaluated. Inside a
 *       region the queue is drained to a fixpoint in the same tick, so a wire line settles instantly as in
 *       vanilla. A neighbour in another region gets a {@link Msg#NEIGHBOR_UPDATE} message instead; it runs next
 *       epoch (the one-epoch-per-border invariant) and carries the sender's epoch as its origin.</li>
 *   <li><b>Scheduled ticks</b> (delay ≥ 1 game tick): torches (2), repeaters (2 per redstone tick), lamp turn-off
 *       (4), falling sand (2). They go into the region's {@link ScheduledTicks} heap with
 *       {@code due = origin + delay}. Because a cross-border update keeps the sender's epoch as its origin, a
 *       repeater fed from another region fires on the same epoch it would inside one region.</li>
 * </ul>
 *
 * <h2>Power model (simplified vanilla)</h2>
 * <ul>
 *   <li>Sources: redstone block (15, not into blocks), lit torch (15 to its sides and strongly into the block
 *       above, not into the block it stands on), powered repeater (15 into the position in front).</li>
 *   <li>A solid block is <i>strongly</i> powered by a repeater facing into it or a lit torch below it, and
 *       <i>weakly</i> powered by a wire on top of it. Wires read only strong power from blocks (as vanilla wires
 *       ignore power that came from wires); torches, repeaters, lamps and TNT read both.</li>
 *   <li>Wire power is the maximum of its sources and its neighbouring wires minus one.</li>
 * </ul>
 * Not modelled: comparators, observers, pistons, diagonal wire steps, wire direction shaping, torch burnout.
 */
final class Redstone {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    /** Most block evaluations one region runs per tick; the rest carry over (and are counted). */
    static final int UPDATE_BUDGET = 1 << 16;
    static final int TORCH_DELAY = 2, LAMP_OFF_DELAY = 4, FALL_DELAY = 2, TNT_FUSE = 80;

    private Redstone() {}

    // ---- queueing ----------------------------------------------------------------------------------------------

    /** Re-evaluate the block at (x,y,z): locally, or by message to its owner. */
    static void update(Region r, int x, int y, int z, long origin) {
        BlockStorage b = r.world.blocks;
        if (!b.inBounds(x, y, z)) return;
        int owner = r.world.ownerOfBlock(x, z);
        if (owner != r.id) {
            MemorySegment m = r.begin(Msg.NEIGHBOR_UPDATE);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(ValueLayout.JAVA_LONG, Msg.DEADLINE, origin);
            r.send(owner);
            r.crossUpdates++;
            return;
        }
        OffHeapRing q = r.updates;
        long pos = q.tryClaim();
        if (pos < 0) {
            r.updatesDropped++;
            return;
        }
        long o = q.payloadOffset(pos);
        MemorySegment s = q.segment();
        s.set(I, o, x);
        s.set(I, o + 4, y);
        s.set(I, o + 8, z);
        s.set(I, o + 12, (int) origin);
        q.publish(pos);
    }

    /** Update the six neighbours of (x,y,z) that react to updates. */
    static void notifyNeighbours(Region r, int x, int y, int z, long origin) {
        notifyIfReacts(r, x + 1, y, z, origin);
        notifyIfReacts(r, x - 1, y, z, origin);
        notifyIfReacts(r, x, y + 1, z, origin);
        notifyIfReacts(r, x, y - 1, z, origin);
        notifyIfReacts(r, x, y, z + 1, origin);
        notifyIfReacts(r, x, y, z - 1, origin);
    }

    private static void notifyIfReacts(Region r, int x, int y, int z, long origin) {
        if (reactsToUpdates(r.world.blocks.getShared(x, y, z))) update(r, x, y, z, origin);
    }

    /** Schedule a tick for a block this region owns, no earlier than next epoch. */
    static void schedule(Region r, int x, int y, int z, long due, int priority) {
        scheduleAt(r, x, y, z, Math.max(due, r.epoch + 1), priority);
    }

    /**
     * Schedule at exactly {@code due}: used for ticks handed over from another region, which may already be due
     * (messages are drained before scheduled ticks run, so it still fires this epoch).
     */
    static void scheduleAt(Region r, int x, int y, int z, long due, int priority) {
        if (!r.blockTicks.schedule(due, priority, ScheduledTicks.pack(x, y, z))
                && !r.blockTicks.isScheduled(ScheduledTicks.pack(x, y, z))) {
            r.ticksDropped++; // queue full
        }
    }

    /** A block was set by something other than redstone (mining, placing, explosions, commands). */
    static void blockChanged(Region r, int x, int y, int z) {
        if (reactsToUpdates(r.world.blocks.get(x, y, z))) update(r, x, y, z, r.epoch);
        notifyNeighbours(r, x, y, z, r.epoch);
    }

    // ---- processing --------------------------------------------------------------------------------------------

    /** Drain the neighbour-update queue to a fixpoint, within the tick's budget. */
    static void processUpdates(Region r) {
        OffHeapRing q = r.updates;
        while (r.updateBudget > 0) {
            long pos = q.tryAcquire();
            if (pos < 0) return;
            long o = q.payloadOffset(pos);
            MemorySegment s = q.segment();
            int x = s.get(I, o), y = s.get(I, o + 4), z = s.get(I, o + 8);
            long origin = (r.epoch & ~0xFFFF_FFFFL) | (s.get(I, o + 12) & 0xFFFF_FFFFL);
            if (origin > r.epoch) origin -= 1L << 32; // low 32 bits wrapped since it was queued
            q.release(pos);
            r.updateBudget--;
            r.blockUpdates++;
            evaluate(r, x, y, z, origin);
        }
        if (q.size() > 0) r.updatesDeferred++;
    }

    /** Run every scheduled tick due by this epoch, in (due, priority, insertion) order, cascading after each. */
    static void runScheduled(Region r) {
        ScheduledTicks t = r.blockTicks;
        while (t.peekDue() <= r.epoch && r.updateBudget > 0) {
            long due = t.peekDue();
            int priority = t.peekPriority();
            long pos = t.poll();
            int x = ScheduledTicks.x(pos), y = ScheduledTicks.y(pos), z = ScheduledTicks.z(pos);
            int owner = r.world.ownerOfBlock(x, z);
            if (owner != r.id) { // the region split since this was scheduled: hand it to the new owner
                MemorySegment m = r.begin(Msg.SCHEDULED_TICK);
                m.set(I, Msg.A, x);
                m.set(I, Msg.B, y);
                m.set(I, Msg.C, z);
                m.set(I, Msg.D, priority);
                m.set(ValueLayout.JAVA_LONG, Msg.DEADLINE, due);
                r.send(owner);
                continue;
            }
            r.updateBudget--;
            r.scheduledTicks++;
            tick(r, x, y, z, due);
            processUpdates(r);
        }
    }

    /** A neighbour changed: decide whether this block changes now or schedules a tick. */
    private static void evaluate(Region r, int x, int y, int z, long origin) {
        if (r.world.ownerOfBlock(x, z) != r.id) { // queued before a split moved this block
            update(r, x, y, z, origin);
            return;
        }
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (isWire(st)) {
            int p = wireInput(b, x, y, z);
            if (p != wirePower(st)) {
                b.set(x, y, z, withWirePower(st, p));
                notifyNeighbours(r, x, y, z, origin);
                notifyNeighbours(r, x, y - 1, z, origin); // the block below is (weakly) powered by us
            }
        } else if (isTorch(st)) {
            if (torchShouldBeLit(b, x, y, z) != (st == TORCH)) schedule(r, x, y, z, origin + TORCH_DELAY, 0);
        } else if (isRepeater(st)) {
            if (repeaterInput(b, x, y, z, repeaterDir(st)) != repeaterPowered(st)) {
                schedule(r, x, y, z, origin + 2L * repeaterDelay(st), repeaterPriority(b, x, y, z, st));
            }
        } else if (isLamp(st)) {
            boolean powered = receivesPower(b, x, y, z);
            if (powered && st == LAMP) {
                b.set(x, y, z, LAMP_LIT);
                notifyNeighbours(r, x, y, z, origin);
            } else if (!powered && st == LAMP_LIT) {
                schedule(r, x, y, z, origin + LAMP_OFF_DELAY, 0);
            }
        } else if (st == TNT) {
            if (receivesPower(b, x, y, z)) {
                b.set(x, y, z, AIR);
                Sim.spawnTnt(r, x + 0.5, y, z + 0.5, TNT_FUSE, dev.mulcor.core.Rng.mix(origin, x, (long) y << 32 | z));
                r.tntPrimed++;
                notifyNeighbours(r, x, y, z, origin);
            }
        } else if (st == SAND) {
            if (b.getShared(x, y - 1, z) == AIR && y - 1 >= b.minY()) schedule(r, x, y, z, origin + FALL_DELAY, 0);
        }
    }

    /** A scheduled tick fired at epoch {@code due}. */
    private static void tick(Region r, int x, int y, int z, long due) {
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (isTorch(st)) {
            boolean lit = torchShouldBeLit(b, x, y, z);
            if (lit != (st == TORCH)) {
                b.set(x, y, z, lit ? TORCH : TORCH_OFF);
                r.redstoneChanges++;
                notifyNeighbours(r, x, y, z, due);
                notifyNeighbours(r, x, y + 1, z, due); // the block above is strongly powered by a lit torch
            }
        } else if (isRepeater(st)) {
            int dir = repeaterDir(st);
            boolean input = repeaterInput(b, x, y, z, dir);
            if (repeaterPowered(st) && !input) {
                setRepeater(r, x, y, z, st, false, due);
            } else if (!repeaterPowered(st)) {
                setRepeater(r, x, y, z, st, true, due);
                // Vanilla pulse extension: a pulse shorter than the delay still comes out delay ticks long.
                if (!input) schedule(r, x, y, z, due + 2L * repeaterDelay(st), repeaterPriority(b, x, y, z, st));
            }
        } else if (st == LAMP_LIT) {
            if (!receivesPower(b, x, y, z)) {
                b.set(x, y, z, LAMP);
                notifyNeighbours(r, x, y, z, due);
            }
        } else if (st == SAND) {
            if (y - 1 >= b.minY() && b.getShared(x, y - 1, z) == AIR) {
                b.set(x, y, z, AIR);
                Physics.spawnFallingBlock(r, x + 0.5, y, z + 0.5, SAND);
                notifyNeighbours(r, x, y, z, due);
            }
        }
    }

    private static void setRepeater(Region r, int x, int y, int z, int st, boolean powered, long origin) {
        r.world.blocks.set(x, y, z, withRepeaterPowered(st, powered));
        r.redstoneChanges++;
        int dir = repeaterDir(st);
        int fx = x + DX[dir], fz = z + DZ[dir];
        update(r, fx, y, fz, origin);               // the block in front
        notifyNeighbours(r, fx, y, fz, origin);     // and what that (now strongly powered) block powers
    }

    /** Vanilla: a repeater that feeds another diode ticks with higher priority, so chains keep their order. */
    private static int repeaterPriority(BlockStorage b, int x, int y, int z, int st) {
        int dir = repeaterDir(st);
        return isRepeater(b.getShared(x + DX[dir], y, z + DZ[dir])) ? -1 : 0;
    }

    // ---- power queries (neighbours may be foreign: shared reads, one epoch stale at worst) ---------------------

    /** Strong power a solid block receives from something other than wire: a repeater facing into it or a lit torch below. */
    static boolean stronglyPowered(BlockStorage b, int x, int y, int z) {
        if (b.getShared(x, y - 1, z) == TORCH) return true;
        for (int d = 0; d < 4; d++) {
            int n = b.getShared(x - DX[d], y, z - DZ[d]); // neighbour whose front (dir d) is us
            if (isRepeater(n) && repeaterPowered(n) && repeaterDir(n) == d) return true;
        }
        return false;
    }

    /** Strong power, or weak power from a wire on top. */
    static boolean blockPowered(BlockStorage b, int x, int y, int z) {
        int above = b.getShared(x, y + 1, z);
        return (isWire(above) && wirePower(above) > 0) || stronglyPowered(b, x, y, z);
    }

    static int wireInput(BlockStorage b, int x, int y, int z) {
        int p = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d], nz = z + DZ[d];
            int n = b.getShared(nx, y, nz);
            if (n == REDSTONE_BLOCK || n == TORCH) return 15;
            if (isRepeater(n)) {
                if (repeaterPowered(n) && nx + DX[repeaterDir(n)] == x && nz + DZ[repeaterDir(n)] == z) return 15;
            } else if (isWire(n)) {
                p = Math.max(p, wirePower(n) - 1);
            } else if (isConductor(n) && stronglyPowered(b, nx, y, nz)) {
                return 15;
            }
        }
        int below = b.getShared(x, y - 1, z), above = b.getShared(x, y + 1, z);
        if (below == REDSTONE_BLOCK || above == REDSTONE_BLOCK) return 15;
        if (isSolid(below) && stronglyPowered(b, x, y - 1, z)) return 15;
        if (isSolid(above) && stronglyPowered(b, x, y + 1, z)) return 15;
        return p;
    }

    static boolean torchShouldBeLit(BlockStorage b, int x, int y, int z) {
        int support = b.getShared(x, y - 1, z);
        return support != REDSTONE_BLOCK && !(isSolid(support) && blockPowered(b, x, y - 1, z));
    }

    /** Signal entering a repeater from behind (the side opposite its output). */
    static boolean repeaterInput(BlockStorage b, int x, int y, int z, int dir) {
        int bx = x - DX[dir], bz = z - DZ[dir];
        int n = b.getShared(bx, y, bz);
        if (n == REDSTONE_BLOCK || n == TORCH) return true;
        if (isWire(n)) return wirePower(n) > 0;
        if (isRepeater(n)) return repeaterPowered(n) && repeaterDir(n) == dir;
        return isSolid(n) && blockPowered(b, bx, y, bz);
    }

    /** Lamps and TNT: powered by any adjacent source, powered block, or adjacent wire (beside or above). */
    static boolean receivesPower(BlockStorage b, int x, int y, int z) {
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d], nz = z + DZ[d];
            if (sidePowers(b, b.getShared(nx, y, nz), nx, y, nz, x, z)) return true;
        }
        int below = b.getShared(x, y - 1, z);
        if (below == REDSTONE_BLOCK || below == TORCH || (isSolid(below) && blockPowered(b, x, y - 1, z))) return true;
        int above = b.getShared(x, y + 1, z);
        if (above == REDSTONE_BLOCK || (isWire(above) && wirePower(above) > 0)) return true;
        return isSolid(above) && blockPowered(b, x, y + 1, z); // a torch on top stands on us: no power
    }

    private static boolean sidePowers(BlockStorage b, int n, int nx, int y, int nz, int x, int z) {
        if (n == REDSTONE_BLOCK || n == TORCH) return true;
        if (isWire(n)) return wirePower(n) > 0;
        if (isRepeater(n)) return repeaterPowered(n) && nx + DX[repeaterDir(n)] == x && nz + DZ[repeaterDir(n)] == z;
        return isSolid(n) && blockPowered(b, nx, y, nz);
    }
}
