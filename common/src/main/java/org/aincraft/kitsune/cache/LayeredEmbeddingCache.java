package org.aincraft.kitsune.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Two-tier embedding cache with Caffeine L1 (in-memory) + SQLite L2 (persistent).
 * Uses long keys for zero-allocation cache lookups.
 */
public final class LayeredEmbeddingCache implements EmbeddingCache {
    private static final int DEFAULT_MAX_L1_SIZE = 10000;
    private static final int BUFFER_POOL_SIZE = 32;
    private static final int INITIAL_BUFFER_CAPACITY = 4096;
    private static final int WRITE_BEHIND_BATCH_SIZE = 100;
    private static final int MAX_WRITE_BUFFER_SIZE = 1000;
    private static final long WRITE_BEHIND_FLUSH_MS = 1000;
    private static final int MAX_EXECUTOR_THREADS = Math.min(4, Runtime.getRuntime().availableProcessors());

    private final Logger logger;
    private final DataSource dataSource;
    private final com.github.benmanes.caffeine.cache.Cache<Long, float[]> l1Cache;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Deque<ByteBuffer> bufferPool = new ConcurrentLinkedDeque<>();

    // Write-behind buffer
    private final ConcurrentLinkedQueue<WriteEntry> writeBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger writeBufferSize = new AtomicInteger(0);
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    private record WriteEntry(long key, byte[] data, long timestamp) {}

    public LayeredEmbeddingCache(DataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(DEFAULT_MAX_L1_SIZE)
            .build();
        this.executor = Executors.newFixedThreadPool(MAX_EXECUTOR_THREADS, r -> {
            Thread t = new Thread(r, "embedding-cache-executor-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-cache-scheduler");
            t.setDaemon(true);
            return t;
        });
        startWriteBehindFlusher();
    }

