package dev.mulcor.harness;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.region.Region;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.Ownership;

/** World-level invariants shared by the stress tests. */
final class Audit {
    private Audit() {}

    /** Mineable blocks + those items anywhere + items dropped + blocks destroyed by explosions. Conserved exactly. */
    static long mineableMass(Engine e) {
        var w = e.world;
        var s = e.stats();
        return w.countBlocks(Blocks.STONE) + w.countBlocks(Blocks.DIRT) + w.countItems(Blocks.STONE)
                + w.countItems(Blocks.DIRT) + s.droppedItems + s.destroyedBlocks;
    }

    /**
     * Stop behaviours and all motion, then drain every ring until nothing is in flight. Moving entities keep
     * crossing boundaries, so without stopping motion some transfer is always legitimately in flight.
     */
    static void quiesce(Engine engine) {
        engine.setAiEnabled(false);
        engine.run(2); // let in-flight inputs (which can set velocities) land first
        for (Region r : engine.world.regions) {
            for (int s = 0; s < r.table.count(); s++) r.table.setVel(s, 0f, 0f, 0f); // between ticks: driver owns all
        }
        // Lit TNT keeps burning with AI off and its explosion messages neighbours, so wait until no TNT is left and
        // nothing has been in flight for several consecutive ticks.
        int quiet = 0;
        for (int i = 0; i < 2000 && quiet < 5; i++) {
            engine.tick();
            quiet = engine.stats().pending == 0 && !hasTnt(engine) ? quiet + 1 : 0;
        }
    }

    /** Returns live entity count, throwing if any entity is duplicated, lost, or inconsistent with the directory. */
    static int entities(Engine engine) {
        var world = engine.world;
        boolean[] seen = new boolean[world.directory.capacity()];
        int total = 0;
        for (Region r : world.regions) {
            EntityTable t = r.table;
            for (int s = 0; s < t.count(); s++) {
                int eid = (int) t.id(s);
                if (seen[eid]) throw new AssertionError("entity " + eid + " duplicated");
                seen[eid] = true;
                long w = world.directory.ownership(eid);
                if (Ownership.state(w) != Ownership.OWNED || Ownership.region(w) != r.id || world.directory.slot(eid) != s) {
                    throw new AssertionError("entity " + eid + " directory mismatch: " + Ownership.toString(w));
                }
                total++;
            }
        }
        for (int eid = 0; eid < seen.length; eid++) {
            int st = Ownership.state(world.directory.ownership(eid));
            if (st == Ownership.IN_TRANSIT) {
                if (seen[eid]) throw new AssertionError("entity " + eid + " both in a table and in transit");
                total++;
            } else if (st == Ownership.OWNED && !seen[eid]) {
                throw new AssertionError("entity " + eid + " lost");
            }
        }
        return total;
    }

    private static boolean hasTnt(Engine engine) {
        for (var r : engine.world.regions) {
            for (int s = 0; s < r.table.count(); s++) {
                if (r.table.type(s) == dev.mulcor.core.region.Entities.TNT) return true;
            }
        }
        return false;
    }
}
