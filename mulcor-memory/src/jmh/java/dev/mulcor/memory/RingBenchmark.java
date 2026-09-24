package dev.mulcor.memory;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * OffHeapRing throughput. {@code roundTrip} is the uncontended cost of one claim-publish-acquire-release, and
 * the {@code mpmc} group is 2 producers against 2 consumers on one ring.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RingBenchmark {
    private NativeMemory mem;
    private OffHeapRing ring;

    @Setup
    public void setup() {
        mem = new NativeMemory();
        ring = new OffHeapRing(mem, 4096, 64);
    }

    @TearDown
    public void tearDown() {
        mem.close();
    }

    @Benchmark
    @Group("roundTrip")
    @GroupThreads(1)
    public long roundTrip() {
        long pos = ring.tryClaim();
        ring.segment().set(ValueLayout.JAVA_LONG, ring.payloadOffset(pos), pos);
        ring.publish(pos);
        long c = ring.tryAcquire();
        long v = ring.segment().get(ValueLayout.JAVA_LONG, ring.payloadOffset(c));
        ring.release(c);
        return v;
    }

    @Benchmark
    @Group("mpmc")
    @GroupThreads(2)
    public boolean produce() {
        long pos = ring.tryClaim();
        if (pos < 0) {
            return false;
        }
        ring.segment().set(ValueLayout.JAVA_LONG, ring.payloadOffset(pos), pos);
        ring.publish(pos);
        return true;
    }

    @Benchmark
    @Group("mpmc")
    @GroupThreads(2)
    public void consume(Blackhole bh) {
        long pos = ring.tryAcquire();
        if (pos >= 0) {
            bh.consume(ring.segment().get(ValueLayout.JAVA_LONG, ring.payloadOffset(pos)));
            ring.release(pos);
        }
    }
}
