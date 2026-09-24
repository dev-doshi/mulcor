# Mulcor Architecture Roadmap

> Phase 0 design document: from the regionized, lock-free, zero-allocation prototype to a vanilla-compatible Minecraft Java server. Progress against it is tracked in **Status** below.

## Status (branch `main-7kmdaq`)

| Milestone | State | What is in |
|---|---|---|
| M1 | Mostly done | Join, configuration, play, chunk streaming; encryption and online mode; vanilla state ids from the generated registry; real light in chunk packets; `HotPathAuditTest`. **Egress:** per-region `BroadcastJournal` of block changes and block events, forwarded by `PlaySession` (block updates, piston animation events); per-region seqlocked network entity snapshots, entity tracking (spawn with LpVec3 velocity, position sync, remove) and tab lists. Missing: chat and the cold lane (`ColdOpRing`), entity metadata beyond defaults, section-batched block updates. |
| M2 | Mostly done | NBT codec, Anvil region files, chunk codec, `level.dat`, async sharded chunk I/O, off-heap light engine with incremental updates. |
| M3 | Mostly done | Block updates in vanilla order (`NeighborUpdater`, shape updates); redstone: wire, torches with burnout, repeaters, comparators (analog inputs, compare/subtract), observers, levers, buttons, lamps, TNT, falling blocks; **pistons** (block events, structure resolver, moving-piston block entities); **fluids** (water, lava, waterlogging, their own tick list); **affinity coalescing** (§3.3). Missing: pressure plates and other entity-driven components, random ticks (crops, leaves, ice), block-entity analog outputs (containers). |
| M4-M6 | Not started | Players place only dirt today; inventories and item use are M4. |

Verification: redstone traces in `RedstoneParityTest` were recorded on a vanilla server; the newer component, piston and fluid tests are derived from the vanilla code and should be replaced by recorded traces. Packet encoders are checked byte-for-byte against Minestom's serializers, and `MultiplayerTest` runs two real-socket clients.

**Decisions:**
- Cross-region parity: **affinity coalescing** plus **deadline-stamped messages** (§3).
- World generation: a pluggable `ChunkGenerator` SPI with flat and void generators, plus loading of vanilla-generated Anvil worlds. Vanilla noise generation is an unowned post-M6 track.

## Parallel work

Another session ("Multi-core Minecraft server architecture") built the vanilla join path, committed as `8905fb7`: login and configuration, `PlaySession`, `PlayWriter`, `JoinTickets`, the `BlockStorage` external-read guard, and `MulcorServer`/`VanillaClient`. It is now reworking redstone and entity physics. It is touching `Sim` (splitting it into physics and redstone classes), `Region`, `Msg`, `Blocks`, `Entities`, `EntityTable`/`EntityRecord` (a walk-intent column) and `Protocol` (state mapping), and adding a `CascadeBenchmark` JMH benchmark.

What that session has adopted from §3:
- **Deadline messages:** a `DEADLINE` field on `Msg`. A cross-border effect fires at `send_epoch + delay`.
- **Scheduled-tick queue:** a per-region **off-heap min-heap** ordered by (due epoch, priority, insertion seq). This replaces the timing wheel this document first proposed. It gives the same ordering with no bucket overflow.
- **`NEIGHBOR_UPDATE`:** it replaces `REDSTONE`. Cascades run to a fixpoint inside a region, with a per-tick budget; overflow carries over and is counted.
- **Border-entity snapshots:** double-buffered by epoch parity. Pushing is symmetric, and each region applies impulses only to its own entities.

Still owned by this roadmap: affinity coalescing (§3.3) and the generated registry (§5.1). The interim Mulcor block ids for repeater, torch, lamp and sand move to the registry in M1.

- **M1** is unblocked: the join work is committed as `8905fb7`. M1 avoids the files listed above until the redstone and physics rework lands, or coordinates with that session first.
- **M3 and M5** treat that session's `Sim` redstone and physics rework as their starting input. They move it into `mulcor-game` behind the behavior table and add affinity bonds (§3.3) on top. They do not build a competing implementation.


## 0. Baseline: what exists today

