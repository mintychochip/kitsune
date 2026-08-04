package dev.jlo.kitsune.bukkit;

import org.junit.jupiter.api.Test;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.index.IndexRepository;

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

class BukkitRuntimeLifecycleTest {

    @Test
    void bootstrapLifecycleRejectsStaleCompletionsAfterStop() {
        BukkitRuntime.BootstrapLifecycle lifecycle = new BukkitRuntime.BootstrapLifecycle();
        long generation = lifecycle.begin();
        lifecycle.stop();

        assertFalse(lifecycle.isCurrent(generation));
        assertTrue(lifecycle.isStopping());
    }

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
            public List<RootIdentity> findCandidates(
                    UUID worldId,
                    int minChunkX,
                    int maxChunkX,
                    int minChunkZ,
                    int maxChunkZ
            ) {
                return List.of();
            }

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
