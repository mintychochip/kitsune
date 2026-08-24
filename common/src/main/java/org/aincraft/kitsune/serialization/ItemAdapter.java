package org.aincraft.kitsune.serialization;

import org.aincraft.kitsune.Item;

import java.util.List;

/**
 * Platform-agnostic adapter for item serialization.
 * @param <T> Platform-specific item type (e.g., ItemStack for Bukkit)
 */
public interface ItemAdapter<T> {
    /**
     * Convert platform item to Item interface.
     */
    Item toItem(T item);

    /**
     * Check if item is empty/null.
     */
    boolean isEmpty(T item);

    /**
     * Get the slot index for an item in a list context.
     */
    default int getSlotIndex(List<T> items, T item) {
        return items.indexOf(item);
    }
}
