package dev.mulcor.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A world save's directory layout (cold path). Vanilla 26.x stores each dimension under
 * {@code dimensions/<namespace>/<path>/} ({@code region/}, {@code entities/}, {@code poi/}, {@code data/}), world
 * state in {@code data/minecraft/<name>.dat} saved-data files ({@code {DataVersion, data}}) and players in
 * {@code players/data/<uuid>.dat}. Worlds from before that change keep the overworld's folders at the root, the
 * nether in {@code DIM-1/} and the end in {@code DIM1/}, and players in {@code playerdata/}; both are read.
 */
public final class WorldDirectory {
    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";

    private final Path root;
    private final boolean dimensionsLayout;

    public WorldDirectory(Path root) {
        this.root = root;
        // New worlds (and anything Mulcor creates) use the dimensions/ layout; a legacy world has region/ at the root.
        this.dimensionsLayout = Files.isDirectory(root.resolve("dimensions")) || !Files.isDirectory(root.resolve("region"));
    }

    public Path root() { return root; }
    public boolean usesDimensionsLayout() { return dimensionsLayout; }

    /** The folder holding {@code region/}, {@code entities/}, {@code poi/} and {@code data/} for a dimension key. */
    public Path dimension(String key) {
        String ns = key.indexOf(':') < 0 ? "minecraft" : key.substring(0, key.indexOf(':'));
        String path = key.indexOf(':') < 0 ? key : key.substring(key.indexOf(':') + 1);
        if (dimensionsLayout) return root.resolve("dimensions").resolve(ns).resolve(path);
        return switch (ns + ":" + path) {
            case OVERWORLD -> root;
            case NETHER -> root.resolve("DIM-1");
            case END -> root.resolve("DIM1");
            default -> root.resolve("dimensions").resolve(ns).resolve(path);
        };
    }

    public Path regions(String dimension) { return dimension(dimension).resolve("region"); }
    public Path entities(String dimension) { return dimension(dimension).resolve("entities"); }
    public Path poi(String dimension) { return dimension(dimension).resolve("poi"); }

    public Path levelDat() { return root.resolve("level.dat"); }

    /** A world-wide saved-data file ({@code data/minecraft/game_rules.dat}, ...). */
    public Path savedData(String name) {
        return root.resolve("data").resolve("minecraft").resolve(name + ".dat");
    }

    /** A per-dimension saved-data file ({@code world_border}, {@code raids}, {@code chunk_tickets}). */
    public Path dimensionData(String dimension, String name) {
        return dimension(dimension).resolve("data").resolve("minecraft").resolve(name + ".dat");
    }

    public Path playerData(UUID player) {
        Path dir = dimensionsLayout ? root.resolve("players").resolve("data") : root.resolve("playerdata");
        return dir.resolve(player + ".dat");
    }

    /** Read {@code level.dat}'s {@code Data} compound. */
    public NbtTree.Compound readLevel() throws IOException {
        NbtTree.Compound root = NbtTree.readGzipFile(levelDat());
        NbtTree.Compound data = root.getCompound("Data");
        if (data == null) throw new IOException(levelDat() + " has no Data compound");
        return data;
    }

    /** Write {@code level.dat} (crash-safe, keeping {@code level.dat_old}). */
    public void writeLevel(NbtTree.Compound data) throws IOException {
        NbtTree.Compound root = new NbtTree.Compound();
        root.put("Data", data);
        Files.createDirectories(root().toAbsolutePath());
        NbtTree.writeGzipFile(levelDat(), root);
    }

    /** Read a saved-data file's {@code data} compound, or null if the file does not exist. */
    public static NbtTree.Compound readSavedData(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        NbtTree.Compound root = NbtTree.readGzipFile(file);
        return root.getCompound("data");
    }

    /** Write a saved-data file: {@code {DataVersion, data}}. */
    public static void writeSavedData(Path file, int dataVersion, NbtTree.Compound data) throws IOException {
        NbtTree.Compound root = new NbtTree.Compound();
        root.put("data", data);
        root.put("DataVersion", dataVersion);
        Files.createDirectories(file.getParent());
        NbtTree.writeGzipFile(file, root);
    }
}