| Area | Today | Gap to vanilla |
|---|---|---|
| World | Dense, finite `BlockStorage` (`chunksX × chunksZ × sections` table); 16-bit Mulcor-private state ids (`core/Blocks.java`: 7 blocks + 16 wire states); flat generator in `World.generate()` | Unbounded sparse world, vanilla state ids, chunk load/unload, persistence |
| Regions | `Partition` over a dense cell grid (Hilbert + AABB split, cost-based split/merge); `Region.tick` = overflow retry → drain `inbox[(e-1)&1]` → ingress → `Sim.processUpdates` → `Sim.simulate` | Vanilla tick phases, scheduled ticks, block events, affinity |
| Messages | Fixed 104-B `Msg`, 10 kinds (TRANSFER, BLOCK_BREAK/PLACE, EXPLOSION, REDSTONE, INV_TAKE/PUT/DELIVER, INPUT, PROBE) | Deadline stamps, fluid/light/damage/lease kinds |
| Entities | SoA `EntityTable` (pos, vel, type, flags, 3 aux ints), CAS `Ownership` word, 64-B `EntityRecord` | Components: health, metadata, AI, equipment, bounding boxes |
| Items | `OffHeapInventory` slot = `item:32 \| count:32`, one CAS per slot | Data components (1.20.5+), windows, crafting |
| Net | Offline login + configuration via Minestom objects (`LoginHandler`); hot decode of 7 packet ids into 32-B `Input`; `PlayWriter` hand-encodes chunk, keep-alive, view centre, batch, ack; full-bright fake light | Encryption, Mojang auth, block/entity/inventory deltas to clients, ~100 more packets |
| Gates | `NoLocksTest` (bytecode), JFR `LockAudit`, alloc gates under G1 and Epsilon, jcstress (13 tests), JMH, `ReportMain` | Hot/cold boundary enforcement, vanilla conformance oracles |

Measured baseline, from `build/reports/mulcor/summary.md` on an M1 with 4 workers: 5,000 bots + 5,000 clients at 615 µs mean and 921 µs p99 tick, 0 B/tick.

Known debts to clear before M1 work begins:
- ~~The uncommitted login and play-session work~~: committed as `8905fb7`.
- `PlayWriterTest`, which `PlayWriter`'s Javadoc cites but which does not exist.

## 1. Invariants (kept, and new ones added)

Kept from the README:
- One thread per region per epoch.
- Cross-region effects land at exactly e+1.
- No entity or item duplication.
- No locks.
- 0 B/tick in steady state.

New invariants:
- **I-H (hot/cold boundary):** Hot code never calls cold code. Cold code reaches hot state only through rings or the global phase (§2).
- **I-D (deadline exactness):** Any cross-region effect with vanilla delay d ≥ 1 executes at exactly `send_epoch + d`.
- **I-A (affinity):** Two cells joined by a hard-affinity edge are never in different regions at a tick boundary. The only exception is the ≤1-rebalance transient after the edge is created (§3.3).
- **I-P (publication):** Anything a non-owner thread reads (sections, light, entity snapshots, journals) is published with release/acquire and reclaimed only after an epoch-deferred quiescence check. The existing `beginExternalRead` Dekker guard is the model.

## 2. Hybrid memory and allocation model

### 2.1 Thread classes

| Thread | Count | Path | Allocation rule | Lock rule |
|---|---|---|---|---|
| Region workers (FJ pool) | `workers` | HOT | 0 B/tick (gated) | none (bytecode + JFR) |
| Commit / **global phase** (last completer) | 1 at a time | HOT, serial | 0 B/tick when no cold work is queued; budgeted otherwise | none |
| Netty event loops, play state | `io` | HOT | 0 B per hot packet in and out (gated) | none |
| Netty event loops, login/config | same | COLD | free | none on shared state |
| Chunk service | 1 | WARM | 0 B steady state (ticket bookkeeping) | none |
| Storage I/O | `ioThreads` (sharded by `.mca` file) | COLD | bounded; pooled direct buffers | single writer per file, no locks |
| Light/gen workers (fresh chunks) | pool | WARM | 0 B per chunk after warm-up | none |
| Cold lane (commands, chat, auth, advancements, datapacks) | small pool / virtual threads | COLD | free | may lock internally, must never touch hot state directly |

### 2.2 What is hot and what is cold

- **Hot:** region ticks (blocks, fluids, redstone, entities, AI, physics, pathfinding, containers); light propagation; commit and global phase bookkeeping; hot packet decode; egress encoding (chunks, light, block, entity and inventory deltas); compression; encryption.
- **Cold:**
  - Handshake, login, RSA, Mojang HTTP auth.
  - The configuration phase.
  - Chat parsing and signing.
  - Brigadier parsing and suggestions.
  - Datapack, recipe, loot and advancement loading.
  - NBT and Anvil disk I/O.
  - Sign and book text.
  - Advancement criterion evaluation.
  - Server list pings.

### 2.3 Cold → hot handoff (no locks, no heap sharing on the hot side)

1. **`ColdOpRing`** is an off-heap MPMC ring (the existing `OffHeapRing` algorithm) of 64-B "compiled op" records. Cold threads parse and validate, then emit fixed records: SET_BLOCK, TELEPORT, GIVE, KILL_SELECTOR, SCOREBOARD_SET, and so on. The global phase drains the ring with a budget and routes each record to its owner region's ingress or inbox.
2. **`HandleTable`** covers data that is naturally heap-shaped: sign text, custom names, book pages, text components. Cold threads create the object and store it in a pre-sized `Object[]` slab indexed by an int handle. Hot code copies and compares handles only, never dereferences them. Egress threads read the pre-serialized wire bytes the cold side cached. Handles are freed epoch-deferred, using the same pattern as `BlockStorage.reclaim()`.
3. **Hot → cold `EventRing`s** carry advancement triggers, statistics deltas, chat-worthy events, log lines and death messages. Each is a fixed record. A cold consumer evaluates it; results come back through `ColdOpRing`.

