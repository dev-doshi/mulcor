package dev.mulcor.core.region;

import dev.mulcor.registry.Items;

/**
 * Item stacks as primitive longs: item id in bits 0-15, count in bits 16-23, damage (the {@code DAMAGE} component)
 * in bits 24-39. {@link #EMPTY} is 0. Two stacks hold the same item with the same components
 * ({@code ItemStack.isSameItemSameComponents}) exactly when they are equal apart from the count bits.
 * Nothing else in a stack is modelled yet (no enchantments, names or custom data).
 */
public final class Stacks {
    public static final long EMPTY = 0;
    private static final long COUNT_MASK = 0xFFL << 16;

    private Stacks() {}

    public static long of(int item, int count) {
        return count <= 0 || item <= 0 ? EMPTY : (item & 0xFFFFL) | (long) (count & 0xFF) << 16;
    }

    public static long of(int item, int count, int damage) {
        return count <= 0 || item <= 0 ? EMPTY : (item & 0xFFFFL) | (long) (count & 0xFF) << 16 | (long) (damage & 0xFFFF) << 24;
    }

    public static int item(long s) { return (int) (s & 0xFFFF); }
    public static int count(long s) { return (int) (s >>> 16) & 0xFF; }
    public static int damage(long s) { return (int) (s >>> 24) & 0xFFFF; }
    public static boolean isEmpty(long s) { return s == EMPTY; }

    /** The stack with {@code count} items (empty at 0). */
    public static long withCount(long s, int count) {
        return count <= 0 ? EMPTY : (s & ~COUNT_MASK) | (long) (count & 0xFF) << 16;
    }

    public static long withDamage(long s, int damage) {
        return (s & ~(0xFFFFL << 24)) | (long) (damage & 0xFFFF) << 24;
    }

    /** {@code ItemStack.isSameItemSameComponents}. */
    public static boolean sameItemSameComponents(long a, long b) {
        return (a & ~COUNT_MASK) == (b & ~COUNT_MASK);
    }

    /** {@code ItemStack.isSameItem}. */
    public static boolean sameItem(long a, long b) {
        return item(a) == item(b);
    }

    /** {@code ItemStack.getMaxStackSize}. */
    public static int maxStackSize(long s) {
        return s == EMPTY ? 64 : Items.maxStackSize(item(s));
    }

    /** {@code ItemStack.isDamageableItem}. */
    public static boolean damageable(long s) {
        return s != EMPTY && Items.maxDamage(item(s)) > 0;
    }

    /** {@code ItemStack.isDamaged}. */
    public static boolean damaged(long s) {
        return damageable(s) && damage(s) > 0;
    }

    /** {@code ItemStack.isStackable}: max stack size above 1 and not a damaged tool. */
    public static boolean stackable(long s) {
        return maxStackSize(s) > 1 && !damaged(s);
    }
}
