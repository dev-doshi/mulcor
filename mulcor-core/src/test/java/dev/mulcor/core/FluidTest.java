package dev.mulcor.core;

import static dev.mulcor.core.RedstoneParityTest.state;
import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.registry.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Water and lava against the rules and delays of the vanilla 26.2 code they port ({@code FlowingFluid},
 * {@code WaterFluid}: 5-tick delay, drop-off 1; {@code LavaFluid}: 30 ticks, drop-off 2 in the overworld). Derived
 * from the code, not recorded on a vanilla server; recorded traces should replace them.
 */
class FluidTest {
    private static final int OX = 66, OZ = 66;
    private Engine e;
    private int oy;

    @BeforeEach
    void setUp() {
        e = new Engine(small().pillarDensity(0).chestsPerCell(0).build());
        oy = e.world.surfaceY;
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
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
        assertTrue(e.setBlockCommand(OX + x, oy + y, OZ + z, state(spec)), spec);
    }

    /** {@code name} or {@code name[level=n]} for water and lava. */
    private String at(int x, int y, int z) {
        int st = e.world.blocks.get(OX + x, oy + y, OZ + z);
        int b = BlockData.block(st);
        String n = BlockData.name(b);
        n = n.substring(n.indexOf(':') + 1);
        int p = BlockData.property(b, "level");
        return p == BlockData.NO_PROPERTY ? n : n + "[level=" + BlockData.intValue(st, p) + "]";
    }

    @Test
    void waterSpreadsOneBlockEveryFiveTicksLosingOneLevel() {
        set(0, 0, 0, "water");
        e.run(4);
        assertEquals("air", at(1, 0, 0));
        e.run(1);
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) assertEquals("water[level=1]", at(d[0], 0, d[1]));
        e.run(5);
        assertEquals("water[level=2]", at(2, 0, 0));
        assertEquals("water[level=2]", at(1, 0, 1));
        e.run(25);
        assertEquals("water[level=7]", at(7, 0, 0));
        e.run(10);
        assertEquals("air", at(8, 0, 0), "level 7 is the last");
    }

    @Test
    void waterFallsThenSpreadsFromWhereItLands() {
        set(0, 2, 0, "water");
        e.run(5);
        assertEquals("water[level=8]", at(0, 1, 0), "falling water");
        assertEquals("air", at(1, 2, 0), "a source over a drop does not spread sideways");
        e.run(5);
        assertEquals("water[level=8]", at(0, 0, 0));
        e.run(5);
        assertEquals("water[level=1]", at(1, 0, 0), "falling water spreads as level 7 flow");
    }

    @Test
    void twoSourcesMakeANewSource() {
        set(0, 0, 0, "water");
        set(2, 0, 0, "water");
        e.run(5);
        assertEquals("water[level=0]", at(1, 0, 0));
    }

    @Test
    void lavaSpreadsEveryThirtyTicksLosingTwoLevels() {
        set(0, 0, 0, "lava");
        e.run(29);
        assertEquals("air", at(1, 0, 0));
        e.run(1);
        assertEquals("lava[level=2]", at(1, 0, 0));
    }

    @Test
    void waterNextToLavaSourceMakesObsidian() {
        set(0, 0, 0, "lava");
        set(2, 0, 0, "lava");
        e.run(1);
        set(1, 0, 0, "water");
        assertEquals("obsidian", at(0, 0, 0));
        assertEquals("obsidian", at(2, 0, 0));
    }

    @Test
    void flowingWaterDoesNotWaterlogASlab() {
        set(1, 0, 0, "oak_slab[type=bottom]");
        set(0, 0, 0, "water");
        e.run(12);
        assertEquals("oak_slab", at(1, 0, 0));
        int st = e.world.blocks.get(OX + 1, oy, OZ);
        assertFalse(BlockData.boolValue(st, BlockData.property(BlockData.block(st), "waterlogged")));
    }

    @Test
    void waterDriesUpWhenTheSourceGoes() {
        set(0, 0, 0, "water");
        e.run(10);
        assertEquals("water[level=2]", at(2, 0, 0));
        set(0, 0, 0, "air");
        e.run(5);
        assertEquals("water[level=3]", at(1, 0, 0), "recedes: its best neighbour now has amount 6");
        e.run(80);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) assertEquals("air", at(x, 0, z), "dried up at " + x + "," + z);
        }
    }
}
