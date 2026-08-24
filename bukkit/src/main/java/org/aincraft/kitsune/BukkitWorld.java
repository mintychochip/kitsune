package org.aincraft.kitsune;

public record BukkitWorld(org.bukkit.World world) implements World {

  @Override
  public String getName() {
    return world.getName();
  }

  @Override
  public Block getBlock(int x, int y, int z) {
    return BukkitBlock.from(world.getBlockAt(x,y,z));
  }
}
