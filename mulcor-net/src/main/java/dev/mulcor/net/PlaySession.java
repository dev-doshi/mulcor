package dev.mulcor.net;

import dev.mulcor.core.region.Input;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.NativeMemory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.FastThreadLocal;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The outbound half of a player's play session. Every 50 ms on the connection's event loop it:
 * <ul>
 *   <li>re-centres the client's chunk view when the player crosses a chunk border (from the positions the
 *       {@link IngressDecoder} has seen),</li>
 *   <li>streams the chunks in view that the client does not have yet, nearest first, at most
 *       {@code chunksPerTick} per batch and only while the socket is writable,</li>
 *   <li>acknowledges the client's block actions and sends a keep-alive every 10 s.</li>
 * </ul>
 * Chunks are encoded straight from off-heap block storage by the event loop's {@link PlayWriter}, so steady-state
 * streaming allocates nothing on the heap. Inbound bytes pass through to the {@link IngressHandler} behind it.
 * When the connection closes, the player's entity is removed with a LEAVE input.
 */
public final class PlaySession extends ChannelInboundHandlerAdapter {
    private static final FastThreadLocal<PlayWriter> COMPRESSED = new FastThreadLocal<>();
    private static final FastThreadLocal<PlayWriter> PLAIN = new FastThreadLocal<>();
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final ServerContext server;
    private final int entity;
    private final IngressDecoder decoder;
    private final int threshold;
    private final String name;
    private final BlockStorage blocks;
    private final int chunksX, chunksZ, radius;
    /** One bit per world chunk: the client currently holds it. */
    private final long[] sent;
    private final MemorySegment scratch = NativeMemory.auto().allocate(Input.BYTES);
    private int centerX, centerZ, ring;
    private int lastAcked = -1;
    private long lastKeepAlive;
    private long chunksSent;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> task;
    private boolean closed;

    PlaySession(ServerContext server, int entity, IngressDecoder decoder, int threshold, double spawnX, double spawnZ, String name) {
        this.server = server;
        this.entity = entity;
        this.decoder = decoder;
        this.threshold = threshold;
        this.name = name;
        this.blocks = server.engine().world.blocks;
        this.chunksX = blocks.chunksX();
        this.chunksZ = blocks.chunksZ();
        this.radius = server.viewDistance();
        this.sent = new long[(chunksX * chunksZ + 63) >>> 6];
        this.centerX = Math.floorDiv((int) Math.floor(spawnX), 16);
        this.centerZ = Math.floorDiv((int) Math.floor(spawnZ), 16);
    }

    ChannelHandlerContext ctx() { return ctx; }
    public int entity() { return entity; }
    public String name() { return name; }
    public long chunksSent() { return chunksSent; }

    private PlayWriter writer() {
        FastThreadLocal<PlayWriter> local = threshold > 0 ? COMPRESSED : PLAIN;
        PlayWriter w = local.get();
        if (w == null) {
            w = new PlayWriter(threshold);
            local.set(w);
        }
        return w;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        server.online().incrementAndGet();
        lastKeepAlive = System.nanoTime();
        ByteBuf out = ctx.alloc().directBuffer(64 * 1024);
        writer().viewCenter(centerX, centerZ, out);
        stream(out);
        ctx.writeAndFlush(out);
        task = ctx.executor().scheduleAtFixedRate(this::update, 50, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        close();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel(false);
        server.online().decrementAndGet();
        leave();
    }

    private void leave() {
        if (!server.engine().requestLeave(scratch, entity)) {
            ctx.executor().schedule(this::leave, 5, TimeUnit.MILLISECONDS); // ingress ring full: retry
        }
    }

    private void update() {
        if (closed || !ctx.channel().isActive()) return;
        PlayWriter w = writer();
        ByteBuf out = ctx.alloc().directBuffer(16 * 1024);
        if (decoder.hasPosition()) {
            int cx = Math.floorDiv(decoder.lastX1000(), 16_000), cz = Math.floorDiv(decoder.lastZ1000(), 16_000);
            if (cx != centerX || cz != centerZ) recenter(cx, cz, w, out);
        }
        if (ctx.channel().isWritable()) stream(out);
        int seq = decoder.lastSequence();
        if (seq > lastAcked) {
            w.acknowledgeBlockChange(seq, out);
            lastAcked = seq;
        }
        long now = System.nanoTime();
        if (now - lastKeepAlive >= KEEP_ALIVE_NANOS) {
            w.keepAlive(now, out);
            lastKeepAlive = now;
        }
        if (out.isReadable()) {
            ctx.writeAndFlush(out);
        } else {
            out.release();
        }
    }

    /** The client drops chunks outside its new view square itself; forget that we sent them, then refill. */
    private void recenter(int cx, int cz, PlayWriter w, ByteBuf out) {
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (Math.abs(x - cx) > radius || Math.abs(z - cz) > radius) clear(x, z);
            }
        }
        centerX = cx;
        centerZ = cz;
        ring = 0;
        w.viewCenter(cx, cz, out);
    }

    /** Send up to {@code chunksPerTick} missing chunks in rings of growing distance around the centre. */
    private void stream(ByteBuf out) {
        PlayWriter w = writer();
        int budget = server.chunksPerTick(), n = 0;
        for (int d = ring; d <= radius; d++) {
            for (int i = -d; i <= d; i++) {
                n = send(centerX + i, centerZ - d, n, w, out);
                if (d > 0) n = send(centerX + i, centerZ + d, n, w, out);
                if (n >= budget) { finish(d, n, w, out); return; }
            }
            for (int j = -d + 1; j <= d - 1; j++) {
                n = send(centerX - d, centerZ + j, n, w, out);
                n = send(centerX + d, centerZ + j, n, w, out);
                if (n >= budget) { finish(d, n, w, out); return; }
            }
        }
        finish(radius + 1, n, w, out);
    }

    private int send(int x, int z, int n, PlayWriter w, ByteBuf out) {
        if (x < 0 || z < 0 || x >= chunksX || z >= chunksZ) return n;
        int bit = z * chunksX + x;
        if ((sent[bit >>> 6] & (1L << bit)) != 0) return n;
        if (n == 0) w.batchStart(out);
        w.chunk(blocks, x, z, out);
        sent[bit >>> 6] |= 1L << bit;
        chunksSent++;
        return n + 1;
    }

    private void finish(int nextRing, int n, PlayWriter w, ByteBuf out) {
        ring = nextRing;
        if (n > 0) w.batchFinished(n, out);
    }

    private void clear(int x, int z) {
        if (x < 0 || z < 0 || x >= chunksX || z >= chunksZ) return;
        int bit = z * chunksX + x;
        sent[bit >>> 6] &= ~(1L << bit);
    }
}
