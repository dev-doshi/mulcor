package dev.mulcor.core.region;

import dev.mulcor.core.World;
import dev.mulcor.memory.EntityTable;
import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;

/**
 * Player survival: hunger ({@code FoodData}), fall damage ({@code Entity.checkFallDamage},
 * {@code Block.fallOn}), damage with its invulnerability window ({@code LivingEntity.hurtServer}), death
 * ({@code ServerPlayer.die}, {@code Inventory.dropAll}) and respawn ({@code PlayerList.respawn}). Exhaustion comes
 * from movement ({@code Player.checkMovementStatistics}), jumping ({@code Player.jumpFromGround}) and mining
 * ({@code Player.causeFoodExhaustion} in {@code Block.playerDestroy}). Difficulty is normal.
 *
 * <p>Not ported: void, lava, fire, drowning and suffocation damage, armour and enchantments, experience, status
 * effects.
 */
final class Survival {
    /** {@code Block.fallOn} damage multiplier per block id; slime is 0 unless the player holds shift. */
    private static final float[] FALL_MULT = new float[BlockId.COUNT];
    /** Blocks that cancel a fall: climbables ({@code onClimbable}) and {@code makeStuckInBlock} blocks. */
    private static final boolean[] RESETS_FALL = new boolean[BlockId.COUNT];

    static {
        java.util.Arrays.fill(FALL_MULT, 1.0F);
        FALL_MULT[BlockId.HAY_BLOCK] = 0.2F;
        FALL_MULT[BlockId.HONEY_BLOCK] = 0.2F;
        FALL_MULT[BlockId.SLIME_BLOCK] = 0.0F;
        for (int b = BlockId.WHITE_BED; b <= BlockId.BLACK_BED; b++) FALL_MULT[b] = 0.5F;
        for (int b : new int[] {BlockId.LADDER, BlockId.VINE, BlockId.SCAFFOLDING, BlockId.WEEPING_VINES,
                BlockId.WEEPING_VINES_PLANT, BlockId.TWISTING_VINES, BlockId.TWISTING_VINES_PLANT, BlockId.CAVE_VINES,
                BlockId.CAVE_VINES_PLANT, BlockId.COBWEB, BlockId.SWEET_BERRY_BUSH, BlockId.POWDER_SNOW}) {
            RESETS_FALL[b] = true;
        }
    }

    static final float MAX_HEALTH = 20.0F;

    private Survival() {}

    /** Per tick for every player of the region: the invulnerability countdown and {@code FoodData.tick}. */
    static void tick(Region r) {
        EntityTable t = r.table;
        World w = r.world;
        for (int s = 0; s < t.count(); s++) {
            if (t.type(s) != Entities.PLAYER) continue;
            int eid = (int) t.id(s);
            if (w.health[eid] <= 0) continue;
            if (w.invulnerableTime[eid] > 0) w.invulnerableTime[eid]--;
            foodTick(r, eid);
        }
    }

    private static void foodTick(Region r, int eid) {
        World w = r.world;
        int food = w.food[eid];
        float sat = w.saturation[eid];
        if (w.exhaustion[eid] > 4.0F) {
            w.exhaustion[eid] -= 4.0F;
            if (sat > 0.0F) w.saturation[eid] = Math.max(sat - 1.0F, 0.0F);
            else w.food[eid] = Math.max(food - 1, 0);
        }
        float health = w.health[eid];
        boolean hurt = health > 0.0F && health < MAX_HEALTH;
        if (w.saturation[eid] > 0.0F && hurt && w.food[eid] >= 20) {
            if (++w.foodTimer[eid] >= 10) {
                float f = Math.min(w.saturation[eid], 6.0F);
                heal(r, eid, f / 6.0F);
                addExhaustion(w, eid, f);
                w.foodTimer[eid] = 0;
            }
        } else if (w.food[eid] >= 18 && hurt) {
            if (++w.foodTimer[eid] >= 80) {
                heal(r, eid, 1.0F);
                addExhaustion(w, eid, 6.0F);
                w.foodTimer[eid] = 0;
            }
        } else if (w.food[eid] <= 0) {
            if (++w.foodTimer[eid] >= 80) {
                if (w.health[eid] > 1.0F) hurt(r, eid, 1.0F, World.CAUSE_STARVE); // starvation stops at half a heart on normal
                w.foodTimer[eid] = 0;
            }
        } else {
            w.foodTimer[eid] = 0;
        }
        if (w.food[eid] != food || w.saturation[eid] != sat) r.invChanged(eid);
    }

