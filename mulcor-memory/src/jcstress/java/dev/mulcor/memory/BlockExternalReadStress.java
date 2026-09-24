package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * A network thread encodes chunk 0 (holding the external-read guard) while the engine empties that chunk's only
 * section, reclaims it in the commit phase, and reuses it for chunk 1. The pool has a single section, so reuse
 * succeeds only if the reclaim went ahead.
 *
 * <p>The reader may see the old block (5) or air (0). It must never see chunk 1's block (7) through chunk 0's
 * stale section ref: that would mean the section was recycled while the reader was still using it.
 */
@JCStressTest
@Outcome(id = {"5, 0", "5, 1", "0, 0", "0, 1"}, expect = ACCEPTABLE, desc = "Old value or air; reuse happened or was deferred.")
@Outcome(id = {"7, 0", "7, 1"}, expect = FORBIDDEN, desc = "Read a section that had been recycled into another chunk.")
@Outcome(expect = FORBIDDEN, desc = "Garbage.")
@State
public class BlockExternalReadStress {
    private final BlockStorage blocks = new BlockStorage(NativeMemory.auto(), 2, 1, 0, 1, 1);

    public BlockExternalReadStress() {
        blocks.set(0, 0, 0, 5);
    }

    @Actor
    public void engine(II_Result r) {
        blocks.set(0, 0, 0, BlockStorage.AIR); // last block: the section is retired
        blocks.reclaim();                       // commit phase
        r.r2 = blocks.set(16, 0, 0, 7) == BlockStorage.FAILED ? 0 : 1; // chunk 1 takes the (only) section
    }

    @Actor
    public void encoder(II_Result r) {
        blocks.beginExternalRead();
        int ref = blocks.sectionRef(0, 0, 0);
        r.r1 = ref == 0 ? 0 : Short.toUnsignedInt(blocks.slab().get(ValueLayout.JAVA_SHORT, BlockStorage.sectionOffset(ref)));
        blocks.endExternalRead();
    }
}
