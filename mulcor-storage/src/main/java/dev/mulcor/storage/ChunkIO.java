package dev.mulcor.storage;

import static dev.mulcor.memory.Mem.INT;

import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.OffHeapRing;
import dev.mulcor.memory.TaggedFreeList;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Asynchronous chunk loading and saving for one dimension's {@code region/} folder, on dedicated I/O threads.
 *
 * <ul>
 *   <li><b>Sharding, no locks.</b> Region file {@code (rx, rz)} always belongs to shard
 *       {@code floorMod(rx * 31 + rz, shards)}, so each {@code .mca} has exactly one reader/writer thread and needs no
 *       locking. Each shard keeps an LRU of open region files and its own codec, compressor and buffers.</li>
 *   <li><b>Submission never blocks.</b> Callers (the chunk service, region threads during autosave) reserve a
 *       ticket, then enqueue a fixed 16-byte record into the shard's off-heap MPMC ring and wake the shard if it is
 *       parked. A full ring or an exhausted ticket pool returns -1: retry later.</li>
 *   <li><b>Completion by polling.</b> The shard writes the ticket's status with a release store after the result is
 *       complete; {@link #status} acquires it. The loaded {@link ChunkData} lives in the ticket's reusable slot until
 *       {@link #release}.</li>
 * </ul>
 */
public final class ChunkIO implements AutoCloseable {
    public static final int PENDING = 0, DONE = 1, ABSENT = 2, FAILED = 3;
    private static final int OP_LOAD = 1, OP_SAVE = 2, OP_FLUSH = 3;
    private static final int REC = 16; // op, cx, cz, ticket

    private final Path dir;
    private final int sectionCount;
    private final NativeMemory memory = new NativeMemory();
    private final Shard[] shards;
    private final TaggedFreeList tickets;
    private final MemorySegment status;
    private final ChunkData[] slots;
    private final Throwable[] errors;
    private volatile boolean closed;

    /**
     * @param dir the dimension's {@code region/} folder
     * @param sectionCount the dimension's height in sections (24 for the overworld)
     */
    public ChunkIO(Path dir, int sectionCount, int threads, int maxTickets) {
        this.dir = dir;
        this.sectionCount = sectionCount;
        this.tickets = new TaggedFreeList(memory, maxTickets);
        this.status = memory.allocate((long) maxTickets * Integer.BYTES);
        this.slots = new ChunkData[maxTickets];
        this.errors = new Throwable[maxTickets];
        for (int i = 0; i < maxTickets; i++) slots[i] = new ChunkData();
        this.shards = new Shard[threads];
        for (int i = 0; i < threads; i++) {
            shards[i] = new Shard(i);
            shards[i].thread.start();
        }
    }

    private Shard shardFor(int cx, int cz) {
        return shards[Math.floorMod((cx >> 5) * 31 + (cz >> 5), shards.length)];
    }

    // ---- API (any thread) ----

    /** Queue a load of chunk (cx, cz). Returns a ticket to {@link #status poll}, or -1 (retry later). */
    public int load(int cx, int cz) {
        return submit(OP_LOAD, cx, cz, null);
    }

    /**
     * Queue a save of {@code data} as chunk (cx, cz). The data object must not be modified until the ticket
     * completes. Returns a ticket, or -1.
     */
    public int save(ChunkData data) {
        return submit(OP_SAVE, data.x, data.z, data);
    }

    private int submit(int op, int cx, int cz, ChunkData data) {
        if (closed) return -1;
        int t = tickets.pop();
        if (t < 0) return -1;
        errors[t] = null;
        if (data != null) slots[t] = data; // published by the ring's release store
        INT.setRelease(status, (long) t * 4, PENDING);
        Shard s = shardFor(cx, cz);
        if (!s.offer(op, cx, cz, t)) {
            if (data != null) slots[t] = new ChunkData();
            tickets.push(t);
            return -1;
        }
        return t;
    }

    /** {@link #PENDING}, {@link #DONE}, {@link #ABSENT} (no such chunk on disk) or {@link #FAILED}. */
    public int status(int ticket) {
        return (int) INT.getAcquire(status, (long) ticket * 4);
    }

    /** The loaded chunk of a DONE load ticket (valid until {@link #release}). */
    public ChunkData result(int ticket) {
        return slots[ticket];
    }

    public Throwable error(int ticket) {
        return errors[ticket];
    }

    /** Return a completed ticket. A save ticket's slot gets a fresh object so the caller keeps its data. */
    public void release(int ticket) {
        tickets.push(ticket);
    }

    /** Block until every shard has written and fsynced everything queued before this call (save-all flush). */
    public void flush() throws IOException {
        int[] t = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            int ticket;
            while ((ticket = tickets.pop()) < 0) Thread.onSpinWait();
            INT.setRelease(status, (long) ticket * 4, PENDING);
            while (!shards[i].offer(OP_FLUSH, 0, 0, ticket)) Thread.onSpinWait();
            t[i] = ticket;
        }
        for (int ticket : t) {
            while (status(ticket) == PENDING) LockSupport.parkNanos(100_000);
            Throwable e = errors[ticket];
            release(ticket);
            if (e != null) throw new IOException("flush failed", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        flush();
        closed = true;
        for (Shard s : shards) LockSupport.unpark(s.thread);
        for (Shard s : shards) {
            try {
                s.thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        memory.close();
    }

    private void complete(int ticket, int result) {
        INT.setRelease(status, (long) ticket * 4, result);
    }

    // ---- shard ----

    private final class Shard implements Runnable {
        final Thread thread;
        final OffHeapRing ring = new OffHeapRing(memory, 4096, REC);
        final MemorySegment rec = memory.allocate(REC);
        volatile boolean sleeping;
        final ChunkCodec codec = new ChunkCodec();
        final NbtWriter writer = new NbtWriter(1 << 18);
        final Map<Long, RegionFile> files = new LinkedHashMap<>(64, 0.75f, true);

        Shard(int i) {
            thread = Thread.ofPlatform().daemon().name("mulcor-chunk-io-" + i).unstarted(this);
        }

        /** Any thread. Copies the record into the ring and wakes the shard if it parked. */
        boolean offer(int op, int cx, int cz, int ticket) {
            MemorySegment r = SUBMIT.get();
            r.set(ValueLayout.JAVA_INT, 0, op);
            r.set(ValueLayout.JAVA_INT, 4, cx);
            r.set(ValueLayout.JAVA_INT, 8, cz);
            r.set(ValueLayout.JAVA_INT, 12, ticket);
            boolean ok = ring.offer(r, 0, REC);
            if (ok && sleeping) LockSupport.unpark(thread);
            return ok;
        }

        @Override
        public void run() {
            while (true) {
                if (!ring.poll(rec, 0)) {
                    if (closed) break;
                    sleeping = true;
                    if (ring.size() == 0 && !closed) LockSupport.parkNanos(this, 50_000_000);
                    sleeping = false;
                    continue;
                }
                int op = rec.get(ValueLayout.JAVA_INT, 0), cx = rec.get(ValueLayout.JAVA_INT, 4);
                int cz = rec.get(ValueLayout.JAVA_INT, 8), ticket = rec.get(ValueLayout.JAVA_INT, 12);
                try {
                    switch (op) {
                        case OP_LOAD -> {
                            ByteBuffer nbt = file(cx, cz).read(cx, cz);
                            if (nbt == null) {
                                complete(ticket, ABSENT);
                            } else {
                                codec.decode(nbt, sectionCount, slots[ticket]);
                                complete(ticket, DONE);
                            }
                        }
                        case OP_SAVE -> {
                            codec.encode(slots[ticket], writer);
                            slots[ticket] = new ChunkData(); // the caller keeps its object
                            file(cx, cz).write(cx, cz, writer.buffer().flip());
                            writer.buffer().clear();
                            complete(ticket, DONE);
                        }
                        case OP_FLUSH -> {
                            for (RegionFile f : files.values()) f.flush();
                            complete(ticket, DONE);
                        }
                        default -> throw new IllegalStateException("op " + op);
                    }
                } catch (Throwable t) {
                    errors[ticket] = t;
                    complete(ticket, FAILED);
                }
            }
            for (RegionFile f : files.values()) {
                try {
                    f.flush();
                    f.close();
                } catch (IOException ignored) {
                    // closing on shutdown; the flush above already reported failures to callers
                }
            }
        }

        private RegionFile file(int cx, int cz) throws IOException {
            long key = ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFF_FFFFL);
            RegionFile f = files.get(key);
            if (f == null) {
                if (files.size() >= 64) {
                    var eldest = files.entrySet().iterator().next();
                    eldest.getValue().flush();
                    eldest.getValue().close();
                    files.remove(eldest.getKey());
                }
                f = new RegionFile(dir, cx >> 5, cz >> 5);
                files.put(key, f);
            }
            return f;
        }
    }

    /** Per submitting thread: a 16-byte record buffer (allocated once per thread, not per call). */
    private static final ThreadLocal<MemorySegment> SUBMIT = ThreadLocal.withInitial(() -> NativeMemory.auto().allocate(REC));
}
