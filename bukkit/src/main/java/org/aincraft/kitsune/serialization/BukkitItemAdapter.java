package org.aincraft.kitsune.serialization;

import org.aincraft.kitsune.Item;
import org.bukkit.inventory.ItemStack;

public enum BukkitItemAdapter implements ItemAdapter<ItemStack> {
    INSTANCE;

    @Override
    public Item toItem(ItemStack item) {
        return new BukkitItem(item);
    }

    @Override
    public boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
