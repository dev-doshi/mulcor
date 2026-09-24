package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A 2-slot journal whose producer keeps writing while a reader reads record 1: the producer overwrites record 1's
 * slot with record 3. The reader must get record 1 intact, EMPTY or LAPPED, never a torn or foreign payload.
 */
@JCStressTest
@Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Record 1 not published yet.")
@Outcome(id = "1, 1, 1", expect = ACCEPTABLE, desc = "Record 1 intact.")
@Outcome(id = "-1, 0, 0", expect = ACCEPTABLE, desc = "Lapped: record 1 overwritten, detected.")
@Outcome(expect = FORBIDDEN, desc = "Torn or foreign record reported as OK.")
@State
public class JournalLapStress {
    private final BroadcastJournal j = new BroadcastJournal(NativeMemory.auto(), 2, 8);
    private final MemorySegment dst = NativeMemory.auto().allocate(8);

    public JournalLapStress() {
        write(0);
        j.publish();
    }

    private void write(int p) {
        long off = j.begin();
        j.segment().set(ValueLayout.JAVA_INT, off, p);
        j.segment().set(ValueLayout.JAVA_INT, off + 4, p);
        j.end();
    }

    @Actor
    public void producer() {
        for (int p = 1; p <= 3; p++) {
            write(p);
            j.publish();
        }
    }

    @Actor
    public void reader(III_Result r) {
        int s = j.read(1, dst, 0);
        r.r1 = s;
        r.r2 = s == BroadcastJournal.OK ? dst.get(ValueLayout.JAVA_INT, 0) : 0;
        r.r3 = s == BroadcastJournal.OK ? dst.get(ValueLayout.JAVA_INT, 4) : 0;
    }
}
