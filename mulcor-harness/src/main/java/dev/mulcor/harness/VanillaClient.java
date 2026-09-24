package dev.mulcor.harness;

import dev.mulcor.net.Vanilla;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import dev.mulcor.net.Crypto;
import net.minestom.server.network.packet.client.login.ClientEncryptionResponsePacket;
import net.minestom.server.network.packet.server.login.EncryptionRequestPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.play.ClientChunkBatchReceivedPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerLoadedPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.common.PingResponsePacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.BlockActionPacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.packet.server.play.JoinGamePacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import net.minestom.server.network.packet.server.status.ResponsePacket;

/**
 * A headless stand-in for the vanilla client. It speaks the client side of the whole connection (handshake,
 * login, compression, configuration with known packs, play) over a real socket, and parses every server packet
 * with Minestom's serializers, which mirror vanilla's. Anything malformed fails the parse, so this is an
 * independent check of what Mulcor sends.
 *
 * <p>Run against a live server: {@code ./gradlew :mulcor-harness:vanillaProbe} (defaults to localhost:25565).
 */
public final class VanillaClient implements AutoCloseable {
    public final String name;
    private final Socket socket;
    private final BufferedInputStream rawIn;
    private final OutputStream rawOut;
    // Swapped for AES/CFB8 streams by the reader thread when the server asks for encryption.
    private volatile DataInputStream in;
    private volatile OutputStream out;
    private final Thread reader;
    private volatile ConnectionState state = ConnectionState.HANDSHAKE;
    private volatile int threshold;
    private final Inflater inflater = new Inflater();

    // What the server has told us so far.
    public volatile int entityId = -1;
    public volatile Pos position;
    public volatile int viewCenterX = Integer.MIN_VALUE, viewCenterZ = Integer.MIN_VALUE;
    public volatile int lastAckedSequence = -1;
    public volatile String disconnectReason;
    /** True once both directions are encrypted. */
    public volatile boolean encrypted;
    /** Whether the server's Encryption Request asked the client to authenticate with the session server. */
    public volatile boolean authenticationRequested;
    /** The session hash this client would have sent to Mojang's {@code join} endpoint. */
    public volatile String serverHash;
    /** The profile from Login Success (online mode: the session server's profile). */
    public volatile GameProfile profile;
    public volatile Throwable failure;
    public final AtomicInteger registryPackets = new AtomicInteger(), tagPackets = new AtomicInteger();
    public final AtomicInteger keepAlives = new AtomicInteger(), batches = new AtomicInteger();
    public final Map<Long, ChunkDataPacket> chunks = new ConcurrentHashMap<>();
    /** Entities the server spawned on this client and has not removed, by entity id. */
    public final Map<Integer, SpawnEntityPacket> entities = new ConcurrentHashMap<>();
    /** The tab list: UUID → name. */
    public final Map<java.util.UUID, String> tabList = new ConcurrentHashMap<>();
    /** The latest block update per position ({@link #blockKey}). */
    public final Map<Long, Integer> blockUpdates = new ConcurrentHashMap<>();
    public final AtomicInteger entitySyncs = new AtomicInteger(), blockEvents = new AtomicInteger();

    private VanillaClient(String host, int port, String name) throws IOException {
        this.name = name;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        this.rawIn = new BufferedInputStream(socket.getInputStream());
        this.rawOut = socket.getOutputStream();
        this.in = new DataInputStream(rawIn);
        this.out = rawOut;
        send(new ClientHandshakePacket(Vanilla.PROTOCOL, host, port, ClientHandshakePacket.Intent.LOGIN));
        state = ConnectionState.LOGIN;
        send(new ClientLoginStartPacket(name, UUID.randomUUID()));
        this.reader = Thread.ofPlatform().daemon().name("vanilla-client-" + name).start(this::readLoop);
    }

    /** Connect and log in; returns once the server has sent Join Game and the spawn teleport. */
    public static VanillaClient join(String host, int port, String name, Duration timeout) throws IOException {
        VanillaClient c = new VanillaClient(host, port, name);
        c.await(() -> c.entityId >= 0 && c.position != null, timeout, "join");
        return c;
    }

