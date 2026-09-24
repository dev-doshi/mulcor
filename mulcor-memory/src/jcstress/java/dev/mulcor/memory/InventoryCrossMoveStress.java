package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

/** Two threads move stacks in opposite directions between two chests at once; the total must stay 100. */
@JCStressTest
@Outcome(id = "100", expect = ACCEPTABLE, desc = "Conserved.")
@Outcome(expect = FORBIDDEN, desc = "Items duplicated or destroyed.")
@State
public class InventoryCrossMoveStress {
    private final OffHeapInventory inv = new OffHeapInventory(NativeMemory.auto(), 2, 2);
    private final int[] overflowA = new int[1];
    private final int[] overflowB = new int[1];

    public InventoryCrossMoveStress() {
        inv.set(0, 0, 1, 60);
        inv.set(1, 0, 1, 40);
    }

    @Actor
    public void aToB() {
        inv.move(0, 0, 1, 50, overflowA);
    }

    @Actor
    public void bToA() {
        inv.move(1, 0, 0, 30, overflowB);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = (int) (inv.totalAll(0) + inv.totalAll(1) + overflowA[0] + overflowB[0]);
    }
}
