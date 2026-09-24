package dev.mulcor.core.light;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.region.Region;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine keeps the world's light exact: the generated world is lit at startup, and every block change made
 * through {@link Region#setBlock} is handed to the light thread at that epoch's commit; once it is idle, light matches a from-scratch relight.
 */
class WorldLightTest {
    Engine e;
    int sy, x0 = 40, z0 = 40;

    @BeforeEach
    void setUp() {
        e = new Engine(EngineConfig.builder().world(8, 8).cellChunks(8).workers(1).maxRegions(4).initialRegions(1)
                .regionEntityCapacity(64).maxEntities(128).rebalanceInterval(0).sampleCapacity(64).pillarDensity(0)
                .chestsPerCell(0).height(0, 2).build());
        e.setAiEnabled(false);
        sy = e.world.surfaceY;
    }

    @AfterEach
    void tearDown() {
        e.close();
    }

    Region owner(int x, int z) {
        return e.world.regions[e.world.ownerOfBlock(x, z)];
    }

    @Test
    void generatedWorldIsLit() {
        assertEquals(15, e.world.skyLight.get(x0, sy, z0), "open sky above the dirt");
        assertEquals(0, e.world.skyLight.get(x0, sy - 2, z0), "under the ground");
        assertEquals(0, e.world.blockLight.get(x0, sy, z0));
    }

    @Test
    void torchPlacedAndRemovedThroughTheFunnel() {
        int torch = BlockData.defaultState(BlockId.TORCH);
        owner(x0, z0).setBlock(x0, sy, z0, torch);
        assertEquals(0, e.world.blockLight.get(x0, sy, z0), "light follows the commit (on the light thread), not the write");
        e.tick();
        e.world.lightService.awaitIdle();
        assertEquals(14, e.world.blockLight.get(x0, sy, z0));
        assertEquals(11, e.world.blockLight.get(x0 + 3, sy, z0));
        owner(x0, z0).setBlock(x0, sy, z0, Blocks.AIR);
        e.tick();
        e.world.lightService.awaitIdle();
        assertEquals(0, e.world.blockLight.get(x0 + 3, sy, z0));
    }

    @Test
    void roofShadowsAndMatchesAFullRelight() {
        Region r = owner(x0, z0);
        for (int x = x0 - 2; x <= x0 + 2; x++) for (int z = z0 - 2; z <= z0 + 2; z++) r.setBlock(x, sy + 3, z, Blocks.STONE);
        r.setBlock(x0 + 1, sy, z0, BlockData.defaultState(BlockId.GLOWSTONE));
        e.tick();
        e.world.lightService.awaitIdle();
        assertEquals(15 - 3, e.world.skyLight.get(x0, sy, z0), "under a 5×5 roof: 15 at the rim, −1 per block inward");
        assertEquals(14, e.world.blockLight.get(x0, sy, z0));

        var mem = e.world.memory;
        var bl = new LightStorage(mem, 8, 8, 0, 2, 8 * 8 * 4, 0);
        var sl = new LightStorage(mem, 8, 8, 0, 2, 8 * 8 * 4, 0);
        new LightEngine(e.world.blocks, bl, sl).relightAll();
        for (int x = 0; x < 128; x++) for (int z = 0; z < 128; z++) for (int y = -16; y < 48; y++) {
            assertEquals(bl.get(x, y, z), e.world.blockLight.get(x, y, z), "block light " + x + "," + y + "," + z);
            assertEquals(sl.get(x, y, z), e.world.skyLight.get(x, y, z), "sky light " + x + "," + y + "," + z);
        }
    }

    /**
     * Blocks keep changing while the light thread works on earlier changes (it reads them one change stale at
     * worst). Once it is idle, light must equal a from-scratch relight of the final blocks.
     */
    @Test
    void convergesWhileBlocksChangeUnderTheLightThread() {
        var rnd = new java.util.SplittableRandom(5);
        int[] palette = {0, 0, Blocks.STONE, BlockData.defaultState(BlockId.TORCH), BlockData.defaultState(BlockId.GLOWSTONE),
                BlockData.defaultState(BlockId.GLASS), BlockData.defaultState(BlockId.WATER),
                BlockData.defaultState(BlockId.OAK_LEAVES)};
        for (int tick = 0; tick < 300; tick++) {
            for (int i = 0; i < 40; i++) {
                int x = rnd.nextInt(128), z = rnd.nextInt(128), y = 1 + rnd.nextInt(24);
                owner(x, z).setBlock(x, y, z, palette[rnd.nextInt(palette.length)]);
            }
            e.tick();
        }
        e.world.lightService.awaitIdle();
        var mem = e.world.memory;
        var bl = new LightStorage(mem, 8, 8, 0, 2, 8 * 8 * 4, 0);
        var sl = new LightStorage(mem, 8, 8, 0, 2, 8 * 8 * 4, 0);
        new LightEngine(e.world.blocks, bl, sl).relightAll();
        for (int x = 0; x < 128; x++) for (int z = 0; z < 128; z++) for (int y = -16; y < 48; y++) {
            assertEquals(bl.get(x, y, z), e.world.blockLight.get(x, y, z), "block light " + x + "," + y + "," + z);
            assertEquals(sl.get(x, y, z), e.world.skyLight.get(x, y, z), "sky light " + x + "," + y + "," + z);
        }
    }
}
