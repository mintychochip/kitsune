package dev.jlo.kitsune.item;

import java.util.List;

import org.bukkit.inventory.ItemStack;

/**
 * Supplies the nested item contents held inside a Bukkit item stack.
 */
interface BukkitNestedContentsProvider {
    /**
     * Returns whether this provider can expand the given stack.
     *
     * @param stack stack to inspect
     * @return {@code true} when children are available for the stack
     */
    boolean supports(ItemStack stack);

    /**
     * Returns the nested children of a supported stack, in slot order.
     *
     * @param stack stack to expand
     * @return non-null, non-empty list of contained child stacks
     */
    List<Child> children(ItemStack stack);

    /**
     * A single nested item referenced by the stack that contained it.
     *
     * @param label namespaced key of the containing stack type
     * @param slot position within the containing inventory
     * @param stack the nested item stack
     */
    record Child(String label, int slot, ItemStack stack) {}
}
