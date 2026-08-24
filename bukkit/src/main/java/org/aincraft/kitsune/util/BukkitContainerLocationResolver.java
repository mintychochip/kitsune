package org.aincraft.kitsune.util;

import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.indexing.ContainerLocationResolver;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit implementation of ContainerLocationResolver.
 * Handles both single-block containers and multi-block containers like double chests.
 */
public final class BukkitContainerLocationResolver implements ContainerLocationResolver {

    @Override
    @Nullable
    public ContainerLocations resolveFromInventoryHolder(Object inventoryHolder) {
        if (inventoryHolder == null) {
            return null;
        }

        // Cast to InventoryHolder
        if (!(inventoryHolder instanceof InventoryHolder holder)) {
            return null;
        }

        // Handle double chest - multi-block container
        if (holder instanceof DoubleChest doubleChest) {
            return resolveDoubleChestLocations(doubleChest);
        }

        // Handle regular container blocks
        if (holder instanceof Container container) {
            Location location = BukkitLocation.from(container.getLocation());
            return ContainerLocations.single(location);
        }

        return null;
    }

    @Override
    @Nullable
    public ContainerLocations resolveFromBlock(Object block) {
        if (block == null) {
            return null;
        }

        // Cast to Block
        if (!(block instanceof Block bukkitBlock)) {
            return null;
        }

        // Check if the block state is a container
        if (!(bukkitBlock.getState() instanceof Container)) {
            return null;
        }

        // Try to get inventory holder through the block
        InventoryHolder holder = (InventoryHolder) bukkitBlock.getState();
        if (holder != null) {
            return resolveFromInventoryHolder(holder);
        }

        // Fallback to single location
        Location location = BukkitLocation.from(bukkitBlock.getLocation());
        return ContainerLocations.single(location);
    }

    /**
     * Resolves locations for a double chest, determining the primary location
     * by selecting the lexicographically smaller position.
     *
     * @param doubleChest the double chest
     * @return container locations for the double chest
     */
    private ContainerLocations resolveDoubleChestLocations(DoubleChest doubleChest) {
        InventoryHolder leftHolder = doubleChest.getLeftSide();
        InventoryHolder rightHolder = doubleChest.getRightSide();

        if (leftHolder == null || rightHolder == null) {
            return null;
        }

        // Cast to Chest to get location
        if (!(leftHolder instanceof Chest) || !(rightHolder instanceof Chest)) {
            return null;
        }

        Chest leftChest = (Chest) leftHolder;
        Chest rightChest = (Chest) rightHolder;

        Location leftLocation = BukkitLocation.from(leftChest.getLocation());
        Location rightLocation = BukkitLocation.from(rightChest.getLocation());

        // Determine primary location: smaller X, then smaller Z (lexicographically)
        Location primaryLocation;
        if (isLocationSmaller(leftLocation, rightLocation)) {
            primaryLocation = leftLocation;
        } else {
            primaryLocation = rightLocation;
        }

        return ContainerLocations.multi(
            primaryLocation,
            java.util.List.of(leftLocation, rightLocation)
        );
    }

    /**
     * Compares two locations to determine which is lexicographically smaller.
     * Primary: smaller X, Secondary: smaller Z, Tertiary: smaller Y
     *
     * @param loc1 first location
     * @param loc2 second location
     * @return true if loc1 is smaller than loc2
     */
    private boolean isLocationSmaller(Location loc1, Location loc2) {
        if (loc1.blockX() != loc2.blockX()) {
            return loc1.blockX() < loc2.blockX();
        }
        if (loc1.blockZ() != loc2.blockZ()) {
            return loc1.blockZ() < loc2.blockZ();
        }
        return loc1.blockY() < loc2.blockY();
    }
}
