package dev.mulcor.registry;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;

/**
 * Loads the vanilla registry tables generated at build time from the vanilla server's data generator output (via
 * Minestom's data bundle). Loading happens once, at class initialization (cold path); afterwards every lookup is a
 * read from a {@code static final} primitive array, safe from any thread and allocation-free.
 */
public final class Registry {
    /** Minecraft version name, protocol version and world DataVersion these tables describe. */
    public static final String VERSION;
    public static final int PROTOCOL;
    public static final int DATA_VERSION;

    // ---- blocks (indexed by block id) ----
    static final String[] BLOCK_NAME;
    static final int[] BLOCK_FIRST_STATE, BLOCK_STATE_COUNT, BLOCK_DEFAULT_STATE, BLOCK_ITEM, BLOCK_ENTITY_TYPE;
    static final float[] BLOCK_HARDNESS, BLOCK_RESISTANCE, BLOCK_FRICTION, BLOCK_SPEED_FACTOR, BLOCK_JUMP_FACTOR;
    static final byte[] BLOCK_PUSH_REACTION;
    static final String[] BLOCK_SOUND_TYPE;
    /** Per block: index of its first property in the PROP_* arrays, and how many it has. */
    static final int[] BLOCK_PROP_BASE, BLOCK_PROP_COUNT;
    static final String[] PROP_NAME;
    static final String[][] PROP_VALUES;
    static final int[] PROP_STRIDE, PROP_VALUE_COUNT;

    // ---- states (indexed by state id) ----
    static final short[] STATE_BLOCK;
    static final int[] STATE_FLAGS;
    static final byte[] STATE_EMISSION, STATE_OPACITY;
    static final short[] STATE_COLLISION, STATE_OUTLINE, STATE_OCCLUSION, STATE_INTERACTION;
    /** isFaceSturdy bits per state: type (FULL, CENTER, RIGID) * 6 + direction (D, U, N, S, W, E). */
    static final int[] STATE_STURDY;

    // ---- shapes: boxes as minX,minY,minZ,maxX,maxY,maxZ in block-local coordinates ----
    static final double[][] SHAPE_BOXES;

    // ---- items ----
    static final String[] ITEM_NAME;
    static final byte[] ITEM_MAX_STACK;
    static final int[] ITEM_MAX_DAMAGE, ITEM_BLOCK;

    // ---- attributes ----
    static final String[] ATTR_NAME;
    static final double[] ATTR_DEFAULT, ATTR_MIN, ATTR_MAX;
    static final boolean[] ATTR_SYNC;

    // ---- entity types ----
    static final String[] ENTITY_NAME, ENTITY_PACKET_TYPE;
    static final double[] ENTITY_WIDTH, ENTITY_HEIGHT, ENTITY_EYE_HEIGHT;
    static final int[] ENTITY_TRACKING_RANGE;
    /** Per entity type: default value of every attribute (NaN = the type does not have that attribute). */
    static final double[][] ENTITY_ATTRIBUTES;

    static final String[] BLOCK_ENTITY_NAME, SOUND_NAME, PARTICLE_NAME, FLUID_NAME;

    static {
        try (InputStream raw = Registry.class.getResourceAsStream("registry.bin")) {
            if (raw == null) throw new IllegalStateException("registry.bin missing: run :mulcor-registry:generateRegistry");
            DataInputStream d = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16));
            if (d.readInt() != 0x4D524547 || d.readInt() != 2) throw new IllegalStateException("bad registry.bin header");
            VERSION = d.readUTF();
            PROTOCOL = d.readInt();
            DATA_VERSION = d.readInt();

            int nb = d.readInt();
            BLOCK_NAME = new String[nb];
            BLOCK_FIRST_STATE = new int[nb];
            BLOCK_STATE_COUNT = new int[nb];
            BLOCK_DEFAULT_STATE = new int[nb];
            BLOCK_ITEM = new int[nb];
            BLOCK_ENTITY_TYPE = new int[nb];
            BLOCK_HARDNESS = new float[nb];
            BLOCK_RESISTANCE = new float[nb];
            BLOCK_FRICTION = new float[nb];
            BLOCK_SPEED_FACTOR = new float[nb];
            BLOCK_JUMP_FACTOR = new float[nb];
            BLOCK_PUSH_REACTION = new byte[nb];
            BLOCK_SOUND_TYPE = new String[nb];
            BLOCK_PROP_BASE = new int[nb];
            BLOCK_PROP_COUNT = new int[nb];
            var propNames = new java.util.ArrayList<String>();
            var propValues = new java.util.ArrayList<String[]>();
            var propStride = new java.util.ArrayList<Integer>();
            for (int b = 0; b < nb; b++) {
                BLOCK_NAME[b] = d.readUTF();
                BLOCK_FIRST_STATE[b] = d.readInt();
                BLOCK_STATE_COUNT[b] = d.readInt();
                BLOCK_DEFAULT_STATE[b] = d.readInt();
                BLOCK_HARDNESS[b] = d.readFloat();
                BLOCK_RESISTANCE[b] = d.readFloat();
                BLOCK_FRICTION[b] = d.readFloat();
                BLOCK_SPEED_FACTOR[b] = d.readFloat();
                BLOCK_JUMP_FACTOR[b] = d.readFloat();
                BLOCK_ITEM[b] = d.readInt();
                BLOCK_PUSH_REACTION[b] = d.readByte();
                BLOCK_ENTITY_TYPE[b] = d.readInt();
                BLOCK_SOUND_TYPE[b] = d.readUTF();
                int np = d.readUnsignedByte();
                BLOCK_PROP_BASE[b] = propNames.size();
                BLOCK_PROP_COUNT[b] = np;
                String[][] values = new String[np][];
                for (int p = 0; p < np; p++) {
                    propNames.add(d.readUTF());
                    int nv = d.readUnsignedByte();
                    values[p] = new String[nv];
                    for (int v = 0; v < nv; v++) values[p][v] = d.readUTF();
                    propValues.add(values[p]);
                }
                int acc = 1;
                int[] strides = new int[np];
                for (int p = np - 1; p >= 0; p--) {
                    strides[p] = acc;
                    acc *= values[p].length;
                }
                for (int s : strides) propStride.add(s);
            }
            PROP_NAME = propNames.toArray(String[]::new);
            PROP_VALUES = propValues.toArray(String[][]::new);
            PROP_STRIDE = new int[PROP_NAME.length];
            PROP_VALUE_COUNT = new int[PROP_NAME.length];
            for (int i = 0; i < PROP_NAME.length; i++) {
                PROP_STRIDE[i] = propStride.get(i);
                PROP_VALUE_COUNT[i] = PROP_VALUES[i].length;
            }

