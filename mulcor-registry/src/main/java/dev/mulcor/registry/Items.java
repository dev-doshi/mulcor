package dev.mulcor.registry;

import static dev.mulcor.registry.Registry.*;

/** Vanilla items: default max stack size, durability, and the block an item places (if it is a block item). */
public final class Items {
    private Items() {}

    public static String name(int item) { return ITEM_NAME[item]; }
    /** Default {@code minecraft:max_stack_size} (1, 16 or 64; components may override it per stack). */
    public static int maxStackSize(int item) { return ITEM_MAX_STACK[item]; }
    /** Default {@code minecraft:max_damage}, or 0 if the item has no durability. */
    public static int maxDamage(int item) { return ITEM_MAX_DAMAGE[item]; }
    /** The block id this item corresponds to, or -1. */
    public static int block(int item) { return ITEM_BLOCK[item]; }

    private static final java.util.Map<String, Integer> BY_NAME = new java.util.HashMap<>();

    static {
        for (int i = 0; i < ITEM_NAME.length; i++) BY_NAME.put(ITEM_NAME[i], i);
    }

    /** Cold: item id for a namespaced (or bare) name, or -1. */
    public static int byName(String name) {
        Integer i = BY_NAME.get(name.indexOf(':') < 0 ? "minecraft:" + name : name);
        return i == null ? -1 : i;
    }
}
