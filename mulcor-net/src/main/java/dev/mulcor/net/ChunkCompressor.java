package dev.mulcor.net;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib compression between Netty direct buffers, with no copies to the heap. The source and destination are
 * handed to zlib as the pooled buffers' cached internal NIO views ({@code internalNioBuffer}), so steady-state
 * calls allocate nothing. One instance per thread: the Deflater/Inflater are reused via {@code reset()}.
 */
public final class ChunkCompressor implements AutoCloseable {
    private final Deflater deflater;
    private final Inflater inflater = new Inflater();

    public ChunkCompressor(int level) {
        deflater = new Deflater(level);
    }

    /** Compress the readable bytes of {@code src} (not consumed) and append them to {@code dst}. Returns bytes written. */
    public int compress(ByteBuf src, ByteBuf dst) {
        deflater.reset();
        ByteBuffer in = src.internalNioBuffer(src.readerIndex(), src.readableBytes());
        deflater.setInput(in);
        deflater.finish();
        int written = 0;
        while (!deflater.finished()) {
            dst.ensureWritable(Math.max(256, src.readableBytes() / 4 + 64));
            ByteBuffer out = dst.internalNioBuffer(dst.writerIndex(), dst.writableBytes());
            int n = deflater.deflate(out);
            dst.writerIndex(dst.writerIndex() + n);
            written += n;
        }
        return written;
    }

    /** Inflate {@code src}'s readable bytes, which must decompress to exactly {@code length} bytes, into {@code dst}. */
    public void decompress(ByteBuf src, ByteBuf dst, int length) throws DataFormatException {
        inflater.reset();
        inflater.setInput(src.internalNioBuffer(src.readerIndex(), src.readableBytes()));
        dst.ensureWritable(length);
        ByteBuffer out = dst.internalNioBuffer(dst.writerIndex(), length);
        int total = 0;
        while (total < length) {
            int n = inflater.inflate(out);
            if (n == 0 && (inflater.finished() || inflater.needsInput())) break;
            total += n;
        }
        if (total != length) throw new DataFormatException("expected " + length + " bytes, got " + total);
        dst.writerIndex(dst.writerIndex() + length);
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }
}
