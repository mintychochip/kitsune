package org.aincraft.kitsune.protection;

import java.util.UUID;
import org.aincraft.kitsune.Location;

/**
 * Platform-agnostic interface for protection plugin integration.
 * Implementations check if players can access containers at given locations.
 *
 * <p>This interface is nullable at runtime - some platforms may not provide
 * protection integration. Callers should handle the absence of a provider
 * by allowing access by default.</p>
 */
public interface ProtectionProvider {
    /**
     * Checks if a player can access a container at the given location.
     *
     * @param playerId The UUID of the player to check
     * @param location The container location
     * @return true if the player can access the container, false otherwise
     */
    boolean canAccess(UUID playerId, Location location);
}
