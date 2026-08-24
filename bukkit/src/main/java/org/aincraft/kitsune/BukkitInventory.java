package org.aincraft.kitsune;

import java.util.List;
import java.util.function.IntFunction;
import org.aincraft.kitsune.serialization.BukkitItem;
import org.bukkit.inventory.ItemStack;

public record BukkitInventory(IntFunction<ItemStack> getter, int size) implements Inventory {

  public static Inventory from(org.bukkit.inventory.Inventory inventory) {
    return new BukkitInventory(inventory::getItem, inventory.getSize());
  }

  public static Inventory from(List<ItemStack> items) {
    return new BukkitInventory(items::get, items.size());
  }

  @Override
  public Item getItem(int slot) {
    ItemStack item = getter.apply(slot);
    if (item == null || item.getType().isAir()) {
      return null;
    }
    return new BukkitItem(item);
  }
}
