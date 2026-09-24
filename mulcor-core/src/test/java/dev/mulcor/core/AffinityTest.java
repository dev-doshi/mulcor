package dev.mulcor.core;

import static dev.mulcor.core.RedstoneParityTest.state;
import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Affinity coalescing: blocks that interact with no delay across a cell edge put both cells in one region at the next
 * commit, and splits never separate them again. Only with a dynamic partition ({@code rebalanceInterval > 0}).
 */
class AffinityTest {
    /** An x boundary (block coordinate) between two cells of different regions, and a z inside both cells. */
    private static int[] border(Engine e) {
        var p = e.world.partition;
        int cb = e.world.cellBlocks();
        for (int cz = 0; cz < p.cellsZ(); cz++) {
            for (int cx = 0; cx + 1 < p.cellsX(); cx++) {
                if (p.regionAt(cx, cz) != p.regionAt(cx + 1, cz)) return new int[] {(cx + 1) * cb, cz * cb + cb / 2};
            }
        }
        throw new AssertionError("no region border");
    }

    private static Engine engine(boolean affinity) {
        // Rebalancing on (so affinity applies) but so rare that no ordinary split or merge happens in the test.
        return new Engine(small().pillarDensity(0).chestsPerCell(0).rebalanceInterval(1_000_000).affinity(affinity).build());
    }

    @Test
    void wireAcrossABorderMergesTheRegions() {
        try (var e = engine(true)) {
            int[] b = border(e);
            int x = b[0], z = b[1], y = e.world.surfaceY;
            assertNotEquals(e.world.ownerOfBlock(x - 1, z), e.world.ownerOfBlock(x, z));
            e.setBlockCommand(x - 1, y, z, state("redstone_wire"));
            e.setBlockCommand(x, y, z, state("redstone_wire"));
            e.tick();
            assertEquals(e.world.ownerOfBlock(x - 1, z), e.world.ownerOfBlock(x, z));
            assertTrue(e.affinityMerges >= 1);
        }
    }

    @Test
    void withoutAffinityTheRegionsStaySeparate() {
        try (var e = engine(false)) {
            int[] b = border(e);
            int x = b[0], z = b[1], y = e.world.surfaceY;
            e.setBlockCommand(x - 1, y, z, state("redstone_wire"));
            e.setBlockCommand(x, y, z, state("redstone_wire"));
            e.run(3);
            assertNotEquals(e.world.ownerOfBlock(x - 1, z), e.world.ownerOfBlock(x, z));
            assertEquals(0, e.affinityMerges);
        }
    }

    /** A piston facing a border within its reach bonds the edge its push line crosses, then pushes across it. */
    @Test
    void pistonNearABorderPushesAcrossAfterTheMerge() {
        try (var e = engine(true)) {
            int[] b = border(e);
            int x = b[0], z = b[1], y = e.world.surfaceY;
            e.setBlockCommand(x - 3, y, z, state("piston[facing=east]"));
            e.setBlockCommand(x - 2, y, z, state("stone"));
            e.setBlockCommand(x - 1, y, z, state("stone"));
            e.tick();
            assertEquals(e.world.ownerOfBlock(x - 3, z), e.world.ownerOfBlock(x + 1, z), "bonded before it fires");
            e.setBlockCommand(x - 4, y, z, state("redstone_block"));
            e.run(3);
            assertEquals("minecraft:stone", dev.mulcor.registry.BlockData.name(
                    dev.mulcor.registry.BlockData.block(e.world.blocks.get(x, y, z))), "pushed across the old border");
        }
    }
}
