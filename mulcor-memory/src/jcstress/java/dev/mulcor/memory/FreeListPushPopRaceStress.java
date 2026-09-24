package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Index 0 of a single-entry pool is held and pushed back while another thread pops.
 * Afterwards the index must be in exactly one place: the popper's hand or the pool.
 */
@JCStressTest
@Outcome(id = {"0, -1", "-1, 0"}, expect = ACCEPTABLE, desc = "Index in exactly one place.")
@Outcome(expect = FORBIDDEN, desc = "Index duplicated or lost.")
@State
public class FreeListPushPopRaceStress {
    private final TaggedFreeList list = new TaggedFreeList(NativeMemory.auto(), 1);
    private final int held = list.pop();

    @Actor
    public void pusher() {
        list.push(held);
    }

    @Actor
    public void popper(II_Result r) {
        r.r1 = list.pop();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r2 = list.pop();
    }
}
