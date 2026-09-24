package dev.mulcor.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EntityTableTest {
    private final NativeMemory mem = new NativeMemory();

    @AfterEach
    void close() {
        mem.close();
    }

    @Test
    void addRemoveSwapsLastIntoHole() {
        var t = new EntityTable(mem, 3);
        assertEquals(0, t.add(10, 1, 2, 3, 7));
        assertEquals(1, t.add(11, 4, 5, 6, 8));
        assertEquals(2, t.add(12, 7, 8, 9, 9));
        assertEquals(-1, t.add(13, 0, 0, 0, 0));
        assertEquals(12, t.remove(0));
        assertEquals(2, t.count());
        assertEquals(12, t.id(0));
        assertEquals(7.0, t.x(0));
        assertEquals(9, t.type(0));
        assertEquals(-1, t.remove(1), "removing the last slot moves nothing");
        assertEquals(1, t.count());
    }

    @Test
    void recordRoundTrip() {
        var a = new EntityTable(mem, 4);
        var b = new EntityTable(mem, 4);
        int s = a.add(99, 1.5, -2.25, 1e9, 42);
        a.setVel(s, 0.1f, -0.2f, 0.3f);
        a.setFlags(s, 0xF0);
        a.setAux0(s, 1);
        a.setAux1(s, 2);
        a.setAux2(s, 3);
        var rec = mem.allocate(EntityRecord.BYTES);
        a.writeRecord(s, rec, 0);
        int d = b.addRecord(rec, 0);
        assertEquals(99, b.id(d));
        assertEquals(1.5, b.x(d));
        assertEquals(-2.25, b.y(d));
        assertEquals(1e9, b.z(d));
        assertEquals(-0.2f, b.vy(d));
        assertEquals(42, b.type(d));
        assertEquals(0xF0, b.flags(d));
        assertEquals(3, b.aux2(d));
    }

    @Test
    void ownershipWordPacking() {
        long w = Ownership.pack(Ownership.IN_TRANSIT, Ownership.MAX_REGION, -1);
        assertEquals(Ownership.IN_TRANSIT, Ownership.state(w));
        assertEquals(Ownership.MAX_REGION, Ownership.region(w));
        assertEquals(-1, Ownership.epoch(w));
        assertEquals("OWNED(region=5, epoch=9)", Ownership.toString(Ownership.pack(Ownership.OWNED, 5, 9)));
    }

    /** Many threads race to claim one in-transit entity; exactly one claim may succeed per round. */
    @Test
    void directoryClaimIsExclusive() throws Exception {
        var dir = new EntityDirectory(mem, 4);
        int id = dir.allocate(1, 0);
        final int threads = 8, rounds = 20_000;
        var barrier = new CyclicBarrier(threads, () -> {});
        var wins = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int region = t + 2;
            ts[t] = Thread.ofPlatform().start(() -> {
                for (int r = 0; r < rounds; r++) {
                    long transit = Ownership.pack(Ownership.IN_TRANSIT, 1, r);
                    if (region == 2) dir.casOwnership(id, dir.ownership(id), transit);
                    try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                    if (dir.casOwnership(id, transit, Ownership.pack(Ownership.OWNED, region, r))) wins.incrementAndGet();
                    try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
        }
        for (Thread t : ts) t.join();
        assertEquals(rounds, wins.get());
    }
}
