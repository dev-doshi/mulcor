package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.WorkerTopology;
import dev.mulcor.core.region.Entities;
import java.util.SplittableRandom;

/**
 * The standard stress world: 1024×1024 blocks (64×64 chunks), 16×16 cells of 64 blocks, one shared chest per
 * cell, and bots split across miners (30%), navigators (30%), chest users (25%) and bombers (15%).
 */
public final class Scenario {
    private Scenario() {}

    public static EngineConfig.Builder config(int workers) {
        return EngineConfig.builder()
                .world(64, 64)
                .cellChunks(4)
                .workers(workers)
                .maxRegions(64)
                .initialRegions(16)
                .regionEntityCapacity(4096)
                .maxEntities(16384)
                .inboxCapacity(8192)
                .ingressCapacity(8192)
                .ingressBudget(8192)
                .rebalanceInterval(20)
                .tickBudgetNanos(5_000_000)
                .sampleCapacity(1 << 15);
    }

    public static EngineConfig.Builder config() {
        return config(WorkerTopology.workers());
    }

    public static int role(int i, boolean active) {
        if (!active) return Entities.IDLE;
        int r = i % 20;
        if (r < 6) return Entities.MINER;
        if (r < 12) return Entities.NAVIGATOR;
        if (r < 17) return Entities.CHESTER;
        return Entities.BOMBER;
    }

    /** Build an engine and spawn {@code bots} bots uniformly. Returns the engine; bot ids are 0..bots-1 in order. */
    public static Engine build(EngineConfig cfg, int bots, boolean activeRoles, long seed) {
        Engine engine = new Engine(cfg);
        var rnd = new SplittableRandom(seed);
        for (int i = 0; i < bots; i++) {
            int id = engine.spawnBot(rnd.nextInt(engine.world.sizeX()), rnd.nextInt(engine.world.sizeZ()), role(i, activeRoles));
            if (id != i) throw new IllegalStateException("expected dense bot ids, got " + id + " for " + i);
        }
        return engine;
    }
}
