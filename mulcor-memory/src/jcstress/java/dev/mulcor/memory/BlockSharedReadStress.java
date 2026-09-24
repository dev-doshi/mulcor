package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The owning region writes the first block of a new section while a neighbouring region reads it with
 * getShared. The neighbour may see air (not yet published) or the new value. It must never see anything else,
 * and it must see the value if it can already see the neighbouring block that was written after it.
 */
@JCStressTest
@Outcome(id = {"0, 0", "7, 0", "7, 9"}, expect = ACCEPTABLE, desc = "Consistent snapshot.")
@Outcome(id = "0, 9", expect = FORBIDDEN, desc = "Saw the later write but missed the earlier one.")
@Outcome(expect = FORBIDDEN, desc = "Garbage block state.")
@State
public class BlockSharedReadStress {
    private final BlockStorage blocks = new BlockStorage(NativeMemory.auto(), 1, 1, 0, 1, 2);

    @Actor
    public void owner() {
        blocks.set(1, 1, 1, 7);
        blocks.set(2, 1, 1, 9);
    }

    @Actor
    public void neighbour(II_Result r) {
        int later = blocks.getShared(2, 1, 1);
        int earlier = blocks.getShared(1, 1, 1);
        r.r1 = earlier;
        r.r2 = later;
    }
}
