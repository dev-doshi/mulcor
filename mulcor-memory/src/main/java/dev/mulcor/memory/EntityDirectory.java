package dev.mulcor.memory;

import static dev.mulcor.memory.Mem.INT;
import static dev.mulcor.memory.Mem.LONG;

import java.lang.foreign.MemorySegment;

/**
 * Global off-heap directory for entity ids. It is the one source of truth for who owns an entity, and it
 * records where the entity sits in its owner's {@link EntityTable}.
 *
 * <p>Each 16-byte row holds {@code ownership:long} ({@link Ownership}) and {@code slot:int}. Ids are recycled
 * through a lock-free {@link TaggedFreeList}.
 *
 * <p>Id 0 is never handed out: vanilla numbers entities from 1, and the client treats id 0 as "not yet
 * assigned" (joining with it crashes the client in {@code ClientLevel.addEntity}).
 */
public final class EntityDirectory {
    private static final long ROW = 16;
    private static final long OWNERSHIP = 0;
    private static final long SLOT = 8;

    private final MemorySegment rows;
    private final TaggedFreeList ids;
    private final int capacity;

    public EntityDirectory(NativeMemory memory, int capacity) {
        this.capacity = capacity;
        this.rows = memory.allocate(capacity * ROW);
        this.ids = new TaggedFreeList(memory, capacity);
        if (ids.pop() != RESERVED) throw new IllegalStateException("fresh free list must yield id 0 first");
    }

    /** The id reserved as vanilla's "unassigned" sentinel; {@link #allocate} never returns it. */
    public static final int RESERVED = 0;

    public int capacity() {
        return capacity;
    }

    /**
     * Allocate an id already owned by {@code region} in {@code epoch}. Returns {@code -1} if all ids are in use.
     * Never returns {@link #RESERVED}, so at most {@code capacity - 1} ids are live at once.
     */
    public int allocate(int region, int epoch) {
        int id = ids.pop();
        if (id >= 0) {
            LONG.setRelease(rows, id * ROW + OWNERSHIP, Ownership.pack(Ownership.OWNED, region, epoch));
        }
        return id;
    }

    /** Retire an id. Only the owning region may call this; the id may be reused afterwards. */
    public void free(int id) {
        LONG.setRelease(rows, id * ROW + OWNERSHIP, Ownership.pack(Ownership.FREE, 0, 0));
        ids.push(id);
    }

    public long ownership(int id) {
        return (long) LONG.getAcquire(rows, id * ROW + OWNERSHIP);
    }

    public boolean casOwnership(int id, long expected, long update) {
        return LONG.compareAndSet(rows, id * ROW + OWNERSHIP, expected, update);
    }

    /** Owner-only: the entity's slot in its owner's table. */
    public int slot(int id) {
        return (int) INT.get(rows, id * ROW + SLOT);
    }

    public void setSlot(int id, int slot) {
        INT.set(rows, id * ROW + SLOT, slot);
    }
}
