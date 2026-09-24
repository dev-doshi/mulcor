package dev.mulcor.core;

import static dev.mulcor.core.RedstoneParityTest.state;
import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.registry.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pistons against the timings and rules of the vanilla 26.2 code they port ({@code PistonBaseBlock},
 * {@code PistonStructureResolver}, {@code PistonMovingBlockEntity}): the block event runs in the next tick, the moved
 * blocks are {@code moving_piston}s for that tick and the next, and land on the third. These expectations are derived
 * from the code, not recorded on a vanilla server; recorded traces should replace them.
 */
class PistonTest {
    private static final int OX = 66, OZ = 66;
    private Engine e;
    private int oy;

    @BeforeEach
    void setUp() {
        e = new Engine(small().pillarDensity(0).chestsPerCell(0).build());
        oy = e.world.surfaceY;
        for (int x = -4; x <= 20; x++) {
            for (int z = -4; z <= 8; z++) {
                e.world.blocks.set(OX + x, oy - 1, OZ + z, Blocks.STONE);
                for (int y = 0; y <= 4; y++) e.world.blocks.set(OX + x, oy + y, OZ + z, Blocks.AIR);
            }
        }
    }

    @AfterEach
    void tearDown() {
        e.close();
    }

    private void set(int x, int y, int z, String spec) {
        e.setBlockCommand(OX + x, oy + y, OZ + z, state(spec));
    }

    private String name(int x, int y, int z) {
        String n = BlockData.name(BlockData.block(e.world.blocks.get(OX + x, oy + y, OZ + z)));
        return n.substring(n.indexOf(':') + 1);
    }

    private boolean extended(int x, int y, int z) {
        int st = e.world.blocks.get(OX + x, oy + y, OZ + z);
        return BlockData.boolValue(st, BlockData.property(BlockData.block(st), "extended"));
    }

    @Test
    void extendsPushingABlockAndRetracts() {
        set(0, 0, 0, "piston[facing=east]");
        set(1, 0, 0, "stone");
        e.run(2);
        set(-1, 0, 0, "redstone_block");
        assertFalse(extended(0, 0, 0), "the block event waits for the next tick");
        e.tick();
        assertTrue(extended(0, 0, 0));
        assertEquals("moving_piston", name(1, 0, 0));
        assertEquals("moving_piston", name(2, 0, 0));
        e.tick();
        assertEquals("moving_piston", name(2, 0, 0));
        e.tick();
        assertEquals("piston_head", name(1, 0, 0));
        assertEquals("stone", name(2, 0, 0));

        set(-1, 0, 0, "air");
        e.tick();
        assertEquals("moving_piston", name(0, 0, 0), "the retracting base is a moving piston");
        assertEquals("air", name(1, 0, 0), "a normal piston's head goes at once");
        e.run(2);
        assertEquals("piston", name(0, 0, 0));
        assertFalse(extended(0, 0, 0));
        assertEquals("stone", name(2, 0, 0), "a normal piston does not pull");
    }

    @Test
    void stickyPistonPullsTheBlockBack() {
        set(0, 0, 0, "sticky_piston[facing=east]");
        set(1, 0, 0, "stone");
        e.run(2);
        set(-1, 0, 0, "redstone_block");
        e.run(3);
        assertEquals("stone", name(2, 0, 0));
        set(-1, 0, 0, "air");
        e.tick();
        assertEquals("moving_piston", name(1, 0, 0));
        assertEquals("air", name(2, 0, 0));
        e.run(2);
        assertEquals("stone", name(1, 0, 0));
        assertEquals("sticky_piston", name(0, 0, 0));
        assertFalse(extended(0, 0, 0));
    }

    @Test
    void twelveBlocksMoveButThirteenDoNot() {
        set(0, 0, 0, "piston[facing=east]");
        for (int i = 1; i <= 12; i++) set(i, 0, 0, "stone");
        e.run(2);
        set(-1, 0, 0, "redstone_block");
        e.run(3);
        assertTrue(extended(0, 0, 0));
        assertEquals("stone", name(13, 0, 0));

        set(0, 0, 2, "piston[facing=east]");
        for (int i = 1; i <= 13; i++) set(i, 0, 2, "stone");
        e.run(2);
        set(-1, 0, 2, "redstone_block");
        e.run(3);
        assertFalse(extended(0, 0, 2));
        assertEquals("stone", name(1, 0, 2));
        assertEquals("air", name(14, 0, 2));
    }

    @Test
    void obsidianCannotBePushed() {
        set(0, 0, 0, "piston[facing=east]");
        set(1, 0, 0, "obsidian");
        e.run(2);
        set(-1, 0, 0, "redstone_block");
        e.run(3);
        assertFalse(extended(0, 0, 0));
        assertEquals("obsidian", name(1, 0, 0));
    }

    /** A slime block drags the block on top of it along (one layer up, clear of the floor it would also stick to). */
    @Test
    void slimeBlockCarriesWhatSticksToIt() {
        set(0, 1, 0, "piston[facing=east]");
        set(1, 1, 0, "slime_block");
        set(1, 2, 0, "stone");
        e.run(2);
        set(-1, 1, 0, "redstone_block");
        e.run(3);
        assertEquals("slime_block", name(2, 1, 0));
        assertEquals("stone", name(2, 2, 0));
        assertEquals("air", name(1, 2, 0));
    }

    /**
     * Quasi-connectivity: a redstone block two above powers the piston (it counts power into the space above it) but
     * gives it no update; the piston only extends once something next to it changes.
     */
    @Test
    void quasiConnectivityNeedsAnUpdate() {
        set(0, 0, 0, "piston[facing=east]");
        set(0, 1, 0, "glass");
        e.run(2);
        set(0, 2, 0, "redstone_block");
        e.run(4);
        assertFalse(extended(0, 0, 0), "budded");
        set(0, 0, 1, "stone");
        e.run(1);
        assertTrue(extended(0, 0, 0));
    }

    @Test
    void breakingTheHeadBreaksThePiston() {
        set(0, 0, 0, "piston[facing=east]");
        e.run(2);
        set(-1, 0, 0, "redstone_block");
        e.run(3);
        assertEquals("piston_head", name(1, 0, 0));
        set(1, 0, 0, "air");
        assertEquals("air", name(0, 0, 0));
    }
}
