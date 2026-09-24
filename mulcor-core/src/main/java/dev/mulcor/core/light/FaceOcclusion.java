package dev.mulcor.core.light;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.Registry;
import dev.mulcor.registry.Shapes;

/**
 * Directional light occlusion between neighbouring block states: vanilla's {@code LightEngine.shapeOccludes}.
 *
 * <h2>Vanilla (Mojang mappings, 1.20+)</h2>
 * <pre>
 * shapeOccludes(from, to, dir) = Shapes.faceShapeOccludes(getOcclusionShape(from, dir), getOcclusionShape(to, dir.opposite()))
 * getOcclusionShape(state, dir) = isEmptyShape(state) ? Shapes.empty() : state.getFaceOcclusionShape(dir)
 * isEmptyShape(state)           = !state.canOcclude() || !state.useShapeForLightOcclusion()
 * getFaceOcclusionShape(dir)    = Shapes.getFaceShape(state.getOcclusionShape(), dir)
 * Shapes.faceShapeOccludes(a, b) = a or b is the full face, or (a ∪ b) covers the whole face; false if both empty
 * </pre>
 * {@code ChunkSkyLightSources.isEdgeOccluded(above, below)} (sky sources) uses the same faces: {@code below}'s
 * light block is non-zero, or {@code faceShapeOccludes(getOcclusionShape(above, DOWN), getOcclusionShape(below, UP))}.
 * The registry carries {@code canOcclude} ({@link BlockData#OCCLUDES}) and the occlusion shape, but not
 * {@code useShapeForLightOcclusion}. For full-cube occlusion shapes the flag cannot change any result, since a
 * full face occludes and such states block light by opacity alike; for the rest (slabs, stairs, snow layers,
 * farmland, paths, ...) vanilla sets it. So: {@code useShape = canOcclude && !isFullBlock(occlusionShape)}. The
 * light oracle test (vanilla-stored light from real worlds) guards this.
 *
 * <p>Faces are precomputed per state and direction into rectangle lists in a shared 2-D frame per axis
 * (UP/DOWN in x,z; NORTH/SOUTH in x,y; WEST/EAST in z,y), so a lookup allocates nothing.
 */
public final class FaceOcclusion {
    /** Directions in vanilla {@code Direction} order: DOWN, UP, NORTH (-z), SOUTH (+z), WEST (-x), EAST (+x). */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    public static final int[] DX = {0, 0, 0, 0, -1, 1}, DY = {-1, 1, 0, 0, 0, 0}, DZ = {0, 0, -1, 1, 0, 0};

    private static final double EPS = 1e-7;
    /** Per state: bit d set if the face toward d is the full square. */
    private static final byte[] FULL;
    /** Per state: bit d set if the face toward d is empty. */
    private static final byte[] EMPTY;
    /** Per state: true if vanilla's isEmptyShape (light ignores its shape). */
    private static final boolean[] EMPTY_SHAPE;
    /** Per state × 6: rectangles {u0, v0, u1, v1, ...} for partial faces, or null. */
    private static final double[][] RECTS;

    static {
        int n = Registry.stateCount();
        FULL = new byte[n];
        EMPTY = new byte[n];
        EMPTY_SHAPE = new boolean[n];
        RECTS = new double[n * 6][];
        for (int s = 0; s < n; s++) {
            boolean canOcclude = BlockData.is(s, BlockData.OCCLUDES);
            int shape = BlockData.occlusionShape(s);
            EMPTY_SHAPE[s] = !canOcclude || Shapes.isFullBlock(shape);
            for (int d = 0; d < 6; d++) {
                double[] rects = EMPTY_SHAPE[s] ? new double[0] : face(shape, d);
                if (rects.length == 0) {
                    EMPTY[s] |= (byte) (1 << d);
                } else if (covers(rects, null)) {
                    FULL[s] |= (byte) (1 << d);
                } else {
                    RECTS[s * 6 + d] = rects;
                }
            }
        }
    }

