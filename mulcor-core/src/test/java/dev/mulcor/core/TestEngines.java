package dev.mulcor.core;

import dev.mulcor.core.region.Region;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.Ownership;

final class TestEngines {
    private TestEngines() {}

    /** 256×256 blocks, 8×8 cells of 32 blocks, 16 initial regions. */
    static EngineConfig.Builder small() {
        return EngineConfig.builder()
                .world(16, 16)
                .cellChunks(2)
                .workers(4)
                .maxRegions(64)
                .initialRegions(16)
                .regionEntityCapacity(4096)
                .maxEntities(8192)
                .inboxCapacity(8192)
                .ingressCapacity(8192)
                .rebalanceInterval(0)
                .sampleCapacity(4096);
    }

    /** Run with AI and motion off until every inbox, overflow and ingress ring is empty. */
    static void quiesce(Engine engine) {
        engine.setAiEnabled(false);
        engine.run(2);
        for (Region r : engine.world.regions) {
            for (int s = 0; s < r.table.count(); s++) { r.table.setVel(s, 0f, 0f, 0f); r.table.setInput(s, 0f, 0f); }
        }
        // Lit TNT keeps burning with AI off and its explosion messages neighbours, so wait until no TNT is left and
        // nothing has been in flight for several consecutive ticks.
        int quiet = 0;
        for (int i = 0; i < 2000 && quiet < 5; i++) {
            engine.tick();
            quiet = engine.stats().pending == 0 && !hasTnt(engine) ? quiet + 1 : 0;
        }
    }

    /**
     * Every live entity is in exactly one place: either in the table of the region its ownership word names
     * (at the recorded slot), or IN_TRANSIT and in no table. Returns the number of live entities.
     */
    static int assertDirectoryConsistent(Engine engine) {
        var world = engine.world;
        int total = 0;
        boolean[] seen = new boolean[world.directory.capacity()];
        for (Region r : world.regions) {
            EntityTable t = r.table;
            if (!r.isActive()) {
                if (t.count() != 0) throw new AssertionError("inactive region " + r.id + " holds entities");
                continue;
            }
            for (int s = 0; s < t.count(); s++) {
                int eid = (int) t.id(s);
                if (seen[eid]) throw new AssertionError("entity " + eid + " present twice (duplication)");
                seen[eid] = true;
                long w = world.directory.ownership(eid);
                if (Ownership.state(w) != Ownership.OWNED || Ownership.region(w) != r.id) {
                    throw new AssertionError("entity " + eid + " in region " + r.id + " but directory says " + Ownership.toString(w));
                }
                if (world.directory.slot(eid) != s) throw new AssertionError("entity " + eid + " slot mismatch");
                total++;
            }
        }
        for (int eid = 0; eid < world.directory.capacity(); eid++) {
            long w = world.directory.ownership(eid);
            if (Ownership.state(w) == Ownership.IN_TRANSIT) {
                if (seen[eid]) throw new AssertionError("entity " + eid + " is in a table and in transit");
                total++;
            } else if (Ownership.state(w) == Ownership.OWNED && !seen[eid]) {
                throw new AssertionError("entity " + eid + " is OWNED but in no table (lost): " + Ownership.toString(w));
            }
        }
        return total;
    }

    static long mineableTotal(Engine e) {
        var w = e.world;
        var s = e.stats();
        return w.countBlocks(Blocks.STONE) + w.countBlocks(Blocks.DIRT) + w.countBlocks(Blocks.SAND) + fallingBlocks(e)
                + w.countItems(Blocks.STONE) + w.countItems(Blocks.DIRT) + w.countItems(Blocks.SAND)
                + s.droppedItems + s.destroyedBlocks;
    }

    private static boolean hasTnt(Engine engine) {
        for (var r : engine.world.regions) {
            for (int s = 0; s < r.table.count(); s++) {
                int type = r.table.type(s);
                if (type == dev.mulcor.core.region.Entities.TNT || type == dev.mulcor.core.region.Entities.FALLING_BLOCK) return true;
            }
        }
        return false;
    }

    /** Sand in flight: a falling block entity holds one block. */
    static long fallingBlocks(Engine engine) {
        long n = 0;
        for (var r : engine.world.regions) {
            if (!r.isActive()) continue;
            for (int s = 0; s < r.table.count(); s++) {
                if (r.table.type(s) == dev.mulcor.core.region.Entities.FALLING_BLOCK) n++;
            }
        }
        return n;
    }
}