            int ns = d.readInt();
            STATE_BLOCK = new short[ns];
            STATE_FLAGS = new int[ns];
            STATE_EMISSION = new byte[ns];
            STATE_OPACITY = new byte[ns];
            STATE_COLLISION = new short[ns];
            STATE_OUTLINE = new short[ns];
            STATE_OCCLUSION = new short[ns];
            STATE_INTERACTION = new short[ns];
            STATE_STURDY = new int[ns];
            for (int s = 0; s < ns; s++) {
                STATE_BLOCK[s] = d.readShort();
                STATE_FLAGS[s] = d.readInt();
                STATE_EMISSION[s] = d.readByte();
                STATE_OPACITY[s] = d.readByte();
                STATE_COLLISION[s] = d.readShort();
                STATE_OUTLINE[s] = d.readShort();
                STATE_OCCLUSION[s] = d.readShort();
                STATE_INTERACTION[s] = d.readShort();
                STATE_STURDY[s] = d.readInt();
            }

            int nsh = d.readInt();
            SHAPE_BOXES = new double[nsh][];
            for (int i = 0; i < nsh; i++) {
                double[] boxes = new double[d.readUnsignedShort() * 6];
                for (int j = 0; j < boxes.length; j++) boxes[j] = d.readDouble();
                SHAPE_BOXES[i] = boxes;
            }

            int ni = d.readInt();
            ITEM_NAME = new String[ni];
            ITEM_MAX_STACK = new byte[ni];
            ITEM_MAX_DAMAGE = new int[ni];
            ITEM_BLOCK = new int[ni];
            for (int i = 0; i < ni; i++) {
                ITEM_NAME[i] = d.readUTF();
                ITEM_MAX_STACK[i] = d.readByte();
                ITEM_MAX_DAMAGE[i] = d.readInt();
                ITEM_BLOCK[i] = d.readInt();
            }

            int na = d.readInt();
            ATTR_NAME = new String[na];
            ATTR_DEFAULT = new double[na];
            ATTR_MIN = new double[na];
            ATTR_MAX = new double[na];
            ATTR_SYNC = new boolean[na];
            for (int i = 0; i < na; i++) {
                ATTR_NAME[i] = d.readUTF();
                ATTR_DEFAULT[i] = d.readDouble();
                ATTR_MIN[i] = d.readDouble();
                ATTR_MAX[i] = d.readDouble();
                ATTR_SYNC[i] = d.readBoolean();
            }

            int ne = d.readInt();
            ENTITY_NAME = new String[ne];
            ENTITY_PACKET_TYPE = new String[ne];
            ENTITY_WIDTH = new double[ne];
            ENTITY_HEIGHT = new double[ne];
            ENTITY_EYE_HEIGHT = new double[ne];
            ENTITY_TRACKING_RANGE = new int[ne];
            ENTITY_ATTRIBUTES = new double[ne][];
            for (int i = 0; i < ne; i++) {
                ENTITY_NAME[i] = d.readUTF();
                ENTITY_PACKET_TYPE[i] = d.readUTF();
                ENTITY_WIDTH[i] = d.readDouble();
                ENTITY_HEIGHT[i] = d.readDouble();
                ENTITY_EYE_HEIGHT[i] = d.readDouble();
                ENTITY_TRACKING_RANGE[i] = d.readInt();
                double[] attrs = new double[na];
                java.util.Arrays.fill(attrs, Double.NaN);
                int n = d.readUnsignedShort();
                for (int j = 0; j < n; j++) attrs[d.readUnsignedShort()] = d.readDouble();
                ENTITY_ATTRIBUTES[i] = attrs;
            }

            BLOCK_ENTITY_NAME = names(d);
            SOUND_NAME = names(d);
            PARTICLE_NAME = names(d);
            FLUID_NAME = names(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String[] names(DataInputStream d) throws IOException {
        String[] n = new String[d.readInt()];
        for (int i = 0; i < n.length; i++) n[i] = d.readUTF();
        return n;
    }

    private Registry() {}

    /** Number of block states (every valid state id is below this). */
    public static int stateCount() { return STATE_BLOCK.length; }
    public static int blockCount() { return BLOCK_NAME.length; }
    public static int itemCount() { return ITEM_NAME.length; }
    public static int entityTypeCount() { return ENTITY_NAME.length; }
    public static int attributeCount() { return ATTR_NAME.length; }

    public static String blockEntityTypeName(int id) { return BLOCK_ENTITY_NAME[id]; }
    public static String soundName(int id) { return SOUND_NAME[id]; }
    public static String particleName(int id) { return PARTICLE_NAME[id]; }
    public static String fluidName(int id) { return FLUID_NAME[id]; }
}