    private FaceOcclusion() {}

    public static int opposite(int dir) {
        return dir ^ 1;
    }

    /** Vanilla {@code LightEngine.shapeOccludes(from, to, dir)}: may light pass from {@code from} into {@code to}? */
    public static boolean occludes(int from, int to, int dir) {
        if (EMPTY_SHAPE[from] && EMPTY_SHAPE[to]) return false; // fast path: both faces empty
        int opp = dir ^ 1;
        if ((FULL[from] >> dir & 1) != 0 || (FULL[to] >> opp & 1) != 0) return true;
        boolean ea = (EMPTY[from] >> dir & 1) != 0, eb = (EMPTY[to] >> opp & 1) != 0;
        if (ea && eb) return false;
        double[] a = ea ? null : RECTS[from * 6 + dir];
        double[] b = eb ? null : RECTS[to * 6 + opp];
        return covers(a == null ? b : a, a == null ? null : b);
    }

    /** Rectangles of the boxes of {@code shape} touching the face toward {@code dir}, in the axis' 2-D frame. */
    private static double[] face(int shape, int dir) {
        int n = Shapes.boxCount(shape);
        double[] out = new double[n * 4];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double x0 = Shapes.minX(shape, i), y0 = Shapes.minY(shape, i), z0 = Shapes.minZ(shape, i);
            double x1 = Shapes.maxX(shape, i), y1 = Shapes.maxY(shape, i), z1 = Shapes.maxZ(shape, i);
            boolean touches = switch (dir) {
                case DOWN -> y0 <= EPS;
                case UP -> y1 >= 1 - EPS;
                case NORTH -> z0 <= EPS;
                case SOUTH -> z1 >= 1 - EPS;
                case WEST -> x0 <= EPS;
                default -> x1 >= 1 - EPS;
            };
            if (!touches) continue;
            switch (dir) {
                case DOWN, UP -> { out[k++] = x0; out[k++] = z0; out[k++] = x1; out[k++] = z1; }
                case NORTH, SOUTH -> { out[k++] = x0; out[k++] = y0; out[k++] = x1; out[k++] = y1; }
                default -> { out[k++] = z0; out[k++] = y0; out[k++] = z1; out[k++] = y1; }
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    /**
     * Does the union of the rectangles in {@code a} and {@code b} cover the unit square? Sweep over x strips
     * between rectangle edges; in each strip, the y intervals of rectangles spanning it must cover [0, 1].
     * Allocation-free: at most a few dozen rectangles, scanned in place.
     */
    static boolean covers(double[] a, double[] b) {
        double x = 0;
        while (x < 1 - EPS) {
            // Next strip end: smallest rectangle edge > x.
            double next = 1;
            next = nextEdge(a, x, next);
            next = nextEdge(b, x, next);
            double mid = (x + next) / 2;
            // Cover [0,1] in y within this strip.
            double y = 0;
            boolean progress = true;
            while (y < 1 - EPS && progress) {
                progress = false;
                double reach = extend(a, mid, y, y);
                reach = extend(b, mid, y, reach);
                if (reach > y + EPS) {
                    y = reach;
                    progress = true;
                }
            }
            if (y < 1 - EPS) return false;
            x = next;
        }
        return true;
    }

    private static double nextEdge(double[] r, double x, double best) {
        if (r == null) return best;
        for (int i = 0; i < r.length; i += 4) {
            if (r[i] > x + EPS && r[i] < best) best = r[i];
            if (r[i + 2] > x + EPS && r[i + 2] < best) best = r[i + 2];
        }
        return best;
    }

    /** Largest v1 among rectangles spanning u = mid whose [v0, v1] contains y. */
    private static double extend(double[] r, double mid, double y, double reach) {
        if (r == null) return reach;
        for (int i = 0; i < r.length; i += 4) {
            if (r[i] <= mid && r[i + 2] >= mid && r[i + 1] <= y + EPS && r[i + 3] > reach) reach = r[i + 3];
        }
        return reach;
    }
}
