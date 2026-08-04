package dev.jlo.kitsune.item;

import java.util.List;

import org.bukkit.inventory.ItemStack;

interface BukkitNestedContentsProvider {
    boolean supports(ItemStack stack);
    List<Child> children(ItemStack stack);

    record Child(String label, int slot, ItemStack stack) {}
}
