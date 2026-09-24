package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Engine;
import dev.mulcor.core.EngineConfig;
import dev.mulcor.net.NetServer;
import dev.mulcor.net.ServerContext;
import dev.mulcor.registry.BlockData;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real client picks blocks in creative, looks a way and clicks: the server places the held block in the state
 * vanilla's {@code getStateForPlacement} gives it (see {@code Placement}).
 */
class PlacementTest {
    private static final Duration WAIT = Duration.ofSeconds(60);
    private ExecutorService pool;
    private Engine engine;
    private NetServer net;
    private VanillaClient c;
    private int sx, sy, sz, seq;

    private void tickUntil(BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.getAsBoolean()) {
            if (c != null && c.failure != null) throw new AssertionError("client failed", c.failure);
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            engine.tick();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @BeforeEach
    void start() throws Exception {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        engine = new Engine(EngineConfig.builder().world(8, 8).cellChunks(2).workers(2).initialRegions(1).maxRegions(4)
                .maxEntities(256).rebalanceInterval(0).sampleCapacity(256).pillarDensity(0).chestsPerCell(0).build());
        engine.setAiEnabled(false);
        ServerContext ctx = ServerContext.of(engine).withViewDistance(2);
        net = NetServer.vanilla(0, 1, ctx);
        Future<VanillaClient> f = pool.submit(() -> VanillaClient.join("127.0.0.1", net.port(), "builder", WAIT));
        tickUntil(f::isDone, "join");
        c = f.get();
        sx = ctx.spawnX();
        sz = ctx.spawnZ();
        sy = engine.surfaceAt(sx, sz); // first air layer
    }

    @AfterEach
    void stop() throws Exception {
        c.close();
        net.close();
        engine.close();
        pool.shutdownNow();
    }

    private void hold(Material m) {
        c.send(new ClientCreativeInventoryActionPacket((short) 36, ItemStack.of(m)));
        c.send(new ClientHeldItemChangePacket((short) 0));
    }

    /** Look (yaw, pitch) and click face {@code face} of the block at (x, y, z) at the given point inside it. */
    private void click(float yaw, float pitch, int x, int y, int z, BlockFace face, float cx, float cy, float cz) {
        c.moveTo(sx + 0.5, sy, sz - 3.5, yaw, pitch);
        c.send(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(x, y, z), face, cx, cy, cz, false, false, ++seq));
    }

    private String placed(int x, int y, int z) {
        int[] st = {0};
        tickUntil(() -> {
            st[0] = engine.world.blocks.getShared(x, y, z);
            return !BlockData.isAir(st[0]);
        }, "block at " + x + "," + y + "," + z);
        return BlockData.toString(st[0]);
    }

    @Test
    void logsTakeTheAxisOfTheClickedFace() {
        hold(Material.OAK_LOG);
        click(0, 0, sx, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        assertEquals("minecraft:oak_log[axis=y]", placed(sx, sy, sz));
        click(0, 0, sx, sy, sz, BlockFace.EAST, 1f, 0.5f, 0.5f);
        assertEquals("minecraft:oak_log[axis=x]", placed(sx + 1, sy, sz));
    }

    /** Facing south (yaw 0): a furnace faces the player (north); stairs face south; an observer looks south. */
    @Test
    void directionalBlocksFollowThePlayer() {
        hold(Material.FURNACE);
        click(0, 0, sx, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        assertTrue(placed(sx, sy, sz).contains("facing=north"));
        hold(Material.OAK_STAIRS);
        click(0, 0, sx + 1, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        String stairs = placed(sx + 1, sy, sz);
        assertTrue(stairs.contains("facing=south") && stairs.contains("half=bottom"), stairs);
        hold(Material.OBSERVER);
        click(0, 10, sx + 2, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        assertTrue(placed(sx + 2, sy, sz).contains("facing=south"));
    }

    @Test
    void slabsGoTopWhenClickedHighOnASide() {
        hold(Material.STONE);
        click(0, 0, sx, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        placed(sx, sy, sz);
        hold(Material.OAK_SLAB);
        click(0, 0, sx, sy, sz, BlockFace.EAST, 1f, 0.8f, 0.5f);
        assertTrue(placed(sx + 1, sy, sz).contains("type=top"));
        click(0, 0, sx, sy, sz, BlockFace.WEST, 0f, 0.2f, 0.5f);
        assertTrue(placed(sx - 1, sy, sz).contains("type=bottom"));
    }

    @Test
    void torchesGoOnWallsAndDoorsGetTwoHalves() {
        hold(Material.STONE);
        click(0, 0, sx, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        placed(sx, sy, sz);
        hold(Material.TORCH);
        click(0, 0, sx, sy, sz, BlockFace.SOUTH, 0.5f, 0.5f, 1f);
        assertEquals("minecraft:wall_torch[facing=south]", placed(sx, sy, sz + 1));
        hold(Material.OAK_DOOR);
        click(0, 0, sx + 3, sy - 1, sz, BlockFace.TOP, 0.5f, 1f, 0.5f);
        assertTrue(placed(sx + 3, sy, sz).contains("half=lower"));
        assertTrue(placed(sx + 3, sy + 1, sz).contains("half=upper"));
    }
}