    private static void addExhaustion(World w, int eid, float f) {
        w.exhaustion[eid] = Math.min(w.exhaustion[eid] + f, 40.0F);
    }

    /** {@code Player.causeFoodExhaustion}: not in creative or spectator. */
    static void causeFoodExhaustion(Region r, int eid, float f) {
        int gm = r.world.gameMode[eid];
        if (gm == World.CREATIVE || gm == World.SPECTATOR || r.world.health[eid] <= 0) return;
        addExhaustion(r.world, eid, f);
    }

    /** {@code LivingEntity.heal}. */
    static void heal(Region r, int eid, float amount) {
        World w = r.world;
        if (w.health[eid] <= 0) return;
        w.health[eid] = Math.min(w.health[eid] + amount, MAX_HEALTH);
        r.invChanged(eid);
    }

    /**
     * {@code LivingEntity.hurtServer}: within the invulnerability window (the last 10 of its 20 ticks) only the part
     * above the previous hit applies. Returns whether damage was taken; {@code cause} names the death, if it kills.
     */
    static boolean hurt(Region r, int eid, float amount, int cause) {
        World w = r.world;
        int gm = w.gameMode[eid];
        if (amount <= 0 || w.health[eid] <= 0 || gm == World.CREATIVE || gm == World.SPECTATOR) return false;
        float applied;
        if (w.invulnerableTime[eid] > 10) {
            if (amount <= w.lastHurt[eid]) return false;
            applied = amount - w.lastHurt[eid];
            w.lastHurt[eid] = amount;
        } else {
            w.lastHurt[eid] = amount;
            w.invulnerableTime[eid] = 20;
            applied = amount;
        }
        w.health[eid] = Math.max(w.health[eid] - applied, 0.0F);
        r.invChanged(eid);
        if (w.health[eid] <= 0) {
            w.deathCause[eid] = cause;
            die(r, eid);
        }
        return true;
    }

    /**
     * A player's position update ({@code ServerGamePacketListenerImpl.handleMovePlayer}): jump and movement
     * exhaustion, then {@code doCheckFallDamage}. {@code ox, oy, oz} is the position before the move.
     */
    static void onMove(Region r, int slot, int eid, double ox, double oy, double oz, boolean wasOnGround, boolean onGround) {
        World w = r.world;
        if (w.health[eid] <= 0) return;
        EntityTable t = r.table;
        double x = t.x(slot), y = t.y(slot), z = t.z(slot);
        double dx = x - ox, dy = y - oy, dz = z - oz;
        int p = w.presence[eid];
        boolean sprinting = (p & Presence.SPRINTING) != 0;
        if (wasOnGround && !onGround && dy > 0) causeFoodExhaustion(r, eid, sprinting ? 0.2F : 0.05F);
        movementExhaustion(r, eid, p, x, y, z, dx, dy, dz, onGround, sprinting);
        checkFallDamage(r, eid, p, x, y, z, dy, onGround);
    }

    private static void movementExhaustion(Region r, int eid, int p, double x, double y, double z,
                                           double dx, double dy, double dz, boolean onGround, boolean sprinting) {
        double d3 = dx * dx + dy * dy + dz * dz, d2 = dx * dx + dz * dz;
        if ((p & Presence.SWIMMING) != 0 || Presence.eyeInWater(r, x, y + ItemEntities.eyeHeight(p), z)) {
            int i = Math.round((float) Math.sqrt(d3) * 100.0F);
            if (i > 0) causeFoodExhaustion(r, eid, 0.01F * i * 0.01F);
        } else if (Presence.inWater(r, x, y, z)) {
            int i = Math.round((float) Math.sqrt(d2) * 100.0F);
            if (i > 0) causeFoodExhaustion(r, eid, 0.01F * i * 0.01F);
        } else if (climbable(r, x, y, z)) {
            // climbing costs nothing
        } else if (onGround && sprinting) {
            int i = Math.round((float) Math.sqrt(d2) * 100.0F);
            if (i > 0) causeFoodExhaustion(r, eid, 0.1F * i * 0.01F);
        }
    }

