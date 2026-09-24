package dev.mulcor.harness;

import com.lmax.disruptor.EventPoller;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.InsufficientCapacityException;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapRing;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpmcArrayQueue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The region-inbox pattern (many producer regions, one consuming region), 2 producers : 1 consumer, 4096 slots.
 * Compares Mulcor's off-heap ring against JCTools, the LMAX Disruptor, and a lock-based ArrayBlockingQueue.
 * The meaningful metric is {@code transfers} (successful hand-offs per µs, counted by the consumer). The raw
 * group score also counts failed offers and polls.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class InboxQueueBenchmark {
    static final int CAPACITY = 4096;
    static final Object TOKEN = new Object();

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class Transfers {
        public long transfers;
    }

    @State(Scope.Group)
    public static class Mulcor {
        NativeMemory mem;
        OffHeapRing ring;

        @Setup
        public void setup() {
            mem = new NativeMemory();
            ring = new OffHeapRing(mem, CAPACITY, 104);
        }

        @TearDown
        public void tearDown() {
            mem.close();
        }
    }

    @Benchmark
    @Group("mulcorOffHeapRing")
    @GroupThreads(2)
    public boolean mulcorProduce(Mulcor s) {
        long pos = s.ring.tryClaim();
        if (pos < 0) return false;
        s.ring.segment().set(ValueLayout.JAVA_LONG, s.ring.payloadOffset(pos), pos);
        s.ring.publish(pos);
        return true;
    }

    @Benchmark
    @Group("mulcorOffHeapRing")
    @GroupThreads(1)
    public void mulcorConsume(Mulcor s, Transfers t, Blackhole bh) {
        long pos = s.ring.tryAcquire();
        if (pos >= 0) {
            bh.consume(s.ring.segment().get(ValueLayout.JAVA_LONG, s.ring.payloadOffset(pos)));
            s.ring.release(pos);
            t.transfers++;
        }
    }

    @State(Scope.Group)
    public static class JCTools {
        MpmcArrayQueue<Object> q = new MpmcArrayQueue<>(CAPACITY);
    }

    @Benchmark
    @Group("jctoolsMpmc")
    @GroupThreads(2)
    public boolean jctoolsProduce(JCTools s) {
        return s.q.offer(TOKEN);
    }

    @Benchmark
    @Group("jctoolsMpmc")
    @GroupThreads(1)
    public Object jctoolsConsume(JCTools s, Transfers t) {
        Object o = s.q.poll();
        if (o != null) t.transfers++;
        return o;
    }

    @State(Scope.Group)
    public static class Abq {
        ArrayBlockingQueue<Object> q = new ArrayBlockingQueue<>(CAPACITY);
    }

    @Benchmark
    @Group("arrayBlockingQueue")
    @GroupThreads(2)
    public boolean abqProduce(Abq s) {
        return s.q.offer(TOKEN);
    }

    @Benchmark
    @Group("arrayBlockingQueue")
    @GroupThreads(1)
    public Object abqConsume(Abq s, Transfers t) {
        Object o = s.q.poll();
        if (o != null) t.transfers++;
        return o;
    }

    public static final class LongEvent {
        long value;
    }

    @State(Scope.Group)
    public static class Disruptor {
        RingBuffer<LongEvent> ring;
        EventPoller<LongEvent> poller;
        EventPoller.Handler<LongEvent> handler;
        long sink, consumed;

        @Setup
        public void setup() {
            ring = RingBuffer.createMultiProducer(LongEvent::new, CAPACITY);
            poller = ring.newPoller();
            ring.addGatingSequences(poller.getSequence());
            // Return true: consume the whole available batch per poll, the Disruptor's intended usage. (One event
            // per poll forces an O(ring) availability rescan per event under multi-producer sequencing.)
            handler = (event, sequence, endOfBatch) -> {
                sink += event.value;
                consumed++;
                return true;
            };
        }
    }

    @Benchmark
    @Group("lmaxDisruptor")
    @GroupThreads(2)
    public boolean disruptorProduce(Disruptor s) {
        try {
            long seq = s.ring.tryNext();
            s.ring.get(seq).value = seq;
            s.ring.publish(seq);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Benchmark
    @Group("lmaxDisruptor")
    @GroupThreads(1)
    public long disruptorConsume(Disruptor s, Transfers t) throws Exception {
        long before = s.consumed;
        s.poller.poll(s.handler);
        t.transfers += s.consumed - before;
        return s.sink;
    }
}
