package dev.jlo.kitsune.item;

import java.util.*;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import dev.jlo.kitsune.api.item.NestedContentsProvider;

final class BundleContentsProvider implements NestedContentsProvider {

    @Override
    public boolean supports(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItemMeta() instanceof BundleMeta;
    }

    @Override
    public List<NestedContentsProvider.ChildItem> children(ItemStack stack) {
        if (!supports(stack)) return Collections.emptyList();
        BundleMeta meta = (BundleMeta) stack.getItemMeta();
        List<ItemStack> items = meta.getItems();
        if (items == null) return Collections.emptyList();
        String label = stack.getType().getKey().toString();
        List<NestedContentsProvider.ChildItem> children = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item != null && !item.isEmpty()) {
                children.add(new NestedContentsProvider.ChildItem(label, i, item.clone()));
            }
        }
        return List.copyOf(children);
    }
}