### 2.4 Enforcement

- **Static checks:**
  - Extend `NoLocksTest` into `HotPathAuditTest`. It scans `@Hot`-annotated packages (core, game, memory, net `.play`) and flags `new`, `newarray`, `invokedynamic` (lambdas, string concatenation), boxing (`valueOf`) and varargs outside `@ColdOk` methods. Keep the scanner's negative-control canaries.
  - Add ArchUnit, which is already in `libs.versions.toml`. Rule: `..core..`, `..game..` and `..net.play..` must not depend on `..cold..`, `com.mojang.brigadier..` or `net.minestom..` (except `mulcor-registry` codegen output).
- **Dynamic checks:** the existing per-thread `AllocationMeter` gates, under G1 and Epsilon, extended with new scenario loads for each milestone.

## 3. Multi-region and epoch-deferred messaging expansion

### 3.1 Region tick phases (vanilla `ServerLevel.tick` order, per region)

1. Retry overflow.
2. Drain `inbox[(e-1)&1]`. Deadline messages go into the region's **scheduled-tick queue**.
3. Ingress (client inputs).
4. Block scheduled ticks due at e.
5. Fluid scheduled ticks due at e.
6. Chunk ticks: random ticks over sections whose random-tick count is > 0, and mob spawning.
7. Block events (pistons, note blocks), processed to fixpoint within the region.
8. Entities (AI → movement → collisions → hand-off).
9. Block entities (hoppers, furnaces, brewing).
10. Light queue processing.
11. Publish: seal this region's **event journal** and **entity snapshot** for e.

The **global phase** runs inside the commit, serial and budgeted:
- Section and handle reclaim.
- Chunk install and uninstall batches (§4).
- Partition rebalance, including affinity merges.
- Time, weather and world border.
- `ColdOpRing` execution (commands, functions, scoreboards).
- Schedule rebuild.

The scheduled-tick queue is an off-heap min-heap per region, ordered by (due epoch, priority, insertion seq) like vanilla's tick ordering. It is fed locally and by deadline messages.

### 3.2 Message v2

The record stays a fixed 104 B. Add a `DEADLINE` field: the target epoch, or 0 for "on receipt".

Receivers with `deadline > e` insert into the scheduled-tick queue. That makes any vanilla delay ≥ 1 exact across borders (I-D): repeaters, comparators, observers (2 gt), water (5), lava (30/10), TNT fuse, sand, and piston extension (2).

New kinds:

| Kind | Purpose |
|---|---|
| `SCHED_TICK` | pos, block, delay, priority |
| `NEIGHBOR_UPDATE` | pos, source block, source pos: the 0-delay `neighborChanged` |
| `FLUID_SPREAD` | pos, fluid state, deadline |
| `LIGHT_EDGE` | pos, level, kind (sky/block), op (raise/lower) |
| `EXPLODE_APPLY` | up to 7 packed block positions per record, plus the explosion id |
| `ENTITY_IMPULSE` / `ENTITY_DAMAGE` | eid, vector or amount, source eid, damage type |
| `INV_LEASE_REQ` / `INV_LEASE_GRANT` / `INV_LEASE_RETURN` | §3.4 |
| `HOPPER_PULL` / `HOPPER_PUSH` | container pos, slot filter, count |
| `AFFINITY_EDGE` | cellA, cellB, reason; routed to the global phase |
| `PLAYER_EVENT` | eid-addressed UI effects (title, sound) |

### 3.3 Affinity coalescing (exact vanilla timing for 0-delay interactions)

- **Affinity edges.** Each cell pair gets a counter of hard-affinity "bonds" crossing their shared chunk edge. Bonds are:
  - redstone dust ↔ any redstone component
  - piston ↔ pushable block within 12 blocks
  - double chest halves
  - hopper/dropper ↔ container
  - bed and door halves
  - observer ↔ watched block
  - rail ↔ minecart line

  A block write that creates or removes a bond across a cell boundary is detected by its owner region, which does a block-behavior-table lookup (O(1) per write, only on the boundary strip). The region then sends `AFFINITY_EDGE` ±1 to the global phase.
- **Partition rules.** `Partition.split` never cuts a bonded edge: cut candidates that sever bonds are rejected. The global phase force-merges regions that share a bonded edge, and a forced merge relaxes `maxCompactness`. A connected bonded component therefore always lives in one region.
- **Transient.** Between bond creation and the next commit (≤ 1 epoch, since merges on bonds are immediate rather than waiting for `rebalanceInterval`), the interaction runs eventually-consistent. It uses `NEIGHBOR_UPDATE` messages plus `getShared` reads, which is exactly today's model. No duplication is possible in either mode.
- **Megaregion cap.** If a bonded component exceeds `maxAffinityCells` (e.g. a huge computer), it becomes one region on one thread. That matches vanilla's single-threaded cost. Mitigation: LPT scheduling puts it first. It shows up in metrics as `affinityRegions`.

