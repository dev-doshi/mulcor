package dev.mulcor.core.region;

import static dev.mulcor.core.region.Entities.*;

import dev.mulcor.core.Mth;
import dev.mulcor.core.World;
import dev.mulcor.memory.BlockStorage;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;

/**
 * {@code ItemEntity}, ported: dropped stacks that fall, float, slide, merge, despawn after five minutes and are
 * picked up by players.
 *
 * <h2>Columns</h2>
 * {@code aux1} = item | count << 16 (the {@link Stacks} low bits); {@code aux0} = damage (bits 0-15) |
 * {@code tickCount % 40} (bits 16-21) | health (bits 24-27); {@code aux2} = age (low 16 bits, signed) |
 * pickup delay << 16. An item whose count is 0 is dead: it is skipped by pickup and merging and discarded when it
 * next ticks (vanilla: {@code if (getItem().isEmpty()) discard()}).
 *
 * <h2>Known differences</h2>
 * Pickup and merging only see items and players of the same region. The {@code tickCount} phase is kept modulo
 * 40, which is all vanilla reads of it. Burning after leaving lava or fire ({@code remainingFireTicks}) is not
 * modelled; the RNG is the region's, not the entity's.
 */
final class ItemEntities {
    static final float WIDTH = 0.25F, HEIGHT = 0.25F;
    static final int LIFETIME = 6000, INFINITE_PICKUP_DELAY = 32767, INFINITE_LIFETIME = -32768;
    private static final int HEALTH = 5;
    private static final int CACTUS = BlockId.CACTUS, FIRE = BlockId.FIRE, SOUL_FIRE = BlockId.SOUL_FIRE;
    private static final boolean[] FIRE_RESISTANT = new boolean[dev.mulcor.registry.ItemId.COUNT];

    static {
        for (int i = 1; i < FIRE_RESISTANT.length; i++) {
            String n = dev.mulcor.registry.Items.name(i);
            FIRE_RESISTANT[i] = n.contains("netherite") || n.endsWith("ancient_debris");
        }
    }

    private ItemEntities() {}

    // ---- columns ------------------------------------------------------------------------------------------------

    static long stack(EntityTable t, int s) {
        return (t.aux1(s) & 0xFFFFFFL) | (long) (t.aux0(s) & 0xFFFF) << 24;
    }

    private static void setStack(EntityTable t, int s, long stack) {
        t.setAux1(s, (int) (stack & 0xFFFFFF));
        t.setAux0(s, (t.aux0(s) & ~0xFFFF) | Stacks.damage(stack));
    }

    private static int age(EntityTable t, int s) { return (short) t.aux2(s); }
    private static int pickupDelay(EntityTable t, int s) { return t.aux2(s) >>> 16; }
    private static void setAgeDelay(EntityTable t, int s, int age, int delay) {
        t.setAux2(s, (age & 0xFFFF) | delay << 16);
    }

    // ---- spawning -----------------------------------------------------------------------------------------------

    /** {@code new ItemEntity(level, x, y, z, stack)} with the given motion and pickup delay; returns the id or -1. */
    static int spawn(Region r, double x, double y, double z, long stack, double vx, double vy, double vz, int pickupDelay) {
        if (stack == Stacks.EMPTY) return -1;
        int eid = r.spawn(x, y, z, ITEM, 0);
        if (eid < 0) {
            r.droppedItems += Stacks.count(stack);
            return -1;
        }
        int s = r.world.directory.slot(eid);
        EntityTable t = r.table;
        t.setAux0(s, HEALTH << 24);
        setStack(t, s, stack);
        setAgeDelay(t, s, 0, pickupDelay);
        t.setVel(s, vx, vy, vz);
        t.setFlags(s, 0);
        r.itemsSpawned++;
        return eid;
    }

    /**
     * {@code Block.popResource(level, pos, stack)}: at the block's centre ±0.25 (y lowered by half the item height),
     * with the {@code ItemEntity} constructor's motion {@code (nextDouble() * 0.2 - 0.1, 0.2, nextDouble() * 0.2 - 0.1)}
     * and the default pickup delay (10).
     */
    static void popResource(Region r, int x, int y, int z, long stack) {
        if (stack == Stacks.EMPTY) return;
        double h = (double) HEIGHT / 2.0;
        double px = x + 0.5 + nextDouble(r, -0.25, 0.25);
        double py = y + 0.5 + nextDouble(r, -0.25, 0.25) - h;
        double pz = z + 0.5 + nextDouble(r, -0.25, 0.25);
        spawn(r, px, py, pz, stack, r.nextDouble() * 0.2 - 0.1, 0.2, r.nextDouble() * 0.2 - 0.1, 10);
    }

