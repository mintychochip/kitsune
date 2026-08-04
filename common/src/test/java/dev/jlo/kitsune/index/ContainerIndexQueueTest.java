package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexQueueTest {
    private static final UUID WORLD_ID = new UUID(0L, 1L);

    @Test
    void dirtyQueueDeduplicatesCanonicalRoot() {
        ContainerIndexQueue queue = queue(2, 8);
        BlockKey root = root(0);

        for (int index = 0; index < 10; index++) queue.markDirty(root);

        ContainerIndexQueue.TickBatch batch = queue.claimTick();
        assertEquals(List.of(root), batch.roots().stream()
            .map(ContainerIndexQueue.RootWork::key)
            .toList());
        assertEquals(0, queue.pendingRootCount());
    }

    @Test
    void tickHonorsChunkAndRootBudgetsInInsertionOrder() {
        ContainerIndexQueue queue = queue(2, 8);
        List<ChunkKey> chunks = List.of(chunk(0), chunk(1), chunk(2));
        List<BlockKey> roots = new ArrayList<>();
        chunks.forEach(queue::enqueueChunk);
        for (int index = 0; index < 10; index++) {
            BlockKey root = root(index);
            roots.add(root);
            queue.markDirty(root);
        }

        ContainerIndexQueue.TickBatch batch = queue.claimTick();

        assertEquals(chunks.subList(0, 2), batch.chunks().stream()
            .map(ContainerIndexQueue.ChunkWork::key)
            .toList());
        assertEquals(roots.subList(0, 8), batch.roots().stream()
            .map(ContainerIndexQueue.RootWork::key)
            .toList());
        assertEquals(1, queue.pendingChunkCount());
        assertEquals(2, queue.pendingRootCount());
    }

    @Test
    void readinessCompletesOnlyAfterDiscoveryRootAndChunkWrites() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery = queue.claimTick().chunks().getFirst();
        CompletableFuture<Void> ready = queue.awaitReady(Set.of(chunk), Duration.ofSeconds(3))
            .toCompletableFuture();

        queue.discovered(discovery, List.of(root));
        ContainerIndexQueue.RootWork write = queue.claimTick().roots().getFirst();
        assertFalse(ready.isDone());

        queue.completeRootWrite(write, null);
        assertFalse(ready.isDone());
        queue.completeChunkWrite(discovery, null);

        assertTrue(ready.isDone());
        ready.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void failedWriteCompletesReadinessExceptionally() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery = queue.claimTick().chunks().getFirst();
        CompletableFuture<Void> ready = queue.awaitReady(Set.of(chunk), Duration.ofSeconds(3))
            .toCompletableFuture();
        queue.discovered(discovery, List.of(root));
        ContainerIndexQueue.RootWork write = queue.claimTick().roots().getFirst();
        queue.completeChunkWrite(discovery, null);

        IllegalStateException failure = new IllegalStateException("write failed");
        queue.completeRootWrite(write, failure);

        CompletionException completion = assertThrows(CompletionException.class, ready::join);
        assertEquals(failure, completion.getCause());
        assertFalse(queue.isReady(chunk));
    }

    @Test
    void staleCompletionCannotReadyNewerGeneration() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork stale = queue.claimTick().chunks().getFirst();
        queue.discovered(stale, List.of());

        queue.enqueueChunk(chunk);
        CompletableFuture<Void> currentReady = queue.awaitReady(Set.of(chunk), Duration.ofSeconds(3))
            .toCompletableFuture();
        queue.completeChunkWrite(stale, null);
        assertFalse(currentReady.isDone());
        assertFalse(queue.isReady(chunk));

        ContainerIndexQueue.ChunkWork current = queue.claimTick().chunks().getFirst();
        queue.discovered(current, List.of());
        queue.completeChunkWrite(current, null);

        currentReady.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void supersededChunkWaitsForFreshRootOperation() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork staleChunk =
            queue.claimTick().chunks().getFirst();
        queue.discovered(staleChunk, List.of(root));
        ContainerIndexQueue.RootWork staleRoot =
            queue.claimTick().roots().getFirst();

        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork currentChunk =
            queue.claimTick().chunks().getFirst();
        queue.discovered(currentChunk, List.of(root));
        queue.completeChunkWrite(currentChunk, null);
        CompletableFuture<Void> currentReady = queue.awaitReady(
            Set.of(chunk),
            Duration.ofSeconds(3)
        ).toCompletableFuture();

        queue.completeRootWrite(staleRoot, null);

        assertFalse(currentReady.isDone());
        assertFalse(queue.isReady(chunk));
        ContainerIndexQueue.RootWork currentRoot =
            queue.claimTick().roots().getFirst();
        queue.completeRootWrite(currentRoot, null);
        currentReady.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void deduplicatedRootSatisfiesBothChunkGenerations() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey first = chunk(0);
        ChunkKey second = chunk(1);
        BlockKey shared = root(15);
        queue.enqueueChunk(first);
        queue.enqueueChunk(second);
        List<ContainerIndexQueue.ChunkWork> discoveries = queue.claimTick().chunks();
        CompletableFuture<Void> both = queue.awaitReady(Set.of(first, second), Duration.ofSeconds(3))
            .toCompletableFuture();

        queue.discovered(discoveries.get(0), List.of(shared));
        queue.discovered(discoveries.get(1), List.of(shared));
        ContainerIndexQueue.TickBatch writes = queue.claimTick();
        assertEquals(1, writes.roots().size());
        queue.completeChunkWrite(discoveries.get(0), null);
        queue.completeChunkWrite(discoveries.get(1), null);
        assertFalse(both.isDone());
        queue.completeRootWrite(writes.roots().getFirst(), null);

        both.join();
        assertTrue(queue.isReady(first));
        assertTrue(queue.isReady(second));
    }

    @Test
    void awaitReadyPrioritizesRequestedChunksAndTimesOutAtomically() {
        ManualTimeoutScheduler priorityScheduler = new ManualTimeoutScheduler();
        ContainerIndexQueue priorityQueue = new ContainerIndexQueue(
            2,
            8,
            priorityScheduler
        );
        ChunkKey first = chunk(0);
        ChunkKey second = chunk(1);
        ChunkKey requested = chunk(2);
        priorityQueue.enqueueChunk(first);
        priorityQueue.enqueueChunk(second);
        priorityQueue.enqueueChunk(requested);
        priorityQueue.awaitReady(
            Set.of(requested),
            Duration.ofSeconds(3)
        );

        ContainerIndexQueue.TickBatch batch = priorityQueue.claimTick();
        assertEquals(requested, batch.chunks().getFirst().key());

        ManualTimeoutScheduler timeoutScheduler = new ManualTimeoutScheduler();
        ContainerIndexQueue timeoutQueue = new ContainerIndexQueue(
            2,
            8,
            timeoutScheduler
        );
        timeoutQueue.enqueueChunk(first);
        timeoutQueue.enqueueChunk(requested);
        CompletableFuture<Void> ready = timeoutQueue.awaitReady(
            Set.of(first, requested),
            Duration.ofSeconds(3)
        ).toCompletableFuture();

        timeoutScheduler.fire();

        CompletionException completion = assertThrows(
            CompletionException.class,
            ready::join
        );
        assertInstanceOf(
            IndexWarmupTimeoutException.class,
            completion.getCause()
        );
        assertFalse(timeoutQueue.isReady(first));
        assertFalse(timeoutQueue.isReady(requested));
    }

    @Test
    void retryRootWriteWaitsForTrackerAndPreservesReadiness() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        PendingRoot pending = new PendingRoot(root, 5L, PendingAction.SNAPSHOT);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery =
            queue.claimTick().chunks().getFirst();
        queue.discovered(discovery, List.of(root));
        queue.markRoot(pending);
        ContainerIndexQueue.RootWork failed =
            queue.claimTick().roots().getFirst();
        queue.completeChunkWrite(discovery, null);
        CompletableFuture<Void> ready = queue.awaitReady(
            Set.of(chunk),
            Duration.ofSeconds(3)
        ).toCompletableFuture();

        queue.retryRootWrite(failed);

        assertTrue(queue.claimTick().roots().isEmpty());
        assertFalse(ready.isDone());

        queue.markRoot(pending);
        ContainerIndexQueue.RootWork retry =
            queue.claimTick().roots().getFirst();
        assertEquals(5L, retry.revision());
        assertEquals(PendingAction.SNAPSHOT, retry.action());
        queue.completeRootWrite(retry, null);

        ready.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void newerPendingRootReusesParkedGenerationWaiters() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery =
            queue.claimTick().chunks().getFirst();
        queue.discovered(discovery, List.of(root));
        queue.markRoot(new PendingRoot(root, 5L, PendingAction.SNAPSHOT));
        ContainerIndexQueue.RootWork failed =
            queue.claimTick().roots().getFirst();
        queue.completeChunkWrite(discovery, null);
        CompletableFuture<Void> ready = queue.awaitReady(
            Set.of(chunk),
            Duration.ofSeconds(3)
        ).toCompletableFuture();
        queue.retryRootWrite(failed);

        queue.markRoot(new PendingRoot(root, 6L, PendingAction.SNAPSHOT));
        ContainerIndexQueue.RootWork replacement =
            queue.claimTick().roots().getFirst();
        assertEquals(6L, replacement.revision());
        queue.completeRootWrite(replacement, null);

        ready.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void newerPendingRootSurvivesFailureWhileWriteIsInFlight() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey root = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery =
            queue.claimTick().chunks().getFirst();
        queue.discovered(discovery, List.of(root));
        queue.markRoot(new PendingRoot(root, 5L, PendingAction.SNAPSHOT));
        ContainerIndexQueue.RootWork failed =
            queue.claimTick().roots().getFirst();
        queue.completeChunkWrite(discovery, null);
        CompletableFuture<Void> ready = queue.awaitReady(
            Set.of(chunk),
            Duration.ofSeconds(3)
        ).toCompletableFuture();

        queue.markRoot(new PendingRoot(root, 6L, PendingAction.DELETE));
        queue.retryRootWrite(failed);

        ContainerIndexQueue.RootWork replacement =
            queue.claimTick().roots().getFirst();
        assertEquals(6L, replacement.revision());
        assertEquals(PendingAction.DELETE, replacement.action());
        queue.completeRootWrite(replacement, null);
        ready.join();
        assertTrue(queue.isReady(chunk));
    }

    @Test
    void stopAcceptingFailsExistingAndFutureReadinessRequests() {
        ContainerIndexQueue queue = queue(2, 8);
        CompletableFuture<Void> existing = queue.awaitReady(
            Set.of(chunk(0)),
            Duration.ofSeconds(3)
        ).toCompletableFuture();

        queue.stopAccepting();

        CompletionException existingFailure = assertThrows(
            CompletionException.class,
            existing::join
        );
        assertInstanceOf(
            IllegalStateException.class,
            existingFailure.getCause()
        );
        CompletableFuture<Void> future = queue.awaitReady(
            Set.of(chunk(1)),
            Duration.ofSeconds(3)
        ).toCompletableFuture();
        CompletionException futureFailure = assertThrows(
            CompletionException.class,
            future::join
        );
        assertInstanceOf(
            IllegalStateException.class,
            futureFailure.getCause()
        );
    }

    @Test
    void unloadInvalidatesReadinessWithoutDeletingQueuedRoots() {
        ContainerIndexQueue queue = queue(2, 8);
        ChunkKey chunk = chunk(0);
        BlockKey dirty = root(0);
        queue.enqueueChunk(chunk);
        ContainerIndexQueue.ChunkWork discovery = queue.claimTick().chunks().getFirst();
        queue.discovered(discovery, List.of());
        queue.completeChunkWrite(discovery, null);
        assertTrue(queue.isReady(chunk));
        queue.markDirty(dirty);
        int pendingRoots = queue.pendingRootCount();

        queue.unload(chunk);

        assertFalse(queue.isReady(chunk));
        assertEquals(pendingRoots, queue.pendingRootCount());
    }

    private static ContainerIndexQueue queue(int chunkBudget, int rootBudget) {
        return new ContainerIndexQueue(chunkBudget, rootBudget, new ManualTimeoutScheduler());
    }

    private static ChunkKey chunk(int x) {
        return new ChunkKey(WORLD_ID, x, 0);
    }

    private static BlockKey root(int x) {
        return new BlockKey(WORLD_ID, x, 64, 0);
    }

    private static final class ManualTimeoutScheduler
        implements ContainerIndexQueue.TimeoutScheduler {
        private final List<ScheduledTimeout> scheduled = new ArrayList<>();

        @Override
        public ContainerIndexQueue.TimeoutHandle schedule(
            Duration delay,
            Runnable timeout
        ) {
            ScheduledTimeout scheduledTimeout = new ScheduledTimeout(delay, timeout);
            scheduled.add(scheduledTimeout);
            return () -> scheduledTimeout.cancelled = true;
        }

        void fire() {
            ScheduledTimeout timeout = scheduled.getLast();
            if (!timeout.cancelled) timeout.operation.run();
        }
    }

    private static final class ScheduledTimeout {
        private final Duration delay;
        private final Runnable operation;
        private boolean cancelled;

        private ScheduledTimeout(Duration delay, Runnable operation) {
            this.delay = delay;
            this.operation = operation;
        }
    }
}
