package dev.mulcor.core;

import static dev.mulcor.memory.Mem.LONG;

import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.TaggedFreeList;
import java.lang.foreign.MemorySegment;

/**
 * Hands the entity id of a newly spawned network player from the region thread that spawned it back to the
 * network thread that asked for it, without either side waiting on the other.
 *
 * <p>A network thread {@link #reserve reserves} a ticket, sends a {@code JOIN} input carrying it, and then
 * {@link #poll polls} the ticket from its own event loop. The region completes the ticket with a release store
 * after the entity is fully registered, so a poll that sees the id also sees the entity.
 */
public final class JoinTickets {
    /** {@link #poll} result: the region has not processed the join yet. */
    public static final int PENDING = -1;
    /** {@link #poll} result: the region could not spawn the player (no free ids or table space). */
    public static final int FAILED = -2;

    private static final long EMPTY = 0, FAILURE = -1;

    private final MemorySegment slots;
    private final TaggedFreeList free;

    public JoinTickets(NativeMemory memory, int capacity) {
        this.slots = memory.allocate((long) capacity * Long.BYTES);
        this.free = new TaggedFreeList(memory, capacity);
    }

    /** Take a ticket, or return -1 if all are in use. Thread-safe. */
    public int reserve() {
        int t = free.pop();
        if (t >= 0) LONG.setRelease(slots, (long) t * Long.BYTES, EMPTY);
        return t;
    }

    /** Region thread: publish the result of a join (an entity id, or a negative value for failure). */
    public void complete(int ticket, int entity) {
        LONG.setRelease(slots, (long) ticket * Long.BYTES, entity >= 0 ? entity + 1L : FAILURE);
    }

    /** The spawned entity id, {@link #PENDING} or {@link #FAILED}. */
    public int poll(int ticket) {
        long v = (long) LONG.getAcquire(slots, (long) ticket * Long.BYTES);
        return v == EMPTY ? PENDING : v == FAILURE ? FAILED : (int) (v - 1);
    }

    /** Return a completed ticket for reuse. */
    public void release(int ticket) {
        free.push(ticket);
    }
}
