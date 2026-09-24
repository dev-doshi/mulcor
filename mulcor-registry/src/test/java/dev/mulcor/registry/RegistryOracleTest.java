package dev.mulcor.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.Shape;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every generated table value is checked against Minestom's own registry (itself generated from the vanilla
 * server), state by state. Minestom is a test-only dependency: the runtime registry has none.
 */
class RegistryOracleTest {
    @BeforeAll
    static void init() {
        if (MinecraftServer.process() == null) MinecraftServer.init();
    }

    @Test
    void versionMatchesMinestom() {
        assertEquals(MinecraftServer.PROTOCOL_VERSION, Registry.PROTOCOL);
        assertEquals(MinecraftServer.VERSION_NAME, Registry.VERSION);
        assertTrue(Registry.DATA_VERSION > 4000, "world DataVersion " + Registry.DATA_VERSION);
    }

    @Test
    void everyStateMatches() {
        assertEquals(Block.statesCount(), Registry.stateCount());
        assertTrue(Registry.stateCount() <= 65536, "state ids must fit Mulcor's 16-bit block storage");
        for (int s = 0; s < Registry.stateCount(); s++) {
            Block m = Block.fromStateId(s);
            var r = m.registry();
            int b = BlockData.block(s);
            String at = BlockData.toString(s);
            assertEquals(m.id(), b, at);
            assertEquals(m.key().asString(), BlockData.name(b), at);
            assertEquals(r.lightEmission(), BlockData.lightEmission(s), at);
            assertEquals(r.lightBlocked(), BlockData.lightBlock(s), at);
            assertEquals(r.isSolid(), BlockData.is(s, BlockData.SOLID), at);
            assertEquals(r.blocksMotion(), BlockData.is(s, BlockData.BLOCKS_MOTION), at);
            assertEquals(r.isAir(), BlockData.isAir(s), at);
            assertEquals(r.isLiquid(), BlockData.is(s, BlockData.LIQUID), at);
            assertEquals(r.isFluid(), BlockData.is(s, BlockData.FLUID), at);
            assertEquals(r.isReplaceable(), BlockData.is(s, BlockData.REPLACEABLE), at);
            assertEquals(r.occludes(), BlockData.is(s, BlockData.OCCLUDES), at);
            assertEquals(r.requiresTool(), BlockData.is(s, BlockData.REQUIRES_TOOL), at);
            assertEquals(r.isRedstoneConductor(), BlockData.is(s, BlockData.REDSTONE_CONDUCTOR), at);
            assertEquals(r.isSignalSource(), BlockData.is(s, BlockData.SIGNAL_SOURCE), at);
            assertEquals(r.hardness(), BlockData.hardness(b), at);
            assertEquals(r.explosionResistance(), BlockData.explosionResistance(b), at);
            assertEquals(r.friction(), BlockData.friction(b), at);
            assertEquals(r.speedFactor(), BlockData.speedFactor(b), at);
            assertEquals(r.jumpFactor(), BlockData.jumpFactor(b), at);
            assertEquals(r.isBlockEntity() ? r.blockEntityId() : -1, BlockData.blockEntityType(b), at);
            Material mat = r.material();
            assertEquals(mat == null ? -1 : mat.id(), BlockData.item(b), at);
            assertShape(r.collisionShape(), BlockData.collisionShape(s), at + " collision");
            assertShape(r.outlineShape(), BlockData.outlineShape(s), at + " outline");

            // Properties: every property of the Minestom state reads back, and the string form round-trips.
            for (Map.Entry<String, String> p : m.properties().entrySet()) {
                int h = BlockData.property(b, p.getKey());
                assertNotEquals(BlockData.NO_PROPERTY, h, at + " " + p.getKey());
                assertEquals(p.getValue(), BlockData.valueName(h, BlockData.get(s, h)), at);
            }
            assertEquals(s, BlockData.parse(BlockData.name(b), m.properties()), at);
            assertEquals(Block.fromState(at).stateId(), s, at);
        }
    }