    private static void checkFallDamage(Region r, int eid, int p, double x, double y, double z, double dy, boolean onGround) {
        World w = r.world;
        if (World.mayFly(w.gameMode[eid]) || (p & Presence.FLYING) != 0 || Presence.inWater(r, x, y, z)
                || RESETS_FALL[blockAt(r, x, y, z)] || RESETS_FALL[blockAt(r, x, y + 0.9, z)]) {
            w.fallDistance[eid] = 0;
            return;
        }
        if (!onGround) {
            if (dy < 0) w.fallDistance[eid] -= dy;
            return;
        }
        double fd = w.fallDistance[eid];
        w.fallDistance[eid] = 0;
        if (fd <= 0) return;
        int landed = blockAt(r, x, y - 0.2, z);
        float mult = landed == BlockId.SLIME_BLOCK && Presence.shift(p) ? 1.0F : FALL_MULT[landed];
        int damage = (int) Math.floor((fd + 1.0E-6 - 3.0) * mult); // LivingEntity.calculateFallDamage
        if (damage > 0) hurt(r, eid, damage, World.CAUSE_FALL);
    }

    private static boolean climbable(Region r, double x, double y, double z) {
        return RESETS_FALL[blockAt(r, x, y, z)];
    }

    /** The block id at a point, or air outside the world. */
    private static int blockAt(Region r, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        var b = r.world.blocks;
        if (by < b.minY() || by >= b.maxYExclusive() || bx < 0 || bz < 0 || bx >= r.world.sizeX() || bz >= r.world.sizeZ()) {
            return BlockId.AIR;
        }
        return BlockData.block(b.getShared(bx, by, bz));
    }

    /**
     * {@code ServerPlayer.die}: the open menu closes (carried and grid stacks go back to the inventory), then
     * {@code Inventory.dropAll} throws everything in random directions: main inventory (hotbar first), off hand, then
     * armour feet to head.
     */
    static void die(Region r, int eid) {
        World w = r.world;
        w.health[eid] = 0;
        Menus.close(r, eid);
        for (int i = 0; i < 36; i++) dropSlot(r, eid, i < 9 ? PlayerInv.HOTBAR + i : PlayerInv.MAIN + i - 9);
        dropSlot(r, eid, PlayerInv.OFFHAND);
        for (int s = PlayerInv.ARMOR_FEET; s >= PlayerInv.ARMOR_HEAD; s--) dropSlot(r, eid, s);
        long carried = PlayerInv.carried(r, eid);
        if (carried != Stacks.EMPTY) {
            PlayerInv.setCarried(r, eid, Stacks.EMPTY);
            ItemEntities.dropFromPlayer(r, eid, carried, true);
        }
        w.fallDistance[eid] = 0;
        w.deaths[eid]++;
        r.invChanged(eid);
    }

    private static void dropSlot(Region r, int eid, int slot) {
        long s = PlayerInv.get(r, eid, slot);
        if (s == Stacks.EMPTY) return;
        PlayerInv.set(r, eid, slot, Stacks.EMPTY);
        ItemEntities.dropFromPlayer(r, eid, s, true);
    }

    /** Fresh survival state for a joining or respawning player. */
    static void reset(World w, int eid) {
        w.health[eid] = MAX_HEALTH;
        w.food[eid] = 20;
        w.saturation[eid] = 5.0F;
        w.exhaustion[eid] = 0;
        w.foodTimer[eid] = 0;
        w.lastHurt[eid] = 0;
        w.invulnerableTime[eid] = 0;
        w.fallDistance[eid] = 0;
    }

    /** {@code PlayerList.respawn} of a dead player at a spawn point: full health and food, same entity id. */
    static void respawn(Region r, int slot, int eid, double x, double y, double z) {
        World w = r.world;
        if (w.health[eid] > 0 || r.table.type(slot) != Entities.PLAYER) return;
        reset(w, eid);
        int p = w.presence[eid];
        w.presence[eid] = Presence.withSettings(Presence.DEFAULT, Presence.skinParts(p), Presence.mainHand(p));
        w.heldSlot[eid] = 0;
        r.table.setPos(slot,
                Math.clamp(x, 0.5, w.sizeX() - 0.5),
                Math.clamp(y, w.blocks.minY() + 1.0, w.blocks.maxYExclusive() - 1.0),
                Math.clamp(z, 0.5, w.sizeZ() - 0.5));
        r.invChanged(eid);
    }
}
