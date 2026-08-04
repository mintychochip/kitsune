package dev.jlo.kitsune.search;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.index.ContainerSnapshotter;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.protection.ProtectionRegistry;

public final class BukkitLiveRootAccess implements LiveRootAccess {
    private static final double EPSILON = 1e-9;
    private final Server server;
    private final ContainerSnapshotter snapshotter;
    private final ProtectionRegistry protectionRegistry;

    public BukkitLiveRootAccess(Server server, ContainerSnapshotter snapshotter, ProtectionRegistry protectionRegistry) {
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.snapshotter = Objects.requireNonNull(snapshotter, "Snapshotter must not be null");
        this.protectionRegistry = Objects.requireNonNull(protectionRegistry, "ProtectionRegistry must not be null");
    }

    @Override
    public Set<ChunkKey> loadedChunks(SearchContext context, int radius) {
        Objects.requireNonNull(context, "Context must not be null");
        if (radius < 0) return Set.of();
        World world = server.getWorld(context.origin().worldId());
        if (world == null) return Set.of();
        int minChunkX = Math.floorDiv(context.origin().x() - radius, 16);
        int maxChunkX = Math.floorDiv(context.origin().x() + radius, 16);
        int minChunkZ = Math.floorDiv(context.origin().z() - radius, 16);
        int maxChunkZ = Math.floorDiv(context.origin().z() + radius, 16);
        Set<ChunkKey> loaded = new LinkedHashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) loaded.add(new ChunkKey(world.getUID(), chunkX, chunkZ));
            }
        }
        return Set.copyOf(loaded);
    }

    @Override
    public AllowedRoot validate(SearchContext context, RootIdentity identity, int radius) {
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(identity, "Identity must not be null");
        if (radius < 0) return null;
        BlockKey origin = context.origin();
        BlockKey candidate = identity.key();
        if (!origin.worldId().equals(candidate.worldId())) return null;
        World world = server.getWorld(origin.worldId());
        if (world == null) return null;
        Player player = server.getPlayer(context.playerId());
        if (player == null || !player.isOnline()) return null;
        if (!player.getWorld().getUID().equals(origin.worldId())) return null;
        long dx = (long) candidate.x() - origin.x();
        long dy = (long) candidate.y() - origin.y();
        long dz = (long) candidate.z() - origin.z();
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (distance - radius > EPSILON) return null;
        int chunkX = Math.floorDiv(candidate.x(), 16);
        int chunkZ = Math.floorDiv(candidate.z(), 16);
        if (!world.isChunkLoaded(chunkX, chunkZ)) return null;

        ContainerSnapshotter.Result snapshot;
        try {
            snapshot = snapshotter.snapshot(candidate);
        } catch (RuntimeException failure) {
            return null;
        }
        if (snapshot == null || snapshot.status() != ContainerSnapshotter.Status.COMPLETE || snapshot.draft() == null) return null;
        ContainerDraft draft = snapshot.draft();
        if (!draft.key().equals(candidate)
            || !Objects.equals(draft.blockType(), identity.blockType())
            || !Arrays.equals(draft.fingerprint(), identity.fingerprint())) return null;
        try {
            Block block = world.getBlockAt(candidate.x(), candidate.y(), candidate.z());
            if (!(block.getState() instanceof org.bukkit.block.Container)) return null;
            AccessContext access = new AccessContext(player.getUniqueId(), player.getName(), candidate);
            if (!protectionRegistry.canAccess(access)) return null;
        } catch (RuntimeException failure) {
            return null;
        }
        return new AllowedRoot(identity, distance);
    }
}
