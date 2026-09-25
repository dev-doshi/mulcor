package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link PlayWriter}'s entity packets are byte-identical to Minestom's (vanilla-mirroring) serializers. */
class EntityPacketsTest {
    private static byte[] body(ByteBuf framed) {
        NetworkBuffer nb = NetworkBuffer.wrap(bytes(framed), 0, framed.readableBytes());
        nb.read(NetworkBuffer.VAR_INT); // frame length (uncompressed)
        return nb.read(NetworkBuffer.RAW_BYTES);
    }

    private static byte[] bytes(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.getBytes(b.readerIndex(), out);
        return out;
    }

    private static <T> byte[] minestom(int id, NetworkBuffer.Type<T> type, T packet) {
        NetworkBuffer nb = NetworkBuffer.resizableBuffer();
        nb.write(NetworkBuffer.VAR_INT, id);
        nb.write(type, packet);
        return nb.read(NetworkBuffer.RAW_BYTES);
    }

    /**
     * Angles are compared at whole 1/256 turns: vanilla's {@code Mth.packDegrees} floors ({@code (byte) floor(d * 256 /
     * 360)}), Minestom rounds, and they agree only there. Between steps Mulcor follows vanilla.
     */
    @Test
    void anglesFloorLikeVanilla() {
        org.junit.jupiter.api.Assertions.assertEquals((byte) -22, (byte) PlayWriter.angle(-30f));
        org.junit.jupiter.api.Assertions.assertEquals((byte) 255, (byte) PlayWriter.angle(359.9f));
        org.junit.jupiter.api.Assertions.assertEquals(64, PlayWriter.angle(90f));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0, 0, 0",
            "0.1, -0.08, 0.0, 90, -45",
            "3.5, 12.25, -7.0, -135, 45",
            "0.00001, 0, 0, 358.59375, -90",
            "130.0, -2.0, 0.5, 180, 12.5"})
    void addEntityMatchesMinestom(double vx, double vy, double vz, float yaw, float pitch) {
        try (var w = new PlayWriter(0)) {
            ByteBuf out = Unpooled.buffer();
            UUID uuid = new UUID(0x1234_5678_9ABC_DEF0L, 0x0FED_CBA9_8765_4321L);
            int type = EntityType.ZOMBIE.id();
            w.addEntity(77, uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), type, 10.5, 64.0, -3.25,
                    vx, vy, vz, yaw, pitch, 5, out);
            var packet = new SpawnEntityPacket(77, uuid, EntityType.ZOMBIE, new Pos(10.5, 64.0, -3.25, yaw, pitch), yaw, 5,
                    new Vec(vx, vy, vz));
            assertArrayEquals(minestom(Protocol.OUT_ADD_ENTITY, SpawnEntityPacket.SERIALIZER, packet), body(out));
        }
    }

    @Test
    void entitySyncMatchesMinestom() {
        try (var w = new PlayWriter(0)) {
            ByteBuf out = Unpooled.buffer();
            w.entitySync(9, 1.5, 70.25, -8.0, 0.1, -0.0784, 0.0, 33.5f, -12f, true, out);
            var packet = new EntityPositionSyncPacket(9, new Vec(1.5, 70.25, -8.0), new Vec(0.1, -0.0784, 0.0), 33.5f, -12f, true);
            assertArrayEquals(minestom(Protocol.OUT_ENTITY_SYNC, EntityPositionSyncPacket.SERIALIZER, packet), body(out));
        }
    }

    @Test
    void removeEntitiesMatchesMinestom() {
        try (var w = new PlayWriter(0)) {
            ByteBuf out = Unpooled.buffer();
            w.removeEntities(new int[] {3, 300, 70000, 0}, 3, out);
            var packet = new DestroyEntitiesPacket(List.of(3, 300, 70000));
            assertArrayEquals(minestom(Protocol.OUT_REMOVE_ENTITIES, DestroyEntitiesPacket.SERIALIZER, packet), body(out));
        }
    }

    /** Each entry alone (Minestom's packet copies its map, losing order; vanilla writes ascending ids like Mulcor). */
    @Test
    void playerMetadataMatchesMinestom() {
        // crouching + sprinting, pose CROUCHING, left-handed, skin 0x7F
        int presence = 0x0A | 0x7F << 8 | 5 << 17;
        int[] masks = {PlayWriter.META_FLAGS, PlayWriter.META_POSE, PlayWriter.META_HAND, PlayWriter.META_SKIN};
        int[] ids = {0, 6, 15, 16};
        net.minestom.server.entity.Metadata.Entry<?>[] entries = {
                net.minestom.server.entity.Metadata.Byte((byte) 0x0A),
                net.minestom.server.entity.Metadata.Pose(net.minestom.server.entity.EntityPose.SNEAKING),
                net.minestom.server.entity.Metadata.MainHand(net.minestom.server.entity.MainHand.LEFT),
                net.minestom.server.entity.Metadata.Byte((byte) 0x7F)};
        try (var w = new PlayWriter(0)) {
            for (int i = 0; i < 4; i++) {
                ByteBuf out = Unpooled.buffer();
                w.playerMeta(42, presence, masks[i], out);
                var packet = new net.minestom.server.network.packet.server.play.EntityMetaDataPacket(42,
                        java.util.Map.of(ids[i], entries[i]));
                assertArrayEquals(minestom(Protocol.OUT_METADATA,
                        net.minestom.server.network.packet.server.play.EntityMetaDataPacket.SERIALIZER, packet), body(out), "index " + ids[i]);
            }
        }
    }

    @Test
    void mainHandEquipmentMatchesMinestom() {
        try (var w = new PlayWriter(0)) {
            for (var m : new net.minestom.server.item.Material[] {net.minestom.server.item.Material.AIR,
                    net.minestom.server.item.Material.STONE, net.minestom.server.item.Material.DIAMOND_SWORD}) {
                ByteBuf out = Unpooled.buffer();
                w.mainHand(5, m == net.minestom.server.item.Material.AIR ? 0 : m.id(), out);
                var packet = new net.minestom.server.network.packet.server.play.EntityEquipmentPacket(5,
                        java.util.Map.of(net.minestom.server.entity.EquipmentSlot.MAIN_HAND, net.minestom.server.item.ItemStack.of(m)));
                assertArrayEquals(minestom(Protocol.OUT_EQUIPMENT,
                        net.minestom.server.network.packet.server.play.EntityEquipmentPacket.SERIALIZER, packet), body(out), m.name());
            }
        }
    }

    @Test
    void animationAndHeadLookMatchMinestom() {
        try (var w = new PlayWriter(0)) {
            ByteBuf out = Unpooled.buffer();
            w.animation(7, 3, out);
            var anim = new net.minestom.server.network.packet.server.play.EntityAnimationPacket(7,
                    net.minestom.server.network.packet.server.play.EntityAnimationPacket.Animation.SWING_OFF_HAND);
            assertArrayEquals(minestom(Protocol.OUT_ANIMATION,
                    net.minestom.server.network.packet.server.play.EntityAnimationPacket.SERIALIZER, anim), body(out));
            out = Unpooled.buffer();
            w.headLook(7, -90f, out);
            var head = new net.minestom.server.network.packet.server.play.EntityHeadLookPacket(7, -90f);
            assertArrayEquals(minestom(Protocol.OUT_HEAD_LOOK,
                    net.minestom.server.network.packet.server.play.EntityHeadLookPacket.SERIALIZER, head), body(out));
        }
    }

    @Test
    void setTimeMatchesMinestom() {
        try (var w = new PlayWriter(0)) {
            ByteBuf out = Unpooled.buffer();
            w.setTime(123_456L, Vanilla.OVERWORLD_CLOCK, 30_000L, out);
            var packet = new net.minestom.server.network.packet.server.play.SetTimePacket(123_456L,
                    java.util.Map.of(net.minestom.server.world.clock.WorldClock.OVERWORLD,
                            new net.minestom.server.network.packet.server.play.SetTimePacket.ClockState(30_000L, 0f, 1f)));
            NetworkBuffer nb = NetworkBuffer.resizableBuffer(Vanilla.REGISTRIES);
            nb.write(NetworkBuffer.VAR_INT, Protocol.OUT_SET_TIME);
            nb.write(net.minestom.server.network.packet.server.play.SetTimePacket.SERIALIZER, packet);
            assertArrayEquals(nb.read(NetworkBuffer.RAW_BYTES), body(out));
        }
    }
}
