package dev.mulcor.core;

import dev.mulcor.core.grid.Partition;
import dev.mulcor.core.light.LightEngine;
import dev.mulcor.core.light.LightService;
import dev.mulcor.core.region.Msg;
import dev.mulcor.core.region.Input;
import dev.mulcor.core.region.Region;
import dev.mulcor.core.region.Journal;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.BroadcastJournal;
import dev.mulcor.memory.EntityDirectory;
import dev.mulcor.memory.EntityRecord;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapInventory;
import dev.mulcor.memory.Ownership;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * State shared by all regions: native block, entity and inventory storage, the cell→region partition, and the
 * routing functions that decide which region owns what.
 */
public final class World implements AutoCloseable {
    public static final int PLAYER_SLOTS = 36;
    public static final int CHEST_SLOTS = 27;

    public final EngineConfig cfg;
    public final NativeMemory memory = new NativeMemory();
    public final BlockStorage blocks;
    /** Vanilla block and sky light; written only by the commit phase ({@link #light}), read anywhere with getShared. */
    public final LightStorage blockLight, skyLight;
    public final LightEngine light;
    /** The light thread; started by {@link #startLight()} once the generated world is lit. */
    public LightService lightService;
    public final EntityDirectory directory;
    public final OffHeapInventory players;
    public final OffHeapInventory chests;
    /**
     * Network players' inventories: {@link #INV} {@link dev.mulcor.core.region.Stacks} per entity id, indexed like
     * vanilla's {@code InventoryMenu} (0 craft result, 1-4 craft grid, 5-8 armor head to feet, 9-35 main,
     * 36-44 hotbar, 45 offhand), then the crafting table's grid (46-54) and result (55). Plus the stack on the
     * cursor ({@code carried}), the selected hotbar slot, the open container ({@link #window}: container id |
     * menu kind << 8, 0 = the inventory) and the number of clicks handled. Written only by the region that owns
     * the player; network threads read the copies {@code Region} publishes into {@link #invPub}.
     */
    public final long[] inv, carried;
    public final int[] heldSlot, window, clicks, containerCounter;
    /** Block position of the open crafting table ({@code ScheduledTicks.pack}), for {@code stillValid}. */
    public final long[] menuPos;
    /** Drag state ({@code AbstractContainerMenu.quickcraftStatus | quickcraftType << 8}) and the dragged-over slots (a mask). */
    public final int[] quickcraft;
    public final long[] quickcraftSlots;
    /** Per-tick dedup stamps for {@code Region.invChanged}. */
    public final long[] invMarked;
    public static final int INV = 56;
    /** Published inventory: {@link #INV} slots, the carried stack, then window | clicks << 32. */
    public static final int INV_PUB = INV + 2;
    /** Published copies of {@link #inv} (see {@code Region.publishInventories}), guarded by the seqlock {@link #invSeq}. */
    public final long[] invPub;
    public final int[] invSeq;
    /**
     * Network players' visible state ({@link dev.mulcor.core.region.Presence}) and arm-swing counters, per entity id.
     * Written only by the owning region.
     */
    public final int[] presence, swings;
    /** Network players' game modes (vanilla {@code GameType} ids), per entity id. Written only by the owning region. */
    public final int[] gameMode;
    /** Network players' health ({@code LivingEntity.getHealth}), per entity id. Written only by the owning region. */
    public final float[] health;
    public static final int SURVIVAL = 0, CREATIVE = 1, ADVENTURE = 2, SPECTATOR = 3;
    /** Day time minus game time (the epoch): {@code /time set} moves it. Cold: written by commands. */
    public volatile long dayTimeOffset;
    public final Partition partition;
    /**
     * Egress journals, one per region slot ({@link Journal} records): what changed in each tick, for player sessions.
     * The region running in a slot is its only writer; sessions read every slot's journal.
     */
    public final BroadcastJournal[] journals;
    public final Region[] regions;
    public final JoinTickets joins;
    public final int surfaceY;
    private final int[] chestX, chestY, chestZ;
    private final int cellBlocks, cellsX, cellsZ, sizeX, sizeZ;

