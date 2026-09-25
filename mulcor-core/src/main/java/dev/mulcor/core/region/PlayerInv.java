package dev.mulcor.core.region;

import dev.mulcor.core.World;

/**
 * A network player's {@code Inventory} on {@code World.inv} ({@link Stacks}, indexed like {@code InventoryMenu}; see
 * {@link World#inv}). Only the region owning the player calls these; every write marks the player for the end-of-tick
 * publish ({@link Region#invChanged}).
 *
 * <p>Vanilla {@code Inventory} indices (0-8 hotbar, 9-35 main, 36-39 armor feet to head, 40 offhand) map to menu
 * slots with {@link #slotOf}.
 */
final class PlayerInv {
    static final int RESULT = 0, CRAFT = 1, ARMOR_HEAD = 5, ARMOR_FEET = 8, MAIN = 9, HOTBAR = 36, OFFHAND = 45;
    static final int TABLE_GRID = 46, TABLE_RESULT = 55;
    /** Equipment slots ({@code EquipmentSlot}) an item can be worn in: none (main hand), or the armor slots. */
    static final int EQUIP_MAINHAND = 0, EQUIP_FEET = 1, EQUIP_LEGS = 2, EQUIP_CHEST = 3, EQUIP_HEAD = 4;
    private static final byte[] EQUIP = new byte[dev.mulcor.registry.ItemId.COUNT];

    static {
        for (int i = 1; i < EQUIP.length; i++) {
            String n = dev.mulcor.registry.Items.name(i);
            n = n.substring(n.indexOf(':') + 1);
            // The EQUIPPABLE component's slot for the player armor slots.
            int e = EQUIP_MAINHAND;
            if (n.endsWith("_helmet") || n.equals("carved_pumpkin") || n.endsWith("_head") || n.endsWith("_skull")) e = EQUIP_HEAD;
            else if (n.endsWith("_chestplate") || n.equals("elytra")) e = EQUIP_CHEST;
            else if (n.endsWith("_leggings")) e = EQUIP_LEGS;
            else if (n.endsWith("_boots")) e = EQUIP_FEET;
            if (n.equals("piston_head")) e = EQUIP_MAINHAND;
            EQUIP[i] = (byte) e;
        }
    }

    private PlayerInv() {}

    /** Menu slot of vanilla {@code Inventory} index {@code i}. */
    static int slotOf(int i) {
        if (i < 9) return HOTBAR + i;
        if (i < 36) return i;
        if (i < 40) return ARMOR_FEET - (i - 36);
        return OFFHAND;
    }

    /** {@code getEquipmentSlotForItem}: an {@code EQUIP_*} value. */
    static int equipSlot(long stack) {
        return stack == Stacks.EMPTY ? EQUIP_MAINHAND : EQUIP[Stacks.item(stack)];
    }

    /** The armor menu slot for an equipment slot (head 5 ... feet 8). */
    static int armorSlot(int equip) {
        return ARMOR_FEET - (equip - EQUIP_FEET);
    }

    static long get(Region r, int eid, int slot) {
        return r.world.inv[eid * World.INV + slot];
    }

    static void set(Region r, int eid, int slot, long stack) {
        r.world.inv[eid * World.INV + slot] = stack;
        r.invChanged(eid);
    }

    static long carried(Region r, int eid) {
        return r.world.carried[eid];
    }

    static void setCarried(Region r, int eid, long stack) {
        r.world.carried[eid] = stack;
        r.invChanged(eid);
    }

    static int selectedSlot(Region r, int eid) {
        return HOTBAR + r.world.heldSlot[eid];
    }

    static long selected(Region r, int eid) {
        return get(r, eid, selectedSlot(r, eid));
    }

    /** {@code Player.hasInfiniteMaterials}: creative. */
    static boolean infinite(Region r, int eid) {
        return r.world.gameMode[eid] == World.CREATIVE;
    }

    /** {@code Inventory.getMaxStackSize(stack)}: {@code min(99, stack max)}. */
    private static int maxSize(long stack) {
        return Math.min(99, Stacks.maxStackSize(stack));
    }

    private static boolean hasRemainingSpaceForItem(long dest, long stack) {
        return dest != Stacks.EMPTY && Stacks.sameItemSameComponents(dest, stack) && Stacks.stackable(dest)
                && Stacks.count(dest) < maxSize(dest);
    }

    /** {@code Inventory.getSlotWithRemainingSpace}: selected slot, offhand, then 0-35 (as menu slots; -1 if none). */
    static int slotWithRemainingSpace(Region r, int eid, long stack) {
        int sel = selectedSlot(r, eid);
        if (hasRemainingSpaceForItem(get(r, eid, sel), stack)) return sel;
        if (hasRemainingSpaceForItem(get(r, eid, OFFHAND), stack)) return OFFHAND;
        for (int i = 0; i < 36; i++) {
            int s = slotOf(i);
            if (hasRemainingSpaceForItem(get(r, eid, s), stack)) return s;
        }
        return -1;
    }

