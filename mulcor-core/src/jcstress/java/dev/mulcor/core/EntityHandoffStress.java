package dev.mulcor.core;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.mulcor.memory.EntityDirectory;
import dev.mulcor.memory.EntityRecord;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapRing;
import dev.mulcor.memory.Ownership;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.IIJ_Result;

/**
 * The full cross-region handoff protocol, raced.
 *
 * <ul>
 *   <li>The source region CASes {@code OWNED(1) → IN_TRANSIT(2, e+1)}, writes the entity record into the
 *       destination inbox, and publishes it. The same record is published twice, simulating a buggy or replayed
 *       sender.</li>
 *   <li>Two destination consumers race to drain the inbox and claim the entity by CASing the word they read out
 *       of the record.</li>
 *   <li>The arbiter drains whatever is left.</li>
 * </ul>
 * Across all of them exactly one claim may succeed, and the claimant must see the full record payload (the
 * entity's position).
 */
@JCStressTest
@Outcome(id = {"1, 0, 42", "0, 1, 42"}, expect = ACCEPTABLE, desc = "Claimed exactly once by an actor, full payload seen.")
@Outcome(id = {"0, 0, 42"}, expect = ACCEPTABLE, desc = "Consumers ran early; arbiter claimed exactly once.")
@Outcome(expect = FORBIDDEN, desc = "Entity duplicated, lost, or claimed with a stale payload.")
@State
public class EntityHandoffStress {
    private static final long MSG = 72;
    private final NativeMemory mem = NativeMemory.auto();
    private final EntityDirectory dir = new EntityDirectory(mem, 1);
    private final OffHeapRing inbox = new OffHeapRing(mem, 4, (int) MSG);
    private final MemorySegment rec = mem.allocate(MSG);
    private final int id = dir.allocate(1, 7);
    private int arbiterClaims;
    private long claimedX = -1;

    @Actor
    public void source() {
        long cur = dir.ownership(id);
        long transit = Ownership.pack(Ownership.IN_TRANSIT, 2, 8);
        if (!dir.casOwnership(id, cur, transit)) return;
        rec.set(ValueLayout.JAVA_LONG, 0, transit);
        rec.set(ValueLayout.JAVA_LONG, 8 + EntityRecord.ID, id);
        rec.set(ValueLayout.JAVA_DOUBLE, 8 + EntityRecord.X, 42.0);
        inbox.offer(rec, 0, MSG);
        inbox.offer(rec, 0, MSG); // duplicate delivery
    }

    private int consume() {
        int claims = 0;
        for (int i = 0; i < 2; i++) {
            long pos = inbox.tryAcquire();
            if (pos < 0) break;
            long off = inbox.payloadOffset(pos);
            long word = inbox.segment().get(ValueLayout.JAVA_LONG, off);
            double x = inbox.segment().get(ValueLayout.JAVA_DOUBLE, off + 8 + EntityRecord.X);
            inbox.release(pos);
            if (dir.casOwnership(id, word, Ownership.pack(Ownership.OWNED, 2, 8))) {
                claims++;
                claimedX = (long) x;
            }
        }
        return claims;
    }

    @Actor
    public void destination1(IIJ_Result r) {
        r.r1 = consume();
    }

    @Actor
    public void destination2(IIJ_Result r) {
        r.r2 = consume();
    }

    @Arbiter
    public void arbiter(IIJ_Result r) {
        arbiterClaims = consume();
        long w = dir.ownership(id);
        boolean ownedByDst = Ownership.state(w) == Ownership.OWNED && Ownership.region(w) == 2;
        int total = r.r1 + r.r2 + arbiterClaims;
        r.r3 = ownedByDst && total == 1 ? claimedX : -total;
    }
}
