package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

/** Two poppers on a 2-entry pool must get distinct indices and leave the pool empty. */
@JCStressTest
@Outcome(id = {"0, 1, -1", "1, 0, -1"}, expect = ACCEPTABLE, desc = "Distinct indices, pool drained.")
@Outcome(expect = FORBIDDEN, desc = "Index handed out twice or lost.")
@State
public class FreeListPopRaceStress {
    private final TaggedFreeList list = new TaggedFreeList(NativeMemory.auto(), 2);

    @Actor
    public void a(III_Result r) {
        r.r1 = list.pop();
    }

    @Actor
    public void b(III_Result r) {
        r.r2 = list.pop();
    }

    @Arbiter
    public void arbiter(III_Result r) {
        r.r3 = list.pop();
    }
}
