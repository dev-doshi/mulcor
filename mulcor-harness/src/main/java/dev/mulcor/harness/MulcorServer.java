package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.WorkerTopology;
import dev.mulcor.core.region.Entities;
import dev.mulcor.net.Authenticator;
import dev.mulcor.net.Crypto;
import dev.mulcor.net.NetServer;
import dev.mulcor.net.ServerContext;
import dev.mulcor.net.Vanilla;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * A Mulcor server that real Minecraft clients can join (creative, flat world; offline mode by default).
 *
 * <pre>./gradlew :mulcor-harness:runServer --args='--port 25565 --chunks 32 --bots 200'</pre>
 *
 * Options: {@code --port} (25565), {@code --chunks} world edge in chunks (32), {@code --bots} simulated bots (0),
 * {@code --tps} (20), {@code --view} view distance (8), {@code --io} Netty threads (2), {@code --workers},
 * {@code --online true} (encryption + Mojang session authentication, like vanilla {@code online-mode=true}),
 * {@code --encrypt true} (offline mode but with protocol encryption).
 */
public final class MulcorServer {
    private MulcorServer() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        int port = Integer.parseInt(opt.getOrDefault("port", "25565"));
        int chunks = Integer.parseInt(opt.getOrDefault("chunks", "32"));
        int bots = Integer.parseInt(opt.getOrDefault("bots", "0"));
        int tps = Integer.parseInt(opt.getOrDefault("tps", "20"));
        int view = Integer.parseInt(opt.getOrDefault("view", "8"));
        int io = Integer.parseInt(opt.getOrDefault("io", "2"));
        int workers = Integer.parseInt(opt.getOrDefault("workers", String.valueOf(WorkerTopology.workers())));
        boolean onlineMode = Boolean.parseBoolean(opt.getOrDefault("online", "false"));
        boolean encrypt = onlineMode || Boolean.parseBoolean(opt.getOrDefault("encrypt", "false"));

        EngineConfig cfg = EngineConfig.builder().world(chunks, chunks).height(0, 4).cellChunks(4).workers(workers)
                .maxEntities(Math.max(4096, bots * 2 + 1024)).build();
        try (Engine engine = new Engine(cfg)) {
            var rnd = new SplittableRandom(cfg.seed());
            int[] roles = {Entities.MINER, Entities.NAVIGATOR, Entities.CHESTER, Entities.BOMBER};
            for (int i = 0; i < bots; i++) {
                engine.spawnBot(rnd.nextInt(engine.world.sizeX()), rnd.nextInt(engine.world.sizeZ()), roles[i % roles.length]);
            }
            ServerContext ctx = ServerContext.of(engine).withViewDistance(view);
            if (onlineMode) ctx = ctx.withOnlineMode(Crypto.generate(), Authenticator.mojang());
            else if (encrypt) ctx = ctx.withEncryption(Crypto.generate());
            try (NetServer net = NetServer.vanilla(port, io, ctx)) {
                System.out.printf("Mulcor listening on port %d (%s transport): Minecraft %s, protocol %d, %s%n",
                        net.port(), net.transport(), Vanilla.VERSION, Vanilla.PROTOCOL,
                        onlineMode ? "online mode" : encrypt ? "offline mode, encrypted" : "offline mode");
                System.out.printf("world %dx%d chunks, %d region workers, %d bots, %d TPS%n", chunks, chunks, workers, bots, tps);
                drive(engine, ctx, tps);
            }
        }
    }

    /** Fixed-rate tick loop on this thread; prints a status line every 5 seconds. */
    private static void drive(Engine engine, ServerContext ctx, int tps) {
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            try {
                main.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        long period = 1_000_000_000L / tps;
        long next = System.nanoTime(), lastReport = next, busy = 0, ticks = 0, worst = 0;
        while (running.get()) {
            long dt = engine.tick();
            busy += dt;
            worst = Math.max(worst, dt);
            ticks++;
            long now = System.nanoTime();
            if (now - lastReport >= 5_000_000_000L) {
                var s = engine.stats();
                System.out.printf("players %d | entities %d | regions %d | tick mean %.0f µs, max %.0f µs | joins %d, leaves %d%n",
                        ctx.online().get(), s.entities, s.activeRegions, busy / 1e3 / ticks, worst / 1e3, s.joins, s.leaves);
                lastReport = now;
                busy = ticks = worst = 0;
            }
            next += period;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else if (sleep < -period * 20) {
                next = System.nanoTime(); // far behind (paused, debugger): don't try to catch up
            }
        }
    }
}
