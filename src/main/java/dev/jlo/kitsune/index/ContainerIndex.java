
package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDraft;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

public final class ContainerIndex implements AutoCloseable {
    private final ContainerIndexQueue queue;
    private final RootResolver<Inventory> rootResolver;
    private final ContainerSnapshotter snapshotter;
    private final IndexWorker worker;
    private final IndexRepository repository;
    private final EmbeddingProvider embeddingProvider;
    private final AtomicLong revisionCounter;
    private final Map<ChunkKey, Chunk> loadedChunks = new LinkedHashMap<>();
    private final Map<ChunkKey, Set<BlockKey>> rootsByChunk = new LinkedHashMap<>();
    private final Set<BlockKey> loadedRoots = new LinkedHashSet<>();
    private volatile boolean accepting = true;

    public ContainerIndex(RootResolver<Inventory> rootResolver,
                          ContainerSnapshotter snapshotter,
                          IndexWorker worker,
                          IndexRepository repository,
                          EmbeddingProvider embeddingProvider,
                          int chunksPerTick,
                          int rootsPerTick) {
        this.queue = new ContainerIndexQueue(chunksPerTick, rootsPerTick);
        this.rootResolver = Objects.requireNonNull(rootResolver, "Resolver");
        this.snapshotter = Objects.requireNonNull(snapshotter, "Snapshotter");
        this.worker = Objects.requireNonNull(worker, "Worker");
        this.repository = Objects.requireNonNull(repository, "Repository");
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "Embedding provider");
        this.revisionCounter = new AtomicLong();
    }

    public void stopAccepting() {
        this.accepting = false;
    }

    public CompletionStage<Void> awaitReady(Set<ChunkKey> chunks, Duration timeout) {
        return queue.awaitReady(chunks, timeout);
    }

    public void onChunkLoaded(Chunk chunk) {
        if (!accepting) {
            return;
        }

        Objects.requireNonNull(chunk, "Chunk");
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        loadedChunks.put(key, chunk);
        rootsByChunk.remove(key);
        rebuildLoadedRoots();
        rootsByChunk.put(key, new LinkedHashSet<>());
        queue.enqueueChunk(key);
    }

    public void onChunkUnloaded(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "Chunk key");
        loadedChunks.remove(chunk);
        Set<BlockKey> unavailableRoots = rootsByChunk.remove(chunk);
        if (unavailableRoots != null && !unavailableRoots.isEmpty()) {
            for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
                chunkRoots.removeAll(unavailableRoots);
            }
        }
        rebuildLoadedRoots();
        queue.unload(chunk);
        worker.submit(() -> {
            repository.setChunkAvailable(chunk, false, revisionCounter.incrementAndGet());
            return null;
        });
    }

    public void markDirty(BlockKey root) {
        Objects.requireNonNull(root, "Root key");
        if (accepting) queue.markDirty(root);
    }

    public void delete(BlockKey root) {
        Objects.requireNonNull(root, "Root key");
        if (!accepting) return;
        removeLoadedRoot(root);

        worker.submit(() -> {
            repository.deleteRoot(root);
            return null;
        });
    }

    public boolean isChunkReady(ChunkKey chunk) {
        return queue.isReady(chunk);
    }

    public Set<BlockKey> loadedRoots() {
        return Set.copyOf(loadedRoots);
    }

    public void tick() {
        if (!accepting) return;
        ContainerIndexQueue.TickBatch tick = queue.claimTick();

        for (ContainerIndexQueue.ChunkWork chunkWork : tick.chunks()) {
            Chunk chunk = loadedChunks.get(chunkWork.key());
            if (chunk == null || !chunk.isLoaded()) {
                queue.unload(chunkWork.key());
                continue;
            }

            try {
                World world = chunk.getWorld();
                Set<BlockKey> discovered = new LinkedHashSet<>();
                Set<BlockKey> resolved = new LinkedHashSet<>();

                for (var state : chunk.getTileEntities(false)) {
                    if (!(state instanceof BlockInventoryHolder holder)) {
                        continue;
                    }

                    BlockKey blockKey = new BlockKey(world.getUID(), state.getX(), state.getY(), state.getZ());
                    RootResolver.Resolution<Inventory> resolution = rootResolver.resolve(blockKey);
                    switch (resolution.status()) {
                        case RESOLVED -> {
                            discovered.add(resolution.key());
                            resolved.add(resolution.key());
                        }
                        case MISSING, UNAVAILABLE, UNRESOLVED_LOOT ->
                            discovered.add(blockKey);
                        case UNSUPPORTED -> {
                            // Not a persistent searchable root.
                        }
                    }
                }

                rootsByChunk.put(chunkWork.key(), resolved);
                rebuildLoadedRoots();

                queue.discovered(chunkWork, new ArrayList<>(discovered));
                submitChunkWrite(chunkWork);
            } catch (Throwable failure) {
                queue.completeChunkWrite(chunkWork, failure);
            }
        }

        for (ContainerIndexQueue.RootWork rootWork : tick.roots()) {
            processRoot(rootWork);
        }
    }

    private void submitChunkWrite(ContainerIndexQueue.ChunkWork chunkWork) {
        worker.submit(() -> {
            repository.setChunkAvailable(chunkWork.key(), true, revisionCounter.incrementAndGet());
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                queue.completeChunkWrite(chunkWork, failure);
            } else {
                queue.completeChunkWrite(chunkWork, null);
            }
        });
    }

    private void processRoot(ContainerIndexQueue.RootWork rootWork) {
        try {
            ContainerSnapshotter.Result result = snapshotter.snapshot(rootWork.key());
            if (result.status() != ContainerSnapshotter.Status.COMPLETE) {
                removeLoadedRoot(rootWork.key());
                deleteRoot(rootWork);
                return;
            }
            submitRootReplacement(rootWork, result.draft());
        } catch (Throwable failure) {
            queue.completeRootWrite(rootWork, failure);
        }
    }

    private void deleteRoot(ContainerIndexQueue.RootWork rootWork) {
        worker.submit(() -> {
            repository.deleteRoot(rootWork.key());
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                queue.completeRootWrite(rootWork, failure);
            } else {
                queue.completeRootWrite(rootWork, null);
            }
        });
    }

    private void submitRootReplacement(ContainerIndexQueue.RootWork rootWork, ContainerDraft draft) {
        worker.submit(() -> {
            List<IndexedItem> items = new ArrayList<>(draft.items().size());
            for (ItemDraft itemDraft : draft.items()) {
                items.add(new IndexedItem(
                    itemDraft.path(),
                    itemDraft.amount(),
                    itemDraft.descriptor(),
                    embeddingProvider.embed(itemDraft.descriptor())
                ));
            }
            ContainerSnapshot snapshot = new ContainerSnapshot(
                draft.key(),
                draft.blockType(),
                draft.fingerprint(),
                items
            );
            repository.replaceRoot(snapshot, revisionCounter.incrementAndGet());
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                queue.completeRootWrite(rootWork, failure);
            } else {
                queue.completeRootWrite(rootWork, null);
            }
        });
    }

    private void removeLoadedRoot(BlockKey root) {
        for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
            chunkRoots.remove(root);
        }
        rebuildLoadedRoots();
    }

    private void rebuildLoadedRoots() {
        loadedRoots.clear();
        for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
            loadedRoots.addAll(chunkRoots);
        }
    }

    @Override
    public void close() {
        stopAccepting();
        loadedChunks.clear();
        rootsByChunk.clear();
        loadedRoots.clear();
    }
}
