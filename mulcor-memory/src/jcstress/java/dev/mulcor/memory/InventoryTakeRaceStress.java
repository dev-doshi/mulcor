package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

/** Two players click the last diamond in a chest at the same instant: exactly one gets it. */
@JCStressTest
@Outcome(id = {"1, 0, 0", "0, 1, 0"}, expect = ACCEPTABLE, desc = "One winner, slot empty.")
@Outcome(expect = FORBIDDEN, desc = "Item duplicated or destroyed.")
@State
public class InventoryTakeRaceStress {
    private final OffHeapInventory inv = new OffHeapInventory(NativeMemory.auto(), 1, 1);

    public InventoryTakeRaceStress() {
        inv.set(0, 0, 264, 1);
    }

    @Actor
    public void a(III_Result r) {
        r.r1 = OffHeapInventory.count(inv.take(0, 0, 1));
    }

    @Actor
    public void b(III_Result r) {
        r.r2 = OffHeapInventory.count(inv.take(0, 0, 1));
    }

    @Arbiter
    public void arbiter(III_Result r) {
        r.r3 = OffHeapInventory.count(inv.get(0, 0));
    }
}
