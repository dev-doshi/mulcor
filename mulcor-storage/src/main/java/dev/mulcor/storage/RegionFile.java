package dev.mulcor.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * One Anvil region file ({@code r.<x>.<z>.mca}: 32×32 chunks), byte-compatible with vanilla's
 * {@code net.minecraft.world.level.chunk.storage.RegionFile}.
 *
 * <h2>Format (vanilla)</h2>
 * <ul>
 *   <li>Sectors are 4096 bytes. Sector 0 holds 1024 big-endian ints {@code sectorOffset << 8 | sectorCount} (0 = no
 *       chunk), sector 1 holds 1024 big-endian epoch-second timestamps. Chunk index = {@code (x & 31) + (z & 31) * 32}.</li>
 *   <li>A chunk's sectors start with {@code int length} (bytes that follow, including the next byte) and
 *       {@code byte compression}: 1 = gzip, 2 = zlib (vanilla's default), 3 = none, 4 = LZ4, 127 = custom. Bit
 *       0x80 means the payload is in an external file {@code c.<x>.<z>.mcc} (chunks over 255 sectors, ~1 MiB).</li>
 *   <li>Writes follow vanilla's crash ordering: allocate fresh sectors (first fit), write the chunk there, then
 *       rewrite the header entry, then free the old sectors. A crash mid-write leaves the old chunk intact.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: each file is owned by exactly one storage I/O thread (files are sharded across
 * threads by region coordinates), so no locks are needed. Buffers, the Deflater and the Inflater are reused.
 */
public final class RegionFile implements AutoCloseable {
    public static final int SECTOR = 4096;
    public static final int GZIP = 1, ZLIB = 2, NONE = 3, LZ4 = 4, CUSTOM = 127, EXTERNAL = 0x80;
    private static final int MAX_SECTORS_PER_CHUNK = 255;

    private final Path path;
    private final Path dir;
    private final int regionX, regionZ;
    private final FileChannel ch;
    private final ByteBuffer header = ByteBuffer.allocate(2 * SECTOR).order(ByteOrder.BIG_ENDIAN);
    private final BitSet used = new BitSet();
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final Inflater inflater = new Inflater();
    private ByteBuffer io = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN);
    private ByteBuffer out = ByteBuffer.allocate(256 * 1024).order(ByteOrder.BIG_ENDIAN);

    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /** Open (creating if needed) the region file containing chunk (cx, cz) under {@code dir}. */
    public static RegionFile forChunk(Path dir, int cx, int cz) throws IOException {
        return new RegionFile(dir, cx >> 5, cz >> 5);
    }

    public RegionFile(Path dir, int regionX, int regionZ) throws IOException {
        this.dir = dir;
        this.regionX = regionX;
        this.regionZ = regionZ;
        Files.createDirectories(dir);
        this.path = dir.resolve(fileName(regionX, regionZ));
        this.ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = ch.size();
        used.set(0, 2);
        if (size < 2L * SECTOR) {
            header.clear();
            ch.write(header.duplicate().clear(), 0); // fresh file: two zero header sectors
            return;
        }
        header.clear();
        readFully(header, 0);
        long sectors = (size + SECTOR - 1) / SECTOR;
        for (int i = 0; i < 1024; i++) {
            int loc = header.getInt(i * 4);
            int off = loc >>> 8, count = loc & 0xFF;
            if (loc == 0) continue;
            if (off < 2 || off + count > sectors) {
                header.putInt(i * 4, 0); // vanilla: out-of-bounds entries are dropped (chunk regenerates)
                continue;
            }
            used.set(off, off + count);
        }
    }

    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }

    private static int index(int cx, int cz) {
        return (cx & 31) + (cz & 31) * 32;
    }

    public boolean has(int cx, int cz) {
        return header.getInt(index(cx, cz) * 4) != 0;
    }

    /** Epoch seconds of the chunk's last write, or 0. */
    public int timestamp(int cx, int cz) {
        return header.getInt(SECTOR + index(cx, cz) * 4);
    }

    /**
     * Read and decompress chunk (cx, cz). Returns a buffer positioned at the start of the chunk's NBT (valid until
     * the next call on this file), or {@code null} if the chunk is absent.
     */
    public ByteBuffer read(int cx, int cz) throws IOException {
        int loc = header.getInt(index(cx, cz) * 4);
        if (loc == 0) return null;
        int off = loc >>> 8, count = loc & 0xFF;
        ensureIo(count * SECTOR);
        io.clear().limit(count * SECTOR);
        readFully(io, (long) off * SECTOR);
        io.flip();
        int length = io.getInt();
        if (length <= 0 || length > count * SECTOR - 4) throw new IOException("chunk " + cx + "," + cz + ": bad length " + length);
        int type = io.get() & 0xFF;
        ByteBuffer compressed;
        if ((type & EXTERNAL) != 0) {
            byte[] ext = Files.readAllBytes(externalPath(cx, cz));
            compressed = ByteBuffer.wrap(ext);
            type &= ~EXTERNAL;
        } else {
            compressed = io.slice(io.position(), length - 1);
        }
        return decompress(type, compressed, cx, cz);
    }

    private ByteBuffer decompress(int type, ByteBuffer src, int cx, int cz) throws IOException {
        switch (type) {
            case NONE -> {
                ensureOut(src.remaining());
                out.clear().put(src).flip();
                return out;
            }
            case ZLIB -> {
                inflater.reset();
                inflater.setInput(src);
                out.clear();
                try {
                    while (!inflater.finished()) {
                        if (!out.hasRemaining()) growOut();
                        int n = inflater.inflate(out);
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                            throw new IOException("chunk " + cx + "," + cz + ": truncated zlib stream");
                        }
                    }
                } catch (DataFormatException e) {
                    throw new IOException("chunk " + cx + "," + cz + ": corrupt zlib stream", e);
                }
                return out.flip();
            }
            case GZIP -> {
                byte[] raw = new byte[src.remaining()];
                src.get(raw);
                byte[] data = new GZIPInputStream(new java.io.ByteArrayInputStream(raw)).readAllBytes();
                ensureOut(data.length);
                out.clear().put(data).flip();
                return out;
            }
            default -> throw new IOException("chunk " + cx + "," + cz + ": unsupported compression " + type
                    + (type == LZ4 ? " (LZ4: set region-file-compression=deflate)" : ""));
        }
    }

    /** Compress (zlib, vanilla's default) and write chunk (cx, cz) from {@code nbt}'s remaining bytes. */
    public void write(int cx, int cz, ByteBuffer nbt) throws IOException {
        deflater.reset();
        deflater.setInput(nbt);
        deflater.finish();
        out.clear();
        out.position(5); // room for length + compression type
        while (!deflater.finished()) {
            if (!out.hasRemaining()) growOut();
            deflater.deflate(out);
        }
        int compressed = out.position() - 5;
        int idx = index(cx, cz);
        int oldLoc = header.getInt(idx * 4);
        Path ext = externalPath(cx, cz);
        int sectorsNeeded = (compressed + 5 + SECTOR - 1) / SECTOR;
        int type = ZLIB;
        ByteBuffer payload;
        if (sectorsNeeded > MAX_SECTORS_PER_CHUNK) {
            // Oversized: payload goes to c.x.z.mcc (written via a temp file + atomic move, as vanilla does).
            Path tmp = dir.resolve(ext.getFileName() + ".tmp");
            try (FileChannel e = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer body = out.duplicate().position(5).limit(5 + compressed);
                while (body.hasRemaining()) e.write(body);
            }
            Files.move(tmp, ext, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            type = ZLIB | EXTERNAL;
            payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).putInt(1).put((byte) type).flip();
            sectorsNeeded = 1;
        } else {
            out.putInt(0, compressed + 1).put(4, (byte) type);
            payload = out.duplicate().position(0).limit(5 + compressed);
            Files.deleteIfExists(ext);
        }
        int start = allocate(sectorsNeeded);
        long pos = (long) start * SECTOR;
        while (payload.hasRemaining()) pos += ch.write(payload, pos);
        long end = (long) (start + sectorsNeeded) * SECTOR;
        if (ch.size() < end) ch.write(ByteBuffer.allocate((int) (end - pos)), pos); // pad to a whole sector
        header.putInt(idx * 4, start << 8 | sectorsNeeded);
        header.putInt(SECTOR + idx * 4, (int) (System.currentTimeMillis() / 1000));
        writeHeaderEntry(idx);
        if (oldLoc != 0) used.clear(oldLoc >>> 8, (oldLoc >>> 8) + (oldLoc & 0xFF));
    }

    /** Remove chunk (cx, cz). */
    public void delete(int cx, int cz) throws IOException {
        int idx = index(cx, cz);
        int oldLoc = header.getInt(idx * 4);
        if (oldLoc == 0) return;
        header.putInt(idx * 4, 0);
        header.putInt(SECTOR + idx * 4, 0);
        writeHeaderEntry(idx);
        used.clear(oldLoc >>> 8, (oldLoc >>> 8) + (oldLoc & 0xFF));
        Files.deleteIfExists(externalPath(cx, cz));
    }

    private void writeHeaderEntry(int idx) throws IOException {
        ch.write(header.duplicate().position(idx * 4).limit(idx * 4 + 4), idx * 4L);
        ch.write(header.duplicate().position(SECTOR + idx * 4).limit(SECTOR + idx * 4 + 4), SECTOR + idx * 4L);
    }

    /** First-fit run of {@code n} free sectors (vanilla {@code RegionBitmap.allocate}). */
    private int allocate(int n) {
        int start = 2;
        for (;;) {
            int free = used.nextClearBit(start);
            int next = used.nextSetBit(free);
            if (next < 0 || next - free >= n) {
                used.set(free, free + n);
                return free;
            }
            start = next;
        }
    }

    /** Force data and header to disk (vanilla's {@code save-all flush}). */
    public void flush() throws IOException {
        ch.force(true);
    }

    private Path externalPath(int cx, int cz) {
        return dir.resolve("c." + cx + "." + cz + ".mcc");
    }

    private void readFully(ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException(path + ": unexpected end of file");
            pos += n;
        }
    }

    private void ensureIo(int n) {
        if (io.capacity() < n) io = ByteBuffer.allocate(Integer.highestOneBit(n - 1) << 1).order(ByteOrder.BIG_ENDIAN);
    }

    private void ensureOut(int n) {
        if (out.capacity() < n) out = ByteBuffer.allocate(Integer.highestOneBit(n - 1) << 1).order(ByteOrder.BIG_ENDIAN);
    }

    private void growOut() {
        ByteBuffer bigger = ByteBuffer.allocate(out.capacity() * 2).order(ByteOrder.BIG_ENDIAN);
        out.flip();
        bigger.put(out);
        out = bigger;
    }

    @Override
    public void close() throws IOException {
        deflater.end();
        inflater.end();
        ch.close();
    }
}
