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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            assertEquals(2, schemaVersion(first.connection()));
        }
        try (RepositoryTestFixture second = new RepositoryTestFixture(database)) {
            second.repository().migrate();
            assertEquals(2, schemaVersion(second.connection()));
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
            assertEquals(2, schemaVersion(connection));
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM embeddings");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
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