### 3.4 How each interaction uses the deferred inbox

| Interaction | Same region (or bonded) | Across a border (unbonded or transient) |
|---|---|---|
| Redstone dust | Vanilla-exact wire update order within the tick | `NEIGHBOR_UPDATE` at e+1; power read by `getShared` (stale ≤ 1 epoch) |
| Repeaters, comparators, observers | Local tick queue | `SCHED_TICK` with deadline: **exact** |
| Fluids | Local tick queue | `FLUID_SPREAD` with deadline: **exact**. The target validates against its own state on arrival (vanilla re-checks too), so no conflict. |
| Hoppers | Direct `OffHeapInventory` access | `HOPPER_PULL` to the container owner → it takes the items → `INV_DELIVER`-style reply. Items are in exactly one place or one in-flight message. The hopper cooldown (8 gt) starts on arrival. |
| Explosions | Rays read blocks with `getShared`; the owner computes the destroyed set **once** at explosion time | `EXPLODE_APPLY` carries the explicit position list (no recompute, so the set is deterministic); `ENTITY_IMPULSE`/`DAMAGE` go to entity owners; chained TNT has fuse ≥ 1, so it is exact |
| Pistons | Block events to fixpoint | Always bonded, so never cross-region beyond the transient; during the transient the piston refuses to move (vanilla-legal "blocked" outcome) |
| Entity contact and damage | Direct | Reads the neighbor's **entity snapshot** (e-1); damage and knockback via messages at e+1 |
| Containers | Direct | **Inventory lease**: the player's inventory ownership word (like `Ownership`) moves `OWNED(playerRegion) → LEASED(containerRegion)` by CAS while the window is open. Every click then executes on the container owner with both inventories local, which keeps vanilla click semantics exact. Pickups for a leased inventory are forwarded. `INV_LEASE_RETURN` on close. Verified with jcstress. |

### 3.5 Entity snapshots (cross-region and network reads)

- Each region writes `snapshot[e&1]` in phase 11. It is an off-heap array of `(cell, eid, type, x, y, z, yaw, pitch, bbox, flags)` sorted by cell, plus a per-cell index.
- The parallel session's physics rework publishes **border entities only** (id, pos, vel, box). M1 widens this to every entity, adding type, rotation and flags, because network entity tracking (§5.3) needs all of them. Physics readers can keep using a border-only view of the same buffer.
- Readers in epoch e use `snapshot[(e-1)&1]`, which nobody writes in e. This is the same parity trick as the inboxes.
- Network readers outside the tick check a per-buffer epoch stamp before and after reading, a seqlock-style "lapped" check. A lapped reader retries against the newer buffer and never blocks the writer.

## 4. Off-heap chunk and Anvil storage

### 4.1 Sparse, unbounded world

- **`ChunkDirectory`** is an off-heap open-addressing hash from packed `(cx, cz)` (2 × 22 bits) to a chunk slot.
  - Mutated **only in the global phase**, in bounded batches per epoch. Region threads and egress read it with acquire loads.
  - Removed slots are tombstoned and reclaimed after the external-read guard is clear, reusing `BlockStorage`'s Dekker handshake.
- **Chunk slot** (fixed layout from a slab):
  - section refs for 24 sections (height taken from the dimension)
  - light refs (sky and block, 26 sections each)
  - heightmaps (4 × 256 × u16)
  - per-section counts: non-air, random-tick, fluid, block-entity
  - status word, dirty bits, inhabited time, and the cell id
- **Partition over sparse cells.** Cells become keys, not a dense array: `(cellX, cellZ)` → a 64-bit Hilbert key on a 2^22 grid. `Partition` keeps the Hilbert-sorted list of *loaded* cells per region. Cell add and remove happen in the global phase, and the split and merge algorithms stay unchanged over that list. `World.ownerOfBlock` becomes a cell-directory lookup, with a per-region last-hit cache for the hot path.
- **Block ids become vanilla global state ids** (< 65,536; asserted at startup), generated by `mulcor-registry`. `Protocol.vanillaState` becomes the identity mapping.
- **Section representations:**
  - **HOT_RAW:** 8 KiB of u16s, today's format. Used for ticking chunks.
  - **COLD_PALETTED:** the vanilla wire-format paletted container. Used for loaded but non-ticking chunks and fresh loads. It is readable by the encoder with no transcoding (memcpy to the wire).
  - Promotion to raw on the first write or when the chunk starts ticking. The owner copies into a new raw section and swaps the ref with a release store; the old section goes to `retired`, then `reclaim()`. This is the existing mechanism. Demotion runs in the background on idle chunks, budgeted per tick.

### 4.2 Chunk lifecycle and tickets

`UNLOADED → LOADING (I/O) → STAGED (off-heap, unpublished) → INSTALLED (global phase publishes refs; the owner region adopts it) → TICKING (ticket level) → UNLOADING (snapshot for save) → UNLOADED`

