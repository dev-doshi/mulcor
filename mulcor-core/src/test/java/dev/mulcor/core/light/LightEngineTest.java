package dev.mulcor.core.light;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Known vanilla light values in small hand-built scenes (the real-world oracle, {@link LightOracleTest}, covers
 * natural terrain). Each case states the vanilla rule it pins.
 */
class LightEngineTest {
    static final int STONE = BlockData.defaultState(BlockId.STONE);
    NativeMemory mem;
    BlockStorage blocks;
    LightStorage blockLight, skyLight;
    LightEngine engine;

    @BeforeEach
    void setUp() {
        mem = new NativeMemory();
        blocks = new BlockStorage(mem, 2, 2, 0, 4, 64);
        blockLight = new LightStorage(mem, 2, 2, 0, 4, 64, 0);
        skyLight = new LightStorage(mem, 2, 2, 0, 4, 64, 0);
        engine = new LightEngine(blocks, blockLight, skyLight);
    }

    @AfterEach
    void tearDown() {
        mem.close();
    }

    static int state(int block, String... props) {
        int s = BlockData.defaultState(block);
        for (int i = 0; i < props.length; i += 2) {
            int p = BlockData.property(block, props[i]);
            s = BlockData.with(s, p, BlockData.valueIndex(p, props[i + 1]));
        }
        return s;
    }

