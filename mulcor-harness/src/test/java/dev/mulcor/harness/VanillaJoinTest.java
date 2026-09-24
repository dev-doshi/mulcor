package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Region;
import dev.mulcor.net.NetServer;
import dev.mulcor.net.ServerContext;
import dev.mulcor.net.Vanilla;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import org.junit.jupiter.api.Test;

/**
 * The full vanilla connection over real sockets: a headless client ({@link VanillaClient}) that parses every
 * server packet with Minestom's serializers logs in, receives chunks, moves and digs, and disconnects. The engine
 * ticks on the test thread, so entity tables are read only between ticks.
 */
class VanillaJoinTest {
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static EngineConfig cfg(int rebalance) {
        return EngineConfig.builder().world(8, 8).cellChunks(2).workers(4).initialRegions(4).maxRegions(16)
                .maxEntities(4096).rebalanceInterval(rebalance).sampleCapacity(1024).build();
    }

    private static void tickUntil(Engine engine, BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what + "; " + engine.stats());
            engine.tick();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Joins on a background thread (join blocks until the engine, ticked here, has spawned the player). */
    private static VanillaClient join(Engine engine, ExecutorService pool, int port, String name) throws Exception {
        Future<VanillaClient> f = pool.submit(() -> VanillaClient.join("127.0.0.1", port, name, WAIT));
        tickUntil(engine, f::isDone, name + " to join");
        return f.get();
    }

    private static int slotOf(Engine engine, int eid) {
        return engine.world.directory.slot(eid);
    }

    private static Region ownerRegion(Engine engine, int eid) {
        return engine.world.regions[engine.world.ownerOfEntity(eid)];
    }

