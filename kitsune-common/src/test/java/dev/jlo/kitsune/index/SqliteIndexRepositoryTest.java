package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.FullTextQuery;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Exercises SQLite repository migration, replacement, and query behavior. */
class SqliteIndexRepositoryTest {

    /** Provides a temporary SQLite repository and direct inspection helpers. */
    static final class RepositoryTestFixture implements AutoCloseable {
        private final IndexRepository repository;
        private final Connection connection;

        /** Opens and migrates the repository at the supplied database path. */
        RepositoryTestFixture(Path database) throws Exception {
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

        long containerRevision(BlockKey root) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT revision FROM containers " +
                            "WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                statement.setString(1, root.worldId().toString());
                statement.setInt(2, root.x());
                statement.setInt(3, root.y());
                statement.setInt(4, root.z());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("Missing root");
                    }
                    return result.getLong(1);
                }
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
            return snapshot(root, materialKey, new SparseTagEmbeddingProvider());
        }

        ContainerSnapshot snapshot(
                BlockKey root, String materialKey, EmbeddingProvider provider) {
            ItemDescriptor descriptor = ItemDescriptor.builder()
                    .materialKey(materialKey)
                    .amount(1)
                    .build();
            IndexedItem item = new IndexedItem(
                    new ItemPath(List.of(new ItemPathStep("Storage", 0))),
                    1,
                    descriptor,
                    provider.embed(descriptor));
            return new ContainerSnapshot(
                    root, "chest", new byte[] {1, 2, 3}, List.of(item));
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


        ContainerSnapshot snapshotWithMaterial(BlockKey root, String materialKey, ItemPath path) {
            return snapshotWithMaterial(root, materialKey, path, new SparseTagEmbeddingProvider());
        }

        ContainerSnapshot snapshotWithMaterial(
                BlockKey root, String materialKey, ItemPath path, EmbeddingProvider provider) {
            ItemDescriptor descriptor = ItemDescriptor.builder()
                    .materialKey(materialKey)
                    .amount(1)
                    .build();
            IndexedItem item = new IndexedItem(path, 1, descriptor, provider.embed(descriptor));
            return new ContainerSnapshot(root, "chest", new byte[] {1, 2, 3}, List.of(item));
        }

        ContainerSnapshot snapshotWithDescriptor(BlockKey root, ItemDescriptor descriptor, ItemPath path) {
            EmbeddingProvider provider = new SparseTagEmbeddingProvider();
            IndexedItem item = new IndexedItem(path, descriptor.amount(), descriptor, provider.embed(descriptor));
            return new ContainerSnapshot(root, "chest", new byte[] {1, 2, 3}, List.of(item));
        }

        ContainerSnapshot snapshotWithItems(BlockKey root, List<IndexedItem> items) {
            return new ContainerSnapshot(root, "chest", new byte[] {1, 2, 3}, items);
        }

        void makeChunkAvailable(ChunkKey chunk, boolean available) throws Exception {
            repository.setChunkAvailable(chunk, available, 1);
        }

        int containerId(BlockKey root) throws Exception {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM containers WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, root.worldId().toString());
                ps.setInt(2, root.x());
                ps.setInt(3, root.y());
                ps.setInt(4, root.z());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Missing container");
                    }
                    return rs.getInt(1);
                }
            }
        }

        int itemSearchCountForContainer(BlockKey root) throws Exception {
            int containerId = containerId(root);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM item_search s JOIN items i ON i.id = s.item_id "
                            + "WHERE i.container_id = ?")) {
                ps.setInt(1, containerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }

        List<IndexRepository.FullTextMatch> findFullText(
                BlockKey root, String query, int limit) throws Exception {
            ChunkKey chunk = root.chunkKey();
            return repository.findFullTextMatches(
                    FullTextQuery.parse(query).matchExpression(),
                    root.worldId(),
                    chunk.x(),
                    chunk.x(),
                    chunk.z(),
                    chunk.z(),
                    limit);
        }

        @Override
        public void close() throws Exception {
            if (connection != null) {
                connection.close();
            }
        }
    }

    @Test
    void migratesEmptyDatabaseAndReopensWithoutMutation(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("index.db");
        try (RepositoryTestFixture first = new RepositoryTestFixture(database)) {
            first.repository().migrate();
            assertEquals(3, schemaVersion(first.connection()));
        }
        try (RepositoryTestFixture second = new RepositoryTestFixture(database)) {
            second.repository().migrate();
            assertEquals(3, schemaVersion(second.connection()));
        }
    }

    @Test
    void replacementDeletesOldDocumentsAtomically(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("replace.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshot(root, "old-item"), 1);
            fixture.repository().replaceRoot(fixture.snapshot(root, "new-item"), 2);
            assertEquals(List.of("new-item"), fixture.materialKeys(root));
        }
    }

    @Test
    void replacementRollbackPreservesOldDocuments(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture =
                     new RepositoryTestFixture(tempDir.resolve("rollback.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshot(root, "old-item"), 1);
            ItemDescriptor replacementDescriptor = ItemDescriptor.builder()
                    .materialKey("new-item")
                    .amount(1)
                    .build();
            Embedding failingEmbedding = new Embedding() {
                @Override
                public String providerId() {
                    return "builtin:failing";
                }

                @Override
                public int providerVersion() {
                    return 1;
                }

                @Override
                public double norm() {
                    return 1.0;
                }

                @Override
                public byte[] encode() {
                    throw new IllegalStateException("vector encoding failed");
                }

                @Override
                public double cosine(Embedding other) {
                    return 0.0;
                }
            };
            ContainerSnapshot replacement = new ContainerSnapshot(
                    root,
                    "barrel",
                    new byte[] {9, 8, 7},
                    List.of(new IndexedItem(
                            new ItemPath(List.of(new ItemPathStep("Storage", 0))),
                            1,
                            replacementDescriptor,
                            failingEmbedding)));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.repository().replaceRoot(replacement, 2));

            assertEquals("vector encoding failed", failure.getMessage());
            assertEquals(List.of("old-item"), fixture.materialKeys(root));
            assertEquals(1, fixture.containerCount());
            assertEquals(1, fixture.itemCount());
            assertEquals(1, fixture.containerRevision(root));
            assertTrue(fixture.connection().getAutoCommit());
        }
    }

    @Test
    void rootLookupReturnsPersistedIdentity(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("lookup.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.insertAvailable(root, "diamond");

            RootIdentity identity = fixture.repository().findRoot(root).orElseThrow();

            assertEquals(root, identity.key());
            assertEquals("chest", identity.blockType());
            assertArrayEquals(new byte[] {1, 2, 3}, identity.fingerprint());
            assertEquals(1L, identity.revision());
            assertTrue(fixture.repository().findRoot(fixture.key(16, 64, 0)).isEmpty());
        }
    }

    @Test
    void embeddingsRoundTripBySemanticHashAndIgnoreAmount(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("cache.db"))) {
            EmbeddingProvider provider = new SparseTagEmbeddingProvider();
            ItemDescriptor one = ItemDescriptor.builder()
                    .materialKey("minecraft:cobblestone")
                    .amount(1)
                    .build();
            ItemDescriptor stack = ItemDescriptor.builder()
                    .materialKey("minecraft:cobblestone")
                    .amount(64)
                    .build();
            SemanticDescriptorHash hash = SemanticDescriptorHash.of(one);
            Embedding embedded = provider.embed(one);

            fixture.repository().putEmbeddings(provider, Map.of(hash, embedded));

            Map<SemanticDescriptorHash, Embedding> found = fixture.repository().findEmbeddings(
                    provider, Set.of(SemanticDescriptorHash.of(stack)));
            assertEquals(1, found.size());
            assertArrayEquals(embedded.encode(), found.get(hash).encode());
            assertEquals(embedded.norm(), found.get(hash).norm(), 1e-9);
        }
    }

    @Test
    void embeddingsMissWhenProviderIdentityDiffers(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("cache-miss.db"))) {
            EmbeddingProvider stored = new SparseTagEmbeddingProvider();
            ItemDescriptor descriptor = ItemDescriptor.builder()
                    .materialKey("minecraft:cobblestone")
                    .amount(1)
                    .build();
            SemanticDescriptorHash hash = SemanticDescriptorHash.of(descriptor);
            fixture.repository().putEmbeddings(stored, Map.of(hash, stored.embed(descriptor)));

            EmbeddingProvider other = fixture.provider("other", 1);
            assertTrue(fixture.repository().findEmbeddings(other, Set.of(hash)).isEmpty());
        }
    }

    @Test
    void migrateUpgradesV1DatabaseAndCreatesEmbeddingsTable(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("v1.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             java.sql.Statement statement = connection.createStatement()) {
            for (String sql : v1Statements()) {
                statement.execute(sql);
            }
            statement.execute("INSERT INTO schema_metadata (key, value) VALUES ('schema_version', '1')");
        }

        try (IndexRepository ignored = SqliteIndexRepository.open(database);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assertEquals(3, schemaVersion(connection));
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM embeddings");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            assertTrue(tableExists(connection, "item_search"));
            assertTrue(tableExists(connection, "item_fts"));
        }
    }

    @Test
    void splitStatementsKeepsCreateTriggerAsOneStatement() {
        String sql = """
                CREATE TABLE item_search (item_id INTEGER PRIMARY KEY);
                CREATE TRIGGER item_search_ai AFTER INSERT ON item_search BEGIN
                  INSERT INTO item_fts(rowid, material) VALUES (new.item_id, new.material);
                END;
                """;
        List<String> statements = SqliteIndexRepository.splitStatements(sql);
        assertEquals(2, statements.size());
        assertTrue(statements.get(1).startsWith("CREATE TRIGGER"));
        assertTrue(statements.get(1).endsWith("END"));
    }

    @Test
    void migrateUpgradesV2DatabaseAndBackfillsItemSearch(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("v2-backfill.db");
        UUID worldId = UUID.randomUUID();
        BlockKey root = new BlockKey(worldId, 0, 64, 0);
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:oak_planks")
                .amount(1)
                .build();
        EmbeddingProvider provider = new SparseTagEmbeddingProvider();
        Embedding embedding = provider.embed(descriptor);
        createV2Database(database, root, descriptor, embedding, null);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(true);
            SqliteIndexRepository repository = new SqliteIndexRepository(connection);
            repository.migrate();
            assertEquals(3, schemaVersion(connection));
            assertEquals(tableCount(connection, "items"), tableCount(connection, "item_search"));
            assertTrue(tableExists(connection, "item_fts"));

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO chunks (world_uuid, chunk_x, chunk_z, available, revision) "
                            + "VALUES (?, ?, ?, 1, 1)")) {
                ps.setString(1, worldId.toString());
                ps.setInt(2, root.chunkKey().x());
                ps.setInt(3, root.chunkKey().z());
                ps.executeUpdate();
            }

            Map<BlockKey, List<IndexedItem>> documents =
                    repository.loadDocuments(Set.of(root), provider);
            IndexedItem loaded = documents.get(root).getFirst();
            assertArrayEquals(embedding.encode(), loaded.embedding().encode());
            assertEquals(embedding.norm(), loaded.embedding().norm(), 1e-9);
        }
    }

    @Test
    void migrateV2RollsBackWhenBackfillFails(@TempDir Path tempDir) throws Exception {
        Path corruptDatabase = tempDir.resolve("v2-corrupt.db");
        BlockKey root = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:oak_planks")
                .amount(1)
                .build();
        EmbeddingProvider provider = new SparseTagEmbeddingProvider();
        Embedding embedding = provider.embed(descriptor);
        createV2Database(corruptDatabase, root, descriptor, embedding, new byte[] {0x00});

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + corruptDatabase.toAbsolutePath())) {
            connection.setAutoCommit(true);
            SqliteIndexRepository repository = new SqliteIndexRepository(connection);
            assertThrows(IllegalArgumentException.class, repository::migrate);
            assertEquals(2, schemaVersion(connection));
            assertFalse(tableExists(connection, "item_search"));
            assertFalse(tableExists(connection, "item_fts"));
        }

        Path cleanDatabase = tempDir.resolve("v2-clean.db");
        createV2Database(cleanDatabase, root, descriptor, embedding, null);
        try (IndexRepository ignored = SqliteIndexRepository.open(cleanDatabase);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + cleanDatabase.toAbsolutePath())) {
            assertEquals(3, schemaVersion(connection));
            assertEquals(tableCount(connection, "items"), tableCount(connection, "item_search"));
        }
    }

    @Test
    void migrateUpgradesV1DatabaseThroughEmbeddingsCacheAndFts(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("v1-to-v3.db");
        UUID worldId = UUID.randomUUID();
        BlockKey root = new BlockKey(worldId, 0, 64, 0);
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:oak_planks")
                .amount(1)
                .build();
        EmbeddingProvider provider = new SparseTagEmbeddingProvider();
        Embedding embedding = provider.embed(descriptor);
        createV1Database(database, root, descriptor, embedding);

        try (IndexRepository repository = SqliteIndexRepository.open(database);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assertEquals(3, schemaVersion(connection));
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM embeddings");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            assertEquals(tableCount(connection, "items"), tableCount(connection, "item_search"));
            assertTrue(tableExists(connection, "item_fts"));

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO chunks (world_uuid, chunk_x, chunk_z, available, revision) "
                            + "VALUES (?, ?, ?, 1, 1)")) {
                ps.setString(1, worldId.toString());
                ps.setInt(2, root.chunkKey().x());
                ps.setInt(3, root.chunkKey().z());
                ps.executeUpdate();
            }

            Map<BlockKey, List<IndexedItem>> documents =
                    repository.loadDocuments(Set.of(root), provider);
            IndexedItem loaded = documents.get(root).getFirst();
            assertEquals("minecraft:oak_planks", loaded.descriptor().materialKey());
            assertArrayEquals(embedding.encode(), loaded.embedding().encode());
            assertEquals(embedding.norm(), loaded.embedding().norm(), 1e-9);
        }
    }

    @Test
    void migrateRejectsUnsupportedSchemaVersion(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("v2-unsupported.db");
        BlockKey root = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:oak_planks")
                .amount(1)
                .build();
        EmbeddingProvider provider = new SparseTagEmbeddingProvider();
        Embedding embedding = provider.embed(descriptor);
        createV2Database(database, root, descriptor, embedding, null);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(true);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE schema_metadata SET value = ? WHERE key = ?")) {
                ps.setString(1, "99");
                ps.setString(2, "schema_version");
                ps.executeUpdate();
            }
        }

        SQLException failure = assertThrows(
                SQLException.class,
                () -> SqliteIndexRepository.open(database));
        assertTrue(failure.getMessage().contains("Unsupported schema version: 99"));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            assertEquals(99, schemaVersion(connection));
            assertFalse(tableExists(connection, "item_fts"));
        }
    }


    @Test
    void deletionCascadesDocuments(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("delete.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshot(root, "diamond"), 1);
            fixture.repository().deleteRoot(root);
            assertEquals(0, fixture.containerCount());
            assertEquals(0, fixture.itemCount());
        }
    }

    @Test
    void candidatesAreBoundedByWorldAndChunkRange(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("bounds.db"))) {
            BlockKey inside = fixture.key(0, 64, 0);
            BlockKey outside = fixture.key(64, 64, 0);
            fixture.insertAvailable(inside, "diamond");
            fixture.insertAvailable(outside, "diamond");
            IndexRepository.CandidatePage page = fixture.repository().findCandidates(
                    inside.worldId(), -1, 1, -1, 1, null, 10);
            assertEquals(List.of(fixture.rootIdentity(inside, "diamond")), page.roots());
            assertEquals(null, page.next());
        }
    }

    @Test
    void candidatesUseKeysetPagesWithoutSkipsOrDuplicates(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("pages.db"))) {
            UUID worldId = UUID.randomUUID();
            BlockKey first = new BlockKey(worldId, 0, 64, 0);
            BlockKey second = new BlockKey(worldId, 0, 65, 0);
            BlockKey third = new BlockKey(worldId, 1, 64, 0);
            fixture.insertAvailable(first, "first");
            fixture.insertAvailable(second, "second");
            fixture.insertAvailable(third, "third");

            IndexRepository.CandidatePage firstPage = fixture.repository().findCandidates(
                    worldId, -1, 2, -1, 1, null, 2);
            IndexRepository.CandidatePage secondPage = fixture.repository().findCandidates(
                    worldId, -1, 2, -1, 1, firstPage.next(), 2);

            assertEquals(
                    List.of(fixture.rootIdentity(first, "first"), fixture.rootIdentity(second, "second")),
                    firstPage.roots()
            );
            assertEquals(List.of(fixture.rootIdentity(third, "third")), secondPage.roots());
            assertEquals(null, secondPage.next());

            List<RootIdentity> flattened = new ArrayList<>(firstPage.roots());
            flattened.addAll(secondPage.roots());
            assertEquals(
                    List.of(
                            fixture.rootIdentity(first, "first"),
                            fixture.rootIdentity(second, "second"),
                            fixture.rootIdentity(third, "third")
                    ),
                    flattened
            );
        }
    }

    @Test
    void candidatePageRejectsNonPositiveLimit(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("limit.db"))) {
            UUID worldId = UUID.randomUUID();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.repository().findCandidates(
                            worldId, -1, 1, -1, 1, null, 0)
            );
        }
    }

    @Test
    void candidatePageQueryUsesChunkIndex(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("query-plan.db"))) {
            String sql = "EXPLAIN QUERY PLAN " + SqliteIndexRepository.candidatePageSql(false);
            List<String> details = new ArrayList<>();
            try (PreparedStatement statement = fixture.connection().prepareStatement(sql)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setInt(2, -10);
                statement.setInt(3, 10);
                statement.setInt(4, -10);
                statement.setInt(5, 10);
                statement.setInt(6, 129);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) details.add(result.getString(4));
                }
            }
            assertTrue(details.stream().anyMatch(detail -> detail.contains("containers_by_chunk")), details.toString());
            assertTrue(
                    details.stream().anyMatch(
                            detail -> detail.contains("sqlite_autoindex_chunks_1")
                    ),
                    details.toString()
            );
            assertTrue(
                    details.stream().noneMatch(
                            detail -> detail.contains("sqlite_autoindex_containers_1")
                    ),
                    details.toString()
            );
        }
    }

    @Test
    void startupResetMakesPersistedChunksUnavailable(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("restart.db");
        BlockKey root;
        try (RepositoryTestFixture first = new RepositoryTestFixture(database)) {
            root = first.key(0, 64, 0);
            first.insertAvailable(root, "diamond");
            assertFalse(first.repository().findCandidates(
                    root.worldId(), 0, 0, 0, 0, null, 10).roots().isEmpty());
        }
        try (RepositoryTestFixture second = new RepositoryTestFixture(database)) {
            second.repository().markAllChunksUnavailable();
            assertTrue(second.repository().findCandidates(
                    root.worldId(), 0, 0, 0, 0, null, 10).roots().isEmpty());
        }
    }

    @Test
    void reembedReplacesEmbeddingWithFakeProvider(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("reembed.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemDescriptor descriptor = ItemDescriptor.builder().materialKey("diamond").amount(1).build();
            EmbeddingProvider fake = fakeProvider("builtin:fake", 1, descriptor);
            IndexedItem item = new IndexedItem(
                    new ItemPath(List.of(new ItemPathStep("Storage", 0))),
                    1,
                    descriptor,
                    fake.embed(descriptor));
            fixture.repository().replaceRoot(new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, List.of(item)), 1);
            EmbeddingProvider updated = fakeProvider("builtin:fake", 2, descriptor);
            fixture.repository().reembedAll(updated);
            Map<BlockKey, List<IndexedItem>> documents = fixture.repository()
                    .loadDocuments(Set.of(root), updated);
            assertEquals(2, documents.get(root).getFirst().embedding().providerVersion());
            assertEquals("diamond", documents.get(root).getFirst().descriptor().materialKey());
        }
    }

    @Test
    void reembedAllEmbedsEachSemanticHashOnceAndRefreshesCache(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("reembed-unique.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemDescriptor one = ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(1).build();
            ItemDescriptor stack = ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(64).build();
            EmbeddingProvider original = fakeProvider("builtin:count", 1, one);
            List<IndexedItem> items = List.of(
                    new IndexedItem(new ItemPath(List.of(new ItemPathStep("Storage", 0))), 1, one, original.embed(one)),
                    new IndexedItem(new ItemPath(List.of(new ItemPathStep("Storage", 1))), 64, stack, original.embed(stack))
            );
            fixture.repository().replaceRoot(
                    new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, items), 1);

            AtomicInteger embedCalls = new AtomicInteger();
            EmbeddingProvider updated = countingProvider("builtin:count", 2, embedCalls);
            fixture.repository().reembedAll(updated);

            assertEquals(1, embedCalls.get());
            Map<SemanticDescriptorHash, Embedding> cached = fixture.repository().findEmbeddings(
                    updated, Set.of(SemanticDescriptorHash.of(one)));
            assertEquals(1, cached.size());
            assertEquals(2, cached.values().iterator().next().providerVersion());
        }
    }

    @Test
    void providerMismatchRejected(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("provider.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemDescriptor descriptor = ItemDescriptor.builder().materialKey("diamond").amount(1).build();
            EmbeddingProvider original = fixture.provider("builtin:stable", 1);
            IndexedItem item = new IndexedItem(
                    new ItemPath(List.of(new ItemPathStep("Storage", 0))),
                    1,
                    descriptor,
                    original.embed(descriptor));
            fixture.repository().replaceRoot(new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, List.of(item)), 1);
            EmbeddingProvider different = fixture.provider("builtin:other", 2);
            IllegalStateException exception = new IllegalStateException("Expected provider mismatch");
            try {
                fixture.repository().loadDocuments(Set.of(root), different);
            } catch (IllegalStateException ex) {
                exception = ex;
            }
            assertTrue(exception.getMessage().contains("Provider mismatch"));
        }
    }

    @Test
    void codecRejectsTooManyPathSteps() {
        List<ItemPathStep> steps = new ArrayList<>();
        for (int i = 0; i < 65536; i++) {
            steps.add(new ItemPathStep("x", i));
        }
        IllegalArgumentException exception = new IllegalArgumentException("Expected too many steps");
        try {
            ItemPathCodec.encode(new ItemPath(steps));
        } catch (IllegalArgumentException ex) {
            exception = ex;
        }
        assertTrue(exception.getMessage().contains("Too many path steps"));
    }

    @Test
    void codecRejectsTooManyStrings() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 65536; i++) {
            values.add("x");
        }
        IllegalArgumentException exception = new IllegalArgumentException("Expected too many strings");
        try {
            ItemDescriptor.Builder builder = ItemDescriptor.builder().materialKey("diamond").amount(1);
            for (String value : values) builder.addDisplayText(value);
            DescriptorCodec.encode(builder.build());
        } catch (IllegalArgumentException ex) {
            exception = ex;
        }
        assertTrue(exception.getMessage().contains("Too many strings"));
    }

    @Test
    void codecRejectsUnsupportedDescriptorVersion() {
        IllegalArgumentException exception = new IllegalArgumentException("Expected unsupported version");
        try {
            DescriptorCodec.decode(new byte[]{3});
        } catch (IllegalArgumentException ex) {
            exception = ex;
        }
        assertTrue(exception.getMessage().contains("Unsupported descriptor version"));
    }

    @Test
    void reembedBatchesAt256(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("batch.db"))) {
            List<IndexedItem> items = new ArrayList<>();
            for (int i = 0; i < 257; i++) {
                ItemDescriptor descriptor = ItemDescriptor.builder().materialKey("item-" + i).amount(1).build();
                items.add(new IndexedItem(
                        new ItemPath(List.of(new ItemPathStep("Storage", i))),
                        1,
                        descriptor,
                        fakeProvider("builtin:batch", 1, descriptor).embed(descriptor)));
            }
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, items), 1);
            assertEquals(257, fixture.itemCount());
            EmbeddingProvider updated = fakeProvider("builtin:batch", 2, null);
            fixture.repository().reembedAll(updated);
            assertEquals(257, fixture.itemCount());
            try (PreparedStatement ps = fixture.connection().prepareStatement("SELECT COUNT(*) FROM items WHERE provider_version = 2")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(257, rs.getInt(1));
                }
            }
            Map<BlockKey, List<IndexedItem>> documents = fixture.repository().loadDocuments(Set.of(root), updated);
            assertEquals(257, documents.get(root).size());
            for (IndexedItem item : documents.get(root)) {
                assertEquals(2, item.embedding().providerVersion());
            }
        }
    }

    @Test
    void reembedBatchesAt256ProviderThrowAt257(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("batch-fail.db"))) {
            List<IndexedItem> items = new ArrayList<>();
            AtomicInteger call = new AtomicInteger();
            EmbeddingProvider throwingProvider = new EmbeddingProvider() {
                @Override public String id() { return "builtin:batch"; }
                @Override public int version() { return 2; }
                @Override public Embedding embed(dev.jlo.kitsune.model.ItemDescriptor d) {
                    if (call.incrementAndGet() > 256) {
                        throw new IllegalArgumentException("Embedding provider mismatch");
                    }
                    return fakeProvider("builtin:batch", 2, null).embed(d);
                }
                @Override public Embedding embedQuery(String q) { return fakeProvider("builtin:batch", 2, null).embedQuery(q); }
                @Override public Embedding decode(byte[] payload, double norm) { return fakeProvider("builtin:batch", 2, null).decode(payload, norm); }
            };
            for (int i = 0; i < 257; i++) {
                ItemDescriptor descriptor = ItemDescriptor.builder().materialKey("item-" + i).amount(1).build();
                items.add(new IndexedItem(
                        new ItemPath(List.of(new ItemPathStep("Storage", i))),
                        1,
                        descriptor,
                        fakeProvider("builtin:batch", 1, descriptor).embed(descriptor)));
            }
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(new ContainerSnapshot(root, "chest", new byte[]{1, 2, 3}, items), 1);
            assertEquals(257, fixture.itemCount());
            try {
                fixture.repository().reembedAll(throwingProvider);
                fail("Expected failure");
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().contains("Embedding provider mismatch"));
            }
            assertEquals(256, countProviderVersion(fixture.connection(), 2));
            assertEquals(1, countProviderVersion(fixture.connection(), 1));
            assertTrue(fixture.connection().getAutoCommit());
        }
    }

    @Test
    void reembedRejectsEmbeddingWithWrongProviderIdentity(@TempDir Path tempDir)
            throws Exception {
        try (RepositoryTestFixture fixture =
                     new RepositoryTestFixture(tempDir.resolve("reembed-identity.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshot(root, "diamond"), 1);
            EmbeddingProvider provider = new EmbeddingProvider() {
                @Override
                public String id() {
                    return "builtin:expected";
                }

                @Override
                public int version() {
                    return SparseTagEmbeddingProvider.VERSION + 1;
                }

                @Override
                public Embedding embed(ItemDescriptor descriptor) {
                    return new FakeEmbedding("builtin:wrong", 99, descriptor);
                }

                @Override
                public Embedding embedQuery(String query) {
                    return new FakeEmbedding("builtin:wrong", 99, null);
                }

                @Override
                public Embedding decode(byte[] payload, double norm) {
                    return new FakeEmbedding("builtin:wrong", 99, null);
                }
            };

            assertThrows(IllegalStateException.class,
                    () -> fixture.repository().reembedAll(provider));
            assertEquals(1, countProviderVersion(
                fixture.connection(),
                SparseTagEmbeddingProvider.VERSION
            ));
            assertEquals(0, countProviderVersion(
                fixture.connection(),
                SparseTagEmbeddingProvider.VERSION + 1
            ));
            assertTrue(fixture.connection().getAutoCommit());
        }
    }

    @Test
    void loadDocumentsRejectsDecodedEmbeddingWithWrongProviderIdentity(
            @TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture =
                     new RepositoryTestFixture(tempDir.resolve("decode-identity.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemDescriptor descriptor = ItemDescriptor.builder()
                    .materialKey("diamond")
                    .amount(1)
                    .build();
            EmbeddingProvider storedProvider =
                    fakeProvider("builtin:expected", 2, descriptor);
            fixture.repository().replaceRoot(
                    fixture.snapshot(root, "diamond", storedProvider), 1);
            EmbeddingProvider badDecoder = new EmbeddingProvider() {
                @Override
                public String id() {
                    return "builtin:expected";
                }

                @Override
                public int version() {
                    return 2;
                }

                @Override
                public Embedding embed(ItemDescriptor item) {
                    return new FakeEmbedding(id(), version(), item);
                }

                @Override
                public Embedding embedQuery(String query) {
                    return new FakeEmbedding(id(), version(), descriptor);
                }

                @Override
                public Embedding decode(byte[] payload, double norm) {
                    return new FakeEmbedding("builtin:wrong", version(), descriptor);
                }
            };

            assertThrows(IllegalStateException.class,
                    () -> fixture.repository().loadDocuments(Set.of(root), badDecoder));
        }
    }

    @Test
    void loadDocumentsBatchesAllowedKeysAndRetainsEmptyRoots(
            @TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture =
                     new RepositoryTestFixture(tempDir.resolve("batch-keys.db"))) {
            EmbeddingProvider provider =
                    fakeProvider("builtin:allowed", 1, null);
            UUID worldId = UUID.randomUUID();
            java.util.LinkedHashSet<BlockKey> allowed = new java.util.LinkedHashSet<>();
            for (int index = 0; index < 305; index++) {
                allowed.add(new BlockKey(worldId, 0, 64, index));
            }
            BlockKey stored = allowed.getFirst();
            BlockKey absent = allowed.stream().skip(1).findFirst().orElseThrow();
            BlockKey unallowed = new BlockKey(worldId, 1, 64, 999);
            fixture.repository().replaceRoot(
                    fixture.snapshot(stored, "diamond", provider), 1);
            fixture.repository().replaceRoot(
                    fixture.snapshot(unallowed, "iron", provider), 1);

            Map<BlockKey, List<IndexedItem>> documents =
                    fixture.repository().loadDocuments(allowed, provider);

            assertEquals(305, documents.size());
            assertEquals("diamond",
                    documents.get(stored).getFirst().descriptor().materialKey());
            assertTrue(documents.get(absent).isEmpty());
            assertFalse(documents.containsKey(unallowed));
        }
    }

    @Test
    void loadDocumentsRejectsUnallowedStoredRoots(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("unallowed.db"))) {
            BlockKey allowed = fixture.key(0, 64, 0);
            BlockKey unallowed = fixture.key(1, 64, 0);
            fixture.insertAvailable(allowed, "diamond");
            fixture.insertAvailable(unallowed, "iron");
            Map<BlockKey, List<IndexedItem>> docs = fixture.repository().loadDocuments(
                Set.of(allowed),
                fixture.provider(
                    SparseTagEmbeddingProvider.ID,
                    SparseTagEmbeddingProvider.VERSION
                )
            );
            assertEquals(1, docs.size());
            assertEquals("diamond", docs.get(allowed).getFirst().descriptor().materialKey());
        }
    }

    @Test
    void loadDocumentsRejectsProviderMismatchInLaterBatch(
            @TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture =
                     new RepositoryTestFixture(tempDir.resolve("batch-mismatch.db"))) {
            EmbeddingProvider expected =
                    fakeProvider("builtin:expected", 2, null);
            EmbeddingProvider mismatched = new SparseTagEmbeddingProvider();
            UUID worldId = UUID.randomUUID();
            java.util.LinkedHashSet<BlockKey> allowed = new java.util.LinkedHashSet<>();
            for (int index = 0; index < 201; index++) {
                BlockKey key = new BlockKey(worldId, 0, 64, index);
                allowed.add(key);
                EmbeddingProvider stored = index < 200 ? expected : mismatched;
                fixture.repository().replaceRoot(
                        fixture.snapshot(key, "item-" + index, stored), 1);
            }

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.repository().loadDocuments(allowed, expected));

            assertTrue(failure.getMessage().contains("Provider mismatch"));
        }
    }

    @Test
    void workerPersistsQueuedSqliteOperationBeforeClosingRepository(
            @TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("worker-sqlite.db");
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(database)) {
            ChunkKey chunk = new ChunkKey(UUID.randomUUID(), 4, -2);
            IndexWorker worker = new IndexWorker(fixture.repository());

            worker.submit(() -> {
                fixture.repository().setChunkAvailable(chunk, true, 7);
                return null;
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            worker.close();

            assertTrue(fixture.connection().isClosed());
            try (Connection reopened =
                         DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 PreparedStatement statement = reopened.prepareStatement(
                         "SELECT available, revision FROM chunks " +
                                 "WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?")) {
                statement.setString(1, chunk.worldId().toString());
                statement.setInt(2, chunk.x());
                statement.setInt(3, chunk.z());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                    assertEquals(7, result.getLong(2));
                }
            }
        }
    }

    private static EmbeddingProvider countingProvider(
            String id, int version, AtomicInteger embedCalls) {
        return new EmbeddingProvider() {
            @Override
            public String id() { return id; }
            @Override
            public int version() { return version; }
            @Override
            public Embedding embed(ItemDescriptor descriptor) {
                embedCalls.incrementAndGet();
                return new FakeEmbedding(id, version, descriptor);
            }
            @Override
            public Embedding embedQuery(String query) {
                return new FakeEmbedding(id, version, null);
            }
            @Override
            public Embedding decode(byte[] payload, double norm) {
                return new FakeEmbedding(id, version, null);
            }
        };
    }

    private static EmbeddingProvider fakeProvider(String id, int version, ItemDescriptor descriptor) {
        return new EmbeddingProvider() {
            @Override
            public String id() { return id; }
            @Override
            public int version() { return version; }
            @Override
            public Embedding embed(dev.jlo.kitsune.model.ItemDescriptor d) { return new FakeEmbedding(id, version, descriptor); }
            @Override
            public Embedding embedQuery(String query) { return new FakeEmbedding(id, version, descriptor); }
            @Override
            public Embedding decode(byte[] payload, double norm) { return new FakeEmbedding(id, version, descriptor); }
        };
    }

    private static final class FakeEmbedding implements Embedding {
        private final String providerId;
        private final int providerVersion;
        private final ItemDescriptor descriptor;
        private FakeEmbedding(String providerId, int providerVersion, ItemDescriptor descriptor) {
            this.providerId = providerId;
            this.providerVersion = providerVersion;
            this.descriptor = descriptor;
        }
        @Override public String providerId() { return providerId; }
        @Override public int providerVersion() { return providerVersion; }
        @Override public double norm() { return 1.0; }
        @Override public byte[] encode() { return new byte[]{1}; }
        @Override public double cosine(Embedding other) { return 0; }
        @Override public boolean equals(Object o) { return false; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    private static void createV2Database(
            Path database,
            BlockKey root,
            ItemDescriptor descriptor,
            Embedding embedding,
            byte[] corruptDescriptor)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             java.sql.Statement statement = connection.createStatement()) {
            for (String sql : v1Statements()) {
                statement.execute(sql);
            }
            for (String sql : v2Statements()) {
                statement.execute(sql);
            }
            statement.execute("INSERT INTO schema_metadata (key, value) VALUES ('schema_version', '2')");
            statement.execute(
                    "INSERT INTO containers (world_uuid, x, y, z, chunk_x, chunk_z, block_type, fingerprint, revision) "
                            + "VALUES ('"
                            + root.worldId()
                            + "', "
                            + root.x()
                            + ", "
                            + root.y()
                            + ", "
                            + root.z()
                            + ", "
                            + root.chunkKey().x()
                            + ", "
                            + root.chunkKey().z()
                            + ", 'chest', X'010203', 1)");
            try (PreparedStatement insertItem = connection.prepareStatement(
                    "INSERT INTO items (container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ItemPath path = new ItemPath(List.of(new ItemPathStep("Storage", 0)));
                insertItem.setInt(1, 1);
                insertItem.setBytes(2, ItemPathCodec.encode(path));
                insertItem.setInt(3, descriptor.amount());
                insertItem.setBytes(4, DescriptorCodec.encode(descriptor));
                insertItem.setString(5, embedding.providerId());
                insertItem.setInt(6, embedding.providerVersion());
                insertItem.setBytes(7, embedding.encode());
                insertItem.setDouble(8, embedding.norm());
                insertItem.executeUpdate();

                if (corruptDescriptor != null) {
                    ItemPath corruptPath = new ItemPath(List.of(new ItemPathStep("Storage", 1)));
                    insertItem.setInt(1, 1);
                    insertItem.setBytes(2, ItemPathCodec.encode(corruptPath));
                    insertItem.setInt(3, 1);
                    insertItem.setBytes(4, corruptDescriptor);
                    insertItem.setString(5, embedding.providerId());
                    insertItem.setInt(6, embedding.providerVersion());
                    insertItem.setBytes(7, embedding.encode());
                    insertItem.setDouble(8, embedding.norm());
                    insertItem.executeUpdate();
                }
            }
        }
    }

    private static void createV1Database(
            Path database,
            BlockKey root,
            ItemDescriptor descriptor,
            Embedding embedding)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             java.sql.Statement statement = connection.createStatement()) {
            for (String sql : v1Statements()) {
                statement.execute(sql);
            }
            statement.execute("INSERT INTO schema_metadata (key, value) VALUES ('schema_version', '1')");
            statement.execute(
                    "INSERT INTO containers (world_uuid, x, y, z, chunk_x, chunk_z, block_type, fingerprint, revision) "
                            + "VALUES ('"
                            + root.worldId()
                            + "', "
                            + root.x()
                            + ", "
                            + root.y()
                            + ", "
                            + root.z()
                            + ", "
                            + root.chunkKey().x()
                            + ", "
                            + root.chunkKey().z()
                            + ", 'chest', X'010203', 1)");
            try (PreparedStatement insertItem = connection.prepareStatement(
                    "INSERT INTO items (container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ItemPath path = new ItemPath(List.of(new ItemPathStep("Storage", 0)));
                insertItem.setInt(1, 1);
                insertItem.setBytes(2, ItemPathCodec.encode(path));
                insertItem.setInt(3, descriptor.amount());
                insertItem.setBytes(4, DescriptorCodec.encode(descriptor));
                insertItem.setString(5, embedding.providerId());
                insertItem.setInt(6, embedding.providerVersion());
                insertItem.setBytes(7, embedding.encode());
                insertItem.setDouble(8, embedding.norm());
                insertItem.executeUpdate();
            }
        }
    }


    @Test
    void findFullTextMatchesReturnsDiamondPickaxe(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-diamond.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemPath path = new ItemPath(List.of(new ItemPathStep("Storage", 0)));
            fixture.repository().replaceRoot(
                    fixture.snapshotWithMaterial(root, "minecraft:diamond_pickaxe", path), 1);
            fixture.makeChunkAvailable(root.chunkKey(), true);

            List<IndexRepository.FullTextMatch> hits = fixture.findFullText(root, "diam", 10);
            assertEquals(1, hits.size());
            assertEquals(path, hits.getFirst().path());
            assertEquals("minecraft:diamond_pickaxe", hits.getFirst().descriptor().materialKey());
        }
    }

    @Test
    void replaceRootRemovesPreviousFullTextHit(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-replace.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.insertAvailable(root, "minecraft:diamond_pickaxe");
            assertEquals(1, fixture.findFullText(root, "diam", 10).size());

            fixture.repository().replaceRoot(fixture.snapshot(root, "minecraft:oak_planks"), 2);
            assertTrue(fixture.findFullText(root, "diam", 10).isEmpty());
            assertEquals(1, fixture.findFullText(root, "oak", 10).size());
        }
    }

    @Test
    void deleteRootClearsFullTextRows(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-delete.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.insertAvailable(root, "minecraft:diamond_pickaxe");
            int containerId = fixture.containerId(root);
            assertEquals(1, fixture.itemSearchCountForContainer(root));

            fixture.repository().deleteRoot(root);
            assertEquals(0, itemSearchCountForContainer(fixture.connection(), containerId));
            assertTrue(fixture.findFullText(root, "diam", 10).isEmpty());
        }
    }

    @Test
    void findFullTextMatchesSurvivesReopen(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("fts-reopen.db");
        BlockKey root;
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(database)) {
            root = fixture.key(0, 64, 0);
            fixture.insertAvailable(root, "minecraft:diamond_pickaxe");
            assertEquals(1, fixture.findFullText(root, "diam", 10).size());
        }
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(database)) {
            assertEquals(1, fixture.findFullText(root, "diam", 10).size());
        }
    }

    @Test
    void findFullTextMatchesRespectsWorldAndChunkBounds(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-bounds.db"))) {
            UUID worldId = UUID.randomUUID();
            BlockKey inside = new BlockKey(worldId, 0, 64, 0);
            BlockKey otherWorld = new BlockKey(UUID.randomUUID(), 0, 64, 0);
            BlockKey farChunk = new BlockKey(worldId, 256, 64, 0);

            fixture.insertAvailable(inside, "minecraft:diamond_pickaxe");
            fixture.insertAvailable(otherWorld, "minecraft:diamond_pickaxe");
            fixture.insertAvailable(farChunk, "minecraft:diamond_pickaxe");

            ChunkKey insideChunk = inside.chunkKey();
            List<IndexRepository.FullTextMatch> hits = fixture.repository().findFullTextMatches(
                    FullTextQuery.parse("diam").matchExpression(),
                    worldId,
                    insideChunk.x(),
                    insideChunk.x(),
                    insideChunk.z(),
                    insideChunk.z(),
                    10);
            assertEquals(1, hits.size());
            assertEquals(inside, hits.getFirst().root().key());
        }
    }

    @Test
    void findFullTextMatchesExcludesUnavailableChunk(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-unavailable.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshot(root, "minecraft:diamond_pickaxe"), 1);
            fixture.makeChunkAvailable(root.chunkKey(), false);

            assertTrue(fixture.findFullText(root, "diam", 10).isEmpty());
        }
    }

    @Test
    void findFullTextMatchesHonorsLimitAndBm25Order(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-limit.db"))) {
            UUID worldId = UUID.randomUUID();
            EmbeddingProvider provider = new SparseTagEmbeddingProvider();
            List<IndexedItem> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ItemDescriptor descriptor = ItemDescriptor.builder()
                        .materialKey("minecraft:oak_planks")
                        .amount(1)
                        .addDisplayText("wood plank " + i)
                        .build();
                items.add(new IndexedItem(
                        new ItemPath(List.of(new ItemPathStep("Storage", i))),
                        1,
                        descriptor,
                        provider.embed(descriptor)));
            }
            BlockKey root = new BlockKey(worldId, 0, 64, 0);
            fixture.repository().replaceRoot(fixture.snapshotWithItems(root, items), 1);
            fixture.makeChunkAvailable(root.chunkKey(), true);

            ChunkKey chunk = root.chunkKey();
            List<IndexRepository.FullTextMatch> hits = fixture.repository().findFullTextMatches(
                    FullTextQuery.parse("wood").matchExpression(),
                    worldId,
                    chunk.x(),
                    chunk.x(),
                    chunk.z(),
                    chunk.z(),
                    3);
            assertEquals(3, hits.size());
            for (int i = 1; i < hits.size(); i++) {
                assertTrue(hits.get(i - 1).bm25() <= hits.get(i).bm25());
            }
        }
    }

    @Test
    void findFullTextMatchesTreatsRawOperatorsAsLiterals(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-literal.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ItemDescriptor descriptor = ItemDescriptor.builder()
                    .materialKey("minecraft:stone")
                    .amount(1)
                    .addDisplayText("foo AND bar")
                    .build();
            ItemPath path = new ItemPath(List.of(new ItemPathStep("Storage", 0)));
            fixture.repository().replaceRoot(fixture.snapshotWithDescriptor(root, descriptor, path), 1);
            fixture.makeChunkAvailable(root.chunkKey(), true);

            String expression = FullTextQuery.parse("foo AND bar").matchExpression();
            ChunkKey chunk = root.chunkKey();
            List<IndexRepository.FullTextMatch> hits = fixture.repository().findFullTextMatches(
                    expression,
                    root.worldId(),
                    chunk.x(),
                    chunk.x(),
                    chunk.z(),
                    chunk.z(),
                    10);
            assertEquals(1, hits.size());
            assertEquals(path, hits.getFirst().path());
        }
    }

    @Test
    void findFullTextMatchesRejectsNonPositiveLimit(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-limit-invalid.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ChunkKey chunk = root.chunkKey();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.repository().findFullTextMatches(
                            FullTextQuery.parse("diam").matchExpression(),
                            root.worldId(),
                            chunk.x(),
                            chunk.x(),
                            chunk.z(),
                            chunk.z(),
                            0));
        }
    }

    @Test
    void findFullTextMatchesReturnsEmptyForBlankExpression(@TempDir Path tempDir) throws Exception {
        try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("fts-blank.db"))) {
            BlockKey root = fixture.key(0, 64, 0);
            ChunkKey chunk = root.chunkKey();
            assertTrue(fixture.repository().findFullTextMatches(
                    "",
                    root.worldId(),
                    chunk.x(),
                    chunk.x(),
                    chunk.z(),
                    chunk.z(),
                    10).isEmpty());
        }
    }


    private static List<String> v2Statements() throws Exception {
        try (var input = SqliteIndexRepository.class.getClassLoader()
                .getResourceAsStream("db/migration/V2__embeddings_cache.sql")) {
            if (input == null) {
                throw new IllegalStateException("Missing migration V2__embeddings_cache.sql");
            }
            String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
            List<String> statements = new ArrayList<>();
            for (String raw : sql.split(";")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
            }
            return statements;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int tableCount(Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }


    private static List<String> v1Statements() throws Exception {
        try (var input = SqliteIndexRepository.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__initial.sql")) {
            if (input == null) {
                throw new IllegalStateException("Missing migration V1__initial.sql");
            }
            String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
            List<String> statements = new ArrayList<>();
            for (String raw : sql.split(";")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
            }
            return statements;
        }
    }

    private static int itemSearchCountForContainer(Connection connection, int containerId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM item_search s JOIN items i ON i.id = s.item_id "
                        + "WHERE i.container_id = ?")) {
            ps.setInt(1, containerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int schemaVersion(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM schema_metadata WHERE key = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new IllegalStateException("Missing schema_version");
        }
    }

    private static int countProviderVersion(Connection connection, int version) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM items WHERE provider_version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
