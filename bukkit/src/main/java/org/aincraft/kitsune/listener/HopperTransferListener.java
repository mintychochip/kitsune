package org.aincraft.kitsune.listener;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.api.indexing.ContainerLocationResolver;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import jakarta.inject.Inject;

public class HopperTransferListener implements Listener {
    private final BukkitContainerIndexer containerIndexer;
    private final ContainerLocationResolver locationResolver;
    private final Plugin plugin;

    @Inject
    public HopperTransferListener(BukkitContainerIndexer containerIndexer, ContainerLocationResolver locationResolver, Plugin plugin) {
        this.containerIndexer = containerIndexer;
        this.locationResolver = locationResolver;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            InventoryHolder sourceHolder = source.getHolder();
            if (sourceHolder instanceof Container || sourceHolder instanceof DoubleChest) {
                ContainerLocations sourceLocations = locationResolver.resolveFromInventoryHolder(sourceHolder);
                if (sourceLocations != null) {
                    containerIndexer.scheduleIndex(sourceLocations, source.getContents());
                }
            }

            // Handle destination container (single or double chest)
            InventoryHolder destHolder = destination.getHolder();
            if (destHolder instanceof Container || destHolder instanceof DoubleChest) {
                ContainerLocations destLocations = locationResolver.resolveFromInventoryHolder(destHolder);
                if (destLocations != null) {
                    containerIndexer.scheduleIndex(destLocations, destination.getContents());
                }
            }
        }, 1L);
    }
}
