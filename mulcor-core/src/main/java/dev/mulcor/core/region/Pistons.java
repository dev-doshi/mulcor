package dev.mulcor.core.region;

import static dev.mulcor.core.region.RedstoneStates.*;

import dev.mulcor.memory.BlockEventQueue;
import dev.mulcor.memory.ScheduledTicks;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;

/**
 * Vanilla 26.2 pistons for one region, run by the thread ticking it: {@code PistonBaseBlock} (signal check, block
 * events, {@code moveBlocks}), {@code PistonStructureResolver}, {@code PistonHeadBlock}, {@code MovingPistonBlock}
 * with {@code PistonMovingBlockEntity}, and {@code ServerLevel.runBlockEvents}. Method names follow Mojang's.
 *
 * <h2>Timing</h2>
 * A piston schedules a block event when its signal changes; {@link #runBlockEvents} runs the region's events after
 * its block ticks ({@code ServerLevel.tick}: block ticks, fluid ticks, chunk ticks, then {@code runBlockEvents}),
 * draining events added meanwhile. The event turns the moved blocks into {@code moving_piston}s whose block entities
 * tick after the entities ({@link #tickBlockEntities}): progress 0 → 0.5 → 1.0, and on the third tick (two ticks after
 * the event) the moved block is placed.
 *
 * <h2>Region borders (the one intended deviation)</h2>
 * A piston only moves blocks its region owns: a structure that reaches another region's block, or a head or moved
 * block that would land in one, does not resolve, and the piston stays put (a vanilla-legal "blocked" outcome). The
 * roadmap's affinity bonds keep a piston and what it pushes in one region, so this only shows while a bond forms.
 * A {@code moving_piston} in another region is seen as having no block entity.
 *
 * <h2>Not ported</h2>
 * Entities moved or launched by moving blocks ({@code moveCollidedEntities}, {@code moveStuckEntities}, slime and honey
 * bounce), item drops of destroyed blocks, sounds and game events.
 */
final class Pistons {
    /** {@code PistonBaseBlock.TRIGGER_EXTEND / TRIGGER_CONTRACT / TRIGGER_DROP}. */
    static final int TRIGGER_EXTEND = 0, TRIGGER_CONTRACT = 1, TRIGGER_DROP = 2;
    /** Flag sets {@code PistonBaseBlock} and {@code PistonMovingBlockEntity} pass to {@code Level.setBlock}. */
    private static final int F_18 = 18, F_67 = 67, F_68 = 68, F_82 = 82, F_84 = 84, F_276 = 276;

    private Pistons() {}

    private static long pack(int x, int y, int z) { return ScheduledTicks.pack(x, y, z); }

    private static int state(Region r, long p) {
        return Redstone.state(r, ScheduledTicks.x(p), ScheduledTicks.y(p), ScheduledTicks.z(p));
    }

    private static long rel(long p, int dir, int n) {
        return pack(ScheduledTicks.x(p) + OX[dir] * n, ScheduledTicks.y(p) + OY[dir] * n, ScheduledTicks.z(p) + OZ[dir] * n);
    }

    private static boolean owned(Region r, long p) {
        int x = ScheduledTicks.x(p), y = ScheduledTicks.y(p), z = ScheduledTicks.z(p);
        return !r.world.blocks.inBounds(x, y, z) || r.world.ownerOfBlock(x, z) == r.id;
    }

    /** The {@code PistonMovingBlockEntity} at {@code p}, or -1 (none, or in another region). */
    private static int movingEntity(Region r, long p) {
        return owned(r, p) ? r.movingPistons.find(p) : -1;
    }

    // =============================================================================================================
    // Block events (ServerLevel.blockEvent / runBlockEvents)
    // =============================================================================================================

    /** {@code Level.blockEvent(pos, block, a, b)}. */
    static void blockEvent(Region r, int x, int y, int z, int block, int a, int b) {
        if (!r.blockEvents.add(pack(x, y, z), block, a, b)) r.blockEventsDropped++;
    }

