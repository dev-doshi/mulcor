package dev.mulcor.net;

import dev.mulcor.core.JoinTickets;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.common.PingResponsePacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.JoinGamePacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.SpawnPositionPacket;
import net.minestom.server.network.packet.server.play.data.PlayerSpawnInfo;
import net.minestom.server.network.packet.server.play.data.WorldPos;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.registry.Registries;

/**
 * The vanilla connection sequence up to the first play packet. It runs once per connection, so it uses
 * Minestom's packet objects and serializers freely (allocation here is not on the tick path).
 *
 * <pre>
 * HANDSHAKE ─intent 1─▶ STATUS: status request → server list JSON; ping → pong, close
 * HANDSHAKE ─intent 2─▶ LOGIN:  Login Start → Set Compression, Login Success
 *                               Login Acknowledged ─▶ CONFIGURATION
 * CONFIGURATION: brand, Select Known Packs → client's known packs → registry data (only what the client lacks),
 *                tags, enabled features, Finish Configuration → client's Finish ─▶ PLAY
 * PLAY: JOIN input to the owning region → poll the join ticket → Join Game, spawn position, teleport,
 *       "waiting for chunks" → hand the channel to {@link PlaySession} + {@link IngressHandler}
 * </pre>
 *
 * Offline mode: no encryption or Mojang authentication, UUIDs are derived from the name like vanilla's
 * {@code online-mode=false}.
 */