    public World(EngineConfig cfg) {
        this.cfg = cfg;
        this.cellBlocks = cfg.cellBlocks();
        this.cellsX = cfg.cellsX();
        this.cellsZ = cfg.cellsZ();
        this.sizeX = cfg.chunksX() * 16;
        this.sizeZ = cfg.chunksZ() * 16;
        this.surfaceY = cfg.minY() + 4;
        this.blocks = new BlockStorage(memory, cfg.chunksX(), cfg.chunksZ(), cfg.minY(), cfg.sections(),
                cfg.chunksX() * cfg.chunksZ() * cfg.sections());
        int lightSections = cfg.chunksX() * cfg.chunksZ() * (cfg.sections() + 2);
        this.blockLight = new LightStorage(memory, cfg.chunksX(), cfg.chunksZ(), cfg.minY(), cfg.sections(), lightSections, 0);
        this.skyLight = new LightStorage(memory, cfg.chunksX(), cfg.chunksZ(), cfg.minY(), cfg.sections(), lightSections, 0);
        this.light = new LightEngine(blocks, blockLight, skyLight);
        this.directory = new EntityDirectory(memory, cfg.maxEntities());
        this.players = new OffHeapInventory(memory, cfg.maxEntities(), PLAYER_SLOTS);
        this.inv = new long[cfg.maxEntities() * INV];
        this.carried = new long[cfg.maxEntities()];
        this.heldSlot = new int[cfg.maxEntities()];
        this.window = new int[cfg.maxEntities()];
        this.clicks = new int[cfg.maxEntities()];
        this.containerCounter = new int[cfg.maxEntities()];
        this.menuPos = new long[cfg.maxEntities()];
        this.quickcraft = new int[cfg.maxEntities()];
        this.quickcraftSlots = new long[cfg.maxEntities()];
        this.invMarked = new long[cfg.maxEntities()];
        this.invPub = new long[cfg.maxEntities() * INV_PUB];
        this.invSeq = new int[cfg.maxEntities()];
        this.presence = new int[cfg.maxEntities()];
        this.swings = new int[cfg.maxEntities()];
        this.gameMode = new int[cfg.maxEntities()];
        this.health = new float[cfg.maxEntities()];
        int numChests = cellsX * cellsZ * cfg.chestsPerCell();
        this.chests = new OffHeapInventory(memory, numChests, CHEST_SLOTS);
        this.chestX = new int[numChests];
        this.chestY = new int[numChests];
        this.chestZ = new int[numChests];
        this.joins = new JoinTickets(memory, 1024);
        this.partition = new Partition(cellsX, cellsZ, cfg.maxRegions(), cfg.maxCompactness());
        this.partition.initialize(cfg.initialRegions());
        this.journals = new BroadcastJournal[cfg.maxRegions()];
        for (int r = 0; r < journals.length; r++) journals[r] = new BroadcastJournal(memory, Journal.CAPACITY, Journal.BYTES);
        this.regions = new Region[cfg.maxRegions()];
        for (int r = 0; r < regions.length; r++) {
            regions[r] = new Region(r, this, partition.isActive(r));
        }
    }

    private static final java.lang.invoke.VarHandle SEQ = java.lang.invoke.MethodHandles.arrayElementVarHandle(int[].class);

    /** Region side of the inventory seqlock: copy player {@code eid}'s inventory into {@link #invPub}. */
    public void publishInventory(int eid) {
        int s = invSeq[eid];
        SEQ.setOpaque(invSeq, eid, s + 1);
        java.lang.invoke.VarHandle.storeStoreFence();
        int base = eid * INV_PUB;
        System.arraycopy(inv, eid * INV, invPub, base, INV);
        invPub[base + INV] = carried[eid];
        invPub[base + INV + 1] = (window[eid] & 0xFFFFFFFFL) | (long) clicks[eid] << 32;
        SEQ.setRelease(invSeq, eid, s + 2);
    }

