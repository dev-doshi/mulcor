package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Engine;
import dev.mulcor.core.region.Entities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Anti-duplication under maximum pressure. 5,000 virtual clients are driven by 4 unpaced producer threads while
 * 5,000 bots run their behaviours. The flood hammers four shared chests, blocks where four regions meet, and
 * movement that forces constant region crossings. Every input may land in any region at any moment. Afterwards,
 * block and item mass must be conserved exactly and every entity must exist exactly once.
 */
@Tag("stress")
class ConcurrentFloodStressTest {
    @Test
    void maximumRateFloodConservesEverything() {
        try (Engine engine = Scenario.build(Scenario.config().build(), 5000, true, 77)) {
            long massBefore = Audit.mineableMass(engine);
            var flood = new ClientFlood(engine, 5000, 4, 1, false, 5);
            try {
                engine.run(3000);
            } finally {
                flood.close();
            }
            Audit.quiesce(engine);
            var s = engine.stats();
            long tnt = 0;
            for (var r : engine.world.regions) for (int i = 0; i < r.table.count(); i++) if (r.table.type(i) == Entities.TNT) tnt++;
            System.out.printf("flood: accepted=%d rejected(backpressure)=%d %s%n", flood.accepted(), flood.rejected(), s);
            assertEquals(5000 + tnt, Audit.entities(engine), "entity duplicated or lost");
            assertEquals(massBefore, Audit.mineableMass(engine), "block/item mass not conserved: " + s);
            assertEquals(0, s.stateViolations, s.toString());
            if (s.pending != 0) {
                for (var r : engine.world.regions) if (r.pendingMessages() > 0) System.out.println("  stuck: " + r.describeRings());
                for (int eid = 0; eid < engine.world.directory.capacity(); eid++) {
                    long w = engine.world.directory.ownership(eid);
                    if (dev.mulcor.memory.Ownership.state(w) == dev.mulcor.memory.Ownership.IN_TRANSIT) {
                        System.out.println("  in transit: entity " + eid + " " + dev.mulcor.memory.Ownership.toString(w)
                                + " epoch now " + engine.epoch());
                    }
                }
            }
            assertEquals(0, s.pending, "everything in flight must have landed");
            assertTrue(flood.accepted() > 1_000_000, "flood too small: " + flood.accepted());
            assertTrue(s.transfersOut > 1000, "flood must force region crossings: " + s);
        }
    }
}
