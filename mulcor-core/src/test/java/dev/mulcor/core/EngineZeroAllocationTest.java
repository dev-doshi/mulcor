package dev.mulcor.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Entities;
import dev.mulcor.memory.AllocationMeter;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Static-load gate: 2,000 bots are present and physics runs every tick, but there are no inputs or behaviours.
 * In steady state the whole tick (driver thread plus every region worker) must allocate exactly 0 heap bytes.
 *
 * <p>Steady state means every path the tick uses has reached C2, including paths that run only once per tick
 * (the commit phase), which cross HotSpot's Tier4 threshold (~5,000 invocations) only after ~5,000 ticks.
 * JFR with {@code -XX:-UseTLAB} showed exactly one 72-byte JVM-internal allocation (String + byte[]) at that
 * tier-up, attributed to {@code TickRoot.release()} at epoch 5632, and 0 bytes for the following 29k ticks.
 * So warm-up runs at least 10,000 ticks, then waits for 3 consecutive allocation-free 1,000-tick windows.
 */
@Tag("alloc")
class EngineZeroAllocationTest {
    @Test
    void staticLoadTickAllocatesNothing() {
        var cfg = EngineConfig.builder().world(32, 32).cellChunks(4).maxRegions(64).maxEntities(8192)
                .regionEntityCapacity(4096).rebalanceInterval(20).sampleCapacity(1 << 14).build();
        try (var engine = new Engine(cfg)) {
            var rnd = new SplittableRandom(11);
            for (int i = 0; i < 2000; i++) engine.spawnBot(rnd.nextInt(512), rnd.nextInt(512), Entities.IDLE);
            engine.setAiEnabled(false);
            engine.run(10_000);
            int windows = 0, clean = 0;
            long warmupBytes = 0;
            while (clean < 3 && windows < 30) {
                long before = engine.workerAllocatedBytes();
                engine.run(1000);
                long delta = engine.workerAllocatedBytes() - before;
                warmupBytes += delta;
                clean = delta == 0 ? clean + 1 : 0;
                windows++;
            }
            System.out.printf("warm-up: 10000 + %d x 1000 ticks, %d B in the extra windows%n", windows, warmupBytes);
            long workers0 = engine.workerAllocatedBytes();
            long driver0 = AllocationMeter.currentThread();
            int ticks = 5000;
            engine.run(ticks);
            long driver = AllocationMeter.currentThread() - driver0;
            long workers = engine.workerAllocatedBytes() - workers0;
            System.out.printf("static load: %d ticks, workers=%d B, driver=%d B, regions=%d, threads=%d%n",
                    ticks, workers, driver, engine.activeRegions(), engine.workerThreads().length);
            assertEquals(0, workers, "worker threads allocated " + workers + " B over " + ticks + " ticks");
            assertEquals(0, driver, "driver thread allocated " + driver + " B over " + ticks + " ticks");
        }
    }
}
