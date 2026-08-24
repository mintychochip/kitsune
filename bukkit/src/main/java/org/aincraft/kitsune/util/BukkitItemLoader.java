package org.aincraft.kitsune.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.indexing.ItemLoader;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.aincraft.kitsune.serialization.BukkitItem;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Logger;

/**
 * Bukkit implementation of ItemLoader that loads live items from container inventories.
 * Centralizes item loading logic from BukkitKitsuneMain for reusability.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Converting platform-agnostic Location to Bukkit Location</li>
 *   <li>Loading items from direct container slots</li>
 *   <li>Navigating nested containers (shulker boxes, bundles)</li>
 *   <li>Thread safety checks (must run on main thread)</li>
 *   <li>Null safety for unloaded chunks, missing items, etc.</li>
 * </ul>
 */
public class BukkitItemLoader implements ItemLoader {

    private final Logger logger;

    public BukkitItemLoader(Logger logger) {
        this.logger = logger;
    }

    /**
     * Load a live item from a container at the specified location and slot.
     *
     * @param location the container location
     * @param slotIndex the slot index within the final container
     * @param containerPath the path through nested containers, or null/root for direct access
     * @return ItemContext wrapping the live item, or null if item cannot be loaded
     */
    @Nullable
    @Override
    public Item loadItem(Location location, int slotIndex, @Nullable ContainerPath containerPath) {
        if (slotIndex < 0) {
            return null;
        }

        // Must be called from main thread to access block entities
        if (!Bukkit.isPrimaryThread()) {
            logger.fine("BukkitItemLoader: cannot access item from async thread");
            return null;
        }

        try {
            // Convert to Bukkit location
            org.bukkit.Location bukkitLoc = BukkitLocation.toBukkitOrNull(location);
            if (bukkitLoc == null) {
                logger.fine("BukkitItemLoader: world not loaded for location: " + location.getWorld().getName());
                return null;
            }

            // Get the block at the chest location
            org.bukkit.block.Block block = bukkitLoc.getBlock();
            if (block == null) {
                return null;
            }

            if (!(block.getState() instanceof org.bukkit.block.Container container)) {
                logger.fine("BukkitItemLoader: block is not a container at " + bukkitLoc);
                return null;
            }

            Inventory inventory = container.getInventory();
            if (inventory == null) {
                return null;
            }

            // If no container path, get item directly from chest at slotIndex
            if (containerPath == null || containerPath.isRoot()) {
                if (slotIndex >= inventory.getSize()) {
                    return null;
                }
                ItemStack item = inventory.getItem(slotIndex);
                if (item == null || item.getType().isAir()) {
                    return null;
                }
                return new BukkitItem(item);
            }

            // Navigate through container path to find the final container,
            // then get the item at slotIndex from that container
            ItemStack nestedItem = navigateNestedItem(inventory, containerPath, slotIndex);
            if (nestedItem == null || nestedItem.getType().isAir()) {
                return null;
            }

            return new BukkitItem(nestedItem);

        } catch (Exception e) {
            // Silently fail - expected for async access, chunk not loaded, etc.
            logger.fine("BukkitItemLoader: failed to load item: " + e.getMessage());
            return null;
        }
    }

    /**
     * Navigate into nested containers (shulker boxes, bundles) to get the item.
     *
     * <p>For nested items:
     * <ul>
     *   <li>containerPath stores the path to get to the container holding the item</li>
     *   <li>slotIndex is the item's slot within that final container</li>
     * </ul>
     *
     * <p>Example: Diamond at slot 3 inside a shulker at chest slot 5
     * <ul>
     *   <li>containerPath = [{type:"shulker_box", slot:5}]</li>
     *   <li>slotIndex = 3</li>
     *   <li>Navigation: chest -> slot 5 (shulker) -> slot 3 (diamond)</li>
     * </ul>
     *
     * @param chestInventory the chest inventory to start from
     * @param path the container path (each ref specifies container type and its slot in parent)
     * @param itemSlot the final item's slot within the deepest container
     * @return the item, or null if navigation fails
     */
    @Nullable
    private ItemStack navigateNestedItem(Inventory chestInventory, ContainerPath path, int itemSlot) {
        if (path == null || path.isRoot()) {
            return chestInventory.getItem(itemSlot);
        }

        List<ContainerNode> refs = path.containerRefs();
        if (refs.isEmpty()) {
            return chestInventory.getItem(itemSlot);
        }

        // Start by getting the first container from the chest
        int firstSlot = refs.get(0).getSlotIndex();
        if (firstSlot < 0 || firstSlot >= chestInventory.getSize()) {
            return null;
        }
        ItemStack currentContainer = chestInventory.getItem(firstSlot);
        if (currentContainer == null || currentContainer.getType().isAir()) {
            return null;
        }

        // Navigate through remaining containers in the path (if any)
        for (int i = 1; i < refs.size(); i++) {
            List<ItemStack> contents = getContainerContents(currentContainer);
            if (contents == null || contents.isEmpty()) {
                return null;
            }

            int containerSlot = refs.get(i).getSlotIndex();
            if (containerSlot < 0 || containerSlot >= contents.size()) {
                return null;
            }

            currentContainer = contents.get(containerSlot);
            if (currentContainer == null) {
                return null;
            }
        }

        // Now currentContainer is the final container, get the item at itemSlot
        List<ItemStack> finalContents = getContainerContents(currentContainer);
        if (finalContents == null || finalContents.isEmpty()) {
            return null;
        }

        if (itemSlot < 0 || itemSlot >= finalContents.size()) {
            return null;
        }

        return finalContents.get(itemSlot);
    }

    /**
     * Get contents from a container item (shulker box or bundle).
     *
     * @param item the container item
     * @return list of items in the container, or null if not a container
     */
    @Nullable
    private List<ItemStack> getContainerContents(ItemStack item) {
        if (item == null) {
            return null;
        }

        // Check for shulker box contents
        if (item.hasData(DataComponentTypes.CONTAINER)) {
            var container = item.getData(DataComponentTypes.CONTAINER);
            if (container != null) {
                return container.contents();
            }
        }
        // Check for bundle contents
        else if (item.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
            var bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                return bundle.contents();
            }
        }
        return null;
    }
}
