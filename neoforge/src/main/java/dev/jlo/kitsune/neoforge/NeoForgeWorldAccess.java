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

public final class NeoForgeWorldAccess implements LiveRootAccess {
    private static final double EPSILON = 1e-9;
    private static final int DEFAULT_SCAN_CHUNK_RADIUS = 8;

    private final MinecraftServer server;
    private final NeoForgeItemAccess items;
    private final NestedItemWalker<ItemStack> walker;

    public NeoForgeWorldAccess(MinecraftServer server, NeoForgeItemAccess items, int maximumDepth, int maximumStacksPerRoot) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.items = Objects.requireNonNull(items, "Item access must not be null");
        this.walker = new NestedItemWalker<>(
            new dev.jlo.kitsune.item.TraversalLimits(maximumDepth, maximumStacksPerRoot),
            items
        );
    }

    public static UUID worldId(ServerLevel level) {
        return UUID.nameUUIDFromBytes(
            level.dimension().location().toString().getBytes(StandardCharsets.UTF_8)
        );
    }

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
        if (!java.util.Arrays.equals(draft.fingerprint(), identity.fingerprint())) return null;
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

    public record ContainerData(ContainerDraft draft, ChunkKey chunk) {
        public ContainerData {
            Objects.requireNonNull(draft, "Draft must not be null");
            Objects.requireNonNull(chunk, "Chunk must not be null");
        }
    }
}
