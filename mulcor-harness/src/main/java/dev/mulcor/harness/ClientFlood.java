package dev.mulcor.harness;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Engine;
import dev.mulcor.core.Rng;
import dev.mulcor.core.region.Input;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Producer threads that behave like thousands of connected clients, running concurrently with the tick.
 *
 * <ul>
 *   <li>{@code paced}: each client sends {@code packetsPerTick} packets per epoch. Producers watch the engine's
 *       epoch and burst when it advances, like Netty event loops receiving a steady packet stream.</li>
 *   <li>{@code unpaced}: producers fire as fast as they can. This is a flood that exercises backpressure and the
 *       anti-duplication paths.</li>
 * </ul>
 * The packet mix is movement, digs, placements and chest clicks. Clicks and digs concentrate on a few hotspots
 * (shared chests, blocks at region corners) so different regions collide on the same state at the same moment.
 */
public final class ClientFlood implements AutoCloseable {
    private final Thread[] threads;
    private final HeadlessVirtualClientProvider[] providers;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public ClientFlood(Engine engine, int clients, int producers, int packetsPerTick, boolean paced, long seed) {
        this.threads = new Thread[producers];
        this.providers = new HeadlessVirtualClientProvider[producers];
        int chests = engine.world.chestCount();
        int size = engine.world.sizeX();
        int surface = engine.world.surfaceY;
        for (int p = 0; p < producers; p++) {
            var provider = new HeadlessVirtualClientProvider(engine);
            providers[p] = provider;
            final int first = p * clients / producers, last = (p + 1) * clients / producers;
            final long pseed = seed * 31 + p;
            threads[p] = Thread.ofPlatform().name("mulcor-client-" + p).start(() -> {
                try {
                    long lastEpoch = -1;
                    long n = 0;
                    while (!stop.get()) {
                        if (paced) {
                            long e = engine.epoch();
                            if (e == lastEpoch) {
                                Thread.onSpinWait();
                                continue;
                            }
                            lastEpoch = e;
                        }
                        for (int rep = 0; rep < packetsPerTick; rep++) {
                            for (int c = first; c < last && !stop.get(); c++) {
                                long h = Rng.mix(pseed, c, n++);
                                sendOne(provider, c, h, chests, size, surface);
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
    }

    private static void sendOne(HeadlessVirtualClientProvider out, int client, long h, int chests, int size, int surface) {
        int kind = Rng.bounded(h, 100);
        if (kind < 55) {
            out.send(Input.MOVE, client, 0, 0, 0, Rng.pick(h, 8, 401) - 200, Rng.pick(h, 20, 401) - 200, 0);
        } else if (kind < 75) {
            // hot blocks where 4 cells meet: many regions dig the same block
            int cx = 64 * (1 + Rng.pick(h, 8, size / 64 - 1)), cz = 64 * (1 + Rng.pick(h, 16, size / 64 - 1));
            out.send(Input.DIG, client, cx - (int) (h >>> 40 & 1), surface - 1, cz - (int) (h >>> 41 & 1), 0, 0, 0);
        } else if (kind < 85) {
            int cx = 64 * (1 + Rng.pick(h, 8, size / 64 - 1)), cz = 64 * (1 + Rng.pick(h, 16, size / 64 - 1));
            out.send(Input.PLACE, client, cx - (int) (h >>> 40 & 1), surface - 1, cz - (int) (h >>> 41 & 1), Blocks.DIRT, 0, 0);
        } else {
            int chest = Rng.pick(h, 8, Math.min(4, chests)); // four hot chests shared by everyone
            int count = Rng.pick(h, 30, 2) == 0 ? 1 + Rng.pick(h, 32, 16) : -(1 + Rng.pick(h, 36, 16));
            out.send(Input.CHEST, client, 0, 0, 0, chest, Rng.pick(h, 16, 9), count);
        }
    }

    public long accepted() {
        long s = 0;
        for (var p : providers) s += p.accepted();
        return s;
    }

    public long rejected() {
        long s = 0;
        for (var p : providers) s += p.rejected();
        return s;
    }

    @Override
    public void close() {
        stop.set(true);
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Throwable f = failure.get();
        if (f != null) throw new IllegalStateException("client producer failed", f);
    }
}
