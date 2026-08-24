package org.aincraft.kitsune.api.indexing;

import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.Item;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-agnostic interface for loading live items from containers.
 * Implementations handle platform-specific logic for accessing container inventories.
 */
public interface ItemLoader {

    /**
     * Load a live item from a container at the specified location and slot.
     *
     * @param location the container location
     * @param slotIndex the slot index within the final container
     * @param containerPath the path through nested containers, or null/root for direct access
     * @return Item wrapping the live item, or null if item cannot be loaded
     */
    @Nullable
    Item loadItem(Location location, int slotIndex, @Nullable ContainerPath containerPath);
}
