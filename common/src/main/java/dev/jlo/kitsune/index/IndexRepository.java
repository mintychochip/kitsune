package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.RootIdentity;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface IndexRepository extends AutoCloseable {
    void migrate() throws SQLException;

    void markAllChunksUnavailable() throws SQLException;

    void setChunkAvailable(ChunkKey chunk, boolean available, long revision) throws SQLException;

    void replaceRoot(ContainerSnapshot snapshot, long revision) throws SQLException;

    void deleteRoot(BlockKey key) throws SQLException;

    java.util.List<RootIdentity> findCandidates(UUID worldId, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) throws SQLException;

    Map<BlockKey, java.util.List<IndexedItem>> loadDocuments(Set<BlockKey> allowed, EmbeddingProvider provider) throws SQLException;

    void reembedAll(EmbeddingProvider provider) throws SQLException;
}