    /** Fill the whole storage with stone, then carve air at the given boxes. */
    void solid() {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 0; y < 64; y++) blocks.set(x, y, z, STONE);
    }

    void air(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) blocks.set(x, y, z, 0);
    }

    /** A torch (emission 14) in a sealed tunnel: level = 14 − distance, one per block of air (opacity max(1, 0)). */
    @Test
    void torchFallsOffByOnePerBlock() {
        solid();
        air(2, 10, 5, 25, 10, 5);
        blocks.set(2, 10, 5, BlockData.defaultState(BlockId.TORCH));
        engine.relightAll();
        assertEquals(14, BlockData.lightEmission(BlockData.defaultState(BlockId.TORCH)));
        for (int d = 0; d <= 13; d++) assertEquals(14 - d, blockLight.get(2 + d, 10, 5), "distance " + d);
        assertEquals(0, blockLight.get(2 + 14, 10, 5));
        assertEquals(0, blockLight.get(3, 11, 5), "stone (light block 15) takes nothing");
        assertEquals(0, skyLight.get(10, 10, 5), "sealed: no sky");
    }

    /** Water has light block 1: a glowstone's light loses max(1, 1) = 1 per water block, like air. */
    @Test
    void glowstoneIntoWater() {
        solid();
        air(5, 20, 5, 12, 20, 5);
        blocks.set(5, 20, 5, BlockData.defaultState(BlockId.GLOWSTONE));
        for (int x = 6; x <= 12; x++) blocks.set(x, 20, 5, BlockData.defaultState(BlockId.WATER));
        engine.relightAll();
        assertEquals(15, blockLight.get(5, 20, 5), "a source is lit at its own emission even though opaque");
        for (int x = 6; x <= 12; x++) assertEquals(15 - (x - 5), blockLight.get(x, 20, 5));
    }

    /**
     * Sky: open columns are 15 down to the first light-blocking block. A leaf (light block 1) ends the sky column
     * ({@code ChunkSkyLightSources.isEdgeOccluded}); the leaf gets 14, and every air block below one less.
     */
    @Test
    void skyThroughLeaves() {
        solid();
        air(8, 30, 8, 8, 63, 8); // a 1-wide shaft open to the sky
        int leaf = state(BlockId.OAK_LEAVES, "persistent", "true");
        assertEquals(1, BlockData.lightBlock(leaf));
        blocks.set(8, 50, 8, leaf);
        engine.relightAll();
        assertEquals(15, skyLight.get(8, 51, 8));
        assertEquals(51, engine.skySource(8, 8));
        assertEquals(14, skyLight.get(8, 50, 8), "leaf: 15 − 1");
        for (int y = 49; y >= 37; y--) assertEquals(14 - (50 - y), skyLight.get(8, y, 8), "y " + y);
        assertEquals(0, skyLight.get(8, 35, 8));
    }

    /**
     * Slabs have light block 0 but occlude by shape. A bottom slab's top face is open, so sky reaches it (it is a
     * sky source), but its full bottom face stops light going down. A top slab's full top face stops sky above it.
     */
    @Test
    void slabsOccludeByFace() {
        solid();
        air(4, 40, 4, 4, 63, 4);
        air(12, 40, 12, 12, 63, 12);
        blocks.set(4, 50, 4, state(BlockId.STONE_SLAB, "type", "bottom"));
        blocks.set(12, 50, 12, state(BlockId.STONE_SLAB, "type", "top"));
        engine.relightAll();
        assertEquals(15, skyLight.get(4, 50, 4), "bottom slab: open top face, a sky source");
        assertEquals(0, skyLight.get(4, 49, 4), "…but its full bottom face blocks light below");
        assertEquals(15, skyLight.get(12, 51, 12));
        assertEquals(0, skyLight.get(12, 50, 12), "top slab: full top face, no sky enters");
        assertEquals(0, skyLight.get(12, 49, 12));
    }

    /** Face occlusion itself (vanilla {@code Shapes.faceShapeOccludes}): two half faces that together cover the face. */
    @Test
    void faceUnionCoverage() {
        assertTrue(FaceOcclusion.covers(new double[] {0, 0, 1, 0.5}, new double[] {0, 0.5, 1, 1}), "two halves");
        assertFalse(FaceOcclusion.covers(new double[] {0, 0, 1, 0.5}, new double[] {0, 0.5, 0.9, 1}), "a gap");
        assertTrue(FaceOcclusion.covers(new double[] {0, 0, 1, 1}, null));
        int bottom = state(BlockId.STONE_SLAB, "type", "bottom"), top = state(BlockId.STONE_SLAB, "type", "top");
        assertTrue(FaceOcclusion.occludes(0, top, FaceOcclusion.DOWN), "air → top slab from above");
        assertFalse(FaceOcclusion.occludes(0, bottom, FaceOcclusion.DOWN), "air → bottom slab from above");
        assertTrue(FaceOcclusion.occludes(bottom, 0, FaceOcclusion.DOWN), "bottom slab → air below");
        assertFalse(FaceOcclusion.occludes(0, STONE, FaceOcclusion.DOWN), "full blocks never use their shape for light");
    }

    /**
     * Incremental updates must land on the same fixed point as a full relight. Random edits (emitters, opaque and
     * partially transparent blocks, face-occluding slabs, water, leaves) in a cave-riddled scene, each applied with
     * onBlockChanged; every 25 edits the whole storage is compared against a from-scratch relight.
     */
    @Test
    void incrementalUpdatesMatchFullRelight() {
        var rnd = new java.util.SplittableRandom(99);
        int[] palette = {0, 0, 0, STONE, STONE, BlockData.defaultState(BlockId.TORCH), BlockData.defaultState(BlockId.GLOWSTONE),
                BlockData.defaultState(BlockId.GLASS), BlockData.defaultState(BlockId.WATER),
                state(BlockId.OAK_LEAVES, "persistent", "true"), state(BlockId.STONE_SLAB, "type", "bottom"),
                state(BlockId.STONE_SLAB, "type", "top"), BlockData.defaultState(BlockId.LANTERN),
                BlockData.defaultState(BlockId.SEA_LANTERN), state(BlockId.OAK_STAIRS, "half", "top")};
        // Terrain: stone up to y=30 with random caves, open sky above.
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 0; y < 30; y++) {
            blocks.set(x, y, z, rnd.nextInt(5) == 0 ? 0 : STONE);
        }
        engine.relightAll();
        var refBlockLight = new LightStorage(mem, 2, 2, 0, 4, 256, 0);
        var refSkyLight = new LightStorage(mem, 2, 2, 0, 4, 256, 0);
        var reference = new LightEngine(blocks, refBlockLight, refSkyLight);
        for (int edit = 1; edit <= 400; edit++) {
            int x = rnd.nextInt(32), y = rnd.nextInt(40), z = rnd.nextInt(32);
            blocks.set(x, y, z, palette[rnd.nextInt(palette.length)]);
            engine.onBlockChanged(x, y, z);
            if (edit % 25 == 0) {
                reference.relightAll();
                for (int px = 0; px < 32; px++) for (int pz = 0; pz < 32; pz++) for (int py = -16; py < 80; py++) {
                    assertEquals(refBlockLight.get(px, py, pz), blockLight.get(px, py, pz), "block light at " + px + "," + py + "," + pz + " after " + edit + " edits");
                    assertEquals(refSkyLight.get(px, py, pz), skyLight.get(px, py, pz), "sky light at " + px + "," + py + "," + pz + " after " + edit + " edits");
                }
            }
        }
    }
}
