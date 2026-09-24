package dev.mulcor.core.region;

import dev.mulcor.core.EngineConfig;
import dev.mulcor.core.World;
import dev.mulcor.memory.EntityDirectory;
import dev.mulcor.memory.EntityRecord;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.memory.OffHeapRing;
import dev.mulcor.memory.Ownership;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * One independently ticked slice of the world. See the package documentation for the execution model and the
 * cross-region invariants.
 *
 * <p>All per-region state (entity table, rings, scratch, pathfinder, metrics) is allocated once in the
 * constructor, so {@link #tick(long)} allocates nothing.
 */
public final class Region {
    public static final int INACTIVE = StateWord.INACTIVE, IDLE = StateWord.IDLE;
    public static final int SCHEDULED = StateWord.SCHEDULED, RUNNING = StateWord.RUNNING;

    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

    public final int id;
    final World world;
    final EngineConfig cfg;
    final EntityDirectory dir;
    public final EntityTable table;
    private final OffHeapRing[] inbox = new OffHeapRing[2];
    public final OffHeapRing ingress;
    private final OffHeapRing overflow;
    final OffHeapRing updates;
    final MemorySegment scratch;
    private final MemorySegment retry;
    final Pathfinder path = new Pathfinder();
    private final OffHeapRing.SlotHandler onMessage = this::handleMessage;
    private final OffHeapRing.SlotHandler onInput = this::handleInput;

    private final StateWord state;
    long epoch;
    boolean ai = true;

    // ---- metrics: written by the thread running this region, read after the epoch barrier ----
    public double costEwma;
    public long lastTickNanos;
    private final long[] samples;
    private final int sampleMask;
    private long sampleCount;
    private final long[] probeLog = new long[4096];
    private int probeCount;

    // ---- counters ----
    public long ticks, transfersOut, transfersIn, transferRejects, transferDeferred, transferCancelled;
    public long droppedItems, destroyedBlocks, explosions, forwarded, inputs, messages, overflowed, undeliverable;
    public long stateViolations, updatesDropped, spawnFailures, joins, leaves;

    public Region(int id, World world, boolean active) {
        this.id = id;
        this.world = world;
        this.cfg = world.cfg;
        this.dir = world.directory;
        var mem = world.memory;
        this.table = new EntityTable(mem, cfg.regionEntityCapacity());
        this.inbox[0] = new OffHeapRing(mem, cfg.inboxCapacity(), Msg.BYTES);
        this.inbox[1] = new OffHeapRing(mem, cfg.inboxCapacity(), Msg.BYTES);
        this.ingress = new OffHeapRing(mem, cfg.ingressCapacity(), Input.BYTES);
        this.overflow = new OffHeapRing(mem, cfg.inboxCapacity(), Msg.BYTES);
        this.updates = new OffHeapRing(mem, 4096, 16);
        this.scratch = mem.allocate(Msg.BYTES);
        this.retry = mem.allocate(Msg.BYTES);
        int cap = Integer.highestOneBit(Math.max(2, cfg.sampleCapacity()));
        this.samples = new long[cap];
        this.sampleMask = cap - 1;
        this.state = new StateWord(active ? IDLE : INACTIVE);
    }

    // ---- state machine -----------------------------------------------------------------------------------------

    public int state() {
        return state.get();
    }

    public boolean casState(int expected, int update) {
        return state.cas(expected, update);
    }

    public boolean isActive() {
        return state.get() != INACTIVE;
    }

    public OffHeapRing inbox(long epoch) {
        return inbox[(int) (epoch & 1)];
    }

    // ---- the tick ----------------------------------------------------------------------------------------------

    /** Run one epoch. The caller must have moved this region to SCHEDULED. */
    public void tick(long epoch, boolean aiEnabled) {
        long t0 = System.nanoTime();
        if (!casState(SCHEDULED, RUNNING)) {
            stateViolations++;
            return;
        }
        this.epoch = epoch;
        this.ai = aiEnabled;
        retryOverflow();
        messages += inbox(epoch - 1).drain(onMessage, Integer.MAX_VALUE);
        ingress.drain(onInput, cfg.ingressBudget());
        Sim.processUpdates(this);
        Sim.simulate(this);
        long dt = System.nanoTime() - t0;
        lastTickNanos = dt;
        costEwma = costEwma == 0 ? dt : costEwma * 0.8 + dt * 0.2;
        samples[(int) (sampleCount++ & sampleMask)] = dt;
        ticks++;
        if (!casState(RUNNING, IDLE)) stateViolations++;
    }

    // ---- sending -----------------------------------------------------------------------------------------------

    /** Clear and start building a message of {@code kind} in {@link #scratch}. */
    MemorySegment begin(int kind) {
        scratch.fill((byte) 0);
        scratch.set(I, Msg.KIND, kind);
        return scratch;
    }

    /** Send {@link #scratch} to region {@code dst}. Delivery happens next epoch. */
    void send(int dst) {
        send(scratch, dst);
    }

    private void send(MemorySegment msg, int dst) {
        if (dst < 0) {
            undeliverable(msg, 0);
            return;
        }
        msg.set(I, Msg.DST, dst);
        if (world.regions[dst].inbox(epoch).offer(msg, 0, Msg.BYTES)) return;
        if (overflow.offer(msg, 0, Msg.BYTES)) {
            overflowed++;
        } else {
            undeliverable(msg, 0);
        }
    }

    /** Forward a message still sitting in an inbox slot to its current owner. */
    private void forward(MemorySegment seg, long off, int dst) {
        forwarded++;
        if (dst < 0) {
            undeliverable(seg, off);
            return;
        }
        seg.set(I, off + Msg.DST, dst);
        if (world.regions[dst].inbox(epoch).offer(seg, off, Msg.BYTES)) return;
        if (overflow.offer(seg, off, Msg.BYTES)) {
            overflowed++;
        } else {
            undeliverable(seg, off);
        }
    }

    /** A message that cannot be delivered. Items it carries are dropped to the ground, and still counted. */
    private void undeliverable(MemorySegment seg, long off) {
        undeliverable++;
        int kind = seg.get(I, off + Msg.KIND);
        if (kind == Msg.INV_PUT || kind == Msg.INV_DELIVER) {
            droppedItems += seg.get(I, off + Msg.C);
        } else if (kind == Msg.BLOCK_PLACE) {
            droppedItems += 1;
        } else if (kind == Msg.TRANSFER) {
            stateViolations++; // never routed through here: transfers cancel instead
        }
    }

    private void retryOverflow() {
        int n = overflow.size();
        for (int i = 0; i < n; i++) {
            if (!take(overflow)) break;
            send(retry, world.route(retry, 0));
        }
    }

    // ---- entities ----------------------------------------------------------------------------------------------

    /** Spawn an entity owned by this region. Returns its id, or -1 if ids or table space ran out. */
    public int spawn(double x, double y, double z, int type, int role) {
        int eid = dir.allocate(id, (int) epoch);
        if (eid < 0) {
            spawnFailures++;
            return -1;
        }
        int slot = table.add(eid, x, y, z, type);
        if (slot < 0) {
            dir.free(eid);
            spawnFailures++;
            return -1;
        }
        table.setAux0(slot, role);
        dir.setSlot(eid, slot);
        return eid;
    }

    /** Spawn a network player (JOIN input) and publish its id, or the failure, through the join ticket. */
    private void join(int x1000, int y1000, int z1000, int ticket) {
        int eid = spawn(x1000 / 1000.0, y1000 / 1000.0, z1000 / 1000.0, Entities.PLAYER, 0);
        if (eid >= 0) joins++;
        world.joins.complete(ticket, eid);
    }

    void despawn(int slot) {
        int eid = (int) table.id(slot);
        removeSlot(slot);
        dir.free(eid);
    }

    void removeSlot(int slot) {
        long moved = table.remove(slot);
        if (moved >= 0) dir.setSlot((int) moved, slot);
    }

    /** Hand the entity in {@code slot} to region {@code dst}. Returns true if it left this region. */
    boolean migrate(int slot, int dst) {
        int eid = (int) table.id(slot);
        long cur = dir.ownership(eid);
        if (Ownership.state(cur) != Ownership.OWNED || Ownership.region(cur) != id) {
            stateViolations++;
            return false;
        }
        long transit = Ownership.pack(Ownership.IN_TRANSIT, dst, (int) (epoch + 1));
        if (!dir.casOwnership(eid, cur, transit)) {
            stateViolations++;
            return false;
        }
        MemorySegment m = begin(Msg.TRANSFER);
        m.set(I, Msg.DST, dst);
        m.set(L, Msg.WORD, transit);
        table.writeRecord(slot, m, Msg.BODY);
        if (!world.regions[dst].inbox(epoch).offer(m, 0, Msg.BYTES)) {
            // Target inbox full: roll ownership back and keep the entity; physics retries next tick.
            if (!dir.casOwnership(eid, transit, cur)) stateViolations++;
            transferCancelled++;
            return false;
        }
        removeSlot(slot);
        transfersOut++;
        return true;
    }

    /**
     * Commit-phase only (single-threaded): move an entity straight into {@code to}'s table (used by
     * split/merge). Returns true if moved.
     */
    public boolean rehome(int slot, Region to, long epoch) {
        if (to.table.isFull()) return false;
        int eid = (int) table.id(slot);
        long cur = dir.ownership(eid);
        if (!dir.casOwnership(eid, cur, Ownership.pack(Ownership.OWNED, to.id, (int) epoch))) {
            stateViolations++;
            return false;
        }
        table.writeRecord(slot, scratch, 0);
        int s = to.table.addRecord(scratch, 0);
        dir.setSlot(eid, s);
        removeSlot(slot);
        return true;
    }

    // ---- receiving ---------------------------------------------------------------------------------------------

    private void handleMessage(MemorySegment seg, long off) {
        int kind = seg.get(I, off + Msg.KIND);
        switch (kind) {
            case Msg.TRANSFER -> receiveTransfer(seg, off);
            case Msg.PROBE -> {
                if (probeCount < probeLog.length) {
                    probeLog[probeCount++] = (seg.get(L, off + Msg.WORD) << 32) | (epoch & 0xFFFF_FFFFL);
                }
            }
            case Msg.INV_DELIVER -> {
                int eid = seg.get(I, off + Msg.A);
                if (ownsEntity(eid)) {
                    droppedItems += world.players.insert(eid, seg.get(I, off + Msg.B), seg.get(I, off + Msg.C));
                } else {
                    forward(seg, off, world.ownerOfEntity(eid));
                }
            }
            case Msg.INPUT -> {
                int eid = seg.get(I, off + Msg.BODY + Input.ENTITY);
                if (ownsEntity(eid)) {
                    applyInput(seg, off + Msg.BODY, eid);
                } else {
                    forward(seg, off, world.ownerOfEntity(eid));
                }
            }
            default -> {
                int owner = world.route(seg, off);
                if (owner != id) {
                    forward(seg, off, owner);
                } else {
                    Sim.applyOwned(this, seg, off, kind);
                }
            }
        }
    }

    private void receiveTransfer(MemorySegment seg, long off) {
        long word = seg.get(L, off + Msg.WORD);
        int eid = (int) seg.get(L, off + Msg.BODY + EntityRecord.ID);
        if (table.isFull()) {
            // No room: keep it in transit and look again next epoch. Its ownership word stays unchanged.
            transferDeferred++;
            if (!inbox(epoch).offer(seg, off, Msg.BYTES) && !overflow.offer(seg, off, Msg.BYTES)) stateViolations++;
            return;
        }
        if (dir.casOwnership(eid, word, Ownership.pack(Ownership.OWNED, id, (int) epoch))) {
            int slot = table.addRecord(seg, off + Msg.BODY);
            dir.setSlot(eid, slot);
            transfersIn++;
        } else {
            transferRejects++; // stale or duplicate record: someone else already holds this entity
        }
    }

    boolean ownsEntity(int eid) {
        if (eid < 0 || eid >= dir.capacity()) return false;
        long w = dir.ownership(eid);
        return Ownership.state(w) == Ownership.OWNED && Ownership.region(w) == id;
    }

    private void handleInput(MemorySegment seg, long off) {
        inputs++;
        int kind = seg.get(I, off + Input.KIND);
        if (kind == Input.PROBE_EMIT) {
            int target = seg.get(I, off + Input.A);
            MemorySegment m = begin(Msg.PROBE);
            m.set(L, Msg.WORD, epoch);
            send(target);
            return;
        }
        if (kind == Input.SET_BLOCK) {
            Sim.setBlockAndUpdate(this, seg.get(I, off + Input.X), seg.get(I, off + Input.Y),
                    seg.get(I, off + Input.Z), seg.get(I, off + Input.A));
            return;
        }
        if (kind == Input.JOIN) {
            join(seg.get(I, off + Input.X), seg.get(I, off + Input.Y), seg.get(I, off + Input.Z), seg.get(I, off + Input.A));
            return;
        }
        int eid = seg.get(I, off + Input.ENTITY);
        if (ownsEntity(eid)) {
            applyInput(seg, off, eid);
            return;
        }
        int owner = world.ownerOfEntity(eid);
        if (owner < 0) return;
        MemorySegment m = begin(Msg.INPUT);
        MemorySegment.copy(seg, off, m, Msg.BODY, Input.BYTES);
        forwarded++;
        send(owner);
    }

    private void applyInput(MemorySegment seg, long off, int eid) {
        int slot = dir.slot(eid);
        int x = seg.get(I, off + Input.X), y = seg.get(I, off + Input.Y), z = seg.get(I, off + Input.Z);
        int a = seg.get(I, off + Input.A);
        switch (seg.get(I, off + Input.KIND)) {
            case Input.MOVE -> table.setVel(slot, a / 1000f, 0f, seg.get(I, off + Input.B) / 1000f);
            case Input.DIG -> Sim.dig(this, eid, x, y, z);
            case Input.PLACE -> Sim.place(this, eid, x, y, z, a);
            case Input.CHEST -> {
                int count = seg.get(I, off + Input.C);
                if (count > 0) Sim.takeFromChest(this, eid, a, seg.get(I, off + Input.B), count);
                else if (count < 0) Sim.putIntoChest(this, eid, a, -count);
            }
            case Input.IGNITE -> Sim.spawnTnt(this, x + 0.5, y, z + 0.5, Math.max(1, a));
            case Input.POSITION -> {
                if ((a & Input.NO_POSITION) == 0) {
                    table.setPos(slot,
                            Math.clamp(x / 1000.0, 0.5, world.sizeX() - 0.5),
                            Math.clamp(y / 1000.0, world.blocks.minY() + 1.0, world.blocks.maxYExclusive() - 1.0),
                            Math.clamp(z / 1000.0, 0.5, world.sizeZ() - 0.5));
                }
                if (table.type(slot) == Entities.PLAYER) {
                    if ((a & Input.HAS_ROTATION) != 0) {
                        table.setAux1(slot, seg.get(I, off + Input.B)); // yaw (float bits)
                        table.setAux2(slot, seg.get(I, off + Input.C)); // pitch (float bits)
                    }
                    table.setFlags(slot, (a & Input.ON_GROUND) != 0 ? Entities.FLAG_ON_GROUND : 0);
                }
            }
            case Input.LEAVE -> {
                if (table.type(slot) == Entities.PLAYER) {
                    droppedItems += world.players.clear(eid); // the player's items drop where they stood
                    despawn(slot);
                    leaves++;
                }
            }
            default -> { }
        }
    }

    // ---- commit-phase helpers (single-threaded) -------------------------------------------------------------

    /** Move pending messages and inputs into {@code to} (region merge). Anything that does not fit stays here. */
    public void drainInto(Region to, long epoch) {
        moveRing(inbox(epoch), to.inbox(epoch));
        moveRing(inbox(epoch - 1), to.inbox(epoch));
        moveRing(overflow, to.overflow);
        moveRing(ingress, to.ingress);
        moveRing(updates, to.updates);
    }

    /** Commit phase: re-route everything held by this retired slot to the current owners. */
    public void forwardRetired(long epoch) {
        this.epoch = epoch;
        rerouteRing(inbox(epoch));
        rerouteRing(inbox(epoch - 1));
        rerouteRing(overflow);
        int n = ingress.size();
        for (int i = 0; i < n; i++) {
            if (!take(ingress)) break;
            int owner = world.routeInput(retry, 0);
            if (owner >= 0 && owner != id && !world.regions[owner].ingress.offer(retry, 0, Input.BYTES)) {
                ingress.offer(retry, 0, Input.BYTES); // retry next commit
            }
        }
    }

    /** Copy the next record of {@code ring} into {@link #retry} and free its slot. */
    private boolean take(OffHeapRing ring) {
        long pos = ring.tryAcquire();
        if (pos < 0) return false;
        MemorySegment.copy(ring.segment(), ring.payloadOffset(pos), retry, 0, ring.payloadBytes());
        ring.release(pos);
        return true;
    }

    private void rerouteRing(OffHeapRing ring) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            if (!take(ring)) break;
            int dst = world.route(retry, 0);
            if (dst == id || dst < 0 || !world.regions[dst].inbox(epoch).offer(retry, 0, Msg.BYTES)) {
                if (!overflow.offer(retry, 0, Msg.BYTES)) undeliverable(retry, 0);
            }
        }
    }

    private void moveRing(OffHeapRing from, OffHeapRing to) {
        int n = from.size();
        for (int i = 0; i < n; i++) {
            if (!take(from)) break;
            if (!to.offer(retry, 0, from.payloadBytes())) from.offer(retry, 0, from.payloadBytes());
        }
    }

    /** Diagnostic snapshot of ring occupancy (allocates; not for the tick path). */
    public String describeRings() {
        return "region " + id + " state=" + state.get() + " inbox0=" + inbox[0].size() + " inbox1=" + inbox[1].size()
                + " overflow=" + overflow.size() + " ingress=" + ingress.size() + " updates=" + updates.size()
                + " entities=" + table.count();
    }

    public int pendingMessages() {
        return inbox[0].size() + inbox[1].size() + overflow.size() + ingress.size() + updates.size();
    }

    // ---- metrics access (read after quiescing) ------------------------------------------------------------------

    public long sampleCount() { return sampleCount; }

    /** Copy up to the last {@code samples.length} tick durations (ns) into {@code out}; returns count. */
    public int copySamples(long[] out) {
        int n = (int) Math.min(sampleCount, samples.length);
        long start = sampleCount - n;
        for (int i = 0; i < n && i < out.length; i++) out[i] = samples[(int) ((start + i) & sampleMask)];
        return Math.min(n, out.length);
    }

    public int probeCount() { return probeCount; }
    public long probe(int i) { return probeLog[i]; }
    public void clearProbes() { probeCount = 0; }
}
