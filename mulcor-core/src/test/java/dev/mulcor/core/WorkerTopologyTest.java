package dev.mulcor.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkerTopologyTest {
    @Test
    void propertyOverrideWins() {
        String old = System.getProperty("mulcor.workers");
        try {
            System.setProperty("mulcor.workers", "3");
            assertEquals(3, WorkerTopology.workers());
        } finally {
            if (old == null) System.clearProperty("mulcor.workers"); else System.setProperty("mulcor.workers", old);
        }
    }

    @Test
    void defaultsToPerformanceCoresOnAsymmetricArm() {
        if (System.getProperty("mulcor.workers") != null) return;
        var d = WorkerTopology.decide();
        int cpus = Runtime.getRuntime().availableProcessors();
        assertTrue(d.workers() >= 1 && d.workers() <= cpus);
        String arch = System.getProperty("os.arch");
        if (arch.contains("aarch64")) {
            assertTrue(d.workers() < cpus, "ARM default must exclude efficiency cores: " + d);
        }
        System.out.println("worker topology: " + d + " of " + cpus + " cpus");
    }
}
