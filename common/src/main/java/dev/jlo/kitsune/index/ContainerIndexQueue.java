package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-tick work queue coordinating chunk and root indexing.
 *
 * <p>Tracks chunk generations, schedules chunk writes and root writes against a per-tick
 * budget, and exposes readiness promises so callers can await a chunk reaching its final
 * indexed state. All mutating operations are synchronized; writes are claimed through
 * {@link #claimTick()} and afterwards reported via {@link #completeChunkWrite} and
 * {@link #completeRootWrite}. The queue stops accepting work after {@link #stopAccepting()}.
 */
final class ContainerIndexQueue {
    /** Cancels an already-scheduled timeout callback. */
    @FunctionalInterface
    interface TimeoutHandle {
        /** Cancels the scheduled timeout, if it has not already fired. */
        void cancel();
    }

    /** Schedules a timeout callback to run after a delay. */
    @FunctionalInterface
    interface TimeoutScheduler {
        /**
         * Schedules {@code timeout} to run after {@code delay}.
         *
         * @param delay the delay before the timeout fires
         * @param timeout the callback to run
         * @return a handle to cancel the scheduled timeout
         */
        TimeoutHandle schedule(Duration delay, Runnable timeout);
    }

    /** Identifies one claimed chunk write by chunk key and generation. */
    record ChunkWork(ChunkKey key, long generation) {
        ChunkWork {
            Objects.requireNonNull(key, "Chunk key must not be null");
            if (generation <= 0) {
                throw new IllegalArgumentException("Chunk generation must be positive");
            }
        }
    }

    /** Identifies one claimed root write by root key, operation, revision, and action. */
    record RootWork(
        BlockKey key,
        long operation,
        long revision,
        PendingAction action
    ) {
        RootWork {
            Objects.requireNonNull(key, "Root key must not be null");
            Objects.requireNonNull(action, "Pending action must not be null");
            if (operation <= 0) {
                throw new IllegalArgumentException("Root operation must be positive");
            }
            if (revision <= 0) {
                throw new IllegalArgumentException("Root revision must be positive");
            }
        }
    }

    /** Work claimed for one tick, split into chunk and root writes. */
    record TickBatch(List<ChunkWork> chunks, List<RootWork> roots) {
        TickBatch {
            chunks = List.copyOf(chunks);
            roots = List.copyOf(roots);
        }
    }

    private static final TimeoutScheduler DEFAULT_TIMEOUT_SCHEDULER =
        (delay, timeout) -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            CompletableFuture.runAsync(
                () -> {
                    if (!cancelled.get()) timeout.run();
                },
                CompletableFuture.delayedExecutor(
                    delay.toNanos(),
                    TimeUnit.NANOSECONDS
                )
            );
            return () -> cancelled.set(true);
        };

    private final int chunksPerTick;
    private final int rootsPerTick;
    private final TimeoutScheduler timeoutScheduler;
    private final Map<ChunkKey, ChunkGeneration> currentChunks =
        new LinkedHashMap<>();
    private final Map<Long, ChunkGeneration> chunksByGeneration =
        new LinkedHashMap<>();
    private final ArrayDeque<ChunkGeneration> chunkQueue = new ArrayDeque<>();
    private final Map<BlockKey, RootState> roots = new LinkedHashMap<>();
    private final ArrayDeque<RootState> rootQueue = new ArrayDeque<>();
    private final Map<Long, RootOperation> rootOperations =
        new LinkedHashMap<>();
    private final Set<ReadinessWaiter> readinessWaiters =
        new LinkedHashSet<>();
    private long nextGeneration;
    private long nextRootOperation;
    private boolean accepting = true;

    /**
     * Creates a queue with the default {@link CompletableFuture}-based timeout scheduler.
     *
     * @param chunksPerTick maximum chunk writes claimed per tick, positive
     * @param rootsPerTick maximum root writes claimed per tick, positive
     */
    ContainerIndexQueue(int chunksPerTick, int rootsPerTick) {
        this(chunksPerTick, rootsPerTick, DEFAULT_TIMEOUT_SCHEDULER);
    }

    /**
     * Creates a queue with a custom timeout scheduler.
     *
     * @param chunksPerTick maximum chunk writes claimed per tick, positive
     * @param rootsPerTick maximum root writes claimed per tick, positive
     * @param timeoutScheduler scheduler used for readiness timeouts, must not be null
     * @throws IllegalArgumentException if either tick budget is not positive
     */
    ContainerIndexQueue(
        int chunksPerTick,
        int rootsPerTick,
        TimeoutScheduler timeoutScheduler
    ) {
        if (chunksPerTick <= 0) {
            throw new IllegalArgumentException("Chunks per tick must be positive");
        }
        if (rootsPerTick <= 0) {
            throw new IllegalArgumentException("Roots per tick must be positive");
        }
        this.chunksPerTick = chunksPerTick;
        this.rootsPerTick = rootsPerTick;
        this.timeoutScheduler = Objects.requireNonNull(
            timeoutScheduler,
            "Timeout scheduler must not be null"
        );
    }

    /**
     * Stops the queue from accepting new work and fails all pending readiness waiters.
     *
     * <p>Subsequent enqueue and dirty-marking calls become no-ops and future calls to
     * {@link #awaitReady} return a failed stage. Calling more than once has no further effect.
     */
    synchronized void stopAccepting() {
        if (!accepting) return;
        accepting = false;
        IllegalStateException failure = new IllegalStateException(
            "Container index queue is stopped"
        );
        for (ReadinessWaiter waiter : List.copyOf(readinessWaiters)) {
            failWaiter(waiter, failure);
        }
    }

    /**
     * Enqueues work for a chunk, superseding any previously queued generation for the same key.
     *
     * @param key the chunk to enqueue, must not be null
     * @return the newly-created generation ID, or {@code 0L} if the queue has stopped
     */
    synchronized long enqueueChunk(ChunkKey key) {
        Objects.requireNonNull(key, "Chunk key must not be null");
        if (!accepting) {
            return 0L;
        }
        ChunkGeneration previous = currentChunks.get(key);
        if (previous != null) {
            failGeneration(
                previous,
                new IllegalStateException("Chunk generation superseded")
            );
        }

        long generationId = Math.incrementExact(nextGeneration);
        nextGeneration = generationId;
        ChunkGeneration generation = new ChunkGeneration(key, generationId);
        currentChunks.put(key, generation);
        chunksByGeneration.put(generation.id, generation);
        chunkQueue.addLast(generation);
        bindUnknownWaiters(generation);
        return generation.id;
    }

    /**
     * Marks a root as dirty, scheduling it for a snapshot write if not already in flight.
     *
     * @param key the root to mark dirty, must not be null
     */
    synchronized void markDirty(BlockKey key) {
        Objects.requireNonNull(key, "Root key must not be null");
        if (!accepting) return;
        RootState state = roots.computeIfAbsent(key, RootState::new);
        state.latestPending = new PendingRoot(key, 1L, PendingAction.SNAPSHOT);
        if (state.inFlight != null) {
            state.rerunRequested = true;
            return;
        }
        state.dirtyRequested = true;
        queueRoot(state);
    }

    /**
     * Schedules processing of an explicit pending root action for a root.
     *
     * @param pending the root action to schedule, must not be null
     */
    synchronized void markRoot(PendingRoot pending) {
        Objects.requireNonNull(pending, "Pending root must not be null");
        if (!accepting) return;
        RootState state = roots.computeIfAbsent(pending.key(), RootState::new);
        if (state.parkedPending != null) {
            if (state.parkedGenerations != null) {
                state.waitingGenerations.addAll(state.parkedGenerations);
            }
            state.parkedPending = null;
            state.parkedGenerations = null;
        }
        state.latestPending = pending;
        if (state.inFlight != null) {
            state.rerunRequested = true;
            return;
        }
        state.dirtyRequested = true;
        queueRoot(state);
    }

    /**
     * Re-queues a root write after a transient failure, preserving any replacement pending action.
     *
     * @param work the failed root work to retry, must not be null
     */
    synchronized void retryRootWrite(RootWork work) {
        Objects.requireNonNull(work, "Root work must not be null");
        RootOperation operation = rootOperations.remove(work.operation());
        if (operation == null || !operation.key.equals(work.key())) return;
        RootState state = roots.get(operation.key);
        if (state == null || state.inFlight != operation) return;

        boolean replacementPending = state.rerunRequested
            && !Objects.equals(state.latestPending, operation.pendingRoot);
        state.inFlight = null;
        if (replacementPending) {
            state.waitingGenerations.addAll(operation.generations);
            state.rerunRequested = false;
            state.dirtyRequested = true;
            queueRoot(state);
            return;
        }

        state.parkedPending = operation.pendingRoot;
        state.parkedGenerations = new LinkedHashSet<>(operation.generations);
        state.rerunRequested = false;
    }

    /**
     * Claims up to the configured per-tick budgets of chunk and root writes.
     *
     * <p>Prioritizes chunks with pending readiness waiters, then takes the remaining active
     * chunks in order. Root writes are assigned fresh operation IDs against the current
     * generation ceiling.
     *
     * @return the batch of work claimed for this tick
     */
    synchronized TickBatch claimTick() {
        List<ChunkWork> chunkWork = new ArrayList<>(chunksPerTick);
        List<RootWork> rootWork = new ArrayList<>(rootsPerTick);

        Set<ChunkGeneration> claimedChunks = new LinkedHashSet<>();
        for (ChunkGeneration generation : chunkQueue) {
            if (claimedChunks.size() == chunksPerTick) break;
            if (generation.active && isPrioritized(generation)) {
                claimedChunks.add(generation);
            }
        }
        for (ChunkGeneration generation : chunkQueue) {
            if (claimedChunks.size() == chunksPerTick) break;
            if (generation.active) claimedChunks.add(generation);
        }
        for (ChunkGeneration generation : claimedChunks) {
            chunkQueue.remove(generation);
            chunkWork.add(new ChunkWork(generation.key, generation.id));
        }

        while (rootWork.size() < rootsPerTick && !rootQueue.isEmpty()) {
            RootState state = rootQueue.removeFirst();
            state.queued = false;
            long operationId = Math.incrementExact(nextRootOperation);
            nextRootOperation = operationId;
            RootOperation operation = new RootOperation(
                operationId,
                state.key,
                nextGeneration,
                state.waitingGenerations,
                state.latestPending
            );
            state.waitingGenerations.clear();
            state.dirtyRequested = false;
            state.inFlight = operation;
            rootOperations.put(operation.id, operation);
            PendingRoot latestPending = state.latestPending;
            rootWork.add(new RootWork(
                operation.key,
                operation.id,
                latestPending != null ? latestPending.revision() : 1L,
                latestPending != null ? latestPending.action() : PendingAction.SNAPSHOT
            ));
        }

        return new TickBatch(chunkWork, rootWork);
    }

    /**
     * Records the root keys discovered while writing a chunk.
     *
     * @param work the chunk write that discovered the roots
     * @param discoveredRoots roots discovered during the write, must not be null
     */
    synchronized void discovered(
        ChunkWork work,
        List<BlockKey> discoveredRoots
    ) {
        Objects.requireNonNull(work, "Chunk work must not be null");
        Objects.requireNonNull(
            discoveredRoots,
            "Discovered roots must not be null"
        );
        if (!accepting) return;
        ChunkGeneration generation = activeGeneration(work);
        if (generation == null || generation.discovered) return;
        generation.discovered = true;

        for (BlockKey key : discoveredRoots) {
            Objects.requireNonNull(key, "Discovered root must not be null");
            if (!generation.pendingRoots.add(key)) continue;

            RootState state = roots.computeIfAbsent(key, RootState::new);
            if (state.inFlight != null
                && generation.id <= state.inFlight.generationCeiling) {
                state.inFlight.generations.add(generation.id);
            } else {
                state.waitingGenerations.add(generation.id);
                queueRoot(state);
            }
        }
        attemptReady(generation);
    }

    /**
     * Marks a chunk write as complete, failing the generation if the write failed.
     *
     * @param work the completed chunk write
     * @param failure the write failure, or {@code null} on success
     */
    synchronized void completeChunkWrite(
        ChunkWork work,
        Throwable failure
    ) {
        Objects.requireNonNull(work, "Chunk work must not be null");
        ChunkGeneration generation = activeGeneration(work);
        if (generation == null) return;
        if (failure != null) {
            failGeneration(generation, unwrap(failure));
            return;
        }
        generation.chunkWriteComplete = true;
        attemptReady(generation);
    }

    /**
     * Marks a root write as complete, releasing any related chunk generations from their
     * pending roots and re-queueing the root if further work remains.
     *
     * @param work the completed root write
     * @param failure the write failure, or {@code null} on success
     */
    synchronized void completeRootWrite(
        RootWork work,
        Throwable failure
    ) {
        Objects.requireNonNull(work, "Root work must not be null");
        RootOperation operation = rootOperations.remove(work.operation());
        if (operation == null || !operation.key.equals(work.key())) return;
        RootState state = roots.get(operation.key);
        if (state == null || state.inFlight != operation) return;
        state.inFlight = null;

        Throwable actualFailure = failure == null ? null : unwrap(failure);
        for (Long generationId : List.copyOf(operation.generations)) {
            ChunkGeneration generation = chunksByGeneration.get(generationId);
            if (generation == null || !generation.active) continue;
            if (actualFailure != null) {
                failGeneration(generation, actualFailure);
            } else {
                generation.pendingRoots.remove(operation.key);
                attemptReady(generation);
            }
        }

        if (state.rerunRequested) {
            state.rerunRequested = false;
            state.dirtyRequested = true;
        }
        if (state.dirtyRequested || !state.waitingGenerations.isEmpty()) {
            roots.putIfAbsent(state.key, state);
            queueRoot(state);
        } else {
            roots.remove(state.key, state);
        }
    }

    /**
     * Returns a stage completing when all listed chunks become ready or timing out.
     *
     * <p>Waits for the current generation of each key to reach its fully-indexed state. If a
     * generation has already failed, the returned stage completes exceptionally. If the queue
     * has stopped, the stage fails immediately.
     *
     * @param keys chunks to await readiness for, must be non-empty
     * @param timeout maximum wait duration, must be positive
     * @return a stage completing on readiness or failing on failure, timeout, or stop
     * @throws IllegalArgumentException if {@code keys} is empty or {@code timeout} is
     *         zero or negative
     */
    synchronized CompletionStage<Void> awaitReady(
        Set<ChunkKey> keys,
        Duration timeout
    ) {
        Objects.requireNonNull(keys, "Chunks must not be null");
        Objects.requireNonNull(timeout, "Timeout must not be null");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Chunks must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        if (!accepting) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Container index queue is stopped")
            );
        }

        LinkedHashSet<GenerationTarget> targets = new LinkedHashSet<>();
        for (ChunkKey key : keys) {
            Objects.requireNonNull(key, "Chunk key must not be null");
            ChunkGeneration generation = currentChunks.get(key);
            if (generation == null) {
                targets.add(new GenerationTarget(key, 0));
            } else if (generation.failure != null) {
                return CompletableFuture.failedFuture(generation.failure);
            } else if (!generation.ready) {
                targets.add(new GenerationTarget(key, generation.id));
            }
        }
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        ReadinessWaiter waiter = new ReadinessWaiter(targets);
        readinessWaiters.add(waiter);
        attachWaiter(waiter);
        waiter.timeout = timeoutScheduler.schedule(
            timeout,
            () -> timeout(waiter)
        );
        return waiter.future;
    }

    /**
     * Reports whether the current generation of a chunk has reached the ready state.
     *
     * @param key the chunk to check, must not be null
     * @return {@code true} if a current generation exists and is ready
     */
    synchronized boolean isReady(ChunkKey key) {
        Objects.requireNonNull(key, "Chunk key must not be null");
        ChunkGeneration generation = currentChunks.get(key);
        return generation != null && generation.ready;
    }

    /**
     * Removes a chunk from the queue, failing its current generation with an unload error.
     *
     * @param key the chunk to unload, must not be null
     */
    synchronized void unload(ChunkKey key) {
        Objects.requireNonNull(key, "Chunk key must not be null");
        ChunkGeneration generation = currentChunks.remove(key);
        if (generation != null) {
            failGeneration(
                generation,
                new IllegalStateException("Chunk unloaded")
            );
        }
    }

    /** Returns the number of chunk generations currently awaiting a write. */
    synchronized int pendingChunkCount() {
        return chunkQueue.size();
    }

    /** Returns the number of roots currently awaiting a write. */
    synchronized int pendingRootCount() {
        return rootQueue.size();
    }

    private ChunkGeneration activeGeneration(ChunkWork work) {
        ChunkGeneration generation = chunksByGeneration.get(work.generation());
        if (generation == null
            || !generation.active
            || !generation.key.equals(work.key())
            || currentChunks.get(generation.key) != generation) {
            return null;
        }
        return generation;
    }

    private void queueRoot(RootState state) {
        if (state.queued || state.inFlight != null) return;
        state.queued = true;
        rootQueue.addLast(state);
    }

    private boolean isPrioritized(ChunkGeneration generation) {
        GenerationTarget target = new GenerationTarget(
            generation.key,
            generation.id
        );
        for (ReadinessWaiter waiter : readinessWaiters) {
            if (!waiter.done && waiter.remaining.contains(target)) return true;
        }
        return false;
    }

    private void attemptReady(ChunkGeneration generation) {
        if (!generation.active
            || generation.ready
            || !generation.discovered
            || !generation.chunkWriteComplete
            || !generation.pendingRoots.isEmpty()) {
            return;
        }
        generation.ready = true;
        completeGenerationWaiters(generation);
    }

    private void failGeneration(
        ChunkGeneration generation,
        Throwable failure
    ) {
        if (!generation.active) return;
        generation.active = false;
        generation.ready = false;
        generation.failure = Objects.requireNonNull(failure, "Failure");
        chunkQueue.remove(generation);
        chunksByGeneration.remove(generation.id);
        detachGenerationFromRoots(generation);
        for (ReadinessWaiter waiter : List.copyOf(generation.waiters)) {
            failWaiter(waiter, generation.failure);
        }
        generation.waiters.clear();
    }

    private void detachGenerationFromRoots(ChunkGeneration generation) {
        for (BlockKey key : List.copyOf(generation.pendingRoots)) {
            RootState state = roots.get(key);
            if (state == null) continue;
            state.waitingGenerations.remove(generation.id);
            if (state.parkedGenerations != null) {
                state.parkedGenerations.remove(generation.id);
                if (state.parkedGenerations.isEmpty()) {
                    state.parkedGenerations = null;
                    state.parkedPending = null;
                }
            }
            if (state.inFlight != null) {
                state.inFlight.generations.remove(generation.id);
            }
            if (state.inFlight == null
                && state.waitingGenerations.isEmpty()
                && state.parkedGenerations == null
                && !state.dirtyRequested) {
                if (state.queued) rootQueue.remove(state);
                state.queued = false;
                roots.remove(key, state);
            }
        }
        generation.pendingRoots.clear();
    }

    private void completeGenerationWaiters(ChunkGeneration generation) {
        GenerationTarget target = new GenerationTarget(
            generation.key,
            generation.id
        );
        for (ReadinessWaiter waiter : List.copyOf(generation.waiters)) {
            waiter.remaining.remove(target);
            generation.waiters.remove(waiter);
            if (waiter.remaining.isEmpty()) completeWaiter(waiter);
        }
    }

    private void attachWaiter(ReadinessWaiter waiter) {
        for (GenerationTarget target : waiter.remaining) {
            if (target.generation == 0) continue;
            ChunkGeneration generation = chunksByGeneration.get(
                target.generation
            );
            if (generation != null) generation.waiters.add(waiter);
        }
    }

    private void bindUnknownWaiters(ChunkGeneration generation) {
        GenerationTarget unknown = new GenerationTarget(generation.key, 0);
        GenerationTarget current = new GenerationTarget(
            generation.key,
            generation.id
        );
        for (ReadinessWaiter waiter : readinessWaiters) {
            if (waiter.done || !waiter.remaining.remove(unknown)) continue;
            waiter.remaining.add(current);
            generation.waiters.add(waiter);
        }
    }

    private synchronized void timeout(ReadinessWaiter waiter) {
        if (!waiter.done) {
            failWaiter(
                waiter,
                new IndexWarmupTimeoutException("Index warmup timed out")
            );
        }
    }

    private void completeWaiter(ReadinessWaiter waiter) {
        if (waiter.done) return;
        waiter.done = true;
        detachWaiter(waiter);
        waiter.future.complete(null);
    }

    private void failWaiter(ReadinessWaiter waiter, Throwable failure) {
        if (waiter.done) return;
        waiter.done = true;
        detachWaiter(waiter);
        waiter.future.completeExceptionally(failure);
    }

    private void detachWaiter(ReadinessWaiter waiter) {
        readinessWaiters.remove(waiter);
        for (ChunkGeneration generation : currentChunks.values()) {
            generation.waiters.remove(waiter);
        }
        if (waiter.timeout != null) waiter.timeout.cancel();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class ChunkGeneration {
        private final ChunkKey key;
        private final long id;
        private final Set<BlockKey> pendingRoots = new LinkedHashSet<>();
        private final Set<ReadinessWaiter> waiters = new LinkedHashSet<>();
        private boolean active = true;
        private boolean discovered;
        private boolean chunkWriteComplete;
        private boolean ready;
        private Throwable failure;

        private ChunkGeneration(ChunkKey key, long id) {
            this.key = key;
            this.id = id;
        }
    }

    private static final class RootState {
        private final BlockKey key;
        private final Set<Long> waitingGenerations = new LinkedHashSet<>();
        private RootOperation inFlight;
        private boolean queued;
        private boolean dirtyRequested;
        private boolean rerunRequested;
        private PendingRoot latestPending;
        private PendingRoot parkedPending;
        private Set<Long> parkedGenerations;

        private RootState(BlockKey key) {
            this.key = key;
        }
    }

    private static final class RootOperation {
        private final long id;
        private final BlockKey key;
        private final long generationCeiling;
        private final Set<Long> generations;
        private final PendingRoot pendingRoot;

        private RootOperation(
            long id,
            BlockKey key,
            long generationCeiling,
            Set<Long> generations,
            PendingRoot pendingRoot
        ) {
            this.id = id;
            this.key = key;
            this.generationCeiling = generationCeiling;
            this.generations = new LinkedHashSet<>(generations);
            this.pendingRoot = pendingRoot;
        }
    }

    private record GenerationTarget(ChunkKey key, long generation) {}

    private static final class ReadinessWaiter {
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final Set<GenerationTarget> remaining;
        private boolean done;
        private TimeoutHandle timeout;

        private ReadinessWaiter(Set<GenerationTarget> targets) {
            this.remaining = new LinkedHashSet<>(targets);
        }
    }
}
