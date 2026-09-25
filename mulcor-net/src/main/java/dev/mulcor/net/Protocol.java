package dev.mulcor.net;

import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.common.ClientSettingsPacket;
import net.minestom.server.network.packet.client.play.ClientAnimationPacket;
import net.minestom.server.network.packet.client.play.ClientChatMessagePacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.server.network.packet.client.play.ClientStatusPacket;
import net.minestom.server.network.packet.client.play.ClientCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientEntityActionPacket;
import net.minestom.server.network.packet.client.play.ClientInputPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerAbilitiesPacket;
import net.minestom.server.network.packet.client.play.ClientSignedCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionStatusPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockActionPacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.CloseWindowPacket;
import net.minestom.server.network.packet.server.play.CollectItemPacket;
import net.minestom.server.network.packet.server.play.OpenWindowPacket;
import net.minestom.server.network.packet.server.play.SetCursorItemPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.server.network.packet.server.play.WindowItemsPacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityAnimationPacket;
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket;
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SetTimePacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
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
    public static final int CREATIVE_SLOT = id(ClientCreativeInventoryActionPacket.class);
    public static final int HELD_ITEM = id(ClientHeldItemChangePacket.class);
    public static final int SWING = id(ClientAnimationPacket.class);
    public static final int PLAYER_INPUT = id(ClientInputPacket.class);
    public static final int PLAYER_COMMAND = id(ClientEntityActionPacket.class);
    public static final int SETTINGS = id(ClientSettingsPacket.class);
    public static final int ABILITIES = id(ClientPlayerAbilitiesPacket.class);
    public static final int CLOSE_WINDOW = id(ClientCloseWindowPacket.class);
    public static final int CLIENT_STATUS = id(ClientStatusPacket.class);
    public static final int TELEPORT_CONFIRM = id(ClientTeleportConfirmPacket.class);
    // Cold play packets the decoder hands to the session's mailbox (see ColdMailbox).
    public static final int CHAT = id(ClientChatMessagePacket.class);
    public static final int COMMAND = id(ClientCommandChatPacket.class);
    public static final int SIGNED_COMMAND = id(ClientSignedCommandChatPacket.class);

    // Server → client play packets written without Minestom objects (see PlayWriter).
    public static final int OUT_CHUNK_DATA = serverId(ChunkDataPacket.class);
    public static final int OUT_KEEP_ALIVE = serverId(KeepAlivePacket.class);
    public static final int OUT_VIEW_CENTER = serverId(UpdateViewPositionPacket.class);
    public static final int OUT_BATCH_START = serverId(ChunkBatchStartPacket.class);
    public static final int OUT_BATCH_FINISHED = serverId(ChunkBatchFinishedPacket.class);
    public static final int OUT_ACK_BLOCK = serverId(AcknowledgeBlockChangePacket.class);
    public static final int OUT_BLOCK_UPDATE = serverId(BlockChangePacket.class);
    public static final int OUT_BLOCK_EVENT = serverId(BlockActionPacket.class);
    public static final int OUT_ADD_ENTITY = serverId(SpawnEntityPacket.class);
    public static final int OUT_ENTITY_SYNC = serverId(EntityPositionSyncPacket.class);
    public static final int OUT_REMOVE_ENTITIES = serverId(DestroyEntitiesPacket.class);
    public static final int OUT_PLAYER_INFO_UPDATE = serverId(PlayerInfoUpdatePacket.class);
    public static final int OUT_PLAYER_INFO_REMOVE = serverId(PlayerInfoRemovePacket.class);
    public static final int OUT_METADATA = serverId(EntityMetaDataPacket.class);
    public static final int OUT_EQUIPMENT = serverId(EntityEquipmentPacket.class);
    public static final int OUT_ANIMATION = serverId(EntityAnimationPacket.class);
    public static final int OUT_HEAD_LOOK = serverId(EntityHeadLookPacket.class);
    public static final int OUT_SET_TIME = serverId(SetTimePacket.class);
    public static final int OUT_SET_SLOT = serverId(SetSlotPacket.class);
    public static final int OUT_WINDOW_ITEMS = serverId(WindowItemsPacket.class);
    public static final int OUT_SET_CURSOR = serverId(SetCursorItemPacket.class);
    public static final int OUT_OPEN_WINDOW = serverId(OpenWindowPacket.class);
    public static final int OUT_CLOSE_WINDOW = serverId(CloseWindowPacket.class);
    public static final int OUT_COLLECT = serverId(CollectItemPacket.class);
    public static final int OUT_HEALTH = serverId(UpdateHealthPacket.class);

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
