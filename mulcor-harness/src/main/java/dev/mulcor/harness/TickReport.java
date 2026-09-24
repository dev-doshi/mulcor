package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import dev.mulcor.core.region.Region;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.HdrHistogram.Histogram;

/** Latency statistics for a measured window, built from the engine's in-loop sample rings after the run. */
public final class TickReport {
    public record RegionStat(int id, long ticks, double p50Us, double p99Us, double maxUs, int entities) {}

    public final String name;
    public final int workers, bots;
    public final long ticks;
    public final double meanUs, p50Us, p99Us, p999Us, maxUs, wallSeconds;
    public final long overBudget, stutters;
    public final List<RegionStat> regions;
    public final long allocatedBytes, gcCount;
    public final Engine.Stats stats;
    public long clientAccepted, clientRejected;

    private TickReport(String name, int workers, int bots, long ticks, Histogram h, double wallSeconds,
            long overBudget, long stutters, List<RegionStat> regions, long allocatedBytes, long gcCount, Engine.Stats stats) {
        this.name = name;
        this.workers = workers;
        this.bots = bots;
        this.ticks = ticks;
        this.meanUs = h.getMean() / 1000.0;
        this.p50Us = h.getValueAtPercentile(50) / 1000.0;
        this.p99Us = h.getValueAtPercentile(99) / 1000.0;
        this.p999Us = h.getValueAtPercentile(99.9) / 1000.0;
        this.maxUs = h.getMaxValue() / 1000.0;
        this.wallSeconds = wallSeconds;
        this.overBudget = overBudget;
        this.stutters = stutters;
        this.regions = regions;
        this.allocatedBytes = allocatedBytes;
        this.gcCount = gcCount;
        this.stats = stats;
    }

    public double tpsCapability() {
        return 1_000_000.0 / meanUs;
    }

    public double bytesPerTick() {
        return ticks == 0 ? 0 : (double) allocatedBytes / ticks;
    }

    public static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    /**
     * Runs {@code ticks} measured ticks (after the caller's warm-up) and collects latency, per-region cost,
     * allocation and GC counts.
     */
    public static TickReport measure(String name, Engine engine, int bots, int ticks, long budgetNanos, long stutterNanos) {
        engine.resetEpochSamples();
        long[] regionSamplesBefore = new long[engine.world.regions.length];
        for (Region r : engine.world.regions) regionSamplesBefore[r.id] = r.sampleCount();
        long gc0 = gcCount();
        long workers0 = engine.workerAllocatedBytes();
        // Only engine.run() between the driver-counter reads: MXBean calls allocate on this thread.
        long driver0 = dev.mulcor.memory.AllocationMeter.currentThread();
        long t0 = System.nanoTime();
        engine.run(ticks);
        double wall = (System.nanoTime() - t0) / 1e9;
        long driver = dev.mulcor.memory.AllocationMeter.currentThread() - driver0;
        long alloc = driver + engine.workerAllocatedBytes() - workers0;
        long gcs = gcCount() - gc0;

        long[] samples = new long[ticks];
        int n = engine.copyEpochSamples(samples);
        Histogram h = new Histogram(3_600_000_000_000L, 3);
        long over = 0, stutter = 0;
        for (int i = 0; i < n; i++) {
            h.recordValue(Math.max(1, samples[i]));
            if (samples[i] > budgetNanos) over++;
            if (samples[i] > stutterNanos) stutter++;
        }
        List<RegionStat> regions = new ArrayList<>();
        long[] buf = new long[1 << 15];
        for (Region r : engine.world.regions) {
            long count = r.sampleCount() - regionSamplesBefore[r.id];
            if (count <= 0) continue;
            int got = r.copySamples(buf);
            int from = (int) Math.max(0, got - count);
            Histogram rh = new Histogram(3_600_000_000_000L, 3);
            for (int i = from; i < got; i++) rh.recordValue(Math.max(1, buf[i]));
            regions.add(new RegionStat(r.id, count, rh.getValueAtPercentile(50) / 1000.0,
                    rh.getValueAtPercentile(99) / 1000.0, rh.getMaxValue() / 1000.0, r.isActive() ? r.table.count() : 0));
        }
        return new TickReport(name, engine.workers(), bots, n, h, wall, over, stutter, regions, alloc, gcs, engine.stats());
    }

    public double worstRegionP99Us() {
        return regions.stream().mapToDouble(RegionStat::p99Us).max().orElse(0);
    }

    public String line() {
        return String.format(Locale.ROOT,
                "%-24s workers=%d bots=%d ticks=%d mean=%.1fus p50=%.1fus p99=%.1fus p99.9=%.1fus max=%.1fus "
                        + "TPS-capability=%.0f >5ms=%d >10ms=%d worstRegionP99=%.1fus alloc=%.1fB/tick GCs=%d regions=%d",
                name, workers, bots, ticks, meanUs, p50Us, p99Us, p999Us, maxUs, tpsCapability(), overBudget, stutters,
                worstRegionP99Us(), bytesPerTick(), gcCount, regions.size());
    }
}