public final class LoginHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_FRAME = 2 * 1024 * 1024;

    private final ServerContext server;
    private final MemorySegment scratch = NativeMemory.auto().allocate(Input.BYTES);
    private ConnectionState state = ConnectionState.HANDSHAKE;
    private boolean compression, spawning;
    private ByteBuf cumulation;
    private ChannelHandlerContext ctx;
    private Inflater inflater;
    private String name;
    private UUID uuid;
    private double spawnX, spawnY, spawnZ;
    private int ticket = -1;
    private long joinStarted;

    public LoginHandler(ServerContext server) {
        this.server = server;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.cumulation = ctx.alloc().buffer(1024);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        if (inflater != null) inflater.end();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        spawning = false; // a pending join is completed and cleaned up by pollJoin
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            cumulation.writeBytes(in);
        } finally {
            in.release();
        }
        try {
            drain();
        } catch (RuntimeException | DataFormatException e) {
            disconnect("Protocol error: " + e.getMessage());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private void drain() throws DataFormatException {
        // Once the client is in PLAY, its bytes wait here until the ingress pipeline takes over.
        while (state != ConnectionState.PLAY && cumulation != null && cumulation.isReadable()) {
            int start = cumulation.readerIndex();
            long len = VarInts.peek(cumulation, start, cumulation.writerIndex());
            if (len == VarInts.INCOMPLETE) return;
            if (len == VarInts.MALFORMED || (int) len < 0 || (int) len > MAX_FRAME) throw new IllegalStateException("bad frame length");
            int body = start + (int) (len >>> 32), end = body + (int) len;
            if (end > cumulation.writerIndex()) return;
            byte[] frame = new byte[(int) len];
            cumulation.getBytes(body, frame);
            cumulation.readerIndex(end);
            handle(parse(frame));
        }
        cumulation.discardSomeReadBytes();
    }

    private ClientPacket parse(byte[] frame) throws DataFormatException {
        NetworkBuffer buf = NetworkBuffer.wrap(frame, 0, frame.length);
        byte[] payload = frame;
        if (compression) {
            int dataLength = buf.read(NetworkBuffer.VAR_INT);
            if (dataLength != 0) {
                if (dataLength > MAX_FRAME) throw new DataFormatException("inflated frame too large");
                if (inflater == null) inflater = new Inflater();
                inflater.reset();
                inflater.setInput(frame, (int) buf.readIndex(), frame.length - (int) buf.readIndex());
                payload = new byte[dataLength];
                if (inflater.inflate(payload) != dataLength) throw new DataFormatException("short inflate");
                buf = NetworkBuffer.wrap(payload, 0, payload.length);
            }
        }
        int id = buf.read(NetworkBuffer.VAR_INT);
        return PacketVanilla.CLIENT_PACKET_PARSER.parse(state, id, buf);
    }

    private void handle(ClientPacket packet) {
        switch (packet) {
            case ClientHandshakePacket h -> {
                if (h.intent() == ClientHandshakePacket.Intent.STATUS) {
                    state = ConnectionState.STATUS;
                } else {
                    state = ConnectionState.LOGIN;
                    if (h.protocolVersion() != Vanilla.PROTOCOL) {
                        disconnect("Mulcor speaks Minecraft " + Vanilla.VERSION + " (protocol " + Vanilla.PROTOCOL
                                + "); your client uses protocol " + h.protocolVersion() + ".");
                    }
                }
            }
            case StatusRequestPacket ignored -> send(new net.minestom.server.network.packet.server.status.ResponsePacket(statusJson()));
            case ClientPingRequestPacket ping -> {
                send(new PingResponsePacket(ping.number()));
                ctx.flush();
                ctx.close();
            }
            case ClientLoginStartPacket login -> {
                name = login.username();
                uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                if (server.compressionThreshold() > 0) {
                    send(new SetCompressionPacket(server.compressionThreshold()));
                    compression = true; // every later frame, both ways, carries the data-length header
                }
                send(new LoginSuccessPacket(new GameProfile(uuid, name), new UUID(0, 0)));
            }
            case ClientLoginAcknowledgedPacket ignored -> {
                state = ConnectionState.CONFIGURATION;
                send(PluginMessagePacket.brandPacket("Mulcor"));
                send(new SelectKnownPacksPacket(List.of(SelectKnownPacksPacket.MINECRAFT_CORE)));
            }
            case ClientSelectKnownPacksPacket known -> {
                // A client that has the vanilla core pack only needs the registry entry names, not their data.
                boolean hasCore = known.entries().contains(SelectKnownPacksPacket.MINECRAFT_CORE);
                for (SendablePacket p : Registries.registryDataPackets(Vanilla.REGISTRIES, hasCore)) {
                    send(SendablePacket.extractServerPacket(ConnectionState.CONFIGURATION, p));
                }
                send(Registries.tagsPacket(Vanilla.REGISTRIES));
                send(new UpdateEnabledFeaturesPacket(List.of("minecraft:vanilla")));
                send(new FinishConfigurationPacket());
            }
            case ClientFinishConfigurationPacket ignored -> {
                state = ConnectionState.PLAY;
                spawnX = server.spawnX() + 0.5;
                spawnZ = server.spawnZ() + 0.5;
                spawnY = server.engine().surfaceAt(server.spawnX(), server.spawnZ());
                spawning = true;
                joinStarted = System.nanoTime();
                requestJoin();
            }
            default -> { } // client settings, plugin messages, keep-alives, cookies: nothing to do
        }
        ctx.flush();
    }

    /** Offer the JOIN input; if the region's ring or the ticket table is full, try again shortly. */
    private void requestJoin() {
        if (!spawning) return;
        ticket = server.engine().requestJoin(scratch, spawnX, spawnY, spawnZ);
        if (ticket < 0) {
            ctx.executor().schedule(this::requestJoin, 5, TimeUnit.MILLISECONDS);
        } else {
            pollJoin();
        }
    }

    /** The region spawns the player during its next tick; check the ticket from this event loop until it has. */
    private void pollJoin() {
        int entity = server.engine().pollJoin(ticket);
        if (entity == JoinTickets.PENDING) {
            if (System.nanoTime() - joinStarted > TimeUnit.SECONDS.toNanos(30)) {
                // The engine is not ticking. Keep polling in the background so a late spawn is still cleaned up.
                if (spawning) disconnect("The server did not spawn you in time.");
            }
            ctx.executor().schedule(this::pollJoin, 1, TimeUnit.MILLISECONDS);
            return;
        }
        if (entity == JoinTickets.FAILED) {
            disconnect("The server is full.");
            return;
        }
        if (!spawning || !ctx.channel().isActive()) {
            // Client left while we were spawning it: remove the orphan.
            if (!server.engine().requestLeave(scratch, entity)) {
                ctx.executor().schedule(() -> removeOrphan(entity), 5, TimeUnit.MILLISECONDS);
            }
            return;
        }
        play(entity);
    }

    private void removeOrphan(int entity) {
        if (!server.engine().requestLeave(scratch, entity)) {
            ctx.executor().schedule(() -> removeOrphan(entity), 5, TimeUnit.MILLISECONDS);
        }
    }

    private void play(int entity) {
        state = ConnectionState.PLAY;
        int view = server.viewDistance();
        send(new JoinGamePacket(entity, false, List.of(Vanilla.WORLD), server.maxPlayers(), view, view, false, true,
                false, new PlayerSpawnInfo(Vanilla.OVERWORLD_ID, Vanilla.WORLD, 0L, GameMode.CREATIVE, null, false,
                        true, null, 0, 63), false, false));
        send(new SpawnPositionPacket(new WorldPos(Vanilla.WORLD, new Vec(spawnX, spawnY, spawnZ)), 0f, 0f));
        send(new PlayerPositionAndLookPacket(1, new Vec(spawnX, spawnY, spawnZ), Vec.ZERO, 0f, 0f, 0));
        send(new ChangeGameStatePacket(ChangeGameStatePacket.Reason.LEVEL_CHUNKS_LOAD_START, 0f));
        ctx.flush();

        var decoder = new IngressDecoder(entity, server.sink(), compression);
        var ingress = new IngressHandler(decoder, new TokenBucket(server.perConnectionBurst(), server.perConnectionRate()),
                server.globalBucket());
        var session = new PlaySession(server, entity, decoder, compression ? server.compressionThreshold() : 0,
                spawnX, spawnZ, name);
        ByteBuf leftover = cumulation.isReadable() ? cumulation.retainedSlice() : null;
        ctx.pipeline().addAfter(ctx.name(), "mulcor-play", session);
        ctx.pipeline().addAfter("mulcor-play", "mulcor-ingress", ingress);
        ctx.pipeline().remove(this);
        if (leftover != null) session.ctx().fireChannelRead(leftover); // play packets that arrived early
    }

    private String statusJson() {
        return "{\"version\":{\"name\":\"" + Vanilla.VERSION + "\",\"protocol\":" + Vanilla.PROTOCOL + "},"
                + "\"players\":{\"max\":" + server.maxPlayers() + ",\"online\":" + server.online().get() + "},"
                + "\"description\":{\"text\":\"Mulcor: regionized, lock-free, zero-allocation\"}}";
    }

    private void send(ServerPacket packet) {
        NetworkBuffer buf = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
        // Set Compression itself is the last uncompressed frame.
        boolean framedCompressed = compression && !(packet instanceof SetCompressionPacket);
        PacketWriting.writeFramedPacket(buf, state, packet, framedCompressed ? server.compressionThreshold() : 0);
        byte[] bytes = new byte[(int) buf.writeIndex()];
        buf.copyTo(0, bytes, 0, bytes.length);
        ctx.write(Unpooled.wrappedBuffer(bytes));
    }

    private void disconnect(String reason) {
        spawning = false;
        if (state == ConnectionState.LOGIN) {
            send(new LoginDisconnectPacket(Component.text(reason)));
        } else if (state == ConnectionState.CONFIGURATION || state == ConnectionState.PLAY) {
            send(new DisconnectPacket(Component.text(reason)));
        }
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        state = ConnectionState.PLAY; // stop parsing
    }
}
