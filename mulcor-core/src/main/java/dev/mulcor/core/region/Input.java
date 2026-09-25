package dev.mulcor.core.region;

/**
 * Layout of a client input record: 32 bytes, produced by the network decoder or by headless virtual clients
 * and consumed from a region's ingress ring.
 */
public final class Input {
    public static final long KIND = 0;
    public static final long ENTITY = 4;
    public static final long X = 8, Y = 12, Z = 16;
    public static final long A = 20, B = 24, C = 28;
    public static final int BYTES = 32;

    /** A,B = walk direction x,z (any scale; 0,0 = stop). The mob faces it and walks at its movement speed. */
    public static final int MOVE = 1;
    /**
     * X,Y,Z = block to break. A = 0: break a mineable block now (headless clients); else the
     * {@code ServerboundPlayerActionPacket} status + 1 (1 start, 2 abort, 3 stop destroying), judged by the game mode.
     */
    public static final int DIG = 2;
    /**
     * X,Y,Z = position; A = block/item from the player's inventory, or {@link #HELD_ITEM} for the item in the
     * player's hand (a network player, creative); B = the clicked face + 1 (a Direction ordinal: the clicked block is
     * X,Y,Z minus that direction), or 0 for a bare placement; C = the click position inside the clicked block,
     * thousandths packed x | y << 10 | z << 20. A clicked block that reacts to being used (lever, button, comparator,
     * repeater) is used instead of placing.
     */
    public static final int PLACE = 3;
    /** PLACE with A = HELD_ITEM: place the item in the player's selected hotbar slot. */
    public static final int HELD_ITEM = -1;
    /** A = chest; B = slot; C = count (> 0 take into player, < 0 put from player). */
    public static final int CHEST = 4;
    /** X,Y,Z = spawn a primed TNT; A = fuse ticks. */
    public static final int IGNITE = 5;
    /** Region-addressed (ENTITY ignored). X,Y,Z = position; A = block state; sets the block and updates neighbours. */
    public static final int SET_BLOCK = 6;
    /** Region-addressed. A = target region: send it a PROBE message this epoch. */
    public static final int PROBE_EMIT = 7;
    /**
     * Client movement packet. X,Y,Z = absolute position in thousandths of a block; A = flags
     * ({@link #NO_POSITION}, {@link #HAS_ROTATION}, {@link #ON_GROUND}); B,C = yaw, pitch as float bits.
     */
    public static final int POSITION = 8;
    /**
     * Region-addressed (ENTITY ignored). Spawn a network player at X,Y,Z (thousandths of a block) and report its
     * entity id through join ticket A (see {@code JoinTickets}).
     */
    public static final int JOIN = 9;
    /** The player's connection closed: remove its entity. */
    public static final int LEAVE = 10;
    /**
     * Region-addressed (ENTITY ignored). X,Y,Z = block a player uses with an empty hand: pulls a lever, presses a
     * button, cycles a repeater's delay or a comparator's mode.
     */
    public static final int USE_BLOCK = 11;
    /**
     * Creative inventory action: A = inventory menu slot (1-45; -1 drops), B = item id, C = count | damage << 16
     * (count 0 clears the slot).
     */
    public static final int CREATIVE_SLOT = 12;
    /** Selected hotbar slot: A = 0-8. */
    public static final int HELD_SLOT = 13;
    /** Arm swing ({@code ServerboundSwingPacket}): A = hand (0 main, 1 off). */
    public static final int SWING = 14;
    /** Movement keys ({@code ServerboundPlayerInputPacket}): A = flags (forward 1, backward 2, left 4, right 8, jump 16, shift 32, sprint 64). */
    public static final int PLAYER_INPUT = 15;
    /** {@code ServerboundPlayerCommandPacket}: A = action ordinal (1 start sprinting, 2 stop sprinting, ...). */
    public static final int PLAYER_COMMAND = 16;
    /** Client settings: A = displayed skin parts, B = main hand (0 left, 1 right). */
    public static final int SETTINGS = 17;
    /** {@code ServerboundPlayerAbilitiesPacket}: A = flags (2 = flying). */
    public static final int ABILITIES = 18;
    /** Game mode change ({@code /gamemode}): A = vanilla {@code GameType} id (0 survival, 1 creative, 2 adventure, 3 spectator). */
    public static final int GAME_MODE = 19;
    /**
     * Container click ({@code ServerboundContainerClickPacket}): X = container id, A = slot, B = button,
     * C = click type ({@link Menus}).
     */
    public static final int CLICK = 20;
    /** Container closed by the client: X = container id. */
    public static final int CLOSE_WINDOW = 21;
    /** Drop from the selected slot (player action 3/4): A = 1 for the whole stack. */
    public static final int DROP = 22;
    /** Swap the selected item with the offhand (player action 6). */
    public static final int SWAP_HANDS = 23;
    /** Respawn a dead player: X,Y,Z = spawn position × 1000. */
    public static final int RESPAWN = 24;

    /** POSITION flag: the record carries only rotation/ground state; X,Y,Z are ignored. */
    public static final int NO_POSITION = 1;
    /** POSITION flag: B,C hold yaw and pitch. */
    public static final int HAS_ROTATION = 2;
    /** POSITION flag: the client reports being on the ground. */
    public static final int ON_GROUND = 4;

    private Input() {}
}
