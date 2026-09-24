package dev.mulcor.core.region;

import static dev.mulcor.core.region.RedstoneStates.*;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.BlockId;
import dev.mulcor.registry.Shapes;

/**
 * Vanilla 26.2 block placement ({@code BlockItem.place} → {@code getPlacementState} → the block's
 * {@code getStateForPlacement(BlockPlaceContext)}, then {@code canSurvive}), for the families of blocks most builds
 * use. Each family below cites the vanilla class whose rule it ports:
 * <ul>
 *   <li>{@code RotatedPillarBlock}: axis of the clicked face. {@code SlabBlock}: bottom or top half by face and click
 *       height, double when clicking into a matching half. {@code StairBlock}: the player's facing and half.</li>
 *   <li>Horizontal facing: the player's direction reversed (furnaces, chests, diodes, glazed terracotta, ...); the
 *       player's direction for doors, fence gates, beds and campfires, and clockwise of it for anvils.</li>
 *   <li>Six-way facing: {@code getNearestLookingDirection} reversed (pistons, dispensers, droppers, barrels, command
 *       blocks), as is ({@code ObserverBlock}), or the clicked face (shulker boxes, rods, amethyst); hoppers point
 *       down unless placed against a side.</li>
 *   <li>{@code FaceAttachedHorizontalDirectionalBlock} (levers, buttons, grindstones) and
 *       {@code StandingAndWallBlockItem} (torches, signs, banners, heads, coral fans): the first of the player's
 *       nearest looking directions where the block survives.</li>
 *   <li>{@code TrapDoorBlock}, {@code DoorBlock} (hinge side from the neighbours and the click, upper half placed
 *       too), {@code BedBlock} (head placed too), {@code DoublePlantBlock} (upper half placed too).</li>
 *   <li>Waterlogging when placed into water; rotation (0-15) for standing signs, banners and heads.</li>
 * </ul>
 * Other blocks get their default state; the redstone survival rules ({@link Redstone#updateFromNeighbourShapes})
 * then reject what cannot stand there. Not ported: stair shapes and fence/wall/pane connections (their
 * {@code updateShape}), chests pairing, bells, crafters, candles and sea pickles stacking.
 */
