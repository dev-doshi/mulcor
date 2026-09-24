package dev.mulcor.core.grid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PartitionTest {
    private static void assertConsistent(Partition p) {
        int[] counts = new int[p.maxRegions()];
        for (int c = 0; c < p.cells(); c++) {
            int r = p.regionOf(c);
            assertTrue(p.isActive(r), "cell " + c + " mapped to inactive region " + r);
            counts[r]++;
        }
        int active = 0;
        for (int r = 0; r < p.maxRegions(); r++) {
            assertEquals(counts[r], p.cellCount(r), "cell count of region " + r);
            if (p.isActive(r)) {
                active++;
                assertTrue(counts[r] > 0, "active region " + r + " has no cells");
            }
        }
        assertEquals(active, p.activeCount());
    }

    @Test
    void powerOfFourInitialRegionsAreSquares() {
        var p = new Partition(16, 16, 64, 2.0);
        p.initialize(16);
        assertConsistent(p);
        for (int r = 0; r < 16; r++) {
            assertEquals(16, p.cellCount(r));
            assertTrue(p.compactness(r) <= 1.0 + 1e-9, "region " + r + " should be a 4x4 square");
        }
    }

    @Test
    void splitBalancesCostAndMergeRestores() {
        var p = new Partition(8, 8, 8, 2.0);
        p.initialize(1);
        long[] cost = new long[64];
        for (int c = 0; c < 64; c++) cost[c] = (c % 8) < 2 ? 100 : 0; // heavy strip along x = 0..1
        int fresh = p.split(0, cost);
        assertTrue(fresh > 0);
        assertConsistent(p);
        long a = 0, b = 0;
        for (int c = 0; c < 64; c++) {
            if (p.regionOf(c) == 0) a += cost[c] + 1; else b += cost[c] + 1;
        }
        assertTrue(Math.abs(a - b) <= (a + b) / 2, "split should roughly balance cost: " + a + " vs " + b);
        assertTrue(p.merge(0, fresh));
        assertConsistent(p);
        assertEquals(1, p.activeCount());
        assertEquals(64, p.cellCount(0));
    }

    @Test
    void singleCellAndFullSlotsRefuseToSplit() {
        var p = new Partition(2, 1, 2, 2.0);
        p.initialize(2);
        assertEquals(Partition.NONE, p.split(0, new long[2]));
        var q = new Partition(4, 4, 1, 2.0);
        q.initialize(1);
        assertEquals(Partition.NONE, q.split(0, new long[16]), "no free region slot");
    }

    @Test
    void mergeRequiresAdjacency() {
        var p = new Partition(4, 1, 4, 10.0);
        p.initialize(4);
        int left = p.regionAt(0, 0), right = p.regionAt(3, 0);
        assertFalse(p.adjacent(left, right));
        assertFalse(p.merge(left, right));
    }

    /**
     * 10k random splits and merges under random, clustered load. Every region must stay within the shape rule
     * the whole time, every cell must stay mapped to an active region, and boundary traffic must stay bounded.
     */
    @Test
    void randomizedRebalancingKeepsShapesCompact() {
        var rnd = new SplittableRandom(7);
        var p = new Partition(16, 16, 64, 2.0);
        p.initialize(16);
        long[] cost = new long[p.cells()];
        int maxBoundary = 0, splits = 0, merges = 0;
        for (int iter = 0; iter < 10_000; iter++) {
            // clustered load: a few hotspots that wander
            java.util.Arrays.fill(cost, 0);
            for (int h = 0; h < 3; h++) {
                int hx = rnd.nextInt(16), hz = rnd.nextInt(16);
                for (int k = 0; k < 40; k++) {
                    int x = Math.clamp(hx + rnd.nextInt(5) - 2, 0, 15), z = Math.clamp(hz + rnd.nextInt(5) - 2, 0, 15);
                    cost[z * 16 + x] += rnd.nextInt(100);
                }
            }
            if (rnd.nextBoolean()) {
                int r = rnd.nextInt(64);
                if (p.isActive(r) && p.split(r, cost) >= 0) splits++;
            } else {
                int a = rnd.nextInt(64), b = rnd.nextInt(64);
                if (p.merge(a, b)) merges++;
            }
            for (int r = 0; r < 64; r++) {
                if (p.isActive(r)) {
                    assertTrue(p.compactness(r) <= 2.0 + 1e-9,
                            "iteration " + iter + ": region " + r + " compactness " + p.compactness(r));
                }
            }
            maxBoundary = Math.max(maxBoundary, p.boundaryEdges());
            if (iter % 1000 == 0) assertConsistent(p);
        }
        assertConsistent(p);
        assertTrue(splits > 100 && merges > 100, "exercise both operations: splits=" + splits + " merges=" + merges);
        System.out.printf("partition: splits=%d merges=%d maxBoundaryEdges=%d (of %d internal edges)%n",
                splits, merges, maxBoundary, 2 * 16 * 15);
    }
}
