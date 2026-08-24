package org.aincraft.kitsune;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.jetbrains.annotations.Nullable;

public record BukkitBlock(org.bukkit.block.Block block, String type, String world,
                          @Nullable Inventory inventory) implements
    Block {

  public static Block from(org.bukkit.block.Block block) {
    Material blockType = block.getType();
    BlockState state = block.getState();
    @Nullable
    Inventory inventory = null;
    if (state instanceof Container container) {
      inventory = BukkitInventory.from(container.getInventory());
    }
    return new BukkitBlock(block, blockType.toString(), block.getWorld().toString(), inventory);
  }

  @Override
  public boolean isAir() {
    return block.getType().isAir();
  }
}
