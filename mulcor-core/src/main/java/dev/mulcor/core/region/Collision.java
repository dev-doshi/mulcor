package dev.mulcor.core.region;

import dev.mulcor.memory.BlockStorage;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.Registry;
import dev.mulcor.registry.Shapes;

/**
 * Entity-versus-block collision with vanilla's real block shapes, including step-up. Allocation-free: results go
 * into the region's scratch arrays.
 *
 * <h2>Vanilla (Mojang mappings, 26.2)</h2>
 * <pre>
 * Entity.collide(movement):
 *     aabb = getBoundingBox()
 *     vec = movement.lengthSqr() == 0 ? movement : collideBoundingBox(movement, aabb)   // Shapes.collide per axis
 *     xCol = movement.x != vec.x; yCol = movement.y != vec.y; zCol = movement.z != vec.z
 *     onGroundAfter = yCol &amp;&amp; movement.y &lt; 0
 *     if (maxUpStep() &gt; 0 &amp;&amp; (onGroundAfter || onGround()) &amp;&amp; (xCol || zCol)):
 *         aabb1 = onGroundAfter ? aabb.move(0, vec.y, 0) : aabb
 *         aabb2 = aabb1.expandTowards(movement.x, maxUpStep(), movement.z)
 *         if (!onGroundAfter) aabb2 = aabb2.expandTowards(0, -1.0E-5F, 0)
 *         colliders = collectCollidersIgnoringWorldBorder(aabb2)         // block shapes intersecting aabb2
 *         for f1 in collectCandidateStepUpHeights(aabb1, colliders, maxUpStep(), (float) vec.y):   // ascending
 *             vec1 = collideWithShapes(new Vec3(movement.x, f1, movement.z), aabb1, colliders)
 *             if (vec1.horizontalDistanceSqr() &gt; vec.horizontalDistanceSqr())
 *                 return vec1.subtract(0, aabb.minY - aabb1.minY, 0)
 *     return vec
 * collectCandidateStepUpHeights(box, colliders, maxStep, skip): for each shape, for each d in getCoords(Y)
 *     (ascending): f = (float)(d - box.minY); if f &lt; 0 continue; if f == skip continue; if f &gt; maxStep break;
 *     add f to a set. Sorted ascending.
 * Shapes.collide(axis, box, shapes, off): off = shape.collide(axis, box, off) for each shape, 0 once |off| &lt; 1e-7.
 * VoxelShape.collide(axis): a part of the shape counts if it overlaps the box on the other axes by more than 1e-7;
 *     moving +: d = partMin - boxMax, used if d &gt;= -1e-7 (off = min(off, d)); moving -: d = partMax - boxMin, used if
 *     d &lt;= 1e-7 (off = max(off, d)).
 * Direction.axisStepOrder(m) = |m.x| &lt; |m.z| ? Y, Z, X : Y, X, Z.
 * </pre>
 * Shapes are the vanilla collision boxes from the registry; collision is evaluated per box, which is the same set
 * of faces as the voxel grid. {@code getCoords(Y)} is a voxel shape's grid: shapes whose boxes all lie on a
 * 1/2^k grid within the cell are {@code CubeVoxelShape}s whose coordinates are every grid line (a bottom slab
 * reports 0, 0.5 and 1.0); others ({@code ArrayVoxelShape}) report their box faces. The world's sides and floor act
 * as solid walls (collision only; step-up candidates ignore them, like the world border).
 */
final class Collision {
    static final double EPS = 1.0E-7;
    private static final int X = 0, Y = 1, Z = 2;

    /** Per shape: its {@code getCoords(Y)} as offsets within the cell, ascending. */
    private static final double[][] COORDS_Y;
    /** Per state: collision shape id; 0 = empty, 1 means full cube is detected by {@link #FULL}. */
    private static final boolean[] FULL;

    static {
        int shapes = Shapes.count();
        COORDS_Y = new double[shapes][];
        for (int sh = 0; sh < shapes; sh++) COORDS_Y[sh] = gridCoordsY(sh);
        FULL = new boolean[Registry.stateCount()];
        for (int s = 0; s < FULL.length; s++) FULL[s] = Shapes.isFullBlock(BlockData.collisionShape(s));
    }

    private Collision() {}