    /**
     * Network side of the seqlock: copy player {@code eid}'s published inventory into {@code out} ({@link #INV_PUB}
     * longs). Returns the sequence number read, or -1 if a write was in progress (try again later).
     */
    public int readInventory(int eid, long[] out) {
        int s = (int) SEQ.getAcquire(invSeq, eid);
        if ((s & 1) != 0) return -1;
        System.arraycopy(invPub, eid * INV_PUB, out, 0, INV_PUB);
        java.lang.invoke.VarHandle.loadLoadFence();
        return (int) SEQ.getOpaque(invSeq, eid) == s ? s : -1;
    }

    /** The published sequence number of player {@code eid}'s inventory (changes on every publish). */
    public int inventorySeq(int eid) {
        return (int) SEQ.getAcquire(invSeq, eid);
    }

    /** {@code Abilities.mayfly} of a game mode ({@code GameType.updatePlayerAbilities}). */
    public static boolean mayFly(int gameMode) {
        return gameMode == CREATIVE || gameMode == SPECTATOR;
    }

    /** Light the whole world, then hand light over to its own thread. */
    public void startLight() {
        light.relightAll();
        lightService = new LightService(light, blocks, 1 << 16);
    }

    /** Flat terrain: bedrock, two stone layers and dirt, with scattered 3-high stone pillars and one chest per cell. */
    public void generate() {
        int minY = cfg.minY();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                blocks.set(x, minY, z, Blocks.BEDROCK);
                blocks.set(x, minY + 1, z, Blocks.STONE);
                blocks.set(x, minY + 2, z, Blocks.STONE);
                blocks.set(x, minY + 3, z, Blocks.DIRT);
                long h = Rng.mix(cfg.seed(), x, z);
                if (Rng.bounded(h, 1_000_000) < (int) (cfg.pillarDensity() * 1_000_000)) {
                    for (int y = surfaceY; y < surfaceY + 3 && y < blocks.maxYExclusive(); y++) {
                        blocks.set(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        int chest = 0;
        for (int cz = 0; cz < cellsZ; cz++) {
            for (int cx = 0; cx < cellsX; cx++) {
                for (int i = 0; i < cfg.chestsPerCell(); i++, chest++) {
                    int x = cx * cellBlocks + cellBlocks / 2 + 2 * i, z = cz * cellBlocks + cellBlocks / 2;
                    for (int y = surfaceY; y < surfaceY + 3 && y < blocks.maxYExclusive(); y++) blocks.set(x, y, z, Blocks.AIR);
                    blocks.set(x, surfaceY, z, Blocks.CHEST);
                    chestX[chest] = x;
                    chestY[chest] = surfaceY;
                    chestZ[chest] = z;
                    for (int s = 0; s < 9; s++) chests.set(chest, s, s % 2 == 0 ? Blocks.DIRT : Blocks.STONE, 32);
                }
            }
        }
    }

    /**
     * An explosion destroyed the chest block at (x, y, z): empty its inventory (the items spill, and are counted as
     * dropped by the caller). Owner region only. Returns the number of items removed.
     */
    public long destroyChestAt(int x, int y, int z) {
        for (int c = 0; c < chestX.length; c++) {
            if (chestX[c] == x && chestY[c] == y && chestZ[c] == z) return chests.clear(c);
        }
        return 0;
    }

    public int cellBlocks() { return cellBlocks; }
    public int sizeX() { return sizeX; }
    public int sizeZ() { return sizeZ; }
    public int chestCount() { return chestX.length; }
    public int chestX(int chest) { return chestX[chest]; }
    public int chestZ(int chest) { return chestZ[chest]; }

    public int cellOf(int x, int z) {
        int cx = Math.clamp(x / cellBlocks, 0, cellsX - 1);
        int cz = Math.clamp(z / cellBlocks, 0, cellsZ - 1);
        return cz * cellsX + cx;
    }

    public int ownerOfBlock(int x, int z) {
        return partition.regionOf(cellOf(x, z));
    }

    public int ownerOfChest(int chest) {
        return ownerOfBlock(chestX[chest], chestZ[chest]);
    }

    /** The region that owns (or is about to own) an entity, or -1 if the id is not live. */
    public int ownerOfEntity(int id) {
        if (id < 0 || id >= directory.capacity()) return -1;
        long w = directory.ownership(id);
        int state = Ownership.state(w);
        return state == Ownership.OWNED || state == Ownership.IN_TRANSIT ? Ownership.region(w) : -1;
    }

    /** Where a message should go under the current partition, or -1 if its target no longer exists. */
    public int route(MemorySegment seg, long off) {
        int kind = seg.get(ValueLayout.JAVA_INT, off + Msg.KIND);
        return switch (kind) {
            case Msg.TRANSFER -> ownerOfBlock(
                    (int) Math.floor(seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + EntityRecord.X)),
                    (int) Math.floor(seg.get(ValueLayout.JAVA_DOUBLE, off + Msg.BODY + EntityRecord.Z)));
            case Msg.BLOCK_BREAK, Msg.BLOCK_PLACE, Msg.NEIGHBOR_UPDATE, Msg.SCHEDULED_TICK, Msg.FLUID_TICK, Msg.FLUID_SPREAD ->
                    ownerOfBlock(seg.get(ValueLayout.JAVA_INT, off + Msg.A), seg.get(ValueLayout.JAVA_INT, off + Msg.C));
            case Msg.INV_TAKE, Msg.INV_PUT -> ownerOfChest(seg.get(ValueLayout.JAVA_INT, off + Msg.A));
            case Msg.INV_DELIVER -> ownerOfEntity(seg.get(ValueLayout.JAVA_INT, off + Msg.A));
            case Msg.INPUT -> ownerOfEntity(seg.get(ValueLayout.JAVA_INT, off + Msg.BODY + Input.ENTITY));
            default -> seg.get(ValueLayout.JAVA_INT, off + Msg.DST);
        };
    }

    /** Where a client input record should be handled: by entity owner, or by block owner for region-addressed kinds. */
    public int routeInput(MemorySegment seg, long off) {
        return switch (seg.get(ValueLayout.JAVA_INT, off + Input.KIND)) {
            case Input.JOIN -> ownerOfBlock(Math.floorDiv(seg.get(ValueLayout.JAVA_INT, off + Input.X), 1000),
                    Math.floorDiv(seg.get(ValueLayout.JAVA_INT, off + Input.Z), 1000));
            case Input.SET_BLOCK, Input.USE_BLOCK -> ownerOfBlock(seg.get(ValueLayout.JAVA_INT, off + Input.X), seg.get(ValueLayout.JAVA_INT, off + Input.Z));
            default -> ownerOfEntity(seg.get(ValueLayout.JAVA_INT, off + Input.ENTITY));
        };
    }

    // ---- audit helpers (O(world); tests and reports only) -------------------------------------------------------

    public long countBlocks(int state) {
        long n = 0;
        for (int x = 0; x < sizeX; x++)
            for (int z = 0; z < sizeZ; z++)
                for (int y = cfg.minY(); y < blocks.maxYExclusive(); y++)
                    if (blocks.get(x, y, z) == state) n++;
        return n;
    }

    public long countItems(int item) {
        long n = 0;
        for (int i = 0; i < players.inventories(); i++) n += players.total(i, item);
        for (int i = 0; i < chests.inventories(); i++) n += chests.total(i, item);
        return n;
    }

    /** Items of registry id {@code item} lying on the ground as item entities. */
    public long countDropped(int item) {
        long n = 0;
        for (var r : regions) if (r != null) n += r.droppedItems(item);
        return n;
    }

    @Override
    public void close() {
        if (lightService != null) lightService.close();
        memory.close();
    }
}
