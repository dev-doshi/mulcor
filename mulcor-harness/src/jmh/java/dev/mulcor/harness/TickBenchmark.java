package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Cost of one full engine tick with 5,000 bots running every behaviour, across simulation worker counts.
 * 1 → 4 uses only performance cores on Apple M1; 8 adds the efficiency cores.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class TickBenchmark {
    @Param({"1", "2", "4", "8"})
    public int workers;

    @Param({"5000"})
    public int bots;

    private Engine engine;

    @Setup(Level.Trial)
    public void setup() {
        engine = Scenario.build(Scenario.config(workers).build(), bots, true, 1234);
        engine.run(2000);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.close();
    }

    @Benchmark
    public long tick() {
        return engine.tick();
    }
}
