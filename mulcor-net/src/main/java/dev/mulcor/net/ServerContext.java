package dev.mulcor.net;

import dev.mulcor.core.Engine;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a vanilla connection needs from the server: the engine, where players spawn, protocol settings and
 * backpressure limits. Shared by all connections; every member is thread-safe.
 */
public record ServerContext(
        Engine engine,
        InputSink sink,
        int spawnX,
        int spawnZ,
        int viewDistance,
        int compressionThreshold,
        int maxPlayers,
        int chunksPerTick,
        int perConnectionBurst,
        long perConnectionRate,
        TokenBucket globalBucket,
        AtomicInteger online) {

    /** Defaults for a local server: spawn at the world centre, view distance 8, compression at 256 bytes. */
    public static ServerContext of(Engine engine) {
        return new ServerContext(engine, engine::submitInput, engine.world.sizeX() / 2, engine.world.sizeZ() / 2, 8,
                256, 1000, 64, 2000, 4000, new TokenBucket(TokenBucket.MAX_CAPACITY, 4_000_000), new AtomicInteger());
    }

    public ServerContext withViewDistance(int v) {
        return new ServerContext(engine, sink, spawnX, spawnZ, v, compressionThreshold, maxPlayers, chunksPerTick,
                perConnectionBurst, perConnectionRate, globalBucket, online);
    }

    public ServerContext withCompressionThreshold(int v) {
        return new ServerContext(engine, sink, spawnX, spawnZ, viewDistance, v, maxPlayers, chunksPerTick,
                perConnectionBurst, perConnectionRate, globalBucket, online);
    }
}
