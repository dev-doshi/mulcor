package dev.mulcor.core.grid;

import java.util.Arrays;

/**
 * Assigns world cells to regions and decides how regions split and merge.
 *
 * <ul>
 *   <li>Regions start as equal ranges of the Hilbert order, which keeps them spatially compact.</li>
 *   <li>A split cuts a region's Hilbert-ordered cells at the cost-balanced point that satisfies the shape
 *       rule. If no Hilbert cut qualifies, it falls back to a quadtree-style AABB bisection of the region's
 *       bounding box on its longest axis.</li>
 *   <li>A merge combines two edge-adjacent regions.</li>
 * </ul>
 *
 * <p><b>Shape invariant:</b> every region created by a split or merge satisfies
 * {@code compactness = perimeter / (4·√area) ≤ maxCompactness}. A square scores 1.0; long strips and ragged
 * shapes score higher. Perimeter counts only cell edges shared with other regions (world borders cost
 * nothing), because those edges are what generate mailbox traffic.
 *
 * <p>Not thread-safe: the engine mutates the partition only in the single-threaded commit phase between epochs.
 */
public final class Partition {
    public static final int NONE = -1;

    private final int cellsX, cellsZ, cells, maxRegions;
    private final double maxCompactness;
    private final int[] cellRegion;
    private final int[] hilbertOrder;
    private final boolean[] active;
    private final int[] cellCount;
    private int activeCount;

    // scratch, reused by every operation: no allocation after construction
    private final int[] tmp, tmpA, tmpB, stamp;
    private final long[] prefix, axisCost;
    private int generation;

    public Partition(int cellsX, int cellsZ, int maxRegions, double maxCompactness) {
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        this.cells = cellsX * cellsZ;
        this.maxRegions = maxRegions;
        this.maxCompactness = maxCompactness;
        this.cellRegion = new int[cells];
        this.active = new boolean[maxRegions];
        this.cellCount = new int[maxRegions];
        this.tmp = new int[cells];
        this.tmpA = new int[cells];
        this.tmpB = new int[cells];
        this.stamp = new int[cells];
        this.prefix = new long[cells + 1];
        this.axisCost = new long[Math.max(cellsX, cellsZ) + 1];

        int n = Hilbert.ceilPow2(Math.max(cellsX, cellsZ));
        long[] keyed = new long[cells];
        for (int c = 0; c < cells; c++) {
            keyed[c] = (Hilbert.xy2d(n, c % cellsX, c / cellsX) << 32) | c;
        }
        Arrays.sort(keyed);
        this.hilbertOrder = new int[cells];
        for (int i = 0; i < cells; i++) hilbertOrder[i] = (int) keyed[i];
    }

    /** Divide the Hilbert order into {@code regions} equal ranges, ids {@code 0..regions-1}. */
    public void initialize(int regions) {
        if (regions < 1 || regions > maxRegions || regions > cells) {
            throw new IllegalArgumentException("regions=" + regions);
        }
        Arrays.fill(active, false);
        Arrays.fill(cellCount, 0);
        for (int i = 0; i < cells; i++) {
            int r = (int) ((long) i * regions / cells);
            cellRegion[hilbertOrder[i]] = r;
            cellCount[r]++;
        }
        for (int r = 0; r < regions; r++) active[r] = true;
        activeCount = regions;
    }

    public int cellsX() { return cellsX; }
    public int cellsZ() { return cellsZ; }
    public int cells() { return cells; }
    public int maxRegions() { return maxRegions; }
    public int activeCount() { return activeCount; }
    public boolean isActive(int r) { return active[r]; }
    public int cellCount(int r) { return cellCount[r]; }
    public int regionOf(int cell) { return cellRegion[cell]; }
    public int regionAt(int cellX, int cellZ) { return cellRegion[cellZ * cellsX + cellX]; }
    public double maxCompactness() { return maxCompactness; }

    // ---- geometry ----------------------------------------------------------------------------------------------

    private int nextStamp() {
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
        return generation;
    }

    /** Edges from marked cells to in-world cells that are not marked. */
    private int perimeterOfMarked(int[] list, int from, int to, int mark) {
        int p = 0;
        for (int i = from; i < to; i++) {
            int c = list[i];
            int x = c % cellsX, z = c / cellsX;
            if (x > 0 && stamp[c - 1] != mark) p++;
            if (x < cellsX - 1 && stamp[c + 1] != mark) p++;
            if (z > 0 && stamp[c - cellsX] != mark) p++;
            if (z < cellsZ - 1 && stamp[c + cellsX] != mark) p++;
        }
        return p;
    }

    private double compactnessOf(int[] list, int from, int to) {
        int mark = nextStamp();
        for (int i = from; i < to; i++) stamp[list[i]] = mark;
        return perimeterOfMarked(list, from, to, mark) / (4.0 * Math.sqrt(to - from));
    }

    private int gather(int r, int[] out) {
        int m = 0;
        for (int i = 0; i < cells; i++) {
            int c = hilbertOrder[i];
            if (cellRegion[c] == r) out[m++] = c;
        }
        return m;
    }

    public double compactness(int r) {
        int m = gather(r, tmp);
        return m == 0 ? 0 : compactnessOf(tmp, 0, m);
    }

    /** Region-boundary cell edges across the world: a proxy for cross-region message traffic. */
    public int boundaryEdges() {
        int e = 0;
        for (int c = 0; c < cells; c++) {
            int x = c % cellsX, z = c / cellsX;
            if (x < cellsX - 1 && cellRegion[c] != cellRegion[c + 1]) e++;
            if (z < cellsZ - 1 && cellRegion[c] != cellRegion[c + cellsX]) e++;
        }
        return e;
    }

