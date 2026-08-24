package org.aincraft.kitsune.listener;

import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.aincraft.kitsune.storage.KitsuneStorage;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import jakarta.inject.Inject;

public class ContainerBreakListener implements Listener {
    private final KitsuneStorage storage;
    private final BukkitContainerIndexer indexer;
    private final Plugin plugin;

    @Inject
    public ContainerBreakListener(KitsuneStorage storage, BukkitContainerIndexer indexer, Plugin plugin) {
        this.storage = storage;
        this.indexer = indexer;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Container)) {
            return;
        }

        Block brokenBlock = event.getBlock();

        // Check if this is part of a double chest and find the other half
        Block otherHalf = findDoubleChestOtherHalf(brokenBlock);

        // Convert broken block location
        Location brokenLocation = BukkitLocation.from(brokenBlock.getLocation());

        // Look up the primary location for this block (handles multi-block containers)
        storage.getPrimaryLocation(brokenLocation)
            .thenCompose(primaryOpt -> {
                Location primaryLocation = primaryOpt.orElse(brokenLocation);

                // Delete the container from the index
                return storage.delete(primaryLocation)
                    .thenCompose(unused -> {
                        // Clean up position mappings
                        return storage.deleteContainerPositions(primaryLocation);
                    });
            })
            .thenRun(() -> {
                // If there was another half, schedule re-indexing on main thread
                if (otherHalf != null) {
                    // Delay by 1 tick to let the block break complete and chest become single
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        // Check on main thread that the other half is still a container
                        if (otherHalf.getState() instanceof Container container) {
                            indexer.scheduleIndex(otherHalf.getLocation(), container.getInventory().getContents());
                        }
                    }, 1L);
                }
            })
            .exceptionally(ex -> {
                event.getPlayer().sendMessage("§cFailed to remove container from index: " + ex.getMessage());
                return null;
            });
    }

    /**
     * Finds the other half of a double chest, if the block is part of one.
     * @param block the block to check
     * @return the other half block, or null if not a double chest
     */
    private Block findDoubleChestOtherHalf(Block block) {
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return null;
        }

        Chest.Type type = chestData.getType();
        if (type == Chest.Type.SINGLE) {
            return null;
        }

        BlockFace facing = chestData.getFacing();
        BlockFace otherHalfDirection = getOtherHalfDirection(type, facing);

        if (otherHalfDirection == null) {
            return null;
        }

        return block.getRelative(otherHalfDirection);
    }

    /**
     * Determines the direction to the other half of a double chest.
     * @param type LEFT or RIGHT chest type
     * @param facing the direction the chest faces
     * @return the BlockFace direction to the other half
     */
    private BlockFace getOtherHalfDirection(Chest.Type type, BlockFace facing) {
        // Double chest layout based on facing direction:
        // LEFT type: other half is to the right (clockwise from facing)
        // RIGHT type: other half is to the left (counter-clockwise from facing)
        return switch (facing) {
            case NORTH -> type == Chest.Type.LEFT ? BlockFace.EAST : BlockFace.WEST;
            case SOUTH -> type == Chest.Type.LEFT ? BlockFace.WEST : BlockFace.EAST;
            case EAST -> type == Chest.Type.LEFT ? BlockFace.SOUTH : BlockFace.NORTH;
            case WEST -> type == Chest.Type.LEFT ? BlockFace.NORTH : BlockFace.SOUTH;
            default -> null;
        };
    }
}
