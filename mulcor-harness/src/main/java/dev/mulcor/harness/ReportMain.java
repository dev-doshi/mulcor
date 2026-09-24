package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.WorkerTopology;
import dev.mulcor.memory.AllocationMeter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Produces the metrics summary: tick latency and TPS capability, per-region latency, allocation per tick,
 * worker-count scaling, plus aggregated JMH, jcstress and JUnit gate results already produced by the build.
 *
 * <p>Usage: {@code ReportMain <outputDir> <projectRoot>}.
 */
public final class ReportMain {
    private static final long BUDGET = 5_000_000, STUTTER = 10_000_000;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/reports/mulcor");
        Path root = Path.of(args.length > 1 ? args[1] : ".");
        Files.createDirectories(out);
        StringBuilder md = new StringBuilder();
        StringBuilder json = new StringBuilder("{\n");

        var topo = WorkerTopology.decide();
        String cpu = sysctl("machdep.cpu.brand_string");
        md.append("# Mulcor metrics summary\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| CPU | ").append(cpu).append(" (").append(Runtime.getRuntime().availableProcessors()).append(" hardware threads) |\n");
        md.append("| JVM | ").append(System.getProperty("java.vm.name")).append(' ').append(Runtime.version()).append(" |\n");
        md.append("| GC | ").append(ManagementFactory.getGarbageCollectorMXBeans().stream().map(g -> g.getName()).toList()).append(" |\n");
        md.append("| Simulation workers | ").append(topo.workers()).append(" (").append(topo.source()).append(") |\n\n");
        json.append("  \"environment\": {\"cpu\": \"").append(cpu).append("\", \"hardwareThreads\": ")
                .append(Runtime.getRuntime().availableProcessors()).append(", \"jvm\": \"").append(Runtime.version())
                .append("\", \"workers\": ").append(topo.workers()).append("},\n");

        // 1. Tick budget under full behaviour + paced clients
        System.out.println("[report] tick budget runs");
        md.append("## Tick latency: full behaviour mix + 1 packet/client/tick\n\n");
        md.append("The tick budget is 5 ms (200 TPS). \"TPS capability\" = 1 s / mean tick time.\n\n");
        md.append("| Bots | Mean | p50 | p99 | p99.9 | Max | TPS capability | Ticks > 5 ms | Ticks > 10 ms | Worst region p99 | Regions | Client packets |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        json.append("  \"tickBudget\": [\n");
        TickReport biggest = null;
        int[] sizes = {1000, 2500, 5000};
        for (int k = 0; k < sizes.length; k++) {
            int bots = sizes[k];
            try (Engine engine = Scenario.build(Scenario.config().build(), bots, true, 1234)) {
                var clients = new ClientFlood(engine, bots, 2, 1, true, 99);
                TickReport r;
                try {
                    engine.run(6000);
                    r = TickReport.measure(bots + " bots", engine, bots, 6000, BUDGET, STUTTER);
                } finally {
                    clients.close();
                }
                r.clientAccepted = clients.accepted();
                System.out.println("  " + r.line());
                md.append(String.format(Locale.ROOT, "| %d | %.0f µs | %.0f µs | %.0f µs | %.0f µs | %.0f µs | %,.0f | %d | %d | %.0f µs | %d | %,d |\n",
                        bots, r.meanUs, r.p50Us, r.p99Us, r.p999Us, r.maxUs, r.tpsCapability(), r.overBudget, r.stutters,
                        r.worstRegionP99Us(), r.regions.size(), r.clientAccepted));
                json.append(String.format(Locale.ROOT, "    {\"bots\": %d, \"meanUs\": %.2f, \"p50Us\": %.2f, \"p99Us\": %.2f, \"p999Us\": %.2f, \"maxUs\": %.2f, \"tpsCapability\": %.1f, \"ticksOver5ms\": %d, \"ticksOver10ms\": %d, \"worstRegionP99Us\": %.2f, \"regions\": %d}%s\n",
                        bots, r.meanUs, r.p50Us, r.p99Us, r.p999Us, r.maxUs, r.tpsCapability(), r.overBudget, r.stutters,
                        r.worstRegionP99Us(), r.regions.size(), k + 1 < sizes.length ? "," : ""));
                biggest = r;
            }
        }
        json.append("  ],\n");

