package dev.mulcor.core.region;

import dev.mulcor.core.World;

/**
 * Container menus of a network player, ported from {@code AbstractContainerMenu}, {@code InventoryMenu} and
 * {@code CraftingMenu}: clicks ({@code doClick}, every {@code ClickType}), shift-click ({@code quickMoveStack}),
 * dragging ({@code QUICK_CRAFT}), closing ({@code removed}) and {@code stillValid}.
 *
 * <p>Menu slots map onto {@code World.inv} ({@link #invSlot}). {@code World.window[eid]} holds the open menu:
 * container id | kind << 8 (0: the player's own inventory, container id 0).
 */
final class Menus {
    static final int INVENTORY = 0, CRAFTING = 1;
    /** Slots per menu (both kinds have 46). */
    static final int SLOTS = 46;
    static final int SLOT_OUTSIDE = -999;
    /** {@code ClickType} ordinals. */
    static final int PICKUP = 0, QUICK_MOVE = 1, SWAP = 2, CLONE = 3, THROW = 4, QUICK_CRAFT = 5, PICKUP_ALL = 6;
    private static final int RESULT = 1, GRID = 2, ARMOR = 3, NORMAL = 4;

    private Menus() {}

    static int kind(int window) { return window >>> 8 & 0xFF; }
    static int containerId(int window) { return window & 0xFF; }

    /** The {@code World.inv} index of menu slot {@code slot} of a menu of {@code kind}. */
    static int invSlot(int kind, int slot) {
        if (kind == CRAFTING) {
            if (slot == 0) return PlayerInv.TABLE_RESULT;
            if (slot <= 9) return PlayerInv.TABLE_GRID + slot - 1;
            return slot - 1;
        }
        return slot;
    }

    private static int type(int kind, int slot) {
        if (slot == 0) return RESULT;
        if (kind == CRAFTING) return slot <= 9 ? GRID : NORMAL;
        if (slot <= 4) return GRID;
        return slot <= 8 ? ARMOR : NORMAL;
    }

    private static long get(Region r, int eid, int kind, int slot) {
        return PlayerInv.get(r, eid, invSlot(kind, slot));
    }

    private static void set(Region r, int eid, int kind, int slot, long stack) {
        PlayerInv.set(r, eid, invSlot(kind, slot), stack);
    }

    /** {@code Slot.mayPlace}: never into a result slot; armor slots take only what is worn there. */
    private static boolean mayPlace(int kind, int slot, long stack) {
        int t = type(kind, slot);
        if (t == RESULT) return false;
        if (t == ARMOR) return PlayerInv.equipSlot(stack) != PlayerInv.EQUIP_MAINHAND
                && PlayerInv.armorSlot(PlayerInv.equipSlot(stack)) == slot;
        return true;
    }

    /** {@code Slot.getMaxStackSize(stack)}. */
    private static int maxStackSize(int kind, int slot, long stack) {
        return type(kind, slot) == ARMOR ? 1 : Math.min(99, Stacks.maxStackSize(stack));
    }

    // ---- entry points -------------------------------------------------------------------------------------------

    /**
     * {@code ServerGamePacketListenerImpl.handleContainerClick}: ignored unless {@code containerId} is the open menu
     * and the slot index is valid; spectators click nothing.
     */
    static void click(Region r, int eid, int containerId, int slot, int button, int clickType) {
        World w = r.world;
        w.clicks[eid]++;
        r.invChanged(eid); // publish the click count even if nothing changes (the session resends the touched slots)
        int window = w.window[eid];
        if (containerId != containerId(window) || w.gameMode[eid] == World.SPECTATOR) return;
        if (!(slot == -1 || slot == SLOT_OUTSIDE || slot >= 0 && slot < SLOTS)) return;
        if (clickType < PICKUP || clickType > PICKUP_ALL) return;
        doClick(r, eid, kind(window), slot, button, clickType);
        Crafting.slotsChanged(r, eid, kind(window));
    }

