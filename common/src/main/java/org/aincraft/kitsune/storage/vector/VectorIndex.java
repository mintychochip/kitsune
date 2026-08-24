package org.aincraft.kitsune.storage.vector;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for vector similarity search operations using approximate nearest neighbor (ANN) search.
 * Handles only vector indexing and retrieval, delegating relational data to MetadataStorage.
 *
 * Vectors are identified by ordinal IDs (integer node indices in the graph index).
 * Supports both unrestricted and filtered search operations.
 */
public interface VectorIndex {

    /**
     * Initialize the vector index asynchronously.
     * Loads existing index from disk if available, otherwise prepares for new indexing.
     *
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Shut down the vector index and release all resources.
     * Should save any pending state to disk before returning.
     */
    void shutdown();

    /**
     * Add or update a vector in the index with a given ordinal ID.
     * The ordinal serves as a unique node identifier in the graph index.
     * Marks the index as dirty, requiring rebuild before next search.
     *
     * @param ordinal unique integer identifier for this vector
     * @param embedding the float array containing the vector data
     * @return CompletableFuture that completes when the vector is added
     */
    CompletableFuture<Void> addVector(int ordinal, float[] embedding);

    /**
     * Remove a vector from the index by its ordinal ID.
     * Marks the index as dirty, requiring rebuild before next search.
     *
     * @param ordinal unique identifier of the vector to remove
     * @return CompletableFuture that completes when the vector is removed
     */
    CompletableFuture<Void> removeVector(int ordinal);

    /**
     * Search for vectors similar to the query embedding.
     * Returns results sorted by similarity score (highest first).
     * Automatically rebuilds the index if marked dirty before searching.
     *
     * @param queryEmbedding the query vector to search for
     * @param limit maximum number of results to return
     * @return CompletableFuture with list of VectorSearchResult ordered by score
     */
    CompletableFuture<List<VectorSearchResult>> search(float[] queryEmbedding, int limit);

    /**
     * Search for vectors similar to the query embedding, limited to a set of allowed ordinals.
     * Useful for filtering search results by metadata before vector similarity computation.
     * Returns results sorted by similarity score (highest first).
     *
     * @param queryEmbedding the query vector to search for
     * @param limit maximum number of results to return
     * @param allowedOrdinals set of ordinal IDs to search within; null/empty means no restriction
     * @return CompletableFuture with list of VectorSearchResult ordered by score, only from allowedOrdinals
     */
    CompletableFuture<List<VectorSearchResult>> searchWithFilter(float[] queryEmbedding, int limit, Set<Integer> allowedOrdinals);

    /**
     * Get a vector by its ordinal ID for reranking or other operations.
     * Returns the raw float array representation.
     *
     * @param ordinal the vector's unique identifier
     * @return Optional containing the vector data if it exists
     */
    Optional<float[]> getVector(int ordinal);

    /**
     * Rebuild the internal graph index after batch updates.
     * Compacts the ordinal space to remove holes from deleted vectors.
     * Must be called before searching if the index is marked dirty.
     *
     * @return CompletableFuture that completes when rebuild is finished
     */
    CompletableFuture<Void> rebuildIndex();

    /**
     * Clear all vectors from the index and remove all persistent data.
     * Deletes the on-disk index file completely.
     *
     * @return CompletableFuture that completes when purge is finished
     */
    CompletableFuture<Void> purgeAll();

    /**
     * Get the count of vectors currently in the index.
     * Note: This is the total capacity, including empty slots from deletions.
     * Check isDirty() to see if a rebuild would compact the space.
     *
     * @return number of vector slots in the index
     */
    int size();

    /**
     * Check if the index requires rebuilding due to changes.
     * Returns true when vectors have been added/removed and the graph needs reconstruction.
     * The search operation automatically rebuilds if dirty before searching.
     *
     * @return true if index is marked dirty, false otherwise
     */
    boolean isDirty();

    /**
     * Get the dimension (length) of vectors stored in this index.
     *
     * @return vector dimension
     */
    int getDimension();
}
