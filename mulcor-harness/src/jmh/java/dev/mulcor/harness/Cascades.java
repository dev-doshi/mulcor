package dev.mulcor.harness;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Real redstone and TNT contraptions for the {@link Scenario} world, built from the same blocks and inputs the
 * game uses (nothing is stubbed: every update, scheduled tick and explosion runs through the engine).
 *
 * <ul>
 *   <li><b>Clock lines</b>: a torch–repeater clock (period 12 ticks; the layout {@code MechanicsTest} verifies)
 *       drives a wire line running the whole width of the world, re-powered by a repeater every 15 blocks. Each
 *       toggle cascades along ~1,000 wires and ~60 repeaters and crosses every region border on the way.</li>
 *   <li><b>TNT clusters</b> on cell corners, where four regions meet: a plus of 5 TNT under a redstone block
 *       (the centre primes at once, the arms are chain-primed by its blast) and a 4-high sand pillar beside it that
 *       falls into the crater. {@link #rearm} places them again, through ordinary SET_BLOCK inputs.</li>
 * </ul>
 */
final class Cascades {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private final Engine engine;
    private final MemorySegment msg = NativeMemory.auto().allocate(Input.BYTES);
    /** Ground level (TNT clusters) and the clock lines' support level, at the top of the world. */
    private final int y, lineY;
    private final int[] spotX, spotZ;

    Cascades(Engine engine, int clockLines, int tntSpotsPerAxis) {
        this.engine = engine;
        this.y = engine.world.surfaceY;
        // Lines run near the top of the world, out of reach of the bots' mining and of ground-level TNT (a power-4
        // ray travels at most 6.9 blocks), so the contraptions stay intact for the whole measurement.
        this.lineY = engine.world.blocks.maxYExclusive() - 3;
        int size = engine.world.sizeX();
        for (int k = 0; k < clockLines; k++) buildClockLine(32 + 64 * k, size);
        int cell = engine.world.cfg.cellBlocks();
        int n = tntSpotsPerAxis;
        spotX = new int[n * n];
        spotZ = new int[n * n];
        int step = Math.max(1, (size / cell - 1) / Math.max(1, n));
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            spotX[i * n + j] = cell * (1 + i * step);
            spotZ[i * n + j] = cell * (1 + j * step) + 16; // off the clock lines
        }
    }

    /** Clock at x = 2, then a line of wire on stone supports eastwards to the world edge. Between ticks only. */
    private void buildClockLine(int z, int size) {
        var b = engine.world.blocks;
        int y = lineY;
        for (int x = 0; x < size; x++) for (int dz = -1; dz <= 3; dz++) for (int dy = 0; dy < 3; dy++) b.set(x, y + dy, z + dz, Blocks.AIR);
        int x = 2;
        b.set(x, y, z, Blocks.STONE);                         // B: the torch's block
        b.set(x, y + 1, z + 1, Blocks.repeater(2, 1, false)); // R1: behind the torch, out south into S
        b.set(x, y + 1, z + 2, Blocks.STONE);                 // S
        b.set(x, y, z + 2, Blocks.WIRE);                      // wire under S
        b.set(x, y, z + 1, Blocks.repeater(0, 1, false));     // R2: out north into B
        for (int lx = x + 1; lx < size - 1; lx++) {
            b.set(lx, y, z, Blocks.STONE);
            boolean repeater = lx > x + 1 && (lx - (x + 1)) % 15 == 0;
            b.set(lx, y + 1, z, repeater ? Blocks.repeater(1, 1, false) : Blocks.WIRE);
        }
        set(x, y + 1, z, Blocks.TORCH); // lit through the engine: the clock starts on the next tick
    }

    /** Place every TNT cluster (and its sand pillar) again. Allocation-free: inputs reuse one segment. */
    void rearm() {
        for (int i = 0; i < spotX.length; i++) {
            int cx = spotX[i], cz = spotZ[i];
            set(cx, y + 1, cz, Blocks.REDSTONE_BLOCK);
            set(cx + 1, y, cz, Blocks.TNT);
            set(cx - 1, y, cz, Blocks.TNT);
            set(cx, y, cz + 1, Blocks.TNT);
            set(cx, y, cz - 1, Blocks.TNT);
            for (int dy = 0; dy < 4; dy++) set(cx + 3, y + dy, cz, Blocks.SAND);
            set(cx, y, cz, Blocks.TNT); // under the redstone block: primes when placed
        }
    }

    int spots() {
        return spotX.length;
    }

    private void set(int x, int y, int z, int state) {
        msg.set(I, Input.KIND, Input.SET_BLOCK);
        msg.set(I, Input.ENTITY, -1);
        msg.set(I, Input.X, x);
        msg.set(I, Input.Y, y);
        msg.set(I, Input.Z, z);
        msg.set(I, Input.A, state);
        engine.submitToRegion(engine.world.ownerOfBlock(x, z), msg, 0);
    }
}
