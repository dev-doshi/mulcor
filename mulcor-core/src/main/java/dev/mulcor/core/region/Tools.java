package dev.mulcor.core.region;

import dev.mulcor.registry.ItemId;
import dev.mulcor.registry.Items;

/**
 * Tool items ({@code DataComponents.TOOL}, {@code ItemStack.hurtAndBreak}): what a tool loses per mined block and
 * how damage breaks it. Enchantments (unbreaking) are not modelled.
 */
final class Tools {
    /** Per item: damage per mined block (0: not a tool), bit 7 set if it cannot break blocks in creative. */
    private static final byte[] TOOL = new byte[ItemId.COUNT];
    private static final int NO_CREATIVE_BREAK = 0x80;

    static {
        for (int i = 1; i < TOOL.length; i++) {
            String n = Items.name(i);
            n = n.substring(n.indexOf(':') + 1);
            int t = 0;
            if (n.endsWith("_pickaxe") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_hoe") || n.equals("shears")) t = 1;
            if (n.endsWith("_sword")) t = 2 | NO_CREATIVE_BREAK;
            if (n.equals("mace")) t = 1 | NO_CREATIVE_BREAK;
            if (n.equals("trident") || n.equals("debug_stick") || n.endsWith("_spear")) t = NO_CREATIVE_BREAK;
            TOOL[i] = (byte) t;
        }
    }

    private Tools() {}

    static boolean isTool(long stack) {
        return stack != Stacks.EMPTY && (TOOL[Stacks.item(stack)] & 0x7F) != 0;
    }

    static int damagePerBlock(long stack) {
        return stack == Stacks.EMPTY ? 0 : TOOL[Stacks.item(stack)] & 0x7F;
    }

    /** {@code Item.canDestroyBlock} false for creative players (swords and the like). */
    static boolean noCreativeBreak(long stack) {
        return stack != Stacks.EMPTY && (TOOL[Stacks.item(stack)] & NO_CREATIVE_BREAK) != 0;
    }

    /**
     * {@code ItemStack.hurtAndBreak(amount, player, slot)}: the stack with {@code amount} more damage, or empty if
     * that breaks it. Creative players' items take no damage.
     */
    static long hurt(Region r, int eid, long stack, int amount) {
        if (!Stacks.damageable(stack) || amount <= 0 || r.world.gameMode[eid] == dev.mulcor.core.World.CREATIVE) return stack;
        int damage = Stacks.damage(stack) + amount;
        return damage >= Items.maxDamage(Stacks.item(stack)) ? Stacks.EMPTY : Stacks.withDamage(stack, damage);
    }
}