    public static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }

    public ConnectionState state() { return state; }

    /** Wait for a condition, failing fast if the connection broke. */
    public void await(BooleanSupplier condition, Duration timeout, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (failure != null) throw new IllegalStateException(name + ": connection failed while waiting for " + what, failure);
            if (disconnectReason != null) throw new IllegalStateException(name + ": disconnected while waiting for " + what + ": " + disconnectReason);
            if (System.nanoTime() > deadline) throw new IllegalStateException(name + ": timed out waiting for " + what);
            Thread.onSpinWait();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        send(new ClientPlayerPositionAndRotationPacket(new Pos(x, y, z, yaw, pitch), true, false));
    }

    public void startDigging(int x, int y, int z, int sequence) {
        send(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, new Vec(x, y, z), BlockFace.TOP, sequence));
    }

    public synchronized void send(ClientPacket packet) {
        NetworkBuffer buf = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
        PacketWriting.writeFramedPacket(buf, state, packet, threshold);
        byte[] bytes = new byte[(int) buf.writeIndex()];
        buf.copyTo(0, bytes, 0, bytes.length);
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void readLoop() {
        try {
            while (!socket.isClosed()) handle(readPacket());
        } catch (EOFException | java.net.SocketException closed) {
            if (disconnectReason == null && !socket.isClosed()) disconnectReason = "connection closed by server";
        } catch (Throwable t) {
            failure = t;
        }
    }

    private ServerPacket readPacket() throws IOException, DataFormatException {
        int length = readVarInt(in);
        byte[] frame = new byte[length];
        in.readFully(frame);
        NetworkBuffer buf = NetworkBuffer.wrap(frame, 0, frame.length, Vanilla.REGISTRIES);
        if (threshold > 0) {
            int dataLength = buf.read(NetworkBuffer.VAR_INT);
            if (dataLength != 0) {
                if (dataLength < threshold) throw new DataFormatException("compressed a packet below the threshold");
                inflater.reset();
                inflater.setInput(frame, (int) buf.readIndex(), frame.length - (int) buf.readIndex());
                byte[] payload = new byte[dataLength];
                if (inflater.inflate(payload) != dataLength) throw new DataFormatException("short inflate");
                buf = NetworkBuffer.wrap(payload, 0, payload.length, Vanilla.REGISTRIES);
            }
        }
        int id = buf.read(NetworkBuffer.VAR_INT);
        ServerPacket p = PacketVanilla.SERVER_PACKET_PARSER.parse(state, id, buf);
        if (buf.readableBytes() != 0) {
            throw new IllegalStateException(p.getClass().getSimpleName() + " left " + buf.readableBytes() + " unread bytes");
        }
        return p;
    }

    private void handle(ServerPacket packet) {
        switch (packet) {
            case SetCompressionPacket c -> threshold = c.threshold();
            case EncryptionRequestPacket r -> enableEncryption(r);
            case LoginSuccessPacket s -> {
                profile = s.gameProfile();
                send(new ClientLoginAcknowledgedPacket());
                state = ConnectionState.CONFIGURATION;
            }
            case LoginDisconnectPacket d -> disconnectReason = d.kickMessage().toString();
            case SelectKnownPacksPacket k -> send(new ClientSelectKnownPacksPacket(List.of(SelectKnownPacksPacket.MINECRAFT_CORE)));
            case RegistryDataPacket r -> registryPackets.incrementAndGet();
            case TagsPacket t -> tagPackets.incrementAndGet();
            case FinishConfigurationPacket f -> {
                send(new ClientFinishConfigurationPacket());
                state = ConnectionState.PLAY;
            }
            case JoinGamePacket j -> entityId = j.entityId();
            case PlayerPositionAndLookPacket p -> {
                send(new ClientTeleportConfirmPacket(p.teleportId()));
                position = new Pos(p.position().x(), p.position().y(), p.position().z(), p.yaw(), p.pitch());
                send(new ClientPlayerLoadedPacket());
            }
            case UpdateViewPositionPacket v -> {
                viewCenterX = v.chunkX();
                viewCenterZ = v.chunkZ();
            }
            case ChunkDataPacket c -> chunks.put(chunkKey(c.chunkX(), c.chunkZ()), c);
            case ChunkBatchFinishedPacket b -> {
                batches.incrementAndGet();
                send(new ClientChunkBatchReceivedPacket(64f));
            }
            case AcknowledgeBlockChangePacket a -> lastAckedSequence = a.sequence();
            case SpawnEntityPacket s -> entities.put(s.entityId(), s);
            case DestroyEntitiesPacket d -> d.entityIds().forEach(entities::remove);
            case EntityPositionSyncPacket s -> entitySyncs.incrementAndGet();
            case PlayerInfoUpdatePacket p -> {
                if (p.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    for (var e : p.entries()) tabList.put(e.uuid(), e.username());
                }
            }
            case PlayerInfoRemovePacket r -> r.uuids().forEach(tabList::remove);
            case BlockChangePacket b -> blockUpdates.put(blockKey(b.blockPosition().blockX(), b.blockPosition().blockY(),
                    b.blockPosition().blockZ()), b.blockStateId());
            case BlockActionPacket b -> blockEvents.incrementAndGet();
            case KeepAlivePacket k -> {
                keepAlives.incrementAndGet();
                send(new ClientKeepAlivePacket(k.id()));
            }
            case DisconnectPacket d -> disconnectReason = d.message().toString();
            default -> { }
        }
    }

    /**
     * Vanilla's client side of Encryption Request: pick a random AES secret, RSA-encrypt it and the verify token
     * with the server's key, send them in the clear, then switch both directions to AES/CFB8. (A real online-mode
     * client would first call Mojang's {@code join} with {@link #serverHash}; tests use a fake session server.)
     */
    private void enableEncryption(EncryptionRequestPacket r) {
        try {
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(r.publicKey())));
            byte[] encSecret = rsa.doFinal(secret), encToken = rsa.doFinal(r.verifyToken());
            var key = new SecretKeySpec(secret, "AES");
            var enc = Cipher.getInstance("AES/CFB8/NoPadding");
            enc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(secret));
            var dec = Cipher.getInstance("AES/CFB8/NoPadding");
            dec.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(secret));
            serverHash = Crypto.serverHash(r.serverId(), secret, r.publicKey());
            authenticationRequested = r.shouldAuthenticate();
            synchronized (this) {
                send(new ClientEncryptionResponsePacket(encSecret, encToken));
                out = new CipherOutputStream(rawOut, enc);
            }
            in = new DataInputStream(new CipherInputStream(rawIn, dec)); // only this (reader) thread reads
            encrypted = true;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Server-list ping: returns the status JSON, after checking that the ping is echoed. */
    public static String status(String host, int port) throws IOException, DataFormatException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            var probe = new Object() {
                void write(ConnectionState st, ClientPacket p) throws IOException {
                    NetworkBuffer buf = NetworkBuffer.resizableBuffer();
                    PacketWriting.writeFramedPacket(buf, st, p, 0);
                    byte[] bytes = new byte[(int) buf.writeIndex()];
                    buf.copyTo(0, bytes, 0, bytes.length);
                    s.getOutputStream().write(bytes);
                }

                ServerPacket read(DataInputStream din) throws IOException {
                    byte[] frame = new byte[readVarInt(din)];
                    din.readFully(frame);
                    NetworkBuffer buf = NetworkBuffer.wrap(frame, 0, frame.length);
                    return PacketVanilla.SERVER_PACKET_PARSER.parse(ConnectionState.STATUS, buf.read(NetworkBuffer.VAR_INT), buf);
                }
            };
            probe.write(ConnectionState.HANDSHAKE, new ClientHandshakePacket(Vanilla.PROTOCOL, host, port, ClientHandshakePacket.Intent.STATUS));
            probe.write(ConnectionState.STATUS, new StatusRequestPacket());
            var din = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            String json = ((ResponsePacket) probe.read(din)).jsonResponse();
            probe.write(ConnectionState.STATUS, new ClientPingRequestPacket(1234567L));
            if (((PingResponsePacket) probe.read(din)).number() != 1234567L) throw new IOException("bad pong");
            return json;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too long");
    }

    @Override
    public void close() throws IOException {
        socket.close();
        inflater.end();
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Manual check against a running server: join, walk, dig, report what came back. */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        System.out.println("status: " + status(host, port));
        try (VanillaClient c = join(host, port, "probe", Duration.ofSeconds(30))) {
            System.out.printf("joined as entity %d at %s; %d registry packets, %d tag packets%n",
                    c.entityId, c.position, c.registryPackets.get(), c.tagPackets.get());
            c.await(() -> c.batches.get() > 0, Duration.ofSeconds(10), "first chunk batch");
            Thread.sleep(500);
            System.out.printf("received %d chunks in %d batches, view centre %d,%d%n", c.chunks.size(), c.batches.get(),
                    c.viewCenterX, c.viewCenterZ);
            Pos p = c.position;
            for (int step = 1; step <= 40; step++) {
                c.moveTo(p.x() + step, p.y(), p.z() + step / 2.0, step * 9f, 0f);
                Thread.sleep(50);
            }
            Thread.sleep(500);
            System.out.printf("after walking 40 blocks: view centre %d,%d, %d chunks held%n", c.viewCenterX, c.viewCenterZ, c.chunks.size());
        }
    }
}