    /**
     * {@code ServerPlayer.doCloseContainer} → {@code containerMenu.removed}: the carried stack goes back to the
     * inventory, the crafting grid is emptied into it ({@code clearContainer}), and the inventory menu is open again.
     */
    static void close(Region r, int eid) {
        World w = r.world;
        int kind = kind(w.window[eid]);
        long carried = PlayerInv.carried(r, eid);
        if (carried != Stacks.EMPTY) {
            PlayerInv.setCarried(r, eid, Stacks.EMPTY);
            PlayerInv.placeItemBackInInventory(r, eid, carried);
        }
        int grid = kind == CRAFTING ? PlayerInv.TABLE_GRID : PlayerInv.CRAFT;
        int n = kind == CRAFTING ? 9 : 4;
        PlayerInv.set(r, eid, kind == CRAFTING ? PlayerInv.TABLE_RESULT : PlayerInv.RESULT, Stacks.EMPTY);
        for (int i = 0; i < n; i++) {
            long s = PlayerInv.get(r, eid, grid + i);
            if (s == Stacks.EMPTY) continue;
            PlayerInv.set(r, eid, grid + i, Stacks.EMPTY);
            PlayerInv.placeItemBackInInventory(r, eid, s);
        }
        w.quickcraft[eid] = 0;
        w.window[eid] = 0;
        r.invChanged(eid);
    }

    /** {@code CraftingTableBlock.useWithoutItem} → {@code openMenu}: a new container id ({@code % 100 + 1}). */
    static void openCraftingTable(Region r, int eid, int x, int y, int z) {
        World w = r.world;
        if (w.window[eid] != 0) close(r, eid);
        int id = w.containerCounter[eid] % 100 + 1;
        w.containerCounter[eid] = id;
        w.window[eid] = id | CRAFTING << 8;
        w.menuPos[eid] = dev.mulcor.memory.ScheduledTicks.pack(x, y, z);
        r.invChanged(eid);
    }

    /**
     * {@code ServerPlayer.tick}: {@code if (!containerMenu.stillValid(this)) closeContainer()}. A crafting table
     * menu stays valid while the table is there and the player is within 4 blocks of interaction range
     * ({@code Player.canInteractWithBlock(pos, 4.0)}: eye-to-block-box distance below 4.5 + 4).
     */
    static void tickPlayers(Region r) {
        dev.mulcor.memory.EntityTable t = r.table;
        for (int s = 0; s < t.count(); s++) {
            if (t.type(s) == Entities.PLAYER) tickPlayer(r, (int) t.id(s), s);
        }
    }

    static void tickPlayer(Region r, int eid, int slot) {
        World w = r.world;
        if (kind(w.window[eid]) != CRAFTING) return;
        long p = w.menuPos[eid];
        int x = dev.mulcor.memory.ScheduledTicks.x(p), y = dev.mulcor.memory.ScheduledTicks.y(p);
        int z = dev.mulcor.memory.ScheduledTicks.z(p);
        boolean valid = dev.mulcor.registry.BlockData.block(w.blocks.getShared(x, y, z)) == dev.mulcor.registry.BlockId.CRAFTING_TABLE;
        if (valid) {
            double ex = r.table.x(slot), ey = r.table.y(slot) + ItemEntities.eyeHeight(w.presence[eid]), ez = r.table.z(slot);
            double dx = Math.max(Math.max(x - ex, ex - (x + 1)), 0), dy = Math.max(Math.max(y - ey, ey - (y + 1)), 0);
            double dz = Math.max(Math.max(z - ez, ez - (z + 1)), 0);
            double range = 4.5 + 4.0; // block interaction range attribute + the menu's buffer
            valid = dx * dx + dy * dy + dz * dz < range * range;
        }
        if (!valid) close(r, eid);
    }

