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
        ItemEntities.buildGrid(r);
        int i = 0;
        while (i < t.count()) {
            boolean removed;
            int type = t.type(i);
            if (type == ITEM) {
                removed = ItemEntities.tick(r, i);
            } else if (type == TNT) {
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
                        dig(r, eid, x, y, z, 0); // bots: the legacy item flow
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
    /** Break rules ({@code Msg.BLOCK_BREAK} D): what a dig may break, decided where the player is. */
    static final int BREAK_LEGACY = 0, BREAK_CREATIVE = 1, BREAK_INSTANT = 2, BREAK_SURVIVAL = 3;

    /**
     * DIG input: {@code action} 0 breaks a mineable block at once (headless clients), else it is the
     * {@code ServerboundPlayerActionPacket} status + 1 and the player's game mode decides
     * ({@code ServerPlayerGameMode.handleBlockBreakAction}): creative breaks on START_DESTROY_BLOCK, survival on
     * STOP_DESTROY_BLOCK or at once when the block's destroy speed is 0; adventure and spectator break nothing.
     * Survival digging time is not validated (the client's timing is trusted).
     */
    static void dig(Region r, int eid, int x, int y, int z, int action) {
        int rule;
        if (action == 0) {
            rule = BREAK_LEGACY;
        } else {
            int status = action - 1, mode = r.world.gameMode[eid];
            // Item.canDestroyBlock: swords, tridents and maces break nothing in creative
            if (mode == World.CREATIVE) rule = status == 0 && !Tools.noCreativeBreak(PlayerInv.selected(r, eid)) ? BREAK_CREATIVE : -1;
            else if (mode == World.SURVIVAL) rule = status == 0 ? BREAK_INSTANT : status == 2 ? BREAK_SURVIVAL : -1;
            else rule = -1;
            if (rule < 0) return;
        }
        if (!r.world.blocks.inBounds(x, y, z)) return;
        long tool = rule == BREAK_LEGACY ? Stacks.EMPTY : PlayerInv.selected(r, eid);
        if (rule == BREAK_SURVIVAL || rule == BREAK_INSTANT) {
            int st = r.world.blocks.getShared(x, y, z);
            if (!dev.mulcor.registry.BlockData.isAir(st) && !FluidStates.isLiquidBlock(st)) mineBlock(r, eid, st);
        }
        int owner = r.world.ownerOfBlock(x, z);
        if (owner == r.id) {
            breakOwned(r, eid, x, y, z, rule, tool);
        } else {
            MemorySegment m = r.begin(Msg.BLOCK_BREAK);
            m.set(I, Msg.A, x);
            m.set(I, Msg.B, y);
            m.set(I, Msg.C, z);
            m.set(I, Msg.D, rule);
            m.set(I, Msg.E, eid);
            m.set(ValueLayout.JAVA_LONG, Msg.WORD, tool);
            r.send(owner);
        }
    }

    /**
     * {@code ItemStack.mineBlock} → {@code Tool.mineBlock}: a damageable tool loses {@code damagePerBlock} (2 for
     * swords, else 1) when the block's destroy time is not 0; creative players' tools are not damaged. The block is
     * read where the player is (a neighbour's block may be a tick stale).
     */
    private static void mineBlock(Region r, int eid, int st) {
        if (r.world.gameMode[eid] == World.CREATIVE) return;
        int slot = PlayerInv.selectedSlot(r, eid);
        long tool = PlayerInv.get(r, eid, slot);
        if (!Tools.isTool(tool) || dev.mulcor.registry.BlockData.hardness(dev.mulcor.registry.BlockData.block(st)) == 0) return;
        PlayerInv.set(r, eid, slot, Tools.hurt(r, eid, tool, Tools.damagePerBlock(tool)));
    }

    /**
     * {@code ServerPlayerGameMode.destroyBlock}: {@code level.removeBlock(pos, false)} leaves the block's fluid (a
     * waterlogged block becomes water). Air and liquid blocks cannot be broken, unbreakable blocks (destroy time -1)
     * only in creative. Survival mining pops the block's loot ({@link Loot}) if the tool can harvest it; creative
     * mining drops nothing.
     */
    static void breakOwned(Region r, int eid, int x, int y, int z, int rule, long tool) {
        BlockStorage b = r.world.blocks;
        int st = b.get(x, y, z);
        if (rule == BREAK_LEGACY) {
            if (!Blocks.isMineable(st)) return; // already gone: whoever arrived first got it
            Redstone.removeBlock(r, x, y, z);
            deliver(r, eid, st, 1);
            return;
        }
        if (dev.mulcor.registry.BlockData.isAir(st) || FluidStates.isLiquidBlock(st)) return;
        float hardness = dev.mulcor.registry.BlockData.hardness(dev.mulcor.registry.BlockData.block(st));
        if (rule != BREAK_CREATIVE && (hardness < 0 || rule == BREAK_INSTANT && hardness != 0)) return;
        // Block.playerWillDestroy → removeBlock → (canHarvest) playerDestroy → dropResources with the tool
        Redstone.setBlock(r, x, y, z, FluidStates.legacyBlock(FluidStates.fluid(st)), Redstone.UPDATE_ALL);
        if (rule != BREAK_CREATIVE && Loot.canHarvest(st, tool)) Loot.dropResources(r, x, y, z, st, tool);
    }

    /** Entity {@code eid} (owned by {@code r}) places one {@code item} from its inventory. */
    /**
     * {@code ServerPlayerGameMode.useItemOn}: the clicked block's use action first ({@code useWithoutItem}; the player
     * is taken as not sneaking), else place the item against it. {@code face1} is the clicked face + 1, or 0 for a
     * bare placement at (x, y, z). A clicked block another region owns is not used: the item is placed (a border
     * deviation until uses travel as messages).
     */
    static void useItemOn(Region r, int eid, int slot, int x, int y, int z, int item, int face1, int cursor) {
        if (face1 > 0 && face1 <= 6) {
            int d = face1 - 1;
            int cx = x - RedstoneStates.OX[d], cy = y - RedstoneStates.OY[d], cz = z - RedstoneStates.OZ[d];
            if (r.world.blocks.inBounds(cx, cy, cz) && r.world.ownerOfBlock(cx, cz) == r.id) {
                // ServerPlayerGameMode.useItemOn: a sneaking player holding an item skips the block's use action
                boolean skipUse = Presence.shift(r.world.presence[eid]) && item == Input.HELD_ITEM
                        && (PlayerInv.selected(r, eid) != Stacks.EMPTY || PlayerInv.get(r, eid, PlayerInv.OFFHAND) != Stacks.EMPTY);
                if (!skipUse) {
                    int used = r.world.blocks.get(cx, cy, cz);
                    if (dev.mulcor.registry.BlockData.block(used) == dev.mulcor.registry.BlockId.CRAFTING_TABLE
                            && r.table.type(slot) == PLAYER) {
                        Menus.openCraftingTable(r, eid, cx, cy, cz);
                        return;
                    }
                    if (Redstone.use(r, cx, cy, cz)) return;
                }
            }
            if (item == Input.HELD_ITEM) {
                placeHeld(r, eid, slot, cx, cy, cz, d, cursor);
                return;
            }
        }
        if (item != Input.HELD_ITEM) place(r, eid, x, y, z, item);
    }

    /**
     * {@code BlockItem.useOn} → {@code place} for a creative player's held block item: the placement position is the
     * clicked block if it can be replaced, else the one against the clicked face; the state comes from
     * {@link Placement}; nothing is consumed. Only positions this region owns (a border deviation, like the use).
     */
    private static void placeHeld(Region r, int eid, int slot, int cx, int cy, int cz, int face, int cursor) {
        if (r.world.gameMode[eid] == World.SPECTATOR || r.world.gameMode[eid] == World.ADVENTURE) return;
        int invSlot = PlayerInv.selectedSlot(r, eid);
        long held = PlayerInv.get(r, eid, invSlot);
        int item = Stacks.item(held);
        int block = held != Stacks.EMPTY ? dev.mulcor.registry.Items.block(item) : -1;
        if (block < 0) return; // not a block item (item use is not ported)
        int clicked = r.world.blocks.get(cx, cy, cz);
        boolean replacingClicked = Placement.replaceable(clicked) && dev.mulcor.registry.BlockData.block(clicked) != block;
        int x = cx, y = cy, z = cz;
        if (!replacingClicked) {
            x += RedstoneStates.OX[face];
            y += RedstoneStates.OY[face];
            z += RedstoneStates.OZ[face];
        }
        BlockStorage b = r.world.blocks;
        if (!b.inBounds(x, y, z) || r.world.ownerOfBlock(x, z) != r.id) return;
        int target = b.get(x, y, z);
        boolean combine = Placement.family(block) == Placement.SLAB && dev.mulcor.registry.BlockData.block(target) == block;
        if (!Placement.replaceable(target) && !combine) return;
        // getClickLocation - pos: the click inside the clicked block, relative to the placement position
        double clickX = cx + (cursor & 1023) / 1000.0 - x, clickY = cy + (cursor >>> 10 & 1023) / 1000.0 - y;
        double clickZ = cz + (cursor >>> 20 & 1023) / 1000.0 - z;
        EntityTable t = r.table;
        float yaw = Float.intBitsToFloat(t.aux1(slot)), pitch = Float.intBitsToFloat(t.aux2(slot));
        int st = Placement.stateForPlacement(r, block, x, y, z, face, clickX, clickY, clickZ, yaw, pitch, replacingClicked,
                target, r.placementDirs);
        if (st < 0) return;
        switch (RedstoneStates.kind(st)) {
            case RedstoneStates.WIRE, RedstoneStates.REPEATER, RedstoneStates.COMPARATOR, RedstoneStates.TORCH,
                    RedstoneStates.WALL_TORCH -> {
                // getStateForPlacement of these reads the neighbours (connections, lock); canSurvive rejects
                st = Redstone.updateFromNeighbourShapes(r, st, x, y, z);
                if (RedstoneStates.isAir(st)) return;
            }
            default -> { }
        }
        // BlockItem.place → level.setBlock(pos, state, UPDATE_ALL_IMMEDIATE = 11), then setPlacedBy
        if (!Redstone.setBlock(r, x, y, z, st, 11)) return;
        Placement.placeSecondHalf(r, st, x, y, z);
        Redstone.placedBy(r, st, x, y, z);
        // BlockItem.place: itemStack.consume(1, player) (not in creative)
        if (r.world.gameMode[eid] != World.CREATIVE) PlayerInv.set(r, eid, invSlot, Stacks.withCount(held, Stacks.count(held) - 1));
    }

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
        // BlockItem.place → level.setBlock(pos, state, UPDATE_ALL_IMMEDIATE = 11)
        if (!(b.inBounds(x, y, z) && b.get(x, y, z) == Blocks.AIR && Redstone.setBlock(r, x, y, z, item, 11))) {
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
            case Msg.BLOCK_BREAK -> breakOwned(r, seg.get(I, off + Msg.E), a, b, c, d,
                    seg.get(ValueLayout.JAVA_LONG, off + Msg.WORD));
            case Msg.BLOCK_PLACE -> placeOwned(r, seg.get(I, off + Msg.E), a, b, c, d);
            case Msg.EXPLOSION -> Explosion.receive(r, seg, off);
            case Msg.EXPLOSION_BLOCKS -> Explosion.receiveBlocks(r, seg, off);
            case Msg.NEIGHBOR_UPDATE -> Redstone.receiveNeighborUpdate(r, a, b, c, seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.SHAPE_UPDATE -> Redstone.receiveShapeUpdate(r, a, b, c, d, seg.get(I, off + Msg.E), seg.get(I, off + Msg.F),
                    (int) seg.get(ValueLayout.JAVA_LONG, off + Msg.WORD), seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.SCHEDULED_TICK -> Redstone.receiveScheduledTick(r, a, b, c, seg.get(I, off + Msg.E), d,
                    seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.FLUID_TICK -> Fluids.receiveTick(r, a, b, c, seg.get(I, off + Msg.E), seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.FLUID_SPREAD -> Fluids.receiveSpread(r, a, b, c, d, seg.get(I, off + Msg.E), seg.get(I, off + Msg.F),
                    seg.get(ValueLayout.JAVA_LONG, off + Msg.DEADLINE));
            case Msg.INV_TAKE -> {
                long taken = r.world.chests.take(a, b, c);
                deliver(r, d, OffHeapInventory.item(taken), OffHeapInventory.count(taken));
            }
            case Msg.INV_PUT -> deliver(r, d, b, r.world.chests.insert(a, b, c));
            default -> r.stateViolations++;
        }
    }

    // ---- commands ----------------------------------------------------------------------------------------------

    /** SET_BLOCK input: vanilla {@code /setblock} on a block this region owns (see {@link Redstone#commandSetBlock}). */
    static void setBlockAndUpdate(Region r, int x, int y, int z, int state) {
        if (r.world.ownerOfBlock(x, z) != r.id || !r.world.blocks.inBounds(x, y, z)) return;
        Redstone.commandSetBlock(r, x, y, z, state);
    }

    /** USE_BLOCK input: a player uses a block this region owns (see {@link Redstone#use}). */
    static void useBlock(Region r, int x, int y, int z) {
        if (r.world.ownerOfBlock(x, z) != r.id || !r.world.blocks.inBounds(x, y, z)) return;
        Redstone.use(r, x, y, z);
    }
}
