package dev.mulcor.core;

/**
 * Immutable engine configuration. Every native buffer is sized from these values at startup and nothing
 * grows while ticking.
 */
public record EngineConfig(
        int chunksX,
        int chunksZ,
        int minY,
        int sections,
        int cellChunks,
        int workers,
        int maxRegions,
        int initialRegions,
        int regionEntityCapacity,
        int maxEntities,
        int inboxCapacity,
        int ingressCapacity,
        int ingressBudget,
        int rebalanceInterval,
        long tickBudgetNanos,
        double maxCompactness,
        int sampleCapacity,
        int chestsPerCell,
        double pillarDensity,
        long seed) {

    public int cellBlocks() { return cellChunks * 16; }
    public int cellsX() { return chunksX / cellChunks; }
    public int cellsZ() { return chunksZ / cellChunks; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int chunksX = 32, chunksZ = 32, minY = 0, sections = 1, cellChunks = 4;
        private int workers = WorkerTopology.workers();
        private int maxRegions = 64, initialRegions = -1, regionEntityCapacity = 4096, maxEntities = 16384;
        private int inboxCapacity = 4096, ingressCapacity = 4096, ingressBudget = 4096, rebalanceInterval = 20;
        private long tickBudgetNanos = 5_000_000L;
        private double maxCompactness = 2.0;
        private int sampleCapacity = 1 << 16, chestsPerCell = 1;
        private double pillarDensity = 0.03;
        private long seed = 42;

        public Builder world(int chunksX, int chunksZ) { this.chunksX = chunksX; this.chunksZ = chunksZ; return this; }
        public Builder height(int minY, int sections) { this.minY = minY; this.sections = sections; return this; }
        public Builder cellChunks(int v) { cellChunks = v; return this; }
        public Builder workers(int v) { workers = v; return this; }
        public Builder maxRegions(int v) { maxRegions = v; return this; }
        public Builder initialRegions(int v) { initialRegions = v; return this; }
        public Builder regionEntityCapacity(int v) { regionEntityCapacity = v; return this; }
        public Builder maxEntities(int v) { maxEntities = v; return this; }
        public Builder inboxCapacity(int v) { inboxCapacity = v; return this; }
        public Builder ingressCapacity(int v) { ingressCapacity = v; return this; }
        public Builder ingressBudget(int v) { ingressBudget = v; return this; }
        public Builder rebalanceInterval(int v) { rebalanceInterval = v; return this; }
        public Builder tickBudgetNanos(long v) { tickBudgetNanos = v; return this; }
        public Builder maxCompactness(double v) { maxCompactness = v; return this; }
        public Builder sampleCapacity(int v) { sampleCapacity = v; return this; }
        public Builder chestsPerCell(int v) { chestsPerCell = v; return this; }
        public Builder pillarDensity(double v) { pillarDensity = v; return this; }
        public Builder seed(long v) { seed = v; return this; }

        public EngineConfig build() {
            if (chunksX % cellChunks != 0 || chunksZ % cellChunks != 0) {
                throw new IllegalArgumentException("world size must be a multiple of cellChunks");
            }
            int initial = initialRegions > 0 ? initialRegions : defaultInitialRegions(workers, (chunksX / cellChunks) * (chunksZ / cellChunks));
            if (initial > maxRegions) {
                throw new IllegalArgumentException("initialRegions > maxRegions");
            }
            return new EngineConfig(chunksX, chunksZ, minY, sections, cellChunks, workers, maxRegions, initial,
                    regionEntityCapacity, maxEntities, inboxCapacity, ingressCapacity, ingressBudget,
                    rebalanceInterval, tickBudgetNanos, maxCompactness, sampleCapacity, chestsPerCell,
                    pillarDensity, seed);
        }

        /** Smallest power of 4 that is ≥ 2 × workers (so Hilbert ranges start as squares), capped by cell count. */
        private static int defaultInitialRegions(int workers, int cells) {
            int r = 1;
            while (r < 2 * workers) r *= 4;
            return Math.min(r, cells);
        }
    }
}
