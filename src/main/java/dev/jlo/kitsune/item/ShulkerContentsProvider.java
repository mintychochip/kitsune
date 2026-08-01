package dev.jlo.kitsune.item;

import java.util.*;

import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import io.papermc.paper.block.TileStateInventoryHolder;

import dev.jlo.kitsune.api.item.NestedContentsProvider;

final class ShulkerContentsProvider implements NestedContentsProvider {

    @Override
    public boolean supports(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) return false;
        BlockState state = blockStateMeta.getBlockState();
        return state instanceof ShulkerBox;
    }

    @Override
    public List<NestedContentsProvider.ChildItem> children(ItemStack stack) {
        if (!supports(stack)) return Collections.emptyList();
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) return Collections.emptyList();
        BlockState state = blockStateMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulkerBox)) return Collections.emptyList();
        Inventory snapshot = ((TileStateInventoryHolder) shulkerBox).getSnapshotInventory();
        String label = stack.getType().getKey().toString();
        List<NestedContentsProvider.ChildItem> children = new ArrayList<>();
        for (int slot = 0; slot < snapshot.getSize(); slot++) {
            ItemStack item = snapshot.getItem(slot);
            if (item != null && !item.isEmpty()) {
                children.add(new NestedContentsProvider.ChildItem(label, slot, item.clone()));
            }
        }
        return List.copyOf(children);
    }
}
