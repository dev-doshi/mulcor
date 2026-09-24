package dev.mulcor.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Chooses how many simulation workers to run.
 *
 * <p>On asymmetric CPUs (Apple M-series P/E cores, ARM big.LITTLE), spreading region ticks over efficiency
 * cores stretches the epoch barrier to the speed of the slowest core. So by default only performance cores
 * are used.
 *
 * <p>Precedence:
 * <ol>
 *   <li>{@code -Dmulcor.workers=N}</li>
 *   <li>macOS/aarch64: {@code sysctl hw.perflevel0.physicalcpu} (the P-core count)</li>
 *   <li>other aarch64: {@code max(1, availableProcessors / 2)}</li>
 *   <li>otherwise: {@code availableProcessors}</li>
 * </ol>
 */
public final class WorkerTopology {
    private WorkerTopology() {}

    public record Decision(int workers, String source) {}

    public static Decision decide() {
        String override = System.getProperty("mulcor.workers");
        if (override != null && !override.isBlank()) {
            return new Decision(Math.max(1, Integer.parseInt(override.trim())), "property mulcor.workers");
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (arm && os.contains("mac")) {
            int pCores = sysctlInt("hw.perflevel0.physicalcpu");
            if (pCores > 0) {
                return new Decision(Math.min(pCores, cpus), "sysctl hw.perflevel0.physicalcpu");
            }
        }
        if (arm) {
            return new Decision(Math.max(1, cpus / 2), "heterogeneous ARM heuristic (cpus/2)");
        }
        return new Decision(cpus, "availableProcessors");
    }

    public static int workers() {
        return decide().workers();
    }

    private static int sysctlInt(String key) {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            return p.exitValue() == 0 ? Integer.parseInt(out) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
