package dev.jlo.kitsune.item;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

final class BundleContentsProvider implements BukkitNestedContentsProvider {
    @Override
    public boolean supports(ItemStack stack) {
        return !BukkitItemDescriber.isEmpty(stack) && stack.getItemMeta() instanceof BundleMeta;
    }

    @Override
    public List<Child> children(ItemStack stack) {
        if (!supports(stack)) return List.of();
        BundleMeta meta = (BundleMeta) stack.getItemMeta();
        List<ItemStack> items = meta.getItems();
        if (items == null) return List.of();
        String label = stack.getType().getKey().toString();
        List<Child> children = new ArrayList<>();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack item = items.get(slot);
            if (!BukkitItemDescriber.isEmpty(item)) children.add(new Child(label, slot, item.clone()));
        }
        return List.copyOf(children);
    }
}