    // ---- AbstractContainerMenu.doClick ---------------------------------------------------------------------------

    private static void doClick(Region r, int eid, int kind, int slot, int button, int clickType) {
        World w = r.world;
        if (clickType == QUICK_CRAFT) {
            quickCraft(r, eid, kind, slot, button);
            return;
        }
        if ((w.quickcraft[eid] & 3) != 0) {
            resetQuickCraft(w, eid);
            return;
        }
        long carried = PlayerInv.carried(r, eid);
        if ((clickType == PICKUP || clickType == QUICK_MOVE) && (button == 0 || button == 1)) {
            boolean primary = button == 0;
            if (slot == SLOT_OUTSIDE) {
                if (carried != Stacks.EMPTY) {
                    if (primary) {
                        PlayerInv.setCarried(r, eid, Stacks.EMPTY);
                        ItemEntities.dropFromPlayer(r, eid, carried, false);
                    } else {
                        PlayerInv.setCarried(r, eid, Stacks.withCount(carried, Stacks.count(carried) - 1));
                        ItemEntities.dropFromPlayer(r, eid, Stacks.withCount(carried, 1), false);
                    }
                }
            } else if (clickType == QUICK_MOVE) {
                if (slot < 0) return;
                long moved = quickMoveStack(r, eid, kind, slot);
                while (moved != Stacks.EMPTY && Stacks.sameItem(get(r, eid, kind, slot), moved)) {
                    moved = quickMoveStack(r, eid, kind, slot);
                }
            } else {
                if (slot < 0) return;
                pickup(r, eid, kind, slot, primary, carried);
            }
        } else if (clickType == SWAP && (button >= 0 && button < 9 || button == 40)) {
            swap(r, eid, kind, slot, button);
        } else if (clickType == CLONE && PlayerInv.infinite(r, eid) && carried == Stacks.EMPTY && slot >= 0) {
            long s = get(r, eid, kind, slot);
            if (s != Stacks.EMPTY) PlayerInv.setCarried(r, eid, Stacks.withCount(s, Stacks.maxStackSize(s)));
        } else if (clickType == THROW && carried == Stacks.EMPTY && slot >= 0) {
            int n = button == 0 ? 1 : Stacks.count(get(r, eid, kind, slot));
            long thrown = safeTake(r, eid, kind, slot, n, Integer.MAX_VALUE);
            ItemEntities.dropFromPlayer(r, eid, thrown, false);
            if (button == 1) {
                while (thrown != Stacks.EMPTY && Stacks.sameItem(get(r, eid, kind, slot), thrown)) {
                    thrown = safeTake(r, eid, kind, slot, n, Integer.MAX_VALUE);
                    ItemEntities.dropFromPlayer(r, eid, thrown, false);
                }
            }
        } else if (clickType == PICKUP_ALL && slot >= 0) {
            pickupAll(r, eid, kind, slot, button, carried);
        }
    }

    /** PICKUP on a slot ({@code doClick}'s PICKUP branch). */
    private static void pickup(Region r, int eid, int kind, int slot, boolean primary, long carried) {
        long item = get(r, eid, kind, slot);
        if (item == Stacks.EMPTY) {
            if (carried != Stacks.EMPTY) {
                int n = primary ? Stacks.count(carried) : 1;
                PlayerInv.setCarried(r, eid, safeInsert(r, eid, kind, slot, carried, n));
            }
            return;
        }
        // mayPickup is always true here (no curse of binding)
        if (carried == Stacks.EMPTY) {
            int n = primary ? Stacks.count(item) : (Stacks.count(item) + 1) / 2;
            long taken = tryRemove(r, eid, kind, slot, n, Integer.MAX_VALUE);
            if (taken != Stacks.EMPTY) {
                PlayerInv.setCarried(r, eid, taken);
                onTake(r, eid, kind, slot, taken);
            }
        } else if (mayPlace(kind, slot, carried)) {
            if (Stacks.sameItemSameComponents(item, carried)) {
                int n = primary ? Stacks.count(carried) : 1;
                PlayerInv.setCarried(r, eid, safeInsert(r, eid, kind, slot, carried, n));
            } else if (Stacks.count(carried) <= maxStackSize(kind, slot, carried)) {
                PlayerInv.setCarried(r, eid, item);
                set(r, eid, kind, slot, carried);
            }
        } else if (Stacks.sameItemSameComponents(item, carried)) {
            long taken = tryRemove(r, eid, kind, slot, Stacks.count(item),
                    Stacks.maxStackSize(carried) - Stacks.count(carried));
            if (taken != Stacks.EMPTY) {
                PlayerInv.setCarried(r, eid, Stacks.withCount(carried, Stacks.count(carried) + Stacks.count(taken)));
                onTake(r, eid, kind, slot, taken);
            }
        }
    }

