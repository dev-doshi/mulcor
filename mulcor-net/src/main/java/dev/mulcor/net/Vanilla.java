package dev.mulcor.net;

import net.minestom.server.MinecraftServer;
import net.minestom.server.registry.Registries;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;

/**
 * The vanilla data a real client needs to join: protocol version, registries and the overworld's geometry.
 *
 * <p>Minestom's cached registry packets need its {@code ServerProcess}, so the first use calls
 * {@link MinecraftServer#init()}. That only builds registries and managers (about a second, no threads). Minestom's
 * network, scheduler and tick loop are never started. Class initialization is thread-safe and happens once.
 */
public final class Vanilla {
    public static final int PROTOCOL = MinecraftServer.PROTOCOL_VERSION;
    public static final String VERSION = MinecraftServer.VERSION_NAME;
    public static final String WORLD = "minecraft:overworld";

    public static final Registries REGISTRIES;
    public static final int OVERWORLD_ID;
    /** Overworld floor and section count: every chunk packet must describe exactly this column. */
    public static final int MIN_Y, SECTIONS;
    public static final int PLAINS_BIOME;
    /** Registry ids of the overworld's clock (Set Time) and the {@code minecraft:chat} chat type. */
    public static final int OVERWORLD_CLOCK, CHAT_TYPE;

    static {
        if (MinecraftServer.process() == null) MinecraftServer.init();
        REGISTRIES = MinecraftServer.getRegistries();
        OVERWORLD_ID = REGISTRIES.dimensionType().getId(DimensionType.OVERWORLD);
        DimensionType overworld = REGISTRIES.dimensionType().get(DimensionType.OVERWORLD);
        MIN_Y = overworld.minY();
        SECTIONS = overworld.height() / 16;
        PLAINS_BIOME = REGISTRIES.biome().getId(Biome.PLAINS);
        OVERWORLD_CLOCK = REGISTRIES.worldClock().getId(net.minestom.server.world.clock.WorldClock.OVERWORLD);
        CHAT_TYPE = REGISTRIES.chatType().getId(net.minestom.server.message.ChatType.CHAT);
    }

    private Vanilla() {}

    /** A text component's network encoding (cold: build once, write with {@code writeBytes}). */
    public static byte[] component(net.kyori.adventure.text.Component text) {
        var nb = net.minestom.server.network.NetworkBuffer.resizableBuffer(REGISTRIES);
        nb.write(net.minestom.server.network.NetworkBuffer.COMPONENT, text);
        return nb.read(net.minestom.server.network.NetworkBuffer.RAW_BYTES);
    }
}
