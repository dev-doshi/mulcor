# Mulcor

A prototype of a regionized, lock-free, zero-allocation Minecraft server core for many-core CPUs. Minestom is used
only as a protocol and serialization library (packet ids, block-state registry, and palette codec as the test
oracle). Its server, instance manager and tick loop are never started.

## Modules

| Module | Contents |
|---|---|
| `mulcor-memory` | FFM (`MemorySegment` / `VarHandle`) primitives: the MPMC `OffHeapRing`, the ABA-safe `TaggedFreeList`, sparse `BlockStorage` with epoch-based section reclamation, the SoA `EntityTable`, the `EntityDirectory` ownership words, the CAS-slotted `OffHeapInventory`, and `AllocationMeter` |
| `mulcor-core` | `Engine` (ForkJoin tick driver with LPT claiming and a single-threaded commit phase), `Region`, the Hilbert/AABB `Partition` with a shape invariant, cross-region messaging, entity hand-off, and mechanics (mining, A*, TNT, chests, redstone) |
| `mulcor-net` | Netty (epoll/kqueue/NIO) ingress that decodes packets field by field straight into region rings, lock-free `TokenBucket` backpressure, and an off-heap → direct-buffer `ChunkEncoder` with zlib |
| `mulcor-harness` | `HeadlessVirtualClientProvider`, `ClientFlood`, the stress scenario, HdrHistogram `TickReport`, the JFR `LockAudit`, `ReportMain`, and the JMH suites |

## Core invariants (all enforced by tests)

- **One thread per region per epoch.** A worker must win the region's `SCHEDULED → RUNNING` CAS (`StateWord`) before touching it.
- **Cross-region effects land exactly one epoch after they are sent.** Inboxes are double-buffered by epoch parity. Redstone and physics are eventually consistent: one epoch per boundary crossed (see `dev.mulcor.core.region` package docs).
- **No entity duplication.** Ownership moves `OWNED(src) → IN_TRANSIT(dst, e+1) → OWNED(dst)`, one CAS per step, so replayed or duplicated transfer records are rejected.
- **No item duplication.** Every chest and block has exactly one owning region, and every mutation is routed there. Blocks + items + dropped + destroyed is conserved exactly.
- **No locks.** A bytecode audit (`NoLocksTest`) finds no monitors or `j.u.c.locks` usage, and a JFR audit during stress finds no monitor enters, waits or lock parks on simulation threads.
- **Zero allocation.** The steady-state tick allocates 0 heap bytes under static and full load. Network decode and chunk encode/compress also allocate 0.

## Running

The JDK is 26 (a preview-compiled class needs the exact JDK that compiled it). `java` is not on PATH on this machine:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
```

```bash
./gradlew check            # unit + stress + zero-alloc (G1 and Epsilon) gates + jcstress quick
```

```bash
./gradlew fullValidation   # check + full jcstress + all JMH + build/reports/mulcor/summary.{md,json}
```

Useful properties:
- `-Pstrict`: fail on any tick > 5 ms.
- `-Pmulcor.workers=N`: override the worker count.
- `-Pjmh.include=Tick`: run only matching JMH benchmarks.
- `-Pjmh.args='-prof gc'`: extra JMH arguments.
- `-Pjcstress.filter=Ring`: run only matching jcstress tests.
- `-Pmulcor.jvmArgs='...'`: extra JVM flags for test JVMs.

## Known limitations

- Measured on an 8-core Apple M1 (4 performance + 4 efficiency cores). Scaling to 64+ threads is a design property that has not been measured.
- Mechanics are simplified models, not vanilla-accurate. There is no login or configuration protocol; a connection is bound to a pre-spawned entity.
- Compressed inbound frames are counted as cold and not decoded. Hot packets are always below the compression threshold.
- Under overload, requests that find both the target inbox and the sender's overflow ring full are shed. Item-carrying messages are shed as counted "dropped items", so conservation still holds.
- jcstress and the tests give strong evidence of correctness, not formal proof.
