package dev.mulcor.core.region;

import dev.mulcor.core.Blocks;
import dev.mulcor.memory.BlockStorage;

/**
 * Bounded grid A* over a W×W window around the start, owned by one region and reused for every search, so it
 * never allocates. Walkable means feet and head space are free. Returns the first step toward the goal
 * (clamped into the window).
 */
final class Pathfinder {
    static final int W = 24;
    private static final int N = W * W;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private final int[] g = new int[N];
    private final int[] parent = new int[N];
    private final int[] seen = new int[N];
    private final int[] closed = new int[N];
    private final int[] heapNode = new int[4 * N];
    private final int[] heapKey = new int[4 * N];
    private int heapSize;
    private int gen;
    long searches, expansions;

    /** Returns {@code (dx + 1) | (dz + 1) << 2} for the first step, or -1 if unreachable or already there. */
    int step(BlockStorage blocks, int sx, int sy, int sz, int tx, int tz) {
        if (++gen == Integer.MAX_VALUE) {
            java.util.Arrays.fill(seen, 0);
            java.util.Arrays.fill(closed, 0);
            gen = 1;
        }
        searches++;
        int ox = sx - W / 2, oz = sz - W / 2;
        int gx = Math.clamp(tx - ox, 0, W - 1), gz = Math.clamp(tz - oz, 0, W - 1);
        int start = W / 2 + (W / 2) * W, goal = gx + gz * W;
        if (start == goal) return -1;
        heapSize = 0;
        g[start] = 0;
        seen[start] = gen;
        parent[start] = -1;
        push(start, h(W / 2, W / 2, gx, gz));
        while (heapSize > 0) {
            int cur = pop();
            if (closed[cur] == gen) continue;
            closed[cur] = gen;
            expansions++;
            if (cur == goal) return firstStep(cur, start);
            int cx = cur % W, cz = cur / W;
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= W || nz >= W) continue;
                int n = nx + nz * W;
                if (closed[n] == gen) continue;
                int wx = ox + nx, wz = oz + nz;
                if (!blocks.inBounds(wx, sy, wz)
                        || Blocks.isSolid(blocks.getShared(wx, sy, wz))
                        || Blocks.isSolid(blocks.getShared(wx, sy + 1, wz))) continue;
                int ng = g[cur] + 1;
                if (seen[n] != gen || ng < g[n]) {
                    seen[n] = gen;
                    g[n] = ng;
                    parent[n] = cur;
                    push(n, ng + h(nx, nz, gx, gz));
                }
            }
        }
        return -1;
    }

    private static int h(int x, int z, int gx, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz);
    }

    private int firstStep(int node, int start) {
        int prev = node;
        while (parent[prev] != start) prev = parent[prev];
        int dx = prev % W - start % W, dz = prev / W - start / W;
        return (dx + 1) | ((dz + 1) << 2);
    }

    private void push(int node, int key) {
        if (heapSize == heapNode.length) return; // bounded: drop, search stays correct but may be suboptimal
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heapKey[p] <= key) break;
            heapNode[i] = heapNode[p];
            heapKey[i] = heapKey[p];
            i = p;
        }
        heapNode[i] = node;
        heapKey[i] = key;
    }

    private int pop() {
        int top = heapNode[0];
        int lastNode = heapNode[--heapSize], lastKey = heapKey[heapSize];
        int i = 0;
        for (;;) {
            int c = 2 * i + 1;
            if (c >= heapSize) break;
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) c++;
            if (heapKey[c] >= lastKey) break;
            heapNode[i] = heapNode[c];
            heapKey[i] = heapKey[c];
            i = c;
        }
        heapNode[i] = lastNode;
        heapKey[i] = lastKey;
        return top;
    }
}
