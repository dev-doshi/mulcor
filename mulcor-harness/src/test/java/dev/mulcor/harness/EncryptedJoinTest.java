package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.net.Authenticator;
import dev.mulcor.net.Crypto;
import dev.mulcor.net.NetServer;
import dev.mulcor.net.ServerContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Protocol encryption and online-mode login over real sockets, against the headless {@link VanillaClient}, which
 * runs the client side of the handshake with the JDK's RSA and AES/CFB8 and parses every packet with Minestom's
 * serializers. A tampered byte anywhere in the AES stream would corrupt every later frame, so a full join,
 * configuration, chunk stream and dig acknowledgement is a strong end-to-end check of both cipher directions.
 */
class EncryptedJoinTest {
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Crypto CRYPTO = Crypto.generate();

    private static EngineConfig cfg() {
        return EngineConfig.builder().world(8, 8).cellChunks(2).workers(2).initialRegions(4).maxRegions(16)
                .maxEntities(1024).rebalanceInterval(0).sampleCapacity(1024).build();
    }

    private static void tickUntil(Engine engine, BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            engine.tick();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static Future<VanillaClient> joinAsync(ExecutorService pool, int port, String name) {
        return pool.submit(() -> VanillaClient.join("127.0.0.1", port, name, WAIT));
    }

    @Test
    void encryptedOfflineJoinStreamsChunksAndDigs() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Engine engine = new Engine(cfg())) {
            engine.setAiEnabled(false);
            ServerContext ctx = ServerContext.of(engine).withViewDistance(2).withEncryption(CRYPTO);
            try (NetServer net = NetServer.vanilla(0, 2, ctx)) {
                var f = joinAsync(pool, net.port(), "alice");
                tickUntil(engine, f::isDone, "join");
                try (VanillaClient c = f.get()) {
                    assertTrue(c.encrypted, "both directions encrypted");
                    assertFalse(c.authenticationRequested, "offline mode does not ask the client to authenticate");
                    assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:alice".getBytes(StandardCharsets.UTF_8)), c.profile.uuid());
                    assertTrue(c.registryPackets.get() > 0, "configuration went through the cipher");
                    tickUntil(engine, () -> c.chunks.size() >= 9, "chunks streamed through the cipher");

                    int x = ctx.spawnX(), z = ctx.spawnZ(), y = engine.surfaceAt(x, z) - 1;
                    assertTrue(Blocks.isMineable(engine.world.blocks.getShared(x, y, z)));
                    c.startDigging(x, y, z, 7);
                    tickUntil(engine, () -> engine.world.blocks.getShared(x, y, z) == Blocks.AIR && c.lastAckedSequence == 7,
                            "encrypted dig applied and acknowledged");
                }
                tickUntil(engine, () -> engine.stats().leaves == 1, "leave");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlineModeTakesTheSessionServersProfile() throws Exception {
        var seen = new ConcurrentHashMap<String, String>();
        HttpServer session = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        session.createContext("/session/minecraft/hasJoined", ex -> {
            for (String kv : ex.getRequestURI().getRawQuery().split("&")) {
                String[] p = kv.split("=", 2);
                seen.put(p[0], java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            byte[] body = ("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":"
                    + "[{\"name\":\"textures\",\"value\":\"dGV4dHVyZXM=\",\"signature\":\"c2lnbmF0dXJl\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        session.start();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Engine engine = new Engine(cfg())) {
            var auth = Authenticator.http(URI.create("http://127.0.0.1:" + session.getAddress().getPort() + "/session/minecraft/hasJoined"));
            ServerContext ctx = ServerContext.of(engine).withViewDistance(2).withOnlineMode(CRYPTO, auth);
            try (NetServer net = NetServer.vanilla(0, 2, ctx)) {
                var f = joinAsync(pool, net.port(), "notch");
                tickUntil(engine, f::isDone, "online join");
                try (VanillaClient c = f.get()) {
                    assertTrue(c.authenticationRequested, "online mode asks the client to authenticate");
                    assertEquals("notch", seen.get("username"), "server asks about the name the client logged in with");
                    assertEquals(c.serverHash, seen.get("serverId"), "server and client agree on the session hash");
                    // Vanilla uses the session server's profile, not what the client claimed.
                    assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), c.profile.uuid());
                    assertEquals("Notch", c.profile.name());
                    assertEquals("textures", c.profile.properties().getFirst().name());
                    tickUntil(engine, () -> !c.chunks.isEmpty(), "chunks");
                }
            }
        } finally {
            pool.shutdownNow();
            session.stop(0);
        }
    }

    @Test
    void onlineModeRefusesUnverifiedAndReportsSessionOutage() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Engine engine = new Engine(cfg())) {
            Authenticator unverified = (name, hash) -> CompletableFuture.completedFuture(null);
            Authenticator down = (name, hash) -> CompletableFuture.failedFuture(new java.io.IOException("unreachable"));
            for (var c : new Object[][] {{unverified, "Failed to verify username!"},
                    {down, "Authentication servers are down"}}) {
                ServerContext ctx = ServerContext.of(engine).withOnlineMode(CRYPTO, (Authenticator) c[0]);
                try (NetServer net = NetServer.vanilla(0, 1, ctx)) {
                    var f = joinAsync(pool, net.port(), "mallory");
                    tickUntil(engine, f::isDone, "refusal");
                    var e = assertThrows(java.util.concurrent.ExecutionException.class, f::get);
                    assertTrue(String.valueOf(e.getCause().getMessage()).contains((String) c[1]), e.getCause().getMessage());
                }
            }
            assertEquals(0, engine.stats().joins, "nobody was spawned");
        } finally {
            pool.shutdownNow();
        }
    }
}
