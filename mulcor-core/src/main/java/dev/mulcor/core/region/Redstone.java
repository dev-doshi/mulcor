package dev.mulcor.core.region;

import static dev.mulcor.core.region.RedstoneStates.*;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.ScheduledTicks;
import dev.mulcor.registry.BlockData;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Vanilla 26.2 block updates and redstone for one region, run by the thread ticking it: a port of
 * {@code Level.setBlock}, {@code LevelChunk.setBlockState}, {@code CollectingNeighborUpdater} (see
 * {@link NeighborUpdater}), shape updates, {@code SignalGetter}, {@code RedStoneWireBlock} with
 * {@code DefaultRedstoneWireEvaluator}, {@code DiodeBlock}/{@code RepeaterBlock}, {@code RedstoneTorchBlock} and
 * {@code RedstoneWallTorchBlock}, {@code ComparatorBlock}, {@code ObserverBlock}, {@code LeverBlock},
 * {@code ButtonBlock}, {@code RedstoneLampBlock}, {@code TntBlock}, {@code FallingBlock} and {@code LevelTicks}
 * ordering; pistons and fluids hook in from here ({@link Pistons}, {@link Fluids}). Method names follow Mojang's; each cites the vanilla method it reproduces.
 *
 * <h2>Time</h2>
 * {@code r.gameTime} is vanilla's {@code level.getGameTime()} as seen by the code running: the epoch during block
 * ticks and entity ticks, {@code epoch - 1} for inputs (vanilla runs commands and player packets between ticks), and
 * the sender's game time for an update that crossed a region border. A tick scheduled with delay {@code d} is due at
 * {@code gameTime + d}, so a repeater fed from another region fires on the same epoch it would inside one region.
 *
 * <h2>Region borders (the one intended deviation)</h2>
 * Updates run exactly as in vanilla, depth first, while they stay in the region. A neighbour or shape update
 * addressed to a position another region owns becomes a {@link Msg#NEIGHBOR_UPDATE} / {@link Msg#SHAPE_UPDATE}
 * message and runs at the start of the next epoch (vanilla would run it in the same tick). Reads of foreign blocks
 * see that region's live state ({@link BlockStorage#getShared}).
 *
 * <h2>Not ported</h2>
 * Pressure plates, tripwires, daylight detectors, targets and other signal sources (their signal is 0);
 * comparator inputs whose value lives in a block entity (containers, jukebox, lectern, ...: they read 0) or an
 * entity (item frames); buttons pressed by arrows; {@code updateShape} of blocks outside this set (fences, walls,
 * ...).
 */
final class Redstone {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

    // Block.UPDATE_* flags (Level.setBlock).
    static final int UPDATE_NEIGHBORS = 1, UPDATE_CLIENTS = 2, UPDATE_KNOWN_SHAPE = 16, UPDATE_SUPPRESS_DROPS = 32,
            UPDATE_MOVE_BY_PISTON = 64, UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE = 128, UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS = 256,
            UPDATE_SKIP_ON_PLACE = 512, UPDATE_ALL = 3;
    /** {@code Block.UPDATE_LIMIT}: the recursion budget of shape updates. */
    static final int UPDATE_LIMIT = 512;
    /** {@code TickPriority}. */
    static final int EXTREMELY_HIGH = -3, VERY_HIGH = -2, HIGH = -1, NORMAL = 0;
    /** {@code RedstoneTorchBlock}: TOGGLE_DELAY, MAX_RECENT_TOGGLES, RECENT_TOGGLE_TIMER, RESTART_DELAY. */
    static final int TORCH_DELAY = 2, MAX_RECENT_TOGGLES = 8, RECENT_TOGGLE_TIMER = 60, RESTART_DELAY = 160;
    /** {@code RedstoneLampBlock}: turn-off delay. {@code FallingBlock.getDelayAfterPlace()}. PrimedTnt fuse. */
    static final int LAMP_DELAY = 4, FALL_DELAY = 2, TNT_FUSE = 80;
    /** {@code ComparatorBlock.getDelay}; the observer's pulse length and delay ({@code ObserverBlock}: 2). */
    static final int COMPARATOR_DELAY = 2, OBSERVER_DELAY = 2;
    /** {@code LevelTicks}: most scheduled ticks run per game tick ({@code ServerLevel.tick}: 65536). */
    static final int MAX_TICKS_PER_TICK = 65536;
    /** SupportType ordinals for {@link #sturdy}. */
    static final int FULL = 0, CENTER = 1, RIGID = 2;

    private Redstone() {}

    // =============================================================================================================
    // Reads
    // =============================================================================================================

    static int state(Region r, int x, int y, int z) {
        return r.world.blocks.getShared(x, y, z);
    }

    /** {@code BlockState.isFaceSturdy(level, pos, dir, type)}. */
    static boolean sturdy(int state, int dir, int type) {
        return BlockData.sturdy(state, dir, type);
    }

    // =============================================================================================================
    // Level.setBlock / LevelChunk.setBlockState
    // =============================================================================================================

    /** {@code Level.setBlock(pos, state, flags)} = {@code setBlock(pos, state, flags, 512)}. */
    static boolean setBlock(Region r, int x, int y, int z, int state, int flags) {
        return setBlock(r, x, y, z, state, flags, UPDATE_LIMIT);
    }

    /**
     * {@code Level.setBlock(pos, state, flags, recursionLeft)} with {@code LevelChunk.setBlockState} inlined:
     * <ol>
     *   <li>write the state (no change → false);</li>
     *   <li>if the block type changed and {@code flags & 1} (or a piston moved it): old block's
     *       {@code affectNeighborsAfterRemoval};</li>
     *   <li>if the block type is still there and not {@code flags & 512}: new state's {@code onPlace} (on every state
     *       change, not only block changes);</li>
     *   <li>if the state is still the one set: {@code flags & 1} → {@code updateNeighborsAt};
     *       not {@code flags & 16} → shape updates with {@code flags & ~34}: old state's indirect ones, the new
     *       state's direct ones, the new state's indirect ones.</li>
     * </ol>
     * Only for positions this region owns.
     */
    static boolean setBlock(Region r, int x, int y, int z, int state, int flags, int recursionLeft) {
        int old = r.setBlock(x, y, z, state);
        if (old == BlockStorage.FAILED || old == state) return false;
        r.redstoneChanges++;
        boolean moved = (flags & UPDATE_MOVE_BY_PISTON) != 0;
        // LevelChunk.setBlockState: the old block's block entity goes when the block type changes.
        if (kind(old) == COMPARATOR && block(old) != block(state)) r.comparators.remove(ScheduledTicks.pack(x, y, z));
        if (kind(old) == MOVING_PISTON && block(old) != block(state)) r.movingPistons.remove(ScheduledTicks.pack(x, y, z));
        Affinity.onBlockWrite(r, x, z, old, state);
        if (block(old) != block(state) && ((flags & UPDATE_NEIGHBORS) != 0 || moved)) {
            affectNeighborsAfterRemoval(r, old, x, y, z, moved);
        }
        int now = r.world.blocks.get(x, y, z);
        if (block(now) != block(state)) return false;
        if ((flags & UPDATE_SKIP_ON_PLACE) == 0) onPlace(r, state, x, y, z, old, moved);
        if (r.world.blocks.get(x, y, z) != state) return true;
        if ((flags & UPDATE_NEIGHBORS) != 0) updateNeighborsAt(r, x, y, z, -1);
        if ((flags & UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
            int f = flags & ~(UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS);
            updateIndirectNeighbourShapes(r, old, x, y, z, f, recursionLeft - 1);
            updateNeighbourShapes(r, state, x, y, z, f, recursionLeft - 1);
            updateIndirectNeighbourShapes(r, state, x, y, z, f, recursionLeft - 1);
        }
        return true;
    }

    /** {@code Level.removeBlock(pos, false)}: {@code setBlock(pos, fluid's legacy block, 3)}. */
    static boolean removeBlock(Region r, int x, int y, int z) {
        return setBlock(r, x, y, z, AIR, UPDATE_ALL);
    }

    /**
     * {@code Level.destroyBlock(pos, drop, null, recursionLeft)}: the block's loot pops (no tool) if {@code drop}, then
     * the block becomes its fluid's legacy block.
     */
    static boolean destroyBlock(Region r, int x, int y, int z, boolean drop, int recursionLeft) {
        int st = state(r, x, y, z);
        if (isAir(st)) return false;
        if (drop) Loot.dropResources(r, x, y, z, st, Stacks.EMPTY);
        return setBlock(r, x, y, z, FluidStates.legacyBlock(FluidStates.fluid(st)), UPDATE_ALL, recursionLeft);
    }

    /**
     * {@code /setblock pos state} (replace mode, not strict): {@code SetBlockCommand.setBlock}. The state is fitted
     * to its neighbours ({@code Block.updateFromNeighbourShapes}; the caller's explicit properties are assumed to
     * agree), set with flags {@code 2 | 256}, then {@code ServerLevel.updateNeighboursOnBlockSet}: the old block's
     * removal side effects if the block type changed, then a neighbour update around the position. This is what
     * {@link Input#SET_BLOCK} does.
     */
    static boolean commandSetBlock(Region r, int x, int y, int z, int state) {
        int old = state(r, x, y, z);
        int s = updateFromNeighbourShapes(r, state, x, y, z);
        if (!setBlock(r, x, y, z, s, UPDATE_CLIENTS | UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS)) return false;
        int placed = state(r, x, y, z);
        if (block(placed) != block(old)) affectNeighborsAfterRemoval(r, old, x, y, z, false);
        updateNeighborsAt(r, x, y, z, -1);
        return true;
    }

    // =============================================================================================================
    // Neighbour updates
    // =============================================================================================================

    /** {@code Level.updateNeighborsAt(pos)} ({@code skip = -1}) / {@code updateNeighborsAtExceptFromFacing}. */
    static void updateNeighborsAt(Region r, int x, int y, int z, int skip) {
        r.updater.add(r, NeighborUpdater.MULTI, x, y, z, skip, NeighborUpdater.firstIndex(skip), 0, 0);
    }

    /** {@code Level.neighborChanged(pos, block)}: one position. */
    static void neighborChangedAt(Region r, int x, int y, int z) {
        r.updater.add(r, NeighborUpdater.SIMPLE, x, y, z, 0, 0, 0, 0);
    }

    /** {@code NeighborUpdater.executeUpdate}: run a neighbour update, or send it to the owning region. */
    static void executeUpdate(Region r, int x, int y, int z) {
        BlockStorage b = r.world.blocks;
        if (!b.inBounds(x, y, z)) return; // void air: no reaction
        int owner = r.world.ownerOfBlock(x, z);
        if (owner != r.id) {
            MemorySegment m = r.begin(Msg.NEIGHBOR_UPDATE);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(L, Msg.DEADLINE, r.gameTime);
            r.send(owner);
            r.crossUpdates++;
            return;
        }
        r.blockUpdates++;
        neighborChanged(r, b.get(x, y, z), x, y, z);
    }

    /** A {@link Msg#NEIGHBOR_UPDATE} arrived: run it as a top-level update at the sender's game time. */
    static void receiveNeighborUpdate(Region r, int x, int y, int z, long gameTime) {
        long saved = r.gameTime;
        r.gameTime = gameTime;
        neighborChangedAt(r, x, y, z);
        r.gameTime = saved;
    }

    /** {@code BlockState.handleNeighborChanged} → {@code Block.neighborChanged}. */
    private static void neighborChanged(Region r, int st, int x, int y, int z) {
        switch (kind(st)) {
            case WIRE -> {
                // RedStoneWireBlock.neighborChanged
                if (wireCanSurvive(r, x, y, z)) updatePowerStrength(r, x, y, z, st);
                else removeBlock(r, x, y, z);
            }
            case REPEATER, COMPARATOR -> {
                // DiodeBlock.neighborChanged
                if (sturdy(state(r, x, y - 1, z), UP, RIGID)) {
                    checkTickOnNeighbor(r, st, x, y, z);
                } else {
                    removeBlock(r, x, y, z);
                    for (int d = 0; d < 6; d++) updateNeighborsAt(r, x + OX[d], y + OY[d], z + OZ[d], -1);
                }
            }
            case TORCH, WALL_TORCH -> {
                // RedstoneTorchBlock.neighborChanged
                if (lit(st) == torchHasNeighborSignal(r, st, x, y, z) && !willTickThisTick(r, x, y, z, block(st))) {
                    scheduleTick(r, x, y, z, block(st), TORCH_DELAY, NORMAL);
                }
            }
            case LAMP -> {
                // RedstoneLampBlock.neighborChanged
                boolean lit = lit(st);
                if (lit != hasNeighborSignal(r, x, y, z)) {
                    if (lit) scheduleTick(r, x, y, z, block(st), LAMP_DELAY, NORMAL);
                    else setBlock(r, x, y, z, LAMP_ON, UPDATE_CLIENTS);
                }
            }
            case TNT -> {
                // TntBlock.neighborChanged
                if (hasNeighborSignal(r, x, y, z)) primeAndRemove(r, x, y, z);
            }
            case PISTON -> Pistons.neighborChanged(r, st, x, y, z);
            case LIQUID -> Fluids.liquidChanged(r, st, x, y, z); // LiquidBlock.neighborChanged
            case PISTON_HEAD -> Pistons.headNeighborChanged(r, st, x, y, z);
            default -> {}
        }
    }

    // =============================================================================================================
    // onPlace / affectNeighborsAfterRemoval
    // =============================================================================================================

    /** {@code BlockState.onPlace(level, pos, oldState, movedByPiston)}. */
    private static void onPlace(Region r, int st, int x, int y, int z, int old, boolean moved) {
        switch (kind(st)) {
            case WIRE -> {
                // RedStoneWireBlock.onPlace: only when the block (not just its state) changed.
                if (isWire(old)) return;
                updatePowerStrength(r, x, y, z, st);
                updateNeighborsAt(r, x, y + 1, z, -1); // Plane.VERTICAL: UP, DOWN
                updateNeighborsAt(r, x, y - 1, z, -1);
                updateNeighborsOfNeighboringWires(r, x, y, z);
            }
            case REPEATER, COMPARATOR -> updateNeighborsInFront(r, st, x, y, z); // DiodeBlock.onPlace
            case OBSERVER -> {
                // ObserverBlock.onPlace: a block placed powered with no pending tick turns off at once
                if (kind(old) != OBSERVER && powered(st) && !r.blockTicks.isScheduled(ScheduledTicks.pack(x, y, z), block(st))) {
                    int off = withPowered(st, false);
                    setBlock(r, x, y, z, off, UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE);
                    updateNeighborsInFront(r, off, x, y, z);
                }
            }
            case TORCH, WALL_TORCH -> notifyNeighbors(r, x, y, z); // RedstoneTorchBlock.onPlace
            case TNT -> {
                // TntBlock.onPlace
                if (kind(old) != TNT && hasNeighborSignal(r, x, y, z)) primeAndRemove(r, x, y, z);
            }
            case FALLING -> scheduleTick(r, x, y, z, block(st), FALL_DELAY, NORMAL); // FallingBlock.onPlace
            case PISTON -> Pistons.onPlace(r, st, x, y, z, old);
            case LIQUID -> Fluids.liquidChanged(r, st, x, y, z); // LiquidBlock.onPlace
            default -> {}
        }
    }

    /** {@code BlockState.affectNeighborsAfterRemoval(level, pos, movedByPiston)} of the removed state. */
    static void affectNeighborsAfterRemoval(Region r, int old, int x, int y, int z, boolean moved) {
        if (kind(old) == OBSERVER) {
            // ObserverBlock.affectNeighborsAfterRemoval (no piston check): a pulse in progress ends
            if (powered(old) && r.blockTicks.isScheduled(ScheduledTicks.pack(x, y, z), block(old))) {
                updateNeighborsInFront(r, old, x, y, z);
            }
            return;
        }
        if (moved) return; // wire, diode, torch, lever and button all return early when moved by a piston
        switch (kind(old)) {
            case WIRE -> {
                // RedStoneWireBlock.affectNeighborsAfterRemoval
                for (int d = 0; d < 6; d++) updateNeighborsAt(r, x + OX[d], y + OY[d], z + OZ[d], -1);
                updatePowerStrength(r, x, y, z, old);
                updateNeighborsOfNeighboringWires(r, x, y, z);
            }
            case REPEATER, COMPARATOR -> updateNeighborsInFront(r, old, x, y, z);
            case TORCH, WALL_TORCH -> notifyNeighbors(r, x, y, z);
            case LEVER, BUTTON -> {
                // LeverBlock / ButtonBlock.affectNeighborsAfterRemoval
                if (powered(old)) updateAttachedNeighbours(r, old, x, y, z);
            }
            case PISTON_HEAD -> Pistons.headRemoved(r, old, x, y, z);
            default -> {}
        }
    }

    // =============================================================================================================
    // Shape updates
    // =============================================================================================================

    /** {@code BlockStateBase.updateNeighbourShapes}: each neighbour in {@code UPDATE_SHAPE_ORDER} W, E, N, S, D, U. */
    private static final int[] UPDATE_SHAPE_ORDER = {WEST, EAST, NORTH, SOUTH, DOWN, UP};

    static void updateNeighbourShapes(Region r, int st, int x, int y, int z, int flags, int recursionLeft) {
        for (int dir : UPDATE_SHAPE_ORDER) {
            neighborShapeChanged(r, dir ^ 1, x + OX[dir], y + OY[dir], z + OZ[dir], st, flags, recursionLeft);
        }
    }

    /** {@code LevelAccessor.neighborShapeChanged(direction, pos, neighborPos = pos + direction, neighborState, ...)}. */
    private static void neighborShapeChanged(Region r, int direction, int x, int y, int z, int neighborState, int flags,
                                             int recursionLeft) {
        r.updater.add(r, NeighborUpdater.SHAPE, x, y, z, direction, neighborState, flags, recursionLeft);
    }

    /** {@code RedStoneWireBlock.updateIndirectNeighbourShapes} (a no-op for every other block). */
    static void updateIndirectNeighbourShapes(Region r, int st, int x, int y, int z, int flags, int recursionLeft) {
        if (!isWire(st)) return;
        for (int dir : HORIZONTAL) {
            if (wireSide(st, dir) == NONE) continue;
            int nx = x + OX[dir], nz = z + OZ[dir];
            if (isWire(state(r, nx, y, nz))) continue;
            if (isWire(state(r, nx, y - 1, nz))) {
                neighborShapeChanged(r, dir ^ 1, nx, y - 1, nz, state(r, x, y - 1, z), flags, recursionLeft);
            }
            if (isWire(state(r, nx, y + 1, nz))) {
                neighborShapeChanged(r, dir ^ 1, nx, y + 1, nz, state(r, x, y + 1, z), flags, recursionLeft);
            }
        }
    }

    /**
     * {@code NeighborUpdater.executeShapeUpdate}: {@code new = old.updateShape(...)}, then
     * {@code Block.updateOrDestroy}. Sent to the owner if the position is another region's.
     */
    static void executeShapeUpdate(Region r, int x, int y, int z, int direction, int neighborState, int flags,
                                   int recursionLeft) {
        BlockStorage b = r.world.blocks;
        if (!b.inBounds(x, y, z)) return;
        int owner = r.world.ownerOfBlock(x, z);
        if (owner != r.id) {
            MemorySegment m = r.begin(Msg.SHAPE_UPDATE);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(I, Msg.D, direction);
            m.set(I, Msg.E, neighborState);
            m.set(I, Msg.F, flags);
            m.set(L, Msg.WORD, recursionLeft);
            m.set(L, Msg.DEADLINE, r.gameTime);
            r.send(owner);
            r.crossUpdates++;
            return;
        }
        int old = b.get(x, y, z);
        if ((flags & UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE) != 0 && isWire(old)) return;
        int updated = updateShape(r, old, x, y, z, direction, neighborState);
        // Block.updateOrDestroy
        if (updated != old) {
            if (isAir(updated)) destroyBlock(r, x, y, z, (flags & UPDATE_SUPPRESS_DROPS) == 0, recursionLeft);
            else setBlock(r, x, y, z, updated, flags & ~UPDATE_SUPPRESS_DROPS, recursionLeft);
        }
    }

    /** A {@link Msg#SHAPE_UPDATE} arrived: run it as a top-level update at the sender's game time. */
    static void receiveShapeUpdate(Region r, int x, int y, int z, int direction, int neighborState, int flags,
                                   int recursionLeft, long gameTime) {
        long saved = r.gameTime;
        r.gameTime = gameTime;
        neighborShapeChanged(r, direction, x, y, z, neighborState, flags, recursionLeft);
        r.gameTime = saved;
    }

    /** {@code Block.updateFromNeighbourShapes}: fit a state to its six neighbours (commands, placement). */
    static int updateFromNeighbourShapes(Region r, int st, int x, int y, int z) {
        for (int dir : UPDATE_SHAPE_ORDER) {
            int nx = x + OX[dir], ny = y + OY[dir], nz = z + OZ[dir];
            st = updateShape(r, st, x, y, z, dir, state(r, nx, ny, nz));
        }
        return st;
    }

    /** {@code BlockState.updateShape(level, ticks, pos, direction, neighborPos, neighborState, random)}. */
    private static int updateShape(Region r, int st, int x, int y, int z, int dir, int neighborState) {
        switch (kind(st)) {
            case WIRE -> {
                // RedStoneWireBlock.updateShape
                if (dir == DOWN) return wireCanSurviveOn(neighborState) ? st : AIR;
                if (dir == UP) return getConnectionState(r, st, x, y, z);
                int side = getConnectingSide(r, x, y, z, dir, !isConductor(state(r, x, y + 1, z)));
                boolean connected = side != NONE;
                if (connected == (wireSide(st, dir) != NONE) && !isCross(st)) return withWireSide(st, dir, side);
                return getConnectionState(r, withWireSide(crossState(wirePower(st)), dir, side), x, y, z);
            }
            case REPEATER -> {
                // RepeaterBlock.updateShape (the DOWN case is DiodeBlock's canSurviveOn: RIGID top face)
                if (dir == DOWN && !sturdy(neighborState, UP, RIGID)) return AIR;
                if ((dir >> 1) != (facing(st) >> 1)) { // direction.getAxis() != FACING.getAxis()
                    return withRepeater(st, isLocked(r, st, x, y, z), powered(st));
                }
                return st;
            }
            case COMPARATOR -> {
                // DiodeBlock.updateShape
                return dir == DOWN && !sturdy(neighborState, UP, RIGID) ? AIR : st;
            }
            case OBSERVER -> {
                // ObserverBlock.updateShape: a change on the observed side starts a pulse (startSignal)
                if (facing(st) == dir && !powered(st)) scheduleTick(r, x, y, z, block(st), OBSERVER_DELAY, NORMAL);
                return st;
            }
            case LEVER, BUTTON -> {
                // FaceAttachedHorizontalDirectionalBlock.updateShape: the support is opposite the connected side
                int c = connected(st);
                return dir == (c ^ 1) && !sturdy(neighborState, c, FULL) ? AIR : st;
            }
            case LIQUID -> {
                Fluids.liquidShapeChanged(r, st, x, y, z, neighborState); // LiquidBlock.updateShape
                return st;
            }
            case PISTON_HEAD -> {
                // PistonHeadBlock.updateShape: the side toward the base changed and it no longer holds the head
                return dir == (facing(st) ^ 1) && !Pistons.headCanSurviveOn(st, neighborState) ? AIR : st;
            }
            case TORCH -> {
                // BaseTorchBlock.updateShape: canSurvive = canSupportCenter(below, UP)
                return dir == DOWN && !sturdy(neighborState, UP, CENTER) ? AIR : st;
            }
            case WALL_TORCH -> {
                // WallTorchBlock.updateShape: the supporting block is behind (FACING.opposite)
                return dir == (facing(st) ^ 1) && !sturdy(neighborState, facing(st), FULL) ? AIR : st;
            }
            case FALLING -> {
                // FallingBlock.updateShape: re-check falling after a delay, from any side
                scheduleTick(r, x, y, z, block(st), FALL_DELAY, NORMAL);
                return st;
            }
            default -> {
                // Waterlogged blocks (and kelp, seagrass) re-tick their water on every shape update
                if (FluidStates.container(st) != FluidStates.NOT_CONTAINER) Fluids.containerShapeChanged(r, st, x, y, z);
                return st;
            }
        }
    }

    // =============================================================================================================
    // Signals (SignalGetter)
    // =============================================================================================================

    /** {@code BlockState.getSignal(level, pos, dir)}: weak signal of {@code st} at pos toward the block at pos - dir. */
    static int blockSignal(Region r, int st, int x, int y, int z, int dir) {
        return switch (kind(st)) {
            case WIRE -> wireSignal(r, st, x, y, z, dir);
            case REPEATER -> powered(st) && facing(st) == dir ? 15 : 0;
            case COMPARATOR -> powered(st) && facing(st) == dir ? comparatorOutput(r, x, y, z) : 0; // DiodeBlock.getSignal
            case OBSERVER -> powered(st) && facing(st) == dir ? 15 : 0; // ObserverBlock: = getDirectSignal
            case LEVER, BUTTON -> powered(st) ? 15 : 0;
            case TORCH -> lit(st) && dir != UP ? 15 : 0;
            case WALL_TORCH -> lit(st) && facing(st) != dir ? 15 : 0;
            case REDSTONE_BLOCK -> 15;
            default -> 0;
        };
    }

    /** {@code BlockState.getDirectSignal(level, pos, dir)}: strong signal. */
    static int blockDirectSignal(Region r, int st, int x, int y, int z, int dir) {
        return switch (kind(st)) {
            case WIRE -> wireSignal(r, st, x, y, z, dir); // RedStoneWireBlock: !shouldSignal ? 0 : getSignal
            case REPEATER, COMPARATOR, OBSERVER -> blockSignal(r, st, x, y, z, dir); // DiodeBlock, ObserverBlock
            case LEVER, BUTTON -> powered(st) && connected(st) == dir ? 15 : 0;
            case TORCH, WALL_TORCH -> dir == DOWN ? blockSignal(r, st, x, y, z, dir) : 0;
            default -> 0;
        };
    }

    /** {@code SignalGetter.getSignal(pos, dir)}: weak signal, or strong power a conductor carries. */
    static int getSignal(Region r, int x, int y, int z, int dir) {
        int st = state(r, x, y, z);
        int s = blockSignal(r, st, x, y, z, dir);
        return isConductor(st) ? Math.max(s, getDirectSignalTo(r, x, y, z)) : s;
    }

    /** {@code SignalGetter.getDirectSignalTo(pos)}: strongest strong signal into pos (D, U, N, S, W, E). */
    static int getDirectSignalTo(Region r, int x, int y, int z) {
        int best = 0;
        for (int d = 0; d < 6; d++) {
            int nx = x + OX[d], ny = y + OY[d], nz = z + OZ[d];
            best = Math.max(best, blockDirectSignal(r, state(r, nx, ny, nz), nx, ny, nz, d));
            if (best >= 15) return best;
        }
        return best;
    }

    /** {@code SignalGetter.hasNeighborSignal(pos)}. */
    static boolean hasNeighborSignal(Region r, int x, int y, int z) {
        for (int d = 0; d < 6; d++) {
            if (getSignal(r, x + OX[d], y + OY[d], z + OZ[d], d) > 0) return true;
        }
        return false;
    }

    /** {@code SignalGetter.getBestNeighborSignal(pos)}. */
    static int getBestNeighborSignal(Region r, int x, int y, int z) {
        int best = 0;
        for (int d = 0; d < 6; d++) {
            int s = getSignal(r, x + OX[d], y + OY[d], z + OZ[d], d);
            if (s >= 15) return 15;
            if (s > best) best = s;
        }
        return best;
    }

    // =============================================================================================================
    // Redstone wire (RedStoneWireBlock + DefaultRedstoneWireEvaluator)
    // =============================================================================================================

    /** {@code RedStoneWireBlock.getSignal}: 0 while {@code shouldSignal} is off (wires ignore wire power). */
    private static int wireSignal(Region r, int st, int x, int y, int z, int dir) {
        if (!r.shouldSignal || dir == DOWN) return 0;
        int p = wirePower(st);
        if (p == 0) return 0;
        if (dir == UP) return p;
        return wireSide(getConnectionState(r, st, x, y, z), dir ^ 1) != NONE ? p : 0;
    }

    /**
     * {@code DefaultRedstoneWireEvaluator.updatePowerStrength}: if the target strength differs, set it (flag 2: no
     * neighbour updates, but shape updates and onPlace) when the wire is still there, then update the neighbours of
     * pos and of its six neighbours, in the iteration order of the {@code HashSet<BlockPos>} vanilla collects them in.
     */
    private static void updatePowerStrength(Region r, int x, int y, int z, int st) {
        int target = calculateTargetStrength(r, x, y, z);
        if (wirePower(st) == target) return;
        if (r.world.blocks.get(x, y, z) == st) setBlock(r, x, y, z, withWirePower(st, target), UPDATE_CLIENTS);
        int[] order = r.hashOrder;
        hashSetOrder(x, y, z, order, r.hashBucket);
        for (int k = 0; k < 7; k++) {
            int i = order[k]; // 0 = pos, 1 + d = pos + Direction d
            if (i == 0) updateNeighborsAt(r, x, y, z, -1);
            else updateNeighborsAt(r, x + OX[i - 1], y + OY[i - 1], z + OZ[i - 1], -1);
        }
    }

    /**
     * Iteration order of {@code new HashSet<>()} after adding pos, then pos + D, U, N, S, W, E: a 16-bucket table
     * ({@code HashMap.hash(h) = h ^ h >>> 16}, bucket {@code & 15}), buckets in index order, insertion order within a
     * bucket. {@code BlockPos.hashCode = (y + z * 31) * 31 + x}. Writes element indices (0 = pos, 1 + d) to
     * {@code out}; {@code bucket} is scratch (7 ints).
     */
    static void hashSetOrder(int x, int y, int z, int[] out, int[] bucket) {
        for (int i = 0; i < 7; i++) {
            int px = x, py = y, pz = z;
            if (i > 0) {
                px += OX[i - 1];
                py += OY[i - 1];
                pz += OZ[i - 1];
            }
            int h = (py + pz * 31) * 31 + px;
            bucket[i] = (h ^ (h >>> 16)) & 15;
            // insertion sort by bucket, stable
            int j = i;
            while (j > 0 && bucket[out[j - 1]] > bucket[i]) {
                out[j] = out[j - 1];
                j--;
            }
            out[j] = i;
        }
    }

    /** {@code DefaultRedstoneWireEvaluator.calculateTargetStrength}. */
    private static int calculateTargetStrength(Region r, int x, int y, int z) {
        // RedStoneWireBlock.getBlockSignal: wires do not count power that reaches them through wires
        r.shouldSignal = false;
        int block = getBestNeighborSignal(r, x, y, z);
        r.shouldSignal = true;
        if (block == 15) return 15;
        return Math.max(block, getIncomingWireSignal(r, x, y, z));
    }

    /** {@code RedstoneWireEvaluator.getIncomingWireSignal}: strongest neighbouring wire (incl. up/down steps) - 1. */
    private static int getIncomingWireSignal(Region r, int x, int y, int z) {
        int w = 0;
        boolean aboveConductor = isConductor(state(r, x, y + 1, z));
        for (int dir : HORIZONTAL) {
            int nx = x + OX[dir], nz = z + OZ[dir];
            int ns = state(r, nx, y, nz);
            w = Math.max(w, wirePowerOrZero(ns));
            if (isConductor(ns)) {
                if (!aboveConductor) w = Math.max(w, wirePowerOrZero(state(r, nx, y + 1, nz)));
            } else {
                w = Math.max(w, wirePowerOrZero(state(r, nx, y - 1, nz)));
            }
        }
        return Math.max(0, w - 1);
    }

    private static int wirePowerOrZero(int st) {
        return isWire(st) ? wirePower(st) : 0;
    }

    /** {@code RedStoneWireBlock.checkCornerChangeAt}. */
    private static void checkCornerChangeAt(Region r, int x, int y, int z) {
        if (!isWire(state(r, x, y, z))) return;
        updateNeighborsAt(r, x, y, z, -1);
        for (int d = 0; d < 6; d++) updateNeighborsAt(r, x + OX[d], y + OY[d], z + OZ[d], -1);
    }

    /** {@code RedStoneWireBlock.updateNeighborsOfNeighboringWires}. */
    private static void updateNeighborsOfNeighboringWires(Region r, int x, int y, int z) {
        for (int dir : HORIZONTAL) checkCornerChangeAt(r, x + OX[dir], y, z + OZ[dir]);
        for (int dir : HORIZONTAL) {
            int nx = x + OX[dir], nz = z + OZ[dir];
            if (isConductor(state(r, nx, y, nz))) checkCornerChangeAt(r, nx, y + 1, nz);
            else checkCornerChangeAt(r, nx, y - 1, nz);
        }
    }

    /** {@code RedStoneWireBlock.canSurvive}: sturdy top face below, or a hopper. */
    private static boolean wireCanSurvive(Region r, int x, int y, int z) {
        return wireCanSurviveOn(state(r, x, y - 1, z));
    }

    private static boolean wireCanSurviveOn(int below) {
        return sturdy(below, UP, FULL) || isHopper(below);
    }

    /** {@code RedStoneWireBlock.getConnectionState}: the sides a wire shows (and powers) given its neighbours. */
    static int getConnectionState(Region r, int st, int x, int y, int z) {
        boolean wasDot = isDot(st);
        int s = getMissingConnections(r, defaultWire(wirePower(st)), x, y, z);
        if (wasDot && isDot(s)) return s;
        boolean n = wireSide(s, NORTH) != NONE, so = wireSide(s, SOUTH) != NONE;
        boolean e = wireSide(s, EAST) != NONE, w = wireSide(s, WEST) != NONE;
        boolean ns = !n && !so, ew = !e && !w;
        if (!w && ns) s = withWireSide(s, WEST, SIDE);
        if (!e && ns) s = withWireSide(s, EAST, SIDE);
        if (!n && ew) s = withWireSide(s, NORTH, SIDE);
        if (!so && ew) s = withWireSide(s, SOUTH, SIDE);
        return s;
    }

    /** {@code RedStoneWireBlock.getMissingConnections}. */
    private static int getMissingConnections(Region r, int st, int x, int y, int z) {
        boolean aboveFree = !isConductor(state(r, x, y + 1, z));
        for (int dir : HORIZONTAL) {
            if (wireSide(st, dir) == NONE) st = withWireSide(st, dir, getConnectingSide(r, x, y, z, dir, aboveFree));
        }
        return st;
    }

    /** {@code RedStoneWireBlock.getConnectingSide(level, pos, dir, aboveIsNotConductor)}. */
    private static int getConnectingSide(Region r, int x, int y, int z, int dir, boolean aboveFree) {
        int nx = x + OX[dir], nz = z + OZ[dir];
        int ns = state(r, nx, y, nz);
        if (aboveFree) {
            boolean climbable = isTrapdoor(ns) || wireCanSurviveOn(ns);
            if (climbable && isWire(state(r, nx, y + 1, nz))) {
                return sturdy(ns, dir ^ 1, FULL) ? UP_SIDE : SIDE;
            }
        }
        if (shouldConnectTo(ns, dir) || (!isConductor(ns) && isWire(state(r, nx, y - 1, nz)))) return SIDE;
        return NONE;
    }

    /** {@code RedStoneWireBlock.shouldConnectTo(state, dir)}. */
    private static boolean shouldConnectTo(int st, int dir) {
        if (isWire(st)) return true;
        if (isRepeater(st)) return facing(st) == dir || facing(st) == (dir ^ 1);
        if (kind(st) == OBSERVER) return facing(st) == dir;
        return isSignalSource(st);
    }

    // =============================================================================================================
    // Diodes (DiodeBlock, RepeaterBlock)
    // =============================================================================================================

    /** {@code DiodeBlock.checkTickOnNeighbor} / {@code ComparatorBlock.checkTickOnNeighbor}. */
    private static void checkTickOnNeighbor(Region r, int st, int x, int y, int z) {
        if (kind(st) == COMPARATOR) {
            if (willTickThisTick(r, x, y, z, block(st))) return;
            int out = calculateOutputSignal(r, st, x, y, z);
            if (out != r.comparators.get(ScheduledTicks.pack(x, y, z)) || powered(st) != shouldTurnOn(r, st, x, y, z)) {
                scheduleTick(r, x, y, z, block(st), COMPARATOR_DELAY, shouldPrioritize(r, st, x, y, z) ? HIGH : NORMAL);
            }
            return;
        }
        if (isLocked(r, st, x, y, z)) return;
        boolean powered = powered(st);
        boolean on = shouldTurnOn(r, st, x, y, z);
        if (powered != on && !willTickThisTick(r, x, y, z, block(st))) {
            int priority = shouldPrioritize(r, st, x, y, z) ? EXTREMELY_HIGH : powered ? VERY_HIGH : HIGH;
            scheduleTick(r, x, y, z, block(st), repeaterDelayTicks(st), priority);
        }
    }

    /** {@code DiodeBlock.tick}. */
    private static void diodeTick(Region r, int st, int x, int y, int z) {
        if (isLocked(r, st, x, y, z)) return;
        boolean powered = powered(st);
        boolean on = shouldTurnOn(r, st, x, y, z);
        if (powered && !on) {
            setBlock(r, x, y, z, withRepeater(st, locked(st), false), UPDATE_CLIENTS);
        } else if (!powered) {
            setBlock(r, x, y, z, withRepeater(st, locked(st), true), UPDATE_CLIENTS);
            if (!on) scheduleTick(r, x, y, z, block(st), repeaterDelayTicks(st), VERY_HIGH);
        }
    }

    /**
     * {@code Block.setPlacedBy} after a player places a block: {@code DiodeBlock} ticks in 1 when it should already be
     * on (the placement itself sends no update to it).
     */
    static void placedBy(Region r, int st, int x, int y, int z) {
        if (isDiode(st) && shouldTurnOn(r, st, x, y, z)) scheduleTick(r, x, y, z, block(st), 1, NORMAL);
    }

    /** {@code DiodeBlock.shouldTurnOn}: {@code getInputSignal > 0}; {@code ComparatorBlock.shouldTurnOn}. */
    private static boolean shouldTurnOn(Region r, int st, int x, int y, int z) {
        if (kind(st) != COMPARATOR) return getInputSignal(r, st, x, y, z) > 0;
        int in = comparatorInputSignal(r, st, x, y, z);
        if (in == 0) return false;
        int side = getAlternateSignal(r, st, x, y, z);
        return in > side || in == side && !subtract(st);
    }

    /** {@code DiodeBlock.getInputSignal}: signal from behind (FACING side), or the power of a wire there. */
    private static int getInputSignal(Region r, int st, int x, int y, int z) {
        int d = facing(st);
        int bx = x + OX[d], by = y + OY[d], bz = z + OZ[d];
        int s = getSignal(r, bx, by, bz, d);
        if (s >= 15) return s;
        return Math.max(s, wirePowerOrZero(state(r, bx, by, bz)));
    }

    /** {@code RepeaterBlock.isLocked}: {@code getAlternateSignal > 0} (side inputs from diodes only). */
    private static boolean isLocked(Region r, int st, int x, int y, int z) {
        return kind(st) == REPEATER && getAlternateSignal(r, st, x, y, z) > 0;
    }

    /**
     * {@code DiodeBlock.getAlternateSignal}: the stronger side input, clockwise then counter-clockwise of FACING;
     * {@code sideInputDiodesOnly} is true for repeaters, false for comparators.
     */
    private static int getAlternateSignal(Region r, int st, int x, int y, int z) {
        int f = facing(st);
        boolean onlyDiodes = kind(st) == REPEATER;
        return Math.max(controlInputSignal(r, x, y, z, CW[f], onlyDiodes), controlInputSignal(r, x, y, z, CCW[f], onlyDiodes));
    }

    /** {@code SignalGetter.getControlInputSignal(pos + dir, dir, onlyDiodes)}. */
    private static int controlInputSignal(Region r, int x, int y, int z, int dir, boolean onlyDiodes) {
        int nx = x + OX[dir], ny = y + OY[dir], nz = z + OZ[dir];
        int st = state(r, nx, ny, nz);
        if (onlyDiodes) return isDiode(st) ? blockDirectSignal(r, st, nx, ny, nz, dir) : 0;
        if (kind(st) == REDSTONE_BLOCK) return 15;
        if (isWire(st)) return wirePower(st);
        return isSignalSource(st) ? blockDirectSignal(r, st, nx, ny, nz, dir) : 0;
    }

    /**
     * {@code DiodeBlock.shouldPrioritize}: the block in front is a diode that does not face back into this one
     * ({@code front.FACING != FACING.getOpposite()}).
     */
    private static boolean shouldPrioritize(Region r, int st, int x, int y, int z) {
        int d = facing(st) ^ 1;
        int front = state(r, x + OX[d], y + OY[d], z + OZ[d]);
        return isDiode(front) && facing(front) != d;
    }

    /** {@code DiodeBlock.updateNeighborsInFront} (= {@code ObserverBlock.updateNeighborsInFront}). */
    private static void updateNeighborsInFront(Region r, int st, int x, int y, int z) {
        int f = facing(st);
        int d = f ^ 1;
        int fx = x + OX[d], fy = y + OY[d], fz = z + OZ[d];
        neighborChangedAt(r, fx, fy, fz);
        updateNeighborsAt(r, fx, fy, fz, f);
    }

    // =============================================================================================================
    // Comparators (ComparatorBlock, ComparatorBlockEntity)
    // =============================================================================================================

    /** {@code ComparatorBlock.getOutputSignal}: the block entity's output (another region's, read shared). */
    private static int comparatorOutput(Region r, int x, int y, int z) {
        long pos = ScheduledTicks.pack(x, y, z);
        int owner = r.world.ownerOfBlock(x, z);
        return owner == r.id ? r.comparators.get(pos) : r.world.regions[owner].comparators.getShared(pos);
    }

    /**
     * {@code ComparatorBlock.getInputSignal}: the diode input, replaced by the analog output of the block behind, or,
     * through a conductor below 15, by the analog output of the block behind that (item frames: not ported).
     */
    private static int comparatorInputSignal(Region r, int st, int x, int y, int z) {
        int in = getInputSignal(r, st, x, y, z);
        int d = facing(st);
        int bx = x + OX[d], by = y + OY[d], bz = z + OZ[d];
        int behind = state(r, bx, by, bz);
        int analog = analogOutput(behind);
        if (analog >= 0) return analog;
        if (in < 15 && isConductor(behind)) {
            int further = analogOutput(state(r, bx + OX[d], by + OY[d], bz + OZ[d]));
            if (further >= 0) return further;
        }
        return in;
    }

    /** {@code ComparatorBlock.calculateOutputSignal}. */
    private static int calculateOutputSignal(Region r, int st, int x, int y, int z) {
        int in = comparatorInputSignal(r, st, x, y, z);
        if (in == 0) return 0;
        int side = getAlternateSignal(r, st, x, y, z);
        if (side > in) return 0;
        return subtract(st) ? in - side : in;
    }

    /** {@code ComparatorBlock.refreshOutputState} (its scheduled tick, and after a mode change). */
    private static void refreshOutputState(Region r, int st, int x, int y, int z) {
        int out = calculateOutputSignal(r, st, x, y, z);
        long pos = ScheduledTicks.pack(x, y, z);
        int was = r.comparators.get(pos);
        r.comparators.set(pos, out);
        if (was != out || !subtract(st)) {
            boolean on = shouldTurnOn(r, st, x, y, z);
            boolean powered = powered(st);
            if (powered && !on) setBlock(r, x, y, z, withPowered(st, false), UPDATE_CLIENTS);
            else if (!powered && on) setBlock(r, x, y, z, withPowered(st, true), UPDATE_CLIENTS);
            updateNeighborsInFront(r, st, x, y, z);
        }
    }

    // =============================================================================================================
    // Observers (ObserverBlock)
    // =============================================================================================================

    /** {@code ObserverBlock.tick}: a 2-tick pulse, then a neighbour update in front either way. */
    private static void observerTick(Region r, int st, int x, int y, int z) {
        if (powered(st)) {
            setBlock(r, x, y, z, withPowered(st, false), UPDATE_CLIENTS);
        } else {
            setBlock(r, x, y, z, withPowered(st, true), UPDATE_CLIENTS);
            scheduleTick(r, x, y, z, block(st), OBSERVER_DELAY, NORMAL);
        }
        updateNeighborsInFront(r, st, x, y, z);
    }

    // =============================================================================================================
    // Levers and buttons (LeverBlock, ButtonBlock)
    // =============================================================================================================

    /** {@code LeverBlock.updateNeighbours} / {@code ButtonBlock.updateNeighbours}: pos, then the support block. */
    private static void updateAttachedNeighbours(Region r, int st, int x, int y, int z) {
        int d = connected(st) ^ 1;
        updateNeighborsAt(r, x, y, z, -1);
        updateNeighborsAt(r, x + OX[d], y + OY[d], z + OZ[d], -1);
    }

    /** {@code ButtonBlock.tick} → {@code checkPressed} with no arrow inside: release. */
    private static void buttonTick(Region r, int st, int x, int y, int z) {
        if (!powered(st)) return;
        int off = withPowered(st, false);
        setBlock(r, x, y, z, off, UPDATE_ALL);
        updateAttachedNeighbours(r, off, x, y, z);
    }

    /**
     * A player uses (right-clicks, empty hand, may build) the block at pos: {@code BlockState.useWithoutItem}.
     * Lever: {@code LeverBlock.pull}. Button: {@code ButtonBlock.press} unless already pressed. Repeater: cycle the
     * delay (flags 3). Comparator: cycle the mode (flags 2), then {@code refreshOutputState}. Returns whether the
     * block reacted. Only for positions this region owns.
     */
    static boolean use(Region r, int x, int y, int z) {
        int st = r.world.blocks.get(x, y, z);
        switch (kind(st)) {
            case LEVER -> {
                int pulled = withPowered(st, !powered(st));
                setBlock(r, x, y, z, pulled, UPDATE_ALL);
                updateAttachedNeighbours(r, pulled, x, y, z);
                return true;
            }
            case BUTTON -> {
                if (powered(st)) return true; // InteractionResult.CONSUME
                int pressed = withPowered(st, true);
                setBlock(r, x, y, z, pressed, UPDATE_ALL);
                updateAttachedNeighbours(r, pressed, x, y, z);
                scheduleTick(r, x, y, z, block(st), pressTicks(st), NORMAL);
                return true;
            }
            case REPEATER -> {
                setBlock(r, x, y, z, useCycle(st), UPDATE_ALL);
                return true;
            }
            case COMPARATOR -> {
                int cycled = useCycle(st);
                setBlock(r, x, y, z, cycled, UPDATE_CLIENTS);
                refreshOutputState(r, cycled, x, y, z);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // =============================================================================================================
    // Torches (RedstoneTorchBlock, RedstoneWallTorchBlock)
    // =============================================================================================================

    /** {@code RedstoneTorchBlock.hasNeighborSignal} / {@code RedstoneWallTorchBlock.hasNeighborSignal}. */
    private static boolean torchHasNeighborSignal(Region r, int st, int x, int y, int z) {
        int d = kind(st) == TORCH ? DOWN : facing(st) ^ 1;
        return getSignal(r, x + OX[d], y + OY[d], z + OZ[d], d) > 0;
    }

    /** {@code RedstoneTorchBlock.notifyNeighbors}. */
    private static void notifyNeighbors(Region r, int x, int y, int z) {
        for (int d = 0; d < 6; d++) updateNeighborsAt(r, x + OX[d], y + OY[d], z + OZ[d], -1);
    }

    /** {@code RedstoneTorchBlock.tick}, with its burnout list ({@code RECENT_TOGGLES}). */
    private static void torchTick(Region r, int st, int x, int y, int z) {
        boolean signal = torchHasNeighborSignal(r, st, x, y, z);
        r.torchToggles.prune(r.gameTime, RECENT_TOGGLE_TIMER);
        long pos = ScheduledTicks.pack(x, y, z);
        if (lit(st)) {
            if (signal) {
                setBlock(r, x, y, z, torchState(st, false), UPDATE_ALL);
                if (r.torchToggles.addAndCount(pos, r.gameTime) >= MAX_RECENT_TOGGLES) {
                    // levelEvent 1502 (burnout smoke) is client-only; the restart tick is for whatever is there now
                    scheduleTick(r, x, y, z, block(state(r, x, y, z)), RESTART_DELAY, NORMAL);
                }
            }
        } else if (!signal && r.torchToggles.count(pos) < MAX_RECENT_TOGGLES) {
            setBlock(r, x, y, z, torchState(st, true), UPDATE_ALL);
        }
    }

    private static int torchState(int st, boolean lit) {
        return kind(st) == TORCH ? (lit ? TORCH_ON : TORCH_OFF) : wallTorchState(facing(st), lit);
    }

    // =============================================================================================================
    // Lamp, TNT, falling blocks
    // =============================================================================================================

    /** {@code RedstoneLampBlock.tick}. */
    private static void lampTick(Region r, int st, int x, int y, int z) {
        if (lit(st) && !hasNeighborSignal(r, x, y, z)) setBlock(r, x, y, z, LAMP_OFF, UPDATE_CLIENTS);
    }

    /** {@code TntBlock.prime} (spawn the primed TNT) then {@code level.removeBlock(pos, false)}. */
    private static void primeAndRemove(Region r, int x, int y, int z) {
        Sim.spawnTnt(r, x + 0.5, y, z + 0.5, TNT_FUSE, dev.mulcor.core.Rng.mix(r.gameTime, x, (long) y << 32 | z));
        r.tntPrimed++;
        removeBlock(r, x, y, z);
    }

    /** {@code FallingBlock.tick}: fall if the block below is free ({@code FallingBlock.isFree}). */
    private static void fallingTick(Region r, int st, int x, int y, int z) {
        int below = state(r, x, y - 1, z);
        boolean free = BlockData.isAir(below) || BlockData.is(below, BlockData.LIQUID) || BlockData.is(below, BlockData.REPLACEABLE);
        if (!free || y - 1 < r.world.blocks.minY()) return;
        // FallingBlockEntity.fall: the block becomes its fluid's legacy block (flags 3), then the entity is added.
        setBlock(r, x, y, z, AIR, UPDATE_ALL);
        Physics.spawnFallingBlock(r, x + 0.5, y, z + 0.5, st);
    }

    // =============================================================================================================
    // Scheduled ticks (LevelTicks)
    // =============================================================================================================

    /**
     * {@code Level.scheduleTick(pos, block, delay, priority)}: due at {@code gameTime + delay}; ignored if the
     * position already has a tick scheduled for this block ({@code LevelChunkTicks.schedule}'s set).
     */
    static void scheduleTick(Region r, int x, int y, int z, int block, int delay, int priority) {
        long due = Math.max(r.gameTime + delay, r.blockTicksDone ? r.epoch + 1 : r.epoch);
        long pos = ScheduledTicks.pack(x, y, z);
        if (!r.blockTicks.schedule(due, priority, pos, block) && !r.blockTicks.isScheduled(pos, block)) r.ticksDropped++;
    }

    /** A {@link Msg#SCHEDULED_TICK} arrived: the tick keeps its due epoch (it may already be due: it runs this epoch). */
    static void receiveScheduledTick(Region r, int x, int y, int z, int block, int priority, long due) {
        long pos = ScheduledTicks.pack(x, y, z);
        if (!r.blockTicks.schedule(due, priority, pos, block) && !r.blockTicks.isScheduled(pos, block)) r.ticksDropped++;
    }

    /**
     * {@code LevelTicks.willTickThisTick}: the tick is among those collected to run in this tick's block-tick phase
     * and has not run yet. Outside that phase nothing is collected.
     */
    static boolean willTickThisTick(Region r, int x, int y, int z, int block) {
        return r.inBlockTicks && r.blockTicks.due(ScheduledTicks.pack(x, y, z), block) <= r.epoch;
    }

    /**
     * {@code ServerLevel.tick} → {@code blockTicks.tick(gameTime, 65536, this::tickBlock)}: every tick due by now in
     * (due, priority, scheduling order); a tick runs only if the block is still the type it was scheduled for.
     */
    static void runScheduled(Region r) {
        ScheduledTicks t = r.blockTicks;
        r.gameTime = r.epoch;
        r.inBlockTicks = true;
        int budget = MAX_TICKS_PER_TICK;
        while (t.peekDue() <= r.epoch && budget-- > 0) {
            long due = t.peekDue();
            int priority = t.peekPriority();
            int block = t.peekBlock();
            long pos = t.poll();
            int x = ScheduledTicks.x(pos), y = ScheduledTicks.y(pos), z = ScheduledTicks.z(pos);
            int owner = r.world.ownerOfBlock(x, z);
            if (owner != r.id) { // the region split since this was scheduled: hand it to the new owner
                MemorySegment m = r.begin(Msg.SCHEDULED_TICK);
                m.set(I, Msg.A, x);
                m.set(I, Msg.B, y);
                m.set(I, Msg.C, z);
                m.set(I, Msg.D, priority);
                m.set(I, Msg.E, block);
                m.set(L, Msg.DEADLINE, due);
                r.send(owner);
                continue;
            }
            int st = r.world.blocks.get(x, y, z);
            if (block(st) != block) continue; // ServerLevel.tickBlock: state.is(block)
            r.scheduledTicks++;
            switch (kind(st)) {
                case REPEATER -> diodeTick(r, st, x, y, z);
                case COMPARATOR -> refreshOutputState(r, st, x, y, z); // ComparatorBlock.tick
                case OBSERVER -> observerTick(r, st, x, y, z);
                case BUTTON -> buttonTick(r, st, x, y, z);
                case TORCH, WALL_TORCH -> torchTick(r, st, x, y, z);
                case LAMP -> lampTick(r, st, x, y, z);
                case FALLING -> fallingTick(r, st, x, y, z);
                default -> {}
            }
        }
        r.inBlockTicks = false;
        r.blockTicksDone = true;
    }
}
