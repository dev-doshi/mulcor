package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every hot-path primitive must allocate exactly 0 heap bytes once warmed up. The per-thread TLAB counter is
 * sampled around a million operations, after enough warm-up for C2 to compile the loop.
 */
@Tag("alloc")
class MemoryZeroAllocationTest {
    private static NativeMemory mem;
    private static OffHeapRing ring;
    private static TaggedFreeList list;
    private static BlockStorage blocks;
    private static EntityTable table;
    private static EntityDirectory dir;
    private static OffHeapInventory inv;
    private static MemorySegment scratch;
    private static final int[] OVERFLOW = new int[1];
    private static long sink;
    private static final OffHeapRing.SlotHandler HANDLER = (seg, off) -> sink += seg.get(ValueLayout.JAVA_LONG, off);

    @BeforeAll
    static void setup() {
        assertTrue(AllocationMeter.supported());
        mem = new NativeMemory();
        ring = new OffHeapRing(mem, 1024, 64);
        list = new TaggedFreeList(mem, 64);
        blocks = new BlockStorage(mem, 4, 4, 0, 4, 64);
        table = new EntityTable(mem, 64);
        dir = new EntityDirectory(mem, 64);
        inv = new OffHeapInventory(mem, 2, 27);
        scratch = mem.allocate(EntityRecord.BYTES);
        inv.set(0, 0, 1, 64);
    }

    @AfterAll
    static void tearDown() {
        mem.close();
    }

    private static void workload(int iterations) {
        for (int i = 0; i < iterations; i++) {
            // ring: claim/publish, drain, copy-offer/poll
            long pos = ring.tryClaim();
            ring.segment().set(ValueLayout.JAVA_LONG, ring.payloadOffset(pos), i);
            ring.publish(pos);
            ring.drain(HANDLER, 8);
            ring.offer(scratch, 0, EntityRecord.BYTES);
            ring.poll(scratch, 0);
            // free list
            list.push(list.pop());
            // blocks, including section allocation and release
            int x = i & 63, y = (i >> 6) & 63, z = (i >> 12) & 63;
            blocks.set(x, y, z, 1 + (i & 7));
            sink += blocks.get(x, y, z);
            blocks.set(x, y, z, 0);
            blocks.reclaim();
            // entities + ownership
            int id = dir.allocate(1, i);
            int s = table.add(id, x, y, z, 1);
            dir.setSlot(id, s);
            table.setPos(s, x + 0.5, y, z);
            table.writeRecord(s, scratch, 0);
            dir.casOwnership(id, dir.ownership(id), Ownership.pack(Ownership.IN_TRANSIT, 2, i + 1));
            table.remove(s);
            table.addRecord(scratch, 0);
            table.remove(table.count() - 1);
            dir.free(id);
            // inventory
            inv.move(i & 1, i % 27, (i + 1) & 1, 1 + (i & 31), OVERFLOW);
        }
    }

    /** Negative control: the meter must see real allocation, or a 0 reading proves nothing. */
    @Test
    void meterDetectsAllocation() {
        long before = AllocationMeter.currentThread();
        Object[] junk = new Object[1000];
        for (int i = 0; i < junk.length; i++) junk[i] = new long[16];
        sink += junk.length;
        assertTrue(AllocationMeter.currentThread() - before >= 1000 * 16 * 8, "allocation meter is blind");
    }

    @Test
    void steadyStateAllocatesZeroBytes() {
        for (int round = 0; round < 20; round++) workload(20_000);
        long before = AllocationMeter.currentThread();
        workload(1_000_000);
        long allocated = AllocationMeter.currentThread() - before;
        assertEquals(0, allocated, "hot-path primitives allocated " + allocated + " bytes over 1M iterations");
        assertEquals(64, inv.totalAll(0) + inv.totalAll(1));
    }
}
