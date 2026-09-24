package dev.mulcor.core;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.mulcor.core.region.StateWord;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZI_Result;

/**
 * Two workers try to run the same scheduled region at once, as a bad work-steal or a double claim would.
 * Exactly one may enter RUNNING, and the region must end back in IDLE.
 */
@JCStressTest
@Outcome(id = {"true, false, 1", "false, true, 1"}, expect = ACCEPTABLE, desc = "Exactly one worker ran the region.")
@Outcome(expect = FORBIDDEN, desc = "Region ran on two threads, or never ran.")
@State
public class RegionClaimStress {
    private final StateWord state = new StateWord(StateWord.SCHEDULED);
    private int runs; // plain field: a double run would race here too

    private boolean run() {
        if (!state.cas(StateWord.SCHEDULED, StateWord.RUNNING)) return false;
        runs++;
        return state.cas(StateWord.RUNNING, StateWord.IDLE);
    }

    @Actor
    public void worker1(ZZI_Result r) {
        r.r1 = run();
    }

    @Actor
    public void worker2(ZZI_Result r) {
        r.r2 = run();
    }

    @Arbiter
    public void arbiter(ZZI_Result r) {
        r.r3 = state.get() == StateWord.IDLE ? runs : -runs - 100;
    }
}
