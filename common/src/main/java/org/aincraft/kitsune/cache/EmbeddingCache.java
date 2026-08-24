package org.aincraft.kitsune.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Cache for item embeddings.
 * Keys are long hash codes (combines hashCode + length for uniqueness).
 * Values are embedding vectors.
 */
public interface EmbeddingCache {
    /**
     * Get cached embedding for a hash key.
     *
     * @param key The content hash key
     * @return The cached embedding, or empty if not cached
     */
    CompletableFuture<Optional<float[]>> get(long key);

    /**
     * Get multiple cached embeddings.
     * Returns map of key -> embedding for found entries only.
     *
     * @param keys The keys to look up
     * @return Map of found embeddings (missing entries excluded)
     */
    CompletableFuture<Map<Long, float[]>> getAll(List<Long> keys);

    /**
     * Store embedding for a hash key.
     *
     * @param key The content hash key
     * @param embedding The embedding vector to cache
     */
    CompletableFuture<Void> put(long key, float[] embedding);

    /**
     * Store multiple embeddings.
     * More efficient than individual puts for bulk operations.
     *
     * @param embeddings Map of key to embedding
     */
    CompletableFuture<Void> putAll(Map<Long, float[]> embeddings);

    /**
     * Flush any pending writes (for write-behind implementations).
     */
    CompletableFuture<Void> flush();

    /**
     * Initialize the cache.
     */
    CompletableFuture<Void> initializeAsync();

    /**
     * Shutdown and release resources.
     */
    void shutdown();

    /**
     * Clear all cached embeddings.
     */
    CompletableFuture<Void> clear();

    /**
     * Get cache statistics.
     *
     * @return Number of cached embeddings
     */
    CompletableFuture<Long> size();
}
