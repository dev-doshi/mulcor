package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.region.Entities;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * End to end over a real TCP socket on the native transport (kqueue on macOS, epoll on Linux). A plain Java
 * client sends Minestom-encoded vanilla packets, the Netty event loop decodes them into the region ingress
 * rings, and region workers apply them in their tick.
 */
class NetServerIntegrationTest {
    private static EngineConfig cfg() {
        return EngineConfig.builder().world(16, 16).cellChunks(2).workers(4).initialRegions(16).maxEntities(4096)
                .ingressCapacity(256).ingressBudget(64).rebalanceInterval(0).sampleCapacity(1024).build();
    }

    @Test
    void digOverTcpBreaksBlockAndDeliversItem() throws Exception {
        try (Engine engine = new Engine(cfg())) {
            int bot = engine.spawnBot(40, 40, Entities.IDLE);
            engine.setAiEnabled(false);
            int y = engine.world.surfaceY - 1;
            assertEquals(Blocks.DIRT, engine.world.blocks.get(41, y, 40));
            long dirtBefore = engine.world.players.total(bot, Blocks.DIRT);
            try (var server = new NetServer(0, 2, engine::submitInput, () -> bot, 1000, 100_000, 1_000_000);
                    Socket client = new Socket("127.0.0.1", server.port())) {
                System.out.println("transport: " + server.transport());
                OutputStream out = client.getOutputStream();
                out.write(Packets.position(40.5, engine.world.surfaceY, 40.5));
                out.write(Packets.dig(41, y, 40));
                out.flush();
                for (int i = 0; i < 2000 && engine.world.blocks.get(41, y, 40) != Blocks.AIR; i++) {
                    engine.tick();
                    Thread.sleep(1);
                }
            }
            engine.run(3);
            assertEquals(Blocks.AIR, engine.world.blocks.get(41, y, 40), "dig packet must break the block");
            assertEquals(dirtBefore + 1, engine.world.players.total(bot, Blocks.DIRT), "and the item goes to the player");
        }
    }

    /**
     * 60,000 packets from one socket into a 256-slot ingress ring, drained at only 64 records per region per tick.
     * The handler must throttle reading instead of dropping, so every packet eventually reaches the engine.
     */
    @Test
    void floodIsThrottledNotDropped() throws Exception {
        try (Engine engine = new Engine(cfg())) {
            int bot = engine.spawnBot(100, 100, Entities.IDLE);
            engine.setAiEnabled(false);
            int packets = 60_000;
            var connections = new AtomicInteger();
            try (var server = new NetServer(0, 2, engine::submitInput, () -> { connections.incrementAndGet(); return bot; },
                    100_000, 10_000_000, 10_000_000);
                    Socket client = new Socket("127.0.0.1", server.port())) {
                var buf = new ByteArrayOutputStream();
                for (int i = 0; i < packets; i++) buf.writeBytes(Packets.position(100.5 + (i % 7), engine.world.surfaceY, 100.5));
                Thread writer = Thread.ofPlatform().start(() -> {
                    try {
                        client.getOutputStream().write(buf.toByteArray());
                        client.getOutputStream().flush();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (engine.stats().inputs < packets && System.nanoTime() < deadline) engine.tick();
                writer.join();
            }
            assertEquals(1, connections.get());
            assertEquals(packets, engine.stats().inputs, "every packet delivered exactly once despite backpressure");
        }
    }
}
