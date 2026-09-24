package dev.mulcor.net;

import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionStatusPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.packet.server.play.ChunkBatchStartPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;

/**
 * Protocol constants taken from Minestom, used strictly as a protocol and serialization library: packet ids for
 * the play state, the block palette limits, and the mapping from Mulcor block ids to vanilla block-state ids.
 * Minestom's server, instance manager and tick loop are never started.
 */
public final class Protocol {
    public static final int DIG = id(ClientPlayerActionPacket.class);
    public static final int POSITION = id(ClientPlayerPositionPacket.class);
    public static final int PLACE = id(ClientPlayerBlockPlacementPacket.class);
    public static final int CLICK_WINDOW = id(ClientClickWindowPacket.class);
    public static final int POSITION_ROTATION = id(ClientPlayerPositionAndRotationPacket.class);
    public static final int ROTATION = id(ClientPlayerRotationPacket.class);
    public static final int GROUND = id(ClientPlayerPositionStatusPacket.class);

    // Server → client play packets written without Minestom objects (see PlayWriter).
    public static final int OUT_CHUNK_DATA = serverId(ChunkDataPacket.class);
    public static final int OUT_KEEP_ALIVE = serverId(KeepAlivePacket.class);
    public static final int OUT_VIEW_CENTER = serverId(UpdateViewPositionPacket.class);
    public static final int OUT_BATCH_START = serverId(ChunkBatchStartPacket.class);
    public static final int OUT_BATCH_FINISHED = serverId(ChunkBatchFinishedPacket.class);
    public static final int OUT_ACK_BLOCK = serverId(AcknowledgeBlockChangePacket.class);

    public static final int PALETTE_MIN_BITS = Palette.BLOCK_PALETTE_MIN_BITS;
    public static final int PALETTE_MAX_BITS = Palette.BLOCK_PALETTE_MAX_BITS;
    public static final int PALETTE_DIRECT_BITS = Palette.BLOCK_PALETTE_DIRECT_BITS;

    private Protocol() {}

    private static int id(Class<?> packet) {
        return PacketVanilla.CLIENT_PACKET_PARSER.play().packetInfo(packet).id();
    }

    private static int serverId(Class<?> packet) {
        return PacketVanilla.SERVER_PACKET_PARSER.play().packetInfo(packet).id();
    }

    /**
     * Block ids in Mulcor's storage are vanilla global state ids (see {@code dev.mulcor.registry.BlockData}), so the
     * mapping to the wire is the identity; unknown ids become air.
     */
    public static int vanillaState(int state) {
        return state >= 0 && state < dev.mulcor.registry.Registry.stateCount() ? state : 0;
    }

    /** Whether the state carries a non-empty fluid (water, lava, waterlogged): the chunk packet's fluid count. */
    public static boolean isFluid(int state) {
        return state >= 0 && state < dev.mulcor.registry.Registry.stateCount()
                && dev.mulcor.registry.BlockData.is(state, dev.mulcor.registry.BlockData.FLUID);
    }
}
