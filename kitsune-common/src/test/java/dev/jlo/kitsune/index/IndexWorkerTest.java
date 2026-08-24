package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.RootIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies lifecycle, threading, and shutdown behavior of the index worker.
 */
class IndexWorkerTest {

    @Test
    void runsSeriallyOnNamedPlatformThread() throws Exception {
        RecordingRepository repository = new RecordingRepository(new AtomicBoolean(true));
        AtomicInteger order = new AtomicInteger();

        try (IndexWorker worker = new IndexWorker(repository)) {
            CompletableFuture<ThreadObservation> first = worker.submit(() ->
                    new ThreadObservation(Thread.currentThread().getName(),
                            Thread.currentThread().isVirtual(), order.incrementAndGet()));
            CompletableFuture<ThreadObservation> second = worker.submit(() ->
                    new ThreadObservation(Thread.currentThread().getName(),
                            Thread.currentThread().isVirtual(), order.incrementAndGet()));

            assertEquals(new ThreadObservation("kitsune-index", false, 1),
                    first.get(5, TimeUnit.SECONDS));
            assertEquals(new ThreadObservation("kitsune-index", false, 2),
                    second.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, repository.closeCount());
    }

    @Test
    void completesCheckedExceptionFutureExceptionally() throws Exception {
        RecordingRepository repository = new RecordingRepository(new AtomicBoolean(true));

        try (IndexWorker worker = new IndexWorker(repository)) {
            CompletableFuture<Void> future = worker.submit(() -> {
                throw new IOException("disk failed");
            });

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("disk failed", failure.getCause().getMessage());
        }
    }

    @Test
    void submitAfterCloseReturnsFailedFuture() throws Exception {
        RecordingRepository repository = new RecordingRepository(new AtomicBoolean(true));
        IndexWorker worker = new IndexWorker(repository);
        worker.close();

        CompletableFuture<Void> future = worker.submit(() -> null);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(1, repository.closeCount());
    }

    @Test
    void forcedShutdownCancelsQueuedFutureAndClosesAfterStop() throws Exception {
        AtomicBoolean operationStopped = new AtomicBoolean();
        RecordingRepository repository = new RecordingRepository(operationStopped);
        IndexWorker worker = new IndexWorker(repository);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();

        CompletableFuture<Void> active = worker.submit(() -> {
            running.countDown();
            try {
                neverReleased.await();
                return null;
            } finally {
                operationStopped.set(true);
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = worker.submit(() -> {
            queuedRan.set(true);
            return null;
        });

        worker.close(100, TimeUnit.MILLISECONDS);

        assertTrue(active.isDone());
        assertTrue(queued.isDone());
        assertTrue(queued.isCancelled());
        assertFalse(queuedRan.get());
        assertTrue(operationStopped.get());
        assertTrue(repository.closeSawStopped());
        assertEquals(1, repository.closeCount());
    }

    @Test
    void interruptedCloseAwaitsTerminationClosesAndRestoresInterrupt() throws Exception {
        AtomicBoolean operationStopped = new AtomicBoolean();
        RecordingRepository repository = new RecordingRepository(operationStopped);
        IndexWorker worker = new IndexWorker(repository);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();

        worker.submit(() -> {
            running.countDown();
            try {
                neverReleased.await();
                return null;
            } finally {
                operationStopped.set(true);
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                worker.close(1, TimeUnit.SECONDS);
                closeFailure.set(new AssertionError("Expected interrupted close"));
            } catch (Throwable failure) {
                closeFailure.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(closer.isAlive());
        assertInstanceOf(InterruptedException.class, closeFailure.get());
        assertTrue(interruptRestored.get());
        assertTrue(operationStopped.get());
        assertTrue(repository.closeSawStopped());
        assertEquals(1, repository.closeCount());
    }

    @Test
    void closesRepositoryOnLifecycleThreadNotCaller() throws Exception {
        AtomicBoolean operationStopped = new AtomicBoolean();
        RecordingRepository repository = new RecordingRepository(operationStopped);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        try (IndexWorker worker = new IndexWorker(repository)) {
            CompletableFuture<Void> pending = worker.submit(() -> {
                operationStopped.set(true);
                return null;
            });

            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    worker.close(5, TimeUnit.SECONDS);
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            });
            closer.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(pending.isDone());
            assertFalse(closer.isAlive());
            assertNull(closeFailure.get());
        }

        assertTrue(repository.closeSawStopped());
        assertNotEquals(Thread.currentThread().getName(), repository.closeThreadName());
        assertEquals("kitsune-index", repository.closeThreadName());
        assertEquals(1, repository.closeCount());
    }

    @Test
    void forcedCloseStillRunsRepositoryCloseOnLifecycleThread() throws Exception {
        AtomicBoolean operationStopped = new AtomicBoolean();
        RecordingRepository repository = new RecordingRepository(operationStopped);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(1);

        IndexWorker worker = new IndexWorker(repository);
        CompletableFuture<Void> active = worker.submit(() -> {
            running.countDown();
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                operationStopped.set(true);
            }
            return null;
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                worker.close(100, TimeUnit.MILLISECONDS);
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(active.isDone());
        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());

        assertTrue(repository.closeSawStopped());
        assertNotEquals(Thread.currentThread().getName(), repository.closeThreadName());
        assertEquals("kitsune-index", repository.closeThreadName());
        assertEquals(1, repository.closeCount());
    }

    @Test
    void nonTerminatingOperationKeepsRepositoryOpenAndLaterCloseRetries() throws Exception {
        AtomicBoolean operationStopped = new AtomicBoolean();
        RecordingRepository repository = new RecordingRepository(operationStopped);
        IndexWorker worker = new IndexWorker(repository);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        worker.submit(() -> {
            running.countDown();
            try {
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Deliberately keep running to exercise the non-termination path.
                    }
                }
                return null;
            } finally {
                operationStopped.set(true);
                stopped.countDown();
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class,
                () -> worker.close(20, TimeUnit.MILLISECONDS));
        assertEquals(0, repository.closeCount());

        release.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        worker.close(1, TimeUnit.SECONDS);

        assertTrue(repository.closeSawStopped());
        assertEquals(1, repository.closeCount());
    }

    private record ThreadObservation(String name, boolean virtual, int order) {}

    private static final class RecordingRepository implements IndexRepository {
        private final AtomicBoolean operationStopped;
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicBoolean closeSawStopped = new AtomicBoolean();
        private String closeThreadName;

        private RecordingRepository(AtomicBoolean operationStopped) {
            this.operationStopped = operationStopped;
        }

        int closeCount() {
            return closeCount.get();
        }

        boolean closeSawStopped() {
            return closeSawStopped.get();
        }

        String closeThreadName() {
            return closeThreadName;
        }

        @Override
        public void migrate() {}

        @Override
        public void markAllChunksUnavailable() {}

        @Override
        public void setChunkAvailable(ChunkKey chunk, boolean available, long revision) {}

        @Override
        public void replaceRoot(ContainerSnapshot snapshot, long revision) {}

        @Override
        public void deleteRoot(BlockKey key) {}

        @Override
        public IndexRepository.CandidatePage findCandidates(
                UUID worldId,
                int minChunkX,
                int maxChunkX,
                int minChunkZ,
                int maxChunkZ,
                IndexRepository.CandidateCursor after,
                int limit
        ) {
            return new IndexRepository.CandidatePage(List.of(), null);
        }

        @Override
        public java.util.Optional<RootIdentity> findRoot(BlockKey key) {
            return java.util.Optional.empty();
        }

        @Override
        public Map<SemanticDescriptorHash, Embedding> findEmbeddings(
                EmbeddingProvider provider,
                Set<SemanticDescriptorHash> hashes
        ) {
            return Map.of();
        }

        @Override
        public void putEmbeddings(
                EmbeddingProvider provider,
                Map<SemanticDescriptorHash, Embedding> embeddings
        ) {}

        @Override
        public Map<BlockKey, List<IndexedItem>> loadDocuments(
                Set<BlockKey> allowed, EmbeddingProvider provider) {
            return Map.of();
        }

        @Override
        public void reembedAll(EmbeddingProvider provider) {}

        @Override
        public void close() throws SQLException {
            closeSawStopped.set(operationStopped.get());
            closeThreadName = Thread.currentThread().getName();
            closeCount.incrementAndGet();
        }
    }
}