    private void startWriteBehindFlusher() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (writeBufferSize.get() > 0 && flushPending.compareAndSet(false, true)) {
                flushInternal().whenComplete((v, e) -> flushPending.set(false));
            }
        }, WRITE_BEHIND_FLUSH_MS, WRITE_BEHIND_FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    public void initialize() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS embedding_cache (
                    content_hash INTEGER PRIMARY KEY,
                    embedding BLOB NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_cache_created ON embedding_cache(created_at)"
            );
            logger.info("Embedding cache initialized");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize embedding cache", e);
            throw new RuntimeException("Embedding cache initialization failed", e);
        }
    }

    @Override
    public CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.runAsync(this::initialize, executor);
    }

    @Override
    public CompletableFuture<Optional<float[]>> get(long key) {
        float[] cached = l1Cache.getIfPresent(key);
        if (cached != null && cached.length > 0) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        // If cached is null or empty, treat as miss
        if (cached != null) {
            logger.warning("L1 cache returned empty embedding for key " + key + ", treating as miss");
            l1Cache.invalidate(key);
        }
        return CompletableFuture.supplyAsync(() -> getFromL2(key), executor);
    }

    @Override
    public CompletableFuture<Map<Long, float[]>> getAll(List<Long> keys) {
        Map<Long, float[]> result = new HashMap<>();
        List<Long> misses = new ArrayList<>();

        for (Long key : keys) {
            float[] cached = l1Cache.getIfPresent(key);
            if (cached != null) {
                result.put(key, cached);
            } else {
                misses.add(key);
            }
        }

        if (misses.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<Long, float[]> fromL2 = getBatchFromL2(misses);
                result.putAll(fromL2);
                return result;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed batch get from cache", e);
                return result;
            }
        }, executor);
    }

    private Optional<float[]> getFromL2(long key) {
        String sql = "SELECT embedding FROM embedding_cache WHERE content_hash = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("embedding");
                    float[] embedding = deserializeEmbedding(blob);
                    l1Cache.put(key, embedding);
                    return Optional.of(embedding);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get embedding from cache", e);
        }
        return Optional.empty();
    }

    private Map<Long, float[]> getBatchFromL2(List<Long> keys) throws SQLException {
        Map<Long, float[]> result = new HashMap<>();
        if (keys.isEmpty()) return result;

        String placeholders = String.join(",", keys.stream().map(k -> "?").toList());
        String sql = "SELECT content_hash, embedding FROM embedding_cache WHERE content_hash IN (" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < keys.size(); i++) {
                stmt.setLong(i + 1, keys.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long key = rs.getLong("content_hash");
                    byte[] blob = rs.getBytes("embedding");
                    float[] embedding = deserializeEmbedding(blob);
                    result.put(key, embedding);
                    l1Cache.put(key, embedding);
                }
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Void> put(long key, float[] embedding) {
        l1Cache.put(key, embedding);
        byte[] serialized = serializeEmbedding(embedding);
        long timestamp = System.currentTimeMillis();

        writeBuffer.offer(new WriteEntry(key, serialized, timestamp));
        int size = writeBufferSize.incrementAndGet();

        if (size >= WRITE_BEHIND_BATCH_SIZE && !isShuttingDown.get() && flushPending.compareAndSet(false, true)) {
            return flushAsync();
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> putAll(Map<Long, float[]> embeddings) {
        l1Cache.putAll(embeddings);

        long timestamp = System.currentTimeMillis();
        for (Map.Entry<Long, float[]> entry : embeddings.entrySet()) {
            byte[] serialized = serializeEmbedding(entry.getValue());
            writeBuffer.offer(new WriteEntry(entry.getKey(), serialized, timestamp));
        }
        int size = writeBufferSize.addAndGet(embeddings.size());

        if (size >= WRITE_BEHIND_BATCH_SIZE && !isShuttingDown.get() && flushPending.compareAndSet(false, true)) {
            return flushAsync();
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> flush() {
        if (writeBufferSize.get() == 0) {
            return CompletableFuture.completedFuture(null);
        }
        flushPending.set(true);
        return flushInternal().whenComplete((v, e) -> flushPending.set(false));
    }

    private CompletableFuture<Void> flushInternal() {
        return CompletableFuture.runAsync(() -> {
            List<WriteEntry> batch = new ArrayList<>(WRITE_BEHIND_BATCH_SIZE);
            WriteEntry entry;
            int drained = 0;

            while ((entry = writeBuffer.poll()) != null && drained < WRITE_BEHIND_BATCH_SIZE) {
                batch.add(entry);
                writeBufferSize.decrementAndGet();
                drained++;
            }

            if (!batch.isEmpty()) {
                flushBatch(batch);
            }

            if (writeBufferSize.get() > 0 && !isShuttingDown.get() &&
                writeBufferSize.get() < MAX_WRITE_BUFFER_SIZE) {
                scheduler.submit(() -> {
                    if (!isShuttingDown.get() && flushPending.compareAndSet(false, true)) {
                        flushInternal();
                    }
                });
            }
        }, executor);
    }

    private CompletableFuture<Void> flushAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                flushInternal().join();
            } finally {
                flushPending.set(false);
            }
        }, executor);
    }

    private void flushBatch(List<WriteEntry> batch) {
        if (batch.isEmpty()) return;

        String sql = "INSERT OR REPLACE INTO embedding_cache (content_hash, embedding, created_at) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (WriteEntry entry : batch) {
                stmt.setLong(1, entry.key);
                stmt.setBytes(2, entry.data);
                stmt.setLong(3, entry.timestamp);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to flush batch to cache", e);
        }
    }

    @Override
    public CompletableFuture<Void> clear() {
        l1Cache.invalidateAll();
        writeBuffer.clear();
        writeBufferSize.set(0);
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute("DELETE FROM embedding_cache");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to clear cache", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Long> size() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM embedding_cache")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to get cache size", e);
            }
            return 0L;
        }, executor);
    }

    @Override
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            scheduler.shutdown();

            try {
                flush().get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to flush on shutdown", e);
            }

            l1Cache.invalidateAll();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private ByteBuffer acquireBuffer(int minCapacity) {
        ByteBuffer buffer = bufferPool.pollFirst();
        if (buffer == null || buffer.capacity() < minCapacity) {
            return ByteBuffer.allocate(Math.max(minCapacity, INITIAL_BUFFER_CAPACITY))
                .order(ByteOrder.nativeOrder());
        }
        buffer.clear();
        return buffer;
    }

    private void releaseBuffer(ByteBuffer buffer) {
        if (bufferPool.size() < BUFFER_POOL_SIZE) {
            bufferPool.offerFirst(buffer);
        }
    }

    private byte[] serializeEmbedding(float[] embedding) {
        int requiredCapacity = embedding.length * Float.BYTES;
        ByteBuffer buffer = acquireBuffer(requiredCapacity);
        try {
            buffer.limit(requiredCapacity);
            buffer.asFloatBuffer().put(embedding);
            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } finally {
            releaseBuffer(buffer);
        }
    }

    private float[] deserializeEmbedding(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] embedding = new float[buffer.remaining() / Float.BYTES];
        buffer.asFloatBuffer().get(embedding);
        return embedding;
    }
}