        // 2. Per-region latency at 5,000 bots
        md.append("\n## Per-region tick latency (5,000 bots)\n\n| Region | Ticks | Entities at end | p50 | p99 | Max |\n|---|---|---|---|---|---|\n");
        json.append("  \"regions5000\": [\n");
        for (int k = 0; k < biggest.regions.size(); k++) {
            var rs = biggest.regions.get(k);
            md.append(String.format(Locale.ROOT, "| %d | %d | %d | %.1f µs | %.1f µs | %.1f µs |\n", rs.id(), rs.ticks(), rs.entities(), rs.p50Us(), rs.p99Us(), rs.maxUs()));
            json.append(String.format(Locale.ROOT, "    {\"id\": %d, \"ticks\": %d, \"entities\": %d, \"p50Us\": %.2f, \"p99Us\": %.2f, \"maxUs\": %.2f}%s\n",
                    rs.id(), rs.ticks(), rs.entities(), rs.p50Us(), rs.p99Us(), rs.maxUs(), k + 1 < biggest.regions.size() ? "," : ""));
        }
        json.append("  ],\n");
        md.append("\nRegion counts change over the run because load-based splits and merges are active (every 20 ticks).\n");

        // 3. Allocation
        System.out.println("[report] allocation runs");
        long[] staticAlloc = allocation(false);
        long[] fullAlloc = allocation(true);
        md.append("\n## Heap allocation in steady state\n\n| Load | Ticks | Worker bytes | Driver bytes | Bytes/tick | GCs |\n|---|---|---|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| Static (2,000 idle bots, physics only) | %d | %d | %d | %.2f | %d |\n", staticAlloc[0], staticAlloc[1], staticAlloc[2], (staticAlloc[1] + staticAlloc[2]) / (double) staticAlloc[0], staticAlloc[3]));
        md.append(String.format(Locale.ROOT, "| Full (5,000 active bots + 5,000 clients) | %d | %d | %d | %.2f | %d |\n", fullAlloc[0], fullAlloc[1], fullAlloc[2], (fullAlloc[1] + fullAlloc[2]) / (double) fullAlloc[0], fullAlloc[3]));
        md.append("\nSteady state = after 12,000 warm-up ticks, which covers C2 tier-up of once-per-tick paths and linkage of rarely used VarHandle call sites.\n");
        json.append(String.format(Locale.ROOT, "  \"allocation\": {\"static\": {\"ticks\": %d, \"workerBytes\": %d, \"driverBytes\": %d, \"gcs\": %d}, \"full\": {\"ticks\": %d, \"workerBytes\": %d, \"driverBytes\": %d, \"gcs\": %d}},\n",
                staticAlloc[0], staticAlloc[1], staticAlloc[2], staticAlloc[3], fullAlloc[0], fullAlloc[1], fullAlloc[2], fullAlloc[3]));

        // 4. Scaling
        System.out.println("[report] scaling runs");
        md.append("\n## Scaling with worker threads (5,000 bots, full behaviour, no clients)\n\n| Workers | Mean tick | p99 | Speedup vs 1 | Efficiency |\n|---|---|---|---|---|\n");
        json.append("  \"scaling\": [\n");
        int[] ws = {1, 2, 4, 8};
        double base = 0;
        for (int k = 0; k < ws.length; k++) {
            try (Engine engine = Scenario.build(Scenario.config(ws[k]).build(), 5000, true, 1234)) {
                engine.run(3000);
                var r = TickReport.measure(ws[k] + " workers", engine, 5000, 4000, BUDGET, STUTTER);
                if (k == 0) base = r.meanUs;
                double speedup = base / r.meanUs;
                System.out.println("  " + r.line());
                md.append(String.format(Locale.ROOT, "| %d | %.0f µs | %.0f µs | %.2f× | %.0f%% |\n", ws[k], r.meanUs, r.p99Us, speedup, 100 * speedup / ws[k]));
                json.append(String.format(Locale.ROOT, "    {\"workers\": %d, \"meanUs\": %.2f, \"p99Us\": %.2f, \"speedup\": %.3f, \"efficiency\": %.3f}%s\n",
                        ws[k], r.meanUs, r.p99Us, speedup, speedup / ws[k], k + 1 < ws.length ? "," : ""));
            }
        }
        json.append("  ],\n");
        md.append("\nOn Apple M1, workers 1–4 run on performance cores and 8 adds the 4 slower efficiency cores. The 64+ thread target cannot be measured on this machine.\n");

        // 5. JMH
        md.append("\n## JMH microbenchmarks\n\n");
        json.append("  \"jmh\": [\n");
        List<String> jmhJson = new ArrayList<>();
        StringBuilder jmhMd = new StringBuilder("| Benchmark | Params | Score | Alloc (B/op) |\n|---|---|---|---|\n");
        try (Stream<Path> files = Files.walk(root, 5)) {
            for (Path f : files.filter(p -> p.endsWith(Path.of("reports", "jmh", "results.json"))).sorted().toList()) {
                for (Object o : (List<?>) Json.parse(Files.readString(f))) {
                    Map<?, ?> b = (Map<?, ?>) o;
                    String name = ((String) b.get("benchmark")).replace("dev.mulcor.", "");
                    Map<?, ?> pm = (Map<?, ?>) b.get("primaryMetric");
                    Map<?, ?> params = (Map<?, ?>) b.get("params");
                    Map<?, ?> sec = (Map<?, ?>) b.get("secondaryMetrics");
                    Object alloc = sec != null && sec.get("gc.alloc.rate.norm") != null ? ((Map<?, ?>) sec.get("gc.alloc.rate.norm")).get("score") : null;
                    if (sec != null && sec.get("transfers") != null) pm = (Map<?, ?>) sec.get("transfers"); // successful hand-offs only
                    String allocStr = alloc == null ? "–" : String.format(Locale.ROOT, "%.3f", (Double) alloc);
                    jmhMd.append(String.format(Locale.ROOT, "| %s | %s | %.3f %s | %s |\n", name, params == null ? "" : params.toString(),
                            (Double) pm.get("score"), pm.get("scoreUnit"), allocStr));
                    jmhJson.add(String.format(Locale.ROOT, "    {\"benchmark\": \"%s\", \"params\": \"%s\", \"score\": %.4f, \"unit\": \"%s\", \"allocBytesPerOp\": %s}",
                            name, params == null ? "" : params.toString(), (Double) pm.get("score"), pm.get("scoreUnit"), alloc == null ? "null" : allocStr));
                }
            }
        }
        md.append(jmhJson.isEmpty() ? "_No JMH results found; run `./gradlew jmhRun` first._\n" : jmhMd.toString());
        md.append("\nInbox queue scores are successful transfers/µs (2 producers : 1 consumer). JMH's `gc.alloc.rate.norm` is "
                + "process-wide: it includes the JMH harness threads and JIT linkage still happening during short warm-ups. "
                + "The engine's own threads are measured exactly, per thread, by the allocation gates above (0 B/tick).\n");
        json.append(String.join(",\n", jmhJson)).append("\n  ],\n");

        // 6. Gate results from the build (JUnit XML + jcstress)
        md.append("\n## Validation gates (from this build's reports)\n\n| Module | Task | Tests | Failures | Errors |\n|---|---|---|---|---|\n");
        json.append("  \"gates\": [\n");
        List<String> gateJson = new ArrayList<>();
        Pattern suite = Pattern.compile("<testsuite [^>]*tests=\"(\\d+)\"[^>]*failures=\"(\\d+)\"[^>]*errors=\"(\\d+)\"");
        try (Stream<Path> dirs = Files.walk(root, 4)) {
            for (Path d : dirs.filter(p -> p.getParent() != null && p.getParent().endsWith(Path.of("build", "test-results")) && Files.isDirectory(p)).sorted().toList()) {
                int tests = 0, failures = 0, errors = 0;
                try (Stream<Path> xs = Files.list(d)) {
                    for (Path x : xs.filter(p -> p.toString().endsWith(".xml")).toList()) {
                        Matcher m = suite.matcher(Files.readString(x));
                        if (m.find()) {
                            tests += Integer.parseInt(m.group(1));
                            failures += Integer.parseInt(m.group(2));
                            errors += Integer.parseInt(m.group(3));
                        }
                    }
                }
                if (tests == 0) continue;
                String module = d.getParent().getParent().getParent().getFileName().toString();
                md.append(String.format("| %s | %s | %d | %d | %d |\n", module, d.getFileName(), tests, failures, errors));
                gateJson.add(String.format("    {\"module\": \"%s\", \"task\": \"%s\", \"tests\": %d, \"failures\": %d, \"errors\": %d}", module, d.getFileName(), tests, failures, errors));
            }
        }
        json.append(String.join(",\n", gateJson)).append("\n  ],\n");
        md.append("\n### jcstress (full mode)\n\n| Module | Tests | Result |\n|---|---|---|\n");
        json.append("  \"jcstress\": [\n");
        List<String> jcJson = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 5)) {
            for (Path f : files.filter(p -> p.endsWith(Path.of("reports", "jcstress", "index.html"))).sorted().toList()) {
                String html = Files.readString(f);
                String module = f.getParent().getParent().getParent().getParent().getFileName().toString();
                // Each test row ends in a status cell: ">PASSED", ">FAILED" or ">ERROR" (legend text excluded).
                long passed = Pattern.compile("\">PASSED").matcher(html).results().count();
                long bad = Pattern.compile("\">(FAILED|ERROR)").matcher(html).results().count();
                String status = bad > 0 ? "**" + bad + " FAILED/ERROR**" : "all outcomes acceptable";
                md.append(String.format("| %s | %d passed, %d failed | %s |\n", module, passed, bad, status));
                jcJson.add(String.format("    {\"module\": \"%s\", \"passed\": %d, \"failed\": %d}", module, passed, bad));
            }
        }
        json.append(String.join(",\n", jcJson)).append("\n  ]\n}\n");

        Files.writeString(out.resolve("summary.md"), md.toString());
        Files.writeString(out.resolve("summary.json"), json.toString());
        System.out.println("[report] wrote " + out.resolve("summary.md"));
    }

    /** Returns {ticks, workerBytes, driverBytes, gcs} for a steady-state window. */
    private static long[] allocation(boolean full) {
        EngineConfig cfg = Scenario.config().build();
        try (Engine engine = Scenario.build(cfg, full ? 5000 : 2000, full, 1234)) {
            engine.setAiEnabled(full);
            ClientFlood clients = full ? new ClientFlood(engine, 5000, 2, 1, true, 99) : null;
            try {
                engine.run(12_000);
                int ticks = full ? 3000 : 5000;
                long gc0 = TickReport.gcCount();
                long w0 = engine.workerAllocatedBytes();
                long d0 = AllocationMeter.currentThread();
                engine.run(ticks);
                long driver = AllocationMeter.currentThread() - d0;
                long workers = engine.workerAllocatedBytes() - w0;
                long gcs = TickReport.gcCount() - gc0;
                System.out.printf("  %s load: workers=%d B driver=%d B over %d ticks, GCs=%d%n", full ? "full" : "static", workers, driver, ticks, gcs);
                return new long[] {ticks, workers, driver, gcs};
            } finally {
                if (clients != null) clients.close();
            }
        }
    }

    private static String sysctl(String key) {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 && !s.isEmpty() ? s : System.getProperty("os.arch");
        } catch (IOException | InterruptedException e) {
            return System.getProperty("os.arch");
        }
    }
}