    public boolean adjacent(int a, int b) {
        for (int c = 0; c < cells; c++) {
            if (cellRegion[c] != a) continue;
            int x = c % cellsX, z = c / cellsX;
            if (x > 0 && cellRegion[c - 1] == b) return true;
            if (x < cellsX - 1 && cellRegion[c + 1] == b) return true;
            if (z > 0 && cellRegion[c - cellsX] == b) return true;
            if (z < cellsZ - 1 && cellRegion[c + cellsX] == b) return true;
        }
        return false;
    }

    private int freeSlot() {
        for (int r = 0; r < maxRegions; r++) if (!active[r]) return r;
        return NONE;
    }

    // ---- split -------------------------------------------------------------------------------------------------

    /**
     * Split region {@code r} into two parts of roughly equal {@code cellCost}. Returns the new region's id, or
     * {@link #NONE} if the region is a single cell, there is no free region slot, or every candidate cut would
     * break the shape rule.
     */
    public int split(int r, long[] cellCost) {
        if (!active[r]) return NONE;
        int m = gather(r, tmp);
        if (m < 2) return NONE;
        int fresh = freeSlot();
        if (fresh == NONE) return NONE;

        prefix[0] = 0;
        for (int i = 0; i < m; i++) prefix[i + 1] = prefix[i] + cellCost[tmp[i]] + 1;
        long total = prefix[m];
        int best = 1;
        long bestImbalance = Long.MAX_VALUE;
        for (int k = 1; k < m; k++) {
            long imbalance = Math.abs(2 * prefix[k] - total);
            if (imbalance < bestImbalance) {
                bestImbalance = imbalance;
                best = k;
            }
        }
        // Hilbert cuts, nearest-to-balanced first; balance within ±25% of the region is acceptable.
        long tolerance = total / 4;
        for (int delta = 0; delta < m; delta++) {
            for (int sign = 0; sign < 2; sign++) {
                int k = sign == 0 ? best - delta : best + delta;
                if (k < 1 || k >= m || (delta == 0 && sign == 1)) continue;
                if (Math.abs(2 * prefix[k] - total) > bestImbalance + 2 * tolerance) continue;
                if (compactnessOf(tmp, 0, k) <= maxCompactness && compactnessOf(tmp, k, m) <= maxCompactness) {
                    assign(tmp, k, m, r, fresh);
                    return fresh;
                }
            }
        }
        return aabbSplit(r, fresh, m, cellCost);
    }

    /** Quadtree-style fallback: bisect the bounding box on its longest axis at the cost-weighted median. */
    private int aabbSplit(int r, int fresh, int m, long[] cellCost) {
        int minX = Integer.MAX_VALUE, maxX = -1, minZ = Integer.MAX_VALUE, maxZ = -1;
        for (int i = 0; i < m; i++) {
            int x = tmp[i] % cellsX, z = tmp[i] / cellsX;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        boolean alongX = (maxX - minX) >= (maxZ - minZ);
        int lo = alongX ? minX : minZ, hi = alongX ? maxX : maxZ;
        if (lo == hi) return NONE;
        Arrays.fill(axisCost, 0);
        long total = 0;
        for (int i = 0; i < m; i++) {
            int coord = alongX ? tmp[i] % cellsX : tmp[i] / cellsX;
            long cost = cellCost[tmp[i]] + 1;
            axisCost[coord] += cost;
            total += cost;
        }
        long acc = 0;
        int cut = lo + 1;
        for (int c = lo; c < hi; c++) {
            acc += axisCost[c];
            cut = c + 1;
            if (2 * acc >= total) break;
        }
        int na = 0, nb = 0;
        for (int i = 0; i < m; i++) {
            int coord = alongX ? tmp[i] % cellsX : tmp[i] / cellsX;
            if (coord < cut) tmpA[na++] = tmp[i]; else tmpB[nb++] = tmp[i];
        }
        if (na == 0 || nb == 0) return NONE;
        if (compactnessOf(tmpA, 0, na) > maxCompactness || compactnessOf(tmpB, 0, nb) > maxCompactness) {
            return NONE;
        }
        assign(tmpB, 0, nb, r, fresh);
        return fresh;
    }

    private void assign(int[] list, int from, int to, int oldRegion, int newRegion) {
        for (int i = from; i < to; i++) cellRegion[list[i]] = newRegion;
        int moved = to - from;
        cellCount[oldRegion] -= moved;
        cellCount[newRegion] = moved;
        active[newRegion] = true;
        activeCount++;
    }

    // ---- merge -------------------------------------------------------------------------------------------------

    /** Absorb region {@code b} into {@code a}, if they are adjacent and the union keeps the shape rule. */
    public boolean merge(int a, int b) {
        if (a == b || !active[a] || !active[b] || !adjacent(a, b)) return false;
        int na = gather(a, tmp);
        int nb = gather(b, tmpA);
        System.arraycopy(tmpA, 0, tmp, na, nb);
        if (compactnessOf(tmp, 0, na + nb) > maxCompactness) return false;
        for (int i = 0; i < nb; i++) cellRegion[tmpA[i]] = a;
        cellCount[a] += nb;
        cellCount[b] = 0;
        active[b] = false;
        activeCount--;
        return true;
    }
}
