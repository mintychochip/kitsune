package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.AllowedRoot;
import dev.jlo.kitsune.search.LiveRootAccess;
import dev.jlo.kitsune.search.SearchContext;
import dev.jlo.kitsune.item.NestedItemWalkResult;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalChild;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Provides live container discovery and validation against a Fabric server.
 */
public final class FabricWorldAccess implements LiveRootAccess {
    private static final double EPSILON = 1e-9;
    private static final int DEFAULT_SCAN_CHUNK_RADIUS = 8;

    private final MinecraftServer server;
    private final FabricItemAccess items;
    private final NestedItemWalker<ItemStack> walker;

    /**
     * Creates access backed by the supplied server and item traversal limits.
     *
     * @param server server whose worlds and players are inspected
     * @param items item access used to fingerprint and describe stacks
     * @param maximumDepth maximum nested-item traversal depth
     * @param maximumStacksPerRoot maximum stacks visited for one root
     */
    public FabricWorldAccess(MinecraftServer server, FabricItemAccess items, int maximumDepth, int maximumStacksPerRoot) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.items = Objects.requireNonNull(items, "Item access must not be null");
        this.walker = new NestedItemWalker<>(
            new dev.jlo.kitsune.item.TraversalLimits(maximumDepth, maximumStacksPerRoot),
            items
        );
    }

    /**
     * Derives a stable identifier from a world's registry key.
     *
     * @param world world to identify
     * @return deterministic identifier for the world
     */
    public static UUID worldId(ServerWorld world) {
        return UUID.nameUUIDFromBytes(
            world.getRegistryKey().getValue().toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Scans loaded chunks around each online player for inventories.
     *
     * @return snapshots of discovered containers
     */
    public List<ContainerData> scanLoadedContainers() {
        Set<BlockKey> visited = new HashSet<>();
        List<ContainerData> containers = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getServerWorld();
            BlockPos origin = player.getBlockPos();
            int centerChunkX = origin.getX() >> 4;
            int centerChunkZ = origin.getZ() >> 4;
            for (int chunkX = centerChunkX - DEFAULT_SCAN_CHUNK_RADIUS;
                 chunkX <= centerChunkX + DEFAULT_SCAN_CHUNK_RADIUS;
                 chunkX++) {
                for (int chunkZ = centerChunkZ - DEFAULT_SCAN_CHUNK_RADIUS;
                     chunkZ <= centerChunkZ + DEFAULT_SCAN_CHUNK_RADIUS;
                     chunkZ++) {
                    if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) continue;
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (!(blockEntity instanceof Inventory)) continue;
                        BlockKey key = toBlockKey(world, blockEntity.getPos());
                        if (!visited.add(key)) continue;
                        snapshot(key).ifPresent(containers::add);
                    }
                }
            }
        }
        return List.copyOf(containers);
    }

    /**
     * Reads a current snapshot for a container key when its chunk and inventory are available.
     *
     * @param key block key identifying the container
     * @return the current snapshot, or empty when it is unavailable or unsupported
     */
    public java.util.Optional<ContainerData> snapshot(BlockKey key) {
        Objects.requireNonNull(key, "Key must not be null");
        ServerWorld world = findWorld(key.worldId());
        if (world == null || !isChunkLoaded(world, key)) return java.util.Optional.empty();
        BlockPos position = new BlockPos(key.x(), key.y(), key.z());
        BlockState state = world.getBlockState(position);
        BlockEntity blockEntity = world.getBlockEntity(position);
        if (!(blockEntity instanceof Inventory inventory)) return java.util.Optional.empty();
        if (inventory instanceof LootableInventory lootable && lootable.getLootTable() != null) {
            return java.util.Optional.empty();
        }

        Inventory logicalInventory = inventoryFor(world, state, position, inventory);
        if (logicalInventory == null) return java.util.Optional.empty();
        String blockType = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        MessageDigest digest = sha256();
        List<ItemDraft> leaves = new ArrayList<>();
        writeInt(digest, logicalInventory.size());
        for (int slot = 0; slot < logicalInventory.size(); slot++) {
            ItemStack stack = logicalInventory.getStack(slot);
            writeInt(digest, slot);
            if (stack == null || stack.isEmpty()) {
                digest.update((byte) 0);
                continue;
            }
            digest.update((byte) 1);
            byte[] fingerprint = items.fingerprint(stack);
            writeInt(digest, fingerprint.length);
            digest.update(fingerprint);
            NestedItemWalkResult walked = walker.walk(new TraversalChild<>(blockType, slot, stack.copy()));
            leaves.addAll(walked.leaves());
        }
        return java.util.Optional.of(new ContainerData(
            new ContainerDraft(key, blockType, digest.digest(), List.copyOf(leaves)),
            new ChunkKey(key.worldId(), Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16))
        ));
    }

    /**
     * Lists loaded chunks intersecting the horizontal radius around a search origin.
     *
     * @param context search origin and world
     * @param radius horizontal block radius
     * @return loaded chunks in the origin world
     */
    @Override
    public Set<ChunkKey> loadedChunks(SearchContext context, int radius) {
        Objects.requireNonNull(context, "Context must not be null");
        if (radius < 0) return Set.of();
        ServerWorld world = findWorld(context.origin().worldId());
        if (world == null) return Set.of();
        int minChunkX = Math.floorDiv(context.origin().x() - radius, 16);
        int maxChunkX = Math.floorDiv(context.origin().x() + radius, 16);
        int minChunkZ = Math.floorDiv(context.origin().z() - radius, 16);
        int maxChunkZ = Math.floorDiv(context.origin().z() + radius, 16);
        Set<ChunkKey> loaded = new LinkedHashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    loaded.add(new ChunkKey(worldId(world), chunkX, chunkZ));
                }
            }
        }
        return Set.copyOf(loaded);
    }

    /**
     * Verifies that an indexed root still exists, matches its identity, and is within range.
     *
     * @param context search origin
     * @param identity indexed root identity to verify
     * @param radius maximum distance in blocks
     * @return an allowed root, or {@code null} when validation fails
     */
    @Override
    public AllowedRoot validate(SearchContext context, RootIdentity identity, int radius) {
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(identity, "Identity must not be null");
        if (radius < 0 || !context.origin().worldId().equals(identity.key().worldId())) return null;
        long dx = (long) identity.key().x() - context.origin().x();
        long dy = (long) identity.key().y() - context.origin().y();
        long dz = (long) identity.key().z() - context.origin().z();
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (distance - radius > EPSILON) return null;
        java.util.Optional<ContainerData> current = snapshot(identity.key());
        if (current.isEmpty()) return null;
        ContainerDraft draft = current.get().draft();
        if (!draft.blockType().equals(identity.blockType())) return null;
        return new AllowedRoot(identity, distance);
    }

    private ServerWorld findWorld(UUID id) {
        for (ServerWorld world : server.getWorlds()) {
            if (worldId(world).equals(id)) return world;
        }
        return null;
    }

    private static boolean isChunkLoaded(ServerWorld world, BlockKey key) {
        return world.getChunkManager().isChunkLoaded(
            Math.floorDiv(key.x(), 16),
            Math.floorDiv(key.z(), 16)
        );
    }

    private static Inventory inventoryFor(ServerWorld world, BlockState state, BlockPos position, Inventory fallback) {
        if (state.getBlock() instanceof ChestBlock chest) {
            Inventory chestInventory = ChestBlock.getInventory(chest, state, world, position, false);
            if (chestInventory != null) return chestInventory;
        }
        return fallback;
    }

    private static BlockKey toBlockKey(ServerWorld world, BlockPos position) {
        return new BlockKey(worldId(world), position.getX(), position.getY(), position.getZ());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    /**
     * Pair of a container draft and the chunk containing its block.
     *
     * @param draft current container contents and fingerprint
     * @param chunk containing chunk
     */
    public record ContainerData(ContainerDraft draft, ChunkKey chunk) {
        public ContainerData {
            Objects.requireNonNull(draft, "Draft must not be null");
            Objects.requireNonNull(chunk, "Chunk must not be null");
        }
    }
}
