package dev.mulcor.core;

import static dev.mulcor.core.RedstoneParityTest.state;
import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.registry.BlockData;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** A client's use-item-on packet ({@link Input#PLACE} with a clicked face): use the clicked block, else place. */
class PlayerUseTest {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final int UP = 1;

    private static void useOn(Engine e, int eid, int x, int y, int z, int face) {
        MemorySegment msg = NativeMemory.auto().allocate(Input.BYTES);
        msg.set(I, Input.KIND, Input.PLACE);
        msg.set(I, Input.ENTITY, eid);
        msg.set(I, Input.X, x);
        msg.set(I, Input.Y, y);
        msg.set(I, Input.Z, z);
        msg.set(I, Input.A, Blocks.DIRT);
        msg.set(I, Input.B, face + 1);
        assertTrue(e.submitInput(msg, 0));
    }

    private static boolean powered(Engine e, int x, int y, int z) {
        int st = e.world.blocks.get(x, y, z);
        return BlockData.boolValue(st, BlockData.property(BlockData.block(st), "powered"));
    }

    @Test
    void clickingALeverPullsItInsteadOfPlacing() {
        try (var e = new Engine(small().pillarDensity(0).chestsPerCell(0).build())) {
            e.setAiEnabled(false);
            int y = e.world.surfaceY;
            int eid = e.spawnBot(64, 64, 0);
            e.world.players.set(eid, 0, Blocks.DIRT, 4);
            assertTrue(e.setBlockCommand(68, y, 68, state("lever[face=floor,facing=north]")));
            assertTrue(e.setBlockCommand(69, y, 68, state("redstone_lamp")));
            e.run(2);
            long dirt = e.world.players.total(eid, Blocks.DIRT);

            useOn(e, eid, 68, y + 1, 68, UP); // clicked the lever's top: the target is the block above it
            e.tick();
            assertTrue(powered(e, 68, y, 68));
            assertTrue(BlockData.boolValue(e.world.blocks.get(69, y, 68),
                    BlockData.property(BlockData.block(e.world.blocks.get(69, y, 68)), "lit")));
            assertEquals(Blocks.AIR, e.world.blocks.get(68, y + 1, 68), "nothing placed");
            assertEquals(dirt, e.world.players.total(eid, Blocks.DIRT), "no item used");

            useOn(e, eid, 69, y + 1, 68, UP); // the lamp does not react: place dirt on it
            e.tick();
            assertEquals(Blocks.DIRT, e.world.blocks.get(69, y + 1, 68));
            assertEquals(dirt - 1, e.world.players.total(eid, Blocks.DIRT));
        }
    }
}
