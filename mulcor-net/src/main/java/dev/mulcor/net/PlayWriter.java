package dev.mulcor.net;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
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
    /** One light section copied out of {@link LightStorage}, and a reusable NIO view of it. */
    private final MemorySegment lightScratch = Arena.ofAuto().allocate(LightStorage.SECTION_BYTES);
    private final ByteBuffer lightView = lightScratch.asByteBuffer();

    /** @param threshold compression threshold in bytes; 0 or less writes uncompressed frames. */
    public PlayWriter(int threshold) {
        this.threshold = threshold;
        this.zlib = new ChunkCompressor(java.util.zip.Deflater.BEST_SPEED);
    }

    /**
     * Append one framed Chunk Data and Update Light packet for column (chunkX, chunkZ) with the world's real light.
     * The storage floor must be a multiple of 16 inside the overworld's height.
     */
    public void chunk(BlockStorage blocks, LightStorage blockLight, LightStorage skyLight, int chunkX, int chunkZ,
            ByteBuf out) {
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
        writeLight(blockLight, skyLight, chunkX, chunkZ);
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

    /**
     * Vanilla {@code ClientboundLightUpdatePacketData}: for each light section (one below the column to one above),
     * a section with data goes in the mask with its 2048 bytes; an all-zero one goes in the empty mask
     * ({@code prepareSectionData}: {@code dataLayer.isEmpty()}). Sections Mulcor does not store are air: full sky
     * above the storage, darkness below it. {@code null} storages mean full sky light and no block light.
     */
    private void writeLight(LightStorage blockLight, LightStorage skyLight, int chunkX, int chunkZ) {
        int n = sections + 2, wireMin = (minY >> 4) - 1;
        long skyMask = 0, emptySky = 0, blockMask = 0, emptyBlock = 0;
        for (int i = 0; i < n; i++) {
            long bit = 1L << i;
            if (level(skyLight, 15, chunkX, chunkZ, wireMin + i) == 0) emptySky |= bit; else skyMask |= bit;
            if (level(blockLight, 0, chunkX, chunkZ, wireMin + i) == 0) emptyBlock |= bit; else blockMask |= bit;
        }
        writeMask(skyMask);
        writeMask(blockMask);
        writeMask(emptySky);
        writeMask(emptyBlock);
        writeArrays(skyLight, 15, skyMask, chunkX, chunkZ, wireMin);
        writeArrays(blockLight, 0, blockMask, chunkX, chunkZ, wireMin);
    }

    /** Uniform level of a light section (-1 if it holds an array); outside the storage, {@code above} or 0. */
    private static int level(LightStorage ls, int above, int chunkX, int chunkZ, int sectionY) {
        if (ls == null) return above;
        if (sectionY >= ls.minSection() + ls.sections()) return above;
        if (sectionY < ls.minSection()) return 0;
        return ls.uniformLevel(chunkX, chunkZ, sectionY);
    }

    private void writeMask(long mask) {
        if (mask == 0) {
            VarInts.write(body, 0);
        } else {
            VarInts.write(body, 1);
            body.writeLong(mask);
        }
    }

    private void writeArrays(LightStorage ls, int above, long mask, int chunkX, int chunkZ, int wireMin) {
        VarInts.write(body, Long.bitCount(mask));
        for (long m = mask; m != 0; m &= m - 1) {
            int sectionY = wireMin + Long.numberOfTrailingZeros(m);
            VarInts.write(body, LightStorage.SECTION_BYTES);
            if (ls == null || sectionY >= ls.minSection() + ls.sections() || sectionY < ls.minSection()) {
                body.writeBytes(FULL_LIGHT); // above the storage: open sky (15); below it the level is 0, never sent
            } else {
                ls.copySection(chunkX, chunkZ, sectionY, lightScratch, 0);
                body.ensureWritable(LightStorage.SECTION_BYTES);
                body.internalNioBuffer(body.writerIndex(), LightStorage.SECTION_BYTES).put(lightView.clear());
                body.writerIndex(body.writerIndex() + LightStorage.SECTION_BYTES);
            }
        }
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

    /** {@code ClientboundBlockUpdatePacket}: position (the packed layout), block state id. */
    public void blockUpdate(long pos, int state, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_BLOCK_UPDATE);
        body.writeLong(pos);
        VarInts.write(body, Protocol.vanillaState(state));
        frame(out);
    }

    /** {@code ClientboundBlockEventPacket}: position, event id, parameter, block type (pistons animate from it). */
    public void blockEvent(long pos, int a, int b, int block, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_BLOCK_EVENT);
        body.writeLong(pos);
        body.writeByte(a);
        body.writeByte(b);
        VarInts.write(body, block);
        frame(out);
    }

    /**
     * {@code ClientboundAddEntityPacket}: id, UUID, type, position, velocity ({@link #lpVec3}), pitch, yaw and head
     * yaw as angle bytes, and the type's data (a falling block's state).
     */
    public void addEntity(int id, long uuidMost, long uuidLeast, int type, double x, double y, double z,
                          double vx, double vy, double vz, float yaw, float pitch, int data, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_ADD_ENTITY);
        VarInts.write(body, id);
        body.writeLong(uuidMost);
        body.writeLong(uuidLeast);
        VarInts.write(body, type);
        body.writeDouble(x);
        body.writeDouble(y);
        body.writeDouble(z);
        lpVec3(vx, vy, vz, body);
        body.writeByte(angle(pitch));
        body.writeByte(angle(yaw));
        body.writeByte(angle(yaw)); // head yaw
        VarInts.write(body, data);
        frame(out);
    }

    /** {@code ClientboundEntityPositionSyncPacket}: id, position, velocity, yaw, pitch, on ground. */
    public void entitySync(int id, double x, double y, double z, double vx, double vy, double vz, float yaw, float pitch,
                           boolean onGround, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_ENTITY_SYNC);
        VarInts.write(body, id);
        body.writeDouble(x);
        body.writeDouble(y);
        body.writeDouble(z);
        body.writeDouble(vx);
        body.writeDouble(vy);
        body.writeDouble(vz);
        body.writeFloat(yaw);
        body.writeFloat(pitch);
        body.writeBoolean(onGround);
        frame(out);
    }

    /** {@code ClientboundRemoveEntitiesPacket}: the first {@code n} ids of {@code ids}. */
    public void removeEntities(int[] ids, int n, ByteBuf out) {
        body.clear();
        VarInts.write(body, Protocol.OUT_REMOVE_ENTITIES);
        VarInts.write(body, n);
        for (int i = 0; i < n; i++) VarInts.write(body, ids[i]);
        frame(out);
    }

    /** Frame an already serialized packet (id + payload), e.g. a cold-path packet written by Minestom. */
    public void raw(byte[] packet, ByteBuf out) {
        body.clear();
        body.writeBytes(packet);
        frame(out);
    }

    /** {@code Mth.packDegrees}: degrees to a protocol angle byte. */
    static int angle(float degrees) {
        return (int) Math.floor(degrees * 256.0F / 360.0F) & 0xFF;
    }

    /**
     * Vanilla's {@code LpVec3} (1.21.9+): a single 0 byte for a (near-)zero vector; otherwise the three components
     * divided by a whole-number scale, each quantized to 15 bits, packed with the scale's low bits into 6 bytes
     * (a byte, a byte, a big-endian int), followed by a VarInt of {@code scale >> 2} when the scale needs more than 2
     * bits.
     */
    static void lpVec3(double x, double y, double z, ByteBuf buf) {
        x = sanitize(x);
        y = sanitize(y);
        z = sanitize(z);
        double max = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (max < 3.051944088384301E-5) {
            buf.writeByte(0);
            return;
        }
        long scale = (long) max;
        if (max > scale) scale++;
        boolean continuation = (scale & 3) != scale;
        long scaleBits = continuation ? (scale & 3) | 4 : scale;
        long bits = scaleBits | lpPack(x / scale) << 3 | lpPack(y / scale) << 18 | lpPack(z / scale) << 33;
        buf.writeByte((int) bits);
        buf.writeByte((int) (bits >> 8));
        buf.writeInt((int) (bits >> 16));
        if (continuation) VarInts.write(buf, (int) (scale >> 2));
    }

    private static double sanitize(double v) {
        return Double.isNaN(v) ? 0.0 : Math.clamp(v, -1.7179869183E10, 1.7179869183E10);
    }

    private static long lpPack(double v) {
        return Math.round((v * 0.5 + 0.5) * 32766.0);
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
