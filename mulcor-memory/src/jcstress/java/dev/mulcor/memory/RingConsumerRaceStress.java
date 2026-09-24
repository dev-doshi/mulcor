package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.JJ_Result;

/** Two consumers race for the one queued record; it must be delivered exactly once. */
@JCStressTest
@Outcome(id = {"77, -1", "-1, 77"}, expect = ACCEPTABLE, desc = "Delivered exactly once.")
@Outcome(expect = FORBIDDEN, desc = "Duplicate or lost delivery.")
@State
public class RingConsumerRaceStress {
    private final OffHeapRing ring = new OffHeapRing(NativeMemory.auto(), 2, 8);

    public RingConsumerRaceStress() {
        long pos = ring.tryClaim();
        ring.segment().set(ValueLayout.JAVA_LONG, ring.payloadOffset(pos), 77L);
        ring.publish(pos);
    }

    private long take() {
        long pos = ring.tryAcquire();
        if (pos < 0) return -1;
        long v = ring.segment().get(ValueLayout.JAVA_LONG, ring.payloadOffset(pos));
        ring.release(pos);
        return v;
    }

    @Actor
    public void c1(JJ_Result r) {
        r.r1 = take();
    }

    @Actor
    public void c2(JJ_Result r) {
        r.r2 = take();
    }
}