    /** {@code Mth.nextDouble(random, min, max)}. */
    private static double nextDouble(Region r, double min, double max) {
        return min >= max ? min : r.nextDouble() * (max - min) + min;
    }

    /**
     * {@code Player.drop(stack, throwRandomly, retainOwnership)}: from the eyes minus 0.3, pickup delay 40. Thrown
     * forward along the look direction, or (death) in a random direction.
     */
    static void dropFromPlayer(Region r, int eid, long stack, boolean throwRandomly) {
        if (stack == Stacks.EMPTY) return;
        if (!r.ownsEntity(eid)) {
            r.droppedItems += Stacks.count(stack);
            return;
        }
        EntityTable t = r.table;
        int s = r.world.directory.slot(eid);
        double x = t.x(s), z = t.z(s);
        double y = t.y(s) + (double) eyeHeight(r.world.presence[eid]) - (double) 0.3F;
        double vx, vy, vz;
        if (throwRandomly) {
            float f = r.nextFloat() * 0.5F;
            float f1 = r.nextFloat() * (float) (Math.PI * 2);
            vx = -Mth.sin(f1) * f;
            vy = 0.2F;
            vz = Mth.cos(f1) * f;
        } else {
            float yaw = Float.intBitsToFloat(t.aux1(s)), pitch = Float.intBitsToFloat(t.aux2(s));
            float f1 = Mth.sin(pitch * Mth.DEG_TO_RAD), f2 = Mth.cos(pitch * Mth.DEG_TO_RAD);
            float f3 = Mth.sin(yaw * Mth.DEG_TO_RAD), f4 = Mth.cos(yaw * Mth.DEG_TO_RAD);
            float f5 = r.nextFloat() * (float) (Math.PI * 2);
            float f6 = 0.02F * r.nextFloat();
            vx = (double) (-f3 * f2 * 0.3F) + Math.cos(f5) * (double) f6;
            vy = (double) (-f1 * 0.3F + 0.1F + (r.nextFloat() - r.nextFloat()) * 0.1F);
            vz = (double) (f4 * f2 * 0.3F) + Math.sin(f5) * (double) f6;
        }
        spawn(r, x, y, z, stack, vx, vy, vz, 40);
    }

    /** A player's eye height for its pose ({@code Player} dimensions). */
    static float eyeHeight(int presence) {
        return switch (Presence.pose(presence)) {
            case Presence.POSE_CROUCHING -> 1.27F;
            case Presence.POSE_SWIMMING, Presence.POSE_FALL_FLYING, Presence.SPIN_ATTACK -> 0.4F;
            case Presence.SLEEPING -> 0.2F;
            default -> 1.62F;
        };
    }

    /** A player's bounding box height for its pose. */
    static float playerHeight(int presence) {
        return switch (Presence.pose(presence)) {
            case Presence.POSE_CROUCHING -> 1.5F;
            case Presence.POSE_SWIMMING, Presence.POSE_FALL_FLYING, Presence.SPIN_ATTACK -> 0.6F;
            case Presence.SLEEPING -> 0.2F;
            default -> 1.8F;
        };
    }

    // ---- tick ---------------------------------------------------------------------------------------------------

