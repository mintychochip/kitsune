package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.RootIdentity;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/**
 * Maintains the in-memory index of loaded container roots, driving discovery,
 * snapshots, replacement writes, and reconciliation against the repository.
 */
public final class ContainerIndex implements AutoCloseable {
    private final ContainerIndexQueue queue;
    private final RootResolver<Inventory> rootResolver;
    private final ContainerSnapshotter snapshotter;
    private final IndexWorker worker;
    private final IndexRepository repository;
    private final EmbeddingProvider embeddingProvider;
    private final DirtyRootTracker tracker = new DirtyRootTracker();
    private final LongSupplier currentTick;
    private final int rootsPerTick;
    private final int reconciliationPeriodTicks;
    private final Consumer<BlockKey> rootInvalidated;
    private final CachedEmbeddingResolver embeddings = new CachedEmbeddingResolver();
    private final AtomicLong chunkRevision = new AtomicLong();
    private final Map<ChunkKey, Chunk> loadedChunks = new LinkedHashMap<>();
    private final Map<ChunkKey, Set<BlockKey>> rootsByChunk =
        new LinkedHashMap<>();
    private final Set<BlockKey> loadedRoots = new LinkedHashSet<>();
    private final Set<BlockKey> replacementRequired = new LinkedHashSet<>();
    private final Queue<CompletionAction> completions =
        new ConcurrentLinkedQueue<>();
    private ReconciliationCursor reconciliationCursor;
    private long nextReconciliationTick;
    private volatile boolean accepting = true;

    /**
     * Creates an index bound to the supplied resolution, snapshotting, indexing,
     * and repository services with the given per-tick and reconciliation limits.
     *
     * @param rootResolver resolves logical container inventories
     * @param snapshotter captures container snapshots
     * @param worker performs background index work
     * @param repository persistent index repository
     * @param embeddingProvider embeds item descriptors
     * @param chunksPerTick maximum chunks discovered per tick
     * @param rootsPerTick maximum roots processed per tick
     * @param reconciliationPeriodTicks ticks between reconciliation passes
     * @param currentTick supplies the current server tick
     * @param rootInvalidated callback invoked when a root becomes invalid
     */
    public ContainerIndex(
        RootResolver<Inventory> rootResolver,
        ContainerSnapshotter snapshotter,
        IndexWorker worker,
        IndexRepository repository,
        EmbeddingProvider embeddingProvider,
        int chunksPerTick,
        int rootsPerTick,
        int reconciliationPeriodTicks,
        LongSupplier currentTick,
        Consumer<BlockKey> rootInvalidated
    ) {
        if (rootsPerTick <= 0) {
            throw new IllegalArgumentException("Roots per tick must be positive");
        }
        if (reconciliationPeriodTicks <= 0) {
            throw new IllegalArgumentException(
                "Reconciliation period must be positive"
            );
        }
        this.queue = new ContainerIndexQueue(chunksPerTick, rootsPerTick);
        this.rootResolver = Objects.requireNonNull(rootResolver, "Resolver");
        this.snapshotter = Objects.requireNonNull(snapshotter, "Snapshotter");
        this.worker = Objects.requireNonNull(worker, "Worker");
        this.repository = Objects.requireNonNull(repository, "Repository");
        this.embeddingProvider = Objects.requireNonNull(
            embeddingProvider,
            "Embedding provider"
        );
        this.currentTick = Objects.requireNonNull(
            currentTick,
            "Current tick supplier"
        );
        this.rootInvalidated = Objects.requireNonNull(
            rootInvalidated,
            "Root invalidation callback"
        );
        this.rootsPerTick = rootsPerTick;
        this.reconciliationPeriodTicks = reconciliationPeriodTicks;
        long initialTick = requireTick(currentTick.getAsLong());
        this.nextReconciliationTick = addOrMax(
            initialTick,
            reconciliationPeriodTicks
        );
    }

    /**
     * Prevents new work from being accepted by the index.
     */
    public void stopAccepting() {
        accepting = false;
        queue.stopAccepting();
    }

