package dev.mulcor.registry;

import static dev.mulcor.registry.Registry.SHAPE_BOXES;

/**
 * Vanilla voxel shapes as lists of axis-aligned boxes in block-local coordinates ({@code [0, 1]} for most blocks,
 * up to 1.5 high for fences and walls). Shape 0 is empty. Shapes are shared: a shape id from
 * {@link BlockData#collisionShape} and friends indexes this table. Boxes are exactly vanilla's
 * {@code VoxelShape.toAabbs()} values, as doubles.
 */
public final class Shapes {
    public static final int EMPTY = 0;

    private Shapes() {}

    public static int count() { return SHAPE_BOXES.length; }
    public static int boxCount(int shape) { return SHAPE_BOXES[shape].length / 6; }
    public static double minX(int shape, int box) { return SHAPE_BOXES[shape][box * 6]; }
    public static double minY(int shape, int box) { return SHAPE_BOXES[shape][box * 6 + 1]; }
    public static double minZ(int shape, int box) { return SHAPE_BOXES[shape][box * 6 + 2]; }
    public static double maxX(int shape, int box) { return SHAPE_BOXES[shape][box * 6 + 3]; }
    public static double maxY(int shape, int box) { return SHAPE_BOXES[shape][box * 6 + 4]; }
    public static double maxZ(int shape, int box) { return SHAPE_BOXES[shape][box * 6 + 5]; }

    /** The raw box array ({@code minX, minY, minZ, maxX, maxY, maxZ} per box); do not modify. */
    public static double[] boxes(int shape) { return SHAPE_BOXES[shape]; }

    /** True for exactly the unit cube (vanilla {@code Block.isShapeFullBlock}). */
    public static boolean isFullBlock(int shape) {
        double[] b = SHAPE_BOXES[shape];
        return b.length == 6 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1;
    }

    /** Highest top of any box, or 0 for an empty shape (vanilla {@code VoxelShape.max(Direction.Axis.Y)}). */
    public static double maxY(int shape) {
        double[] b = SHAPE_BOXES[shape];
        double m = 0;
        for (int i = 4; i < b.length; i += 6) m = Math.max(m, b[i]);
        return m;
    }
}