- **Tickets** use vanilla ticket levels (player, forced, portal, spawn, start) in an off-heap per-chunk level array. They are owned by the single **chunk service** thread, which consumes a `TicketDeltaRing` fed by regions and sessions.
- The chunk service issues loads to I/O shards and install and unload batches to the global phase.
- **Backpressure:** section-pool low-water triggers LRU unload of chunks with no ticket.

### 4.3 Anvil I/O (never stalls a tick worker)

- **Shards.** I/O threads own `.mca` files: `file(rx, rz) → shard = hash % ioThreads`. Each file therefore has one writer and needs no locks. Each shard keeps an in-memory sector-allocation bitmap and header per open file, with an LRU of `FileChannel`s.
- **Load:**
  1. positional `read` into a pooled direct buffer
  2. decompress (zlib, gzip, lz4, none; external `.mcc` for oversize)
  3. **streaming NBT reader** over the buffer: a pull parser, no tree
  4. palettes decoded straight into sections popped from the lock-free pool
  5. block entities and entities into staging records
  6. light: use stored light if `isLightOn`, else compute on the light pool
  7. push `STAGED` to the chunk service
- **DataVersion:** exactly the current version is accepted. Older chunks are rejected with a clear message ("run vanilla `--forceUpgrade`"). DFU is out of scope.
- **Save:**
  1. The owner region copies the chunk into a pooled off-heap `SaveBuffer`, in phase 11 with a per-tick byte budget. This is a memcpy of sections, light and block-entity records, so it is µs-scale.
  2. SPSC ring to the I/O shard.
  3. The shard encodes NBT (streaming writer), compresses, writes the new sectors, then rewrites the header entry (vanilla-style crash ordering), and fsyncs on `save-all flush`.
  4. Autosave spreads dirty chunks over the interval.
- **Other data:** `level.dat`, `playerdata/<uuid>.dat`, `entities/` region files (1.17+ split) and POI files use the same shards.
- **Generator SPI:** `ChunkGenerator.generate(cx, cz, SectionSink)` runs on the gen pool into STAGED. Flat and void are built in. Vanilla noise generation is an unowned post-M6 track.

### 4.4 Off-heap lighting engine

- **Storage:** 2048-B nibble arrays from pools. A null ref means uniform (0, or 15 for sky above the heightmap), so most sections need no array.
- **Propagation:** vanilla's two-queue BFS (decrease, then increase) per region. The queues are off-heap `long` rings of `packedPos:44 | level:4 | dir:6 | flags`. They run in phase 10 over blocks the region owns, reading neighbor opacity and emission from registry tables.
- **Crossing a boundary:** the region emits `LIGHT_EDGE` to the owner, and propagation continues at e+1. Vanilla's own light engine is already asynchronous (it runs on a separate thread and lags block changes), so a one-epoch border lag stays within vanilla-observable behavior.
- **Sky:** heightmap fast path for columns; BFS only under overhangs.
- **Fresh and loaded chunks:** lit on the light pool before INSTALL. Edges are reconciled by `LIGHT_EDGE` exchange after install.
- **Network:** a dirty-light section bitmask per chunk goes into the event journal, and egress sends `UpdateLight` from the arrays. This replaces the fake `FULL_LIGHT`.

### 4.5 Block entities

- Per-region off-heap `BlockEntityTable`: open addressing from packed pos to a type-specific fixed record.
  - furnace: 3 slots + burn, cook and total times
  - chest: an inventory index into the `OffHeapInventory` pool
  - hopper: 5 slots + cooldown
  - sign: front and back text handles
- It moves with the chunk on re-partition during the commit, like `rehome`.

## 5. Protocol and entity registry

### 5.1 Version and data

- The Minecraft version is pinned to the Minestom artifact in `libs.versions.toml` (`Vanilla.PROTOCOL` / `Vanilla.VERSION`). Upgrading is one PR: bump Minestom, regenerate the registry, and rerun the differential tests.
- **New module `mulcor-registry`.** A Gradle codegen task reads Minestom's bundled registry data at build time and emits:
  - block states: id → block, properties, flags (solid, opaque, replaceable, random-tick, fluid), light emission and opacity, hardness, collision/outline shape ids → a shared AABB list table
  - items: max stack, default components
  - entity types: dimensions, tracking range and interval, metadata field descriptor table (index, serializer)
  - sound, particle, biome and damage-type ids
  - per-state packet id tables

  Output is static `int[]`/`long[]` tables with no Minestom dependency at runtime on the hot path. A test cross-checks the tables against Minestom.

### 5.2 Pipeline

- **Inbound:** `[AES-CFB8 decrypt] → frame → [inflate if dataLength > 0] → IngressDecoder`.
  - The hot switch writes 32-B `Input` records (extended kinds) into the region ingress.
  - The cold default copies the frame into a variable-length off-heap **cold frame ring**. A cold worker parses it with Minestom objects and emits `ColdOp`s.
