package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Engine;
import dev.mulcor.memory.AllocationMeter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Goes beyond the static-load requirement. With 5,000 bots running every behaviour (mining, pathfinding, TNT,
 * chests, region crossings, rebalancing) and 5,000 paced virtual clients, the steady-state tick still allocates
 * 0 heap bytes on the driver and on every simulation worker.
 *
 * <p>Warm-up is 12,000 ticks: rarely taken paths (explosions near borders, forwarding, splits) need that long for
 * their VarHandle call sites to link and reach C2. JFR traces of shorter runs show only {@code MethodType} and
 * {@code MemberName} linkage objects.
 */
@Tag("alloc")
class FullLoadZeroAllocationTest {
    @Test
    void fullBehaviourLoadAllocatesNothing() {
        try (Engine engine = Scenario.build(Scenario.config().build(), 5000, true, 1234)) {
            var clients = new ClientFlood(engine, 5000, 2, 1, true, 99);
            try {
                engine.run(12_000);
                // Only engine.run() may sit between the driver-counter reads: MXBean calls allocate on this thread.
                long gc0 = TickReport.gcCount();
                long w0 = engine.workerAllocatedBytes();
                int ticks = 3000;
                long d0 = AllocationMeter.currentThread();
                engine.run(ticks);
                long driver = AllocationMeter.currentThread() - d0;
                long workers = engine.workerAllocatedBytes() - w0;
                long gcs = TickReport.gcCount() - gc0;
                System.out.printf("full load: %d ticks, workers=%d B, driver=%d B, GCs=%d, %s%n", ticks, workers, driver, gcs, engine.stats());
                assertEquals(0, workers, "workers allocated " + workers + " B");
                assertEquals(0, driver, "driver allocated " + driver + " B");
            } finally {
                clients.close();
            }
        }
    }
}
