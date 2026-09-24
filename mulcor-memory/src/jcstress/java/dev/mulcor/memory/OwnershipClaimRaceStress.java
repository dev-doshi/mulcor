package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.ZZI_Result;

/**
 * An in-transit entity is claimed at the same moment by its destination region and by a replayed duplicate
 * transfer record. Exactly one claim may win, and the final owner must be the winner.
 */
@JCStressTest
@Outcome(id = {"true, false, 5", "false, true, 9"}, expect = ACCEPTABLE, desc = "Exactly one owner.")
@Outcome(expect = FORBIDDEN, desc = "Entity duplicated across regions or orphaned.")
@State
public class OwnershipClaimRaceStress {
    private static final long TRANSIT = Ownership.pack(Ownership.IN_TRANSIT, 5, 8);
    private final EntityDirectory dir = new EntityDirectory(NativeMemory.auto(), 1);
    private final int id;

    public OwnershipClaimRaceStress() {
        id = dir.allocate(1, 7);
        dir.casOwnership(id, dir.ownership(id), TRANSIT);
    }

    @Actor
    public void destination(ZZI_Result r) {
        r.r1 = dir.casOwnership(id, TRANSIT, Ownership.pack(Ownership.OWNED, 5, 8));
    }

    @Actor
    public void replay(ZZI_Result r) {
        r.r2 = dir.casOwnership(id, TRANSIT, Ownership.pack(Ownership.OWNED, 9, 8));
    }

    @Arbiter
    public void arbiter(ZZI_Result r) {
        r.r3 = Ownership.region(dir.ownership(id));
    }
}