    /** {@code Inventory.getFreeSlot}: the first empty slot of 0-35 (as a menu slot; -1 if none). */
    static int freeSlot(Region r, int eid) {
        for (int i = 0; i < 36; i++) {
            int s = slotOf(i);
            if (get(r, eid, s) == Stacks.EMPTY) return s;
        }
        return -1;
    }

    /** {@code Inventory.addResource(slot, stack)}: returns the count left over. */
    private static int addResource(Region r, int eid, int slot, long stack) {
        int n = Stacks.count(stack);
        long dest = get(r, eid, slot);
        if (dest == Stacks.EMPTY) dest = Stacks.withCount(stack, 0);
        int base = dest == Stacks.EMPTY ? 0 : Stacks.count(dest);
        int space = maxSize(stack) - base;
        int k = Math.min(n, space);
        if (k <= 0) return n;
        set(r, eid, slot, Stacks.withCount(stack, base + k));
        return n - k;
    }

    /**
     * {@code Inventory.add(-1, stack)}. Returns what is left of {@code stack} (the stack itself if nothing was
     * added); vanilla's result {@code true} is "the count went down". Creative players take everything.
     */
    static long add(Region r, int eid, long stack) {
        if (stack == Stacks.EMPTY) return stack;
        if (Stacks.damaged(stack)) {
            int slot = freeSlot(r, eid);
            if (slot >= 0) {
                set(r, eid, slot, stack);
                return Stacks.EMPTY;
            }
            return infinite(r, eid) ? Stacks.EMPTY : stack;
        }
        int original = Stacks.count(stack);
        int count;
        do {
            count = Stacks.count(stack);
            int slot = slotWithRemainingSpace(r, eid, stack);
            if (slot < 0) slot = freeSlot(r, eid);
            stack = Stacks.withCount(stack, slot < 0 ? count : addResource(r, eid, slot, stack));
        } while (stack != Stacks.EMPTY && Stacks.count(stack) < count);
        if (stack != Stacks.EMPTY && Stacks.count(stack) == original && infinite(r, eid)) return Stacks.EMPTY;
        return stack;
    }

    /** {@code Inventory.add(slot, stack)} for one menu slot: returns what is left. */
    static long addTo(Region r, int eid, int slot, long stack) {
        if (stack == Stacks.EMPTY) return stack;
        if (Stacks.damaged(stack)) {
            if (get(r, eid, slot) != Stacks.EMPTY) return infinite(r, eid) ? Stacks.EMPTY : stack;
            set(r, eid, slot, stack);
            return Stacks.EMPTY;
        }
        int original = Stacks.count(stack);
        int count;
        do {
            count = Stacks.count(stack);
            stack = Stacks.withCount(stack, addResource(r, eid, slot, stack));
        } while (stack != Stacks.EMPTY && Stacks.count(stack) < count);
        if (stack != Stacks.EMPTY && Stacks.count(stack) == original && infinite(r, eid)) return Stacks.EMPTY;
        return stack;
    }

    /**
     * {@code Inventory.placeItemBackInInventory}: into stacks with room (selected slot first), then free slots; what
     * does not fit is dropped at the player.
     */
    static void placeItemBackInInventory(Region r, int eid, long stack) {
        while (stack != Stacks.EMPTY) {
            int slot = slotWithRemainingSpace(r, eid, stack);
            if (slot < 0) slot = freeSlot(r, eid);
            if (slot < 0) {
                ItemEntities.dropFromPlayer(r, eid, stack, false);
                return;
            }
            long dest = get(r, eid, slot);
            int room = Stacks.maxStackSize(stack) - (dest == Stacks.EMPTY ? 0 : Stacks.count(dest));
            int n = Math.min(room, Stacks.count(stack));
            long part = Stacks.withCount(stack, n);
            long left = addTo(r, eid, slot, part);
            stack = Stacks.withCount(stack, Stacks.count(stack) - n + (left == Stacks.EMPTY ? 0 : Stacks.count(left)));
            if (left != Stacks.EMPTY && Stacks.count(left) == n) { // no progress (cannot happen with a free slot)
                ItemEntities.dropFromPlayer(r, eid, stack, false);
                return;
            }
        }
    }

    /** {@code Inventory.removeFromSelected(all)}: take one item (or the whole stack) from the selected slot. */
    static long removeFromSelected(Region r, int eid, boolean all) {
        int slot = selectedSlot(r, eid);
        long s = get(r, eid, slot);
        if (s == Stacks.EMPTY) return Stacks.EMPTY;
        int n = all ? Stacks.count(s) : 1;
        set(r, eid, slot, Stacks.withCount(s, Stacks.count(s) - n));
        return Stacks.withCount(s, n);
    }

    /** Clear the player's inventory, cursor and menus (a joining player; returns the item count removed). */
    static int clear(Region r, int eid) {
        int n = 0;
        int base = eid * World.INV;
        for (int i = 0; i < World.INV; i++) {
            n += Stacks.count(r.world.inv[base + i]);
            r.world.inv[base + i] = Stacks.EMPTY;
        }
        n += Stacks.count(r.world.carried[eid]);
        r.world.carried[eid] = Stacks.EMPTY;
        r.invChanged(eid);
        return n;
    }
}