    private static double[] gridCoordsY(int shape) {
        int n = Shapes.boxCount(shape);
        if (n == 0) return new double[0];
        for (int bits = 0; bits <= 3; bits++) {
            double res = 1 << bits;
            boolean fits = true;
            for (int i = 0; i < n && fits; i++) {
                double[] v = {Shapes.minX(shape, i), Shapes.minY(shape, i), Shapes.minZ(shape, i),
                        Shapes.maxX(shape, i), Shapes.maxY(shape, i), Shapes.maxZ(shape, i)};
                for (double c : v) {
                    double scaled = c * res;
                    if (c < -EPS || c > 1 + EPS || Math.abs(scaled - Math.rint(scaled)) > EPS * res) {
                        fits = false;
                        break;
                    }
                }
            }
            if (fits) {
                double[] out = new double[(1 << bits) + 1];
                for (int i = 0; i < out.length; i++) out[i] = i / res;
                return out;
            }
        }
        java.util.TreeSet<Double> set = new java.util.TreeSet<>();
        for (int i = 0; i < n; i++) {
            set.add(Shapes.minY(shape, i));
            set.add(Shapes.maxY(shape, i));
        }
        double[] out = new double[set.size()];
        int k = 0;
        for (double d : set) out[k++] = d;
        return out;
    }

    // ---- per-axis sweep --------------------------------------------------------------------------------------

    /**
     * {@code Shapes.collide(axis, box, blocks, off)} against the world's collision shapes. Layers of blocks are
     * scanned nearest first. A shape never extends below or sideways out of its cell, and at most 0.5 above it
     * (fences, walls), so the nearest hit is final except when moving down, where one more layer can hold a taller
     * shape: that layer is scanned too.
     */
    static double collide(Region r, int axis, double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ, double off) {
        if (Math.abs(off) < EPS) return 0.0;
        BlockStorage b = r.world.blocks;
        double eMin = axis == X ? minX : axis == Y ? minY : minZ;
        double eMax = axis == X ? maxX : axis == Y ? maxY : maxZ;
        // Perpendicular block ranges (one extra layer below in y for 1.5-high shapes).
        int ax0, ax1, bx0, bx1; // the two other axes, in order (x/z for Y; y/z for X; x/y for Z)
        if (axis == Y) {
            ax0 = (int) Math.floor(minX + EPS); ax1 = (int) Math.floor(maxX - EPS);
            bx0 = (int) Math.floor(minZ + EPS); bx1 = (int) Math.floor(maxZ - EPS);
        } else if (axis == X) {
            ax0 = (int) Math.floor(minY + EPS) - 1; ax1 = (int) Math.floor(maxY - EPS);
            bx0 = (int) Math.floor(minZ + EPS); bx1 = (int) Math.floor(maxZ - EPS);
        } else {
            ax0 = (int) Math.floor(minX + EPS); ax1 = (int) Math.floor(maxX - EPS);
            bx0 = (int) Math.floor(minY + EPS) - 1; bx1 = (int) Math.floor(maxY - EPS);
        }
        int dir = off > 0 ? 1 : -1;
        // Start in the cell holding the leading face (a partial shape there can be ahead of it) and go one layer
        // past the reach (margin for 1.5-high shapes below when moving down).
        int first = off > 0 ? (int) Math.floor(eMax - EPS) : (int) Math.floor(eMin + EPS);
        int last = off > 0 ? (int) Math.floor(eMax + off) + 1 : (int) Math.floor(eMin + off) - 1;
        int extraLayers = -1; // after the first hit: 0 more layers (1 when moving down in y)
        for (int layer = first; dir > 0 ? layer <= last : layer >= last; layer += dir) {
            boolean hitThisLayer = false;
            for (int a = ax0; a <= ax1; a++) {
                for (int c = bx0; c <= bx1; c++) {
                    int x, y, z;
                    if (axis == Y) { x = a; y = layer; z = c; }
                    else if (axis == X) { x = layer; y = a; z = c; }
                    else { x = a; y = c; z = layer; }
                    double before = off;
                    off = collideBlock(r, b, x, y, z, axis, minX, minY, minZ, maxX, maxY, maxZ, off);
                    if (off != before) hitThisLayer = true;
                    if (Math.abs(off) < EPS) return 0.0;
                }
            }
            if (extraLayers >= 0) {
                if (extraLayers == 0) break;
                extraLayers--;
            } else if (hitThisLayer) {
                extraLayers = axis == Y && off < 0 ? 1 : 0;
                if (extraLayers == 0) break;
                extraLayers--;
            }
        }
        return Math.abs(off) < EPS ? 0.0 : off;
    }

