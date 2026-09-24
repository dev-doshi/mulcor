package dev.mulcor.core;

import dev.mulcor.core.grid.Partition;
import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Input;
import dev.mulcor.core.region.Region;
import dev.mulcor.memory.AllocationMeter;
import dev.mulcor.memory.EntityTable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The regionized tick engine.
 *
 * <p>One tick (epoch) works like this:
 * <ol>
 *   <li>The driver thread submits a pre-allocated {@link TickRoot} to the {@link ForkJoinPool}.</li>
 *   <li>The root forks one pre-allocated {@link TickWorker} per worker thread.</li>
 *   <li>Workers claim regions from a shared atomic index over the schedule, which is sorted
 *       longest-processing-time first. Every worker always takes the heaviest remaining region, so a slow
 *       (efficiency) core only ever delays the region it is running, never the whole queue.</li>
 *   <li>The last task to finish runs the commit phase alone ({@link CountedCompleter#onCompletion}) and then
 *       unparks the driver.</li>
 * </ol>
 * No locks or monitors are involved. Tasks are reused through {@code reinitialize()}, so a steady-state tick
 * allocates nothing on the heap.
 */
public final class Engine implements AutoCloseable {
    private static final VarHandle CLAIM;
    static {
        try {
            CLAIM = MethodHandles.lookup().findVarHandle(Engine.class, "claim", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public final EngineConfig cfg;
    public final World world;
    private final ForkJoinPool pool;
    /** Worker threads, registered lock-free by the pool's thread factory (it may replace a dead worker). */
    private final Thread[] workerThreads;
    private final AtomicInteger workerThreadCount = new AtomicInteger();
    private final TickRoot root;
    private final TickWorker[] workers;

    private final int[] schedule;
    private int scheduleCount;
    @SuppressWarnings("unused") // via CLAIM
    private volatile int claim;

    private long epoch;
    private volatile long completedEpoch;
    private volatile Thread driver;
    private volatile Throwable failure;
    private volatile boolean aiEnabled = true;
    private boolean closed;
    private boolean rootSubmitted;

    private final long[] epochSamples;
    private final int epochMask;
    private long epochCount;
    private final long[] cellCost;
    private final double[] scratchCost;

    public long splits, merges, rehomed, reclaimedSections, rebalances;

    public Engine(EngineConfig cfg) {
        this.cfg = cfg;
        this.world = new World(cfg);
        world.generate();
        world.startLight();
        this.schedule = new int[cfg.maxRegions()];
        this.cellCost = new long[world.partition.cells()];
        this.scratchCost = new double[cfg.maxRegions()];
        int cap = Integer.highestOneBit(Math.max(2, cfg.sampleCapacity()));
        this.epochSamples = new long[cap];
        this.epochMask = cap - 1;
        this.workerThreads = new Thread[cfg.workers() * 4];
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = p -> {
            ForkJoinWorkerThread t = new ForkJoinWorkerThread(null, p, true) {
                @Override
                protected void onStart() {
                    setName("mulcor-region-" + getPoolIndex());
                }
            };
            int i = workerThreadCount.getAndIncrement();
            if (i < workerThreads.length) workerThreads[i] = t;
            return t;
        };
        this.pool = new ForkJoinPool(cfg.workers(), factory, null, false, cfg.workers(), cfg.workers(), 1,
                null, 1, TimeUnit.HOURS);
        this.root = new TickRoot();
        this.workers = new TickWorker[cfg.workers()];
        for (int i = 0; i < workers.length; i++) workers[i] = new TickWorker(root);
        rebuildSchedule();
    }

    // ---- public API --------------------------------------------------------------------------------------------

    public long epoch() { return epoch; }
    public int workers() { return cfg.workers(); }
    /** Snapshot of the simulation worker threads created so far. */
    public Thread[] workerThreads() {
        return Arrays.copyOf(workerThreads, Math.min(workerThreadCount.get(), workerThreads.length));
    }
    public int activeRegions() { return world.partition.activeCount(); }
    public void setAiEnabled(boolean enabled) { aiEnabled = enabled; }

    /** Run one tick on the calling (driver) thread and return its wall-clock duration in nanoseconds. */
    public long tick() {
        long t0 = System.nanoTime();
        long e = ++epoch;
        Thread me = Thread.currentThread();
        if (driver != me) driver = me;
        CLAIM.setRelease(this, 0);
        // tryComplete() runs onCompletion (which releases us) *before* it marks the root done. Resubmitting
        // before that mark lands would let the stale completion swallow the new submission, so wait for it.
        if (rootSubmitted) {
            while (!root.isDone()) Thread.onSpinWait();
        }
        root.reinitialize();
        rootSubmitted = true;
        pool.execute(root);
        awaitEpoch(e);
        long dt = System.nanoTime() - t0;
        epochSamples[(int) (epochCount++ & epochMask)] = dt;
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("region tick failed in epoch " + e, f);
        }
        return dt;
    }

    public void run(int ticks) {
        for (int i = 0; i < ticks; i++) tick();
    }

    /** Spin briefly (the common case completes within microseconds), then park. Parking is not a lock. */
    private void awaitEpoch(long e) {
        long spinUntil = System.nanoTime() + 50_000;
        while (completedEpoch != e) {
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, 1_000_000);
            }
        }
    }

    /** Spawn a bot at a surface position. Only between ticks (from the driver thread). */
    public int spawnBot(int x, int z, int role) {
        int y = world.surfaceY;
        while (y + 1 < world.blocks.maxYExclusive() && Blocks.isSolid(world.blocks.get(x, y, z))) y++;
        Region r = world.regions[world.ownerOfBlock(x, z)];
        int eid = r.spawn(x + 0.5, y, z + 0.5, Entities.BOT, role);
        if (eid >= 0) {
            world.players.insert(eid, Blocks.DIRT, 16);
        }
        return eid;
    }

    /** Thread-safe: route a client input to the region owning its entity. Returns false on backpressure. */
    public boolean submitInput(MemorySegment seg, long off) {
        int owner = world.ownerOfEntity(seg.get(ValueLayout.JAVA_INT, off + Input.ENTITY));
        return owner >= 0 && world.regions[owner].ingress.offer(seg, off, Input.BYTES);
    }

    /** A safe place to stand at block column (x, z): the first air block with two air blocks above it. */
    public int surfaceAt(int x, int z) {
        var b = world.blocks;
        int y = b.minY();
        b.beginExternalRead(); // may run on a network thread, concurrently with the commit phase
        try {
            for (int top = b.maxYExclusive() - 1; top >= b.minY(); top--) {
                if (Blocks.isSolid(b.getShared(x, top, z))) {
                    y = top + 1;
                    break;
                }
            }
        } finally {
            b.endExternalRead();
        }
        return Math.min(y, b.maxYExclusive() - 2);
    }

    /**
     * Thread-safe, non-blocking: ask the region owning (x, z) to spawn a network player there. {@code scratch} is
     * a caller-owned {@link Input#BYTES}-byte segment. Returns a join ticket to {@link #pollJoin poll}, or -1 if
     * no ticket is free or the region's ingress ring is full (retry later).
     */
    public int requestJoin(MemorySegment scratch, double x, double y, double z) {
        int ticket = world.joins.reserve();
        if (ticket < 0) return -1;
        scratch.fill((byte) 0);
        scratch.set(ValueLayout.JAVA_INT, Input.KIND, Input.JOIN);
        scratch.set(ValueLayout.JAVA_INT, Input.ENTITY, -1);
        scratch.set(ValueLayout.JAVA_INT, Input.X, (int) Math.round(x * 1000));
        scratch.set(ValueLayout.JAVA_INT, Input.Y, (int) Math.round(y * 1000));
        scratch.set(ValueLayout.JAVA_INT, Input.Z, (int) Math.round(z * 1000));
        scratch.set(ValueLayout.JAVA_INT, Input.A, ticket);
        if (!world.regions[world.routeInput(scratch, 0)].ingress.offer(scratch, 0, Input.BYTES)) {
            world.joins.release(ticket);
            return -1;
        }
        return ticket;
    }

    /**
     * The entity id for a join ticket, or {@link JoinTickets#PENDING} / {@link JoinTickets#FAILED}. A completed
     * ticket (id or failure) is released by this call and must not be polled again.
     */
    public int pollJoin(int ticket) {
        int r = world.joins.poll(ticket);
        if (r != JoinTickets.PENDING) world.joins.release(ticket);
        return r;
    }

    /** Thread-safe: remove a network player whose connection closed. Returns false on backpressure (retry). */
    public boolean requestLeave(MemorySegment scratch, int entity) {
        scratch.fill((byte) 0);
        scratch.set(ValueLayout.JAVA_INT, Input.KIND, Input.LEAVE);
        scratch.set(ValueLayout.JAVA_INT, Input.ENTITY, entity);
        return submitInput(scratch, 0);
    }

    /** Thread-safe: deliver a region-addressed input (SET_BLOCK, PROBE_EMIT) to a specific region. */
    public boolean submitToRegion(int region, MemorySegment seg, long off) {
        return world.regions[region].ingress.offer(seg, off, Input.BYTES);
    }

    // ---- scheduling: LPT claim ---------------------------------------------------------------------------------

    private final class TickRoot extends CountedCompleter<Void> {
        @Override
        public void compute() {
            int n = scheduleCount;
            for (int i = 0; i < n; i++) {
                if (!world.regions[schedule[i]].casState(Region.IDLE, Region.SCHEDULED)) {
                    failure = new IllegalStateException("region " + schedule[i] + " not IDLE at schedule");
                }
            }
            int helpers = Math.min(workers.length, n);
            setPendingCount(helpers);
            for (int i = 0; i < helpers; i++) {
                workers[i].reinitialize();
                workers[i].fork();
            }
            tryComplete();
        }

        @Override
        public void onCompletion(CountedCompleter<?> caller) {
            try {
                commit();
            } catch (Throwable t) {
                failure = t;
            }
            release();
        }

        @Override
        public boolean onExceptionalCompletion(Throwable ex, CountedCompleter<?> caller) {
            failure = ex;
            release();
            return true;
        }

        private void release() {
            completedEpoch = epoch;
            Thread d = driver;
            if (d != null) LockSupport.unpark(d);
        }
    }

    private final class TickWorker extends CountedCompleter<Void> {
        TickWorker(TickRoot root) {
            super(root);
        }

        @Override
        public void compute() {
            long e = epoch;
            boolean ai = aiEnabled;
            for (;;) {
                int i = (int) CLAIM.getAndAdd(Engine.this, 1);
                if (i >= scheduleCount) break;
                try {
                    world.regions[schedule[i]].tick(e, ai);
                } catch (Throwable t) {
                    failure = t;
                }
            }
            tryComplete();
        }
    }

    // ---- commit phase (single-threaded) ------------------------------------------------------------------------

    private void commit() {
        reclaimedSections += world.blocks.reclaim();
        Region[] regions = world.regions;
        // Light: hand every region's block changes of this epoch to the light thread (vanilla lights
        // asynchronously too, on ThreadedLevelLightEngine).
        for (Region r : regions) r.drainLight(world.lightService);
        for (Region r : regions) {
            if (!r.isActive() && r.pendingMessages() > 0) r.forwardRetired(epoch);
        }
        if (cfg.rebalanceInterval() > 0 && epoch % cfg.rebalanceInterval() == 0) rebalance();
        rebuildSchedule();
    }

    /** Active regions sorted by cost, heaviest first (insertion sort: few regions, no allocation). */
    private void rebuildSchedule() {
        int n = 0;
        Region[] regions = world.regions;
        for (int r = 0; r < regions.length; r++) {
            if (!world.partition.isActive(r)) continue;
            double cost = regions[r].costEwma;
            int i = n++;
            while (i > 0 && scratchCost[i - 1] < cost) {
                schedule[i] = schedule[i - 1];
                scratchCost[i] = scratchCost[i - 1];
                i--;
            }
            schedule[i] = r;
            scratchCost[i] = cost;
        }
        scheduleCount = n;
    }

    /**
     * Cost-weighted rebalance. Split a region whose measured tick cost exceeds
     * {@code budget / (2 × workers)}, so no single region can stretch the epoch barrier beyond half the budget
     * even on a slow core. Also split when a region's entity table is 75% full. Merge adjacent regions whose
     * combined cost is under a quarter of that, keeping at least {@code 2 × workers} regions for parallelism.
     * Partition enforces the shape rule. At most 4 splits and 4 merges happen per pass, to limit churn.
     */
    private void rebalance() {
        rebalances++;
        Partition p = world.partition;
        Region[] regions = world.regions;
        Arrays.fill(cellCost, 0);
        for (int r = 0; r < regions.length; r++) {
            if (!p.isActive(r)) continue;
            EntityTable t = regions[r].table;
            for (int s = 0; s < t.count(); s++) {
                cellCost[world.cellOf((int) t.x(s), (int) t.z(s))]++;
            }
        }
        double splitTarget = (double) cfg.tickBudgetNanos() / (2.0 * cfg.workers());
        double mergeTarget = splitTarget / 4;
        int capacityLimit = cfg.regionEntityCapacity() * 3 / 4;
        int ops = 0;
        int n = scheduleCount;
        for (int i = 0; i < n && ops < 4; i++) {
            Region r = regions[schedule[i]];
            if (r.costEwma <= splitTarget && r.table.count() <= capacityLimit) continue;
            int fresh = p.split(r.id, cellCost);
            if (fresh < 0) continue;
            Region nr = regions[fresh];
            nr.casState(Region.INACTIVE, Region.IDLE);
            moveEntitiesOwnedBy(r, nr);
            r.costEwma /= 2;
            nr.costEwma = r.costEwma;
            splits++;
            ops++;
        }
        int minRegions = Math.min(p.cells(), 2 * cfg.workers());
        ops = 0;
        for (int a = 0; a < regions.length && ops < 4; a++) {
            if (!p.isActive(a)) continue;
            for (int b = 0; b < regions.length && ops < 4 && p.activeCount() > minRegions; b++) {
                if (a == b || !p.isActive(b)) continue;
                Region ra = regions[a], rb = regions[b];
                if (ra.costEwma + rb.costEwma >= mergeTarget) continue;
                if (ra.table.count() + rb.table.count() > cfg.regionEntityCapacity() / 2) continue;
                if (!p.merge(a, b)) continue;
                moveEntitiesOwnedBy(rb, ra);
                rb.drainInto(ra, epoch);
                rb.casState(Region.IDLE, Region.INACTIVE);
                ra.costEwma += rb.costEwma;
                rb.costEwma = 0;
                merges++;
                ops++;
            }
        }
    }

    /** Move every entity of {@code from} whose cell now belongs to {@code to}. */
    private void moveEntitiesOwnedBy(Region from, Region to) {
        EntityTable t = from.table;
        int s = 0;
        while (s < t.count()) {
            if (world.ownerOfBlock((int) t.x(s), (int) t.z(s)) == to.id && from.rehome(s, to, epoch)) {
                rehomed++;
            } else {
                s++;
            }
        }
    }

    // ---- metrics -----------------------------------------------------------------------------------------------

    /** Copy the most recent tick durations (ns) into {@code out}; returns how many. */
    public int copyEpochSamples(long[] out) {
        int n = (int) Math.min(epochCount, epochSamples.length);
        long start = epochCount - n;
        for (int i = 0; i < n && i < out.length; i++) out[i] = epochSamples[(int) ((start + i) & epochMask)];
        return Math.min(n, out.length);
    }

    public void resetEpochSamples() {
        epochCount = 0;
    }

    /** Heap bytes allocated so far by all simulation worker threads. */
    public long workerAllocatedBytes() {
        long sum = 0;
        int n = Math.min(workerThreadCount.get(), workerThreads.length);
        for (int i = 0; i < n; i++) {
            long b = AllocationMeter.thread(workerThreads[i].threadId());
            if (b > 0) sum += b;
        }
        return sum;
    }

    public Stats stats() {
        Stats s = new Stats();
        for (Region r : world.regions) {
            s.entities += r.isActive() ? r.table.count() : 0;
            s.transfersOut += r.transfersOut;
            s.transfersIn += r.transfersIn;
            s.transferRejects += r.transferRejects;
            s.transferDeferred += r.transferDeferred;
            s.transferCancelled += r.transferCancelled;
            s.droppedItems += r.droppedItems;
            s.destroyedBlocks += r.destroyedBlocks;
            s.explosions += r.explosions;
            s.forwarded += r.forwarded;
            s.inputs += r.inputs;
            s.messages += r.messages;
            s.overflowed += r.overflowed;
            s.undeliverable += r.undeliverable;
            s.stateViolations += r.stateViolations;
            s.updatesDropped += r.updatesDropped;
            s.joins += r.joins;
            s.leaves += r.leaves;
            s.blockUpdates += r.blockUpdates;
            s.crossUpdates += r.crossUpdates;
            s.scheduledTicks += r.scheduledTicks;
            s.redstoneChanges += r.redstoneChanges;
            s.tntPrimed += r.tntPrimed;
            s.updatesDeferred += r.updatesDeferred;
            s.ticksDropped += r.ticksDropped;
            s.pushes += r.pushes;
            s.borderPushes += r.borderPushes;
            s.destroyedOther += r.destroyedOther;
            if (r.isActive()) s.pendingTicks += r.scheduledTickCount();
            s.pending += r.pendingMessages();
        }
        s.activeRegions = world.partition.activeCount();
        s.splits = splits;
        s.merges = merges;
        s.rehomed = rehomed;
        return s;
    }

    public static final class Stats {
        public long entities, transfersOut, transfersIn, transferRejects, transferDeferred, transferCancelled;
        public long droppedItems, destroyedBlocks, explosions, forwarded, inputs, messages, overflowed;
        public long undeliverable, stateViolations, updatesDropped, pending, joins, leaves;
        public long blockUpdates, crossUpdates, scheduledTicks, redstoneChanges, tntPrimed, updatesDeferred, ticksDropped;
        public long pushes, borderPushes, pendingTicks, destroyedOther;
        public long activeRegions, splits, merges, rehomed;

        @Override
        public String toString() {
            return "Stats{entities=" + entities + ", regions=" + activeRegions + ", splits=" + splits
                    + ", merges=" + merges + ", rehomed=" + rehomed + ", transfersOut=" + transfersOut
                    + ", transfersIn=" + transfersIn + ", rejects=" + transferRejects + ", deferred="
                    + transferDeferred + ", cancelled=" + transferCancelled + ", messages=" + messages
                    + ", forwarded=" + forwarded + ", inputs=" + inputs + ", explosions=" + explosions
                    + ", destroyed=" + destroyedBlocks + ", dropped=" + droppedItems + ", overflowed="
                    + overflowed + ", undeliverable=" + undeliverable + ", violations=" + stateViolations
                    + ", updatesDropped=" + updatesDropped + ", joins=" + joins + ", leaves=" + leaves
                    + ", blockUpdates=" + blockUpdates + ", crossUpdates=" + crossUpdates + ", scheduledTicks="
                    + scheduledTicks + ", redstoneChanges=" + redstoneChanges + ", tntPrimed=" + tntPrimed
                    + ", updatesDeferred=" + updatesDeferred + ", ticksDropped=" + ticksDropped + ", pushes=" + pushes
                    + ", borderPushes=" + borderPushes + ", destroyedOther=" + destroyedOther + ", pendingTicks=" + pendingTicks
                    + ", pending=" + pending + "}";
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pool.shutdownNow();
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        world.close();
    }
}
