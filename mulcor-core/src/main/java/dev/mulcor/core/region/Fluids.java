package dev.mulcor.core.region;

import static dev.mulcor.core.region.FluidStates.*;
import static dev.mulcor.core.region.RedstoneStates.DOWN;
import static dev.mulcor.core.region.RedstoneStates.EAST;
import static dev.mulcor.core.region.RedstoneStates.HORIZONTAL;
import static dev.mulcor.core.region.RedstoneStates.NORTH;
import static dev.mulcor.core.region.RedstoneStates.OX;
import static dev.mulcor.core.region.RedstoneStates.OY;
import static dev.mulcor.core.region.RedstoneStates.OZ;
import static dev.mulcor.core.region.RedstoneStates.SOUTH;
import static dev.mulcor.core.region.RedstoneStates.UP;
import static dev.mulcor.core.region.RedstoneStates.WEST;

import dev.mulcor.core.Rng;
import dev.mulcor.memory.ScheduledTicks;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Vanilla 26.2 fluids for one region, run by the thread ticking it: {@code FlowingFluid} ({@code tick},
 * {@code spread}, {@code getNewLiquid}, {@code getSpread} with its slope search, {@code spreadTo}), {@code WaterFluid},
 * {@code LavaFluid}, {@code LiquidBlock} (tick scheduling, lava meeting water) and waterlogged blocks, with the
 * fluid tick list ({@code ServerLevel.fluidTicks}: after the block ticks). Method names follow Mojang's.
 * The world is taken as an overworld-like dimension (not ultra-warm) with the default game rules (water converts to
 * sources, lava does not).
 *
 * <h2>Region borders (the one intended deviation)</h2>
 * Reads of foreign blocks see that region's live state. A spread into a position another region owns becomes a
 * {@link Msg#FLUID_SPREAD} message applied at the start of the next epoch at the sender's game time: the fluid
 * appears one tick late there, and the ticks it schedules keep vanilla's due time. The receiver re-checks that the
 * position still takes the fluid.
 *
 * <h2>Not ported</h2>
 * Item drops of blocks fluids wash away, lava's random ticks (fire), the lava spread delay's random factor uses a
 * deterministic hash instead of the level's random, fizz sounds and particles.
 */
final class Fluids {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    /** {@code WaterFluid.getTickDelay}; {@code LavaFluid.getTickDelay} outside ultra-warm dimensions. */
    static final int WATER_DELAY = 5, LAVA_DELAY = 30;
    /** {@code LiquidBlock.POSSIBLE_FLOW_DIRECTIONS}: the lava/water check looks at pos + opposite of each. */
    private static final int[] POSSIBLE_FLOW_DIRECTIONS = {DOWN, SOUTH, NORTH, EAST, WEST};
    private static final int OBSIDIAN = BlockData.defaultState(BlockId.OBSIDIAN);
    private static final int COBBLESTONE = BlockData.defaultState(BlockId.COBBLESTONE);
    private static final int BASALT = BlockData.defaultState(BlockId.BASALT);
    private static final int STONE = BlockData.defaultState(BlockId.STONE);

    private Fluids() {}

    // ---- per-fluid parameters (WaterFluid / LavaFluid) -----------------------------------------------------------

    static int tickDelay(int group) { return group == WATER_GROUP ? WATER_DELAY : LAVA_DELAY; }
    private static int dropOff(int group) { return group == WATER_GROUP ? 1 : 2; }
    private static int slopeFindDistance(int group) { return group == WATER_GROUP ? 4 : 2; }
    private static boolean canConvertToSource(int group) { return group == WATER_GROUP; }

    private static int state(Region r, int x, int y, int z) { return Redstone.state(r, x, y, z); }

    // ---- scheduling ------------------------------------------------------------------------------------------------

    /** {@code Level.scheduleTick(pos, fluid, delay)} on the fluid tick list (one pending tick per position and fluid). */
    static void scheduleTick(Region r, int x, int y, int z, int type, int delay) {
        long due = Math.max(r.gameTime + delay, r.fluidTicksDone ? r.epoch + 1 : r.epoch);
        long pos = ScheduledTicks.pack(x, y, z);
        if (!r.fluidTicks.schedule(due, Redstone.NORMAL, pos, type) && !r.fluidTicks.isScheduled(pos, type)) r.ticksDropped++;
    }

    /** A {@link Msg#FLUID_TICK} arrived: a fluid tick whose position changed owner, keeping its due epoch. */
    static void receiveTick(Region r, int x, int y, int z, int type, long due) {
        long pos = ScheduledTicks.pack(x, y, z);
        if (!r.fluidTicks.schedule(due, Redstone.NORMAL, pos, type) && !r.fluidTicks.isScheduled(pos, type)) r.ticksDropped++;
    }

    /**
     * {@code ServerLevel.tick} → {@code fluidTicks.tick(gameTime, 65536, this::tickFluid)}: every fluid tick due by now;
     * {@code tickFluid} runs it only if the fluid there is still the one it was scheduled for.
     */
    static void runTicks(Region r) {
        ScheduledTicks t = r.fluidTicks;
        r.gameTime = r.epoch;
        int budget = Redstone.MAX_TICKS_PER_TICK;
        while (t.peekDue() <= r.epoch && budget-- > 0) {
            long due = t.peekDue();
            int type = t.peekBlock();
            long pos = t.poll();
            int x = ScheduledTicks.x(pos), y = ScheduledTicks.y(pos), z = ScheduledTicks.z(pos);
            int owner = r.world.ownerOfBlock(x, z);
            if (owner != r.id) {
                MemorySegment m = r.begin(Msg.FLUID_TICK);
                m.set(I, Msg.A, x);
                m.set(I, Msg.B, y);
                m.set(I, Msg.C, z);
                m.set(I, Msg.E, type);
                m.set(L, Msg.DEADLINE, due);
                r.send(owner);
                continue;
            }
            int st = r.world.blocks.get(x, y, z);
            int fs = fluid(st);
            if (type(fs) != type) continue; // FluidState.is(fluid)
            r.fluidTicksRun++;
            tick(r, x, y, z, st, fs);
        }
        r.fluidTicksDone = true;
    }

    // ---- LiquidBlock and waterlogged blocks ----------------------------------------------------------------------

    /** {@code LiquidBlock.onPlace} / {@code neighborChanged}: schedule the fluid unless lava just solidified. */
    static void liquidChanged(Region r, int st, int x, int y, int z) {
        if (shouldSpreadLiquid(r, st, x, y, z)) {
            int fs = fluid(st);
            scheduleTick(r, x, y, z, type(fs), tickDelay(group(fs)));
        }
    }

    /** {@code LiquidBlock.updateShape}: a source here or at the neighbour reschedules this fluid. */
    static void liquidShapeChanged(Region r, int st, int x, int y, int z, int neighborState) {
        int fs = fluid(st);
        if (isSource(fs) || isSource(fluid(neighborState))) scheduleTick(r, x, y, z, type(fs), tickDelay(group(fs)));
    }

    /** {@code updateShape} of a waterlogged block (and kelp, seagrass): its water ticks again. */
    static void containerShapeChanged(Region r, int st, int x, int y, int z) {
        if (type(fluid(st)) == WATER) scheduleTick(r, x, y, z, WATER, WATER_DELAY);
    }

    /**
     * {@code LiquidBlock.shouldSpreadLiquid}: lava next to (or under) water becomes obsidian (source) or cobblestone;
     * lava on soul soil next to blue ice becomes basalt. Checks pos + UP, N, S, W, E.
     */
    private static boolean shouldSpreadLiquid(Region r, int st, int x, int y, int z) {
        int fs = fluid(st);
        if (group(fs) != LAVA_GROUP) return true;
        boolean soulSoil = BlockData.block(state(r, x, y - 1, z)) == BlockId.SOUL_SOIL;
        for (int d : POSSIBLE_FLOW_DIRECTIONS) {
            int o = d ^ 1;
            int ns = state(r, x + OX[o], y + OY[o], z + OZ[o]);
            if (group(fluid(ns)) == WATER_GROUP) {
                Redstone.setBlock(r, x, y, z, isSource(fs) ? OBSIDIAN : COBBLESTONE, Redstone.UPDATE_ALL);
                return false;
            }
            if (soulSoil && BlockData.block(ns) == BlockId.BLUE_ICE) {
                Redstone.setBlock(r, x, y, z, BASALT, Redstone.UPDATE_ALL);
                return false;
            }
        }
        return true;
    }

    // ---- FlowingFluid.tick ---------------------------------------------------------------------------------------

    /** {@code FlowingFluid.tick}: a flowing fluid re-derives its level (or dries up), then spreads. */
    static void tick(Region r, int x, int y, int z, int blockState, int fs) {
        int group = group(fs);
        if (!isSource(fs)) {
            int next = getNewLiquid(r, x, y, z, r.world.blocks.get(x, y, z), group);
            int delay = getSpreadDelay(r, x, y, z, fs, next, group);
            if (isEmpty(next)) {
                fs = next;
                blockState = RedstoneStates.AIR;
                Redstone.setBlock(r, x, y, z, blockState, Redstone.UPDATE_ALL);
            } else if (next != fs) {
                fs = next;
                blockState = legacyBlock(next);
                Redstone.setBlock(r, x, y, z, blockState, Redstone.UPDATE_ALL);
                scheduleTick(r, x, y, z, type(next), delay);
            }
        }
        spread(r, x, y, z, blockState, fs, group);
    }

    /**
     * {@code getSpreadDelay}: the tick delay; lava rising (not falling) waits 4 times as long 3 times in 4 (vanilla
     * draws from the level's random; this uses a deterministic hash of time and position).
     */
    private static int getSpreadDelay(Region r, int x, int y, int z, int current, int next, int group) {
        int delay = tickDelay(group);
        if (group == LAVA_GROUP && !isEmpty(current) && !isEmpty(next) && !falling(current) && !falling(next)
                && height(r, x, y, z, next) > height(r, x, y, z, current)
                && Rng.bounded(Rng.mix(r.gameTime, ScheduledTicks.pack(x, y, z), 0x4C415641L), 4) != 0) {
            delay *= 4;
        }
        return delay;
    }

    /** {@code FluidState.getHeight(level, pos)}: 1 under the same fluid, else {@code amount / 9}. */
    private static float height(Region r, int x, int y, int z, int fs) {
        return group(fluid(state(r, x, y + 1, z))) == group(fs) ? 1.0F : ownHeight(fs);
    }

    /** {@code FlowingFluid.getNewLiquid}: the fluid a position would hold given its neighbours. */
    static int getNewLiquid(Region r, int x, int y, int z, int blockState, int group) {
        int maxAmount = 0, sources = 0;
        for (int d : HORIZONTAL) {
            int ns = state(r, x + OX[d], y, z + OZ[d]);
            int nf = fluid(ns);
            if (group(nf) == group && CollisionFaces.canPassThroughWall(d, blockState, ns)) {
                if (isSource(nf)) sources++;
                maxAmount = Math.max(maxAmount, amount(nf));
            }
        }
        if (sources >= 2 && canConvertToSource(group)) {
            int below = state(r, x, y - 1, z);
            if (BlockData.is(below, BlockData.SOLID) || isSourceOf(fluid(below), group)) return source(group);
        }
        int above = state(r, x, y + 1, z);
        int af = fluid(above);
        if (!isEmpty(af) && group(af) == group && CollisionFaces.canPassThroughWall(UP, blockState, above)) {
            return flowing(group, 8, true);
        }
        int k = maxAmount - dropOff(group);
        return k <= 0 ? 0 : flowing(group, k, false);
    }

    // ---- spreading -----------------------------------------------------------------------------------------------

    /** {@code FlowingFluid.spread}: down if it can, then (a source, or not over a hole) to the sides. */
    private static void spread(Region r, int x, int y, int z, int blockState, int fs, int group) {
        if (isEmpty(fs)) return;
        int by = y - 1;
        int belowState = state(r, x, by, z);
        int belowFluid = fluid(belowState);
        if (canMaybePassThrough(blockState, DOWN, belowState, belowFluid, group)) {
            int next = getNewLiquid(r, x, by, z, belowState, group);
            int type = type(next);
            if (canBeReplacedWith(r, x, by, z, belowFluid, type, DOWN) && canHoldSpecificFluid(belowState, type)) {
                spreadTo(r, x, by, z, belowState, DOWN, next, group);
                if (sourceNeighborCount(r, x, y, z, group) >= 3) spreadToSides(r, x, y, z, fs, blockState, group);
                return;
            }
        }
        if (isSource(fs) || !isWaterHole(r, x, y, z, blockState, belowState, group)) {
            spreadToSides(r, x, y, z, fs, blockState, group);
        }
    }

    private static int sourceNeighborCount(Region r, int x, int y, int z, int group) {
        int n = 0;
        for (int d : HORIZONTAL) if (isSourceOf(fluid(state(r, x + OX[d], y, z + OZ[d])), group)) n++;
        return n;
    }

    /** {@code FlowingFluid.spreadToSides}: to the directions {@link #getSpread} picks, in {@code EnumMap} order. */
    private static void spreadToSides(Region r, int x, int y, int z, int fs, int blockState, int group) {
        int i = amount(fs) - dropOff(group);
        if (falling(fs)) i = 7;
        if (i <= 0) return;
        int n = getSpread(r, x, y, z, blockState, group);
        FluidScratch s = r.fluid;
        for (int k = 0; k < n; k++) {
            int d = s.spreadDir[k];
            int nx = x + OX[d], nz = z + OZ[d];
            spreadTo(r, nx, y, nz, state(r, nx, y, nz), d, s.spreadFluid[k], group);
        }
    }

    /**
     * {@code FlowingFluid.getSpread}: of the horizontal neighbours the fluid can flow into, those with the shortest
     * slope distance to a hole (0 for a hole next door), with the fluid each would get. Results go to
     * {@link Region#fluid} sorted by Direction ordinal (vanilla collects them in an {@code EnumMap}); returns the count.
     */
    private static int getSpread(Region r, int x, int y, int z, int blockState, int group) {
        FluidScratch s = r.fluid;
        int best = 1000, n = 0;
        for (int d : HORIZONTAL) {
            int nx = x + OX[d], nz = z + OZ[d];
            int ns = state(r, nx, y, nz);
            int nf = fluid(ns);
            if (!canMaybePassThrough(blockState, d, ns, nf, group)) continue;
            int next = getNewLiquid(r, nx, y, nz, ns, group);
            if (!canHoldSpecificFluid(ns, type(next))) continue;
            int j = isHole(r, nx, y, nz, ns, group) ? 0 : getSlopeDistance(r, nx, y, nz, 1, d ^ 1, ns, group);
            if (j < best) n = 0;
            if (j <= best) {
                if (canBeReplacedWith(r, nx, y, nz, nf, type(next), d)) {
                    s.spreadDir[n] = d;
                    s.spreadFluid[n] = next;
                    n++;
                }
                best = j;
            }
        }
        // EnumMap order: by Direction ordinal (N, S, W, E among horizontals).
        for (int i = 1; i < n; i++) {
            int d = s.spreadDir[i], f = s.spreadFluid[i], j = i;
            while (j > 0 && s.spreadDir[j - 1] > d) {
                s.spreadDir[j] = s.spreadDir[j - 1];
                s.spreadFluid[j] = s.spreadFluid[j - 1];
                j--;
            }
            s.spreadDir[j] = d;
            s.spreadFluid[j] = f;
        }
        return n;
    }

    /** {@code FlowingFluid.getSlopeDistance}: steps (up to the slope find distance) to the nearest hole, or 1000. */
    private static int getSlopeDistance(Region r, int x, int y, int z, int depth, int excluded, int blockState, int group) {
        int best = 1000;
        int flowing = flowingType(group);
        for (int d : HORIZONTAL) {
            if (d == excluded) continue;
            int nx = x + OX[d], nz = z + OZ[d];
            int ns = state(r, nx, y, nz);
            int nf = fluid(ns);
            if (canMaybePassThrough(blockState, d, ns, nf, group) && canHoldSpecificFluid(ns, flowing)) {
                if (isHole(r, nx, y, nz, ns, group)) return depth;
                if (depth < slopeFindDistance(group)) {
                    int j = getSlopeDistance(r, nx, y, nz, depth + 1, d ^ 1, ns, group);
                    if (j < best) best = j;
                }
            }
        }
        return best;
    }

    /** {@code SpreadContext.isHole} → {@code isWaterHole} with the block below. */
    private static boolean isHole(Region r, int x, int y, int z, int blockState, int group) {
        return isWaterHole(r, x, y, z, blockState, state(r, x, y - 1, z), group);
    }

    /** {@code FlowingFluid.isWaterHole}: the fluid can go down here (into its own fluid, or somewhere that holds it). */
    private static boolean isWaterHole(Region r, int x, int y, int z, int blockState, int belowState, int group) {
        if (!CollisionFaces.canPassThroughWall(DOWN, blockState, belowState)) return false;
        return group(fluid(belowState)) == group || canHoldFluid(belowState, flowingType(group));
    }

    /** {@code FlowingFluid.canMaybePassThrough}. */
    private static boolean canMaybePassThrough(int blockState, int dir, int spreadState, int spreadFluid, int group) {
        return !isSourceOf(spreadFluid, group) && holdsAnyFluid(spreadState)
                && CollisionFaces.canPassThroughWall(dir, blockState, spreadState);
    }

    /**
     * {@code FluidState.canBeReplacedWith(level, pos, fluid, dir)}: empty always; water only from above by a
     * non-water fluid ({@code WaterFluid}); lava by water once it is at least 4/9 high ({@code LavaFluid}).
     */
    private static boolean canBeReplacedWith(Region r, int x, int y, int z, int fs, int type, int dir) {
        return switch (group(fs)) {
            case WATER_GROUP -> dir == DOWN && groupOfType(type) != WATER_GROUP;
            case LAVA_GROUP -> height(r, x, y, z, fs) >= 0.44444445F && groupOfType(type) == WATER_GROUP;
            default -> true;
        };
    }

    /**
     * {@code FlowingFluid.spreadTo} ({@code LavaFluid.spreadTo}: lava falling onto water turns it to stone): fill a
     * container, or replace the block with the fluid's block. Another region's position gets a message.
     */
    private static void spreadTo(Region r, int x, int y, int z, int blockState, int dir, int fs, int group) {
        if (!r.world.blocks.inBounds(x, y, z)) return;
        int owner = r.world.ownerOfBlock(x, z);
        if (owner != r.id) {
            MemorySegment m = r.begin(Msg.FLUID_SPREAD);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(I, Msg.D, dir);
            m.set(I, Msg.E, fs);
            m.set(I, Msg.F, group);
            m.set(L, Msg.DEADLINE, r.gameTime);
            r.send(owner);
            r.crossUpdates++;
            return;
        }
        spreadToOwned(r, x, y, z, blockState, dir, fs, group);
    }

    private static void spreadToOwned(Region r, int x, int y, int z, int blockState, int dir, int fs, int group) {
        if (group == LAVA_GROUP && dir == DOWN && group(fluid(blockState)) == WATER_GROUP) {
            if (isLiquidBlock(blockState)) Redstone.setBlock(r, x, y, z, STONE, Redstone.UPDATE_ALL);
            return; // fizz
        }
        if (container(blockState) != NOT_CONTAINER) {
            // LiquidBlockContainer.placeLiquid (SimpleWaterloggedBlock: a water source into a dry block)
            if (container(blockState) == WATERLOGGABLE && isEmpty(fluid(blockState)) && type(fs) == WATER) {
                Redstone.setBlock(r, x, y, z, waterlogged(blockState), Redstone.UPDATE_ALL);
                scheduleTick(r, x, y, z, WATER, WATER_DELAY);
            }
            return;
        }
        // beforeDestroyingBlock (drops): not ported
        Redstone.setBlock(r, x, y, z, legacyBlock(fs), Redstone.UPDATE_ALL);
    }

    /**
     * A {@link Msg#FLUID_SPREAD} arrived: run the spread at the sender's game time if the position still takes the
     * fluid (it may have changed in the epoch the message travelled).
     */
    static void receiveSpread(Region r, int x, int y, int z, int dir, int fs, int group, long gameTime) {
        if (!r.world.blocks.inBounds(x, y, z) || r.world.ownerOfBlock(x, z) != r.id) return;
        int st = r.world.blocks.get(x, y, z);
        int cur = fluid(st);
        if (isSourceOf(cur, group) || !holdsAnyFluid(st) || !canHoldSpecificFluid(st, type(fs))
                || !canBeReplacedWith(r, x, y, z, cur, type(fs), dir)) {
            return;
        }
        long saved = r.gameTime;
        r.gameTime = gameTime;
        spreadToOwned(r, x, y, z, st, dir, fs, group);
        r.gameTime = saved;
    }
}
