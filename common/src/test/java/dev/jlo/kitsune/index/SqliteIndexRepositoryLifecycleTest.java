package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;

import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies repository reopen and embedding-provider lifecycle behavior. */
class SqliteIndexRepositoryLifecycleTest {

    /** Provides a temporary SQLite repository and direct inspection helpers. */
    static final class RepositoryTestFixture implements AutoCloseable {
        private final Path database;
        private final IndexRepository repository;
        private Connection connection;

        /** Opens and migrates the repository at the supplied database path. */
        RepositoryTestFixture(Path database) throws Exception {
            this.database = database;
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            connection.setAutoCommit(true);
            this.repository = new SqliteIndexRepository(connection);
            repository().migrate();
        }

        IndexRepository repository() {
            return repository;
        }

        BlockKey key(int x, int y, int z) {
            return new BlockKey(UUID.randomUUID(), x, y, z);
        }

        Connection connection() {
            return connection;
        }

        int containerCount() throws Exception {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM containers");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }

        int itemCount() throws Exception {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM items");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }

        List<String> materialKeys(BlockKey root) throws Exception {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT i.descriptor FROM items i JOIN containers c ON c.id = i.container_id " +
                            "WHERE c.world_uuid = ? AND c.x = ? AND c.y = ? AND c.z = ?")) {
                ps.setString(1, root.worldId().toString());
                ps.setInt(2, root.x());
                ps.setInt(3, root.y());
                ps.setInt(4, root.z());
                List<String> keys = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(DescriptorCodec.decode(rs.getBytes(1)).materialKey());
                    }
                }
                return keys;
            }
        }

        ContainerSnapshot snapshot(BlockKey root, String materialKey) {
            ItemDescriptor descriptor = ItemDescriptor.builder().materialKey(materialKey).amount(1).build();
            EmbeddingProvider provider = new SparseTagEmbeddingProvider();
            IndexedItem item = new IndexedItem(
                    new ItemPath(List.of(new ItemPathStep("Storage", 0))),
                    1,
                    descriptor,
                    provider.embed(descriptor));
            return new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, List.of(item));
        }

        RootIdentity rootIdentity(BlockKey root, String materialKey) {
            return new RootIdentity(root, "chest", new byte[]{1, 2, 3}, 1);
        }

        EmbeddingProvider provider(String id, int version) {
            return new SparseTagEmbeddingProvider() {
                @Override
                public String id() { return id; }
                @Override
                public int version() { return version; }
            };
        }

        void insertAvailable(BlockKey root, String materialKey) throws Exception {
            ChunkKey chunk = root.chunkKey();
            repository.replaceRoot(snapshot(root, materialKey), 1);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO chunks (world_uuid, chunk_x, chunk_z, available, revision) " +
                            "VALUES (?, ?, ?, 1, 1)")) {
                ps.setString(1, chunk.worldId().toString());
                ps.setInt(2, chunk.x());
                ps.setInt(3, chunk.z());
                ps.executeUpdate();
            }
        }

        @Override
        public void close() throws Exception {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /** Re-embeds persisted documents only when the provider metadata changes. */
    @Test
    void openWithProviderReembedsOnlyWhenNeeded(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("lifecycle.db");
        BlockKey root;
        try (RepositoryTestFixture first = new RepositoryTestFixture(database)) {
            root = first.key(0, 64, 0);
            first.insertAvailable(root, "diamond");
            assertFalse(first.repository().findCandidates(
                    root.worldId(), 0, 0, 0, 0, null, 10).roots().isEmpty());
        }
        try (RepositoryTestFixture ignored = new RepositoryTestFixture(database)) {
            // existing path resets chunks
        }
        EmbeddingProvider fakeV2 = fakeProvider("builtin:fake", 2);
        try (var repo = SqliteIndexRepository.open(database, fakeV2)) {
            assertTrue(repo.findCandidates(
                    root.worldId(), 0, 0, 0, 0, null, 10).roots().isEmpty(),
                    "chunks must remain unavailable after reopen");
            Map<BlockKey, List<IndexedItem>> docs = repo.loadDocuments(Set.of(root), fakeV2);
            assertEquals(2, docs.get(root).getFirst().embedding().providerVersion());
        }
    }

    /** Creates an embedding provider with deterministic identity and payload behavior. */
    private static EmbeddingProvider fakeProvider(String id, int version) {
        return new EmbeddingProvider() {
            @Override public String id() { return id; }
            @Override public int version() { return version; }
            @Override public dev.jlo.kitsune.api.embedding.Embedding embed(dev.jlo.kitsune.model.ItemDescriptor d) {
                return new dev.jlo.kitsune.api.embedding.Embedding() {
                    @Override public String providerId() { return id; }
                    @Override public int providerVersion() { return version; }
                    @Override public double norm() { return 1.0; }
                    @Override public byte[] encode() { return new byte[]{1}; }
                    @Override public double cosine(dev.jlo.kitsune.api.embedding.Embedding other) { return 0; }
                };
            }
            @Override public dev.jlo.kitsune.api.embedding.Embedding embedQuery(String q) {
                return embed(null);
            }
            @Override public dev.jlo.kitsune.api.embedding.Embedding decode(byte[] payload, double norm) {
                return embed(null);
            }
        };
    }
}