    /**
     * {@code ServerLevel.runBlockEvents}: take events from the head until none are left, including ones added while
     * running; {@code doBlockEvent} runs an event only if the block is still of the type that queued it.
     */
    static void runBlockEvents(Region r) {
        BlockEventQueue q = r.blockEvents;
        while (q.poll()) {
            long p = q.pos();
            int block = q.block(), a = q.a(), b = q.b();
            int x = ScheduledTicks.x(p), y = ScheduledTicks.y(p), z = ScheduledTicks.z(p);
            if (!r.world.blocks.inBounds(x, y, z) || r.world.ownerOfBlock(x, z) != r.id) {
                r.blockEventsDropped++; // queued before a split moved the position away (splits move them: defensive)
                continue;
            }
            int st = r.world.blocks.get(x, y, z);
            if (block(st) != block) continue;
            r.blockEventsRun++;
            // doBlockEvent → triggerEvent; a true result is broadcast (ClientboundBlockEventPacket: the client animates)
            if (kind(st) == PISTON && triggerEvent(r, st, x, y, z, a, b)) r.emit(p, Journal.blockEvent(a, b, block));
        }
    }

    // =============================================================================================================
    // PistonBaseBlock
    // =============================================================================================================

    /** {@code PistonBaseBlock.neighborChanged}. */
    static void neighborChanged(Region r, int st, int x, int y, int z) {
        checkIfExtend(r, st, x, y, z);
    }

    /** {@code PistonBaseBlock.onPlace}: a newly placed piston (not a state change, no block entity here) checks. */
    static void onPlace(Region r, int st, int x, int y, int z, int old) {
        if (block(old) != block(st) && r.movingPistons.find(pack(x, y, z)) < 0) checkIfExtend(r, st, x, y, z);
    }

    /** {@code PistonBaseBlock.checkIfExtend}. */
    static void checkIfExtend(Region r, int st, int x, int y, int z) {
        int f = facing(st);
        boolean signal = getNeighborSignal(r, x, y, z, f);
        if (signal && !extended(st)) {
            if (resolve(r, x, y, z, f, true)) blockEvent(r, x, y, z, block(st), TRIGGER_EXTEND, f);
        } else if (!signal && extended(st)) {
            long p2 = pack(x + 2 * OX[f], y + 2 * OY[f], z + 2 * OZ[f]);
            int bs = state(r, p2);
            int id = TRIGGER_CONTRACT;
            if (kind(bs) == MOVING_PISTON && facing(bs) == f) {
                int e = movingEntity(r, p2);
                MovingPistons mp = r.movingPistons;
                // getProgress(0) is progressO
                if (e >= 0 && mp.extending(e) && (mp.progressO(e) < 1 || r.gameTime == mp.lastTicked(e) || r.handlingTick)) {
                    id = TRIGGER_DROP;
                }
            }
            blockEvent(r, x, y, z, block(st), id, f);
        }
    }

    /**
     * {@code PistonBaseBlock.getNeighborSignal}: a signal into any side but the front, or into the block above
     * (quasi-connectivity: any side of the space above except its bottom, which is the piston).
     */
    static boolean getNeighborSignal(Region r, int x, int y, int z, int facing) {
        for (int d = 0; d < 6; d++) {
            if (d != facing && Redstone.getSignal(r, x + OX[d], y + OY[d], z + OZ[d], d) > 0) return true;
        }
        if (Redstone.getSignal(r, x, y, z, DOWN) > 0) return true;
        int ay = y + 1;
        for (int d = 0; d < 6; d++) {
            if (d != DOWN && Redstone.getSignal(r, x + OX[d], ay + OY[d], z + OZ[d], d) > 0) return true;
        }
        return false;
    }

