package org.aincraft.kitsune.protection;

import java.util.UUID;
import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Abstract Bukkit adapter for ProtectionProvider.
 * Handles conversion from platform-agnostic types to Bukkit types.
 */
public abstract class BukkitProtectionProvider
    implements org.aincraft.kitsune.protection.ProtectionProvider {

    @Override
    public final boolean canAccess(UUID playerId, Location location) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            // Player not online - cannot verify access
            return false;
        }

        org.bukkit.Location bukkitLocation = BukkitLocation.toBukkitOrNull(location);
        if (bukkitLocation == null) {
            // World not loaded - allow access by default
            return true;
        }

        return canAccessBukkit(player, bukkitLocation);
    }

    /**
     * Checks if a player can access a container at the given Bukkit location.
     *
     * @param player   The Bukkit player
     * @param location The Bukkit location
     * @return true if access is allowed
     */
    protected abstract boolean canAccessBukkit(Player player, org.bukkit.Location location);
}
