package dev.jlo.kitsune.api.protection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Provides block-level access decisions for a player.
 *
 * <p>Implementations must run on the server thread and must not retain Bukkit objects.
 */
public interface BlockAccessProvider {
    AccessDecision canAccess(Player player, Block block);
}
