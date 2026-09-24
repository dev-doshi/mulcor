package dev.mulcor.core.region;

import static dev.mulcor.core.region.Entities.*;

import dev.mulcor.core.Blocks;
import dev.mulcor.core.Mth;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.EntityRecord;
import dev.mulcor.memory.EntityTable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Entity physics, ported from vanilla (Mojang mappings) to off-heap columns. All state is primitive: position and
 * velocity are doubles as in {@code Vec3}; constants keep vanilla's float-vs-double types (a {@code 0.98F} is
 * widened from float, exactly as javac does in vanilla). Nothing here allocates.
 *
 * <h2>Entity types</h2>
 * <table>
 *   <tr><th>Mulcor</th><th>Vanilla</th><th>size (w × h)</th><th>eye</th><th>gravity</th></tr>
 *   <tr><td>BOT</td><td>Zombie ({@code EntityType.ZOMBIE}, movement speed 0.23)</td><td>0.6F × 1.95F</td><td>1.74F</td><td>0.08 (attribute)</td></tr>
 *   <tr><td>PLAYER</td><td>Player (client-authoritative: never moved by the server)</td><td>0.6F × 1.8F</td><td>1.62F</td><td>-</td></tr>
 *   <tr><td>TNT</td><td>PrimedTnt</td><td>0.98F × 0.98F</td><td>0.15F</td><td>0.04</td></tr>
 *   <tr><td>FALLING_BLOCK</td><td>FallingBlockEntity</td><td>0.98F × 0.98F</td><td>-</td><td>0.04</td></tr>
 * </table>
 *
 * <h2>Known differences from vanilla (by design or not yet modelled)</h2>
 * <ul>
 *   <li><b>Push order.</b> Vanilla pushes each pair from inside every entity's own {@code aiStep}, in entity-list
 *       order, so a push can land before or after the other entity's {@code travel}. Regions tick in parallel,
 *       so all pushes of tick t are applied together at the start of tick t+1, before any travel. The impulses
 *       are vanilla's; only their ordering relative to other entities' movement differs.</li>
 *   <li><b>World edges</b> act as solid walls (vanilla has a world border and unloaded chunks).</li>
 *   <li><b>Mob AI</b> (yaw choice, when to jump) is Mulcor's own. It feeds the same inputs vanilla's
 *       {@code MoveControl}/{@code JumpControl} produce ({@code yRot}, {@code zza = speed}, {@code jumping}); the
 *       physics from there on is vanilla's.</li>
 *   <li>Not modelled: fluids, effects, slow blocks (soul sand, honey), ice friction, step-up onto partial blocks
 *       (every Mulcor block is a full cube, so vanilla's 0.6 step never applies), entity cramming damage.</li>
 * </ul>
 */
final class Physics {
    /** {@code Shapes.EPSILON}/{@code VoxelShape} collision epsilon. */
    static final double EPS = 1.0E-7;
    /** {@code Attributes.GRAVITY} default for living entities. */
    static final double MOB_GRAVITY = 0.08;
    /** {@code PrimedTnt.getDefaultGravity()} / {@code FallingBlockEntity.getDefaultGravity()}. */
    static final double BLOCK_GRAVITY = 0.04;
    /** {@code Block.getFriction()} default: every Mulcor block. */
    static final float BLOCK_FRICTION = 0.6F;
    /** Zombie {@code MOVEMENT_SPEED}. */
    static final float ZOMBIE_SPEED = 0.23F;
    /** {@code LivingEntity.getJumpPower()} = 0.42F × block jump factor (1.0F) + jump boost (0). */
    static final float JUMP_POWER = 0.42F;
    /** Mob push margin for the border snapshot: two half-widths plus slack. */
    static final double BORDER_MARGIN = 0.3 + 0.3 + 0.05;

    private Physics() {}

    static float width(int type) { return type == BOT || type == PLAYER ? 0.6F : 0.98F; }
    static float halfWidth(int type) { return width(type) / 2.0F; } // EntityDimensions.makeBoundingBox: float f = width / 2.0F
    static float height(int type) { return type == BOT ? 1.95F : type == PLAYER ? 1.8F : 0.98F; }
    static float eyeHeight(int type) { return type == BOT ? 1.74F : type == PLAYER ? 1.62F : 0.15F; }
    private static boolean pushable(int type) { return type == BOT; }
    private static boolean pusher(int type) { return type == BOT || type == PLAYER; }

    // ---- one entity ---------------------------------------------------------------------------------------------

    /** Advance one mob, primed TNT or falling block by one tick. Returns true if it left the region or despawned. */
    static boolean step(Region r, int s) {
        int type = r.table.type(s);
        if (type == BOT) {
            mobAiStep(r, s);
        } else if (type == FALLING_BLOCK) {
            if (fallingBlockTick(r, s)) return true;
        } else {
            gravityMoveDrag(r, s);
        }
        return handOff(r, s);
    }

    /**
     * {@code LivingEntity.aiStep} + {@code LivingEntity.travel}/{@code travelInAir}, in vanilla order:
     * <ol>
     *   <li>{@code if (noJumpDelay > 0) --noJumpDelay}</li>
     *   <li>velocity components with {@code |v| < 0.003} become 0 (x, z for non-players; y always)</li>
     *   <li>jump: if {@code jumping && onGround && noJumpDelay == 0}: {@code jumpFromGround()} sets
     *       {@code vy = max((double) 0.42F, vy)} and {@code noJumpDelay = 10}; if not jumping, {@code noJumpDelay = 0}</li>
     *   <li>{@code zza *= 0.98F} (Mob.setSpeed made {@code zza == speed})</li>
     *   <li>{@code f = onGround ? blockFriction : 1.0F; f1 = f * 0.91F}</li>
     *   <li>{@code moveRelative(onGround ? speed * (0.21600002F / (f*f*f)) : 0.02F, (xxa, 0, zza))}:
     *       input with {@code lengthSqr < 1e-7} is ignored, {@code > 1} is normalized, then scaled and rotated by
     *       {@code Mth.sin/cos(yRot * DEG_TO_RAD)}: {@code x += ix*cos - iz*sin, z += iz*cos + ix*sin}</li>
     *   <li>{@code move(SELF, deltaMovement)} (see {@link #move})</li>
     *   <li>{@code deltaMovement = (vx * f1, (vy - gravity) * 0.98F, vz * f1)}</li>
     * </ol>
     * Mulcor stand-in for {@code JumpControl}: {@code jumping} is set when walking and the last move was blocked
     * horizontally. {@code noJumpDelay} lives in {@code aux2}.
     */
    private static void mobAiStep(Region r, int s) {
        EntityTable t = r.table;
        int noJumpDelay = t.aux2(s);
        if (noJumpDelay > 0) noJumpDelay--;
        double vx = t.vx(s), vy = t.vy(s), vz = t.vz(s);
        if (Math.abs(vx) < 0.003) vx = 0.0;
        if (Math.abs(vz) < 0.003) vz = 0.0;
        if (Math.abs(vy) < 0.003) vy = 0.0;
        int flags = t.flags(s);
        boolean onGround = (flags & FLAG_ON_GROUND) != 0;
        float speed = t.inputForward(s);
        boolean jumping = speed != 0F && (flags & FLAG_H_COLLISION) != 0;
        if (jumping) {
            if (onGround && noJumpDelay == 0) {
                vy = Math.max((double) JUMP_POWER, vy);
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }
        t.setAux2(s, noJumpDelay);
        float zza = speed * 0.98F;
        float f = onGround ? BLOCK_FRICTION : 1.0F;
        float f1 = f * 0.91F;
        float amount = onGround ? speed * (0.21600002F / (f * f * f)) : 0.02F;
        double ix = 0.0, iz = zza; // (xxa, zza) as Vec3 components
        double lenSqr = ix * ix + iz * iz;
        if (lenSqr >= 1.0E-7) {
            if (lenSqr > 1.0) {
                double len = Math.sqrt(lenSqr);
                ix /= len;
                iz /= len;
            }
            ix *= amount;
            iz *= amount;
            float yRot = t.inputYaw(s);
            float sin = Mth.sin(yRot * Mth.DEG_TO_RAD), cos = Mth.cos(yRot * Mth.DEG_TO_RAD);
            vx += ix * (double) cos - iz * (double) sin;
            vz += iz * (double) cos + ix * (double) sin;
        }
        t.setVel(s, vx, vy, vz);
        move(r, s, vx, vy, vz);
        t.setVel(s, t.vx(s) * f1, (t.vy(s) - MOB_GRAVITY) * 0.98F, t.vz(s) * f1);
    }

    /**
     * {@code PrimedTnt.tick} movement part: {@code applyGravity()} ({@code vy -= 0.04}), {@code move}, then
     * {@code deltaMovement.scale(0.98D)}, and on the ground {@code multiply(0.7D, -0.5D, 0.7D)}. (The fuse is
     * counted by {@code Sim.tickTnt} after this, as in vanilla.)
     */
    static void gravityMoveDrag(Region r, int s) {
        EntityTable t = r.table;
        double vy = t.vy(s) - BLOCK_GRAVITY;
        t.setVel(s, t.vx(s), vy, t.vz(s));
        move(r, s, t.vx(s), vy, t.vz(s));
        double vx = t.vx(s) * 0.98, nvy = t.vy(s) * 0.98, vz = t.vz(s) * 0.98;
        if ((t.flags(s) & FLAG_ON_GROUND) != 0) {
            vx *= 0.7;
            nvy *= -0.5;
            vz *= 0.7;
        }
        t.setVel(s, vx, nvy, vz);
    }

    /**
     * {@code FallingBlockEntity.tick}: {@code ++time}; {@code applyGravity()} (0.04); {@code move}; then on the
     * ground: {@code deltaMovement.multiply(0.7, -0.5, 0.7)} and try to place the block at {@code blockPosition()}
     * (placed if that block {@code canBeReplaced} (air) and the block below is not {@code FallingBlock.isFree};
     * otherwise it breaks and drops as an item). In the air it drops after {@code time > 600}, or
     * {@code time > 100} outside the build height. Finally {@code deltaMovement.scale(0.98D)}.
     * {@code time} lives in {@code aux2}. Returns true if the entity was removed.
     */
    private static boolean fallingBlockTick(Region r, int s) {
        EntityTable t = r.table;
        int time = t.aux2(s) + 1;
        t.setAux2(s, time);
        double vy = t.vy(s) - BLOCK_GRAVITY;
        t.setVel(s, t.vx(s), vy, t.vz(s));
        move(r, s, t.vx(s), vy, t.vz(s));
        if ((t.flags(s) & FLAG_ON_GROUND) == 0) {
            int by = (int) Math.floor(t.y(s));
            BlockStorage b = r.world.blocks;
            if ((time > 100 && (by <= b.minY() || by >= b.maxYExclusive())) || time > 600) {
                r.despawn(s);
                r.droppedItems++;
                return true;
            }
            t.setVel(s, t.vx(s) * 0.98, t.vy(s) * 0.98, t.vz(s) * 0.98);
            return false;
        }
        int x = (int) Math.floor(t.x(s)), y = (int) Math.floor(t.y(s)), z = (int) Math.floor(t.z(s));
        if (r.world.ownerOfBlock(x, z) != r.id) { // the landing spot belongs to another region: land after the hand-off
            t.setVel(s, t.vx(s) * 0.7 * 0.98, t.vy(s) * -0.5 * 0.98, t.vz(s) * 0.7 * 0.98);
            return false;
        }
        BlockStorage b = r.world.blocks;
        int block = t.aux1(s);
        r.despawn(s);
        boolean replaceable = b.inBounds(x, y, z) && b.get(x, y, z) == Blocks.AIR;
        boolean belowFree = b.getShared(x, y - 1, z) == Blocks.AIR; // FallingBlock.isFree for Mulcor's blocks
        if (replaceable && !belowFree && b.set(x, y, z, block) != BlockStorage.FAILED) {
            Redstone.blockChanged(r, x, y, z);
        } else {
            r.droppedItems++; // breaks and drops as an item
        }
        return true;
    }

    /** Hand the entity to the region that owns its current position, if that is not us. */
    static boolean handOff(Region r, int s) {
        EntityTable t = r.table;
        int owner = r.world.ownerOfBlock((int) Math.floor(t.x(s)), (int) Math.floor(t.z(s)));
        return owner != r.id && r.migrate(s, owner);
    }

    // ---- Entity.move --------------------------------------------------------------------------------------------

    /**
     * {@code Entity.move(MoverType.SELF, movement)} for full-cube worlds:
     * <ol>
     *   <li>{@code collide(movement)}: per axis in {@code Direction.axisStepOrder(movement)} (Y, then X before Z
     *       unless {@code |x| < |z|}), {@code Shapes.collide} against every block box.</li>
     *   <li>The position moves only if {@code collided.lengthSqr() > 1e-7 || movement.lengthSqr() -
     *       collided.lengthSqr() < 1e-7}.</li>
     *   <li>{@code horizontalCollision = !Mth.equal(m.x, c.x) || !Mth.equal(m.z, c.z)} (tolerance 1e-5F);
     *       {@code verticalCollision = m.y != c.y}; {@code onGround = verticalCollision && m.y < 0}.</li>
     *   <li>A horizontally blocked axis zeroes that velocity component; a vertical collision zeroes {@code vy}
     *       ({@code Block.updateEntityMovementAfterFallOn}).</li>
     * </ol>
     * Only updates onGround if {@code |m.y| > 0}, like vanilla on the server.
     */
    static void move(Region r, int s, double mx, double my, double mz) {
        EntityTable t = r.table;
        int type = t.type(s);
        double hw = halfWidth(type), h = height(type);
        double x = t.x(s), y = t.y(s), z = t.z(s);
        double minX = x - hw, minY = y, minZ = z - hw, maxX = x + hw, maxY = y + h, maxZ = z + hw;
        BlockStorage b = r.world.blocks;
        double cx = 0, cy = 0, cz = 0;
        if (my != 0.0) cy = collideY(r, b, minX, minY, minZ, maxX, maxY, maxZ, my);
        if (Math.abs(mx) < Math.abs(mz)) {
            if (mz != 0.0) cz = collideZ(r, b, minX, minY + cy, minZ, maxX, maxY + cy, maxZ, mz);
            if (mx != 0.0) cx = collideX(r, b, minX, minY + cy, minZ + cz, maxX, maxY + cy, maxZ + cz, mx);
        } else {
            if (mx != 0.0) cx = collideX(r, b, minX, minY + cy, minZ, maxX, maxY + cy, maxZ, mx);
            if (mz != 0.0) cz = collideZ(r, b, minX + cx, minY + cy, minZ, maxX + cx, maxY + cy, maxZ, mz);
        }
        double cLen = cx * cx + cy * cy + cz * cz, mLen = mx * mx + my * my + mz * mz;
        if (cLen > 1.0E-7 || mLen - cLen < 1.0E-7) t.setPos(s, x + cx, y + cy, z + cz);
        boolean hx = Math.abs(cx - mx) >= 1.0E-5F, hz = Math.abs(cz - mz) >= 1.0E-5F;
        int flags = t.flags(s) & ~(FLAG_H_COLLISION | FLAG_X_COLLISION | FLAG_Z_COLLISION | FLAG_Y_COLLISION);
        if (hx || hz) flags |= FLAG_H_COLLISION;
        if (hx) flags |= FLAG_X_COLLISION;
        if (hz) flags |= FLAG_Z_COLLISION;
        if (Math.abs(my) > 0.0) {
            boolean vertical = my != cy;
            flags = vertical ? flags | FLAG_Y_COLLISION : flags;
            flags = vertical && my < 0.0 ? flags | FLAG_ON_GROUND : flags & ~FLAG_ON_GROUND;
        }
        t.setFlags(s, flags);
        double vx = t.vx(s), vy = t.vy(s), vz = t.vz(s);
        if (hx) vx = 0.0;
        if (hz) vz = 0.0;
        if (my != cy) vy = 0.0;
        t.setVel(s, vx, vy, vz);
    }

    /** Solid for movement: full-cube blocks, and everything outside the world's sides or below its floor. */
    private static boolean solid(Region r, BlockStorage b, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ() || y < b.minY()) return true;
        return Blocks.isSolid(b.getShared(x, y, z));
    }

    /**
     * {@code Shapes.collide(axis, box, shapes, offset)} for unit cubes. A block takes part if the box overlaps it
     * on the other two axes by more than 1e-7 ({@code VoxelShape.collideX}'s {@code findIndex(min + 1e-7)} /
     * {@code findIndex(max - 1e-7)}). Moving +: {@code d = blockMin - boxMax; if (d >= -1e-7) offset = min(offset, d)};
     * moving -: {@code d = blockMax - boxMin; if (d <= 1e-7) offset = max(offset, d)}. {@code |offset| < 1e-7}
     * snaps to 0.
     *
     * <p>Same result, fewer reads: the answer is the nearest block face ahead, so only the layers between the box
     * face and {@code face + offset} can matter. They are scanned nearest first, and the first solid layer decides
     * (vanilla takes min/max over all shapes, which is that same nearest face).
     */
    private static double collideY(Region r, BlockStorage b, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double off) {
        if (Math.abs(off) < EPS) return 0.0;
        int x0 = (int) Math.floor(minX + EPS), x1 = (int) Math.floor(maxX - EPS);
        int z0 = (int) Math.floor(minZ + EPS), z1 = (int) Math.floor(maxZ - EPS);
        if (off < 0) {
            for (int by = (int) Math.floor(minY + EPS) - 1, end = (int) Math.floor(minY + off) - 1; by >= end; by--) {
                if (by + 1 <= minY + off) break;
                for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.max(off, by + 1 - minY));
                }
            }
        } else {
            for (int by = (int) Math.ceil(maxY - EPS), end = (int) Math.floor(maxY + off) + 1; by <= end; by++) {
                if (by >= maxY + off) break;
                for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.min(off, by - maxY));
                }
            }
        }
        return off;
    }

    private static double collideX(Region r, BlockStorage b, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double off) {
        if (Math.abs(off) < EPS) return 0.0;
        int y0 = (int) Math.floor(minY + EPS), y1 = (int) Math.floor(maxY - EPS);
        int z0 = (int) Math.floor(minZ + EPS), z1 = (int) Math.floor(maxZ - EPS);
        if (off < 0) {
            for (int bx = (int) Math.floor(minX + EPS) - 1, end = (int) Math.floor(minX + off) - 1; bx >= end; bx--) {
                if (bx + 1 <= minX + off) break;
                for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.max(off, bx + 1 - minX));
                }
            }
        } else {
            for (int bx = (int) Math.ceil(maxX - EPS), end = (int) Math.floor(maxX + off) + 1; bx <= end; bx++) {
                if (bx >= maxX + off) break;
                for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.min(off, bx - maxX));
                }
            }
        }
        return off;
    }

    private static double collideZ(Region r, BlockStorage b, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double off) {
        if (Math.abs(off) < EPS) return 0.0;
        int x0 = (int) Math.floor(minX + EPS), x1 = (int) Math.floor(maxX - EPS);
        int y0 = (int) Math.floor(minY + EPS), y1 = (int) Math.floor(maxY - EPS);
        if (off < 0) {
            for (int bz = (int) Math.floor(minZ + EPS) - 1, end = (int) Math.floor(minZ + off) - 1; bz >= end; bz--) {
                if (bz + 1 <= minZ + off) break;
                for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.max(off, bz + 1 - minZ));
                }
            }
        } else {
            for (int bz = (int) Math.ceil(maxZ - EPS), end = (int) Math.floor(maxZ + off) + 1; bz <= end; bz++) {
                if (bz >= maxZ + off) break;
                for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) {
                    if (solid(r, b, bx, by, bz)) return snap(Math.min(off, bz - maxZ));
                }
            }
        }
        return off;
    }

    private static double snap(double off) {
        return Math.abs(off) < EPS ? 0.0 : off;
    }

    // ---- falling blocks -----------------------------------------------------------------------------------------

    /** {@code FallingBlockEntity.fall}: spawned at (x + 0.5, y, z + 0.5) with zero velocity. */
    static void spawnFallingBlock(Region r, double x, double y, double z, int block) {
        int eid = r.spawn(x, y, z, FALLING_BLOCK, 0);
        if (eid >= 0) {
            r.table.setAux1(r.world.directory.slot(eid), block);
        } else {
            r.droppedItems++; // no room for the entity: the block drops as an item
        }
    }

    // ---- pushing (momentum transfer) ----------------------------------------------------------------------------

    private static int cellHash(int cx, int cz) {
        return (cx * 0x9E3779B1 + cz * 0x85EBCA77) >>> (32 - Region.GRID_BITS);
    }

    /**
     * {@code LivingEntity.pushEntities} → {@code Entity.push(Entity)} for every pair of overlapping mobs
     * ({@code AABB.intersects}, strict). Vanilla runs the call from both entities' {@code aiStep}, so each pair
     * gets the impulse twice. See the class notes on ordering.
     */
    static void pushAll(Region r) {
        EntityTable t = r.table;
        int n = t.count();
        int[] head = r.gridHead, stamp = r.gridStamp, next = r.gridNext, cellX = r.gridCellX, cellZ = r.gridCellZ;
        double[] px = r.gridX, pz = r.gridZ;
        int gen = ++r.gridGen;
        if (gen == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);
            gen = r.gridGen = 1;
        }
        int pushers = 0;
        for (int s = 0; s < n; s++) {
            if (!pusher(t.type(s))) {
                cellX[s] = Integer.MIN_VALUE;
                continue;
            }
            double x = t.x(s), z = t.z(s);
            px[s] = x;
            pz[s] = z;
            int cx = (int) x >> 1, cz = (int) z >> 1; // coordinates are ≥ 0: the cast is floor
            cellX[s] = cx;
            cellZ[s] = cz;
            int c = cellHash(cx, cz);
            if (stamp[c] != gen) {
                stamp[c] = gen;
                head[c] = -1;
            }
            next[s] = head[c];
            head[c] = s;
            pushers++;
        }
        if (pushers == 0) return;
        int cb = r.cfg.cellBlocks();
        for (int s = 0; s < n; s++) {
            int cx = cellX[s];
            if (cx == Integer.MIN_VALUE) continue;
            int cz = cellZ[s];
            // Half stencil: own cell (pairs with j > s) and the 4 cells "after" it, so every pair is visited once.
            pairsIn(r, s, cx, cz, true);
            pairsIn(r, s, cx + 1, cz - 1, false);
            pairsIn(r, s, cx + 1, cz, false);
            pairsIn(r, s, cx + 1, cz + 1, false);
            pairsIn(r, s, cx, cz + 1, false);
            if (pushable(t.type(s)) && nearCellEdge(px[s], pz[s], cb)) pushAcrossBorder(r, s);
        }
    }

    private static void pairsIn(Region r, int s, int gx, int gz, boolean same) {
        int c = cellHash(gx, gz);
        if (r.gridStamp[c] != r.gridGen) return;
        int[] next = r.gridNext, cellX = r.gridCellX, cellZ = r.gridCellZ;
        double[] px = r.gridX, pz = r.gridZ;
        EntityTable t = r.table;
        for (int j = r.gridHead[c]; j >= 0; j = next[j]) {
            if (cellX[j] != gx || cellZ[j] != gz || (same && j <= s)) continue; // hash collisions share buckets
            double dx = px[j] - px[s], dz = pz[j] - pz[s];
            if (dx >= 1.0 || dx <= -1.0 || dz >= 1.0 || dz <= -1.0) continue; // no mob is wider than 0.98
            if (!intersects(px[s], t.y(s), pz[s], t.type(s), px[j], t.y(j), pz[j], t.type(j))) continue;
            pushPair(r, s, j);
            pushPair(r, s, j);
        }
    }

    /** Region borders run along cell edges: a mob farther than the push margin from every edge cannot touch one. */
    static boolean nearCellEdge(double x, double z, int cellBlocks) {
        double fx = x % cellBlocks, fz = z % cellBlocks;
        return fx < BORDER_MARGIN || fx > cellBlocks - BORDER_MARGIN || fz < BORDER_MARGIN || fz > cellBlocks - BORDER_MARGIN;
    }

    /**
     * {@code Entity.push(Entity other)}: {@code d0 = other.x - x, d1 = other.z - z, d2 = Mth.absMax(d0, d1)}; if
     * {@code d2 >= 0.01F}: {@code d2 = sqrt(d2); d0 /= d2; d1 /= d2; d3 = min(1, 1/d2); d0 *= d3 * 0.05F…};
     * this entity gets {@code (-d0, 0, -d1)}, the other {@code (d0, 0, d1)}, each only if pushable.
     */
    private static void pushPair(Region r, int a, int b) {
        EntityTable t = r.table;
        double d0 = t.x(b) - t.x(a), d1 = t.z(b) - t.z(a);
        double d2 = Mth.absMax(d0, d1);
        if (d2 < 0.01F) return;
        d2 = Math.sqrt(d2);
        d0 /= d2;
        d1 /= d2;
        double d3 = 1.0 / d2;
        if (d3 > 1.0) d3 = 1.0;
        d0 *= d3;
        d1 *= d3;
        d0 *= 0.05F;
        d1 *= 0.05F;
        if (pushable(t.type(a))) t.setVel(a, t.vx(a) - d0, t.vy(a), t.vz(a) - d1);
        if (pushable(t.type(b))) t.setVel(b, t.vx(b) + d0, t.vy(b), t.vz(b) + d1);
        r.pushes++;
    }

    /** {@code AABB.intersects}: strict overlap on all three axes. */
    private static boolean intersects(double xa, double ya, double za, int ta, double xb, double yb, double zb, int tb) {
        double ha = halfWidth(ta), hb = halfWidth(tb);
        return xa - ha < xb + hb && xa + ha > xb - hb && ya < yb + height(tb) && ya + height(ta) > yb
                && za - ha < zb + hb && za + ha > zb - hb;
    }

    /** Push slot {@code s} away from foreign mobs in the neighbouring regions' previous-epoch snapshots. */
    private static void pushAcrossBorder(Region r, int s) {
        EntityTable t = r.table;
        double x = t.x(s), z = t.z(s), m = BORDER_MARGIN;
        int o1 = owner(r, x - m, z - m), o2 = owner(r, x + m, z - m), o3 = owner(r, x - m, z + m), o4 = owner(r, x + m, z + m);
        if (o1 != r.id) pushFrom(r, s, o1);
        if (o2 != r.id && o2 != o1) pushFrom(r, s, o2);
        if (o3 != r.id && o3 != o1 && o3 != o2) pushFrom(r, s, o3);
        if (o4 != r.id && o4 != o1 && o4 != o2 && o4 != o3) pushFrom(r, s, o4);
    }

    private static int owner(Region r, double x, double z) {
        return r.world.ownerOfBlock(Math.clamp((int) Math.floor(x), 0, r.world.sizeX() - 1),
                Math.clamp((int) Math.floor(z), 0, r.world.sizeZ() - 1));
    }

    /** The half of {@link #pushPair} that falls on our entity, applied twice, from the neighbour's snapshot. */
    private static void pushFrom(Region r, int s, int other) {
        Region o = r.world.regions[other];
        MemorySegment snap = o.snapshot(r.epoch - 1);
        if (snap == null) return;
        int n = o.snapshotCount(r.epoch - 1);
        EntityTable t = r.table;
        int ts = t.type(s);
        long self = t.id(s);
        double sx = t.x(s), sz = t.z(s);
        for (int i = 0; i < n; i++) {
            long off = (long) i * EntityRecord.BYTES;
            // Cheap rejections first: most snapshot entries are nowhere near us.
            double xj = snap.get(ValueLayout.JAVA_DOUBLE, off + EntityRecord.X);
            if (Math.abs(xj - sx) >= 0.98) continue;
            double zj = snap.get(ValueLayout.JAVA_DOUBLE, off + EntityRecord.Z);
            if (Math.abs(zj - sz) >= 0.98) continue;
            int tj = snap.get(ValueLayout.JAVA_INT, off + EntityRecord.TYPE);
            double yj = snap.get(ValueLayout.JAVA_DOUBLE, off + EntityRecord.Y);
            if (!intersects(sx, t.y(s), sz, ts, xj, yj, zj, tj)) continue;
            long id = snap.get(ValueLayout.JAVA_LONG, off + EntityRecord.ID);
            if (id == self || r.world.ownerOfEntity((int) id) == r.id) continue; // it is (now) ours: handled locally
            double d0 = xj - t.x(s), d1 = zj - t.z(s);
            double d2 = Mth.absMax(d0, d1);
            if (d2 < 0.01F) continue;
            d2 = Math.sqrt(d2);
            d0 /= d2;
            d1 /= d2;
            double d3 = 1.0 / d2;
            if (d3 > 1.0) d3 = 1.0;
            d0 *= d3;
            d1 *= d3;
            d0 *= 0.05F;
            d1 *= 0.05F;
            t.setVel(s, t.vx(s) - d0 - d0, t.vy(s), t.vz(s) - d1 - d1);
            r.borderPushes++;
        }
    }

    /**
     * End of tick: publish this region's mobs near a border into this epoch's snapshot buffer, for neighbours to
     * read next epoch. Entries are full {@link EntityRecord}s, so the snapshot can later carry every entity.
     */
    static void publishSnapshot(Region r) {
        MemorySegment snap = r.snapshotForWrite();
        EntityTable t = r.table;
        int n = 0, cap = r.snapshotCapacity();
        double m = BORDER_MARGIN;
        int cb = r.cfg.cellBlocks();
        for (int s = 0; s < t.count() && n < cap; s++) {
            if (!pusher(t.type(s))) continue;
            double x = t.x(s), z = t.z(s);
            if (!nearCellEdge(x, z, cb)) continue;
            if (owner(r, x - m, z - m) == r.id && owner(r, x + m, z - m) == r.id
                    && owner(r, x - m, z + m) == r.id && owner(r, x + m, z + m) == r.id) continue;
            t.writeRecord(s, snap, (long) n * EntityRecord.BYTES);
            n++;
        }
        r.publishSnapshot(n);
    }
}
