package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

class BroadcastJournalTest {
    private static void write(BroadcastJournal j, long v) {
        long off = j.begin();
        j.segment().set(ValueLayout.JAVA_LONG, off, v);
        j.end();
    }

    @Test
    void recordsBecomeVisibleOnPublishAndReadersFollowIndependently() {
        try (var mem = new NativeMemory()) {
            var j = new BroadcastJournal(mem, 8, 8);
            MemorySegment dst = mem.allocate(8);
            write(j, 10);
            write(j, 11);
            assertEquals(BroadcastJournal.EMPTY, j.read(0, dst, 0), "not published yet");
            j.publish();
            assertEquals(2, j.published());
            for (int reader = 0; reader < 3; reader++) { // every reader sees the same stream
                assertEquals(BroadcastJournal.OK, j.read(0, dst, 0));
                assertEquals(10, dst.get(ValueLayout.JAVA_LONG, 0));
                assertEquals(BroadcastJournal.OK, j.read(1, dst, 0));
                assertEquals(11, dst.get(ValueLayout.JAVA_LONG, 0));
                assertEquals(BroadcastJournal.EMPTY, j.read(2, dst, 0));
            }
        }
    }

    @Test
    void aReaderMoreThanCapacityBehindIsLappedNotFedOverwrittenData() {
        try (var mem = new NativeMemory()) {
            var j = new BroadcastJournal(mem, 4, 8);
            MemorySegment dst = mem.allocate(8);
            for (int i = 0; i < 10; i++) write(j, 100 + i);
            j.publish();
            assertEquals(6, j.oldest());
            for (int p = 0; p < 6; p++) assertEquals(BroadcastJournal.LAPPED, j.read(p, dst, 0), "pos " + p);
            for (int p = 6; p < 10; p++) {
                assertEquals(BroadcastJournal.OK, j.read(p, dst, 0));
                assertEquals(100 + p, dst.get(ValueLayout.JAVA_LONG, 0));
            }
        }
    }

    @Test
    void readingASlotBeingRewrittenReportsLapped() {
        try (var mem = new NativeMemory()) {
            var j = new BroadcastJournal(mem, 2, 8);
            MemorySegment dst = mem.allocate(8);
            write(j, 0);
            write(j, 1);
            j.publish();
            j.begin(); // record 2 overwrites record 0's slot; the writer is mid-record
            assertEquals(BroadcastJournal.LAPPED, j.read(0, dst, 0));
            j.end();
            assertThrows(IllegalStateException.class, () -> { j.begin(); j.begin(); });
        }
    }
}