    /** {@code ItemEntity.tick}. Returns true if the entity left the region or was removed. */
    static boolean tick(Region r, int s) {
        EntityTable t = r.table;
        long stack = stack(t, s);
        if (Stacks.count(stack) == 0) {
            r.despawn(s);
            return true;
        }
        int aux0 = t.aux0(s);
        int tickCount = ((aux0 >>> 16 & 63) + 1) % 40; // ++tickCount before tick (ServerLevel.tickNonPassenger)
        int health = aux0 >>> 24 & 15;
        int eid = (int) t.id(s);
        // Entity.baseTick → updateInWaterStateAndDoFluidPushing
        double[] fh = r.fluidHeights;
        fluidPushing(r, s, fh);
        boolean inWater = fh[0] >= 0, inLava = fh[1] >= 0;
        double waterHeight = fh[0], lavaHeight = fh[1];
        if (inLava && !FIRE_RESISTANT[Stacks.item(stack)]) health -= 4; // lavaHurt: hurt(lava, 4)
        int age = age(t, s), delay = pickupDelay(t, s);
        if (delay > 0 && delay != INFINITE_PICKUP_DELAY) delay--;
        double xo = t.x(s), yo = t.y(s), zo = t.z(s);
        double vx = t.vx(s), vy = t.vy(s), vz = t.vz(s);
        if (inWater && waterHeight > 0.1F) {
            vx *= 0.99F;
            vy += vy < 0.06F ? 5.0E-4F : 0.0F;
            vz *= 0.99F;
        } else if (inLava && lavaHeight > 0.1F) {
            vx *= 0.95F;
            vy += vy < 0.06F ? 5.0E-4F : 0.0F;
            vz *= 0.95F;
        } else {
            vy -= Physics.BLOCK_GRAVITY; // applyGravity: 0.04
        }
        t.setVel(s, vx, vy, vz);
        double hw = WIDTH / 2.0F;
        boolean noPhysics = !Collision.noCollision(r, xo - hw + 1.0E-7, yo + 1.0E-7, zo - hw + 1.0E-7,
                xo + hw - 1.0E-7, yo + HEIGHT - 1.0E-7, zo + hw - 1.0E-7);
        if (noPhysics) moveTowardsClosestSpace(r, s, xo, yo + (double) HEIGHT / 2.0, zo);
        vx = t.vx(s);
        vy = t.vy(s);
        vz = t.vz(s);
        boolean onGround = (t.flags(s) & FLAG_ON_GROUND) != 0;
        if (!onGround || vx * vx + vz * vz > 1.0E-5F || (tickCount + eid) % 4 == 0) {
            if (noPhysics) t.setPos(s, xo + vx, yo + vy, zo + vz);
            else Physics.move(r, s, vx, vy, vz);
            health -= blockDamage(r, t, s, stack);
            onGround = (t.flags(s) & FLAG_ON_GROUND) != 0;
            float f = 0.98F;
            if (onGround) {
                int below = r.world.blocks.getShared((int) Math.floor(t.x(s)), (int) Math.floor(t.y(s) - 0.500001F),
                        (int) Math.floor(t.z(s)));
                f = BlockData.friction(BlockData.block(below)) * 0.98F;
            }
            vx = t.vx(s) * f;
            vy = t.vy(s) * 0.98;
            vz = t.vz(s) * f;
            if (onGround && vy < 0.0) vy *= -0.5;
            t.setVel(s, vx, vy, vz);
        }
        t.setAux0(s, (aux0 & 0xFFFF) | tickCount << 16 | Math.max(health, 0) << 24);
        if (health <= 0) {
            r.despawn(s);
            return true;
        }
        setAgeDelay(t, s, age, delay);
        boolean moved = Math.floor(xo) != Math.floor(t.x(s)) || Math.floor(yo) != Math.floor(t.y(s))
                || Math.floor(zo) != Math.floor(t.z(s));
        if (tickCount % (moved ? 2 : 40) == 0 && mergable(t, s)) {
            if (mergeWithNeighbours(r, s)) return true;
            age = age(t, s);
            delay = pickupDelay(t, s);
        }
        if (age != INFINITE_LIFETIME) age++;
        setAgeDelay(t, s, age, delay);
        fluidPushing(r, s, fh); // hasImpulse |= updateInWaterStateAndDoFluidPushing()
        if (age >= LIFETIME) {
            r.despawn(s);
            return true;
        }
        return Physics.handOff(r, s);
    }