- **Outbound:** `PlayWriter` hot codecs, or cold pre-serialized bytes → frame and deflate (`ChunkCompressor`) → AES-CFB8 encrypt.
- **Encryption:**
  - `javax.crypto` AES/CFB8/NoPadding, with `Cipher.update(ByteBuffer, ByteBuffer)` on direct buffers and one cipher pair per connection, created at login (cold).
  - The alloc gate must show 0 B/packet after warm-up. If JMH shows CFB8 as a bottleneck, the fallback is an FFM binding to OpenSSL's `EVP_aes_128_cfb8`, behind the same interface.
  - RSA-1024 keypair at startup. `hasJoined` calls go through `HttpClient` on the cold lane. `online-mode` is a flag, and offline mode stays the test default.
- **Hot serverbound packets:**
  - movement ×4
  - player input, player action (dig), use item on, use item, swing
  - interact entity, set carried item
  - click container, close container
  - player command (sprint, sneak)
  - pick block, creative slot
  - teleport confirm, keep-alive, chunk batch received, client tick end
- **Cold serverbound packets:** everything else (chat, commands, settings, plugin messages, book and sign edit, recipe book, advancements tab, and so on).
- **Hot clientbound packets:**
  - chunk + light, forget chunk, block update, section blocks update, block entity data, block event
  - add and remove entity, move/rotate/sync position, head rotation, velocity, set entity data, equipment, animate, hurt/damage event, entity event
  - sound, particles
  - container set content, set slot, container data
  - set health, set experience, set time
  - keep-alive, ack block, view centre, batch markers, bundle delimiter

### 5.3 Egress architecture: the region event journal

- **Journal format.** Each region has a single-producer, multi-consumer **broadcast journal**: an off-heap ring of fixed 32-B events with a 64-bit monotonic cursor.
  - Event kinds: block change, block event, entity spawn/move/remove/metadata-dirty/animation, sound, particle, light dirty, container slot, player-addressed UI.
  - The region appends during its tick and publishes `cursor` with a release store in phase 11.
- **Readers.** Readers (session egress on Netty loops) keep a per-region read cursor and filter by their view AABB. If `writeCursor - readCursor > capacity`, the reader was lapped: it **resyncs**, resending the affected chunks and entity spawns. The producer never waits.
- **Epoch signal.** The engine publishes `sealedEpoch`. Session egress is triggered by the epoch, replacing the fixed 50-ms timer, via a lock-free wakeup of the event loop.
- **Per-session off-heap state:**
  - sent-chunk set (sparse open addressing, replacing the dense `long[] sent`)
  - tracked-entity set
  - container mirror and state id
  - outbound byte budget
- **Entity tracking.**
  - Each epoch the session scans snapshot cells within `min(view, trackingRange(type))`: new entities → spawn + metadata + equipment; missing → remove.
  - Moves come from the journal: a relative move if |Δ| < 8 blocks, else sync.
  - Metadata comes from per-entity dirty masks.
- **Metadata storage.** A per-entity off-heap block of up to 32 × 8 B slots plus a 64-bit dirty mask. The descriptor table (generated) gives each slot's serializer. Complex values (item stack, text, optional block state, particle) are handles.

### 5.4 Item stacks with data components (keeps the single-CAS invariant)

- **Slot word:** `item:16 | count:8 | flags:8 | patch:32`.
- **patch** is a handle to an immutable, **interned** component patch stored in an off-heap arena in *wire format*, so encoding is a memcpy. Handle 0 means default components.
- Interning is an off-heap hash plus refcount, with epoch-deferred free. Durability and enchantment changes create or look up interned patches on the owner region without heap allocation.

### 5.5 Windows and inventories

- Each open window has a state id and a per-session mirror.
- The owning region (after lease, §3.4) applies vanilla click modes: pickup, quick move, swap, clone, throw, quick craft, pickup-all. It diffs against the mirror and journals `SET_SLOT`. A state-id mismatch triggers a full content resync, as in vanilla.

## 6. Module layout

```
mulcor-memory    FFM primitives (+ scheduled-tick min-heap, open-addressing maps, broadcast journal, arenas)
mulcor-registry  NEW: generated vanilla tables (no runtime deps)
mulcor-core      engine, partition (sparse cells, affinity), chunk directory/lifecycle, light, messaging, global phase
mulcor-game      NEW: vanilla behaviours: blocks, fluids, redstone, block entities, entities, AI, combat, crafting
mulcor-storage   NEW: streaming NBT, Anvil shards, level/player data, generator SPI
mulcor-net       login/crypto/config (cold) + play pipeline, hot codecs, egress journal readers
mulcor-cold      NEW: Brigadier, datapacks, recipes/loot/tags loaders, advancements, scoreboards, chat
mulcor-harness   server main, VanillaClient, floods, conformance runner, reports, JMH
```

- Dependency rule: `cold → {game, core, storage, registry}`; nothing depends on `cold` except `harness`.
- `Sim.java` mechanics move into `mulcor-game`, behind a block-behavior dispatch table indexed by state id.

## 7. Milestones

