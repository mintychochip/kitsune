package org.aincraft.kitsune;

import org.bukkit.Bukkit;

public record BukkitLocation(org.bukkit.Location location, int blockX, int blockY,
                             int blockZ) implements Location {

  public static Location from(org.bukkit.Location location) {
    if (location == null) {
      throw new IllegalArgumentException("Location cannot be null");
    }
    return new BukkitLocation(location, location.blockX(), location.blockY(), location.blockZ());
  }

  public static org.bukkit.Location toBukkit(Location data) {
    org.bukkit.World world = Bukkit.getWorld(data.getWorld().getName());
    if (world == null) {
      throw new IllegalArgumentException("World is not loaded: " + data.getWorld().getName());
    }
    return new org.bukkit.Location(world, data.blockX(), data.blockY(), data.blockZ());
  }

  public static org.bukkit.Location toBukkitOrNull(Location data) {
    if (data == null) return null;
    org.bukkit.World world = Bukkit.getWorld(data.getWorld().getName());
    if (world == null) return null;
    return new org.bukkit.Location(world, data.blockX(), data.blockY(), data.blockZ());
  }

  @Override
  public World getWorld() {
    return new BukkitWorld(location.getWorld());
  }

  public Block getBlock() {
    return BukkitBlock.from(location.getBlock());
  }
}
