package dev.mulcor.net;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionStatusPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket;

/** Test oracle: real vanilla-format frames produced by Minestom's own serializers. */
final class Packets {
    private Packets() {}

    private static <T> byte[] frame(int id, NetworkBuffer.Type<T> serializer, T packet) {
        NetworkBuffer body = NetworkBuffer.resizableBuffer();
        body.write(NetworkBuffer.VAR_INT, id);
        body.write(serializer, packet);
        byte[] payload = body.read(NetworkBuffer.RAW_BYTES);
        NetworkBuffer framed = NetworkBuffer.resizableBuffer();
        framed.write(NetworkBuffer.VAR_INT, payload.length);
        framed.write(NetworkBuffer.RAW_BYTES, payload);
        return framed.read(NetworkBuffer.RAW_BYTES);
    }

    static byte[] dig(int x, int y, int z) {
        return frame(Protocol.DIG, ClientPlayerActionPacket.SERIALIZER,
                new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING, new Vec(x, y, z), BlockFace.TOP, 7));
    }

    static byte[] cancelDig(int x, int y, int z) {
        return frame(Protocol.DIG, ClientPlayerActionPacket.SERIALIZER,
                new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.CANCELLED_DIGGING, new Vec(x, y, z), BlockFace.TOP, 8));
    }

    static byte[] place(int x, int y, int z, BlockFace face) {
        return frame(Protocol.PLACE, ClientPlayerBlockPlacementPacket.SERIALIZER,
                new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), face, 0.5f, 0.5f, 0.5f, false, false, 9));
    }

    static byte[] position(double x, double y, double z) {
        return frame(Protocol.POSITION, ClientPlayerPositionPacket.SERIALIZER, new ClientPlayerPositionPacket(new Vec(x, y, z), true, false));
    }

    static byte[] positionRotation(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        return frame(Protocol.POSITION_ROTATION, ClientPlayerPositionAndRotationPacket.SERIALIZER,
                new ClientPlayerPositionAndRotationPacket(new Pos(x, y, z, yaw, pitch), onGround, false));
    }

    static byte[] rotation(float yaw, float pitch, boolean onGround) {
        return frame(Protocol.ROTATION, ClientPlayerRotationPacket.SERIALIZER, new ClientPlayerRotationPacket(yaw, pitch, onGround, false));
    }

    static byte[] ground(boolean onGround) {
        return frame(Protocol.GROUND, ClientPlayerPositionStatusPacket.SERIALIZER, new ClientPlayerPositionStatusPacket(onGround, false));
    }

    static byte[] placeSeq(int x, int y, int z, int sequence) {
        return frame(Protocol.PLACE, ClientPlayerBlockPlacementPacket.SERIALIZER,
                new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), BlockFace.TOP, 0.25f, 1f, 0.75f, true, false, sequence));
    }

    static byte[] click(int window, int slot, int button, ClientClickWindowPacket.ClickType type) {
        return frame(Protocol.CLICK_WINDOW, ClientClickWindowPacket.SERIALIZER, new ClientClickWindowPacket(window, 1,
                (short) slot, (byte) button, type, Map.of((short) slot, ItemStack.Hash.AIR), ItemStack.Hash.AIR));
    }

    /** A cold packet: some other play id with an arbitrary body. */
    static byte[] cold() {
        int id = 0;
        while (id == Protocol.DIG || id == Protocol.PLACE || id == Protocol.POSITION || id == Protocol.CLICK_WINDOW
                || id == Protocol.POSITION_ROTATION || id == Protocol.ROTATION || id == Protocol.GROUND) id++;
        return new byte[] {3, (byte) id, 42, 43};
    }

    static void write(ByteBuf buf, byte[]... frames) {
        for (byte[] f : frames) buf.writeBytes(f);
    }
}
