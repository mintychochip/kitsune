package org.aincraft.kitsune.listener;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.api.indexing.ContainerLocationResolver;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import jakarta.inject.Inject;

public class ChestPlaceListener implements Listener {
    private final ContainerLocationResolver locationResolver;
    private final BukkitContainerIndexer containerIndexer;
    private final Plugin plugin;

    @Inject
    public ChestPlaceListener(ContainerLocationResolver locationResolver, BukkitContainerIndexer containerIndexer, Plugin plugin) {
        this.locationResolver = locationResolver;
        this.containerIndexer = containerIndexer;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placedBlock = event.getBlock();

        // Only care about containers
        if (!(placedBlock.getState() instanceof Container)) {
            return;
        }

        // Check if this is a chest that might form a double chest
        if (!(placedBlock.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData)) {
            return;
        }

        // If still single, nothing to do
        if (chestData.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return;
        }

        // Delay by 1 tick to allow block placement to fully complete
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!(placedBlock.getState() instanceof Container container)) {
                return;
            }

            // Get inventory holder - for double chest this will be DoubleChest
            ContainerLocations locations = locationResolver.resolveFromInventoryHolder(container.getInventory().getHolder());
            if (locations == null) {
                return;
            }

            // Schedule index for the (double) chest
            containerIndexer.scheduleIndex(locations, container.getInventory().getContents());
        }, 1L);
    }
}
