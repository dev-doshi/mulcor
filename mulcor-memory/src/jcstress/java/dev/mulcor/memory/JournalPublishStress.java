package dev.mulcor.memory;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;

/** A reader that gets OK for a published record must see its complete payload. */
@JCStressTest
@Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Reader first: nothing published yet.")
@Outcome(id = "1, 1111, 2222", expect = ACCEPTABLE, desc = "Complete record.")
@Outcome(expect = FORBIDDEN, desc = "Torn or stale record reported as OK.")
@State
public class JournalPublishStress {
    private final BroadcastJournal j = new BroadcastJournal(NativeMemory.auto(), 4, 8);
    private final MemorySegment dst = NativeMemory.auto().allocate(8);

    @Actor
    public void producer() {
        long off = j.begin();
        j.segment().set(ValueLayout.JAVA_INT, off, 1111);
        j.segment().set(ValueLayout.JAVA_INT, off + 4, 2222);
        j.end();
        j.publish();
    }

    @Actor
    public void reader(III_Result r) {
        r.r1 = j.read(0, dst, 0);
        r.r2 = r.r1 == BroadcastJournal.OK ? dst.get(ValueLayout.JAVA_INT, 0) : 0;
        r.r3 = r.r1 == BroadcastJournal.OK ? dst.get(ValueLayout.JAVA_INT, 4) : 0;
    }
}
