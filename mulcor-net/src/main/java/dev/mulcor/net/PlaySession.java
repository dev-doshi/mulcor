package dev.mulcor.net;

import dev.mulcor.core.World;
import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Input;
import dev.mulcor.core.region.Journal;
import dev.mulcor.core.region.NetEntities;
import dev.mulcor.core.region.Region;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.BroadcastJournal;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.memory.ScheduledTicks;
import dev.mulcor.registry.EntityTypeId;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.FastThreadLocal;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minestom.server.entity.GameMode;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.player.GameProfile;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The outbound half of a player's play session. Every 50 ms on the connection's event loop it:
 * <ul>
 *   <li>re-centres the client's chunk view when the player crosses a chunk border (from the positions the
 *       {@link IngressDecoder} has seen),</li>
 *   <li>streams the chunks in view that the client does not have yet, nearest first, at most
 *       {@code chunksPerTick} per batch and only while the socket is writable,</li>
 *   <li>forwards what changed in the chunks the client holds, from every region's egress journal
 *       ({@link Journal}): block events first (pistons animate from them, as vanilla sends them during the tick),
 *       then one block update per changed position with the state the block has now. A session that falls a whole
 *       journal behind resends its chunks instead;</li>
 *   <li>tracks entities ({@link #trackEntities}) from the regions' network snapshots: spawns those that come into
 *       range, syncs the ones that moved, removes those that left or vanished;</li>
 *   <li>acknowledges the client's block actions and sends a keep-alive every 10 s.</li>
 * </ul>
 * Joining adds the player to every tab list (and every online player to its own), so player entities can be shown;
 * leaving removes it.
 * Chunks are encoded straight from off-heap block storage by the event loop's {@link PlayWriter}, so steady-state
 * streaming allocates nothing on the heap. Inbound bytes pass through to the {@link IngressHandler} behind it.
 * When the connection closes, the player's entity is removed with a LEAVE input.
 */
public final class PlaySession extends ChannelInboundHandlerAdapter {
    private static final FastThreadLocal<PlayWriter> COMPRESSED = new FastThreadLocal<>();
    private static final FastThreadLocal<PlayWriter> PLAIN = new FastThreadLocal<>();
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final ServerContext server;
    private final int entity;
    private final IngressDecoder decoder;
    private final int threshold;
    private final String name;
    private final GameProfile profile;
    private final Players players;
    private final BlockStorage blocks;
    private final LightStorage blockLight, skyLight;
    private final int chunksX, chunksZ, radius;
    /** One bit per world chunk: the client currently holds it. */
    private final long[] sent;
    private final MemorySegment scratch = NativeMemory.auto().allocate(Input.BYTES);
    /** Journal reading: one cursor per region slot, a record buffer, and a per-update set of positions sent. */
    private final BroadcastJournal[] journals;
    private final long[] cursors;
    private final MemorySegment rec = NativeMemory.auto().allocate(Journal.BYTES);
    private final long[] seenPos = new long[SEEN];
    private final int[] seenGen = new int[SEEN];
    private int generation;
    private static final int SEEN = 1 << 13;
    /** Most journal records one update reads per region slot (the rest wait for the next update). */
    private static final int RECORDS_PER_UPDATE = 4096;

    // ---- entity tracking --------------------------------------------------------------------------------------
    /** Tracked entities: open addressing by entity id (key = id + 1, 0 = empty), with what the client last got. */
    private static final int TRACK = 1 << 12;
    private final int[] trackKey = new int[TRACK], trackRound = new int[TRACK];
    private final double[] trackX = new double[TRACK], trackY = new double[TRACK], trackZ = new double[TRACK];
    private final float[] trackYaw = new float[TRACK], trackPitch = new float[TRACK];
    private int tracked, round;
    private final int[] removeIds = new int[TRACK];
    private final World world;
    private final boolean[] relevant;
    /** A copy of one region's snapshot buffer, read and validated before use (see {@link NetEntities}). */
    private final MemorySegment snapCopy;
    private int centerX, centerZ, ring;
    private int lastAcked = -1;
    private long lastKeepAlive;
    private long chunksSent;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> task;
    private boolean closed;

    PlaySession(ServerContext server, int entity, IngressDecoder decoder, int threshold, double spawnX, double spawnZ,
                GameProfile profile) {
        this.server = server;
        this.entity = entity;
        this.decoder = decoder;
        this.threshold = threshold;
        this.name = profile.name();
        this.profile = profile;
        this.players = server.players();
        this.world = server.engine().world;
        this.relevant = new boolean[world.regions.length];
        this.snapCopy = NativeMemory.auto().allocate(NetEntities.HEADER + (long) world.regions[0].netCapacity() * NetEntities.BYTES);
        this.blocks = server.engine().world.blocks;
        this.blockLight = server.engine().world.blockLight;
        this.skyLight = server.engine().world.skyLight;
        this.chunksX = blocks.chunksX();
        this.chunksZ = blocks.chunksZ();
        this.radius = server.viewDistance();
        this.sent = new long[(chunksX * chunksZ + 63) >>> 6];
        this.journals = server.engine().world.journals;
        this.cursors = new long[journals.length];
        for (int r = 0; r < journals.length; r++) cursors[r] = journals[r].published(); // chunks sent later are newer
        this.centerX = Math.floorDiv((int) Math.floor(spawnX), 16);
        this.centerZ = Math.floorDiv((int) Math.floor(spawnZ), 16);
    }

    ChannelHandlerContext ctx() { return ctx; }
    public int entity() { return entity; }
    public String name() { return name; }
    public GameProfile profile() { return profile; }
    public long chunksSent() { return chunksSent; }

    private PlayWriter writer() {
        FastThreadLocal<PlayWriter> local = threshold > 0 ? COMPRESSED : PLAIN;
        PlayWriter w = local.get();
        if (w == null) {
            w = new PlayWriter(threshold);
            local.set(w);
        }
        return w;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        server.online().incrementAndGet();
        lastKeepAlive = System.nanoTime();
        ByteBuf out = ctx.alloc().directBuffer(64 * 1024);
        writer().viewCenter(centerX, centerZ, out);
        stream(out);
        ctx.writeAndFlush(out);
        joinTabLists();
        task = ctx.executor().scheduleAtFixedRate(this::update, 50, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        close();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void close() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel(false);
        server.online().decrementAndGet();
        leaveTabLists();
        leave();
    }

    private void leave() {
        if (!server.engine().requestLeave(scratch, entity)) {
            ctx.executor().schedule(this::leave, 5, TimeUnit.MILLISECONDS); // ingress ring full: retry
        }
    }

    private void update() {
        if (closed || !ctx.channel().isActive()) return;
        PlayWriter w = writer();
        ByteBuf out = ctx.alloc().directBuffer(16 * 1024);
        if (decoder.hasPosition()) {
            int cx = Math.floorDiv(decoder.lastX1000(), 16_000), cz = Math.floorDiv(decoder.lastZ1000(), 16_000);
            if (cx != centerX || cz != centerZ) recenter(cx, cz, w, out);
        }
        if (ctx.channel().isWritable()) stream(out);
        forwardChanges(w, out);
        trackEntities(w, out);
        int seq = decoder.lastSequence();
        if (seq > lastAcked) {
            w.acknowledgeBlockChange(seq, out);
            lastAcked = seq;
        }
        long now = System.nanoTime();
        if (now - lastKeepAlive >= KEEP_ALIVE_NANOS) {
            w.keepAlive(now, out);
            lastKeepAlive = now;
        }
        if (out.isReadable()) {
            ctx.writeAndFlush(out);
        } else {
            out.release();
        }
    }

    /**
     * Read every region journal from this session's cursors: block events go out at once, changed positions are
     * collected (each once) and sent afterwards with their current state. Only positions in chunks the client holds.
     */
    private void forwardChanges(PlayWriter w, ByteBuf out) {
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(seenGen, 0);
            generation = 1;
        }
        ByteBuf updates = null;
        boolean lapped = false;
        for (int r = 0; r < journals.length; r++) {
            BroadcastJournal j = journals[r];
            long pos = cursors[r];
            for (int n = 0; n < RECORDS_PER_UPDATE; n++) {
                int status = j.read(pos, rec, 0);
                if (status == BroadcastJournal.EMPTY) break;
                if (status == BroadcastJournal.LAPPED) {
                    lapped = true;
                    pos = j.published();
                    break;
                }
                pos++;
                long p = rec.get(ValueLayout.JAVA_LONG, Journal.POS);
                long meta = rec.get(ValueLayout.JAVA_LONG, Journal.META);
                int x = ScheduledTicks.x(p), y = ScheduledTicks.y(p), z = ScheduledTicks.z(p);
                if (!holds(x >> 4, z >> 4)) continue;
                if (Journal.kind(meta) == Journal.BLOCK_EVENT) {
                    w.blockEvent(p, Journal.eventA(meta), Journal.eventB(meta), Journal.eventBlock(meta), out);
                } else if (firstThisUpdate(p)) {
                    if (updates == null) updates = ctx.alloc().directBuffer(4 * 1024);
                    blocks.beginExternalRead();
                    int state;
                    try {
                        state = blocks.getShared(x, y, z);
                    } finally {
                        blocks.endExternalRead();
                    }
                    w.blockUpdate(p, state, updates);
                }
            }
            cursors[r] = pos;
        }
        if (updates != null) {
            PlayWriter.copy(updates, out);
            updates.release();
        }
        if (lapped) { // too far behind to know what changed: resend every chunk in view
            Arrays.fill(sent, 0L);
            ring = 0;
        }
    }

    // ---- tab list (cold: joins and leaves) ---------------------------------------------------------------------

    private static final EnumSet<PlayerInfoUpdatePacket.Action> ADD = EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER,
            PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, PlayerInfoUpdatePacket.Action.UPDATE_LISTED);

    private PlayerInfoUpdatePacket.Entry entry() {
        List<PlayerInfoUpdatePacket.Property> props = new ArrayList<>();
        for (GameProfile.Property p : profile.properties()) {
            props.add(new PlayerInfoUpdatePacket.Property(p.name(), p.value(), p.signature()));
        }
        return new PlayerInfoUpdatePacket.Entry(profile.uuid(), profile.name(), props, true, 0, GameMode.CREATIVE, null,
                null, 0, true);
    }

    private static <T> byte[] serialize(int id, NetworkBuffer.Type<T> type, T packet) {
        NetworkBuffer nb = NetworkBuffer.resizableBuffer();
        nb.write(NetworkBuffer.VAR_INT, id);
        nb.write(type, packet);
        return nb.read(NetworkBuffer.RAW_BYTES);
    }

    /** Send an already serialized packet on this connection, from its event loop. */
    private void sendRaw(byte[] packet) {
        if (closed || !ctx.channel().isActive()) return;
        ByteBuf out = ctx.alloc().directBuffer(packet.length + 8);
        writer().raw(packet, out);
        ctx.writeAndFlush(out);
    }

    /** Run {@code packet} through {@link #sendRaw} on another session's event loop. */
    private static void sendTo(PlaySession other, byte[] packet) {
        ChannelHandlerContext c = other.ctx;
        if (c != null) c.executor().execute(() -> other.sendRaw(packet));
    }

    /** This player joins every tab list, and every online player joins this one's (its own entry included). */
    private void joinTabLists() {
        players.add(this);
        List<PlayerInfoUpdatePacket.Entry> all = new ArrayList<>();
        byte[] mine = serialize(Protocol.OUT_PLAYER_INFO_UPDATE, PlayerInfoUpdatePacket.SERIALIZER,
                new PlayerInfoUpdatePacket(ADD, List.of(entry())));
        for (int e = 0; e < players.capacity(); e++) {
            PlaySession other = players.get(e);
            if (other == null) continue;
            all.add(other.entry());
            if (other != this) sendTo(other, mine);
        }
        for (int i = 0; i < all.size(); i += PlayerInfoUpdatePacket.MAX_ENTRIES) {
            sendRaw(serialize(Protocol.OUT_PLAYER_INFO_UPDATE, PlayerInfoUpdatePacket.SERIALIZER,
                    new PlayerInfoUpdatePacket(ADD, all.subList(i, Math.min(all.size(), i + PlayerInfoUpdatePacket.MAX_ENTRIES)))));
        }
    }

    private void leaveTabLists() {
        players.remove(this);
        byte[] gone = serialize(Protocol.OUT_PLAYER_INFO_REMOVE, PlayerInfoRemovePacket.SERIALIZER,
                new PlayerInfoRemovePacket(profile.uuid()));
        for (int e = 0; e < players.capacity(); e++) {
            PlaySession other = players.get(e);
            if (other != null && other != this) sendTo(other, gone);
        }
    }

    // ---- entity tracking ---------------------------------------------------------------------------------------

    /** Vanilla {@code EntityType.clientTrackingRange} (chunks) × 16: how far away an entity of a type is shown. */
    private static int trackingRange(int type) {
        return switch (type) {
            case Entities.PLAYER -> 32 * 16;
            case Entities.TNT, Entities.FALLING_BLOCK -> 10 * 16;
            default -> 8 * 16; // zombies
        };
    }

    private static int protocolType(int type) {
        return switch (type) {
            case Entities.PLAYER -> EntityTypeId.PLAYER;
            case Entities.TNT -> EntityTypeId.TNT;
            case Entities.FALLING_BLOCK -> EntityTypeId.FALLING_BLOCK;
            default -> EntityTypeId.ZOMBIE;
        };
    }

    /**
     * Entity tracking from the network snapshots of the regions that overlap the view: each such snapshot (of the
     * newest epoch) is copied and validated with its sequence word, then every entity in range is spawned (new),
     * synced (moved or turned) or kept; tracked entities not seen go away. If a copy was torn, nothing is removed this
     * round (an entity might only have been missed).
     */
    private void trackEntities(PlayWriter w, ByteBuf out) {
        if (!decoder.hasPosition()) return;
        double px = decoder.lastX1000() / 1000.0, pz = decoder.lastZ1000() / 1000.0;
        Region[] regions = world.regions;
        // Regions whose cells overlap the view square (the partition is read racily: a stale answer only delays).
        Arrays.fill(relevant, false);
        int cb = world.cellBlocks(), view = radius * 16 + 16;
        var part = world.partition;
        int cx0 = Math.max(0, (int) Math.floor((px - view) / cb)), cx1 = Math.min(part.cellsX() - 1, (int) Math.floor((px + view) / cb));
        int cz0 = Math.max(0, (int) Math.floor((pz - view) / cb)), cz1 = Math.min(part.cellsZ() - 1, (int) Math.floor((pz + view) / cb));
        for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++) relevant[part.regionAt(cx, cz)] = true;
        long newest = 0;
        for (int r = 0; r < regions.length; r++) {
            if (!relevant[r]) continue;
            MemorySegment s = regions[r].netSnapshot();
            int cap = regions[r].netCapacity();
            for (int b = 0; b < 2; b++) {
                long seq = NetEntities.seq(s, NetEntities.bufferOffset(b, cap));
                if (seq > 0 && (seq & 1) == 0) newest = Math.max(newest, (seq - 2) / 2);
            }
        }
        if (newest == 0) return;
        round++;
        boolean complete = true;
        for (int r = 0; r < regions.length; r++) {
            if (!relevant[r]) continue;
            int n = copySnapshot(regions[r], newest);
            if (n == -2) continue; // not this epoch: an inactive region
            if (n < 0) {
                complete = false;
                continue;
            }
            for (int i = 0; i < n; i++) track((long) i * NetEntities.BYTES, px, pz, w, out);
        }
        if (!complete) return;
        int removed = 0;
        for (int i = 0; i < TRACK; i++) {
            if (trackKey[i] == 0 || trackRound[i] == round) continue;
            removeIds[removed++] = trackKey[i] - 1;
        }
        for (int k = 0; k < removed; k++) untrack(removeIds[k]);
        if (removed > 0) w.removeEntities(removeIds, removed, out);
    }

    /**
     * Copy region {@code r}'s snapshot of epoch {@code epoch} into {@link #snapCopy} (records from offset 0). Returns
     * the entity count, -1 if the copy was torn (the region was writing it), -2 if it has no snapshot of that epoch.
     */
    private int copySnapshot(Region r, long epoch) {
        MemorySegment s = r.netSnapshot();
        int cap = r.netCapacity();
        for (int b = 0; b < 2; b++) {
            long base = NetEntities.bufferOffset(b, cap);
            long seq = NetEntities.seq(s, base);
            if (seq != 2 * epoch + 2) continue;
            int n = Math.min(NetEntities.count(s, base), cap);
            MemorySegment.copy(s, base + NetEntities.HEADER, snapCopy, 0, (long) n * NetEntities.BYTES);
            VarHandle.loadLoadFence();
            return NetEntities.seq(s, base) == seq ? n : -1;
        }
        return -2;
    }

    private void track(long o, double px, double pz, PlayWriter w, ByteBuf out) {
        MemorySegment s = snapCopy;
        int id = s.get(ValueLayout.JAVA_INT, o + NetEntities.ID);
        if (id == entity) return;
        int type = s.get(ValueLayout.JAVA_INT, o + NetEntities.TYPE);
        double x = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.X), y = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.Y);
        double z = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.Z);
        int range = Math.min(trackingRange(type), radius * 16);
        if (Math.abs(x - px) > range || Math.abs(z - pz) > range) return;
        double vx = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.VX), vy = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.VY);
        double vz = s.get(ValueLayout.JAVA_DOUBLE, o + NetEntities.VZ);
        float yaw = s.get(ValueLayout.JAVA_FLOAT, o + NetEntities.YAW), pitch = s.get(ValueLayout.JAVA_FLOAT, o + NetEntities.PITCH);
        int slot = find(id);
        if (slot < 0) {
            long most, least;
            if (type == Entities.PLAYER) {
                PlaySession p = players.get(id);
                if (p == null) return; // its tab-list entry is not out yet: spawn it next round
                UUID u = p.profile.uuid();
                most = u.getMostSignificantBits();
                least = u.getLeastSignificantBits();
            } else {
                most = 0x6D756C636F720000L | type; // stable per entity: "mulcor" + type, id
                least = id;
            }
            slot = insert(id);
            if (slot < 0) return; // tracking table full
            int data = type == Entities.FALLING_BLOCK ? s.get(ValueLayout.JAVA_INT, o + NetEntities.DATA) : 0;
            w.addEntity(id, most, least, protocolType(type), x, y, z, vx, vy, vz, yaw, pitch, data, out);
        } else if (trackX[slot] != x || trackY[slot] != y || trackZ[slot] != z || trackYaw[slot] != yaw
                || trackPitch[slot] != pitch) {
            boolean onGround = (s.get(ValueLayout.JAVA_INT, o + NetEntities.FLAGS) & Entities.FLAG_ON_GROUND) != 0;
            w.entitySync(id, x, y, z, vx, vy, vz, yaw, pitch, onGround, out);
        }
        trackRound[slot] = round;
        trackX[slot] = x;
        trackY[slot] = y;
        trackZ[slot] = z;
        trackYaw[slot] = yaw;
        trackPitch[slot] = pitch;
    }

    private static int home(int id) {
        return (id * 0x9E3779B9 >>> 20) & (TRACK - 1);
    }

    private int find(int id) {
        for (int i = home(id), p = 0; p < TRACK; p++, i = (i + 1) & (TRACK - 1)) {
            if (trackKey[i] == 0) return -1;
            if (trackKey[i] == id + 1) return i;
        }
        return -1;
    }

    private int insert(int id) {
        if (tracked >= TRACK * 3 / 4) return -1;
        int i = home(id);
        while (trackKey[i] != 0) i = (i + 1) & (TRACK - 1);
        trackKey[i] = id + 1;
        tracked++;
        return i;
    }

    /** Remove {@code id} with backward-shift deletion (keeps probe chains intact without tombstones). */
    private void untrack(int id) {
        int i = find(id);
        if (i < 0) return;
        int hole = i, j = i;
        while (true) {
            j = (j + 1) & (TRACK - 1);
            if (trackKey[j] == 0) break;
            int h = home(trackKey[j] - 1);
            boolean stays = hole <= j ? (h > hole && h <= j) : (h > hole || h <= j);
            if (stays) continue;
            trackKey[hole] = trackKey[j];
            trackRound[hole] = trackRound[j];
            trackX[hole] = trackX[j];
            trackY[hole] = trackY[j];
            trackZ[hole] = trackZ[j];
            trackYaw[hole] = trackYaw[j];
            trackPitch[hole] = trackPitch[j];
            hole = j;
        }
        trackKey[hole] = 0;
        tracked--;
    }

    private boolean holds(int chunkX, int chunkZ) {
        if (chunkX < 0 || chunkZ < 0 || chunkX >= chunksX || chunkZ >= chunksZ) return false;
        int bit = chunkZ * chunksX + chunkX;
        return (sent[bit >>> 6] & (1L << bit)) != 0;
    }

    /** Open-addressing set of positions updated in this round (cleared by bumping {@link #generation}). */
    private boolean firstThisUpdate(long p) {
        int i = (int) (p * 0x9E3779B97F4A7C15L >>> 51) & (SEEN - 1);
        for (int probes = 0; probes < SEEN; probes++, i = (i + 1) & (SEEN - 1)) {
            if (seenGen[i] != generation) {
                seenGen[i] = generation;
                seenPos[i] = p;
                return true;
            }
            if (seenPos[i] == p) return false;
        }
        return true; // full: send it again rather than drop it
    }

    /** The client drops chunks outside its new view square itself; forget that we sent them, then refill. */
    private void recenter(int cx, int cz, PlayWriter w, ByteBuf out) {
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (Math.abs(x - cx) > radius || Math.abs(z - cz) > radius) clear(x, z);
            }
        }
        centerX = cx;
        centerZ = cz;
        ring = 0;
        w.viewCenter(cx, cz, out);
    }

    /** Send up to {@code chunksPerTick} missing chunks in rings of growing distance around the centre. */
    private void stream(ByteBuf out) {
        PlayWriter w = writer();
        int budget = server.chunksPerTick(), n = 0;
        for (int d = ring; d <= radius; d++) {
            for (int i = -d; i <= d; i++) {
                n = send(centerX + i, centerZ - d, n, w, out);
                if (d > 0) n = send(centerX + i, centerZ + d, n, w, out);
                if (n >= budget) { finish(d, n, w, out); return; }
            }
            for (int j = -d + 1; j <= d - 1; j++) {
                n = send(centerX - d, centerZ + j, n, w, out);
                n = send(centerX + d, centerZ + j, n, w, out);
                if (n >= budget) { finish(d, n, w, out); return; }
            }
        }
        finish(radius + 1, n, w, out);
    }

    private int send(int x, int z, int n, PlayWriter w, ByteBuf out) {
        if (x < 0 || z < 0 || x >= chunksX || z >= chunksZ) return n;
        int bit = z * chunksX + x;
        if ((sent[bit >>> 6] & (1L << bit)) != 0) return n;
        if (n == 0) w.batchStart(out);
        w.chunk(blocks, blockLight, skyLight, x, z, out);
        sent[bit >>> 6] |= 1L << bit;
        chunksSent++;
        return n + 1;
    }

    private void finish(int nextRing, int n, PlayWriter w, ByteBuf out) {
        ring = nextRing;
        if (n > 0) w.batchFinished(n, out);
    }

    private void clear(int x, int z) {
        if (x < 0 || z < 0 || x >= chunksX || z >= chunksZ) return;
        int bit = z * chunksX + x;
        sent[bit >>> 6] &= ~(1L << bit);
    }
}