    /** One block's collision boxes (or a solid wall outside the world) against the entity box on one axis. */
    private static double collideBlock(Region r, BlockStorage b, int x, int y, int z, int axis, double minX, double minY,
            double minZ, double maxX, double maxY, double maxZ, double off) {
        boolean outside = x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ() || y < b.minY();
        if (outside) return collideBox(axis, x, y, z, x + 1, y + 1, z + 1, minX, minY, minZ, maxX, maxY, maxZ, off);
        int st = b.getShared(x, y, z);
        if (st == 0) return off;
        if (FULL[st]) return collideBox(axis, x, y, z, x + 1, y + 1, z + 1, minX, minY, minZ, maxX, maxY, maxZ, off);
        double[] boxes = Shapes.boxes(BlockData.collisionShape(st));
        for (int i = 0; i < boxes.length; i += 6) {
            off = collideBox(axis, x + boxes[i], y + boxes[i + 1], z + boxes[i + 2], x + boxes[i + 3], y + boxes[i + 4],
                    z + boxes[i + 5], minX, minY, minZ, maxX, maxY, maxZ, off);
        }
        return off;
    }

    private static double collideBox(int axis, double bx0, double by0, double bz0, double bx1, double by1, double bz1,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double off) {
        switch (axis) {
            case X -> {
                if (!(by1 > minY + EPS && by0 < maxY - EPS && bz1 > minZ + EPS && bz0 < maxZ - EPS)) return off;
                if (off > 0) { double d = bx0 - maxX; if (d >= -EPS) off = Math.min(off, d); }
                else { double d = bx1 - minX; if (d <= EPS) off = Math.max(off, d); }
            }
            case Y -> {
                if (!(bx1 > minX + EPS && bx0 < maxX - EPS && bz1 > minZ + EPS && bz0 < maxZ - EPS)) return off;
                if (off > 0) { double d = by0 - maxY; if (d >= -EPS) off = Math.min(off, d); }
                else { double d = by1 - minY; if (d <= EPS) off = Math.max(off, d); }
            }
            default -> {
                if (!(bx1 > minX + EPS && bx0 < maxX - EPS && by1 > minY + EPS && by0 < maxY - EPS)) return off;
                if (off > 0) { double d = bz0 - maxZ; if (d >= -EPS) off = Math.min(off, d); }
                else { double d = bz1 - minZ; if (d <= EPS) off = Math.max(off, d); }
            }
        }
        return off;
    }

    /** {@code BlockState.isCollisionShapeFullBlock} at (x, y, z); outside the world counts as a full block. */
    static boolean fullBlock(Region r, int x, int y, int z) {
        BlockStorage b = r.world.blocks;
        if (x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ() || y < b.minY()) return true;
        return FULL[b.getShared(x, y, z)];
    }

