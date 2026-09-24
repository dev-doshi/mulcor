package dev.mulcor.core.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Entity.collide} with real vanilla block shapes and step-up (see {@link Collision}). Each expected position
 * is derived by hand from vanilla's algorithm for a zombie-sized box (0.6 × 1.95, step height 0.6).
 */
class CollisionParityTest {
    Engine e;
    Region r;
    int sy, x0 = 40, z0 = 40;

    @BeforeEach
    void setUp() {
        e = new Engine(EngineConfig.builder().world(8, 8).cellChunks(8).workers(1).maxRegions(4).initialRegions(1)
                .regionEntityCapacity(64).maxEntities(128).rebalanceInterval(0).sampleCapacity(64).pillarDensity(0)
                .chestsPerCell(0).build());
        sy = e.world.surfaceY;
        // Clear the area above the surface.
        for (int x = x0 - 3; x <= x0 + 5; x++) for (int z = z0 - 3; z <= z0 + 3; z++) for (int y = sy; y < sy + 6; y++) {
            e.world.blocks.set(x, y, z, 0);
        }
        r = e.world.regions[e.world.ownerOfBlock(x0, z0)];
    }

    @AfterEach
    void tearDown() {
        e.close();
    }

    int mob(double x, double y, double z, boolean onGround) {
        int eid = r.spawn(x, y, z, Entities.BOT, Entities.IDLE);
        int s = e.world.directory.slot(eid);
        r.table.setFlags(s, onGround ? Entities.FLAG_ON_GROUND : 0);
        return s;
    }

    static int state(int block, String... props) {
        int s = BlockData.defaultState(block);
        for (int i = 0; i < props.length; i += 2) {
            int p = BlockData.property(block, props[i]);
            s = BlockData.with(s, p, BlockData.valueIndex(p, props[i + 1]));
        }
        return s;
    }

    /** Walking into a bottom slab steps up onto it: candidates are {0.5} (0 is the skipped vec.y, 1.0 > 0.6). */
    @Test
    void stepsUpOntoABottomSlab() {
        e.world.blocks.set(x0 + 1, sy, z0, state(BlockId.OAK_SLAB, "type", "bottom"));
        int s = mob(x0 + 0.5, sy, z0 + 0.5, true);
        Physics.move(r, s, 0.5, -0.0784, 0.0);
        assertEquals(x0 + 1.0, r.table.x(s), 1e-12);
        assertEquals(sy + 0.5, r.table.y(s), 1e-12, "stepped up by exactly the slab height");
        assertTrue((r.table.flags(s) & Entities.FLAG_ON_GROUND) != 0);
        assertEquals(0, r.table.flags(s) & Entities.FLAG_H_COLLISION, "no horizontal collision after the step");
    }

    /** A full block is 1.0 high: over the 0.6 step, so the mob stops at its face (x = block − (double) 0.3F). */
    @Test
    void cannotStepAFullBlock() {
        e.world.blocks.set(x0 + 1, sy, z0, Blocks.STONE);
        int s = mob(x0 + 0.5, sy, z0 + 0.5, true);
        Physics.move(r, s, 0.5, -0.0784, 0.0);
        assertEquals(x0 + 1 - (double) 0.3F, r.table.x(s), 0.0, "EntityDimensions: half width 0.6F / 2.0F, widened");
        assertEquals(sy, r.table.y(s), 1e-12);
        assertTrue((r.table.flags(s) & Entities.FLAG_H_COLLISION) != 0);
        assertEquals(0.0, r.table.vx(s));
    }

    /** A lone fence is a 0.25-wide, 1.5-high post (x 0.375..0.625): the mob stops at the post, not the cell edge. */
    @Test
    void fencePostBlocksAtItsFaceAndCannotBeStepped() {
        e.world.blocks.set(x0 + 1, sy, z0, BlockData.defaultState(BlockId.OAK_FENCE));
        int s = mob(x0 + 0.5, sy, z0 + 0.5, true);
        Physics.move(r, s, 0.7, -0.0784, 0.0);
        assertEquals(x0 + 1.375 - (double) 0.3F, r.table.x(s), 0.0);
        assertEquals(sy, r.table.y(s), 1e-12);
    }

    /** Falling onto a carpet lands on its 1/16 top; onto a fence, on its 1.5 top. */
    @Test
    void landsOnPartialShapes() {
        e.world.blocks.set(x0, sy, z0, BlockData.defaultState(BlockId.WHITE_CARPET));
        int s = mob(x0 + 0.5, sy + 0.5, z0 + 0.5, false);
        Physics.move(r, s, 0, -1.0, 0);
        assertEquals(sy + 0.0625, r.table.y(s), 1e-12);
        assertTrue((r.table.flags(s) & Entities.FLAG_ON_GROUND) != 0);

        e.world.blocks.set(x0 + 3, sy, z0, BlockData.defaultState(BlockId.OAK_FENCE));
        int f = mob(x0 + 3.5, sy + 3, z0 + 0.5, false);
        Physics.move(r, f, 0, -2.0, 0);
        assertEquals(sy + 1.5, r.table.y(f), 1e-12);
    }

    /**
     * Moving down, a 1.5-high wall in the layer below must win over a carpet in the nearer layer (its top is
     * higher): the sweep scans one extra layer after the first hit.
     */
    @Test
    void tallShapeOneLayerDownBeatsANearerCarpet() {
        e.world.blocks.set(x0, sy, z0, BlockData.defaultState(BlockId.COBBLESTONE_WALL)); // post x 0.25..0.75, top 1.5
        e.world.blocks.set(x0 + 1, sy, z0, Blocks.STONE);
        e.world.blocks.set(x0 + 1, sy + 1, z0, BlockData.defaultState(BlockId.WHITE_CARPET)); // top sy + 1.0625
        int s = mob(x0 + 1.0, sy + 3, z0 + 0.5, false); // box x 0.7..1.3 overlaps both
        Physics.move(r, s, 0, -2.0, 0);
        assertEquals(sy + 1.5, r.table.y(s), 1e-12);
    }

    /** Stairs: the bottom half (0.5) is stepped; the mob continues over it. */
    @Test
    void stepsOntoStairs() {
        e.world.blocks.set(x0 + 1, sy, z0, state(BlockId.OAK_STAIRS, "facing", "east", "half", "bottom"));
        int s = mob(x0 + 0.5, sy, z0 + 0.5, true);
        Physics.move(r, s, 0.5, -0.0784, 0.0);
        assertEquals(sy + 0.5, r.table.y(s), 1e-12);
        assertEquals(x0 + 1.0, r.table.x(s), 1e-12);
    }
}