    @Test
    void joinStreamChunksMoveDigAndLeave() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Engine engine = new Engine(cfg(0))) {
            engine.setAiEnabled(false);
            ServerContext ctx = ServerContext.of(engine).withViewDistance(3);
            try (NetServer net = NetServer.vanilla(0, 2, ctx);
                    VanillaClient c = join(engine, pool, net.port(), "alice")) {
                int eid = c.entityId;
                tickUntil(engine, () -> engine.world.ownerOfEntity(eid) >= 0, "entity registered");
                Region spawnRegion = ownerRegion(engine, eid);
                assertEquals(Entities.PLAYER, spawnRegion.table.type(slotOf(engine, eid)));
                assertEquals(ctx.spawnX() + 0.5, c.position.x());
                assertEquals(1, ctx.online().get());
                assertTrue(c.registryPackets.get() > 0 && c.tagPackets.get() == 1, "configuration sent registries and tags");

                // Every in-world chunk within the view radius of the spawn chunk arrives.
                int scx = ctx.spawnX() >> 4, scz = ctx.spawnZ() >> 4;
                Set<Long> expected = new HashSet<>();
                for (int x = scx - 3; x <= scx + 3; x++) for (int z = scz - 3; z <= scz + 3; z++) {
                    if (x >= 0 && z >= 0 && x < 8 && z < 8) expected.add(VanillaClient.chunkKey(x, z));
                }
                tickUntil(engine, () -> c.chunks.keySet().containsAll(expected), "initial chunks");
                assertEquals(expected, c.chunks.keySet(), "only in-world chunks, each once");

                // Walk to the far corner: the position and rotation land in the entity table, the entity changes
                // region, and the server re-centres the client's view and streams the new chunks.
                double tx = 10.5, tz = 12.5;
                int ty = engine.surfaceAt(10, 12);
                c.moveTo(tx, ty, tz, 135f, -20f);
                tickUntil(engine, () -> {
                    int o = engine.world.ownerOfEntity(eid);
                    if (o < 0) return false;
                    Region r = engine.world.regions[o];
                    int s = slotOf(engine, eid);
                    return r.table.x(s) == tx && r.table.z(s) == tz;
                }, "movement applied");
                Region now = ownerRegion(engine, eid);
                int s = slotOf(engine, eid);
                assertNotEquals(spawnRegion.id, now.id, "crossed into another region");
                assertEquals(135f, Float.intBitsToFloat(now.table.aux1(s)), "yaw");
                assertEquals(-20f, Float.intBitsToFloat(now.table.aux2(s)), "pitch");
                assertEquals(Entities.FLAG_ON_GROUND, now.table.flags(s));
                tickUntil(engine, () -> c.viewCenterX == 0 && c.viewCenterZ == 0
                        && c.chunks.containsKey(VanillaClient.chunkKey(0, 0)), "view re-centred and refilled");

                // Dig the block under our feet: the owning region breaks it, the item reaches us, the client's
                // prediction sequence is acknowledged.
                int by = ty - 1;
                int block = engine.world.blocks.getShared(10, by, 12);
                assertTrue(Blocks.isMineable(block), "standing on " + block);
                long before = engine.world.players.total(eid, block);
                c.startDigging(10, by, 12, 42);
                tickUntil(engine, () -> engine.world.blocks.getShared(10, by, 12) == Blocks.AIR && c.lastAckedSequence == 42, "dig");
                tickUntil(engine, () -> engine.world.players.total(eid, block) == before + 1, "item delivered");
            }
            tickUntil(engine, () -> engine.stats().leaves == 1, "leave processed");
            assertEquals(0, ctx.online().get());
            assertEquals(0, engine.stats().entities, "player entity removed");
            assertEquals(0, engine.stats().stateViolations);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void serverListPingReportsVersionAndPlayers() throws Exception {
        try (Engine engine = new Engine(cfg(0)); NetServer net = NetServer.vanilla(0, 1, ServerContext.of(engine))) {
            String json = VanillaClient.status("127.0.0.1", net.port());
            assertTrue(json.contains("\"protocol\":" + Vanilla.PROTOCOL), json);
            assertTrue(json.contains("\"online\":0"), json);
        }
    }

    @Test
    void wrongProtocolVersionIsRefusedWithAReason() throws Exception {
        try (Engine engine = new Engine(cfg(0)); NetServer net = NetServer.vanilla(0, 1, ServerContext.of(engine));
                Socket s = new Socket("127.0.0.1", net.port())) {
            for (var p : List.<Object[]>of(
                    new Object[] {ConnectionState.HANDSHAKE, new ClientHandshakePacket(47, "localhost", net.port(), ClientHandshakePacket.Intent.LOGIN)},
                    new Object[] {ConnectionState.LOGIN, new ClientLoginStartPacket("old", UUID.randomUUID())})) {
                NetworkBuffer buf = NetworkBuffer.resizableBuffer();
                PacketWriting.writeFramedPacket(buf, (ConnectionState) p[0], (ClientPacket) p[1], 0);
                byte[] bytes = new byte[(int) buf.writeIndex()];
                buf.copyTo(0, bytes, 0, bytes.length);
                s.getOutputStream().write(bytes);
            }
            var in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            int len = 0, shift = 0, b;
            do { b = in.readUnsignedByte(); len |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
            byte[] frame = new byte[len];
            in.readFully(frame);
            NetworkBuffer nb = NetworkBuffer.wrap(frame, 0, frame.length);
            var packet = PacketVanilla.SERVER_PACKET_PARSER.parse(ConnectionState.LOGIN, nb.read(NetworkBuffer.VAR_INT), nb);
            var kick = assertInstanceOf(LoginDisconnectPacket.class, packet);
            assertTrue(kick.kickMessage().toString().contains(String.valueOf(Vanilla.PROTOCOL)), kick.toString());
            assertEquals(-1, in.read(), "and the connection is closed");
        }
    }

    /**
     * 32 clients join at once while 400 AI bots run and regions split and merge. Each gets its own entity, all
     * of them wander across region borders, then all disconnect: every player entity is removed, nothing leaks.
     */
    @Test
    void manyConcurrentPlayersWithBotsAndRebalancing() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        int players = 32, bots = 400;
        try (Engine engine = new Engine(cfg(10))) {
            var rnd = new SplittableRandom(3);
            int[] roles = {Entities.MINER, Entities.NAVIGATOR, Entities.CHESTER, Entities.IDLE};
            for (int i = 0; i < bots; i++) engine.spawnBot(rnd.nextInt(128), rnd.nextInt(128), roles[i % 4]);
            ServerContext ctx = ServerContext.of(engine).withViewDistance(2);
            try (NetServer net = NetServer.vanilla(0, 2, ctx)) {
                List<Future<VanillaClient>> joins = new ArrayList<>();
                for (int i = 0; i < players; i++) {
                    String name = "p" + i;
                    joins.add(pool.submit(() -> VanillaClient.join("127.0.0.1", net.port(), name, WAIT)));
                }
                tickUntil(engine, () -> joins.stream().allMatch(Future::isDone), "all joins");
                List<VanillaClient> clients = new ArrayList<>();
                for (var f : joins) clients.add(f.get());
                Set<Integer> ids = new HashSet<>();
                for (var c : clients) ids.add(c.entityId);
                assertEquals(players, ids.size(), "distinct entities");
                assertEquals(players, ctx.online().get());
                try {
                    for (int step = 0; step < 40; step++) {
                        for (var c : clients) {
                            c.moveTo(1 + rnd.nextInt(126) + 0.5, 6, 1 + rnd.nextInt(126) + 0.5, rnd.nextInt(360), 0f);
                        }
                        for (int t = 0; t < 5; t++) engine.tick();
                    }
                    for (var c : clients) {
                        tickUntil(engine, () -> c.batches.get() > 0, c.name + " chunks");
                        assertEquals(null, c.failure, c.name);
                    }
                } finally {
                    for (var c : clients) c.close();
                }
                tickUntil(engine, () -> engine.stats().leaves == players, "all leaves");
            }
            engine.setAiEnabled(false); // bots stop walking, so no transfer is in flight once messages drain
            engine.run(3);
            tickUntil(engine, () -> engine.stats().pending == 0, "in-flight transfers to land");
            var st = engine.stats();
            System.out.println("concurrent players: " + st);
            assertEquals(players, st.joins);
            assertEquals(bots, st.entities, "only the bots remain");
            assertEquals(0, st.stateViolations);
            assertEquals(0, ctx.online().get());
        } finally {
            pool.shutdownNow();
        }
    }
}
