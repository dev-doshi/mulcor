package dev.mulcor.net;

import dev.mulcor.core.region.Input;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/** Captures decoded input records; can simulate a full ingress ring. */
final class RecordingSink implements InputSink {
    record Rec(int kind, int entity, int x, int y, int z, int a, int b, int c) {}

    final List<Rec> records = new ArrayList<>();
    int rejectBudget; // reject this many offers before accepting again
    int rejectEvery;  // if > 0, reject every n-th offer
    private int offers;

    @Override
    public boolean offer(MemorySegment r, long o) {
        offers++;
        if (rejectBudget > 0) {
            rejectBudget--;
            return false;
        }
        if (rejectEvery > 0 && offers % rejectEvery == 0) return false;
        records.add(new Rec(r.get(ValueLayout.JAVA_INT, o + Input.KIND), r.get(ValueLayout.JAVA_INT, o + Input.ENTITY),
                r.get(ValueLayout.JAVA_INT, o + Input.X), r.get(ValueLayout.JAVA_INT, o + Input.Y), r.get(ValueLayout.JAVA_INT, o + Input.Z),
                r.get(ValueLayout.JAVA_INT, o + Input.A), r.get(ValueLayout.JAVA_INT, o + Input.B), r.get(ValueLayout.JAVA_INT, o + Input.C)));
        return true;
    }
}
