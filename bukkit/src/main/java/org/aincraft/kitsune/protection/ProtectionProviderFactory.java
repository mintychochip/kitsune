package org.aincraft.kitsune.protection;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.bolt.BoltAPI;

/**
 * Factory for creating ProtectionProvider instances. Returns Optional.empty() when protection is
 * disabled or not available.
 */
public final class ProtectionProviderFactory {

  private ProtectionProviderFactory() {
  }

  /**
   * Creates a ProtectionProvider based on configuration.
   *
   * @param config The plugin configuration
   * @param plugin The JavaPlugin instance
   * @param logger The logger for status messages
   * @return Optional containing the provider, or empty if protection is disabled
   */
  public static Optional<ProtectionProvider> create(
      KitsuneConfig config, JavaPlugin plugin, Logger logger) {
    if (!config.protectionEnabled()) {
      logger.info("Protection integration disabled");
      return Optional.empty();
    }

    String provider = config.protectionPlugin().toLowerCase();

    if ("auto".equals(provider)) {
      if (plugin.getServer().getPluginManager().getPlugin("Lockette") != null) {
        logger.info("Using Lockette protection integration");
        return Optional.of(new LocketteProtectionProvider());
      }
      if (plugin.getServer().getPluginManager().getPlugin("Bolt") != null) {
        try {
          logger.info("Using Bolt protection integration");
          BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
          return Optional.of(new BoltProtectionProvider(bolt));
        } catch (NoClassDefFoundError e) {
          logger.warning("Bolt plugin detected but BoltAPI not available");
        }
      }
      if (plugin.getServer().getPluginManager().getPlugin("LWCX") != null) {
        logger.info("Using LWCX protection integration");
        return Optional.of(new LWCXProtectionProvider());
      }
      logger.info("No protection plugin detected, protection checks disabled");
      return Optional.empty();
    }

    return switch (provider) {
      case "lockette" -> {
        logger.info("Using Lockette protection integration");
        yield Optional.of(new LocketteProtectionProvider());
      }
      case "bolt" -> {
        try {
          logger.info("Using Bolt protection integration");
          BoltAPI bolt = Bukkit.getServicesManager().load(BoltAPI.class);
          yield Optional.of(new BoltProtectionProvider(bolt));
        } catch (NoClassDefFoundError e) {
          logger.warning("BoltAPI not available");
          yield Optional.empty();
        }
      }
      case "lwcx" -> {
        logger.info("Using LWCX protection integration");
        yield Optional.of(new LWCXProtectionProvider());
      }
      case "none" -> {
        logger.info("Protection integration explicitly disabled");
        yield Optional.empty();
      }
      default -> {
        logger.warning("Unknown protection provider: " + provider + ", disabling protection");
        yield Optional.empty();
      }
    };
  }

  private static class LocketteProtectionProvider extends BukkitProtectionProvider {

    @Override
    protected boolean canAccessBukkit(Player player, Location location) {
      try {
        Class<?> locketteClass = Class.forName("org.yi.acru.bukkit.Lockette.Lockette");
        var method = locketteClass.getMethod("canAccessContainer", Player.class, Location.class);
        return (Boolean) method.invoke(null, player, location);
      } catch (Exception e) {
        return true;
      }
    }
  }

    private record BoltProtectionProvider(BoltAPI bolt) implements ProtectionProvider {

        @Override
        public boolean canAccess(UUID playerId, org.aincraft.kitsune.Location location) {
            Location loc = BukkitLocation.toBukkit(location);
            Block block = loc.getBlock();
            Player player = Bukkit.getPlayer(playerId);
          return bolt.canAccess(block, player, "bolt.access");
        }
    }

    private static class LWCXProtectionProvider extends BukkitProtectionProvider {

        @Override
        protected boolean canAccessBukkit(Player player, Location location) {
            try {
                Class<?> lwcClass = Class.forName("com.griefcraft.lwc.LWC");
                var getInstance = lwcClass.getMethod("getInstance");
                Object lwc = getInstance.invoke(null);
                var canAccessMethod = lwcClass.getMethod("canAccessProtection", Player.class, Block.class);
                return (Boolean) canAccessMethod.invoke(lwc, player, location.getBlock());
            } catch (Exception e) {
                return true;
            }
        }
    }
}