final class Placement {
    // ---- vanilla Mth.sin / Mth.cos (lookup table) and Direction helpers ------------------------------------------

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
    }

    static float sin(float v) { return SIN[(int) (v * 10430.378F) & 65535]; }
    static float cos(float v) { return SIN[(int) (v * 10430.378F + 16384.0F) & 65535]; }

    /** {@code Direction.from2DDataValue}: 0 SOUTH, 1 WEST, 2 NORTH, 3 EAST. */
    private static final int[] FROM_2D = {SOUTH, WEST, NORTH, EAST};

    /** {@code Direction.fromYRot(yaw)}: the player's horizontal direction. */
    static int horizontalDirection(float yaw) {
        return FROM_2D[(int) Math.floor(yaw / 90.0 + 0.5) & 3];
    }

    /** {@code Direction.orderedByNearest(entity)}: the six directions by how directly the player looks along them. */
    static void orderedByNearest(float yaw, float pitch, int[] out) {
        float f = pitch * (float) (Math.PI / 180.0);
        float f1 = -yaw * (float) (Math.PI / 180.0);
        float f2 = sin(f), f3 = cos(f), f4 = sin(f1), f5 = cos(f1);
        boolean east = f4 > 0.0F, up = f2 < 0.0F, south = f5 > 0.0F;
        float f6 = east ? f4 : -f4, f7 = up ? -f2 : f2, f8 = south ? f5 : -f5;
        float f9 = f6 * f3, f10 = f8 * f3;
        int dx = east ? EAST : WEST, dy = up ? UP : DOWN, dz = south ? SOUTH : NORTH;
        if (f6 > f8) {
            if (f7 > f9) directions(dy, dx, dz, out);
            else if (f10 > f7) directions(dx, dz, dy, out);
            else directions(dx, dy, dz, out);
        } else if (f7 > f10) {
            directions(dy, dz, dx, out);
        } else if (f9 > f7) {
            directions(dz, dx, dy, out);
        } else {
            directions(dz, dy, dx, out);
        }
    }

    private static void directions(int a, int b, int c, int[] out) {
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = c ^ 1;
        out[4] = b ^ 1;
        out[5] = a ^ 1;
    }

    /**
     * {@code BlockPlaceContext.getNearestLookingDirections}: {@link #orderedByNearest}, with the direction into the
     * clicked face moved to the front unless the block replaces the clicked one.
     */
    static void nearestLookingDirections(float yaw, float pitch, int clickedFace, boolean replacingClicked, int[] out) {
        orderedByNearest(yaw, pitch, out);
        if (replacingClicked) return;
        int into = clickedFace ^ 1;
        int i = 0;
        while (i < 6 && out[i] != into) i++;
        if (i > 0 && i < 6) {
            System.arraycopy(out, 0, out, 1, i);
            out[0] = into;
        }
    }

    /** {@code RotationSegment.convertToSegment(rotation)}: 16 steps of 22.5°. */
    static int rotationSegment(float rotation) {
        return (int) Math.floor(rotation / 22.5F + 0.5) & 15;
    }

    // ---- block families ------------------------------------------------------------------------------------------

    static final int DEFAULT = 0, PILLAR = 1, SLAB = 2, STAIRS = 3, H_OPPOSITE = 4, H_SAME = 5, H_CLOCKWISE = 6,
            SIX_NEAREST_OPPOSITE = 7, SIX_NEAREST = 8, SIX_CLICKED = 9, HOPPER = 10, FACE_ATTACHED = 11,
            STANDING = 12, TRAPDOOR = 13, DOOR = 14, BED = 15, TALL_PLANT = 16, FENCE_GATE = 17, END_ROD = 18;

    private static final byte[] FAMILY;
    /** For {@link #STANDING} blocks: the wall variant (or -1) and the attachment direction (DOWN; UP for hanging). */
    private static final int[] WALL_BLOCK;
    /** Standing survival kinds: 0 always, 1 centre support below (torches), 2 solid below (signs, banners), 3 full. */
    private static final byte[] STANDING_SUPPORT;
    private static final boolean[] ROTATION_PLUS_180;
    private static final int P_NONE = BlockData.NO_PROPERTY;

    static {
        int blocks = BlockId.COUNT;
        FAMILY = new byte[blocks];
        WALL_BLOCK = new int[blocks];
        STANDING_SUPPORT = new byte[blocks];
        ROTATION_PLUS_180 = new boolean[blocks];
        java.util.Arrays.fill(WALL_BLOCK, -1);
        for (int b = 0; b < blocks; b++) {
            String n = BlockData.name(b);
            n = n.substring(n.indexOf(':') + 1);
            int facing = BlockData.property(b, "facing");
            int facingValues = facing == P_NONE ? 0 : BlockData.valueCount(facing);
            int axis = BlockData.property(b, "axis");
            int type = BlockData.property(b, "type");
            FAMILY[b] = (byte) family(b, n, facingValues, axis, type);
            if (FAMILY[b] == STANDING) {
                String wall = wallName(n);
                WALL_BLOCK[b] = wall == null ? -1 : BlockData.blockByName(wall);
                STANDING_SUPPORT[b] = (byte) (n.endsWith("torch") ? 1 : n.endsWith("_sign") || n.endsWith("_banner") ? 2
                        : n.endsWith("coral_fan") ? 3 : 0);
                ROTATION_PLUS_180[b] = n.endsWith("_sign") || n.endsWith("_banner");
            }
        }
    }

    private static int family(int b, String n, int facingValues, int axis, int type) {
        if (n.endsWith("_stairs")) return STAIRS;
        if (n.endsWith("_door")) return DOOR;
        if (n.endsWith("_trapdoor")) return TRAPDOOR;
        if (n.endsWith("_fence_gate")) return FENCE_GATE;
        if (n.endsWith("_bed")) return BED;
        if (n.equals("lever") || n.endsWith("_button") || n.equals("grindstone")) return FACE_ATTACHED;
        if (wallName(n) != null && BlockData.blockByName(wallName(n)) >= 0) return STANDING;
        if (n.endsWith("_head") || n.endsWith("_skull")) return STANDING; // heads without a wall variant lookup miss
        switch (n) {
            case "tall_grass", "large_fern", "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant" -> { return TALL_PLANT; }
            case "piston", "sticky_piston", "dispenser", "dropper", "barrel", "command_block", "chain_command_block",
                    "repeating_command_block" -> { return SIX_NEAREST_OPPOSITE; }
            case "observer" -> { return SIX_NEAREST; }
            case "hopper" -> { return HOPPER; }
            case "end_rod", "lightning_rod" -> { return END_ROD; }
            case "campfire", "soul_campfire" -> { return H_SAME; }
            case "anvil", "chipped_anvil", "damaged_anvil" -> { return H_CLOCKWISE; }
            default -> { }
        }
        if (axis != P_NONE && BlockData.valueCount(axis) == 3) return PILLAR;
        if (type != P_NONE && BlockData.valueIndex(type, "double") >= 0 && BlockData.valueIndex(type, "top") >= 0) return SLAB;
        if (facingValues == 6) return SIX_CLICKED;
        if (facingValues == 4) return H_OPPOSITE;
        return DEFAULT;
    }

    /** The wall variant's name of a standing block: torch → wall_torch, oak_sign → oak_wall_sign, ... */
    private static String wallName(String n) {
        if (n.endsWith("_hanging_sign")) return n.substring(0, n.length() - "hanging_sign".length()) + "wall_hanging_sign";
        if (n.equals("torch")) return "wall_torch";
        int cut = n.lastIndexOf('_');
        String last = cut < 0 ? n : n.substring(cut + 1);
        String head = cut < 0 ? "" : n.substring(0, cut + 1);
        return switch (last) {
            case "torch", "sign", "banner", "skull", "head", "fan" -> head + "wall_" + last;
            default -> null;
        };
    }

    static int family(int block) { return FAMILY[block]; }

    // ---- getStateForPlacement ------------------------------------------------------------------------------------

    private static int with(int state, String property, String value) {
        int b = BlockData.block(state);
        int p = BlockData.property(b, property);
        if (p == P_NONE) return state;
        int v = BlockData.valueIndex(p, value);
        return v < 0 ? state : BlockData.with(state, p, v);
    }

    private static String dirName(int d) {
        return switch (d) {
            case DOWN -> "down";
            case UP -> "up";
            case NORTH -> "north";
            case SOUTH -> "south";
            case WEST -> "west";
            default -> "east";
        };
    }

    private static String axisName(int d) {
        return (d >> 1) == 0 ? "y" : (d >> 1) == 1 ? "z" : "x";
    }

    /**
     * The state to place for {@code block} at (x, y, z), or -1 when vanilla would place nothing (no surviving
     * orientation). {@code clickX/Y/Z} are the click position relative to (x, y, z) ({@code getClickLocation - pos});
     * {@code target} is the state being replaced.
     */
    static int stateForPlacement(Region r, int block, int x, int y, int z, int clickedFace, double clickX, double clickY,
                                 double clickZ, float yaw, float pitch, boolean replacingClicked, int target, int[] dirs) {
        int st = BlockData.defaultState(block);
        int horizontal = horizontalDirection(yaw);
        switch (FAMILY[block]) {
            case PILLAR -> st = with(st, "axis", axisName(clickedFace));
            case SLAB -> {
                if (BlockData.block(target) == block) return with(target, "type", "double");
                boolean top = clickedFace == DOWN || clickedFace != UP && clickY > 0.5;
                st = with(st, "type", top ? "top" : "bottom");
            }
            case STAIRS -> {
                boolean top = clickedFace == DOWN || clickedFace != UP && clickY > 0.5;
                st = with(with(st, "facing", dirName(horizontal)), "half", top ? "top" : "bottom");
            }
            case H_OPPOSITE -> st = with(st, "facing", dirName(horizontal ^ 1));
            case H_SAME -> st = with(st, "facing", dirName(horizontal));
            case H_CLOCKWISE -> st = with(st, "facing", dirName(CW[horizontal]));
            case SIX_NEAREST_OPPOSITE, SIX_NEAREST -> {
                orderedByNearest(yaw, pitch, dirs);
                st = with(st, "facing", dirName(FAMILY[block] == SIX_NEAREST ? dirs[0] : dirs[0] ^ 1));
            }
            case SIX_CLICKED -> st = with(st, "facing", dirName(clickedFace));
            case END_ROD -> {
                // EndRodBlock / LightningRodBlock: against a rod facing the same way, point the other way
                int behind = Redstone.state(r, x - OX[clickedFace], y - OY[clickedFace], z - OZ[clickedFace]);
                boolean flip = BlockData.block(behind) == block && facingOf(behind) == clickedFace;
                st = with(st, "facing", dirName(flip ? clickedFace ^ 1 : clickedFace));
            }
            case HOPPER -> {
                int d = clickedFace ^ 1;
                st = with(st, "facing", dirName((d >> 1) == 0 ? DOWN : d));
            }
            case FACE_ATTACHED -> {
                nearestLookingDirections(yaw, pitch, clickedFace, replacingClicked, dirs);
                for (int i = 0; i < 6; i++) {
                    int d = dirs[i];
                    int s = (d >> 1) == 0
                            ? with(with(st, "face", d == UP ? "ceiling" : "floor"), "facing", dirName(horizontal))
                            : with(with(st, "face", "wall"), "facing", dirName(d ^ 1));
                    int conn = (d >> 1) == 0 ? (d == UP ? DOWN : UP) : d ^ 1; // getConnectedDirection
                    int ax = x - OX[conn], ay = y - OY[conn], az = z - OZ[conn];
                    if (BlockData.sturdy(Redstone.state(r, ax, ay, az), conn, BlockData.SUPPORT_FULL)) return waterlog(s, target);
                }
                return -1;
            }
            case STANDING -> {
                return standingOrWall(r, block, st, x, y, z, clickedFace, yaw, pitch, replacingClicked, target, dirs);
            }
            case TRAPDOOR -> {
                boolean horizontalFace = (clickedFace >> 1) != 0;
                if (!replacingClicked && horizontalFace) {
                    st = with(with(st, "facing", dirName(clickedFace)), "half", clickY > 0.5 ? "top" : "bottom");
                } else {
                    st = with(with(st, "facing", dirName(horizontal ^ 1)), "half", clickedFace == UP ? "bottom" : "top");
                }
                if (Redstone.hasNeighborSignal(r, x, y, z)) st = with(with(st, "open", "true"), "powered", "true");
            }
            case FENCE_GATE -> {
                st = with(st, "facing", dirName(horizontal));
                boolean powered = Redstone.hasNeighborSignal(r, x, y, z);
                st = with(with(st, "open", Boolean.toString(powered)), "powered", Boolean.toString(powered));
                // FenceGateBlock: lowered when a wall is on either side along its axis
                int a = CW[horizontal], b = CCW[horizontal];
                boolean inWall = isWall(Redstone.state(r, x + OX[a], y, z + OZ[a])) || isWall(Redstone.state(r, x + OX[b], y, z + OZ[b]));
                st = with(st, "in_wall", Boolean.toString(inWall));
            }
            case DOOR -> {
                if (y + 1 >= r.world.blocks.maxYExclusive() || !replaceable(Redstone.state(r, x, y + 1, z))) return -1;
                boolean powered = Redstone.hasNeighborSignal(r, x, y, z) || Redstone.hasNeighborSignal(r, x, y + 1, z);
                st = with(with(with(st, "facing", dirName(horizontal)), "hinge", doorHinge(r, x, y, z, horizontal, clickX, clickZ)),
                        "half", "lower");
                st = with(with(st, "powered", Boolean.toString(powered)), "open", Boolean.toString(powered));
                if (!BlockData.sturdy(Redstone.state(r, x, y - 1, z), UP, BlockData.SUPPORT_FULL)) return -1;
            }
            case BED -> {
                int hx = x + OX[horizontal], hz = z + OZ[horizontal];
                if (!replaceable(Redstone.state(r, hx, y, hz)) || r.world.ownerOfBlock(hx, hz) != r.id) return -1;
                st = with(with(st, "facing", dirName(horizontal)), "part", "foot");
            }
            case TALL_PLANT -> {
                if (y + 1 >= r.world.blocks.maxYExclusive() || !replaceable(Redstone.state(r, x, y + 1, z))) return -1;
                st = with(st, "half", "lower");
            }
            default -> { }
        }
        return waterlog(st, target);
    }

    private static int facingOf(int state) {
        int p = BlockData.property(BlockData.block(state), "facing");
        if (p == P_NONE) return -1;
        return switch (BlockData.valueName(p, BlockData.get(state, p))) {
            case "down" -> DOWN;
            case "up" -> UP;
            case "north" -> NORTH;
            case "south" -> SOUTH;
            case "west" -> WEST;
            default -> EAST;
        };
    }

    private static boolean isWall(int state) {
        return BlockData.name(BlockData.block(state)).endsWith("_wall");
    }

    /** {@code BlockBehaviour.canBeReplaced} for placement: air, and blocks flagged replaceable (grass, fluids, ...). */
    static boolean replaceable(int state) {
        return BlockData.isAir(state) || BlockData.is(state, BlockData.REPLACEABLE);
    }

    /** Waterlogged when placed into a water source ({@code fluidState.getType() == Fluids.WATER}). */
    private static int waterlog(int st, int target) {
        if (FluidStates.type(FluidStates.fluid(target)) != FluidStates.WATER) return st;
        return FluidStates.container(st) == FluidStates.WATERLOGGABLE ? FluidStates.waterlogged(st) : st;
    }

    /**
     * {@code StandingAndWallBlockItem.getPlacementState}: the first nearest looking direction (skipping the one away
     * from the attachment) where the standing block (the attachment direction) or the wall block (a horizontal one)
     * survives.
     */
    private static int standingOrWall(Region r, int block, int standing, int x, int y, int z, int clickedFace, float yaw,
                                      float pitch, boolean replacingClicked, int target, int[] dirs) {
        int attach = BlockData.name(block).endsWith("_hanging_sign") ? UP : DOWN;
        int wall = WALL_BLOCK[block];
        nearestLookingDirections(yaw, pitch, clickedFace, replacingClicked, dirs);
        for (int i = 0; i < 6; i++) {
            int d = dirs[i];
            if (d == (attach ^ 1)) continue;
            if (d == attach) {
                if (!standingSurvives(r, block, x, y, z)) continue;
                int s = standing;
                int rot = BlockData.property(block, "rotation");
                if (rot != P_NONE) s = BlockData.withInt(s, rot, rotationSegment(ROTATION_PLUS_180[block] ? yaw + 180.0F : yaw));
                return waterlog(s, target);
            }
            if (wall < 0 || (d >> 1) == 0) continue;
            // Wall*Block.getStateForPlacement: facing away from the support, the first horizontal nearest direction
            // (in the same order) that survives
            for (int j = 0; j < 6; j++) {
                int w = dirs[j];
                if ((w >> 1) == 0) continue;
                int facing = w ^ 1;
                int support = Redstone.state(r, x + OX[w], y, z + OZ[w]);
                boolean ok = STANDING_SUPPORT[block] == 2 ? BlockData.is(support, BlockData.SOLID)
                        : STANDING_SUPPORT[block] == 0 && !BlockData.name(block).endsWith("torch")
                        && !BlockData.name(block).endsWith("coral_fan") || BlockData.sturdy(support, facing, BlockData.SUPPORT_FULL);
                if (ok) return waterlog(with(BlockData.defaultState(wall), "facing", dirName(facing)), target);
            }
            return -1;
        }
        return -1;
    }

    private static boolean standingSurvives(Region r, int block, int x, int y, int z) {
        int below = Redstone.state(r, x, y - 1, z);
        return switch (STANDING_SUPPORT[block]) {
            case 1 -> BlockData.sturdy(below, UP, BlockData.SUPPORT_CENTER);
            case 2 -> BlockData.is(below, BlockData.SOLID);
            case 3 -> BlockData.sturdy(below, UP, BlockData.SUPPORT_FULL);
            default -> true;
        };
    }

    /** {@code DoorBlock.getHinge}: toward the side with more full blocks, else toward a door, else by the click. */
    private static String doorHinge(Region r, int x, int y, int z, int dir, double clickX, double clickZ) {
        int ccw = CCW[dir], cw = CW[dir];
        int s1 = Redstone.state(r, x + OX[ccw], y, z + OZ[ccw]), s2 = Redstone.state(r, x + OX[ccw], y + 1, z + OZ[ccw]);
        int s3 = Redstone.state(r, x + OX[cw], y, z + OZ[cw]), s4 = Redstone.state(r, x + OX[cw], y + 1, z + OZ[cw]);
        int i = (full(s1) ? -1 : 0) + (full(s2) ? -1 : 0) + (full(s3) ? 1 : 0) + (full(s4) ? 1 : 0);
        boolean b1 = lowerDoor(s1), b2 = lowerDoor(s3);
        if ((!b1 || b2) && i <= 0) {
            if ((!b2 || b1) && i >= 0) {
                int j = OX[dir], k = OZ[dir];
                return (j >= 0 || !(clickZ < 0.5)) && (j <= 0 || !(clickZ > 0.5)) && (k >= 0 || !(clickX > 0.5))
                        && (k <= 0 || !(clickX < 0.5)) ? "left" : "right";
            }
            return "left";
        }
        return "right";
    }

    private static boolean full(int state) {
        return Shapes.isFullBlock(BlockData.collisionShape(state));
    }

    private static boolean lowerDoor(int state) {
        if (FAMILY[BlockData.block(state)] != DOOR) return false;
        int p = BlockData.property(BlockData.block(state), "half");
        return BlockData.valueName(p, BlockData.get(state, p)).equals("lower");
    }

    /**
     * {@code setPlacedBy} of two-block families: the door's upper half, the bed's head, the tall plant's top, placed
     * right after the block itself (flags 3).
     */
    static void placeSecondHalf(Region r, int st, int x, int y, int z) {
        int b = BlockData.block(st);
        switch (FAMILY[b]) {
            case DOOR, TALL_PLANT -> {
                int upper = with(st, "half", "upper");
                Redstone.setBlock(r, x, y + 1, z, waterlog(upper, Redstone.state(r, x, y + 1, z)), Redstone.UPDATE_ALL);
            }
            case BED -> {
                int f = facingOf(st);
                int hx = x + OX[f], hz = z + OZ[f];
                if (r.world.ownerOfBlock(hx, hz) == r.id) Redstone.setBlock(r, hx, y, hz, with(st, "part", "head"), Redstone.UPDATE_ALL);
            }
            default -> { }
        }
    }
}