    /** {@code PistonBaseBlock.triggerEvent}: extend, retract, or retract dropping a block in motion. */
    static boolean triggerEvent(Region r, int st, int x, int y, int z, int id, int param) {
        int f = facing(st);
        boolean sticky = sticky(st);
        int extendedState = pistonState(sticky, f, true);
        boolean signal = getNeighborSignal(r, x, y, z, f);
        if (signal && (id == TRIGGER_CONTRACT || id == TRIGGER_DROP)) {
            Redstone.setBlock(r, x, y, z, extendedState, Redstone.UPDATE_CLIENTS);
            return false;
        }
        if (!signal && id == TRIGGER_EXTEND) return false;

        if (id == TRIGGER_EXTEND) {
            if (!moveBlocks(r, x, y, z, f, true, sticky)) return false;
            Redstone.setBlock(r, x, y, z, extendedState, F_67);
            r.pistonMoves++;
            return true;
        }

        MovingPistons mp = r.movingPistons;
        long head = pack(x + OX[f], y + OY[f], z + OZ[f]);
        int he = movingEntity(r, head);
        if (he >= 0) finalTick(r, he);
        if (mp.full()) { // no room for the retracting base's block entity: stay extended (see the class notes)
            r.pistonsBlocked++;
            return false;
        }
        int moving = movingState(sticky, f);
        Redstone.setBlock(r, x, y, z, moving, F_276);
        mp.add(pack(x, y, z), pistonState(sticky, param & 7, false), f, false, true, r.gameTime);
        Redstone.updateNeighborsAt(r, x, y, z, -1);
        Redstone.updateNeighbourShapes(r, moving, x, y, z, Redstone.UPDATE_CLIENTS, Redstone.UPDATE_LIMIT);
        int hx = x + OX[f], hy = y + OY[f], hz = z + OZ[f];
        if (sticky) {
            long p2 = pack(x + 2 * OX[f], y + 2 * OY[f], z + 2 * OZ[f]);
            int bs = state(r, p2);
            boolean dropped = false;
            if (kind(bs) == MOVING_PISTON) {
                int e = movingEntity(r, p2);
                if (e >= 0 && mp.facing(e) == f && mp.extending(e)) {
                    finalTick(r, e);
                    dropped = true;
                }
            }
            if (!dropped) {
                int bx = ScheduledTicks.x(p2), by = ScheduledTicks.y(p2), bz = ScheduledTicks.z(p2);
                if (id != TRIGGER_CONTRACT || isAir(bs) || !isPushable(r, bs, bx, by, bz, f ^ 1, false, f)
                        || BlockData.pushReaction(block(bs)) != BlockData.PUSH_NORMAL && kind(bs) != PISTON) {
                    Redstone.removeBlock(r, hx, hy, hz);
                } else {
                    moveBlocks(r, x, y, z, f, false, true);
                }
            }
        } else {
            Redstone.removeBlock(r, hx, hy, hz);
        }
        r.pistonMoves++;
        return true;
    }

    /** {@code PistonBaseBlock.isPushable(state, level, pos, movementDirection, allowDestroy, pistonFacing)}. */
    static boolean isPushable(Region r, int st, int x, int y, int z, int moveDir, boolean allowDestroy, int pistonFacing) {
        var blocks = r.world.blocks;
        int minY = blocks.minY(), maxY = blocks.maxYExclusive() - 1;
        if (y < minY || y > maxY || !blocks.inBounds(x, y, z)) return false; // build limits, world border
        if (isAir(st)) return true;
        int b = block(st);
        if (b == BlockId.OBSIDIAN || b == BlockId.CRYING_OBSIDIAN || b == BlockId.RESPAWN_ANCHOR
                || b == BlockId.REINFORCED_DEEPSLATE) {
            return false;
        }
        if (moveDir == DOWN && y == minY) return false;
        if (moveDir == UP && y == maxY) return false;
        if (kind(st) != PISTON) {
            if (BlockData.hardness(b) == -1.0f) return false;
            switch (BlockData.pushReaction(b)) {
                case BlockData.PUSH_BLOCK -> { return false; }
                case BlockData.PUSH_DESTROY -> { return allowDestroy; }
                case BlockData.PUSH_ONLY -> { return moveDir == pistonFacing; }
                default -> { }
            }
        } else if (extended(st)) {
            return false;
        }
        return BlockData.blockEntityType(b) < 0;
    }