    /**
     * Returns a stage completing when the requested chunks are ready or a timeout elapses.
     *
     * @param chunks chunks to await readiness for
     * @param timeout maximum wait duration
     * @return a stage completed when readiness is achieved
     */
    public CompletionStage<Void> awaitReady(
        Set<ChunkKey> chunks,
        Duration timeout
    ) {
        if (!accepting) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Container index is stopped")
            );
        }
        return queue.awaitReady(chunks, timeout);
    }

    /**
     * Registers a loaded chunk for discovery.
     *
     * @param chunk loaded chunk
     */
    public void onChunkLoaded(Chunk chunk) {
        Objects.requireNonNull(chunk, "Chunk");
        if (!accepting) return;
        ChunkKey key = new ChunkKey(
            chunk.getWorld().getUID(),
            chunk.getX(),
            chunk.getZ()
        );
        loadedChunks.put(key, chunk);
        Set<BlockKey> previousRoots = rootsByChunk.remove(key);
        if (previousRoots != null) {
            previousRoots.forEach(rootInvalidated);
        }
        rebuildLoadedRoots();
        rootsByChunk.put(key, new LinkedHashSet<>());
        queue.enqueueChunk(key);
    }

    /**
     * Unregisters a chunk and marks its roots unavailable.
     *
     * @param chunk unloaded chunk key
     */
    public void onChunkUnloaded(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "Chunk key");
        loadedChunks.remove(chunk);
        Set<BlockKey> unavailableRoots = rootsByChunk.remove(chunk);
        if (unavailableRoots != null && !unavailableRoots.isEmpty()) {
            unavailableRoots.forEach(rootInvalidated);
            for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
                chunkRoots.removeAll(unavailableRoots);
            }
        }
        rebuildLoadedRoots();
        queue.unload(chunk);
        worker.submit(() -> {
            repository.setChunkAvailable(
                chunk,
                false,
                chunkRevision.incrementAndGet()
            );
            return null;
        });
    }

    /**
     * Marks a root as dirty, scheduling it for resnapshotting.
     *
     * @param root root to invalidate
     */
    public void markDirty(BlockKey root) {
        Objects.requireNonNull(root, "Root key");
        if (accepting) {
            tracker.markDirty(root, requireTick(currentTick.getAsLong()));
        }
    }

    public void markTransferDirty(BlockKey root) {
        Objects.requireNonNull(root, "Root key");
        if (accepting) {
            tracker.markTransferDirty(root, requireTick(currentTick.getAsLong()));
        }
    }

    /**
     * Marks a root for deletion from the index and repository.
     *
     * @param root root to delete
     */
    public void delete(BlockKey root) {
        Objects.requireNonNull(root, "Root key");
        if (!accepting) return;
        removeLoadedRoot(root);
        replacementRequired.add(root);
        tracker.markDeleted(root, requireTick(currentTick.getAsLong()));
    }

    /**
     * Marks the canonical root of a holder's inventory dirty, when determinable.
     *
     * @param holder inventory holder to invalidate
     */
    public void markDirty(InventoryHolder holder) {
        canonicalRoot(holder).ifPresent(this::markDirty);
    }

    public void markTransferDirty(InventoryHolder holder) {
        canonicalRoot(holder).ifPresent(this::markTransferDirty);
    }

    /**
     * Marks the canonical root of a holder's inventory for deletion.
     *
     * @param holder inventory holder to delete
     */
    public void delete(InventoryHolder holder) {
        canonicalRoot(holder).ifPresent(this::delete);
    }

    /**
     * Returns whether a chunk has been fully processed and made ready.
     *
     * @param chunk chunk to query
     * @return {@code true} when the chunk is ready
     */
    public boolean isChunkReady(ChunkKey chunk) {
        return queue.isReady(chunk);
    }

    /**
     * Returns an immutable view of currently loaded root keys.
     *
     * @return loaded root keys
     */
    public Set<BlockKey> loadedRoots() {
        return Set.copyOf(loadedRoots);
    }

    /**
     * Advances the index by one server tick: drains completions, reconciles,
     * claims tracker work, and processes the queued chunk and root batches.
     */
    public void tick() {
        if (!accepting) return;
        long tick = requireTick(currentTick.getAsLong());
        drainCompletions(tick);
        reconcile(tick);
        claimTrackerWork(tick);

        ContainerIndexQueue.TickBatch batch = queue.claimTick();
        for (ContainerIndexQueue.ChunkWork chunkWork : batch.chunks()) {
            discoverChunk(chunkWork, tick);
        }
        for (ContainerIndexQueue.RootWork rootWork : batch.roots()) {
            processRoot(rootWork, tick);
        }
    }

    private void discoverChunk(
        ContainerIndexQueue.ChunkWork chunkWork,
        long tick
    ) {
        Chunk chunk = loadedChunks.get(chunkWork.key());
        if (chunk == null || !chunk.isLoaded()) {
            queue.unload(chunkWork.key());
            return;
        }

        try {
            World world = chunk.getWorld();
            Set<BlockKey> discovered = new LinkedHashSet<>();
            Set<BlockKey> resolved = new LinkedHashSet<>();
            for (var state : chunk.getTileEntities()) {
                if (!(state instanceof BlockInventoryHolder)) continue;
                BlockKey blockKey = new BlockKey(
                    world.getUID(),
                    state.getX(),
                    state.getY(),
                    state.getZ()
                );
                RootResolver.Resolution<Inventory> resolution =
                    rootResolver.resolve(blockKey);
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

            Set<BlockKey> previousRoots = rootsByChunk.put(
                chunkWork.key(),
                resolved
            );
            if (previousRoots != null) {
                previousRoots.stream()
                    .filter(root -> !resolved.contains(root))
                    .forEach(rootInvalidated);
            }
            rebuildLoadedRoots();
            for (BlockKey root : discovered) {
                tracker.markDirty(root, tick);
            }
            queue.discovered(chunkWork, List.copyOf(discovered));
            submitChunkAvailability(chunkWork);
        } catch (Throwable failure) {
            queue.completeChunkWrite(chunkWork, failure);
        }
    }

    private void submitChunkAvailability(
        ContainerIndexQueue.ChunkWork chunkWork
    ) {
        worker.submit(() -> {
            repository.setChunkAvailable(
                chunkWork.key(),
                true,
                chunkRevision.incrementAndGet()
            );
            return null;
        }).whenComplete((ignored, failure) -> completions.add(
            new ChunkCompletion(chunkWork, failure)
        ));
    }

    private void claimTrackerWork(long tick) {
        for (PendingRoot work : tracker.claimDue(tick)) {
            queue.markRoot(work);
        }
    }

    private void reconcile(long tick) {
        if (reconciliationCursor == null && tick >= nextReconciliationTick) {
            reconciliationCursor = new ReconciliationCursor(loadedRoots);
            nextReconciliationTick = addOrMax(
                tick,
                reconciliationPeriodTicks
            );
        }
        if (reconciliationCursor == null) return;
        for (BlockKey root : reconciliationCursor.next(rootsPerTick)) {
            tracker.markDirty(root, tick);
        }
        if (reconciliationCursor.complete()) reconciliationCursor = null;
    }

    private void processRoot(
        ContainerIndexQueue.RootWork rootWork,
        long tick
    ) {
        PendingRoot pending = pending(rootWork);
        if (!tracker.isCurrent(pending)) {
            queue.completeRootWrite(rootWork, null);
            return;
        }
        if (pending.action() == PendingAction.DELETE) {
            submitDelete(rootWork);
            return;
        }
        if (!loadedChunks.containsKey(rootWork.key().chunkKey())) {
            removeLoadedRoot(rootWork.key());
            handleRootCompletion(rootWork, null, null, tick);
            return;
        }


        try {
            ContainerSnapshotter.Result result = snapshotter.snapshot(
                rootWork.key()
            );
            if (result.status() == ContainerSnapshotter.Status.COMPLETE) {
                submitReplacement(rootWork, result.draft());
            } else if (
                result.status() == ContainerSnapshotter.Status.UNAVAILABLE
            ) {
                removeLoadedRoot(rootWork.key());
                handleRootCompletion(rootWork, null, null, tick);
            } else {
                removeLoadedRoot(rootWork.key());
                submitDelete(rootWork);
            }
        } catch (Throwable failure) {
            handleRootCompletion(rootWork, null, failure, tick);
        }
    }

    private void submitDelete(ContainerIndexQueue.RootWork rootWork) {
        worker.submit(() -> {
            repository.deleteRoot(rootWork.key());
            return null;
        }).whenComplete((ignored, failure) -> completions.add(
            new RootCompletion(rootWork, null, failure)
        ));
    }

    private void submitReplacement(
        ContainerIndexQueue.RootWork rootWork,
        ContainerDraft draft
    ) {
        boolean forceReplacement = replacementRequired.contains(rootWork.key());
        worker.submit(() -> {
            Optional<RootIdentity> existing = repository.findRoot(draft.key());
            if (
                !forceReplacement
                && rootWork.key().equals(draft.key())
                && existing.isPresent()
                && existing.get().blockType().equals(draft.blockType())
                && Arrays.equals(existing.get().fingerprint(), draft.fingerprint())
            ) {
                return null;
            }

            List<IndexedItem> items = embeddings.resolve(
                draft.items(),
                embeddingProvider,
                repository
            );
            ContainerSnapshot snapshot = new ContainerSnapshot(
                draft.key(),
                draft.blockType(),
                draft.fingerprint(),
                items
            );
            repository.replaceRoot(snapshot, rootWork.revision());
            if (!draft.key().equals(rootWork.key())) {
                repository.deleteRoot(rootWork.key());
            }
            return null;
        }).whenComplete((ignored, failure) -> completions.add(
            new RootCompletion(rootWork, draft.key(), failure)
        ));
    }

    private void restoreLoadedRoot(BlockKey root) {
        ChunkKey chunk = root.chunkKey();
        if (!loadedChunks.containsKey(chunk)) return;
        Set<BlockKey> chunkRoots = rootsByChunk.get(chunk);
        if (chunkRoots == null) return;
        chunkRoots.add(root);
        rebuildLoadedRoots();
    }

    private void drainCompletions(long tick) {
        CompletionAction action;
        while ((action = completions.poll()) != null) {
            if (action instanceof ChunkCompletion chunk) {
                queue.completeChunkWrite(chunk.work(), chunk.failure());
            } else if (action instanceof RootCompletion root) {
                handleRootCompletion(
                    root.work(),
                    root.canonicalRoot(),
                    root.failure(),
                    tick
                );
            }
        }
    }

    private void handleRootCompletion(
        ContainerIndexQueue.RootWork rootWork,
        BlockKey canonicalRoot,
        Throwable failure,
        long tick
    ) {
        PendingRoot pending = pending(rootWork);
        if (!tracker.isCurrent(pending)) {
            queue.completeRootWrite(rootWork, null);
            return;
        }
        if (failure == null) {
            replacementRequired.remove(rootWork.key());
            tracker.complete(pending, tick);
            if (
                pending.action() == PendingAction.SNAPSHOT
                && canonicalRoot != null
            ) {
                if (!rootWork.key().equals(canonicalRoot)) {
                    removeLoadedRoot(rootWork.key());
                }
                restoreLoadedRoot(canonicalRoot);
            }
            queue.completeRootWrite(rootWork, null);
        } else if (tracker.fail(pending, tick)) {
            queue.retryRootWrite(rootWork);
        } else {
            queue.completeRootWrite(rootWork, failure);
        }
    }

    private Optional<BlockKey> canonicalRoot(InventoryHolder holder) {
        if (holder == null) return Optional.empty();
        if (holder instanceof DoubleChest doubleChest) {
            Optional<BlockKey> left = canonicalRoot(doubleChest.getLeftSide());
            return left.isPresent()
                ? left
                : canonicalRoot(doubleChest.getRightSide());
        }
        if (!(holder instanceof BlockInventoryHolder blockHolder)) {
            return Optional.empty();
        }
        var block = blockHolder.getBlock();
        BlockKey candidate = new BlockKey(
            block.getWorld().getUID(),
            block.getX(),
            block.getY(),
            block.getZ()
        );
        RootResolver.Resolution<Inventory> resolution =
            rootResolver.resolve(candidate);
        return switch (resolution.status()) {
            case RESOLVED -> Optional.of(resolution.key());
            case UNRESOLVED_LOOT -> Optional.of(candidate);
            case MISSING, UNAVAILABLE, UNSUPPORTED -> Optional.empty();
        };
    }

    private void removeLoadedRoot(BlockKey root) {
        for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
            chunkRoots.remove(root);
        }
        rebuildLoadedRoots();
        rootInvalidated.accept(root);
    }

    private void rebuildLoadedRoots() {
        loadedRoots.clear();
        for (Set<BlockKey> chunkRoots : rootsByChunk.values()) {
            loadedRoots.addAll(chunkRoots);
        }
    }

    private static PendingRoot pending(
        ContainerIndexQueue.RootWork rootWork
    ) {
        return new PendingRoot(
            rootWork.key(),
            rootWork.revision(),
            rootWork.action()
        );
    }

    private static long requireTick(long tick) {
        if (tick < 0) {
            throw new IllegalStateException(
                "Current tick supplier returned a negative tick"
            );
        }
        return tick;
    }

    private static long addOrMax(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Stops accepting work and releases in-memory state.
     */
    @Override
    public void close() {
        stopAccepting();
        completions.clear();
        loadedChunks.clear();
        rootsByChunk.clear();
        loadedRoots.clear();
        reconciliationCursor = null;
    }

    private sealed interface CompletionAction
        permits ChunkCompletion, RootCompletion {}

    private record ChunkCompletion(
        ContainerIndexQueue.ChunkWork work,
        Throwable failure
    ) implements CompletionAction {}

    private record RootCompletion(
        ContainerIndexQueue.RootWork work,
        BlockKey canonicalRoot,
        Throwable failure
    ) implements CompletionAction {}
}
