package dev.mulcor.core.light;

import static dev.mulcor.core.light.FaceOcclusion.DX;
import static dev.mulcor.core.light.FaceOcclusion.DY;
import static dev.mulcor.core.light.FaceOcclusion.DZ;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.registry.BlockData;

/**
 * Vanilla block and sky light over off-heap {@link BlockStorage} / {@link LightStorage}.
 *
 * <h2>Specification (vanilla 1.20+ light engine, Mojang mappings)</h2>
 * <ul>
 *   <li><b>Opacity</b> ({@code LightEngine.getOpacity}): {@code max(1, state.getLightBlock())}. Light entering a
 *       block loses the <i>destination</i> block's opacity: {@code newLevel = level - opacity(to)}.</li>
 *   <li><b>Directional occlusion</b>: light never crosses a face where {@link FaceOcclusion#occludes} holds.</li>
 *   <li><b>Block light</b> ({@code BlockLightEngine}): every state emits {@code getLightEmission()} at its own
 *       position (even if opaque); the stored level is the maximum over its emission and every neighbour's
 *       {@code level - opacity(this)}. Levels ≤ 1 do not spread further (1 − 1 = 0).</li>
 *   <li><b>Sky light</b> ({@code SkyLightEngine} + {@code ChunkSkyLightSources}): per column, the lowest sky source
 *       is {@code y + 1} for the highest {@code y} where {@code isEdgeOccluded(state(y + 1), state(y))} (the block at
 *       y has non-zero light block, or the faces between y+1 and y occlude). Every position from the source up is
 *       15; below it, light spreads like block light (lateral and downward moves both cost the opacity), from
 *       every 15-level position whose neighbour is not a source.</li>
 *   <li>Both results are the unique maximal fixed point of those rules, so the propagation order does not
 *       change the stored values: a breadth-first flood gives vanilla's exact numbers.</li>
 * </ul>
 * The light storages extend one section below and above the block storage, as vanilla's light sections do;
 * positions there (and above the world) are air. The finite window's x/z edges are hard walls.
 *
 * <p>Queues are primitive rings reused across calls. Single-threaded: a region runs its own light.
 */
public final class LightEngine {
    private final BlockStorage blocks;
    private final LightStorage blockLight, skyLight;
    private final int sizeX, sizeZ, minY, maxY; // light bounds (one section beyond the blocks on each side)
    private final int[] skySource; // per column (z * sizeX + x): lowest y that is a sky source
    private long[] queue = new long[1 << 17];
    private int head, tail;
    private long[] dec = new long[1 << 16];
    private int dHead, dTail;

    public LightEngine(BlockStorage blocks, LightStorage blockLight, LightStorage skyLight) {
        this.blocks = blocks;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.sizeX = blocks.sizeX();
        this.sizeZ = blocks.sizeZ();
        this.minY = blockLight.minY();
        this.maxY = blockLight.maxYExclusive();
        this.skySource = new int[sizeX * sizeZ];
    }

    /**
     * Per state: everything light depends on, packed (emission, light block, and the occlusion shape when light
     * uses it). Two states with equal signatures are interchangeable for block and sky light.
     */
    private static final int[] SIGNATURE;

    static {
        SIGNATURE = new int[dev.mulcor.registry.Registry.stateCount()];
        for (int s = 0; s < SIGNATURE.length; s++) {
            int shape = FaceOcclusion.emptyShape(s) ? 0 : BlockData.occlusionShape(s) + 1;
            SIGNATURE[s] = BlockData.lightEmission(s) | BlockData.lightBlock(s) << 4 | shape << 8;
        }
    }

    /**
     * Can replacing {@code from} with {@code to} change any light level? Vanilla's trigger
     * ({@code LevelChunk.setBlockState} → {@code checkBlock} when {@code LightEngine.hasDifferentLightProperties}:
     * {@code a != b && (lightDampening differs || lightEmission differs || a or b useShapeForLightOcclusion)})
     * re-checks a superset of these changes; since light is the unique fixed point of the engine's inputs, a change
     * that leaves all of them equal leaves every level equal, so skipping it gives the same result.
     */
    public static boolean affectsLight(int from, int to) {
        return SIGNATURE[from] != SIGNATURE[to];
    }

    /** Vanilla {@code LightEngine.getOpacity}. */
    public static int opacity(int state) {
        return Math.max(1, BlockData.lightBlock(state));
    }

    private int state(int x, int y, int z) {
        return blocks.getShared(x, y, z); // out-of-range y reads as air; the light thread does not own chunks
    }

