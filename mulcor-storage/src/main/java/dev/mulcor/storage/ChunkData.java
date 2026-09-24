package dev.mulcor.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk column as stored on disk, decoded for installation into the off-heap world (or encoded from it for
 * saving). This is the staging form used by storage I/O threads (cold path): plain arrays, reused across chunks
 * where possible.
 *
 * <ul>
 *   <li>Block states are vanilla global state ids, 4096 per section in vanilla's YZX order
 *       ({@code index = y << 8 | z << 4 | x}), the same order as Mulcor's {@code BlockStorage}. A {@code null}
 *       section is all air.</li>
 *   <li>Biomes are 4×4×4 cells per section ({@code index = y << 4 | z << 2 | x}) indexing {@link #biomeNames}.</li>
 *   <li>Light arrays are vanilla nibble arrays (2048 bytes, two 4-bit values per byte, low nibble first) for
 *       sections {@code minSection - 1 .. maxSection + 1}; {@code null} = not stored.</li>
 *   <li>Every root field Mulcor does not model yet (block entities, scheduled ticks, heightmaps, structures, ...)
 *       is kept as raw NBT in {@link #extra} and written back unchanged, so loading and saving never loses data.</li>
 * </ul>
 */
public final class ChunkData {
    public int x, z;
    public int dataVersion;
    /** Lowest section index ({@code yPos}); the overworld's is -4 (y = -64). */
    public int minSection;
    public String status = "minecraft:full";
    public long lastUpdate, inhabitedTime;
    public boolean lightOn;
    /** Per section (index = sectionY - minSection): 4096 state ids, or null for all air. */
    public short[][] states;
    /** Per section: 64 biome indices into {@link #biomeNames}, or null for "all {@code biomeNames[0]}". */
    public byte[][] biomes;
    public final List<String> biomeNames = new ArrayList<>();
    /** Per light section (index = sectionY - minSection + 1): 2048-byte nibble arrays or null. */
    public byte[][] blockLight, skyLight;
    /** Palette entries that did not resolve to a known state (decoded as air). */
    public int unknownStates;
    /** Unmodelled root fields, verbatim: tag type, name, raw payload bytes. */
    public final List<RawField> extra = new ArrayList<>();

    public record RawField(int type, String name, byte[] payload) {}

    public int sections() {
        return states == null ? 0 : states.length;
    }

    /** Prepare for {@code sectionCount} sections starting at {@code minSection}; keeps arrays when the shape fits. */
    public ChunkData reset(int sectionCount, int minSection) {
        this.minSection = minSection;
        if (states == null || states.length != sectionCount) {
            states = new short[sectionCount][];
            biomes = new byte[sectionCount][];
            blockLight = new byte[sectionCount + 2][];
            skyLight = new byte[sectionCount + 2][];
        } else {
            java.util.Arrays.fill(states, null);
            java.util.Arrays.fill(biomes, null);
            java.util.Arrays.fill(blockLight, null);
            java.util.Arrays.fill(skyLight, null);
        }
        biomeNames.clear();
        extra.clear();
        unknownStates = 0;
        lightOn = false;
        return this;
    }

    public int state(int x, int y, int z) {
        short[] s = states[(y >> 4) - minSection];
        return s == null ? 0 : Short.toUnsignedInt(s[(y & 15) << 8 | (z & 15) << 4 | (x & 15)]);
    }
}
