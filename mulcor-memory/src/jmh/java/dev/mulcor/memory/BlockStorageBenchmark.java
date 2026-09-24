package dev.mulcor.memory;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Random block reads/writes over a 16×16-chunk, 256-high world that is half solid. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BlockStorageBenchmark {
    private NativeMemory mem;
    private BlockStorage blocks;
    private int cursor;

    @Setup
    public void setup() {
        mem = new NativeMemory();
        blocks = new BlockStorage(mem, 16, 16, 0, 16, 16 * 16 * 16);
        for (int x = 0; x < 256; x++) for (int z = 0; z < 256; z++) for (int y = 0; y < 128; y++) blocks.set(x, y, z, 1);
    }

    @TearDown
    public void tearDown() {
        mem.close();
    }

    private int next() {
        cursor = cursor * 1_103_515_245 + 12_345;
        return cursor;
    }

    @Benchmark
    public int get() {
        int r = next();
        return blocks.get(r & 255, (r >>> 8) & 255, (r >>> 16) & 255);
    }

    @Benchmark
    public int setSolid() {
        int r = next();
        return blocks.set(r & 255, (r >>> 8) & 127, (r >>> 16) & 255, 1 + (r >>> 28));
    }
}
