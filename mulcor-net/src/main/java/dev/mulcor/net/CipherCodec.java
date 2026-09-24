package dev.mulcor.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.FastThreadLocal;
import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

/**
 * AES-128/CFB8 stream encryption for one connection, installed at the head of the pipeline (closest to the
 * socket) once the client has sent Encryption Response. Every byte in and out passes through it, so it is on the
 * hot path and must not allocate: it transforms each buffer <b>in place</b> and passes the same buffer on.
 *
 * <ul>
 *   <li>The JDK's AES avoids temporary arrays only on its {@code byte[]} entry point, and only when input and
 *       output do not overlap: its {@code ByteBuffer} path stages direct memory through a fresh array per call,
 *       and {@code CipherCore} copies the input whenever the two ranges overlap, even at equal offsets. So each
 *       64 KiB slice is copied into a per-event-loop input array, transformed into a second per-event-loop output
 *       array, and copied back into the buffer. The extra memcpy is negligible next to CFB8's one AES block per
 *       byte.</li>
 *   <li>Allocating a fresh pooled buffer per read or write is not free even on an event loop (Netty's pool
 *       bookkeeping allocates now and then), which is why nothing here allocates a buffer.</li>
 * </ul>
 *
 * <p>Ownership: an outbound buffer is encrypted where it lies, so a buffer written to this channel must not also
 * be written to another channel. Mulcor encodes every packet per session, so none is shared. Read-only buffers
 * are copied first.
 */
public final class CipherCodec extends ChannelDuplexHandler {
    private static final int SLICE = 64 * 1024;
    /** Per event-loop staging: [0] = cipher input, [1] = cipher output (distinct, so no overlap copy). */
    private static final FastThreadLocal<byte[][]> SCRATCH = new FastThreadLocal<>() {
        @Override
        protected byte[][] initialValue() {
            return new byte[][] {new byte[SLICE], new byte[SLICE]};
        }
    };

    private final Cipher decrypt;
    private final Cipher encrypt;

    public CipherCodec(Cipher decrypt, Cipher encrypt) {
        this.decrypt = decrypt;
        this.encrypt = encrypt;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf in) {
            try {
                transform(decrypt, in, in.readerIndex(), in.readableBytes());
            } catch (RuntimeException e) {
                in.release();
                throw e;
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf out) {
            ByteBuf target = out;
            if (out.isReadOnly()) { // cold: never produced by Mulcor's own writers
                target = ctx.alloc().buffer(out.readableBytes()).writeBytes(out);
                out.release();
            }
            try {
                transform(encrypt, target, target.readerIndex(), target.readableBytes());
            } catch (RuntimeException e) {
                target.release();
                promise.setFailure(e);
                return;
            }
            ctx.write(target, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    /**
     * Decrypt {@code buf}'s readable bytes in place, for ciphertext that reached the login handler before this
     * codec was installed.
     */
    public void decryptInPlace(ByteBuf buf) {
        transform(decrypt, buf, buf.readerIndex(), buf.readableBytes());
    }

    /** Test hook: encrypt {@code buf}'s readable bytes in place with this connection's outbound cipher. */
    void encryptInPlace(ByteBuf buf) {
        transform(encrypt, buf, buf.readerIndex(), buf.readableBytes());
    }

    /** Transform {@code len} bytes of {@code buf} starting at {@code index}, in place, without moving indices. */
    static void transform(Cipher cipher, ByteBuf buf, int index, int len) {
        if (len == 0) return;
        byte[][] scratch = SCRATCH.get();
        byte[] src = scratch[0], dst = scratch[1];
        for (int done = 0; done < len; ) {
            int k = Math.min(SLICE, len - done);
            buf.getBytes(index + done, src, 0, k);
            update(cipher, src, dst, k);
            buf.setBytes(index + done, dst, 0, k);
            done += k;
        }
    }

    private static void update(Cipher c, byte[] src, byte[] dst, int len) {
        try {
            int written = c.update(src, 0, len, dst, 0);
            if (written != len) throw new IllegalStateException("CFB8 produced " + written + " of " + len + " bytes");
        } catch (ShortBufferException e) {
            throw new IllegalStateException(e);
        }
    }
}
