package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.JJ_Result;

/** A consumer that acquires a slot must see the producer's complete payload, never stale or partial data. */
@JCStressTest
@Outcome(id = "-1, -1", expect = ACCEPTABLE, desc = "Consumer ran first: ring empty.")
@Outcome(id = "1111, 2222", expect = ACCEPTABLE, desc = "Full payload visible.")
@Outcome(expect = FORBIDDEN, desc = "Stale or torn payload observed after acquire.")
@State
public class RingPublishVisibilityStress {
    private final OffHeapRing ring = new OffHeapRing(NativeMemory.auto(), 2, 16);

    @Actor
    public void producer() {
        long pos = ring.tryClaim();
        long off = ring.payloadOffset(pos);
        ring.segment().set(ValueLayout.JAVA_LONG, off, 1111L);
        ring.segment().set(ValueLayout.JAVA_LONG, off + 8, 2222L);
        ring.publish(pos);
    }

    @Actor
    public void consumer(JJ_Result r) {
        long pos = ring.tryAcquire();
        if (pos < 0) {
            r.r1 = -1;
            r.r2 = -1;
            return;
        }
        long off = ring.payloadOffset(pos);
        r.r1 = ring.segment().get(ValueLayout.JAVA_LONG, off);
        r.r2 = ring.segment().get(ValueLayout.JAVA_LONG, off + 8);
        ring.release(pos);
    }
}
