package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Tick cost under heavy redstone and TNT load that crosses region borders, against the plain bot baseline.
 *
 * <ul>
 *   <li>{@code BASELINE}: 2,000 bots with every behaviour (as in {@link TickBenchmark}).</li>
 *   <li>{@code REDSTONE}: plus 16 clock-driven wire lines spanning the world (each toggle crosses 15 borders).</li>
 *   <li>{@code TNT}: plus 36 TNT clusters on four-region corners, re-armed every 100 ticks (chain priming, sand
 *       falling into the craters).</li>
 *   <li>{@code MIXED}: both.</li>
 * </ul>
 * The partition is fixed at 16 regions (no rebalancing) so every load crosses the same borders. Secondary results
 * are per-tick averages: block evaluations, cross-border neighbour updates, scheduled block ticks, explosions.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class CascadeBenchmark {
    public enum Load { BASELINE, REDSTONE, TNT, MIXED }

    @Param({"BASELINE", "REDSTONE", "TNT", "MIXED"})
    public Load load;

    @Param({"1", "4"})
    public int workers;

    @Param({"2000"})
    public int bots;

    Engine engine;
    private Cascades cascades;
    private long ticks;

    @Setup(Level.Trial)
    public void setup() {
        // Fixed partition (16 regions, no rebalancing), so every load crosses the same region borders.
        engine = Scenario.build(Scenario.config(workers).initialRegions(16).rebalanceInterval(0).build(), bots, true, 1234);
        boolean redstone = load == Load.REDSTONE || load == Load.MIXED, tnt = load == Load.TNT || load == Load.MIXED;
        cascades = new Cascades(engine, redstone ? 16 : 0, tnt ? 6 : 0);
        var before = engine.stats();
        for (int i = 0; i < 2000; i++) tick();
        var after = engine.stats();
        // Refuse to measure a dead contraption.
        if (redstone && (after.redstoneChanges - before.redstoneChanges < 1000 || after.crossUpdates - before.crossUpdates < 1000)) {
            throw new IllegalStateException("clock lines are not running across borders: " + after);
        }
        if (tnt && (after.tntPrimed - before.tntPrimed < 100 || after.explosions - before.explosions < 100)) {
            throw new IllegalStateException("TNT clusters are not exploding: " + after);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.close();
    }

    long tick() {
        if (ticks++ % 100 == 0 && cascades.spots() > 0) cascades.rearm();
        return engine.tick();
    }

    /** Per-tick averages of the engine's counters over each measurement iteration. */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class PerTick {
        public double blockUpdates, crossUpdates, scheduledTicks, explosions;
        private long t0, u0, c0, s0, e0;

        @Setup(Level.Iteration)
        public void start(CascadeBenchmark b) {
            var s = b.engine.stats();
            t0 = b.engine.epoch();
            u0 = s.blockUpdates;
            c0 = s.crossUpdates;
            s0 = s.scheduledTicks;
            e0 = s.explosions;
        }

        @TearDown(Level.Iteration)
        public void end(CascadeBenchmark b) {
            var s = b.engine.stats();
            double n = Math.max(1, b.engine.epoch() - t0);
            blockUpdates = (s.blockUpdates - u0) / n;
            crossUpdates = (s.crossUpdates - c0) / n;
            scheduledTicks = (s.scheduledTicks - s0) / n;
            explosions = (s.explosions - e0) / n;
        }
    }

    @Benchmark
    public long tickCounted(PerTick counters) {
        return tick();
    }
}
