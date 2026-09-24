package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Engine;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tick-budget gate. N bots run the full behaviour mix (mining, navigation across chunks and regions, TNT,
 * chest traffic). Meanwhile N virtual clients each send one packet per tick from 2 producer threads,
 * concurrently with the tick. After warm-up, the measured window must satisfy:
 * <ul>
 *   <li>p99.9 tick ≤ 5 ms (at least 200 TPS)</li>
 *   <li>no stutter: no tick > 10 ms (with {@code -Pstrict}, no tick > 5 ms)</li>
 *   <li>every region's p99 ≤ 5 ms</li>
 *   <li>zero monitor enters/waits and zero lock parks on simulation threads (JFR)</li>
 *   <li>zero protocol violations</li>
 * </ul>
 */
@Tag("stress")
class TickBudgetStressTest {
    static final long BUDGET = 5_000_000, STUTTER = 10_000_000;
    static final boolean STRICT = Boolean.getBoolean("mulcor.strict");

    @ParameterizedTest(name = "{0} bots")
    @ValueSource(ints = {1000, 2500, 5000})
    void tickBudget(int bots) throws Exception {
        try (Engine engine = Scenario.build(Scenario.config().build(), bots, true, 1234)) {
            var clients = new ClientFlood(engine, bots, 2, 1, true, 99);
            LockAudit.Result audit;
            TickReport report;
            try {
                engine.run(6000); // warm-up: JIT tiers, call-site linkage, rebalancing settles
                try (var lockAudit = new LockAudit()) {
                    report = TickReport.measure(bots + " bots", engine, bots, 6000, BUDGET, STUTTER);
                    Set<Long> workers = new HashSet<>();
                    for (Thread t : engine.workerThreads()) workers.add(t.threadId());
                    audit = lockAudit.finish(workers, Set.of(Thread.currentThread().threadId()));
                }
            } finally {
                clients.close();
            }
            report.clientAccepted = clients.accepted();
            report.clientRejected = clients.rejected();
            System.out.println(report.line());
            System.out.println("  clients: accepted=" + report.clientAccepted + " rejected=" + report.clientRejected
                    + "  " + report.stats);
            System.out.println("  lock audit: " + audit.monitorEnters() + " monitor enters, " + audit.monitorWaits()
                    + " waits, " + audit.enginePark() + " engine parks");

            assertTrue(audit.clean(), "lock activity on simulation threads: " + audit);
            assertEquals(0, report.stats.stateViolations, "protocol violations: " + report.stats);
            assertTrue(report.p999Us <= BUDGET / 1000.0, "p99.9 tick " + report.p999Us + "us > 5ms: " + report.line());
            assertEquals(0, report.stutters, "ticks over 10ms: " + report.line());
            assertTrue(report.worstRegionP99Us() <= BUDGET / 1000.0, "a region's p99 > 5ms: " + report.line());
            if (STRICT) assertEquals(0, report.overBudget, "strict: ticks over 5ms: " + report.line());
            assertTrue(report.clientAccepted > (long) bots * 3000, "clients must actually be sending");
        }
    }
}
