package org.aincraft.kitsune.listener;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.api.indexing.ContainerLocationResolver;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ContainerCloseListener implements Listener {
    private final BukkitContainerIndexer containerIndexer;
    private final ContainerLocationResolver locationResolver;
    private final Plugin plugin;
    private final Logger logger;

    @Inject
    public ContainerCloseListener(BukkitContainerIndexer containerIndexer,
                                 ContainerLocationResolver locationResolver,
                                 Plugin plugin) {
        this.containerIndexer = containerIndexer;
        this.locationResolver = locationResolver;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();

        // Handle both single containers and double chests
        if (!(holder instanceof Container) && !(holder instanceof DoubleChest)) {
            logger.fine("ContainerCloseListener: Ignoring non-container inventory: " +
                (holder != null ? holder.getClass().getSimpleName() : "null"));
            return;
        }

        logger.info("ContainerCloseListener: Container close detected for " + event.getPlayer().getName());

        // Resolve container locations (handles single and multi-block)
        ContainerLocations locations = locationResolver.resolveFromInventoryHolder(holder);
        if (locations == null) {
            logger.warning("ContainerCloseListener: Failed to resolve locations for holder: " + holder.getClass().getSimpleName());
            return;
        }

        logger.info("ContainerCloseListener: Resolved locations: " + locations.primaryLocation());

        // Clone the inventory contents before leaving main thread
        ItemStack[] inventoryContents = event.getInventory().getContents().clone();
        long nonNullItems = Arrays.stream(inventoryContents).filter(i -> i != null && !i.getType().isAir()).count();
        logger.info("ContainerCloseListener: Inventory has " + nonNullItems + " non-empty items");

        // Schedule async indexing operation
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("ContainerCloseListener: Calling scheduleIndex...");
                // Perform indexing asynchronously
                containerIndexer.scheduleIndex(locations, inventoryContents);
                logger.info("ContainerCloseListener: scheduleIndex called successfully");
            } catch (Exception e) {
                logger.severe("ContainerCloseListener: Failed to index container asynchronously: " + e.getMessage());
                e.printStackTrace();
            }
        }).exceptionally(ex -> {
            logger.severe("ContainerCloseListener: Error during container close indexing: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }
}
