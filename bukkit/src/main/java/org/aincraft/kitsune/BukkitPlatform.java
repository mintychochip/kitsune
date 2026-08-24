package org.aincraft.kitsune;

import com.google.common.base.Preconditions;
import org.aincraft.kitsune.api.serialization.TagProviderRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Bukkit implementation of the KitsunePlugin platform abstraction. Provides a clean interface for
 * the common module to interact with Bukkit.
 */
public final class BukkitPlatform implements Platform {

  private final JavaPlugin plugin;
  private final TagProviderRegistry tagProviderRegistry;

  public BukkitPlatform(JavaPlugin plugin, TagProviderRegistry tagProviderRegistry) {
    this.plugin = plugin;
    this.tagProviderRegistry = tagProviderRegistry;
  }

  @Override
  public Logger getLogger() {
    return plugin.getLogger();
  }

  @Override
  public Path getDataFolder() {
    return plugin.getDataFolder().toPath();
  }

  @Override
  public TagProviderRegistry getTagProviderRegistry() {
    return tagProviderRegistry;
  }

  @Override
  public World getWorld(String worldName) {
    org.bukkit.World bukkitWorld = Bukkit.getWorld(worldName);
    Preconditions.checkArgument(bukkitWorld != null, "Could not locate world");
    return new BukkitWorld(bukkitWorld);
  }

  @Override
  public Location createLocation(String worldName, int x, int y, int z) throws IllegalArgumentException {
    org.bukkit.World bukkitWorld = Bukkit.getWorld(worldName);
    Preconditions.checkArgument(bukkitWorld != null, "Could not locate world");
    org.bukkit.Location bukkitLocation = new org.bukkit.Location(bukkitWorld, x, y, z);
    return BukkitLocation.from(bukkitLocation);
  }
}
