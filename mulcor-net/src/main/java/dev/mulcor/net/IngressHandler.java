package dev.mulcor.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.TimeUnit;

/**
 * Netty handler that feeds one connection's packets into the engine. It runs on the connection's event loop and
 * never waits for a region thread.
 *
 * <ul>
 *   <li>Bytes are decoded straight from the inbound buffer when it holds whole frames. Only a trailing partial
 *       frame is copied into a per-connection pooled direct cumulation buffer.</li>
 *   <li>Every frame costs one token from the connection's bucket and one from the shared global bucket.</li>
 *   <li>When a bucket runs dry or a region ingress ring is full, the handler switches off {@code autoRead} and
 *       schedules a resume. Unread bytes stay in the cumulation buffer, so nothing is dropped, and the kernel
 *       socket buffer pushes back on the client (TCP backpressure).</li>
 * </ul>
 */
public final class IngressHandler extends ChannelInboundHandlerAdapter {
    private final IngressDecoder decoder;
    private final TokenBucket connectionBucket;
    private final TokenBucket globalBucket;
    private ByteBuf cumulation;
    private ChannelHandlerContext ctx;
    private boolean paused;
    private long pauses, malformed;
    private final Runnable resume = this::resume;

    public IngressHandler(IngressDecoder decoder, TokenBucket connectionBucket, TokenBucket globalBucket) {
        this.decoder = decoder;
        this.connectionBucket = connectionBucket;
        this.globalBucket = globalBucket;
    }

    public IngressDecoder decoder() { return decoder; }
    public long pauses() { return pauses; }
    public long malformed() { return malformed; }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.cumulation = ctx.alloc().directBuffer(64 * 1024);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            if (cumulation.isReadable() || paused) {
                cumulation.discardReadBytes();
                cumulation.writeBytes(in); // grows the pooled buffer only if a burst exceeds its capacity
                process(cumulation);
            } else {
                process(in);
                if (in.isReadable()) {
                    cumulation.clear();
                    cumulation.writeBytes(in); // only the trailing partial frame, or what backpressure left unread
                }
            }
        } finally {
            in.release();
        }
    }

    private void process(ByteBuf buf) {
        while (buf.isReadable()) {
            if (!connectionBucket.tryAcquire(1)) {
                pause(connectionBucket.microsUntil(1));
                return;
            }
            if (!globalBucket.tryAcquire(1)) {
                connectionBucket.refund(1);
                pause(globalBucket.microsUntil(1));
                return;
            }
            int before = buf.readerIndex();
            // Decode exactly one frame per token: limit the decoder to the next frame by bounding with a peek.
            long len = VarInts.peek(buf, before, buf.writerIndex());
            if (len < 0) {
                refundBoth();
                if (len == VarInts.MALFORMED) protocolError();
                return;
            }
            int end = before + (int) (len >>> 32) + (int) len;
            if ((int) len > IngressDecoder.MAX_FRAME) {
                refundBoth();
                protocolError();
                return;
            }
            if (end > buf.writerIndex()) {
                refundBoth();
                return; // need more bytes
            }
            int save = buf.writerIndex();
            buf.writerIndex(end);
            IngressDecoder.Result r = decoder.decode(buf);
            buf.writerIndex(save);
            if (r == IngressDecoder.Result.BACKPRESSURE) {
                refundBoth();
                pause(200); // region ingress ring full: retry shortly
                return;
            }
            if (r == IngressDecoder.Result.MALFORMED) {
                refundBoth();
                protocolError();
                return;
            }
        }
    }

    private void refundBoth() {
        connectionBucket.refund(1);
        globalBucket.refund(1);
    }

    private void protocolError() {
        malformed++;
        ctx.close();
    }

    private void pause(long micros) {
        if (!paused) {
            paused = true;
            pauses++;
            ctx.channel().config().setAutoRead(false);
        }
        ctx.executor().schedule(resume, Math.max(50, micros), TimeUnit.MICROSECONDS);
    }

    private void resume() {
        if (cumulation == null) return;
        paused = false;
        process(cumulation);
        if (!paused) ctx.channel().config().setAutoRead(true);
    }
}
