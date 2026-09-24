package dev.mulcor.storage;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;

/**
 * Moves chunks between their disk form ({@link ChunkData}) and Mulcor's off-heap world ({@link BlockStorage} +
 * block/sky {@link LightStorage}). Runs where the target chunk is owned (installation during the global phase or by
 * the owning region); cold path.
 */
public final class ChunkInstaller {
    private ChunkInstaller() {}

    /**
     * Copy {@code c} into the storages at storage-chunk (lx, lz). The storages' floor must equal the chunk's
     * ({@code minSection * 16}). Returns true if vanilla's stored light was installed; false if the chunk carries
     * no valid light ({@code isLightOn} unset) and must be relit.
     */
    public static boolean install(ChunkData c, BlockStorage blocks, LightStorage blockLight, LightStorage skyLight, int lx, int lz) {
        if (blocks.minY() != c.minSection * 16) {
            throw new IllegalArgumentException("chunk floor " + c.minSection * 16 + " != storage floor " + blocks.minY());
        }
        int x0 = lx * 16, z0 = lz * 16;
        int sections = Math.min(c.sections(), blocks.sections());
        for (int si = 0; si < sections; si++) {
            short[] s = c.states[si];
            int y0 = blocks.minY() + si * 16;
            for (int i = 0; i < 4096; i++) {
                int st = s == null ? 0 : Short.toUnsignedInt(s[i]);
                int x = x0 + (i & 15), y = y0 + (i >> 8), z = z0 + ((i >> 4) & 15);
                if (st != 0 || blocks.get(x, y, z) != 0) blocks.set(x, y, z, st);
            }
        }
        if (!c.lightOn) return false;
        int minLight = blockLight.minSection();
        for (int li = 0; li < Math.min(c.sections() + 2, blockLight.sections()); li++) {
            int sy = minLight + li;
            if (c.blockLight[li] != null) blockLight.loadSection(lx, lz, sy, c.blockLight[li]);
            else blockLight.fillSection(lx, lz, sy, 0);
            if (c.skyLight[li] != null) skyLight.loadSection(lx, lz, sy, c.skyLight[li]);
        }
        return true;
    }

    /**
     * Snapshot storage-chunk (lx, lz) into {@code out} for saving; {@code out}'s position, status, biomes and
     * unmodelled fields (from the load) are kept. Block light is stored only for sections that are not all dark,
     * sky light for every section (a missing sky layer would be ambiguous to vanilla).
     */
    public static void snapshot(BlockStorage blocks, LightStorage blockLight, LightStorage skyLight, int lx, int lz, ChunkData out) {
        int x0 = lx * 16, z0 = lz * 16;
        out.minSection = blocks.minY() >> 4;
        if (out.states == null || out.states.length != blocks.sections()) {
            var keep = new java.util.ArrayList<>(out.extra);
            var names = new java.util.ArrayList<>(out.biomeNames);
            byte[][] biomes = out.biomes;
            out.reset(blocks.sections(), out.minSection);
            out.extra.addAll(keep);
            out.biomeNames.addAll(names);
            if (biomes != null && biomes.length == blocks.sections()) out.biomes = biomes;
        }
        for (int si = 0; si < blocks.sections(); si++) {
            short[] s = out.states[si];
            boolean any = false;
            int y0 = blocks.minY() + si * 16;
            for (int i = 0; i < 4096; i++) {
                int st = blocks.get(x0 + (i & 15), y0 + (i >> 8), z0 + ((i >> 4) & 15));
                if (st != 0 && s == null) s = new short[4096];
                if (s != null) s[i] = (short) st;
                any |= st != 0;
            }
            out.states[si] = any ? s : null;
        }
        for (int li = 0; li < blockLight.sections(); li++) {
            int sy = blockLight.minSection() + li;
            byte[] b = out.blockLight[li] != null ? out.blockLight[li] : new byte[LightStorage.SECTION_BYTES];
            int uniform = blockLight.copySection(lx, lz, sy, b);
            out.blockLight[li] = uniform == 0 ? null : b;
            byte[] k = out.skyLight[li] != null ? out.skyLight[li] : new byte[LightStorage.SECTION_BYTES];
            skyLight.copySection(lx, lz, sy, k);
            out.skyLight[li] = k;
        }
        out.lightOn = true;
    }
}
