package dev.mulcor.net;

import dev.mulcor.core.Blocks;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;

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

    public static final int PALETTE_MIN_BITS = Palette.BLOCK_PALETTE_MIN_BITS;
    public static final int PALETTE_MAX_BITS = Palette.BLOCK_PALETTE_MAX_BITS;
    public static final int PALETTE_DIRECT_BITS = Palette.BLOCK_PALETTE_DIRECT_BITS;

    /** Mulcor block id → vanilla block-state id (air for unknown ids). */
    private static final int[] STATE_IDS = new int[Blocks.WIRE + 16];

    static {
        java.util.Arrays.fill(STATE_IDS, Block.AIR.stateId());
        STATE_IDS[Blocks.AIR] = Block.AIR.stateId();
        STATE_IDS[Blocks.BEDROCK] = Block.BEDROCK.stateId();
        STATE_IDS[Blocks.STONE] = Block.STONE.stateId();
        STATE_IDS[Blocks.DIRT] = Block.DIRT.stateId();
        STATE_IDS[Blocks.CHEST] = Block.CHEST.stateId();
        STATE_IDS[Blocks.TNT] = Block.TNT.stateId();
        STATE_IDS[Blocks.REDSTONE_BLOCK] = Block.REDSTONE_BLOCK.stateId();
        for (int p = 0; p < 16; p++) {
            STATE_IDS[Blocks.WIRE + p] = Block.REDSTONE_WIRE.withProperty("power", String.valueOf(p)).stateId();
        }
    }

    private Protocol() {}

    private static int id(Class<?> packet) {
        return PacketVanilla.CLIENT_PACKET_PARSER.play().packetInfo(packet).id();
    }

    public static int vanillaState(int mulcorState) {
        return mulcorState >= 0 && mulcorState < STATE_IDS.length ? STATE_IDS[mulcorState] : STATE_IDS[0];
    }
}
