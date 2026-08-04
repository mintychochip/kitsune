package dev.jlo.kitsune.bukkit.index;

import dev.jlo.kitsune.index.RootResolver;
import dev.jlo.kitsune.model.BlockKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.loot.Lootable;

import java.util.Objects;

public final class BukkitRootResolver {
    private BukkitRootResolver() {
    }

    public static RootResolver<Inventory> forServer(Server server) {
        return new RootResolver<>(new SpigotLiveAccess(Objects.requireNonNull(server, "Server must not be null")));
    }

    private static final class SpigotLiveAccess implements RootResolver.LiveAccess<Inventory> {
        private final Server server;

        private SpigotLiveAccess(Server server) {
            this.server = server;
        }

        @Override
        public boolean isAvailable(BlockKey key) {
            World world = server.getWorld(key.worldId());
            if (world == null) return false;
            return world.isChunkLoaded(Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16));
        }

        @Override
        public RootResolver.RootProbe<Inventory> inspect(BlockKey key) {
            World world = server.getWorld(key.worldId());
            if (world == null) return null;

            int chunkX = Math.floorDiv(key.x(), 16);
            int chunkZ = Math.floorDiv(key.z(), 16);
            if (!world.isChunkLoaded(chunkX, chunkZ)) return null;

            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            BlockState state = block.getState();
            String blockType = state.getType().getKey().toString();
            boolean unresolvedLoot = isUnresolvedLoot(state);
            boolean persistent = state instanceof BlockInventoryHolder;

            if (!(state instanceof BlockInventoryHolder holder)) {
                return new RootResolver.RootProbe<>(
                    key, blockType, null, false, unresolvedLoot, null, false
                );
            }

            if ("minecraft:ender_chest".equals(blockType)) {
                return new RootResolver.RootProbe<>(
                    key, blockType, null, false, false, null, false
                );
            }

            if (unresolvedLoot) {
                return new RootResolver.RootProbe<>(
                    key, blockType, null, true, true, null, false
                );
            }

            Inventory inventory = holder.getInventory();
            if (!(inventory instanceof DoubleChestInventory doubleChest)) {
                return new RootResolver.RootProbe<>(
                    key, blockType, inventory, persistent, unresolvedLoot, null, false
                );
            }

            BlockKey connectedHalf = connectedHalfKey(world, key, doubleChest);
            boolean connectedHalfLoaded = connectedHalf != null && isChunkLoaded(world, connectedHalf);
            return new RootResolver.RootProbe<>(
                key,
                blockType,
                inventory,
                persistent,
                unresolvedLoot,
                connectedHalf,
                connectedHalfLoaded
            );
        }

        private static boolean isUnresolvedLoot(BlockState state) {
            return state instanceof Lootable lootable && lootable.getLootTable() != null;
        }

        private static BlockKey connectedHalfKey(World world, BlockKey key, DoubleChestInventory doubleChest) {
            if (!(doubleChest.getLeftSide().getHolder() instanceof BlockInventoryHolder leftHolder)
                || !(doubleChest.getRightSide().getHolder() instanceof BlockInventoryHolder rightHolder)) {
                return null;
            }

            BlockKey left = toBlockKey(world.getUID(), leftHolder.getBlock());
            BlockKey right = toBlockKey(world.getUID(), rightHolder.getBlock());
            if (!world.getUID().equals(left.worldId()) || !world.getUID().equals(right.worldId())) {
                return null;
            }
            if (left.equals(key)) return right;
            if (right.equals(key)) return left;
            return null;
        }

        private static boolean isChunkLoaded(World world, BlockKey key) {
            return world.isChunkLoaded(Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16));
        }

        private static BlockKey toBlockKey(java.util.UUID worldId, Block block) {
            return new BlockKey(worldId, block.getX(), block.getY(), block.getZ());
        }
    }
}