    /** {@code Entity.applyEffectsFromBlocks}: cactus and fire hurt an item by 1 per tick. Returns the damage. */
    private static int blockDamage(Region r, EntityTable t, int s, long stack) {
        double hw = WIDTH / 2.0F, x = t.x(s), y = t.y(s), z = t.z(s);
        int x0 = (int) Math.floor(x - hw + 1.0E-5), x1 = (int) Math.floor(x + hw - 1.0E-5);
        int y0 = (int) Math.floor(y + 1.0E-5), y1 = (int) Math.floor(y + HEIGHT - 1.0E-5);
        int z0 = (int) Math.floor(z - hw + 1.0E-5), z1 = (int) Math.floor(z + hw - 1.0E-5);
        BlockStorage b = r.world.blocks;
        int damage = 0;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int block = BlockData.block(b.getShared(bx, by, bz));
                    if (block == CACTUS) damage++;
                    else if ((block == FIRE || block == SOUL_FIRE) && !FIRE_RESISTANT[Stacks.item(stack)]) damage++;
                }
            }
        }
        return damage;
    }

    /**
     * {@code Entity.moveTowardsClosestSpace}: of the neighbours north, south, west, east and up whose collision
     * shape is not a full block, the one nearest to the point; the motion on that axis becomes
     * {@code step * (nextFloat() * 0.2F + 0.1F)}, the others are scaled by 0.75.
     */
    private static void moveTowardsClosestSpace(Region r, int s, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        double fx = x - bx, fy = y - by, fz = z - bz;
        int dir = RedstoneStates.UP;
        double best = Double.MAX_VALUE;
        final int[] order = MOVE_ORDER;
        for (int d : order) {
            int nx = bx + RedstoneStates.OX[d], ny = by + RedstoneStates.OY[d], nz = bz + RedstoneStates.OZ[d];
            if (Collision.fullBlock(r, nx, ny, nz)) continue;
            double v = d == RedstoneStates.WEST || d == RedstoneStates.EAST ? fx : d == RedstoneStates.UP ? fy : fz;
            boolean positive = d == RedstoneStates.EAST || d == RedstoneStates.UP || d == RedstoneStates.SOUTH;
            double dist = positive ? 1.0 - v : v;
            if (dist < best) {
                best = dist;
                dir = d;
            }
        }
        float f = r.nextFloat() * 0.2F + 0.1F;
        float step = dir == RedstoneStates.EAST || dir == RedstoneStates.UP || dir == RedstoneStates.SOUTH ? 1.0F : -1.0F;
        EntityTable t = r.table;
        double vx = t.vx(s) * 0.75, vy = t.vy(s) * 0.75, vz = t.vz(s) * 0.75;
        if (dir == RedstoneStates.WEST || dir == RedstoneStates.EAST) vx = step * f;
        else if (dir == RedstoneStates.UP) vy = step * f;
        else vz = step * f;
        t.setVel(s, vx, vy, vz);
    }

    private static final int[] MOVE_ORDER = {RedstoneStates.NORTH, RedstoneStates.SOUTH, RedstoneStates.WEST,
            RedstoneStates.EAST, RedstoneStates.UP};

    // ---- fluids -------------------------------------------------------------------------------------------------

    /**
     * {@code Entity.updateInWaterStateAndDoFluidPushing}: {@code updateFluidHeightAndDoFluidPushing(WATER, 0.014)},
     * then lava with 0.0023333333333333335 (overworld). Writes the water and lava heights to {@code out[0..1]}
     * (-1: not touching that fluid).
     */
    static void fluidPushing(Region r, int s, double[] out) {
        out[0] = fluidHeightAndPush(r, s, FluidStates.WATER_GROUP, 0.014);
        out[1] = fluidHeightAndPush(r, s, FluidStates.LAVA_GROUP, 0.0023333333333333335);
    }

    /**
     * {@code Entity.updateFluidHeightAndDoFluidPushing(tag, motionScale)} over the box deflated by 0.001: the
     * highest fluid surface above the box bottom, and the averaged, normalized flow added to the motion.
     * Returns the fluid height, or -1 if the entity touches no such fluid.
     */
    private static double fluidHeightAndPush(Region r, int s, int group, double motionScale) {
        EntityTable t = r.table;
        double hw = WIDTH / 2.0F;
        double minX = t.x(s) - hw + 0.001, minY = t.y(s) + 0.001, minZ = t.z(s) - hw + 0.001;
        double maxX = t.x(s) + hw - 0.001, maxY = t.y(s) + HEIGHT - 0.001, maxZ = t.z(s) + hw - 0.001;
        int x0 = (int) Math.floor(minX), x1 = (int) Math.ceil(maxX), y0 = (int) Math.floor(minY), y1 = (int) Math.ceil(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.ceil(maxZ);
        double d0 = 0.0, fx = 0.0, fy = 0.0, fz = 0.0;
        boolean touching = false;
        int k = 0;
        double[] flow = r.flowOut;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    int fs = FluidStates.fluid(r.world.blocks.getShared(x, y, z));
                    if (FluidStates.group(fs) != group) continue;
                    double d1 = (double) ((float) y + height(r, x, y, z, fs));
                    if (d1 < minY) continue;
                    touching = true;
                    d0 = Math.max(d1 - minY, d0);
                    flow(r, x, y, z, fs, flow);
                    double sx = flow[0], sy = flow[1], sz = flow[2];
                    if (d0 < 0.4) {
                        sx *= d0;
                        sy *= d0;
                        sz *= d0;
                    }
                    fx += sx;
                    fy += sy;
                    fz += sz;
                    k++;
                }
            }
        }
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 0.0) {
            if (k > 0) {
                fx /= k;
                fy /= k;
                fz /= k;
            }
            len = Math.sqrt(fx * fx + fy * fy + fz * fz); // not a player: normalize
            if (len < 1.0E-5F) {
                fx = fy = fz = 0.0;
            } else {
                fx /= len;
                fy /= len;
                fz /= len;
            }
            fx *= motionScale;
            fy *= motionScale;
            fz *= motionScale;
            double vx = t.vx(s), vz = t.vz(s);
            double l = Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (Math.abs(vx) < 0.003 && Math.abs(vz) < 0.003 && l < 0.0045000000000000005) {
                if (l < 1.0E-5F) {
                    fx = fy = fz = 0.0;
                } else {
                    fx = fx / l * 0.0045000000000000005;
                    fy = fy / l * 0.0045000000000000005;
                    fz = fz / l * 0.0045000000000000005;
                }
            }
            t.setVel(s, vx + fx, t.vy(s) + fy, vz + fz);
        }
        return touching ? d0 : -1.0;
    }

    /** {@code FluidState.getHeight}: 1 under the same fluid, else {@code amount / 9}. */
    private static float height(Region r, int x, int y, int z, int fs) {
        return FluidStates.group(FluidStates.fluid(r.world.blocks.getShared(x, y + 1, z))) == FluidStates.group(fs)
                ? 1.0F : FluidStates.ownHeight(fs);
    }

    private static boolean affectsFlow(int fs, int group) {
        return FluidStates.isEmpty(fs) || FluidStates.group(fs) == group;
    }

    /** {@code FlowingFluid.getFlow}: the normalized flow direction of the fluid at (x, y, z), into {@code out}. */
    static void flow(Region r, int x, int y, int z, int fs, double[] out) {
        BlockStorage b = r.world.blocks;
        int group = FluidStates.group(fs);
        float own = FluidStates.ownHeight(fs);
        double d0 = 0.0, d1 = 0.0;
        for (int d : RedstoneStates.HORIZONTAL) {
            int nx = x + RedstoneStates.OX[d], nz = z + RedstoneStates.OZ[d];
            int ns = b.getShared(nx, y, nz);
            int nfs = FluidStates.fluid(ns);
            if (!affectsFlow(nfs, group)) continue;
            float f = FluidStates.ownHeight(nfs);
            float f1 = 0.0F;
            if (f == 0.0F) {
                if (!BlockData.is(ns, BlockData.BLOCKS_MOTION)) {
                    int bfs = FluidStates.fluid(b.getShared(nx, y - 1, nz));
                    if (affectsFlow(bfs, group)) {
                        f = FluidStates.ownHeight(bfs);
                        if (f > 0.0F) f1 = own - (f - 0.8888889F);
                    }
                }
            } else if (f > 0.0F) {
                f1 = own - f;
            }
            if (f1 != 0.0F) {
                d0 += (double) ((float) RedstoneStates.OX[d] * f1);
                d1 += (double) ((float) RedstoneStates.OZ[d] * f1);
            }
        }
        double vx = d0, vy = 0.0, vz = d1;
        if (FluidStates.falling(fs)) {
            for (int d : RedstoneStates.HORIZONTAL) {
                int nx = x + RedstoneStates.OX[d], nz = z + RedstoneStates.OZ[d];
                if (solidFace(b, nx, y, nz, d, group) || solidFace(b, nx, y + 1, nz, d, group)) {
                    double l = Math.sqrt(vx * vx + vz * vz);
                    if (l < 1.0E-5F) { vx = 0.0; vz = 0.0; } else { vx /= l; vz /= l; }
                    vy = -6.0;
                    break;
                }
            }
        }
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (l < 1.0E-5F) {
            out[0] = out[1] = out[2] = 0.0;
        } else {
            out[0] = vx / l;
            out[1] = vy / l;
            out[2] = vz / l;
        }
    }

    /** {@code FlowingFluid.isSolidFace}. */
    private static boolean solidFace(BlockStorage b, int x, int y, int z, int side, int group) {
        int st = b.getShared(x, y, z);
        if (FluidStates.group(FluidStates.fluid(st)) == group) return false;
        int block = BlockData.block(st);
        if (block == BlockId.ICE || block == BlockId.FROSTED_ICE) return false;
        return BlockData.sturdy(st, side, BlockData.SUPPORT_FULL);
    }

    // ---- merging ------------------------------------------------------------------------------------------------

    /** {@code ItemEntity.isMergable}. */
    private static boolean mergable(EntityTable t, int s) {
        long stack = stack(t, s);
        int age = age(t, s);
        return Stacks.count(stack) > 0 && pickupDelay(t, s) != INFINITE_PICKUP_DELAY && age != INFINITE_LIFETIME
                && age < LIFETIME && Stacks.count(stack) < Stacks.maxStackSize(stack);
    }

    /**
     * {@code ItemEntity.mergeWithNeighbours}: every mergable item whose box meets this one's inflated by
     * (0.5, 0, 0.5). Returns true if this entity was removed.
     */
    private static boolean mergeWithNeighbours(Region r, int s) {
        EntityTable t = r.table;
        int self = (int) t.id(s);
        double x = t.x(s), y = t.y(s), z = t.z(s);
        int gx0 = (int) Math.floor((x - 0.75) / 2), gx1 = (int) Math.floor((x + 0.75) / 2);
        int gz0 = (int) Math.floor((z - 0.75) / 2), gz1 = (int) Math.floor((z + 0.75) / 2);
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                int c = cellHash(gx, gz);
                if (r.itemStamp[c] != r.itemGen) continue;
                for (int k = r.itemHead[c]; k >= 0; k = r.itemNext[k]) {
                    int other = r.itemEid[k];
                    if (other == self || r.itemCellX[k] != gx || r.itemCellZ[k] != gz || !r.ownsEntity(other)) continue;
                    int o = r.world.directory.slot(other);
                    if (t.type(o) != ITEM || !mergable(t, o)) continue;
                    double dx = t.x(o) - x, dy = t.y(o) - y, dz = t.z(o) - z;
                    if (!(Math.abs(dx) < 0.75 && Math.abs(dy) < HEIGHT && Math.abs(dz) < 0.75)) continue;
                    if (tryToMerge(r, s, o)) {
                        r.despawn(s);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code ItemEntity.tryToMerge}: the smaller stack goes into the larger. Returns true if {@code s} emptied. */
    private static boolean tryToMerge(Region r, int s, int o) {
        EntityTable t = r.table;
        long a = stack(t, s), b = stack(t, o);
        // areMergable(destination, origin): the counts fit the origin's max and the items and components match
        if (Stacks.count(a) + Stacks.count(b) > Stacks.maxStackSize(b) || !Stacks.sameItemSameComponents(a, b)) return false;
        if (Stacks.count(b) < Stacks.count(a)) {
            merge(t, s, a, o, b);
            return false;
        }
        merge(t, o, b, s, a);
        return Stacks.count(stack(t, s)) == 0;
    }

    /** {@code ItemEntity.merge(destination, destStack, origin, originStack)} with {@code ItemStack.merge(.., 64)}. */
    private static void merge(EntityTable t, int dst, long dstStack, int org, long orgStack) {
        int n = Math.min(Math.min(Stacks.maxStackSize(dstStack), 64) - Stacks.count(dstStack), Stacks.count(orgStack));
        setStack(t, dst, Stacks.withCount(dstStack, Stacks.count(dstStack) + n));
        int delay = Math.max(pickupDelay(t, dst), pickupDelay(t, org));
        int age = Math.min(age(t, dst), age(t, org));
        setAgeDelay(t, dst, age, delay);
        // an emptied origin is discarded: dead until it is next ticked (see the class notes)
        t.setAux1(org, (int) (Stacks.withCount(orgStack, Stacks.count(orgStack) - n) & 0xFFFFFF));
    }

    // ---- the item grid ------------------------------------------------------------------------------------------

    static int cellHash(int cx, int cz) {
        return (cx * 0x9E3779B1 + cz * 0x85EBCA77) >>> (32 - Region.GRID_BITS);
    }

    /** Index every live item of the region by 2×2-block column (entity ids, so it survives slot moves). */
    static void buildGrid(Region r) {
        EntityTable t = r.table;
        int gen = ++r.itemGen;
        if (gen == Integer.MAX_VALUE) {
            java.util.Arrays.fill(r.itemStamp, 0);
            gen = r.itemGen = 1;
        }
        int n = Math.min(t.count(), r.itemNext.length);
        for (int s = 0; s < n; s++) {
            if (t.type(s) != ITEM) continue;
            int cx = (int) Math.floor(t.x(s) / 2), cz = (int) Math.floor(t.z(s) / 2);
            int c = cellHash(cx, cz);
            if (r.itemStamp[c] != gen) {
                r.itemStamp[c] = gen;
                r.itemHead[c] = -1;
            }
            r.itemEid[s] = (int) t.id(s);
            r.itemCellX[s] = cx;
            r.itemCellZ[s] = cz;
            r.itemNext[s] = r.itemHead[c];
            r.itemHead[c] = s;
        }
    }

    // ---- pickup -------------------------------------------------------------------------------------------------

    /**
     * {@code Player.aiStep} → {@code touch} → {@code ItemEntity.playerTouch} for every player of the region (not
     * spectators): items in the player's box inflated by (1, 0.5, 1) with no pickup delay go into the inventory
     * ({@code Inventory.add}); the pickup is announced ({@code take}) with the count before it.
     */
    static void pickup(Region r) {
        EntityTable t = r.table;
        int players = 0;
        int[] list = r.playerScratch;
        for (int s = 0; s < t.count() && players < list.length; s++) {
            if (t.type(s) == PLAYER) list[players++] = (int) t.id(s);
        }
        if (players == 0) return;
        buildGrid(r);
        for (int p = 0; p < players; p++) {
            int pid = list[p];
            if (!r.ownsEntity(pid) || r.world.gameMode[pid] == World.SPECTATOR || !r.alive(pid)) continue;
            int ps = r.world.directory.slot(pid);
            double px = t.x(ps), py = t.y(ps), pz = t.z(ps);
            double minX = px - 0.3F - 1.0, maxX = px + 0.3F + 1.0, minY = py - 0.5;
            double maxY = py + playerHeight(r.world.presence[pid]) + 0.5, minZ = pz - 0.3F - 1.0, maxZ = pz + 0.3F + 1.0;
            double hw = WIDTH / 2.0F;
            int gx0 = (int) Math.floor((minX - hw) / 2), gx1 = (int) Math.floor((maxX + hw) / 2);
            int gz0 = (int) Math.floor((minZ - hw) / 2), gz1 = (int) Math.floor((maxZ + hw) / 2);
            for (int gx = gx0; gx <= gx1; gx++) {
                for (int gz = gz0; gz <= gz1; gz++) {
                    int c = cellHash(gx, gz);
                    if (r.itemStamp[c] != r.itemGen) continue;
                    for (int k = r.itemHead[c]; k >= 0; k = r.itemNext[k]) {
                        int item = r.itemEid[k];
                        if (r.itemCellX[k] != gx || r.itemCellZ[k] != gz || !r.ownsEntity(item)) continue;
                        int is = r.world.directory.slot(item);
                        if (t.type(is) != ITEM) continue;
                        double ix = t.x(is), iy = t.y(is), iz = t.z(is);
                        if (!(ix - hw < maxX && ix + hw > minX && iy < maxY && iy + HEIGHT > minY && iz - hw < maxZ
                                && iz + hw > minZ)) continue;
                        playerTouch(r, is, pid);
                    }
                }
            }
        }
    }

    /** {@code ItemEntity.playerTouch}. */
    private static void playerTouch(Region r, int s, int player) {
        EntityTable t = r.table;
        long stack = stack(t, s);
        int count = Stacks.count(stack);
        if (count == 0 || pickupDelay(t, s) != 0) return;
        long left = PlayerInv.add(r, player, stack);
        if (left == stack) return; // Inventory.add returned false
        r.emit((long) player << 32 | (t.id(s) & 0xFFFFFFFFL), (long) Journal.COLLECT << 56 | count);
        if (left == Stacks.EMPTY) {
            r.despawn(s);
        } else {
            setStack(t, s, left);
        }
    }
}
