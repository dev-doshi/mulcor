package dev.mulcor.net;

import java.lang.foreign.MemorySegment;

/** Where decoded input records go: normally {@code Engine::submitInput}. Must not block. */
@FunctionalInterface
public interface InputSink {
    /** Returns false for backpressure: the record was not accepted and must be offered again later. */
    boolean offer(MemorySegment record, long offset);
}
