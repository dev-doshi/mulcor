package dev.mulcor.net;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZI_Result;

/** Two event loops race for the last token of a shared global bucket (frozen clock: no refill). */
@JCStressTest
@Outcome(id = {"true, false, 0", "false, true, 0"}, expect = ACCEPTABLE, desc = "Exactly one granted, bucket empty.")
@Outcome(expect = FORBIDDEN, desc = "Token granted twice or lost.")
@State
public class TokenBucketStress {
    private final TokenBucket bucket = new TokenBucket(1, 1, () -> 0L);

    @Actor
    public void loop1(ZZI_Result r) {
        r.r1 = bucket.tryAcquire(1);
    }

    @Actor
    public void loop2(ZZI_Result r) {
        r.r2 = bucket.tryAcquire(1);
    }

    @Arbiter
    public void arbiter(ZZI_Result r) {
        r.r3 = bucket.tryAcquire(1) ? 1 : 0;
    }
}