    /** {@code PistonBaseBlock.moveBlocks}: turn the structure into moving blocks, then update around it. */
    static boolean moveBlocks(Region r, int x, int y, int z, int facing, boolean extending, boolean sticky) {
        PistonScratch s = r.piston;
        long headPos = pack(x + OX[facing], y + OY[facing], z + OZ[facing]);
        if (!extending && kind(state(r, headPos)) == PISTON_HEAD) {
            setBlock(r, headPos, AIR, F_276);
        }
        if (!resolve(r, x, y, z, facing, extending)) return false;
        int n = s.pushCount;
        if (r.movingPistons.size() + n + 1 > r.movingPistonCapacity) {
            r.pistonsBlocked++;
            return false;
        }
        // Map<BlockPos, BlockState> map = new HashMap<>(); list1 = the states, in toPush order.
        for (int k = 0; k < n; k++) {
            int bs = state(r, s.toPush[k]);
            s.mapPos[k] = s.toPush[k];
            s.mapState[k] = bs;
            s.mapLive[k] = true;
            s.pushedStates[k] = bs;
        }
        int direction = extending ? facing : facing ^ 1;
        int i = 0;
        for (int j = s.destroyCount - 1; j >= 0; j--) {
            long p = s.toDestroy[j];
            int bs = state(r, p);
            // PistonBaseBlock.moveBlocks: dropResources(state, level, pos, blockEntity), then air
            Loot.dropResources(r, ScheduledTicks.x(p), ScheduledTicks.y(p), ScheduledTicks.z(p), bs, Stacks.EMPTY);
            setBlock(r, p, AIR, F_18);
            s.states[i++] = bs;
        }
        for (int k = n - 1; k >= 0; k--) {
            long p = s.toPush[k];
            int bs = state(r, p);
            long dst = rel(p, direction, 1);
            mapRemove(s, n, dst);
            int moving = movingState(false, facing);
            setBlock(r, dst, moving, F_68);
            r.movingPistons.add(dst, s.pushedStates[k], facing, extending, false, r.gameTime);
            s.states[i++] = bs;
        }
        if (extending) {
            int moving = movingState(sticky, facing);
            mapRemove(s, n, headPos);
            setBlock(r, headPos, moving, F_68);
            r.movingPistons.add(headPos, headState(sticky, facing), facing, true, true, r.gameTime);
        }
        // for (BlockPos p : map.keySet()) level.setBlock(p, air, 82); then the shape updates, both in map order.
        hashMapOrder(s, n);
        for (int k = 0; k < n; k++) {
            int e = s.mapOrder[k];
            if (s.mapLive[e]) setBlock(r, s.mapPos[e], AIR, F_82);
        }
        for (int k = 0; k < n; k++) {
            int e = s.mapOrder[k];
            if (!s.mapLive[e]) continue;
            long p = s.mapPos[e];
            int px = ScheduledTicks.x(p), py = ScheduledTicks.y(p), pz = ScheduledTicks.z(p);
            Redstone.updateIndirectNeighbourShapes(r, s.mapState[e], px, py, pz, Redstone.UPDATE_CLIENTS, Redstone.UPDATE_LIMIT);
            Redstone.updateNeighbourShapes(r, AIR, px, py, pz, Redstone.UPDATE_CLIENTS, Redstone.UPDATE_LIMIT);
            Redstone.updateIndirectNeighbourShapes(r, AIR, px, py, pz, Redstone.UPDATE_CLIENTS, Redstone.UPDATE_LIMIT);
        }
        i = 0;
        for (int l = s.destroyCount - 1; l >= 0; l--) {
            int bs = s.states[i++];
            long p = s.toDestroy[l];
            int px = ScheduledTicks.x(p), py = ScheduledTicks.y(p), pz = ScheduledTicks.z(p);
            Redstone.affectNeighborsAfterRemoval(r, bs, px, py, pz, false);
            Redstone.updateIndirectNeighbourShapes(r, bs, px, py, pz, Redstone.UPDATE_CLIENTS, Redstone.UPDATE_LIMIT);
            Redstone.updateNeighborsAt(r, px, py, pz, -1);
        }
        for (int m = n - 1; m >= 0; m--) {
            long p = s.toPush[m];
            Redstone.updateNeighborsAt(r, ScheduledTicks.x(p), ScheduledTicks.y(p), ScheduledTicks.z(p), -1);
        }
        if (extending) {
            Redstone.updateNeighborsAt(r, ScheduledTicks.x(headPos), ScheduledTicks.y(headPos), ScheduledTicks.z(headPos), -1);
        }
        return true;
    }

    private static void setBlock(Region r, long p, int st, int flags) {
        Redstone.setBlock(r, ScheduledTicks.x(p), ScheduledTicks.y(p), ScheduledTicks.z(p), st, flags);
    }

