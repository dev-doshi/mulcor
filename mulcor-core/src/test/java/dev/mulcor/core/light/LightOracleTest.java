package dev.mulcor.core.light;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.LightStorage;
import dev.mulcor.memory.NativeMemory;
import dev.mulcor.storage.ChunkCodec;
import dev.mulcor.storage.ChunkData;
import dev.mulcor.storage.RegionFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The light engine against vanilla itself: real chunks saved by a vanilla server carry the light vanilla computed
 * ({@code SkyLight}/{@code BlockLight} per section). Load a window of fully generated chunks, discard the light,
 * relight from blocks alone and compare every stored value. Opt-in: {@code -Dmulcor.anvilWorld118=<save dir>}.
 */
class LightOracleTest {
    static final int W = 10; // window size in chunks; the outer ring is excluded from comparison

    @Test
    void relightMatchesVanillaStoredLight() throws Exception {
        String world = System.getProperty("mulcor.anvilWorld118");
        Assumptions.assumeTrue(world != null, "set -Dmulcor.anvilWorld118 to run");
        Path region = Path.of(world, "region");
        var codec = new ChunkCodec();
        // Find a W×W window of full chunks inside one region file.
        List<Path> files;
        try (var s = Files.list(region)) {
            files = s.filter(p -> p.toString().endsWith(".mca")).sorted().toList();
        }
        long compared = 0, skyBad = 0, blockBad = 0, windows = 0, ms = 0, graded = 0, stale = 0, unexplained = 0;
        String firstSky = null, firstBlock = null;
        for (Path f : files) {
            String[] parts = f.getFileName().toString().split("\\.");
            int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
            ChunkData[][] all = new ChunkData[32][32];
            try (var rf = new RegionFile(region, rx, rz)) {
                for (int i = 0; i < 1024; i++) {
                    ByteBuffer b = rf.read((rx << 5) + (i & 31), (rz << 5) + (i >> 5));
                    if (b == null) continue;
                    ChunkData c = codec.decode(b, 24, new ChunkData());
                    if (c.status.equals("minecraft:full") && c.lightOn) all[i & 31][i >> 5] = c;
                }
            }
            // Tile the region with W×W windows whose interiors ((W-2)² chunks) do not overlap.
            for (int ox = 0; ox + W <= 32; ox += W - 2) {
                for (int oz = 0; oz + W <= 32; oz += W - 2) {
                    boolean ok = true;
                    for (int x = 0; x < W && ok; x++) for (int z = 0; z < W && ok; z++) ok = all[ox + x][oz + z] != null;
                    if (!ok) continue;
                    ChunkData[][] window = new ChunkData[W][W];
                    for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) window[x][z] = all[ox + x][oz + z];
                    long[] r = compare(window, (rx << 5) + ox, (rz << 5) + oz);
                    compared += r[0];
                    skyBad += r[1];
                    blockBad += r[2];
                    ms += r[3];
                    graded += r[4];
                    stale += r[5];
                    unexplained += r[6];
                    windows++;
                    if (firstSky == null && lastSky != null) firstSky = lastSky;
                    if (firstBlock == null && lastBlock != null) firstBlock = lastBlock;
                }
            }
        }
        Assumptions.assumeTrue(windows > 0, "no " + W + "x" + W + " window of lit full chunks");
        System.out.printf("light oracle: %d windows of %dx%d chunks relit in %d ms; %d vanilla values compared (%d of them"
                + " intermediate levels 1..14), %d sky and %d block mismatches; %d stale vanilla positions (values that break"
                + " vanilla's own rules), %d mismatches not explained by them%n", windows, W, W, ms, compared, graded, skyBad, blockBad,
                stale, unexplained);
        if (firstSky != null) System.out.println("  first sky mismatch:   " + firstSky);
        if (firstBlock != null) System.out.println("  first block mismatch: " + firstBlock);
        assertTrue(graded > 10_000, "the comparison must cover real light gradients, not only 0/15 sections");
        // Every mismatch must be vanilla's stale light: its connected region of mismatches contains (or touches) a
        // position whose stored value contradicts vanilla's own local rule. Anything else is an engine error.
        assertEquals(0, unexplained, "mismatches not explained by stale vanilla light");
    }

    private String lastSky, lastBlock;

    /** Vanilla's stored value at window-local (x, y, z), or -1 if vanilla did not store that section. */
    private static int stored(ChunkData[][] window, boolean skyKind, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= W * 16 || z >= W * 16) return -1;
        ChunkData c = window[x >> 4][z >> 4];
        int li = (y >> 4) - c.minSection + 1;
        if (li < 0 || li >= c.sections() + 2) return -1;
        byte[] a = skyKind ? c.skyLight[li] : c.blockLight[li];
        if (a == null) return -1;
        int i = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (a[i >> 1] >> ((i & 1) << 2)) & 15;
    }

    /**
     * Returns {stale, unexplained}. "Stale" positions hold a vanilla value that vanilla's own local rule cannot
     * produce from the stored values around it (sky: 15 at or above the column's sky source; otherwise the maximum
     * over emission and every unoccluded neighbour's stored value minus this block's opacity). Each connected region
     * of mismatches (interior chunks) must contain or touch a stale position.
     */
    private static int printed;

    private static long[] explain(ChunkData[][] window, BlockStorage blocks, LightStorage mine, LightEngine engine, boolean skyKind) {
        int n = W * 16;
        java.util.BitSet mismatch = new java.util.BitSet(), staleSet = new java.util.BitSet();
        int yMin = -64 - 16, yMax = 320 + 16, h = yMax - yMin;
        java.util.function.IntUnaryOperator none = v -> v;
        long stale = 0;
        // Stale seeds and mismatch regions are computed over the whole window (staleness depends only on vanilla's own
        // values and the blocks), so a region crossing into the outer ring still finds its seed; only interior
        // mismatches are counted.
        for (int x = 1; x < n - 1; x++) {
            for (int z = 1; z < n - 1; z++) {
                for (int y = yMin + 1; y < yMax - 1; y++) {
                    int v = stored(window, skyKind, x, y, z);
                    if (v < 0) continue;
                    int idx = (x * n + z) * h + (y - yMin);
                    if (v != mine.get(x, y, z)) mismatch.set(idx);
                    int st = blocks.get(x, y, z);
                    int rule;
                    if (skyKind && y >= engine.skySource(x, z)) {
                        rule = 15;
                    } else {
                        rule = skyKind ? 0 : dev.mulcor.registry.BlockData.lightEmission(st);
                        boolean unknown = false;
                        for (int d = 0; d < 6; d++) {
                            int qx = x + FaceOcclusion.DX[d], qy = y + FaceOcclusion.DY[d], qz = z + FaceOcclusion.DZ[d];
                            int q = stored(window, skyKind, qx, qy, qz);
                            if (q < 0) { unknown = true; continue; }
                            if (FaceOcclusion.occludes(blocks.get(qx, qy, qz), st, d ^ 1)) continue;
                            rule = Math.max(rule, q - LightEngine.opacity(st));
                        }
                        if (unknown && v > rule) continue; // an unstored neighbour may justify it
                    }
                    if (v != Math.max(0, rule)) {
                        staleSet.set(idx);
                        stale++;
                    }
                }
            }
        }
        long unexplained = 0;
        java.util.BitSet seen = new java.util.BitSet();
        int[] queue = new int[1 << 20];
        for (int start = mismatch.nextSetBit(0); start >= 0; start = mismatch.nextSetBit(start + 1)) {
            if (seen.get(start)) continue;
            int head = 0, tail = 0, size = 0, interiorCount = 0;
            boolean explained = false;
            queue[tail++] = start;
            seen.set(start);
            while (head < tail) {
                int idx = queue[head++];
                size++;
                int y = idx % h, xz = idx / h, x = xz / n, z = xz % n;
                if (x >= 16 && x < n - 16 && z >= 16 && z < n - 16) interiorCount++;
                if (staleSet.get(idx)) explained = true;
                for (int d = 0; d < 6; d++) {
                    int nx = x + FaceOcclusion.DX[d], ny = y + FaceOcclusion.DY[d], nz = z + FaceOcclusion.DZ[d];
                    if (nx < 0 || nz < 0 || nx >= n || nz >= n || ny < 0 || ny >= h) continue;
                    int ni = (nx * n + nz) * h + ny;
                    if (staleSet.get(ni)) explained = true;
                    if (mismatch.get(ni) && !seen.get(ni) && tail < queue.length) {
                        seen.set(ni);
                        queue[tail++] = ni;
                    }
                }
            }
            if (!explained && interiorCount > 0) {
                size = interiorCount;
                unexplained += size;
                if (Boolean.getBoolean("mulcor.lightDebug") && printed++ < 4) {
                    int y = start % h + yMin, xz = start / h, x = xz / n, z = xz % n;
                    System.out.printf("  unexplained %s region of %d at window %d,%d,%d: vanilla %d, mulcor %d%n", skyKind ? "sky" : "block",
                            size, x, y, z, stored(window, skyKind, x, y, z), mine.get(x, y, z));
                    if (skyKind) dump(window, blocks, mine, x, y, z);
                }
            }
        }
        return new long[] {stale, unexplained};
    }

    /** Debug: vanilla vs Mulcor sky light and states around (x, y, z), window-local coordinates. */
    private static void dump(ChunkData[][] window, BlockStorage blocks, LightStorage sky, int x, int y, int z) {
        System.out.println("  neighbourhood of mismatch (vanilla/mulcor state), columns x-1..x+1, rows z-1..z+1:");
        for (int dy = 3; dy >= -3; dy--) {
            StringBuilder sb = new StringBuilder("  y=" + (y + dy) + ": ");
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = x + dx, py = y + dy, pz = z + dz;
                    ChunkData c = window[px >> 4][pz >> 4];
                    byte[] a = c.skyLight[(py >> 4) - c.minSection + 1];
                    int i = (py & 15) << 8 | (pz & 15) << 4 | (px & 15);
                    String v = a == null ? "?" : String.valueOf((a[i >> 1] >> ((i & 1) << 2)) & 15);
                    String st = dev.mulcor.registry.BlockData.name(dev.mulcor.registry.BlockData.block(blocks.get(px, py, pz))).replace("minecraft:", "");
                    sb.append(String.format("%2s/%-2d %-12.12s", v, sky.get(px, py, pz), st));
                }
                sb.append(" | ");
            }
            System.out.println(sb);
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = x + dx, pz = z + dz;
                ChunkData c = window[px >> 4][pz >> 4];
                // Vanilla's effective source: lowest y from the top where the stored sky light is still 15 all the way.
                int vy = 320;
                while (vy > -64) {
                    byte[] a = c.skyLight[((vy - 1) >> 4) - c.minSection + 1];
                    int i = ((vy - 1) & 15) << 8 | (pz & 15) << 4 | (px & 15);
                    if (a != null && ((a[i >> 1] >> ((i & 1) << 2)) & 15) != 15) break;
                    vy--;
                }
                int my = 320;
                while (my > -64 && sky.get(px, my - 1, pz) == 15) my--;
                if (vy != my) {
                    System.out.printf("  column %+d,%+d: vanilla 15 down to %d, mulcor down to %d; blocks there: %s | %s%n", dx, dz, vy, my,
                            dev.mulcor.registry.BlockData.toString(blocks.get(px, my - 1, pz)),
                            dev.mulcor.registry.BlockData.toString(blocks.get(px, vy - 1, pz)));
                }
            }
        }
    }

    /** Relight one window; returns {compared, skyMismatches, blockMismatches, millis}. */
    private long[] compare(ChunkData[][] window, int wx, int wz) {
        lastSky = lastBlock = null;
        try (var mem = new NativeMemory()) {
            var blocks = new BlockStorage(mem, W, W, -64, 24, W * W * 24);
            var blockLight = new LightStorage(mem, W, W, -64, 24, W * W * 26, 0);
            var skyLight = new LightStorage(mem, W, W, -64, 24, W * W * 26, 0);
            for (int cx = 0; cx < W; cx++) {
                for (int cz = 0; cz < W; cz++) {
                    ChunkData c = window[cx][cz];
                    for (int si = 0; si < 24; si++) {
                        short[] s = c.states[si];
                        if (s == null) continue;
                        for (int i = 0; i < 4096; i++) {
                            int st = Short.toUnsignedInt(s[i]);
                            if (st != 0) blocks.set(cx * 16 + (i & 15), -64 + si * 16 + (i >> 8), cz * 16 + ((i >> 4) & 15), st);
                        }
                    }
                }
            }
            var engine = new LightEngine(blocks, blockLight, skyLight);
            long t0 = System.nanoTime();
            engine.relightAll();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long compared = 0, skyBad = 0, blockBad = 0, graded = 0;
            for (int cx = 1; cx < W - 1; cx++) {
                for (int cz = 1; cz < W - 1; cz++) {
                    ChunkData c = window[cx][cz];
                    for (int li = 0; li < 26; li++) {
                        int sy = c.minSection - 1 + li;
                        for (int kind = 0; kind < 2; kind++) {
                            byte[] stored = kind == 0 ? c.skyLight[li] : c.blockLight[li];
                            if (stored == null) continue;
                            LightStorage mine = kind == 0 ? skyLight : blockLight;
                            for (int i = 0; i < 4096; i++) {
                                int expect = (stored[i >> 1] >> ((i & 1) << 2)) & 15;
                                int x = cx * 16 + (i & 15), y = sy * 16 + (i >> 8), z = cz * 16 + ((i >> 4) & 15);
                                int got = mine.get(x, y, z);
                                compared++;
                                if (expect > 0 && expect < 15) graded++;
                                if (got != expect) {
                                    String at = "block " + (wx * 16 + x) + "," + y + "," + (wz * 16 + z) + " expected " + expect
                                            + " got " + got + " state " + dev.mulcor.registry.BlockData.toString(blocks.get(x, y, z));
                                    if (kind == 0) {
                                        skyBad++;
                                        if (lastSky == null) {
                                            lastSky = at;
                                            if (Boolean.getBoolean("mulcor.lightDebug")) dump(window, blocks, skyLight, x, y, z);
                                        }
                                    }
                                    else { blockBad++; if (lastBlock == null) lastBlock = at; }
                                }
                            }
                        }
                    }
                }
            }
            long[] sky = explain(window, blocks, skyLight, engine, true);
            long[] blk = explain(window, blocks, blockLight, engine, false);
            return new long[] {compared, skyBad, blockBad, ms, graded, sky[0] + blk[0], sky[1] + blk[1]};
        }
    }
}