Every milestone runs the full set of standard gates (§8): `./gradlew check` green after each major step; `fullValidation` before closing the milestone; 0 B/tick under G1 and Epsilon in the new scenario; `NoLocksTest`, `HotPathAuditTest` and the JFR lock audit at 0; jcstress for every new concurrent primitive before merge; and baseline tick p99 not regressed by more than 10% on the existing 5k-bot scenario. The concrete numeric targets are calibrated after the first measurement on the M1 and recorded in `summary.md`.

### M1: real client join and protocol pipeline

- **Objectives:**
  1. Once the parallel session has committed its WIP, record a green `./gradlew check` baseline. Add the missing `PlayWriterTest` (a byte-for-byte differential test against Minestom).
  2. Build `mulcor-registry` codegen and switch to vanilla state ids.
  3. Encryption (RSA + AES-CFB8), online mode with Mojang auth, compression both ways.
  4. Real lighting in chunk packets (a heightmap sky-light fast path is enough here).
  5. Region event journal + session egress: block changes that other players see, and player entities visible to each other (spawn, move, remove).
  6. Cold frame ring and cold lane skeleton: chat echo, `/tp` via `ColdOpRing`.
  7. `HotPathAuditTest` and ArchUnit rules.
- **Modules:** registry (new), memory (journal, open-addressing map), core (journal, snapshot, global-phase hook), net, harness.
- **Performance gates:**
  - 0 B/packet for decrypt → decode and encode → compress → encrypt.
  - 1,000 headless encrypted clients join, move and see each other with tick p99 within gate.
  - Join p99 < 100 ms on localhost.
- **Tests:**
  - Unit: codec differential tests for every hot packet; crypto round-trip against Minestom's client-side encryption; NBT-free registry cross-check.
  - jcstress: journal publish/lap detection, seqlock snapshot read.
  - Zero-alloc: new `EncryptedFloodZeroAllocationTest`.
  - JMH: CFB8 throughput, journal fan-out.
  - E2E: `VanillaClient` extended with encryption. Manual smoke test with a stock client (documented checklist).

### M2: world persistence, Anvil I/O and lighting

- **Objectives:**
  - Sparse `ChunkDirectory` and sparse `Partition` cells.
  - Chunk lifecycle, tickets and chunk service.
  - Streaming NBT reader/writer.
  - Anvil shards (region, entities, POI), `level.dat`, player data.
  - Save pipeline and autosave; COLD_PALETTED sections with promotion.
  - Full light engine (BFS, cross-region `LIGHT_EDGE`, `UpdateLight`).
  - Generator SPI (flat, void).
- **Modules:** memory, core, storage (new), net, harness.
- **Performance gates:**
  - Tick p99 during autosave and a player flying at 10 blocks/tick within +10% of idle.
  - 0 B/tick on region threads while loading and unloading.
  - I/O shards never touch region threads. JFR shows no region-worker I/O or park.
- **Tests:**
  - Unit: NBT round-trip against Minestom/Adventure NBT on vanilla-saved fixtures.
  - **Anvil round-trip oracle:** load a vanilla-generated world → save → byte-equivalent NBT, with key order normalized.
  - **Light oracle:** recompute light on vanilla-saved chunks and compare with vanilla's stored light (bit-exact within regions, converging within N epochs across borders).
  - jcstress: `ChunkDirectory` install vs acquire read, section promotion vs `getShared`, SaveBuffer SPSC handoff.
  - Crash test: kill -9 during save → world loads.
  - JMH: NBT decode MB/s, light BFS ns/block.

### M3: block behaviors, fluids and the redstone cascade engine