    private static void mapRemove(PistonScratch s, int n, long p) {
        for (int k = 0; k < n; k++) if (s.mapLive[k] && s.mapPos[k] == p) s.mapLive[k] = false;
    }

    /**
     * Iteration order of a {@code HashMap<BlockPos, ?>} (default capacity 16; at most 12 entries, so it never resizes)
     * filled in {@code toPush} order: buckets {@code (h ^ h >>> 16) & 15} ascending, insertion order within a bucket.
     * Removals keep the order of the rest. Writes entry indices to {@code mapOrder}.
     */
    private static void hashMapOrder(PistonScratch s, int n) {
        for (int k = 0; k < n; k++) {
            long p = s.mapPos[k];
            int h = (ScheduledTicks.y(p) + ScheduledTicks.z(p) * 31) * 31 + ScheduledTicks.x(p); // Vec3i.hashCode
            s.mapBucket[k] = (h ^ (h >>> 16)) & 15;
            int j = k;
            while (j > 0 && s.mapBucket[s.mapOrder[j - 1]] > s.mapBucket[k]) {
                s.mapOrder[j] = s.mapOrder[j - 1];
                j--;
            }
            s.mapOrder[j] = k;
        }
    }

    // =============================================================================================================
    // PistonStructureResolver
    // =============================================================================================================

    private static int read(Region r, long p) {
        noteForeign(r, p);
        return state(r, p);
    }

    /** A structure position another region owns: the piston cannot move, and asks for the regions to merge. */
    private static void noteForeign(Region r, long p) {
        if (owned(r, p)) return;
        r.piston.foreign = true;
        r.requestMerge(r.world.ownerOfBlock(ScheduledTicks.x(p), ScheduledTicks.z(p)));
    }