    /** SWAP with hotbar slot {@code button} (0-8) or the offhand (40). */
    private static void swap(Region r, int eid, int kind, int slot, int button) {
        if (slot < 0) return;
        int invSlot = PlayerInv.slotOf(button);
        long hotbar = PlayerInv.get(r, eid, invSlot);
        long item = get(r, eid, kind, slot);
        if (hotbar == Stacks.EMPTY && item == Stacks.EMPTY) return;
        if (hotbar == Stacks.EMPTY) {
            PlayerInv.set(r, eid, invSlot, item);
            set(r, eid, kind, slot, Stacks.EMPTY);
            onTake(r, eid, kind, slot, item);
        } else if (item == Stacks.EMPTY) {
            if (mayPlace(kind, slot, hotbar)) {
                int max = maxStackSize(kind, slot, hotbar);
                if (Stacks.count(hotbar) > max) {
                    set(r, eid, kind, slot, Stacks.withCount(hotbar, max));
                    PlayerInv.set(r, eid, invSlot, Stacks.withCount(hotbar, Stacks.count(hotbar) - max));
                } else {
                    PlayerInv.set(r, eid, invSlot, Stacks.EMPTY);
                    set(r, eid, kind, slot, hotbar);
                }
            }
        } else if (mayPlace(kind, slot, hotbar)) {
            int max = maxStackSize(kind, slot, hotbar);
            if (Stacks.count(hotbar) > max) {
                set(r, eid, kind, slot, Stacks.withCount(hotbar, max));
                PlayerInv.set(r, eid, invSlot, Stacks.withCount(hotbar, Stacks.count(hotbar) - max));
                onTake(r, eid, kind, slot, item);
                long left = PlayerInv.add(r, eid, item);
                if (left != Stacks.EMPTY && left == item) ItemEntities.dropFromPlayer(r, eid, item, false);
                else if (left != Stacks.EMPTY) ItemEntities.dropFromPlayer(r, eid, left, false);
            } else {
                PlayerInv.set(r, eid, invSlot, item);
                set(r, eid, kind, slot, hotbar);
                onTake(r, eid, kind, slot, item);
            }
        }
    }