- **Objectives:**
  - Block-behavior dispatch table.
  - Scheduled-tick min-heap (the parallel session's implementation).
  - Deadline messages.
  - `neighborChanged`/shape updates with vanilla ordering.
  - Block events.
  - Fluids (water, lava, waterlogging).
  - Gravity blocks (falling-block entities).
  - Redstone: dust, torches, repeaters, comparators, observers, pistons, levers, buttons, pressure plates, lamps, TNT.
  - **Affinity coalescing** in `Partition`.
  - Real explosion algorithm with `EXPLODE_APPLY`.
  - Random ticks (crops, leaves, ice).
- **Modules:** game (new), core (tick queue, affinity, Partition), registry.
- **Performance gates:**
  - 10k active redstone components + 1k flowing water sources: 0 B/tick.
  - Megaregion cost reported.
  - The 5k-bot baseline is not regressed.
- **Tests:**
  - **Golden-trace conformance:** contraptions (clocks, 0-tick-free circuits, piston doors, observer chains, water/lava flows) recorded tick by tick on a vanilla server, via a GameTest data-collection harness, and stored as test resources. Mulcor must match them exactly:
    - inside one region
    - straddling borders with affinity enabled
    - with affinity disabled, where the documented lag is asserted
  - jcstress: affinity-edge counters and forced-merge vs in-flight messages.
  - Property tests: block and item conservation extended to all mineable states.

### M4: inventories, containers and crafting

- **Objectives:**
  - Slot word v2 with interned component patches.
  - Player inventory, hotbar, offhand, armor.
  - Windows: chest, double chest, furnace family, hopper, dispenser/dropper, crafting table, anvil and enchanting (basic).
  - All click modes, state-id sync, inventory lease.
  - Hoppers, including cross-region.
  - Furnace tick engine.
  - Recipe matcher: shaped recipes normalized to a hash key and looked up in an off-heap table; shapeless as sorted multisets; smelting. Loaded from datapacks cold, stored off-heap.
  - Item entities: drop, pickup, merge.
- **Modules:** memory, game, net, cold (recipe loading).
- **Performance gates:** 5k hoppers + 1k furnaces + 200 players clicking at 0 B/tick; exact item conservation under stress.
- **Tests:**
  - jcstress: lease CAS (open/close race with pickups), patch intern/refcount/free, hopper pull race vs player click.
  - Conservation audit extended with patches.
  - Click-mode conformance against vanilla traces.
  - JMH: recipe lookup ns/op.

### M5: entity system, combat and mob AI

- **Objectives:**
  - Entity component tables: health, attributes, equipment, metadata, AI state, path buffer, bounding box.
  - Vanilla movement and collision against generated voxel-shape tables.
  - Metadata serializers for every entity type from generated descriptors.
  - Tracking via snapshots.
  - Combat: attack cooldown, crits, knockback, armor and enchantments, damage types, death and drops via loot tables.
  - Pathfinding with vanilla node evaluators (walk, swim, fly), budgeted per tick.
  - Goal and brain selectors for core mobs (zombie, skeleton, creeper, spider, cow, sheep, pig, chicken, villager basics).
  - Natural spawning with per-player local mob caps.
  - Projectiles.
  - Players: survival, hunger, XP, respawn.
- **Modules:** memory, game, registry, net.
- **Performance gates:** 5k mobs + 200 players; tick p99 within budget at the target worker count; 0 B/tick.
- **Tests:**
  - Unit: movement/collision golden tests from vanilla traces; metadata byte differential against Minestom for every entity type.
  - jcstress: `ENTITY_DAMAGE` vs hand-off (damage follows the entity, is never lost or doubled).
  - Stress: entity conservation audit (`Audit.entities`) with AI.
  - JMH: path search, collision sweep.

### M6: commands, datapacks and full vanilla parity

- **Objectives:**
  - Brigadier on the cold lane, with the command tree packet and suggestions.
  - Vanilla command set compiled to `ColdOp`s and executed in the global phase: selectors evaluated against snapshots, then routed to owners.
  - `/function` and datapack functions precompiled to op arrays.
  - Tags, loot tables, predicates, advancements (cold evaluator fed by `EventRing`s), scoreboards, teams, bossbars.
  - Game rules, time and weather, world border.
  - Nether and End dimensions (multi-world = multiple `World`s sharing one worker pool) and portals.
  - `save-all`, `stop`, and graceful shutdown with a full flush.
- **Modules:** cold (new), core (global phase), game, storage, net.
- **Performance gates:**
  - Global phase p99 < 0.5 ms with a 1k-command/tick function load.
  - 0 B/tick when no cold work is queued.
  - Cold-lane saturation never raises tick p99 beyond the gate.
- **Tests:**
  - Command parse and execute conformance against vanilla outputs.
  - Advancement triggers end to end.
  - Datapack load of vanilla's built-in pack with no errors.
  - Long soak test: 24 h with bots plus autosave, no leaks (NativeMemory reserved bytes stable).
  - Parity scorecard in `summary.md`: percentage of conformance suites passing per area.

## 8. Test and gate infrastructure additions

- `HotPathAuditTest` (static allocation and indy scan) + ArchUnit layering rules.
- A per-milestone `*ZeroAllocationTest` scenario, run under G1 and Epsilon.
- `ConformanceRunner` in the harness: replays vanilla golden traces (block states, entity positions, slot contents per tick) and diffs them.
- A `VanillaClient` extended into a scripted bot (encryption, inventory clicks, combat) for E2E tests and floods.
- `ReportMain` gains sections for join latency, I/O throughput, light convergence, journal laps, affinity/megaregion stats and the parity scorecard.

## 9. Risks and open items

- "100% vanilla parity" is tracked as a measured scorecard, not a boolean. Some behaviors (update-order quirks at unbonded borders during the transient) are documented deviations.
- Vanilla noise worldgen is **unowned**. It is a post-M6 track behind the `ChunkGenerator` SPI. Until then: flat/void generators, or pre-generated vanilla worlds loaded through Anvil.
- A parallel session is editing `Sim`, `Region`, `Engine` and `mulcor-net` (login, redstone, physics). Merge ordering is in the "Parallel work" section at the top. That session has adopted the deadline, neighbor-update and snapshot constraints; affinity and the registry remain here.
- Minestom version drift: the codegen and differential tests catch it at upgrade time.
- JDK 26 preview features lock the build to one JDK; this is already documented.
- 64+ thread scaling is still unmeasured on M1 hardware; add a cloud benchmark job when available.
- AES-CFB8 JDK performance is unverified; there is an FFM OpenSSL fallback.
