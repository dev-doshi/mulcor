package dev.mulcor.net;

import dev.mulcor.core.Engine;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a vanilla connection needs from the server: the engine, where players spawn, protocol settings and
 * backpressure limits. Shared by all connections; every member is thread-safe.
 *
 * @param crypto the server's RSA key pair; {@code null} disables protocol encryption (vanilla offline mode)
 * @param auth session-server authentication; non-null means online mode, which requires {@code crypto}
 * @param players the online players' sessions by entity id
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
        AtomicInteger online,
        Crypto crypto,
        Authenticator auth,
        Players players) {

    public ServerContext {
        if (auth != null && crypto == null) throw new IllegalArgumentException("online mode requires encryption");
    }

    /**
     * Defaults for a local server: spawn at the world centre, view distance 8, compression at 256 bytes, offline
     * mode without encryption.
     */
    public static ServerContext of(Engine engine) {
        return new ServerContext(engine, engine::submitInput, engine.world.sizeX() / 2, engine.world.sizeZ() / 2, 8,
                256, 1000, 64, 2000, 4000, new TokenBucket(TokenBucket.MAX_CAPACITY, 4_000_000), new AtomicInteger(),
                null, null, new Players(engine.world.directory.capacity()));
    }

    public ServerContext withViewDistance(int v) {
        return new ServerContext(engine, sink, spawnX, spawnZ, v, compressionThreshold, maxPlayers, chunksPerTick,
                perConnectionBurst, perConnectionRate, globalBucket, online, crypto, auth, players);
    }

    public ServerContext withCompressionThreshold(int v) {
        return new ServerContext(engine, sink, spawnX, spawnZ, viewDistance, v, maxPlayers, chunksPerTick,
                perConnectionBurst, perConnectionRate, globalBucket, online, crypto, auth, players);
    }

    /** Offline mode with protocol encryption (the client skips session authentication). */
    public ServerContext withEncryption(Crypto c) {
        return new ServerContext(engine, sink, spawnX, spawnZ, viewDistance, compressionThreshold, maxPlayers,
                chunksPerTick, perConnectionBurst, perConnectionRate, globalBucket, online, c, null, players);
    }

    /** Online mode: encryption plus session-server authentication, like vanilla's {@code online-mode=true}. */
    public ServerContext withOnlineMode(Crypto c, Authenticator a) {
        return new ServerContext(engine, sink, spawnX, spawnZ, viewDistance, compressionThreshold, maxPlayers,
                chunksPerTick, perConnectionBurst, perConnectionRate, globalBucket, online, c, a, players);
    }

    public boolean onlineMode() {
        return auth != null;
    }
}
