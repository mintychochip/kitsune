package org.aincraft.kitsune.api.indexing;

import org.aincraft.kitsune.api.ContainerLocations;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-agnostic resolver for container locations.
 * Handles resolution of container locations from platform-specific objects,
 * supporting multi-block containers like double chests.
 */
public interface ContainerLocationResolver {

    /**
     * Resolve locations for a container from its inventory holder object.
     *
     * @param inventoryHolder the platform-specific inventory holder object
     * @return container locations, or null if not a valid container
     */
    @Nullable
    ContainerLocations resolveFromInventoryHolder(Object inventoryHolder);

    /**
     * Resolve locations for a container from a block object.
     *
     * @param block the platform-specific block object that may be part of a container
     * @return container locations, or null if not a container block
     */
    @Nullable
    ContainerLocations resolveFromBlock(Object block);
}
