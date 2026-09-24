package dev.mulcor.core.region;

import static dev.mulcor.core.region.RedstoneStates.*;

import dev.mulcor.core.World;
import dev.mulcor.core.grid.Partition;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.registry.BlockData;

/**
 * Affinity bonds (roadmap §3.3): which block pairs across a cell edge must share a region, so that their zero-delay
 * interactions run in vanilla order instead of one epoch apart.
 *
 * <h2>Bonds</h2>
 * A cell edge is bonded when, across it,
 * <ul>
 *   <li>two adjacent blocks interact through redstone: a component (wire, diodes, torches, observers, levers,
 *       buttons, lamps, redstone blocks, TNT, pistons) next to another component or a conductor it can power through;</li>
 *   <li>a slime or honey block touches a non-air block (piston structures branch through them);</li>
 *   <li>two halves of a double block meet (chests, beds);</li>
 *   <li>a piston, head or moving piston within {@link #PISTON_REACH} blocks faces across it (its push line crosses).</li>
 * </ul>
 *
 * <h2>Counting without races</h2>
 * The regions on either side of an edge write their blocks concurrently, so neither can keep an exact count. A block
 * write near an edge only marks the edge dirty ({@link #onBlockWrite}); the single-threaded commit phase recounts
 * each dirty edge from the blocks ({@link #recount}) and the engine then merges bonded regions. Between the write and
 * that commit (at most one epoch) the interaction runs with the documented border lag, and pistons refuse to move
 * blocks of another region (a vanilla-legal outcome); a refused piston also asks for the merge directly.
 */
public final class Affinity {
    /** A piston pushes at most 12 blocks, so its head plus 12 moved blocks reach 13 blocks from the base. */
    public static final int PISTON_REACH = 13;

    private Affinity() {}

    private static boolean component(int st) {
        return switch (kind(st)) {
            case WIRE, REPEATER, COMPARATOR, TORCH, WALL_TORCH, OBSERVER, LEVER, BUTTON, LAMP, REDSTONE_BLOCK, TNT,
                    PISTON, PISTON_HEAD, MOVING_PISTON -> true;
            default -> false;
        };
    }

    private static boolean pistonish(int st) {
        int k = kind(st);
        return k == PISTON || k == PISTON_HEAD || k == MOVING_PISTON;
    }

    private static boolean stickyBlock(int st) {
        int b = block(st);
        return b == dev.mulcor.registry.BlockId.SLIME_BLOCK || b == dev.mulcor.registry.BlockId.HONEY_BLOCK;
    }

    private static final boolean[] DOUBLE;

    static {
        int blocks = dev.mulcor.registry.BlockId.COUNT;
        DOUBLE = new boolean[blocks];
        for (int b = 0; b < blocks; b++) {
            String n = BlockData.name(b);
            DOUBLE[b] = n.equals("minecraft:chest") || n.equals("minecraft:trapped_chest") || n.endsWith("_bed");
        }
    }

    /** Is the adjacent pair (a, b) bonded? */
    static boolean bonded(int a, int b) {
        if (isAir(a) && isAir(b)) return false;
        if (component(a) && (component(b) || isConductor(b))) return true;
        if (component(b) && isConductor(a)) return true;
        if (stickyBlock(a) && !isAir(b) || stickyBlock(b) && !isAir(a)) return true;
        return DOUBLE[block(a)] && block(a) == block(b);
    }

    /**
     * Mark the cell edges a block write at (x, y, z) may change the bonds of: edges within 1 block, or within
     * {@link #PISTON_REACH} when a piston is involved. Called for every block write by the region that owns it.
     */
    static void onBlockWrite(Region r, int x, int z, int old, int now) {
        if (!r.affinityEnabled) return;
        int cb = r.world.cellBlocks();
        int lx = Math.floorMod(x, cb), lz = Math.floorMod(z, cb);
        int reach = pistonish(old) || pistonish(now) ? PISTON_REACH : 1;
        if (lx >= reach && lx < cb - reach && lz >= reach && lz < cb - reach) return; // interior: no edge nearby
        Partition p = r.world.partition;
        int cx = Math.floorDiv(x, cb), cz = Math.floorDiv(z, cb);
        int cellsX = p.cellsX(), cellsZ = p.cellsZ();
        if (cx < 0 || cz < 0 || cx >= cellsX || cz >= cellsZ) return;
        int c = cz * cellsX + cx;
        if (lx < reach && cx > 0) r.markAffinity(2 * (c - 1));
        if (lx >= cb - reach && cx < cellsX - 1) r.markAffinity(2 * c);
        if (lz < reach && cz > 0) r.markAffinity(2 * (c - cellsX) + 1);
        if (lz >= cb - reach && cz < cellsZ - 1) r.markAffinity(2 * c + 1);
    }

    /**
     * Count the bonds across {@code edge} from the blocks (commit phase: no region is running). Adjacent pairs along
     * the whole edge, plus pistons, heads and moving pistons within {@link #PISTON_REACH} facing across it.
     */
    public static int recount(World world, int edge) {
        Partition p = world.partition;
        if (!p.isEdge(edge)) return 0;
        BlockStorage blocks = world.blocks;
        int cb = world.cellBlocks();
        int cellsX = p.cellsX();
        int a = p.edgeCellA(edge);
        int ax = a % cellsX, az = a / cellsX;
        boolean alongX = (edge & 1) == 0; // the edge separates x < boundary from x >= boundary
        int boundary = alongX ? (ax + 1) * cb : (az + 1) * cb;
        int from = alongX ? az * cb : ax * cb;
        int toward = alongX ? EAST : SOUTH;
        int minY = blocks.minY(), maxY = blocks.maxYExclusive();
        int n = 0;
        for (int t = from; t < from + cb; t++) {
            for (int y = minY; y < maxY; y++) {
                int lo = alongX ? blocks.get(boundary - 1, y, t) : blocks.get(t, y, boundary - 1);
                int hi = alongX ? blocks.get(boundary, y, t) : blocks.get(t, y, boundary);
                if (bonded(lo, hi)) n++;
                for (int d = 1; d <= PISTON_REACH; d++) {
                    int near = alongX ? blocks.get(boundary - d, y, t) : blocks.get(t, y, boundary - d);
                    if (pistonish(near) && facing(near) == toward) n++;
                    int far = alongX ? blocks.get(boundary - 1 + d, y, t) : blocks.get(t, y, boundary - 1 + d);
                    if (pistonish(far) && facing(far) == (toward ^ 1)) n++;
                }
            }
        }
        return n;
    }
}
