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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.play.ClientChatMessagePacket;
import net.minestom.server.network.packet.client.play.ClientCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientSignedCommandChatPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.DisguisedChatPacket;
import net.minestom.server.network.packet.server.play.PlayerAbilitiesPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
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
 *   <li>acknowledges the client's block actions and sends a keep-alive every 10 s,</li>
 *   <li>handles the cold packets the decoder parked ({@link ColdMailbox}): chat (broadcast like a server without
 *       secure chat), commands ({@link Commands}), and compressed frames (inflated, then decoded as usual).</li>
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
    /** Players: presence, swing counters, held item. Items: META = the stack's item | count << 16, HELD = damage. */
    private final int[] trackMeta = new int[TRACK], trackSwing = new int[TRACK], trackHeld = new int[TRACK];
    /** This player's own skin parts and main hand as last sent to itself (the client starts from the defaults). */
    private int selfMeta = DEFAULT_PRESENCE;
    /** Updates until the next Set Time (vanilla sends it every 20 ticks). */
    private int timeCountdown;
    /** The client's settings from the configuration phase: displayed skin parts and main hand. */
    private final int skinParts, mainHand;
    private int tracked, round;
    private final int[] removeIds = new int[TRACK];
    private final World world;
    private final boolean[] relevant;
    /** A copy of one region's snapshot buffer, read and validated before use (see {@link NetEntities}). */
    private final MemorySegment snapCopy;
    private int centerX, centerZ, ring;
    private int lastAcked = -1, pendingAck = -1;
    /** This player's game mode (vanilla GameType id); written on its event loop, read by commands anywhere. */
    private volatile int gameMode;
    private int teleportId = 1; // the join teleport is 1
    private final double spawnX, spawnZ;

    // ---- inventory ----------------------------------------------------------------------------------------------
    /** Menu kinds and slot count as in {@code dev.mulcor.core.region.Menus}; the crafting menu's type id and title. */
    private static final int MENU_CRAFTING = 1, MENU_SLOTS = 46, CRAFTING_MENU_TYPE = 12;
    private static final int TABLE_GRID = 46, TABLE_RESULT = 55;
    private static final byte[] CRAFTING_TITLE = Vanilla.component(Component.translatable("container.crafting"));
    /** The published inventory as last read, and as the client last got it ({@link World#INV_PUB} longs each). */
    private final long[] invNow = new long[World.INV_PUB], invSent = new long[World.INV_PUB];
    /** One menu's slots in menu order, for Window Items. */
    private final long[] menuSlots = new long[MENU_SLOTS];
    private int invSeqSeen = -1, windowSent, clicksSent, closesSeen, stateId;
    private Inflater inflater;
    private long lastKeepAlive;
    private long chunksSent;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> task;
    private boolean closed;

    PlaySession(ServerContext server, int entity, IngressDecoder decoder, int threshold, double spawnX, double spawnZ,
                GameProfile profile) {
        this(server, entity, decoder, threshold, spawnX, spawnZ, profile, 0x7F, 1);
    }

    PlaySession(ServerContext server, int entity, IngressDecoder decoder, int threshold, double spawnX, double spawnZ,
                GameProfile profile, int skinParts, int mainHand) {
        this.skinParts = skinParts;
        this.mainHand = mainHand;
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
        this.gameMode = server.gameMode();
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
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
        sendTime(writer(), out);
        stream(out);
        ctx.writeAndFlush(out);
        joinTabLists();
        request(Input.GAME_MODE, gameMode, 0, 0);
        request(Input.SETTINGS, skinParts, mainHand, 0);
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
        if (inflater != null) inflater.end();
        server.online().decrementAndGet();
        leaveTabLists();
        leave();
    }

    /** Offer an input for this player's entity to its region; retried shortly while the ingress ring is full. */
    void request(int kind, int a, int b, int c) {
        request(kind, 0, 0, 0, a, b, c);
    }

    private void request(int kind, int x, int y, int z, int a, int b, int c) {
        if (closed) return;
        scratch.fill((byte) 0);
        scratch.set(ValueLayout.JAVA_INT, Input.KIND, kind);
        scratch.set(ValueLayout.JAVA_INT, Input.ENTITY, entity);
        scratch.set(ValueLayout.JAVA_INT, Input.X, x);
        scratch.set(ValueLayout.JAVA_INT, Input.Y, y);
        scratch.set(ValueLayout.JAVA_INT, Input.Z, z);
        scratch.set(ValueLayout.JAVA_INT, Input.A, a);
        scratch.set(ValueLayout.JAVA_INT, Input.B, b);
        scratch.set(ValueLayout.JAVA_INT, Input.C, c);
        if (!server.engine().submitInput(scratch, 0)) {
            ctx.executor().schedule(() -> request(kind, x, y, z, a, b, c), 5, TimeUnit.MILLISECONDS);
        }
    }

    /** Set Time: the engine's completed epoch as game time, plus the world's day-time offset for the clock. */
    private void sendTime(PlayWriter w, ByteBuf out) {
        long gameTime = server.engine().completedEpoch();
        w.setTime(gameTime, Vanilla.OVERWORLD_CLOCK, gameTime + world.dayTimeOffset, out);
        timeCountdown = 20;
    }

    private void leave() {
        if (!server.engine().requestLeave(scratch, entity)) {
            ctx.executor().schedule(this::leave, 5, TimeUnit.MILLISECONDS); // ingress ring full: retry
        }
    }

    private void update() {
        if (closed || !ctx.channel().isActive()) return;
        if (!decoder.mailbox().isEmpty()) drainCold();
        if (closed) return;
        PlayWriter w = writer();
        ByteBuf out = ctx.alloc().directBuffer(16 * 1024);
        if (decoder.hasPosition()) {
            int cx = Math.floorDiv(decoder.lastX1000(), 16_000), cz = Math.floorDiv(decoder.lastZ1000(), 16_000);
            if (cx != centerX || cz != centerZ) recenter(cx, cz, w, out);
        }
        if (ctx.channel().isWritable()) stream(out);
        forwardChanges(w, out);
        trackEntities(w, out);
        syncInventory(w, out);
        if (--timeCountdown <= 0) sendTime(w, out);
        // Acknowledge one update late: by then the region has applied the action and the block updates it caused are
        // forwarded above, so the client ends its prediction with the server's answer already in hand.
        if (pendingAck > lastAcked) {
            w.acknowledgeBlockChange(pendingAck, out);
            lastAcked = pendingAck;
        }
        pendingAck = decoder.lastSequence();
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

    /** The published inventory index of menu slot {@code slot} ({@code Menus.invSlot}). */
    private static int invSlot(int kind, int slot) {
        if (kind == MENU_CRAFTING) {
            if (slot == 0) return TABLE_RESULT;
            if (slot <= 9) return TABLE_GRID + slot - 1;
            return slot - 1;
        }
        return slot;
    }

    /** {@code AbstractContainerMenu.incrementStateId}. */
    private int nextStateId() {
        return stateId = stateId + 1 & 32767;
    }

    /**
     * {@code AbstractContainerMenu.broadcastChanges} for this player's menu. When the region publishes a new
     * inventory: a new window is opened (or a server-side close is sent), and the whole menu goes out after a window
     * change or a click (the client predicted the click; the resend replaces its guess with the server's result, as
     * vanilla does on a state id mismatch); otherwise only the changed slots and the carried stack go out.
     */
    private void syncInventory(PlayWriter w, ByteBuf out) {
        int seq = world.inventorySeq(entity);
        if (seq == invSeqSeen || (seq & 1) != 0) return;
        if (world.readInventory(entity, invNow) != seq) return; // torn: retry next update
        boolean first = invSeqSeen < 0;
        invSeqSeen = seq;
        long meta = invNow[World.INV + 1];
        int window = (int) meta, clicks = (int) (meta >>> 32);
        int kind = window >>> 8 & 0xFF, container = window & 0xFF;
        boolean full = first || clicks != clicksSent;
        if (window != windowSent) {
            if (container != 0) {
                w.openWindow(container, CRAFTING_MENU_TYPE, CRAFTING_TITLE, out);
            } else if (decoder.windowCloses() == closesSeen) {
                w.closeWindow(windowSent & 0xFF, out); // closed by the server, not by the client
            }
            windowSent = window;
            full = true;
        }
        closesSeen = decoder.windowCloses();
        clicksSent = clicks;
        long carried = invNow[World.INV];
        if (full) {
            for (int m = 0; m < MENU_SLOTS; m++) menuSlots[m] = invNow[invSlot(kind, m)];
            w.windowItems(container, nextStateId(), menuSlots, 0, MENU_SLOTS, carried, out);
        } else {
            for (int m = 0; m < MENU_SLOTS; m++) {
                int i = invSlot(kind, m);
                if (invNow[i] != invSent[i]) w.setSlot(container, nextStateId(), m, invNow[i], out);
            }
            if (carried != invSent[World.INV]) w.setCursor(carried, out);
        }
        System.arraycopy(invNow, 0, invSent, 0, World.INV_PUB);
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
                if (Journal.kind(meta) == Journal.COLLECT) {
                    // ItemEntity.playerTouch → take: seen by those tracking the item (it is untracked just after)
                    int item = (int) p;
                    if (find(item) >= 0) w.collect(item, (int) (p >>> 32), (int) meta & 0xFF, out);
                    continue;
                }
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
        return new PlayerInfoUpdatePacket.Entry(profile.uuid(), profile.name(), props, true, 0,
                GameMode.values()[gameMode], null, null, 0, true);
    }

    /** A packet's id and payload (no frame), as {@link #sendRaw} takes it. */
    static byte[] body(ServerPacket packet) {
        NetworkBuffer nb = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
        PacketWriting.writeFramedPacket(nb, ConnectionState.PLAY, packet, 0);
        nb.read(NetworkBuffer.VAR_INT); // frame length
        return nb.read(NetworkBuffer.RAW_BYTES);
    }

    private static <T> byte[] serialize(int id, NetworkBuffer.Type<T> type, T packet) {
        NetworkBuffer nb = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
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

    // ---- chat, commands and game modes (cold) -------------------------------------------------------------------

    Players players() { return players; }
    int maxPlayers() { return server.maxPlayers(); }
    World world() { return world; }
    long gameTime() { return server.engine().completedEpoch(); }
    int gameMode() { return gameMode; }
    /** Last position the client reported (read from any thread: a slightly stale value only). */
    double x() { return decoder.hasPosition() ? decoder.lastX1000() / 1000.0 : spawnX; }
    double y() { return decoder.hasPosition() ? decoder.lastY1000() / 1000.0 : world.surfaceY; }
    double z() { return decoder.hasPosition() ? decoder.lastZ1000() / 1000.0 : spawnZ; }

    /** Run {@code task} on this player's event loop. */
    void run(Runnable task) {
        ChannelHandlerContext c = ctx;
        if (c != null) c.executor().execute(task);
    }

    /** A system message to this player, from its own event loop. */
    void system(Component message) {
        sendRaw(body(new SystemChatPacket(message, false)));
    }

    /** A system message to this player, from any thread. */
    void post(Component message) {
        sendTo(this, body(new SystemChatPacket(message, false)));
    }

    /** Vanilla {@code Abilities} for a game mode: invulnerable 1, flying 2, may fly 4, instant build 8. */
    static PlayerAbilitiesPacket abilities(int mode) {
        byte flags = switch (mode) {
            case World.CREATIVE -> 1 | 4 | 8;
            case World.SPECTATOR -> 1 | 2 | 4;
            default -> 0;
        };
        return new PlayerAbilitiesPacket(flags, 0.05f, 0.1f);
    }

    /** {@code ServerPlayer.setGameMode}: tell the client, its abilities, every tab list, then the region. */
    void changeGameMode(int mode) {
        if (closed || mode == gameMode) return;
        gameMode = mode;
        sendRaw(body(new ChangeGameStatePacket(ChangeGameStatePacket.Reason.CHANGE_GAMEMODE, mode)));
        sendRaw(body(abilities(mode)));
        byte[] update = serialize(Protocol.OUT_PLAYER_INFO_UPDATE, PlayerInfoUpdatePacket.SERIALIZER,
                new PlayerInfoUpdatePacket(EnumSet.of(PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), List.of(entry())));
        for (PlaySession p : Commands.all(players)) {
            if (p == this) sendRaw(update);
            else sendTo(p, update);
        }
        request(Input.GAME_MODE, mode, 0, 0);
    }

    /** Send Set Time now (after {@code /time}). */
    void resendTime() {
        if (closed || !ctx.channel().isActive()) return;
        ByteBuf out = ctx.alloc().directBuffer(64);
        sendTime(writer(), out);
        ctx.writeAndFlush(out);
    }

    /** Teleport this player (keeping its rotation); its moves are ignored until the client confirms. */
    void teleport(double x, double y, double z) {
        if (closed) return;
        int id = ++teleportId;
        decoder.expectTeleport(id);
        sendRaw(body(new PlayerPositionAndLookPacket(id, new Vec(x, y, z), Vec.ZERO, 0f, 0f, 0x08 | 0x10)));
        request(Input.POSITION, (int) Math.round(x * 1000), (int) Math.round(y * 1000), (int) Math.round(z * 1000), 0, 0, 0);
    }

    private void kick(Component reason) {
        if (closed || !ctx.channel().isActive()) return;
        byte[] packet = body(new DisconnectPacket(reason));
        ByteBuf out = ctx.alloc().directBuffer(packet.length + 8);
        writer().raw(packet, out);
        ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
        close(); // stop updating; the channel closes once the reason is out
    }

    /** Handle what the decoder parked: a copy is taken first, so re-fed packets may park new entries meanwhile. */
    private void drainCold() {
        ColdMailbox mb = decoder.mailbox();
        byte[] entries = Arrays.copyOf(mb.array(), mb.used());
        mb.clear();
        for (int at = 0; at + 8 <= entries.length && !closed; ) {
            int len = intAt(entries, at), id = intAt(entries, at + 4), start = at + 8;
            at = start + len;
            try {
                if (id < 0) inflate(entries, start, len, ~id);
                else cold(id, entries, start, len);
            } catch (RuntimeException | DataFormatException e) {
                kick(Component.translatable("disconnect.packetError"));
            }
        }
    }

    private static int intAt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    /** A compressed frame: inflate it, then handle it as a chat or command packet or decode it like any other. */
    private void inflate(byte[] b, int start, int len, int dataLength) throws DataFormatException {
        if (dataLength < server.compressionThreshold()) throw new IllegalArgumentException("badly compressed packet");
        if (inflater == null) inflater = new Inflater();
        inflater.reset();
        inflater.setInput(b, start, len);
        byte[] data = new byte[dataLength];
        int n = 0;
        while (n < dataLength && !inflater.finished()) {
            int got = inflater.inflate(data, n, dataLength - n);
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
            n += got;
        }
        if (n != dataLength) throw new IllegalArgumentException("bad compressed length");
        ByteBuf packet = Unpooled.wrappedBuffer(data);
        int id = VarInts.read(packet);
        if (id == Protocol.CHAT || id == Protocol.COMMAND || id == Protocol.SIGNED_COMMAND) {
            cold(id, data, packet.readerIndex(), packet.readableBytes());
        } else {
            feed(packet.readerIndex(0));
        }
    }

    /** Decode an inflated packet, retrying shortly while the region's ingress ring pushes back. */
    private void feed(ByteBuf packet) {
        if (closed) return;
        int mark = packet.readerIndex();
        if (!decoder.packet(packet)) {
            packet.readerIndex(mark);
            ctx.executor().schedule(() -> feed(packet), 5, TimeUnit.MILLISECONDS);
        }
    }

    private void cold(int id, byte[] b, int start, int len) {
        NetworkBuffer nb = NetworkBuffer.wrap(b, start, start + len);
        if (id == Protocol.CHAT) {
            chat(nb.read(ClientChatMessagePacket.SERIALIZER).message());
        } else if (id == Protocol.COMMAND) {
            command(nb.read(ClientCommandChatPacket.SERIALIZER).message());
        } else if (id == Protocol.SIGNED_COMMAND) {
            command(nb.read(ClientSignedCommandChatPacket.SERIALIZER).message());
        }
    }

    /** {@code StringUtil.isAllowedChatCharacter} over the whole message, and vanilla's 256-character limit. */
    private static boolean legal(String message) {
        if (message.length() > 256) return false;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\u00a7' || c < ' ' || c == 127) return false;
        }
        return true;
    }

    /** Chat: everyone sees {@code <name> message} (chat type {@code minecraft:chat}), and the console logs it. */
    private void chat(String message) {
        if (!legal(message)) {
            kick(Component.translatable("multiplayer.disconnect.illegal_characters"));
            return;
        }
        System.out.println("<" + name + "> " + message);
        byte[] packet = body(new DisguisedChatPacket(Component.text(message), Vanilla.CHAT_TYPE + 1,
                Component.text(name), null));
        for (PlaySession p : Commands.all(players)) {
            if (p == this) sendRaw(packet);
            else sendTo(p, packet);
        }
    }

    private void command(String line) {
        if (!legal(line)) {
            kick(Component.translatable("multiplayer.disconnect.illegal_characters"));
            return;
        }
        System.out.println(name + " issued server command: /" + line);
        Commands.execute(this, line);
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
            case Entities.ITEM -> 6 * 16;
            default -> 8 * 16; // zombies
        };
    }

    private static int protocolType(int type) {
        return switch (type) {
            case Entities.PLAYER -> EntityTypeId.PLAYER;
            case Entities.TNT -> EntityTypeId.TNT;
            case Entities.FALLING_BLOCK -> EntityTypeId.FALLING_BLOCK;
            case Entities.ITEM -> EntityTypeId.ITEM;
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
        if (id == entity) {
            self(s.get(ValueLayout.JAVA_INT, o + NetEntities.META), w, out);
            return;
        }
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
            if (type == Entities.PLAYER) {
                // Pairing data (ServerEntity.sendPairingData): the non-default entity data, then the equipment.
                int meta = s.get(ValueLayout.JAVA_INT, o + NetEntities.META);
                int held = s.get(ValueLayout.JAVA_INT, o + NetEntities.HELD);
                int mask = metaDiff(meta, DEFAULT_PRESENCE);
                if (mask != 0) w.playerMeta(id, meta, mask, out);
                if (held != 0) w.mainHand(id, held, out);
                trackMeta[slot] = meta;
                trackHeld[slot] = held;
                trackSwing[slot] = s.get(ValueLayout.JAVA_INT, o + NetEntities.SWING);
            } else if (type == Entities.ITEM) {
                // an item's stack rides in its entity data: DATA = item | count << 16, META = damage
                int stack = s.get(ValueLayout.JAVA_INT, o + NetEntities.DATA), damage = s.get(ValueLayout.JAVA_INT, o + NetEntities.META);
                w.itemMeta(id, itemStack(stack, damage), out);
                trackMeta[slot] = stack;
                trackHeld[slot] = damage;
            }
        } else {
            if (trackX[slot] != x || trackY[slot] != y || trackZ[slot] != z || trackYaw[slot] != yaw
                    || trackPitch[slot] != pitch) {
                boolean onGround = (s.get(ValueLayout.JAVA_INT, o + NetEntities.FLAGS) & Entities.FLAG_ON_GROUND) != 0;
                w.entitySync(id, x, y, z, vx, vy, vz, yaw, pitch, onGround, out);
                if (PlayWriter.angle(trackYaw[slot]) != PlayWriter.angle(yaw)) w.headLook(id, yaw, out);
            }
            if (type == Entities.PLAYER) {
                changes(slot, id, o, w, out);
            } else if (type == Entities.ITEM) { // merged or partly picked up
                int stack = s.get(ValueLayout.JAVA_INT, o + NetEntities.DATA), damage = s.get(ValueLayout.JAVA_INT, o + NetEntities.META);
                if (stack != trackMeta[slot] || damage != trackHeld[slot]) w.itemMeta(id, itemStack(stack, damage), out);
                trackMeta[slot] = stack;
                trackHeld[slot] = damage;
            }
        }
        trackRound[slot] = round;
        trackX[slot] = x;
        trackY[slot] = y;
        trackZ[slot] = z;
        trackYaw[slot] = yaw;
        trackPitch[slot] = pitch;
    }

    /** The {@code Stacks} word of an item entity's snapshot DATA and META. */
    private static long itemStack(int data, int damage) {
        return (data & 0xFFFFFFL) | (long) (damage & 0xFFFF) << 24;
    }

    /** A freshly joined player's presence: vanilla's default entity data (right-handed, nothing shown). */
    private static final int DEFAULT_PRESENCE = 1 << 16;

    /** {@link PlayWriter#playerMeta} mask of the entries that differ between two packed presences. */
    private static int metaDiff(int a, int b) {
        int d = a ^ b, mask = 0;
        if ((d & 0xFF) != 0) mask |= PlayWriter.META_FLAGS;
        if ((d & (31 << 17)) != 0) mask |= PlayWriter.META_POSE;
        if ((d & (1 << 16)) != 0) mask |= PlayWriter.META_HAND;
        if ((d & (0xFF << 8)) != 0) mask |= PlayWriter.META_SKIN;
        return mask;
    }

    /** A tracked player's entity data, swings and held item: send what changed since the client last saw it. */
    private void changes(int slot, int id, long o, PlayWriter w, ByteBuf out) {
        MemorySegment s = snapCopy;
        int meta = s.get(ValueLayout.JAVA_INT, o + NetEntities.META);
        int mask = metaDiff(meta, trackMeta[slot]);
        if (mask != 0) w.playerMeta(id, meta, mask, out);
        trackMeta[slot] = meta;
        int swing = s.get(ValueLayout.JAVA_INT, o + NetEntities.SWING), was = trackSwing[slot];
        if ((swing & 0xFFFF) != (was & 0xFFFF)) w.animation(id, 0, out); // SWING_MAIN_HAND
        if ((swing >>> 16) != (was >>> 16)) w.animation(id, 3, out); // SWING_OFF_HAND
        trackSwing[slot] = swing;
        int held = s.get(ValueLayout.JAVA_INT, o + NetEntities.HELD);
        if (held != trackHeld[slot]) w.mainHand(id, held, out);
        trackHeld[slot] = held;
    }

    /**
     * The player's own entity data: vanilla sends it its skin parts and main hand too (other entries are the
     * client's own prediction).
     */
    private void self(int meta, PlayWriter w, ByteBuf out) {
        int mine = meta & (0xFF << 8 | 1 << 16);
        int mask = metaDiff(mine, selfMeta) & (PlayWriter.META_SKIN | PlayWriter.META_HAND);
        if (mask != 0) w.playerMeta(entity, meta, mask, out);
        selfMeta = mine;
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
            trackMeta[hole] = trackMeta[j];
            trackSwing[hole] = trackSwing[j];
            trackHeld[hole] = trackHeld[j];
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