    /**
     * {@code new PistonStructureResolver(level, pistonPos, pistonDirection, extending).resolve()}: fills
     * {@code toPush} and {@code toDestroy} of {@link Region#piston}. Also false when the structure (or where it
     * moves to) reaches another region.
     */
    static boolean resolve(Region r, int px, int py, int pz, int pistonDirection, boolean extending) {
        PistonScratch s = r.piston;
        s.pushCount = 0;
        s.destroyCount = 0;
        s.foreign = false;
        s.pistonPos = pack(px, py, pz);
        s.pistonDirection = pistonDirection;
        s.pushDirection = extending ? pistonDirection : pistonDirection ^ 1;
        long start = rel(s.pistonPos, pistonDirection, extending ? 1 : 2);
        int bs = read(r, start);
        boolean ok;
        if (!isPushable(r, bs, ScheduledTicks.x(start), ScheduledTicks.y(start), ScheduledTicks.z(start), s.pushDirection,
                false, pistonDirection)) {
            ok = extending && BlockData.pushReaction(block(bs)) == BlockData.PUSH_DESTROY && addDestroy(s, start);
        } else if (!addBlockLine(r, start, s.pushDirection)) {
            ok = false;
        } else {
            ok = true;
            for (int i = 0; i < s.pushCount; i++) {
                long p = s.toPush[i];
                if (isStickyBlock(read(r, p)) && !addBranchingBlocks(r, p)) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) return false;
        // Where the structure lands must be this region's too: the head, and every pushed block's destination.
        if (extending) noteForeign(r, rel(s.pistonPos, pistonDirection, 1));
        for (int i = 0; i < s.pushCount; i++) noteForeign(r, rel(s.toPush[i], s.pushDirection, 1));
        if (s.foreign) r.pistonsBlocked++;
        return !s.foreign;
    }

    private static boolean addDestroy(PistonScratch s, long p) {
        if (s.destroyCount == s.toDestroy.length) return false;
        s.toDestroy[s.destroyCount++] = p;
        return true;
    }

    private static boolean isStickyBlock(int st) {
        int b = block(st);
        return b == BlockId.SLIME_BLOCK || b == BlockId.HONEY_BLOCK;
    }

    /** {@code PistonStructureResolver.canStickToEachOther}: slime and honey do not stick to each other. */
    private static boolean canStickToEachOther(int a, int b) {
        int ba = block(a), bb = block(b);
        if (ba == BlockId.HONEY_BLOCK && bb == BlockId.SLIME_BLOCK) return false;
        if (ba == BlockId.SLIME_BLOCK && bb == BlockId.HONEY_BLOCK) return false;
        return isStickyBlock(a) || isStickyBlock(b);
    }

    private static int indexOf(PistonScratch s, long p) {
        for (int i = 0; i < s.pushCount; i++) if (s.toPush[i] == p) return i;
        return -1;
    }

    /** {@code PistonStructureResolver.addBlockLine(originPos, direction)}. */
    private static boolean addBlockLine(Region r, long origin, int direction) {
        PistonScratch s = r.piston;
        int max = PistonScratch.MAX_PUSH_DEPTH;
        int bs = read(r, origin);
        if (isAir(bs)) return true;
        if (!isPushable(r, bs, ScheduledTicks.x(origin), ScheduledTicks.y(origin), ScheduledTicks.z(origin), s.pushDirection,
                false, direction)) {
            return true;
        }
        if (origin == s.pistonPos) return true;
        if (indexOf(s, origin) >= 0) return true;
        int i = 1;
        if (i + s.pushCount > max) return false;
        int back = s.pushDirection ^ 1;
        while (isStickyBlock(bs)) {
            long bp = rel(origin, back, i);
            int old = bs;
            bs = read(r, bp);
            if (isAir(bs) || !canStickToEachOther(old, bs)
                    || !isPushable(r, bs, ScheduledTicks.x(bp), ScheduledTicks.y(bp), ScheduledTicks.z(bp), s.pushDirection,
                    false, back)
                    || bp == s.pistonPos) {
                break;
            }
            if (++i + s.pushCount > max) return false;
        }
        int j = 0;
        for (int k = i - 1; k >= 0; k--) {
            s.toPush[s.pushCount++] = rel(origin, back, k);
            j++;
        }
        int l = 1;
        while (true) {
            long bp1 = rel(origin, s.pushDirection, l);
            int idx = indexOf(s, bp1);
            if (idx > -1) {
                reorderListAtCollision(s, j, idx);
                for (int m = 0; m <= idx + j; m++) {
                    long bp2 = s.toPush[m];
                    if (isStickyBlock(read(r, bp2)) && !addBranchingBlocks(r, bp2)) return false;
                }
                return true;
            }
            bs = read(r, bp1);
            if (isAir(bs)) return true;
            if (!isPushable(r, bs, ScheduledTicks.x(bp1), ScheduledTicks.y(bp1), ScheduledTicks.z(bp1), s.pushDirection,
                    true, s.pushDirection) || bp1 == s.pistonPos) {
                return false;
            }
            if (BlockData.pushReaction(block(bs)) == BlockData.PUSH_DESTROY) {
                return addDestroy(s, bp1);
            }
            if (s.pushCount >= max) return false;
            s.toPush[s.pushCount++] = bp1;
            j++;
            l++;
        }
    }

    /**
     * {@code PistonStructureResolver.reorderListAtCollision(offsets, index)}: toPush becomes
     * {@code [0, index) + [size - offsets, size) + [index, size - offsets)}.
     */
    private static void reorderListAtCollision(PistonScratch s, int offsets, int index) {
        int size = s.pushCount, w = 0;
        for (int i = 0; i < index; i++) s.reorder[w++] = s.toPush[i];
        for (int i = size - offsets; i < size; i++) s.reorder[w++] = s.toPush[i];
        for (int i = index; i < size - offsets; i++) s.reorder[w++] = s.toPush[i];
        System.arraycopy(s.reorder, 0, s.toPush, 0, size);
    }

    /** {@code PistonStructureResolver.addBranchingBlocks}: sticky blocks pull in what sticks to their sides. */
    private static boolean addBranchingBlocks(Region r, long from) {
        PistonScratch s = r.piston;
        int bs = read(r, from);
        for (int d = 0; d < 6; d++) {
            if ((d >> 1) == (s.pushDirection >> 1)) continue; // same axis as the push
            long bp = rel(from, d, 1);
            int bs1 = read(r, bp);
            if (canStickToEachOther(bs1, bs) && !addBlockLine(r, bp, d)) return false;
        }
        return true;
    }

    // =============================================================================================================
    // PistonHeadBlock
    // =============================================================================================================

    /** {@code PistonHeadBlock.isFittingBase}. */
    static boolean isFittingBase(int head, int base) {
        return kind(base) == PISTON && sticky(base) == sticky(head) && extended(base) && facing(base) == facing(head);
    }

    /** {@code PistonHeadBlock.canSurvive}: the base behind it, or a moving piston facing the same way. */
    static boolean headCanSurviveOn(int head, int behind) {
        return isFittingBase(head, behind) || kind(behind) == MOVING_PISTON && facing(behind) == facing(head);
    }

    /** {@code PistonHeadBlock.neighborChanged}: pass the update to the base. */
    static void headNeighborChanged(Region r, int st, int x, int y, int z) {
        int d = facing(st) ^ 1;
        int bx = x + OX[d], by = y + OY[d], bz = z + OZ[d];
        if (headCanSurviveOn(st, Redstone.state(r, bx, by, bz))) Redstone.neighborChangedAt(r, bx, by, bz);
    }

    /** {@code PistonHeadBlock.affectNeighborsAfterRemoval}: a removed head takes its base with it. */
    static void headRemoved(Region r, int st, int x, int y, int z) {
        int d = facing(st) ^ 1;
        int bx = x + OX[d], by = y + OY[d], bz = z + OZ[d];
        if (isFittingBase(st, Redstone.state(r, bx, by, bz)) && r.world.ownerOfBlock(bx, bz) == r.id) {
            Redstone.destroyBlock(r, bx, by, bz, true, Redstone.UPDATE_LIMIT);
        }
    }

    // =============================================================================================================
    // PistonMovingBlockEntity
    // =============================================================================================================

    /**
     * {@code PistonMovingBlockEntity.finalTick}: finish the move at once (a piston retracting from under a block in
     * motion): the moved block (or air, for a retracting base) replaces the {@code moving_piston}.
     */
    static void finalTick(Region r, int e) {
        MovingPistons mp = r.movingPistons;
        if (mp.progressO(e) >= 2) return;
        long p = mp.pos(e);
        int moved = mp.moved(e);
        boolean source = mp.source(e);
        mp.setProgress(e, 2, 2);
        mp.remove(p);
        int x = ScheduledTicks.x(p), y = ScheduledTicks.y(p), z = ScheduledTicks.z(p);
        if (kind(r.world.blocks.get(x, y, z)) != MOVING_PISTON) return;
        int bs = source ? AIR : Redstone.updateFromNeighbourShapes(r, moved, x, y, z);
        Redstone.setBlock(r, x, y, z, bs, Redstone.UPDATE_ALL);
        Redstone.neighborChangedAt(r, x, y, z);
    }

    /**
     * {@code Level.tickBlockEntities} for moving pistons, in creation order, at {@code gameTime = epoch}:
     * {@code PistonMovingBlockEntity.tick}. Entities created during the loop tick from the next tick on.
     */
    static void tickBlockEntities(Region r) {
        MovingPistons mp = r.movingPistons;
        r.gameTime = r.epoch;
        int n = mp.size();
        for (int e = 0; e < n; e++) {
            if (mp.removed(e)) continue;
            mp.setLastTicked(e, r.gameTime);
            int progress = mp.progress(e);
            if (progress >= 2) { // progressO = progress >= 1.0: place the moved block
                mp.setProgress(e, progress, progress);
                long p = mp.pos(e);
                int moved = mp.moved(e);
                mp.remove(p);
                int x = ScheduledTicks.x(p), y = ScheduledTicks.y(p), z = ScheduledTicks.z(p);
                if (kind(r.world.blocks.get(x, y, z)) != MOVING_PISTON) continue;
                int bs = Redstone.updateFromNeighbourShapes(r, moved, x, y, z);
                if (isAir(bs)) {
                    Redstone.setBlock(r, x, y, z, moved, F_84);
                    // Block.updateOrDestroy(moved, air, level, pos, 3): it cannot stay here
                    if (moved != bs) Redstone.destroyBlock(r, x, y, z, true, Redstone.UPDATE_LIMIT);
                } else {
                    Redstone.setBlock(r, x, y, z, unwaterlogged(bs), F_67);
                    Redstone.neighborChangedAt(r, x, y, z);
                }
            } else { // moveCollidedEntities / moveStuckEntities: not ported
                mp.setProgress(e, progress + 1, progress);
            }
        }
        mp.compact();
    }
}