    private static void assertShape(Shape expected, int shape, String at) {
        int n = Shapes.boxCount(shape);
        if (n == 0) {
            assertEquals(0.0, expected.relativeEnd().x() - expected.relativeStart().x(), 0.0, at + ": empty");
            return;
        }
        double minX = 9, minY = 9, minZ = 9, maxX = -9, maxY = -9, maxZ = -9;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, Shapes.minX(shape, i));
            minY = Math.min(minY, Shapes.minY(shape, i));
            minZ = Math.min(minZ, Shapes.minZ(shape, i));
            maxX = Math.max(maxX, Shapes.maxX(shape, i));
            maxY = Math.max(maxY, Shapes.maxY(shape, i));
            maxZ = Math.max(maxZ, Shapes.maxZ(shape, i));
        }
        assertEquals(expected.relativeStart().x(), minX, 1e-9, at);
        assertEquals(expected.relativeStart().y(), minY, 1e-9, at);
        assertEquals(expected.relativeStart().z(), minZ, 1e-9, at);
        assertEquals(expected.relativeEnd().x(), maxX, 1e-9, at);
        assertEquals(expected.relativeEnd().y(), maxY, 1e-9, at);
        assertEquals(expected.relativeEnd().z(), maxZ, 1e-9, at);
    }

    @Test
    void propertyArithmetic() {
        int wire = BlockData.defaultState(BlockId.REDSTONE_WIRE);
        int power = BlockData.property(BlockId.REDSTONE_WIRE, "power");
        for (int p = 0; p <= 15; p++) {
            int s = BlockData.withInt(wire, power, p);
            assertEquals(p, BlockData.intValue(s, power));
            assertEquals(Block.REDSTONE_WIRE.withProperty("power", String.valueOf(p)).stateId(), s);
        }
        int repeater = BlockData.defaultState(BlockId.REPEATER);
        int delay = BlockData.property(BlockId.REPEATER, "delay");
        assertEquals(1, BlockData.intValue(repeater, delay), "repeater delay starts at 1");
        assertEquals(Block.REPEATER.withProperty("delay", "4").stateId(), BlockData.withInt(repeater, delay, 4));
        int slab = BlockData.defaultState(BlockId.OAK_SLAB);
        int wl = BlockData.property(BlockId.OAK_SLAB, "waterlogged");
        assertFalse(BlockData.boolValue(slab, wl));
        assertEquals(Block.OAK_SLAB.withProperty("waterlogged", "true").stateId(), BlockData.withBool(slab, wl, true));
        assertEquals(BlockData.NO_PROPERTY, BlockData.property(BlockId.STONE, "power"));
        assertEquals(-1, BlockData.parse("minecraft:stone", Map.of("power", "1")));
        assertEquals(-1, BlockData.parse("minecraft:no_such_block", Map.of()));
    }

    /** Pins of vanilla {@code isFaceSturdy} (sturdy_faces.txt, extracted from the vanilla 26.2 classes). */
    @Test
    void sturdyFaces() {
        final int down = 0, up = 1, north = 2;
        final int full = BlockData.SUPPORT_FULL, center = BlockData.SUPPORT_CENTER, rigid = BlockData.SUPPORT_RIGID;
        assertEquals(0x3FFFF, BlockData.sturdyFaces(BlockData.defaultState(BlockId.STONE)), "full cube: all 18");
        assertEquals(0, BlockData.sturdyFaces(BlockData.defaultState(BlockId.AIR)));
        int bottom = BlockData.parse("minecraft:oak_slab", Map.of("type", "bottom"));
        assertTrue(BlockData.sturdy(bottom, down, full));
        assertFalse(BlockData.sturdy(bottom, up, full));
        assertFalse(BlockData.sturdy(bottom, north, center));
        int top = BlockData.parse("minecraft:oak_slab", Map.of("type", "top"));
        assertTrue(BlockData.sturdy(top, up, full) && !BlockData.sturdy(top, down, full));
        int post = BlockData.defaultState(BlockId.OAK_FENCE); // no connections
        assertTrue(BlockData.sturdy(post, up, center), "fence post: a torch fits on top");
        assertFalse(BlockData.sturdy(post, up, full), "fence post: no wire on top");
        assertEquals(0, BlockData.sturdyFaces(BlockData.defaultState(BlockId.OAK_LEAVES)), "leaves support nothing");
        int hopper = BlockData.defaultState(BlockId.HOPPER);
        assertTrue(BlockData.sturdy(hopper, up, rigid), "a repeater sits on a hopper");
        assertFalse(BlockData.sturdy(hopper, up, full), "wire special-cases hoppers");
        int scaffolding = BlockData.defaultState(BlockId.SCAFFOLDING);
        assertTrue(BlockData.sturdy(scaffolding, up, full));
        assertFalse(BlockData.sturdy(scaffolding, down, full));
        assertEquals(0, BlockData.sturdyFaces(BlockData.defaultState(BlockId.REDSTONE_WIRE)));
    }

    @Test
    void itemsAndEntitiesMatch() {
        assertEquals(Material.values().size(), Registry.itemCount());
        for (Material m : Material.values()) {
            assertEquals(m.key().asString(), Items.name(m.id()));
            assertEquals(m.maxStackSize(), Items.maxStackSize(m.id()), m.name());
            Block b = m.block();
            assertEquals(b == null ? -1 : b.id(), Items.block(m.id()), m.name());
        }
        assertEquals(EntityType.values().size(), Registry.entityTypeCount());
        for (EntityType t : EntityType.values()) {
            assertEquals(t.key().asString(), EntityTypes.name(t.id()));
            assertEquals(t.width(), EntityTypes.width(t.id()), t.name());
            assertEquals(t.height(), EntityTypes.height(t.id()), t.name());
        }
        assertEquals(0.08, EntityTypes.attribute(EntityTypeId.ZOMBIE, AttributeId.GRAVITY));
        assertEquals(20.0, EntityTypes.attribute(EntityTypeId.ZOMBIE, AttributeId.MAX_HEALTH));
        assertEquals(1, Items.maxStackSize(ItemId.DIAMOND_SWORD));
        assertEquals(1561, Items.maxDamage(ItemId.DIAMOND_SWORD));
    }
}
