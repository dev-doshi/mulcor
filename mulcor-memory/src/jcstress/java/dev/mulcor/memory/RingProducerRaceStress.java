package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZI_Result;

/** Two producers race for the last free slot of a ring; exactly one may claim it, and nothing is overwritten. */
@JCStressTest
@Outcome(id = {"true, false, 2", "false, true, 2"}, expect = ACCEPTABLE, desc = "Exactly one producer claimed.")
@Outcome(expect = FORBIDDEN, desc = "Double claim or lost slot.")
@State
public class RingProducerRaceStress {
    private final OffHeapRing ring = new OffHeapRing(NativeMemory.auto(), 2, 8);

    public RingProducerRaceStress() {
        ring.publish(ring.tryClaim());
    }

    @Actor
    public void p1(ZZI_Result r) {
        long pos = ring.tryClaim();
        r.r1 = pos >= 0;
        if (pos >= 0) ring.publish(pos);
    }

    @Actor
    public void p2(ZZI_Result r) {
        long pos = ring.tryClaim();
        r.r2 = pos >= 0;
        if (pos >= 0) ring.publish(pos);
    }

    @Arbiter
    public void arbiter(ZZI_Result r) {
        r.r3 = ring.size();
    }
}
