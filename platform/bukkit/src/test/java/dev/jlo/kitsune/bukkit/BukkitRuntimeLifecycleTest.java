package dev.jlo.kitsune.bukkit;

import org.junit.jupiter.api.Test;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.SemanticDescriptorHash;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the bootstrap lifecycle and repository-close threading behaviour
 * of the Bukkit runtime bridge.
 */
class BukkitRuntimeLifecycleTest {

    /**
     * A lifecycle that has been stopped no longer accepts the generation
     * issued before that stop.
     */
    @Test
    void bootstrapLifecycleRejectsStaleCompletionsAfterStop() {
        BukkitRuntime.BootstrapLifecycle lifecycle = new BukkitRuntime.BootstrapLifecycle();
        long generation = lifecycle.begin();
        lifecycle.stop();

        assertFalse(lifecycle.isCurrent(generation));
        assertTrue(lifecycle.isStopping());
    }

    /**
     * Calling {@code begin} again after a stop issues a fresh generation that
     * supersedes the previously stopped one.
     */
    @Test
    void bootstrapLifecycleResetsAfterBeginFollowingStop() {
        BukkitRuntime.BootstrapLifecycle lifecycle = new BukkitRuntime.BootstrapLifecycle();
        long first = lifecycle.begin();
        lifecycle.stop();
        long second = lifecycle.begin();

        assertFalse(lifecycle.isCurrent(first));
        assertTrue(lifecycle.isCurrent(second));
        assertFalse(lifecycle.isStopping());
    }

    /**
     * Concurrent completions issued against a stopped generation never advance
     * the lifecycle, and a subsequent begin resets it for new work.
     */
    @Test
    void concurrentStaleCompletionsDoNotAdvanceFuture() throws Exception {
        BukkitRuntime.BootstrapLifecycle lifecycle =
            new BukkitRuntime.BootstrapLifecycle();
        long generation = lifecycle.begin();
        CountDownLatch staleReady = new CountDownLatch(1);
        CountDownLatch staleDone = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                lifecycle.stop();
                staleReady.countDown();
                try {
                    lifecycle.isCurrent(generation);
                } finally {
                    staleDone.countDown();
                }
            });

            assertTrue(staleReady.await(5, TimeUnit.SECONDS));
            assertFalse(lifecycle.isCurrent(generation));
            assertTrue(lifecycle.isStopping());
            assertTrue(staleDone.await(5, TimeUnit.SECONDS));
        }

        long next = lifecycle.begin();
        assertTrue(lifecycle.isCurrent(next));
    }

    /**
     * Closing the repository runs on the dedicated {@code kitsune-close} thread
     * rather than the calling thread.
     */
    @Test
    void repositoryCloseRunsOnDedicatedThread() throws Exception {
        AtomicReference<String> closeThread = new AtomicReference<>();
        IndexRepository repository = new IndexRepository() {
            @Override
            public void migrate() {}

            @Override
            public void markAllChunksUnavailable() {}

            @Override
            public void setChunkAvailable(
                    ChunkKey chunk,
                    boolean available,
                    long revision
            ) {}

            @Override
            public void replaceRoot(
                    ContainerSnapshot snapshot,
                    long revision
            ) {}

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
                    Set<BlockKey> allowed,
                    EmbeddingProvider provider
            ) {
                return Map.of();
            }

            @Override
            public void deleteRoot(BlockKey key) {}

            @Override
            public void reembedAll(EmbeddingProvider provider) {}

            @Override
            public void close() {
                closeThread.set(Thread.currentThread().getName());
            }
        };

        BukkitRuntime.closeRepositoryOffThread(repository)
                .get(5, TimeUnit.SECONDS);

        assertEquals("kitsune-close", closeThread.get());
    }
}