    /** PICKUP_ALL (double click): gather matching stacks onto the cursor, partial stacks first. */
    private static void pickupAll(Region r, int eid, int kind, int slot, int button, long carried) {
        long clicked = get(r, eid, kind, slot);
        if (carried == Stacks.EMPTY || clicked != Stacks.EMPTY) return; // !slot.hasItem() || !slot.mayPickup(player)
        int start = button == 0 ? 0 : SLOTS - 1, step = button == 0 ? 1 : -1;
        int max = Stacks.maxStackSize(carried);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = start; k >= 0 && k < SLOTS && Stacks.count(carried) < max; k += step) {
                long s = get(r, eid, kind, k);
                if (s == Stacks.EMPTY || !canItemQuickReplace(s, carried, true) || type(kind, k) == RESULT) continue;
                if (pass == 0 && Stacks.count(s) == Stacks.maxStackSize(s)) continue;
                long taken = safeTake(r, eid, kind, k, Stacks.count(s), max - Stacks.count(carried));
                carried = Stacks.withCount(carried, Stacks.count(carried) + Stacks.count(taken));
            }
        }
        PlayerInv.setCarried(r, eid, carried);
    }

    // ---- quick craft (dragging) ----------------------------------------------------------------------------------

    private static void resetQuickCraft(World w, int eid) {
        w.quickcraft[eid] = 0;
        w.quickcraftSlots[eid] = 0;
    }

    /**
     * {@code doClick} QUICK_CRAFT: status 0 starts a drag of type {@code button >> 2 & 3} (0 split evenly, 1 one each,
     * 2 a full stack each, creative only), status 1 adds a slot, status 2 ends it and places the items.
     */
    private static void quickCraft(Region r, int eid, int kind, int slot, int button) {
        World w = r.world;
        int state = w.quickcraft[eid];
        int prev = state & 3, type = state >>> 2 & 3;
        int status = button & 3;
        w.quickcraft[eid] = (state & ~3) | status;
        long carried = PlayerInv.carried(r, eid);
        if ((prev != 1 || status != 2) && prev != status) {
            resetQuickCraft(w, eid);
        } else if (carried == Stacks.EMPTY) {
            resetQuickCraft(w, eid);
        } else if (status == 0) {
            type = button >>> 2 & 3;
            if (type == 0 || type == 1 || type == 2 && PlayerInv.infinite(r, eid)) {
                w.quickcraft[eid] = 1 | type << 2;
                w.quickcraftSlots[eid] = 0;
            } else {
                resetQuickCraft(w, eid);
            }
        } else if (status == 1) {
            if (slot < 0 || slot >= SLOTS) return;
            long s = get(r, eid, kind, slot);
            int n = Long.bitCount(w.quickcraftSlots[eid]);
            if (canItemQuickReplace(s, carried, true) && mayPlace(kind, slot, carried)
                    && (type == 2 || Stacks.count(carried) > n) && type(kind, slot) != RESULT) {
                w.quickcraftSlots[eid] |= 1L << slot;
            }
            w.quickcraft[eid] = 1 | type << 2;
        } else if (status == 2) {
            long slots = w.quickcraftSlots[eid];
            if (slots != 0) {
                if (Long.bitCount(slots) == 1) {
                    int k = Long.numberOfTrailingZeros(slots);
                    resetQuickCraft(w, eid);
                    doClick(r, eid, kind, k, type, PICKUP);
                    return;
                }
                long stack = carried;
                int left = Stacks.count(carried);
                int n = Long.bitCount(slots);
                for (long m = slots; m != 0; m &= m - 1) {
                    int k = Long.numberOfTrailingZeros(m);
                    long cur = PlayerInv.carried(r, eid); // unchanged during the loop: the copy's count is what matters
                    long s = get(r, eid, kind, k);
                    if (canItemQuickReplace(s, cur, true) && mayPlace(kind, k, cur)
                            && (type == 2 || Stacks.count(cur) >= n) && type(kind, k) != RESULT) {
                        int j = s == Stacks.EMPTY ? 0 : Stacks.count(s);
                        int max = Math.min(Stacks.maxStackSize(stack), maxStackSize(kind, k, stack));
                        int place = switch (type) {
                            case 0 -> Stacks.count(stack) / n;
                            case 1 -> 1;
                            default -> Stacks.maxStackSize(stack);
                        };
                        int l1 = Math.min(place + j, max);
                        left -= l1 - j;
                        set(r, eid, kind, k, Stacks.withCount(stack, l1));
                    }
                }
                PlayerInv.setCarried(r, eid, Stacks.withCount(stack, left));
            }
            resetQuickCraft(w, eid);
        } else {
            resetQuickCraft(w, eid);
        }
    }

    /** {@code AbstractContainerMenu.canItemQuickReplace}. */
    private static boolean canItemQuickReplace(long slotItem, long stack, boolean stackSizeMatters) {
        if (slotItem == Stacks.EMPTY) return true;
        if (!Stacks.sameItemSameComponents(stack, slotItem)) return false;
        return Stacks.count(slotItem) + (stackSizeMatters ? 0 : Stacks.count(stack)) <= Stacks.maxStackSize(stack);
    }

    // ---- Slot ---------------------------------------------------------------------------------------------------

    /** {@code Slot.safeInsert(stack, increment)}: returns what is left of {@code stack}. */
    private static long safeInsert(Region r, int eid, int kind, int slot, long stack, int increment) {
        if (stack == Stacks.EMPTY || !mayPlace(kind, slot, stack)) return stack;
        long item = get(r, eid, kind, slot);
        int have = item == Stacks.EMPTY ? 0 : Stacks.count(item);
        int n = Math.min(Math.min(increment, Stacks.count(stack)), maxStackSize(kind, slot, stack) - have);
        if (n <= 0) return stack;
        if (item == Stacks.EMPTY) {
            set(r, eid, kind, slot, Stacks.withCount(stack, n));
        } else if (Stacks.sameItemSameComponents(item, stack)) {
            set(r, eid, kind, slot, Stacks.withCount(item, have + n));
        } else {
            return stack;
        }
        return Stacks.withCount(stack, Stacks.count(stack) - n);
    }

    /**
     * {@code Slot.tryRemove(count, decrement)}: a result slot only gives its whole stack, and only if it all fits
     * ({@code allowModification} is false there).
     */
    private static long tryRemove(Region r, int eid, int kind, int slot, int count, int decrement) {
        long item = get(r, eid, kind, slot);
        if (item == Stacks.EMPTY) return Stacks.EMPTY;
        if (type(kind, slot) == RESULT) {
            if (decrement < Stacks.count(item)) return Stacks.EMPTY;
            set(r, eid, kind, slot, Stacks.EMPTY); // ResultContainer.removeItem takes the whole stack
            return item;
        }
        int n = Math.min(Math.min(count, decrement), Stacks.count(item));
        if (n <= 0) return Stacks.EMPTY;
        set(r, eid, kind, slot, Stacks.withCount(item, Stacks.count(item) - n));
        return Stacks.withCount(item, n);
    }

    /** {@code Slot.safeTake}: {@link #tryRemove} then {@code onTake}. */
    private static long safeTake(Region r, int eid, int kind, int slot, int count, int decrement) {
        long taken = tryRemove(r, eid, kind, slot, count, decrement);
        if (taken != Stacks.EMPTY) onTake(r, eid, kind, slot, taken);
        return taken;
    }

    /** {@code Slot.onTake}: taking from a result slot crafts (consumes the grid). */
    private static void onTake(Region r, int eid, int kind, int slot, long taken) {
        if (type(kind, slot) == RESULT) Crafting.onTake(r, eid, kind);
    }

    // ---- quickMoveStack -----------------------------------------------------------------------------------------

    /**
     * {@code InventoryMenu.quickMoveStack} / {@code CraftingMenu.quickMoveStack}. Returns the stack as it was before
     * the move, or empty when nothing moved (which ends shift-click's repeat loop).
     */
    private static long quickMoveStack(Region r, int eid, int kind, int index) {
        long original = get(r, eid, kind, index);
        if (original == Stacks.EMPTY) return Stacks.EMPTY;
        long[] moving = r.moving;
        moving[0] = original;
        boolean ok;
        if (kind == CRAFTING) {
            if (index == 0) {
                ok = moveItemStackTo(r, eid, kind, moving, 10, 46, true);
            } else if (index >= 10 && index < 46) {
                ok = moveItemStackTo(r, eid, kind, moving, 1, 10, false)
                        || (index < 37 ? moveItemStackTo(r, eid, kind, moving, 37, 46, false)
                                : moveItemStackTo(r, eid, kind, moving, 10, 37, false));
            } else {
                ok = moveItemStackTo(r, eid, kind, moving, 10, 46, false);
            }
        } else {
            int equip = PlayerInv.equipSlot(original);
            if (index == 0) {
                ok = moveItemStackTo(r, eid, kind, moving, 9, 45, true);
            } else if (index >= 1 && index < 5) {
                ok = moveItemStackTo(r, eid, kind, moving, 9, 45, false);
            } else if (index >= 5 && index < 9) {
                ok = moveItemStackTo(r, eid, kind, moving, 9, 45, false);
            } else if (equip != PlayerInv.EQUIP_MAINHAND && get(r, eid, kind, PlayerInv.armorSlot(equip)) == Stacks.EMPTY) {
                int a = PlayerInv.armorSlot(equip);
                ok = moveItemStackTo(r, eid, kind, moving, a, a + 1, false);
            } else if (index >= 9 && index < 36) {
                ok = moveItemStackTo(r, eid, kind, moving, 36, 45, false);
            } else if (index >= 36 && index < 45) {
                ok = moveItemStackTo(r, eid, kind, moving, 9, 36, false);
            } else {
                ok = moveItemStackTo(r, eid, kind, moving, 9, 45, false);
            }
        }
        if (!ok) return Stacks.EMPTY;
        long rest = moving[0];
        // the slot holds what did not move (a result slot: onQuickCraft, then the rest is dropped below)
        set(r, eid, kind, index, rest);
        if (rest != Stacks.EMPTY && Stacks.count(rest) == Stacks.count(original)) return Stacks.EMPTY;
        onTake(r, eid, kind, index, rest);
        if (index == 0) {
            if (rest != Stacks.EMPTY) {
                set(r, eid, kind, index, Stacks.EMPTY);
                ItemEntities.dropFromPlayer(r, eid, rest, false);
            }
            Crafting.slotsChanged(r, eid, kind);
        }
        return original;
    }

    /** {@code AbstractContainerMenu.moveItemStackTo}: merge into matching stacks, then the first empty slot. */
    private static boolean moveItemStackTo(Region r, int eid, int kind, long[] moving, int start, int end, boolean reverse) {
        long stack = moving[0];
        boolean moved = false;
        if (Stacks.stackable(stack)) {
            for (int i = reverse ? end - 1 : start; stack != Stacks.EMPTY && (reverse ? i >= start : i < end); i += reverse ? -1 : 1) {
                long item = get(r, eid, kind, i);
                if (item == Stacks.EMPTY || !Stacks.sameItemSameComponents(stack, item)) continue;
                int j = Stacks.count(item) + Stacks.count(stack);
                int k = maxStackSize(kind, i, item);
                if (j <= k) {
                    stack = Stacks.EMPTY;
                    set(r, eid, kind, i, Stacks.withCount(item, j));
                    moved = true;
                } else if (Stacks.count(item) < k) {
                    stack = Stacks.withCount(stack, Stacks.count(stack) - (k - Stacks.count(item)));
                    set(r, eid, kind, i, Stacks.withCount(item, k));
                    moved = true;
                }
            }
        }
        if (stack != Stacks.EMPTY) {
            for (int i = reverse ? end - 1 : start; reverse ? i >= start : i < end; i += reverse ? -1 : 1) {
                if (get(r, eid, kind, i) != Stacks.EMPTY || !mayPlace(kind, i, stack)) continue;
                int n = Math.min(Stacks.count(stack), maxStackSize(kind, i, stack));
                set(r, eid, kind, i, Stacks.withCount(stack, n));
                stack = Stacks.withCount(stack, Stacks.count(stack) - n);
                moved = true;
                break;
            }
        }
        moving[0] = stack;
        return moved;
    }
}
