package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.RootIdentity;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists indexed storage roots, chunk availability, and searchable documents.
 */
public interface IndexRepository extends AutoCloseable {
    /** Cursor identifying the last candidate coordinate returned by a page. */
    record CandidateCursor(int x, int y, int z) {}

    /** A page of candidate roots and the cursor for the next page. */
    record CandidatePage(List<RootIdentity> roots, CandidateCursor next) {
        /** Defensively copies the page's root list. */
        public CandidatePage {
            roots = List.copyOf(roots);
        }
    }

    /** Applies pending schema migrations. */
    void migrate() throws SQLException;

    /** Marks every persisted chunk as unavailable. */
    void markAllChunksUnavailable() throws SQLException;

    /** Records a chunk's availability and revision. */
    void setChunkAvailable(ChunkKey chunk, boolean available, long revision) throws SQLException;

    /** Replaces the indexed representation of a root at a revision. */
    void replaceRoot(ContainerSnapshot snapshot, long revision) throws SQLException;

    /** Deletes the indexed representation of a root. */
    void deleteRoot(BlockKey key) throws SQLException;

    /**
     * Finds roots in a chunk-coordinate rectangle after an optional cursor.
     *
     * @param worldId world containing the candidate roots
     * @param minChunkX inclusive minimum chunk X coordinate
     * @param maxChunkX inclusive maximum chunk X coordinate
     * @param minChunkZ inclusive minimum chunk Z coordinate
     * @param maxChunkZ inclusive maximum chunk Z coordinate
     * @param after cursor from a previous page, or {@code null}
     * @param limit maximum number of roots to return
     * @return a candidate page and optional continuation cursor
     */
    CandidatePage findCandidates(
        UUID worldId,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        CandidateCursor after,
        int limit
    ) throws SQLException;

    /** Finds the persisted identity for a root, if present. */
    Optional<RootIdentity> findRoot(BlockKey key) throws SQLException;

    /** Loads cached vectors for the given semantic descriptor hashes. */
    Map<SemanticDescriptorHash, Embedding> findEmbeddings(
            EmbeddingProvider provider,
            Set<SemanticDescriptorHash> hashes
    ) throws SQLException;

    /** Stores vectors for semantic descriptor hashes under the provider identity. */
    void putEmbeddings(
            EmbeddingProvider provider,
            Map<SemanticDescriptorHash, Embedding> embeddings
    ) throws SQLException;

    /** Loads searchable documents for the allowed roots using an embedding provider. */
    Map<BlockKey, List<IndexedItem>> loadDocuments(Set<BlockKey> allowed, EmbeddingProvider provider) throws SQLException;

    /** Recomputes embeddings for all persisted searchable documents. */
    void reembedAll(EmbeddingProvider provider) throws SQLException;

    /** A full-text search hit with BM25 rank and item metadata. */
    record FullTextMatch(
        RootIdentity root,
        ItemPath path,
        int amount,
        ItemDescriptor descriptor,
        double bm25
    ) {}

    /**
     * Finds items matching a prepared FTS5 MATCH expression within chunk bounds.
     *
     * @param matchExpression FTS5 MATCH expression (never concatenated into SQL)
     * @param worldId world containing candidate roots
     * @param minChunkX inclusive minimum chunk X coordinate
     * @param maxChunkX inclusive maximum chunk X coordinate
     * @param minChunkZ inclusive minimum chunk Z coordinate
     * @param maxChunkZ inclusive maximum chunk Z coordinate
     * @param limit maximum number of hits to return
     * @return hits ordered by BM25 rank ascending
     */
    List<FullTextMatch> findFullTextMatches(
        String matchExpression,
        UUID worldId,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        int limit
    ) throws SQLException;

}