    // ---- queue: x:20 | z:20 | y-minY:12 | level:4 ----

    private void push(int x, int y, int z, int level) {
        if (tail - head == queue.length) grow();
        queue[tail++ & (queue.length - 1)] = (long) x << 36 | (long) z << 16 | (long) (y - minY) << 4 | level;
    }

    /**
     * Capacity warm-up: the queues double when a flood outgrows them and never shrink, so this runs at most a few
     * times over the world's life (never in steady state).
     */
    private void grow() {
        long[] bigger = new long[queue.length * 2];
        for (int i = head; i < tail; i++) bigger[i & (bigger.length - 1)] = queue[i & (queue.length - 1)];
        queue = bigger;
    }

    // ================================================================================================ full relight

    /** Recompute all block and sky light of the window from scratch. */
    public void relightAll() {
        relightBlock();
        relightSky();
    }

    public void relightBlock() {
        for (int cx = 0; cx < sizeX >> 4; cx++) {
            for (int cz = 0; cz < sizeZ >> 4; cz++) {
                for (int s = blockLight.minSection(); s < blockLight.minSection() + blockLight.sections(); s++) {
                    blockLight.fillSection(cx, cz, s, 0);
                }
            }
        }
        head = tail = 0;
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                for (int y = blocks.minY(); y < blocks.maxYExclusive(); y++) {
                    int e = BlockData.lightEmission(state(x, y, z));
                    if (e > 0) {
                        blockLight.set(x, y, z, e);
                        if (e > 1) push(x, y, z, e);
                    }
                }
            }
        }
        flood(blockLight);
    }

    public void relightSky() {
        for (int cx = 0; cx < sizeX >> 4; cx++) {
            for (int cz = 0; cz < sizeZ >> 4; cz++) {
                for (int s = skyLight.minSection(); s < skyLight.minSection() + skyLight.sections(); s++) {
                    skyLight.fillSection(cx, cz, s, 0);
                }
            }
        }
        for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) skySource[z * sizeX + x] = findSource(x, z);
        head = tail = 0;
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int src = skySource[z * sizeX + x];
                for (int y = src; y < maxY; y++) skyLight.set(x, y, z, 15);
                // Seed only 15s that border a non-source: the column's lowest source, and the lateral faces that
                // look into a neighbour column's shadow.
                int top = src + 1;
                for (int d = 2; d < 6; d++) {
                    int nx = x + DX[d], nz = z + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) continue;
                    top = Math.max(top, skySource[nz * sizeX + nx]);
                }
                for (int y = src; y < Math.min(top, maxY); y++) push(x, y, z, 15);
            }
        }
        flood(skyLight);
    }

    /** Vanilla {@code ChunkSkyLightSources.findLowestSourceY} for one column. */
    private int findSource(int x, int z) {
        int above = 0; // air above the top
        for (int y = maxY - 1; y >= minY; y--) {
            int below = state(x, y, z);
            if (BlockData.lightBlock(below) != 0 || FaceOcclusion.occludes(above, below, FaceOcclusion.DOWN)) return y + 1;
            above = below;
        }
        return minY;
    }

    /** Breadth-first increase propagation of everything queued into {@code light}. */
    private void flood(LightStorage light) {
        while (head != tail) {
            long e = queue[head++ & (queue.length - 1)];
            int x = (int) (e >>> 36), z = (int) (e >>> 16) & 0xF_FFFF, y = (int) (e >>> 4 & 0xFFF) + minY;
            int level = (int) (e & 15);
            if (light.get(x, y, z) != level) continue; // superseded by a brighter path
            int from = state(x, y, z);
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ || ny < minY || ny >= maxY) continue;
                int to = state(nx, ny, nz);
                int next = level - opacity(to);
                if (next <= light.get(nx, ny, nz)) continue;
                if (FaceOcclusion.occludes(from, to, d)) continue;
                light.set(nx, ny, nz, next);
                if (next > 1) push(nx, ny, nz, next);
            }
        }
        head = tail = 0;
    }

    // ================================================================================================ incremental

    /**
     * Update light after the block at (x, y, z) changed (its new state is already in the block storage). Vanilla
     * ({@code LightEngine.checkBlock}) re-evaluates the position: the light that may have depended on it is removed
     * (decrease pass: every neighbour darker than the removed level is cleared and cleared outward; brighter or
     * equal neighbours, emitters and sky sources become re-propagation seeds), then everything is re-flooded
     * (increase pass). The result is the same fixed point as a full relight; {@code LightEngineTest} checks that
     * after random edits.
     */
    public void onBlockChanged(int x, int y, int z) {
        updateBlock(x, y, z);
        updateSky(x, y, z);
    }

    private void updateBlock(int x, int y, int z) {
        head = tail = 0;
        dHead = dTail = 0;
        int old = blockLight.get(x, y, z);
        if (old > 0) {
            blockLight.set(x, y, z, 0);
            pushDec(x, y, z, old);
        }
        decrease(blockLight, false);
        int e = BlockData.lightEmission(state(x, y, z));
        if (e > blockLight.get(x, y, z)) {
            blockLight.set(x, y, z, e);
            push(x, y, z, e);
        }
        seedNeighbours(blockLight, x, y, z);
        flood(blockLight);
    }

    private void updateSky(int x, int y, int z) {
        head = tail = 0;
        dHead = dTail = 0;
        int col = z * sizeX + x;
        int oldSrc = skySource[col], newSrc = findSource(x, z);
        skySource[col] = newSrc;
        if (newSrc > oldSrc) {
            // The column closed higher up: positions [oldSrc, newSrc) are no longer sources.
            for (int yy = oldSrc; yy < newSrc; yy++) {
                int l = skyLight.get(x, yy, z);
                if (l > 0) {
                    skyLight.set(x, yy, z, 0);
                    pushDec(x, yy, z, l);
                }
            }
        } else if (newSrc < oldSrc) {
            // The column opened: [newSrc, oldSrc) become 15 and spread.
            for (int yy = newSrc; yy < oldSrc; yy++) {
                skyLight.set(x, yy, z, 15);
                push(x, yy, z, 15);
            }
        }
        if (y < newSrc) {
            int l = skyLight.get(x, y, z);
            if (l > 0) {
                skyLight.set(x, y, z, 0);
                pushDec(x, y, z, l);
            }
        }
        decrease(skyLight, true);
        seedNeighbours(skyLight, x, y, z);
        flood(skyLight);
    }

    /** Neighbours may now shine into (x, y, z): queue them at their current levels. */
    private void seedNeighbours(LightStorage light, int x, int y, int z) {
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
            if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ || ny < minY || ny >= maxY) continue;
            int l = light.get(nx, ny, nz);
            if (l > 1) push(nx, ny, nz, l);
        }
        int l = light.get(x, y, z);
        if (l > 1) push(x, y, z, l);
    }

    private void pushDec(int x, int y, int z, int level) {
        if (dTail - dHead == dec.length) growDec();
        dec[dTail++ & (dec.length - 1)] = (long) x << 36 | (long) z << 16 | (long) (y - minY) << 4 | level;
    }

    /** Capacity warm-up (see {@link #grow}). */
    private void growDec() {
        long[] bigger = new long[dec.length * 2];
        for (int i = dHead; i < dTail; i++) bigger[i & (bigger.length - 1)] = dec[i & (dec.length - 1)];
        dec = bigger;
    }

    /**
     * Decrease pass: from each removed (position, old level), clear neighbours that may have been lit through it
     * (level below the removed one) and continue from them; neighbours at least as bright, emitters and sky sources
     * are queued for the increase pass instead.
     */
    private void decrease(LightStorage light, boolean sky) {
        while (dHead != dTail) {
            long e = dec[dHead++ & (dec.length - 1)];
            int x = (int) (e >>> 36), z = (int) (e >>> 16) & 0xF_FFFF, y = (int) (e >>> 4 & 0xFFF) + minY;
            int removed = (int) (e & 15);
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ || ny < minY || ny >= maxY) continue;
                int l = light.get(nx, ny, nz);
                if (l == 0) continue;
                boolean source = sky ? ny >= skySource[nz * sizeX + nx] : false;
                if (l < removed && !source) {
                    light.set(nx, ny, nz, 0);
                    pushDec(nx, ny, nz, l);
                    if (!sky) {
                        int em = BlockData.lightEmission(state(nx, ny, nz));
                        if (em > 0) {
                            light.set(nx, ny, nz, em);
                            push(nx, ny, nz, em);
                        }
                    }
                } else if (l > 1) {
                    push(nx, ny, nz, l); // a brighter neighbour (or a source) refills the cleared area
                }
            }
        }
        dHead = dTail = 0;
    }

    /** Lowest sky-source y of a column (after {@link #relightSky}). */
    public int skySource(int x, int z) {
        return skySource[z * sizeX + x];
    }
}
