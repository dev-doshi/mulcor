package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.net.NetServer;
import dev.mulcor.net.ServerContext;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.minestom.server.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Two headless vanilla clients over real sockets see each other: tab-list entries, player entities spawned with the
 * right UUID and followed as they move, a block one of them digs, and the other's leave. Every packet is parsed with
 * Minestom's serializers ({@link VanillaClient} fails on any byte left over).
 */
class MultiplayerTest {
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static void tickUntil(Engine engine, VanillaClient[] clients, BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.getAsBoolean()) {
            for (VanillaClient c : clients) {
                if (c != null && c.failure != null) throw new AssertionError(c.name + " failed", c.failure);
            }
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what + "; " + engine.stats());
            engine.tick();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static VanillaClient join(Engine engine, ExecutorService pool, int port, String name) throws Exception {
        Future<VanillaClient> f = pool.submit(() -> VanillaClient.join("127.0.0.1", port, name, WAIT));
        tickUntil(engine, new VanillaClient[0], f::isDone, name + " to join");
        return f.get();
    }

    @Test
    void playersSeeEachOtherAndEachOthersBlocks() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        EngineConfig cfg = EngineConfig.builder().world(8, 8).cellChunks(2).workers(4).initialRegions(4).maxRegions(16)
                .maxEntities(4096).rebalanceInterval(0).sampleCapacity(1024).build();
        try (Engine engine = new Engine(cfg)) {
            engine.setAiEnabled(false);
            ServerContext ctx = ServerContext.of(engine).withViewDistance(3);
            try (NetServer net = NetServer.vanilla(0, 2, ctx);
                    VanillaClient alice = join(engine, pool, net.port(), "alice")) {
                VanillaClient bob = join(engine, pool, net.port(), "bob");
                VanillaClient[] both = {alice, bob};
                int sx = ctx.spawnX(), sz = ctx.spawnZ();
                int sy = engine.surfaceAt(sx, sz);
                alice.moveTo(sx + 0.5, sy, sz + 0.5, 0f, 0f);
                bob.moveTo(sx + 3.5, sy, sz + 0.5, 90f, 10f);

                tickUntil(engine, both, () -> alice.tabList.containsKey(bob.profile.uuid())
                        && bob.tabList.containsKey(alice.profile.uuid()) && alice.tabList.containsKey(alice.profile.uuid()),
                        "tab lists");
                tickUntil(engine, both, () -> alice.entities.containsKey(bob.entityId) && bob.entities.containsKey(alice.entityId),
                        "player entities spawned");
                var seen = alice.entities.get(bob.entityId);
                assertEquals(EntityType.PLAYER, seen.type());
                assertEquals(bob.profile.uuid(), seen.uuid());
                assertFalse(alice.entities.containsKey(alice.entityId), "a client is never sent its own entity");

                int syncs = alice.entitySyncs.get();
                bob.moveTo(sx + 5.5, sy, sz + 2.5, 180f, 0f);
                tickUntil(engine, both, () -> alice.entitySyncs.get() > syncs, "bob's move reaches alice");

                // Bob digs the block under alice: alice receives the block update (air).
                int by = sy - 1;
                assertTrue(Blocks.isMineable(engine.world.blocks.getShared(sx + 1, by, sz)));
                bob.startDigging(sx + 1, by, sz, 7);
                tickUntil(engine, both, () -> Integer.valueOf(0).equals(alice.blockUpdates.get(VanillaClient.blockKey(sx + 1, by, sz))),
                        "block update reaches alice");

                bob.close();
                tickUntil(engine, both, () -> !alice.entities.containsKey(bob.entityId)
                        && !alice.tabList.containsKey(bob.profile.uuid()), "bob gone for alice");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
