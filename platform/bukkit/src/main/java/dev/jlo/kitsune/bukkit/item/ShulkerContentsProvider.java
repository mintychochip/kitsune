package dev.jlo.kitsune.item;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

final class ShulkerContentsProvider implements BukkitNestedContentsProvider {
    @Override
    public boolean supports(ItemStack stack) {
        if (BukkitItemDescriber.isEmpty(stack)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) return false;
        BlockState state = blockStateMeta.getBlockState();
        return state instanceof ShulkerBox;
    }

    @Override
    public List<Child> children(ItemStack stack) {
        if (!supports(stack)) return List.of();
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) return List.of();
        BlockState state = blockStateMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulkerBox)) return List.of();
        Inventory snapshot = shulkerBox.getInventory();
        String label = stack.getType().getKey().toString();
        List<Child> children = new ArrayList<>();
        for (int slot = 0; slot < snapshot.getSize(); slot++) {
            ItemStack item = snapshot.getItem(slot);
            if (!BukkitItemDescriber.isEmpty(item)) children.add(new Child(label, slot, item.clone()));
        }
        return List.copyOf(children);
    }
}
