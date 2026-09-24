package dev.mulcor.core.region;

import dev.mulcor.core.light.FaceOcclusion;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.Shapes;

/**
 * Faces of collision shapes, for {@code FlowingFluid.canPassThroughWall}: can a fluid pass between two neighbouring
 * blocks, given their collision shapes?
 * <pre>
 * canPassThroughWall(dir, pos, state, spreadPos, spreadState):
 *   spreadState's or state's collision shape is the full block → false
 *   both empty → true
 *   else !Shapes.mergedFaceOccludes(state's shape, spreadState's shape, dir)
 * mergedFaceOccludes(a, b, dir) = the union of a's face toward dir and b's face toward -dir covers the whole face
 * </pre>
 * Faces are precomputed per collision shape (not per state: many states share a shape) as rectangle lists in the
 * 2-D frame {@link FaceOcclusion} uses, so a lookup allocates nothing.
 */
final class CollisionFaces {
    private static final double EPS = 1e-7;
    private static final boolean[] FULL, EMPTY;
    /** Per shape × 6: the face rectangles {u0, v0, u1, v1, ...}, or null when the face is empty. */
    private static final double[][] RECTS;

    static {
        int n = Shapes.count();
        FULL = new boolean[n];
        EMPTY = new boolean[n];
        RECTS = new double[n * 6][];
        for (int s = 0; s < n; s++) {
            FULL[s] = Shapes.isFullBlock(s);
            EMPTY[s] = Shapes.boxCount(s) == 0;
            for (int d = 0; d < 6; d++) {
                double[] r = face(s, d);
                RECTS[s * 6 + d] = r.length == 0 ? null : r;
            }
        }
    }

    private CollisionFaces() {}

    /** {@code FlowingFluid.canPassThroughWall(direction, level, pos, state, spreadPos, spreadState)}. */
    static boolean canPassThroughWall(int direction, int state, int spreadState) {
        int spread = BlockData.collisionShape(spreadState);
        if (FULL[spread]) return false;
        int own = BlockData.collisionShape(state);
        if (FULL[own]) return false;
        if (EMPTY[spread] && EMPTY[own]) return true;
        return !mergedFaceOccludes(own, spread, direction);
    }

    /** {@code Shapes.mergedFaceOccludes(shape, adjacentShape, side)} for two non-full shapes. */
    private static boolean mergedFaceOccludes(int shape, int adjacent, int side) {
        double[] a = RECTS[shape * 6 + side], b = RECTS[adjacent * 6 + (side ^ 1)];
        if (a == null && b == null) return false;
        return FaceOcclusion.covers(a == null ? b : a, a == null ? null : b);
    }

    /** Rectangles of the boxes of {@code shape} touching the face toward {@code dir} (see {@code FaceOcclusion}). */
    private static double[] face(int shape, int dir) {
        int n = Shapes.boxCount(shape);
        double[] out = new double[n * 4];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double x0 = Shapes.minX(shape, i), y0 = Shapes.minY(shape, i), z0 = Shapes.minZ(shape, i);
            double x1 = Shapes.maxX(shape, i), y1 = Shapes.maxY(shape, i), z1 = Shapes.maxZ(shape, i);
            boolean touches = switch (dir) {
                case 0 -> y0 <= EPS;
                case 1 -> y1 >= 1 - EPS;
                case 2 -> z0 <= EPS;
                case 3 -> z1 >= 1 - EPS;
                case 4 -> x0 <= EPS;
                default -> x1 >= 1 - EPS;
            };
            if (!touches) continue;
            switch (dir) {
                case 0, 1 -> { out[k++] = x0; out[k++] = z0; out[k++] = x1; out[k++] = z1; }
                case 2, 3 -> { out[k++] = x0; out[k++] = y0; out[k++] = x1; out[k++] = y1; }
                default -> { out[k++] = z0; out[k++] = y0; out[k++] = z1; out[k++] = y1; }
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }
}
