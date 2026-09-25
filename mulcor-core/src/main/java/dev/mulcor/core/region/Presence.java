package dev.mulcor.core.region;

import dev.mulcor.registry.BlockData;

/**
 * What other players see of a network player besides its position: vanilla's synched entity data for an
 * {@code Avatar} (shared flags, pose, main hand, displayed skin parts), the arm swings it made and what it holds.
 *
 * <h2>Packed presence ({@code World.presence[entity]})</h2>
 * bits 0-7 the shared flags byte ({@code Entity.DATA_SHARED_FLAGS_ID}: {@link #CROUCHING} 0x02, {@link #SPRINTING}
 * 0x08, {@link #SWIMMING} 0x10, ...), bits 8-15 the displayed skin parts ({@code DATA_PLAYER_MODE_CUSTOMISATION}),
 * bit 16 the main hand (1 = right, vanilla's default), bits 17-21 the {@code Pose} ordinal, then the inputs the
 * derived values come from: bit 22 shift key down, bit 23 flying ({@code Abilities.flying}), bit 24 sprinting
 * requested (START/STOP_SPRINTING).
 *
 * <p>Only the region owning the player writes it (at input time and when the player moves); the region's network
 * snapshot carries it to sessions. {@code World.swings[entity]} counts main-hand swings in the low 16 bits and
 * off-hand swings in the high 16 bits: sessions send one animation per change.
 */
public final class Presence {
    /** Shared flag bits (vanilla {@code FLAG_*} as masks). */
    public static final int ON_FIRE = 0x01, CROUCHING = 0x02, SPRINTING = 0x08, SWIMMING = 0x10, INVISIBLE = 0x20,
            GLOWING = 0x40, FALL_FLYING = 0x80;
    /** Pose ordinals ({@code net.minecraft.world.entity.Pose}). */
    public static final int STANDING = 0, POSE_FALL_FLYING = 1, SLEEPING = 2, POSE_SWIMMING = 3, SPIN_ATTACK = 4,
            POSE_CROUCHING = 5;

    public static final int RIGHT_HAND = 1 << 16;
    static final int SHIFT_DOWN = 1 << 22, FLYING = 1 << 23, WANTS_SPRINT = 1 << 24;
    /** A freshly joined player: nothing set, right-handed, standing. */
    public static final int DEFAULT = RIGHT_HAND;

    /** {@code ServerboundPlayerCommandPacket.Action} ordinals. */
    static final int START_SPRINTING = 1, STOP_SPRINTING = 2;

    private Presence() {}

    public static int sharedFlags(int p) { return p & 0xFF; }
    public static int skinParts(int p) { return (p >>> 8) & 0xFF; }
    /** Vanilla {@code HumanoidArm} id: 0 left, 1 right. */
    public static int mainHand(int p) { return (p >>> 16) & 1; }
    public static int pose(int p) { return (p >>> 17) & 31; }
    /** {@code isShiftKeyDown} (= {@code isSecondaryUseActive}). */
    static boolean shift(int p) { return (p & SHIFT_DOWN) != 0; }

    /** The client's settings: skin parts shown and main hand (0 left, 1 right). */
    static int withSettings(int p, int skinParts, int mainHand) {
        return p & ~(0xFF << 8 | RIGHT_HAND) | (skinParts & 0xFF) << 8 | (mainHand & 1) << 16;
    }

    /** {@code ServerboundPlayerInputPacket}: {@code setShiftKeyDown(input.shift())}. */
    static int withShift(int p, boolean shift) {
        p = shift ? p | SHIFT_DOWN : p & ~SHIFT_DOWN;
        return shift ? p | CROUCHING : p & ~CROUCHING;
    }

    /** {@code ServerboundPlayerAbilitiesPacket}: flying, when the game mode allows it (creative). */
    static int withFlying(int p, boolean flying) {
        return flying ? p | FLYING : p & ~FLYING;
    }

    /** {@code ServerboundPlayerCommandPacket} START/STOP_SPRINTING: {@code setSprinting}. */
    static int withSprint(int p, boolean sprint) {
        p = sprint ? p | WANTS_SPRINT : p & ~WANTS_SPRINT;
        return sprint ? p | SPRINTING : p & ~SPRINTING;
    }

    /**
     * {@code Player.updateSwimming} then {@code Player.updatePlayerPose} for a player at (x, y, z): swimming starts
     * when sprinting with the eyes under water in a water block and lasts while sprinting in water; the pose is
     * SWIMMING, else CROUCHING when the shift key is down and not flying, else STANDING.
     */
    static int update(int p, Region r, double x, double y, double z) {
        boolean sprinting = (p & SPRINTING) != 0;
        boolean swimming;
        if ((p & SWIMMING) != 0) {
            swimming = sprinting && inWater(r, x, y, z);
        } else {
            swimming = sprinting && eyeInWater(r, x, y + (((p & SHIFT_DOWN) != 0) ? 1.27 : 1.62), z)
                    && isWater(r, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
        p = swimming ? p | SWIMMING : p & ~SWIMMING;
        int pose = swimming ? POSE_SWIMMING : (p & SHIFT_DOWN) != 0 && (p & FLYING) == 0 ? POSE_CROUCHING : STANDING;
        return p & ~(31 << 17) | pose << 17;
    }

    private static boolean isWater(Region r, int x, int y, int z) {
        int fs = fluidAt(r, x, y, z);
        return fs != 0 && FluidStates.group(fs) == FluidStates.WATER_GROUP;
    }

    /** {@code isInWater}: the player's box (0.6 wide, 1.8 high) touches water, sampled at its feet and middle. */
    private static boolean inWater(Region r, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return isWater(r, bx, (int) Math.floor(y), bz) || isWater(r, bx, (int) Math.floor(y + 0.9), bz);
    }

    /** {@code isEyeInFluid(WATER)}: the eye is below the water surface of its block ({@code FluidState.getHeight}). */
    private static boolean eyeInWater(Region r, double x, double eyeY, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(eyeY), bz = (int) Math.floor(z);
        int fs = fluidAt(r, bx, by, bz);
        if (fs == 0 || FluidStates.group(fs) != FluidStates.WATER_GROUP) return false;
        int above = fluidAt(r, bx, by + 1, bz);
        double height = above != 0 && FluidStates.group(above) == FluidStates.WATER_GROUP ? 1.0 : FluidStates.amount(fs) / 9.0;
        return eyeY < by + height;
    }

    private static int fluidAt(Region r, int x, int y, int z) {
        var b = r.world.blocks;
        if (y < b.minY() || y >= b.maxYExclusive() || x < 0 || z < 0 || x >= r.world.sizeX() || z >= r.world.sizeZ()) return 0;
        int state = b.getShared(x, y, z);
        return BlockData.isAir(state) ? 0 : FluidStates.fluid(state);
    }
}
