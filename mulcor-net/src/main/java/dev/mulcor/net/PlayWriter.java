package dev.mulcor.net;

import dev.mulcor.memory.BlockStorage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;

/**
 * Writes the play-state packets a connection sends continuously (chunk columns, keep-alives, view-centre updates,
 * chunk batch markers and block-change acknowledgements) straight into Netty buffers, framed and compressed, with
 * no Minestom objects and no heap allocation per call.
 *
 * <p>A chunk column is read from off-heap {@link BlockStorage} under its external-read guard, so it can be encoded
 * on a Netty event loop while regions tick. Byte layouts match Minestom's serializers (verified by
 * {@code PlayWriterTest}).
 *
 * <p>One instance per event-loop thread: the scratch buffers, encoder and zlib state are reused.
 */
public final class PlayWriter implements AutoCloseable {
    private static final int WORLD_SURFACE = 1, MOTION_BLOCKING = 4; // vanilla Heightmap.Types ids
    private static final byte[] FULL_LIGHT = new byte[2048];

    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
    }

    private final int threshold;
    private final int minY = Vanilla.MIN_Y, sections = Vanilla.SECTIONS, biome = Vanilla.PLAINS_BIOME;
    private final int heightBits = 32 - Integer.numberOfLeadingZeros(Vanilla.SECTIONS * 16); // ceil(log2(height + 1))
    private final ChunkEncoder encoder = new ChunkEncoder();
    private final ChunkCompressor zlib;
    private final ByteBuf data = Unpooled.directBuffer(512 * 1024);
    private final ByteBuf body = Unpooled.directBuffer(640 * 1024);
    private final ByteBuf zipped = Unpooled.directBuffer(640 * 1024);
    private final int[] surface = new int[256], motion = new int[256];

    /** @param threshold compression threshold in bytes; 0 or less writes uncompressed frames. */
    public PlayWriter(int threshold) {
        this.threshold = threshold;
        this.zlib = new ChunkCompressor(java.util.zip.Deflater.BEST_SPEED);
    }

    /**
     * Append one framed Chunk Data and Update Light packet for column (chunkX, chunkZ). The storage floor must be
     * a multiple of 16 inside the overworld's height.
     */
    public void chunk(BlockStorage blocks, int chunkX, int chunkZ, ByteBuf out) {
        data.clear();
        blocks.beginExternalRead();
        try {
            encoder.encodeVanillaColumn(blocks, chunkX, chunkZ, biome, minY, sections, data);
            ChunkEncoder.heightmaps(blocks, chunkX, chunkZ, minY, surface, motion);
        } finally {
            blocks.endExternalRead();
        }
        body.clear();
        VarInts.write(body, Protocol.OUT_CHUNK_DATA);
        body.writeInt(chunkX);
        body.writeInt(chunkZ);
        VarInts.write(body, 2);
        writeHeightmap(WORLD_SURFACE, surface);
        writeHeightmap(MOTION_BLOCKING, motion);
        VarInts.write(body, data.readableBytes());
        copy(data, body);
        VarInts.write(body, 0); // block entities
        writeLight();
        frame(out);
    }

    private void writeHeightmap(int type, int[] heights) {
        int perLong = 64 / heightBits;
        VarInts.write(body, type);
        VarInts.write(body, (256 + perLong - 1) / perLong);
        long word = 0;
        int filled = 0;
        for (int i = 0; i < 256; i++) {
            word |= (long) heights[i] << (filled * heightBits);
            if (++filled == perLong) {
                body.writeLong(word);
                word = 0;
                filled = 0;
            }
        }
        if (filled > 0) body.writeLong(word);
    }

    /** Full sky light everywhere (sections below and above the column included), no block light. */
    private void writeLight() {
        int lightSections = sections + 2;
        long all = lightSections == 64 ? -1L : (1L << lightSections) - 1;
        VarInts.write(body, 1);
        body.writeLong(all); // sky mask
        VarInts.write(body, 0); // block mask
        VarInts.write(body, 0); // empty sky mask
        VarInts.write(body, 1);
        body.writeLong(all); // empty block mask
        VarInts.write(body, lightSections);
        for (int s = 0; s < lightSections; s++) {
            VarInts.write(body, FULL_LIGHT.length);
            body.writeBytes(FULL_LIGHT);
        }
        VarInts.write(body, 0); // block light arrays
    }

    public void keepAlive(long id, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_KEEP_ALIVE);
        body.writeLong(id);
        frame(out);
    }

    public void viewCenter(int chunkX, int chunkZ, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_VIEW_CENTER);
        VarInts.write(body, chunkX);
        VarInts.write(body, chunkZ);
        frame(out);
    }

    public void batchStart(ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_BATCH_START);
        frame(out);
    }

    public void batchFinished(int chunks, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_BATCH_FINISHED);
        VarInts.write(body, chunks);
        frame(out);
    }

    public void acknowledgeBlockChange(int sequence, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_ACK_BLOCK);
        VarInts.write(body, sequence);
        frame(out);
    }

    /** Frame {@link #body} (packet id + payload) into {@code out}, compressing it if it reaches the threshold. */
    private void frame(ByteBuf out) {
        int len = body.readableBytes();
        if (threshold <= 0) {
            VarInts.write(out, len);
        } else if (len < threshold) {
            VarInts.write(out, len + 1);
            out.writeByte(0);
        } else {
            zipped.clear();
            zlib.compress(body, zipped);
            VarInts.write(out, VarInts.size(len) + zipped.readableBytes());
            VarInts.write(out, len);
            copy(zipped, out);
            return;
        }
        copy(body, out);
    }

    /**
     * Append {@code src}'s readable bytes to {@code dst} (not consuming {@code src}). Goes through both buffers'
     * cached internal NIO views: Netty's {@code writeBytes(ByteBuf)} between direct buffers creates slice
     * objects when it runs without {@code sun.misc.Unsafe}, as it does by default on JDK 24+.
     */
    static void copy(ByteBuf src, ByteBuf dst) {
        int len = src.readableBytes();
        if (len == 0) return;
        dst.ensureWritable(len);
        dst.internalNioBuffer(dst.writerIndex(), len).put(src.internalNioBuffer(src.readerIndex(), len));
        dst.writerIndex(dst.writerIndex() + len);
    }

    @Override
    public void close() {
        zlib.close();
        data.release();
        body.release();
        zipped.release();
    }
}
