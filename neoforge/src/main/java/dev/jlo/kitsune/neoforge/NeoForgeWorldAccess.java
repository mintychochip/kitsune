package dev.jlo.kitsune.neoforge;

import dev.jlo.kitsune.item.NestedItemWalkResult;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalChild;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.AllowedRoot;
import dev.jlo.kitsune.search.LiveRootAccess;
import dev.jlo.kitsune.search.SearchContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * NeoForge-backed world access that fingerprints containers and exposes
 * live root access for the Kitsune search index.
 */
public final class NeoForgeWorldAccess implements LiveRootAccess {
    private static final double EPSILON = 1e-9;
    private static final int DEFAULT_SCAN_CHUNK_RADIUS = 8;

    private final MinecraftServer server;
    private final NeoForgeItemAccess items;
    private final NestedItemWalker<ItemStack> walker;

    /**
     * Creates world access bound to the given server.
     *
     * @param server               server whose levels are queried
     * @param items                item access used to fingerprint items
     * @param maximumDepth         maximum nesting depth when walking container contents
     * @param maximumStacksPerRoot maximum stack count walked per root container
     * @throws NullPointerException if {@code server} or {@code items} is {@code null}
     */
    public NeoForgeWorldAccess(MinecraftServer server, NeoForgeItemAccess items, int maximumDepth, int maximumStacksPerRoot) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.items = Objects.requireNonNull(items, "Item access must not be null");
        this.walker = new NestedItemWalker<>(
            new dev.jlo.kitsune.item.TraversalLimits(maximumDepth, maximumStacksPerRoot),
            items
        );
    }

    /**
     * Returns a stable, deterministic identifier for a level based on its dimension name.
     *
     * @param level level to identify
     * @return the level's world identifier
     */
    public static UUID worldId(ServerLevel level) {
        return UUID.nameUUIDFromBytes(
            level.dimension().location().toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Scans containers in the chunks surrounding each online player and returns
     * the resulting container data.
     *
     * @return immutable list of container data for scanned loaded containers
     */
    public List<ContainerData> scanLoadedContainers() {
        Set<BlockKey> visited = new HashSet<>();
        List<ContainerData> containers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            int centerChunkX = origin.getX() >> 4;
            int centerChunkZ = origin.getZ() >> 4;
            for (int chunkX = centerChunkX - DEFAULT_SCAN_CHUNK_RADIUS;
                 chunkX <= centerChunkX + DEFAULT_SCAN_CHUNK_RADIUS;
                 chunkX++) {
                for (int chunkZ = centerChunkZ - DEFAULT_SCAN_CHUNK_RADIUS;
                     chunkZ <= centerChunkZ + DEFAULT_SCAN_CHUNK_RADIUS;
                     chunkZ++) {
                    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null) continue;
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (!(blockEntity instanceof Container)) continue;
                        BlockKey key = toBlockKey(level, blockEntity.getBlockPos());
                        if (!visited.add(key)) continue;
                        snapshot(key).ifPresent(containers::add);
                    }
                }
            }
        }
        return List.copyOf(containers);
    }

    /**
     * Snapshots the container at the given block key into a container draft.
     *
     * @param key block key identifying the container
     * @return the container data, or {@link Optional#empty()} if the world or
     *         chunk is not loaded, the block is not a container, it is a loot
     *         container, or its logical inventory is unavailable
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Optional<ContainerData> snapshot(BlockKey key) {
        Objects.requireNonNull(key, "Key must not be null");
        ServerLevel level = findWorld(key.worldId());
        if (level == null || !isChunkLoaded(level, key)) return Optional.empty();
        BlockPos position = new BlockPos(key.x(), key.y(), key.z());
        BlockState state = level.getBlockState(position);
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (!(blockEntity instanceof Container inventory)) return Optional.empty();
        if (blockEntity instanceof RandomizableContainerBlockEntity lootable && lootable.getLootTable() != null) {
            return Optional.empty();
        }

        Container logicalInventory = inventoryFor(level, state, position, inventory);
        if (logicalInventory == null) return Optional.empty();
        String blockType = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        MessageDigest digest = sha256();
        List<ItemDraft> leaves = new ArrayList<>();
        writeInt(digest, logicalInventory.getContainerSize());
        for (int slot = 0; slot < logicalInventory.getContainerSize(); slot++) {
            ItemStack stack = logicalInventory.getItem(slot);
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
        return Optional.of(new ContainerData(
            new ContainerDraft(key, blockType, digest.digest(), List.copyOf(leaves)),
            new ChunkKey(key.worldId(), Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16))
        ));
    }

    /**
     * Returns the chunks loaded within the given radius of the search origin.
     *
     * @param context search context whose origin anchors the search
     * @param radius  block radius around the origin to consider
     * @return immutable set of loaded chunk keys within radius, or an empty set
     *         if the radius is negative or the origin world is not loaded
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    public Set<ChunkKey> loadedChunks(SearchContext context, int radius) {
        Objects.requireNonNull(context, "Context must not be null");
        if (radius < 0) return Set.of();
        ServerLevel level = findWorld(context.origin().worldId());
        if (level == null) return Set.of();
        int minChunkX = Math.floorDiv(context.origin().x() - radius, 16);
        int maxChunkX = Math.floorDiv(context.origin().x() + radius, 16);
        int minChunkZ = Math.floorDiv(context.origin().z() - radius, 16);
        int maxChunkZ = Math.floorDiv(context.origin().z() + radius, 16);
        Set<ChunkKey> loaded = new LinkedHashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    loaded.add(new ChunkKey(worldId(level), chunkX, chunkZ));
                }
            }
        }
        return Set.copyOf(loaded);
    }

    /**
     * Validates that a root is currently allowed, checking world, distance,
     * and block type.
     *
     * @param context  search context whose origin anchors the search
     * @param identity candidate root identity to validate
     * @param radius   maximum block distance from the origin
     * @return an allowed root with its distance, or {@code null} if the root is
     *         not in the origin world, outside the radius, or no longer present
     *         with matching block type
     * @throws NullPointerException if {@code context} or {@code identity} is {@code null}
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
        Optional<ContainerData> current = snapshot(identity.key());
        if (current.isEmpty()) return null;
        ContainerDraft draft = current.get().draft();
        if (!draft.blockType().equals(identity.blockType())) return null;
        return new AllowedRoot(identity, distance);
    }

    private ServerLevel findWorld(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (worldId(level).equals(id)) return level;
        }
        return null;
    }

    private static boolean isChunkLoaded(ServerLevel level, BlockKey key) {
        return level.getChunkSource().hasChunk(Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16));
    }

    private static Container inventoryFor(ServerLevel level, BlockState state, BlockPos position, Container fallback) {
        if (state.getBlock() instanceof ChestBlock chest) {
            Container chestInventory = ChestBlock.getContainer(chest, state, level, position, false);
            if (chestInventory != null) return chestInventory;
        }
        return fallback;
    }

    private static BlockKey toBlockKey(ServerLevel level, BlockPos position) {
        return new BlockKey(worldId(level), position.getX(), position.getY(), position.getZ());
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
     * Container data produced from a block-key snapshot, pairing the container
     * draft with the chunk in which it resides.
     *
     * @param draft container draft holding key, block type, fingerprint, and items
     * @param chunk chunk key locating the container in the world
     */
    public record ContainerData(ContainerDraft draft, ChunkKey chunk) {
        public ContainerData {
            Objects.requireNonNull(draft, "Draft must not be null");
            Objects.requireNonNull(chunk, "Chunk must not be null");
        }
    }
}
