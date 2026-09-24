package dev.mulcor.core;

import static dev.mulcor.core.RedstoneParityTest.state;
import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Journal;
import dev.mulcor.memory.BroadcastJournal;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.ScheduledTicks;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block changes and block events reach the owning region's egress journal, published by the end of the tick. */
class JournalTest {
    record Rec(long pos, long meta) {}

    private static List<Rec> drain(BroadcastJournal j, long[] cursor) {
        MemorySegment buf = NativeMemory.auto().allocate(Journal.BYTES);
        List<Rec> out = new ArrayList<>();
        while (j.read(cursor[0], buf, 0) == BroadcastJournal.OK) {
            out.add(new Rec(buf.get(ValueLayout.JAVA_LONG, Journal.POS), buf.get(ValueLayout.JAVA_LONG, Journal.META)));
            cursor[0]++;
        }
        return out;
    }

    @Test
    void commandsAndTicksPublishChangedPositions() {
        try (var e = new Engine(small().pillarDensity(0).chestsPerCell(0).build())) {
            int x = 66, z = 66, y = e.world.surfaceY;
            BroadcastJournal j = e.world.journals[e.world.ownerOfBlock(x, z)];
            long[] cursor = {j.published()};
            e.setBlockCommand(x, y, z, state("stone"));
            List<Rec> recs = drain(j, cursor);
            assertTrue(recs.contains(new Rec(ScheduledTicks.pack(x, y, z), (long) Journal.BLOCK << 56)), recs.toString());

            e.setBlockCommand(x + 2, y, z, state("piston[facing=east]"));
            e.setBlockCommand(x + 1, y, z, state("redstone_block"));
            drain(j, cursor);
            e.tick(); // the extension block event runs
            List<Rec> tick = drain(j, cursor);
            assertTrue(tick.stream().anyMatch(r -> Journal.kind(r.meta()) == Journal.BLOCK_EVENT
                    && r.pos() == ScheduledTicks.pack(x + 2, y, z) && Journal.eventA(r.meta()) == 0), tick.toString());
            assertTrue(tick.stream().anyMatch(r -> r.pos() == ScheduledTicks.pack(x + 3, y, z)), "the head's moving piston");
        }
    }
}
