package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;

/**
 * Forwards Bukkit world and inventory events into the container index
 * to keep it synchronized with live world state.
 */
public final class IndexListener implements Listener {
    private final ContainerIndex index;

    /**
     * Creates a listener forwarding events to an index.
     *
     * @param index index to drive
     */
    public IndexListener(ContainerIndex index) {
        this.index = Objects.requireNonNull(index, "ContainerIndex");
    }

    /**
     * Registers a newly loaded chunk with the index.
     *
     * @param event chunk load event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoaded(ChunkLoadEvent event) { index.onChunkLoaded(event.getChunk()); }

    /**
     * Unregisters an unloading chunk with the index.
     *
     * @param event chunk unload event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloaded(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        UUID worldId = Objects.requireNonNull(chunk.getWorld(), "chunk world").getUID();
        index.onChunkUnloaded(new ChunkKey(worldId, chunk.getX(), chunk.getZ()));
    }

    /**
     * Marks a container block dirty when placed.
     *
     * @param event block place event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) { markDirty(event.getBlockPlaced()); }

    /**
     * Deletes an indexed root when its block is broken.
     *
     * @param event block break event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) { delete(event.getBlock()); }

    /**
     * Deletes indexed root blocks destroyed by a block explosion.
     *
     * @param event block explosion event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { deleteBlocks(event.blockList()); }

    /**
     * Deletes indexed root blocks destroyed by an entity explosion.
     *
     * @param event entity explosion event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { deleteBlocks(event.blockList()); }

    /**
     * Marks a viewed top inventory dirty when an inventory click changes it.
     *
     * @param event inventory click event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (clickAffectsTop(event.getRawSlot(), top.getSize(), event.getAction())) {
            markDirty(top);
        }
    }

    /**
     * Marks a viewed top inventory dirty when an inventory drag changes it.
     *
     * @param event inventory drag event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (dragAffectsTop(event.getRawSlots(), top.getSize())) {
            markDirty(top);
        }
    }
    /**
     * Marks source and destination inventories dirty when items are moved.
     *
     * @param event inventory move item event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        markTransferDirty(event.getSource());
        markTransferDirty(event.getDestination());
    }

    /**
     * Marks the target inventory dirty when items are picked up.
     *
     * @param event inventory pickup item event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) { markTransferDirty(event.getInventory()); }

    /**
     * Marks a furnace block dirty when it smelts an item.
     *
     * @param event furnace smelt event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) { markDirty(event.getBlock()); }

    /**
     * Marks a furnace block dirty when fuel burns.
     *
     * @param event furnace burn event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) { markDirty(event.getBlock()); }

    /**
     * Marks a container block dirty when its contents are cooked, ignoring
     * furnace smelt events already handled separately.
     *
     * @param event block cook event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockCook(BlockCookEvent event) {
        if (event instanceof FurnaceSmeltEvent) return;
        markDirty(event.getBlock());
    }

    /**
     * Marks a brewing stand block dirty when a brew completes.
     *
     * @param event brew event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) { markDirty(event.getBlock()); }

    /**
     * Returns whether an inventory click affects the container's top inventory.
     *
     * @param rawSlot raw slot index clicked
     * @param topSize top inventory size
     * @param action inventory action performed
     * @return {@code true} when the click may change the top inventory
     */
    static boolean clickAffectsTop(int rawSlot, int topSize, InventoryAction action) {
        Objects.requireNonNull(action, "Inventory action must not be null");
        if (rawSlot >= 0 && rawSlot < topSize) {
            return action != InventoryAction.NOTHING
                && action != InventoryAction.CLONE_STACK
                && action != InventoryAction.DROP_ALL_CURSOR
                && action != InventoryAction.DROP_ONE_CURSOR;
        }
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
            || action == InventoryAction.COLLECT_TO_CURSOR;
    }

    /**
     * Returns whether an inventory drag touches the container's top inventory.
     *
     * @param rawSlots raw slot indices involved
     * @param topSize top inventory size
     * @return {@code true} when the drag may change the top inventory
     */
    static boolean dragAffectsTop(Set<Integer> rawSlots, int topSize) {
        Objects.requireNonNull(rawSlots, "Raw slots must not be null");
        for (Integer rawSlot : rawSlots) {
            if (rawSlot != null && rawSlot >= 0 && rawSlot < topSize) return true;
        }
        return false;
    }

    private void markDirty(Block block) {
        if (block == null) return;
        if (block.getState() instanceof BlockInventoryHolder holder) {
            index.markDirty(holder);
            if (isChest(block)) markTopologyDirty(block);
        }
    }
    private void markDirty(Inventory inventory) {
        if (inventory != null) index.markDirty(inventory.getHolder());
    }

    private void markTransferDirty(Inventory inventory) {
        if (inventory != null) index.markTransferDirty(inventory.getHolder());
    }

    private void delete(Block block) {
        if (block == null) return;
        if (block.getState() instanceof BlockInventoryHolder holder) {
            index.delete(holder);
            if (isChest(block)) markTopologyDirty(block);
        }
    }

    private void markTopologyDirty(Block block) {
        BlockKey changed = new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        for (BlockKey affected : StorageTopology.affectedRoots(changed)) index.markDirty(affected);
    }

    private static boolean isChest(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private void deleteBlocks(Iterable<Block> blocks) {
        for (Block block : blocks) delete(block);
    }
}