    /**
     * {@code Level.noCollision(box)} against blocks: no block collision box (or the world's walls) overlaps the box
     * with positive volume.
     */
    static boolean noCollision(Region r, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        BlockStorage b = r.world.blocks;
        // Shapes stay inside their cell sideways and reach at most 0.5 above it (fences, walls): one layer below.
        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY) - 1, y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean outside = x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ();
                for (int y = y0; y <= y1; y++) {
                    if (outside || y < b.minY()) {
                        if (overlaps(x, y, z, x + 1, y + 1, z + 1, minX, minY, minZ, maxX, maxY, maxZ)) return false;
                        continue;
                    }
                    int st = b.getShared(x, y, z);
                    if (st == 0) continue;
                    if (FULL[st]) {
                        if (overlaps(x, y, z, x + 1, y + 1, z + 1, minX, minY, minZ, maxX, maxY, maxZ)) return false;
                        continue;
                    }
                    double[] boxes = Shapes.boxes(BlockData.collisionShape(st));
                    for (int i = 0; i < boxes.length; i += 6) {
                        if (overlaps(x + boxes[i], y + boxes[i + 1], z + boxes[i + 2], x + boxes[i + 3], y + boxes[i + 4],
                                z + boxes[i + 5], minX, minY, minZ, maxX, maxY, maxZ)) return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean overlaps(double ax0, double ay0, double az0, double ax1, double ay1, double az1,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return ax0 < maxX && ax1 > minX && ay0 < maxY && ay1 > minY && az0 < maxZ && az1 > minZ;
    }

    /** {@code collideWithShapes} / {@code collideBoundingBox}: Y, then X and Z in {@code axisStepOrder}. Writes {@code out}. */
    static void collideBoundingBox(Region r, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double mx, double my, double mz, double[] out) {
        double cx = 0, cy = 0, cz = 0;
        if (my != 0.0) cy = collide(r, Y, minX, minY, minZ, maxX, maxY, maxZ, my);
        if (Math.abs(mx) < Math.abs(mz)) {
            if (mz != 0.0) cz = collide(r, Z, minX, minY + cy, minZ, maxX, maxY + cy, maxZ, mz);
            if (mx != 0.0) cx = collide(r, X, minX, minY + cy, minZ + cz, maxX, maxY + cy, maxZ + cz, mx);
        } else {
            if (mx != 0.0) cx = collide(r, X, minX, minY + cy, minZ, maxX, maxY + cy, maxZ, mx);
            if (mz != 0.0) cz = collide(r, Z, minX + cx, minY + cy, minZ, maxX + cx, maxY + cy, maxZ, mz);
        }
        out[0] = cx;
        out[1] = cy;
        out[2] = cz;
    }

    /**
     * {@code Entity.collide(movement)} including step-up (see the class notes). The resulting movement is written to
     * {@code out}. {@code onGround} is the entity's flag before this move.
     */
    static void collide(Region r, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double mx, double my, double mz, float maxUpStep, boolean onGround, double[] out) {
        if (mx * mx + my * my + mz * mz == 0.0) {
            out[0] = mx; out[1] = my; out[2] = mz;
            return;
        }
        collideBoundingBox(r, minX, minY, minZ, maxX, maxY, maxZ, mx, my, mz, out);
        double vx = out[0], vy = out[1], vz = out[2];
        boolean xCol = mx != vx, yCol = my != vy, zCol = mz != vz;
        boolean onGroundAfter = yCol && my < 0.0;
        if (!(maxUpStep > 0.0F && (onGroundAfter || onGround) && (xCol || zCol))) return;
        // aabb1 = onGroundAfter ? aabb.move(0, vec.y, 0) : aabb
        double dy1 = onGroundAfter ? vy : 0.0;
        double b1MinY = minY + dy1, b1MaxY = maxY + dy1;
        // aabb2 = aabb1.expandTowards(mx, maxUpStep, mz) [+ expandTowards(0, -1e-5F, 0)]
        double a2MinX = mx < 0 ? minX + mx : minX, a2MaxX = mx > 0 ? maxX + mx : maxX;
        double a2MinZ = mz < 0 ? minZ + mz : minZ, a2MaxZ = mz > 0 ? maxZ + mz : maxZ;
        double a2MinY = onGroundAfter ? b1MinY : b1MinY + (double) -1.0E-5F, a2MaxY = b1MaxY + maxUpStep;
        float skip = (float) vy;
        int n = candidates(r, a2MinX, a2MinY, a2MinZ, a2MaxX, a2MaxY, a2MaxZ, b1MinY, maxUpStep, skip);
        float[] cand = r.stepCandidates;
        double horiz = vx * vx + vz * vz;
        for (int i = 0; i < n; i++) {
            float f1 = cand[i];
            collideBoundingBox(r, minX, b1MinY, minZ, maxX, b1MaxY, maxZ, mx, f1, mz, out);
            if (out[0] * out[0] + out[2] * out[2] > horiz) {
                out[1] -= minY - b1MinY; // vec1.subtract(0, aabb.minY - aabb1.minY, 0)
                return;
            }
        }
        out[0] = vx;
        out[1] = vy;
        out[2] = vz;
    }

    /**
     * {@code collectCandidateStepUpHeights} over the block shapes intersecting the query box: distinct floats
     * {@code (float)(coord - boxMinY)} in {@code [0, maxStep]}, except {@code skip}, sorted ascending into
     * {@code r.stepCandidates}. Returns how many.
     */
    private static int candidates(Region r, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double boxMinY, float maxStep, float skip) {
        BlockStorage b = r.world.blocks;
        float[] out = r.stepCandidates;
        int n = 0;
        // Shapes stay inside their cell sideways and reach at most 0.5 above it (fences, walls): one layer below.
        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY) - 1, y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ()) continue; // border ignored
                for (int y = y0; y <= y1; y++) {
                    int st = b.getShared(x, y, z);
                    if (st == 0) continue;
                    int shape = BlockData.collisionShape(st);
                    double[] boxes = Shapes.boxes(shape);
                    if (boxes.length == 0) continue;
                    // BlockCollisions: only shapes that actually intersect the query box (positive volume).
                    boolean hits = false;
                    for (int i = 0; i < boxes.length && !hits; i += 6) {
                        hits = x + boxes[i] < maxX && x + boxes[i + 3] > minX && y + boxes[i + 1] < maxY
                                && y + boxes[i + 4] > minY && z + boxes[i + 2] < maxZ && z + boxes[i + 5] > minZ;
                    }
                    if (!hits) continue;
                    for (double c : COORDS_Y[shape]) {
                        float f = (float) (y + c - boxMinY);
                        if (f < 0.0F || f == skip) continue;
                        if (f > maxStep) break;
                        int k = 0;
                        while (k < n && out[k] < f) k++;
                        if (k < n && out[k] == f) continue;
                        if (n == out.length) continue; // bounded scratch; far more than any real neighbourhood needs
                        System.arraycopy(out, k, out, k + 1, n - k);
                        out[k] = f;
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
