package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import java.util.Objects;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;

public final class IndexListener implements Listener {
    private final ContainerIndex index;

    public IndexListener(ContainerIndex index) {
        this.index = Objects.requireNonNull(index, "ContainerIndex");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoaded(ChunkLoadEvent event) { index.onChunkLoaded(event.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnloaded(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        UUID worldId = Objects.requireNonNull(chunk.getWorld(), "chunk world").getUID();
        index.onChunkUnloaded(new ChunkKey(worldId, chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) { markDirty(event.getBlockPlaced()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) { delete(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { deleteBlocks(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { deleteBlocks(event.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) { markDirty(event.getView().getTopInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) { markDirty(event.getView().getTopInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        markDirty(event.getSource());
        markDirty(event.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) { markDirty(event.getInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) { markDirty(event.getInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) { markDirty(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) { markDirty(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockCook(BlockCookEvent event) {
        if (event instanceof FurnaceSmeltEvent) return;
        markDirty(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) { markDirty(event.getBlock()); }

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
