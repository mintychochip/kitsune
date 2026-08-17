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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IndexRepository} backed by a SQLite database.
 *
 * <p>The repository stores container roots, per-chunk availability, and
 * embedding-backed searchable documents. It applies schema migrations on
 * open, marks all persisted chunks unavailable, and optionally re-embeds
 * documents when the configured provider identity differs. All mutations
 * are committed transactionally where the operation requires it.
 */
public final class SqliteIndexRepository implements IndexRepository {
    private static final int LOAD_DOCUMENTS_BATCH = 200;
    private static final int REEMBED_BATCH = 256;

    private final Connection connection;

    /**
     * Creates a repository over an existing SQLite connection and configures
     * WAL journaling and foreign-key enforcement.
     *
     * @param connection open SQLite connection
     * @throws SQLException if connection configuration fails
     */
    SqliteIndexRepository(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    /**
     * Opens (creating if necessary) the repository at the given path without
     * re-embedding.
     *
     * @param path database file path
     * @return an open, migrated repository
     * @throws SQLException if the database cannot be opened or migrated
     */
    public static IndexRepository open(Path path) throws SQLException {
        return open(path, null);
    }

    /**
     * Opens (creating if necessary) the repository at the given path, running
     * migrations and optionally re-embedding documents whose provider identity
     * differs from the supplied provider.
     *
     * @param path database file path
     * @param provider embedding provider used to re-embed on identity mismatch,
     *                  or {@code null} to skip re-embedding
     * @return an open, migrated repository
     * @throws SQLException if the database cannot be opened or migrated
     */
    public static IndexRepository open(Path path, EmbeddingProvider provider) throws SQLException {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            connection.setAutoCommit(true);
            SqliteIndexRepository repository = new SqliteIndexRepository(connection);
            repository.migrate();
            repository.markAllChunksUnavailable();
            if (provider != null) {
                repository.reembedIfMismatched(provider);
            }
            return repository;
        } catch (SQLException ex) {
            closeWithSuppressed(connection, ex);
            throw ex;
        } catch (RuntimeException ex) {
            closeWithSuppressed(connection, ex);
            throw ex;
        } catch (Exception ex) {
            SQLException wrapped = new SQLException("Failed to open repository", ex);
            closeWithSuppressed(connection, wrapped);
            throw wrapped;
        }
    }

    private static void closeWithSuppressed(Connection connection, Throwable primary) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    @Override
    public void migrate() throws SQLException {
        connection.setAutoCommit(true);
        Throwable primary = null;
        try {
            Integer current = currentSchemaVersion();
            if (current != null && current == 2) {
                return;
            }
            if (current != null && current != 1) {
                throw new SQLException("Unsupported schema version: " + current);
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (current == null) {
                    for (String sql : splitStatements(readMigration("V1__initial.sql"))) {
                        statement.execute(sql);
                    }
                }
                for (String sql : splitStatements(readMigration("V2__embeddings_cache.sql"))) {
                    statement.execute(sql);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO schema_metadata (key, value) VALUES (?, ?) "
                                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                    ps.setString(1, "schema_version");
                    ps.setString(2, "2");
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException ex) {
                primary = ex;
                try { connection.rollback(); } catch (SQLException r) { ex.addSuppressed(r); }
                throw ex;
            } catch (SQLException ex) {
                primary = ex;
                try { connection.rollback(); } catch (SQLException r) { ex.addSuppressed(r); }
                throw ex;
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreEx) {
                if (primary instanceof SQLException sqlEx) {
                    sqlEx.addSuppressed(restoreEx);
                } else if (primary instanceof RuntimeException runtimeEx) {
                    runtimeEx.addSuppressed(restoreEx);
                } else if (primary == null) {
                    throw restoreEx;
                }
            }
        }
    }

    private Integer currentSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT value FROM schema_metadata WHERE key = 'schema_version'")) {
            if (!rs.next()) {
                return null;
            }
            return rs.getInt(1);
        } catch (SQLException ex) {
            if (!noSuchTable(ex)) throw ex;
            return null;
        }
    }

    @Override
    public void markAllChunksUnavailable() throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE chunks SET available = 0");
        }
    }

    @Override
    public void setChunkAvailable(ChunkKey chunk, boolean available, long revision) throws SQLException {
        connection.setAutoCommit(true);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO chunks (world_uuid, chunk_x, chunk_z, available, revision) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, chunk.worldId().toString());
            ps.setInt(2, chunk.x());
            ps.setInt(3, chunk.z());
            ps.setInt(4, available ? 1 : 0);
            ps.setLong(5, revision);
            ps.executeUpdate();
        }
    }

    @Override
    public void replaceRoot(ContainerSnapshot snapshot, long revision) throws SQLException {
        BlockKey key = snapshot.key();
        connection.setAutoCommit(false);
        Throwable primary = null;
        try {
            int containerId;
            try (PreparedStatement find = connection.prepareStatement(
                    "SELECT id FROM containers WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                find.setString(1, key.worldId().toString());
                find.setInt(2, key.x());
                find.setInt(3, key.y());
                find.setInt(4, key.z());
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        containerId = rs.getInt(1);
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE containers SET block_type = ?, fingerprint = ?, revision = ? WHERE id = ?")) {
                            update.setString(1, snapshot.blockType());
                            update.setBytes(2, snapshot.fingerprint());
                            update.setLong(3, revision);
                            update.setInt(4, containerId);
                            update.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO containers (world_uuid, x, y, z, chunk_x, chunk_z, block_type, fingerprint, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                            insert.setString(1, key.worldId().toString());
                            insert.setInt(2, key.x());
                            insert.setInt(3, key.y());
                            insert.setInt(4, key.z());
                            insert.setInt(5, key.chunkKey().x());
                            insert.setInt(6, key.chunkKey().z());
                            insert.setString(7, snapshot.blockType());
                            insert.setBytes(8, snapshot.fingerprint());
                            insert.setLong(9, revision);
                            insert.executeUpdate();
                            try (ResultSet keys = insert.getGeneratedKeys()) {
                                if (keys.next()) {
                                    containerId = keys.getInt(1);
                                } else {
                                    throw new SQLException("Failed to retrieve generated container id");
                                }
                            }
                        }
                    }
                }
            }
            try (PreparedStatement deleteItems = connection.prepareStatement(
                    "DELETE FROM items WHERE container_id = ?")) {
                deleteItems.setInt(1, containerId);
                deleteItems.executeUpdate();
            }
            try (PreparedStatement insertItem = connection.prepareStatement(
                    "INSERT INTO items (container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (IndexedItem item : snapshot.items()) {
                    Embedding embedding = item.embedding();
                    insertItem.setInt(1, containerId);
                    insertItem.setBytes(2, ItemPathCodec.encode(item.path()));
                    insertItem.setInt(3, item.amount());
                    insertItem.setBytes(4, DescriptorCodec.encode(item.descriptor()));
                    insertItem.setString(5, embedding.providerId());
                    insertItem.setInt(6, embedding.providerVersion());
                    insertItem.setBytes(7, embedding.encode());
                    insertItem.setDouble(8, embedding.norm());
                    insertItem.addBatch();
                }
                insertItem.executeBatch();
            }
            connection.commit();
        } catch (RuntimeException | SQLException ex) {
            primary = ex;
            try { connection.rollback(); } catch (SQLException r) { ex.addSuppressed(r); }
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreEx) {
                if (primary instanceof SQLException sqlEx) {
                    sqlEx.addSuppressed(restoreEx);
                } else if (primary instanceof RuntimeException runtimeEx) {
                    runtimeEx.addSuppressed(restoreEx);
                } else if (primary == null) {
                    throw restoreEx;
                }
            }
        }
    }

    @Override
    public void deleteRoot(BlockKey key) throws SQLException {
        connection.setAutoCommit(true);
        try (PreparedStatement deleteContainer = connection.prepareStatement(
                "DELETE FROM containers WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
            deleteContainer.setString(1, key.worldId().toString());
            deleteContainer.setInt(2, key.x());
            deleteContainer.setInt(3, key.y());
            deleteContainer.setInt(4, key.z());
            deleteContainer.executeUpdate();
        }
    }

    /**
     * Builds the candidate-containers query for available chunks, optionally
     * filtering by a continuation cursor.
     *
     * @param withCursor whether to include the cursor filter
     * @return the candidate page SQL statement
     */
    static String candidatePageSql(boolean withCursor) {
        return "SELECT c.world_uuid, c.x, c.y, c.z, c.block_type, c.fingerprint, c.revision " +
                "FROM chunks ch JOIN containers c INDEXED BY containers_by_chunk " +
                "ON ch.world_uuid = c.world_uuid AND ch.chunk_x = c.chunk_x AND ch.chunk_z = c.chunk_z " +
                "WHERE ch.available = 1 AND c.world_uuid = ? AND c.chunk_x BETWEEN ? AND ? AND c.chunk_z BETWEEN ? AND ? " +
                (withCursor
                        ? "AND (c.x > ? OR (c.x = ? AND c.y > ?) OR (c.x = ? AND c.y = ? AND c.z > ?)) "
                        : "") +
                "ORDER BY c.world_uuid, c.x, c.y, c.z LIMIT ?";
    }

    @Override
    public CandidatePage findCandidates(
            UUID worldId,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            CandidateCursor after,
            int limit
    ) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("Candidate page limit must be positive");
        }
        String sql = candidatePageSql(after != null);
        int fetchLimit = limit == Integer.MAX_VALUE ? limit : limit + 1;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int parameter = 1;
            ps.setString(parameter++, worldId.toString());
            ps.setInt(parameter++, minChunkX);
            ps.setInt(parameter++, maxChunkX);
            ps.setInt(parameter++, minChunkZ);
            ps.setInt(parameter++, maxChunkZ);
            if (after != null) {
                ps.setInt(parameter++, after.x());
                ps.setInt(parameter++, after.x());
                ps.setInt(parameter++, after.y());
                ps.setInt(parameter++, after.x());
                ps.setInt(parameter++, after.y());
                ps.setInt(parameter++, after.z());
            }
            ps.setInt(parameter, fetchLimit);

            List<RootIdentity> candidates = new ArrayList<>(Math.min(limit, 256));
            boolean hasMore = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (candidates.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    BlockKey blockKey = new BlockKey(
                            UUID.fromString(rs.getString(1)),
                            rs.getInt(2),
                            rs.getInt(3),
                            rs.getInt(4)
                    );
                    candidates.add(new RootIdentity(
                            blockKey,
                            rs.getString(5),
                            rs.getBytes(6),
                            rs.getLong(7)
                    ));
                }
            }
            CandidateCursor next = hasMore && !candidates.isEmpty()
                    ? cursor(candidates.getLast())
                    : null;
            return new CandidatePage(candidates, next);
        }
    }

    private static CandidateCursor cursor(RootIdentity root) {
        return new CandidateCursor(root.key().x(), root.key().y(), root.key().z());
    }

    @Override
    public Optional<RootIdentity> findRoot(BlockKey key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world_uuid, x, y, z, block_type, fingerprint, revision " +
                        "FROM containers WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, key.worldId().toString());
            ps.setInt(2, key.x());
            ps.setInt(3, key.y());
            ps.setInt(4, key.z());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                BlockKey storedKey = new BlockKey(
                        UUID.fromString(rs.getString(1)),
                        rs.getInt(2),
                        rs.getInt(3),
                        rs.getInt(4)
                );
                return Optional.of(new RootIdentity(
                        storedKey,
                        rs.getString(5),
                        rs.getBytes(6),
                        rs.getLong(7)
                ));
            }
        }
    }

    @Override
    public Map<SemanticDescriptorHash, Embedding> findEmbeddings(
            EmbeddingProvider provider,
            Set<SemanticDescriptorHash> hashes
    ) throws SQLException {
        Objects.requireNonNull(provider, "Embedding provider");
        if (hashes == null || hashes.isEmpty()) {
            return Map.of();
        }
        List<SemanticDescriptorHash> sorted = new ArrayList<>(hashes);
        Map<SemanticDescriptorHash, Embedding> found = new LinkedHashMap<>();
        for (int start = 0; start < sorted.size(); start += LOAD_DOCUMENTS_BATCH) {
            int end = Math.min(start + LOAD_DOCUMENTS_BATCH, sorted.size());
            StringBuilder sql = new StringBuilder(
                    "SELECT descriptor_hash, vector, vector_norm FROM embeddings "
                            + "WHERE provider_id = ? AND provider_version = ? AND descriptor_hash IN (");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                ps.setString(1, provider.id());
                ps.setInt(2, provider.version());
                int parameter = 3;
                for (int i = start; i < end; i++) {
                    ps.setBytes(parameter++, sorted.get(i).bytes());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SemanticDescriptorHash hash = SemanticDescriptorHash.ofBytes(rs.getBytes(1));
                        found.put(hash, requireProviderIdentity(
                                provider, provider.decode(rs.getBytes(2), rs.getDouble(3))));
                    }
                }
            }
        }
        return Map.copyOf(found);
    }

    @Override
    public void putEmbeddings(
            EmbeddingProvider provider,
            Map<SemanticDescriptorHash, Embedding> embeddings
    ) throws SQLException {
        Objects.requireNonNull(provider, "Embedding provider");
        if (embeddings == null || embeddings.isEmpty()) {
            return;
        }
        connection.setAutoCommit(false);
        Throwable primary = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO embeddings "
                        + "(provider_id, provider_version, descriptor_hash, vector, vector_norm) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (Map.Entry<SemanticDescriptorHash, Embedding> entry : embeddings.entrySet()) {
                Embedding embedding = requireProviderIdentity(provider, entry.getValue());
                ps.setString(1, provider.id());
                ps.setInt(2, provider.version());
                ps.setBytes(3, entry.getKey().bytes());
                ps.setBytes(4, embedding.encode());
                ps.setDouble(5, embedding.norm());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (RuntimeException | SQLException ex) {
            primary = ex;
            try { connection.rollback(); } catch (SQLException r) { ex.addSuppressed(r); }
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreEx) {
                if (primary instanceof SQLException sqlEx) {
                    sqlEx.addSuppressed(restoreEx);
                } else if (primary instanceof RuntimeException runtimeEx) {
                    runtimeEx.addSuppressed(restoreEx);
                } else if (primary == null) {
                    throw restoreEx;
                }
            }
        }
    }

    @Override
    public Map<BlockKey, List<IndexedItem>> loadDocuments(Set<BlockKey> allowed, EmbeddingProvider provider) throws SQLException {
        if (allowed == null || allowed.isEmpty()) {
            return Collections.emptyMap();
        }
        List<BlockKey> sorted = new ArrayList<>(allowed);
        sorted.sort(Comparator.comparing(BlockKey::worldId).thenComparing(BlockKey::x).thenComparing(BlockKey::y).thenComparing(BlockKey::z));
        Map<BlockKey, List<IndexedItem>> result = new LinkedHashMap<>();
        for (BlockKey key : sorted) {
            result.put(key, new ArrayList<>());
        }
        for (int start = 0; start < sorted.size(); start += LOAD_DOCUMENTS_BATCH) {
            int end = Math.min(start + LOAD_DOCUMENTS_BATCH, sorted.size());
            loadDocumentsBatch(sorted, start, end, provider, result);
        }
        // Replace mutable lists with immutable ones, preserving keys not present (remain empty).
        Map<BlockKey, List<IndexedItem>> immutable = new LinkedHashMap<>();
        for (Map.Entry<BlockKey, List<IndexedItem>> entry : result.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private void loadDocumentsBatch(List<BlockKey> sorted, int start, int end, EmbeddingProvider provider,
                                   Map<BlockKey, List<IndexedItem>> result) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT c.world_uuid, c.x, c.y, c.z, i.path, i.amount, i.descriptor, i.provider_id, i.provider_version, i.vector, i.vector_norm " +
                "FROM items i JOIN containers c ON c.id = i.container_id " +
                "WHERE (c.world_uuid, c.x, c.y, c.z) IN (");
        for (int i = 0; i < end - start; i++) {
            if (i > 0) sql.append(",");
            sql.append("(?, ?, ?, ?)");
        }
        sql.append(") ORDER BY c.world_uuid, c.x, c.y, c.z, i.rowid");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int param = 1;
            for (int i = start; i < end; i++) {
                BlockKey key = sorted.get(i);
                ps.setString(param++, key.worldId().toString());
                ps.setInt(param++, key.x());
                ps.setInt(param++, key.y());
                ps.setInt(param++, key.z());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String storedProviderId = rs.getString(8);
                    int storedProviderVersion = rs.getInt(9);
                    if (!provider.id().equals(storedProviderId) || provider.version() != storedProviderVersion) {
                        throw new IllegalStateException("Provider mismatch for root=" + rs.getString(1) + " " + rs.getInt(2) + "," + rs.getInt(3) + "," + rs.getInt(4) + ": stored=" + storedProviderId + ":" + storedProviderVersion + " requested=" + provider.id() + ":" + provider.version());
                    }
                    BlockKey blockKey = new BlockKey(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    ItemPath path = ItemPathCodec.decode(rs.getBytes(5));
                    ItemDescriptor descriptor = DescriptorCodec.decode(rs.getBytes(7));
                    Embedding embedding = requireProviderIdentity(
                            provider, provider.decode(rs.getBytes(10), rs.getDouble(11)));
                    List<IndexedItem> items = result.computeIfAbsent(blockKey, k -> new ArrayList<>());
                    items.add(new IndexedItem(path, rs.getInt(6), descriptor, embedding));
                }
            }
        }
    }

    @Override
    public void reembedAll(EmbeddingProvider provider) throws SQLException {
        connection.setAutoCommit(false);
        Throwable primary = null;
        try {
            long lastRowid = 0;
            while (true) {
                List<long[]> rowids = new ArrayList<>();
                List<byte[]> descriptors = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT rowid, descriptor FROM items WHERE rowid > ? ORDER BY rowid LIMIT ?")) {
                    select.setLong(1, lastRowid);
                    select.setInt(2, REEMBED_BATCH);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) {
                            rowids.add(new long[]{rs.getLong(1)});
                            descriptors.add(rs.getBytes(2));
                        }
                    }
                }
                if (rowids.isEmpty()) {
                    break;
                }
                lastRowid = rowids.get(rowids.size() - 1)[0];
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE items SET provider_id = ?, provider_version = ?, vector = ?, vector_norm = ? WHERE rowid = ?")) {
                    for (int i = 0; i < rowids.size(); i++) {
                        ItemDescriptor descriptor = DescriptorCodec.decode(descriptors.get(i));
                        Embedding embedding = requireProviderIdentity(
                                provider, provider.embed(descriptor));
                        update.setString(1, embedding.providerId());
                        update.setInt(2, embedding.providerVersion());
                        update.setBytes(3, embedding.encode());
                        update.setDouble(4, embedding.norm());
                        update.setLong(5, rowids.get(i)[0]);
                        update.addBatch();
                    }
                    update.executeBatch();
                    connection.commit();
                }
            }
        } catch (RuntimeException | SQLException ex) {
            primary = ex;
            try { connection.rollback(); } catch (SQLException r) { ex.addSuppressed(r); }
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreEx) {
                if (primary instanceof SQLException sqlEx) {
                    sqlEx.addSuppressed(restoreEx);
                } else if (primary instanceof RuntimeException runtimeEx) {
                    runtimeEx.addSuppressed(restoreEx);
                } else if (primary == null) {
                    throw restoreEx;
                }
            }
        }
    }

    private static Embedding requireProviderIdentity(
            EmbeddingProvider provider, Embedding embedding) {
        if (embedding == null) {
            throw new IllegalStateException("Embedding provider returned null");
        }
        if (!provider.id().equals(embedding.providerId())
                || provider.version() != embedding.providerVersion()) {
            throw new IllegalStateException(
                    "Embedding identity does not match provider: expected="
                            + provider.id() + ":" + provider.version()
                            + " actual=" + embedding.providerId() + ":"
                            + embedding.providerVersion());
        }
        return embedding;
    }

    /**
     * Re-embeds all persisted documents if any row's provider identity differs
     * from the supplied provider.
     *
     * @param provider embedding provider whose identity is compared
     * @throws SQLException if the check or re-embedding fails
     */
    void reembedIfMismatched(EmbeddingProvider provider) throws SQLException {
        boolean mismatch = false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT rowid FROM items WHERE provider_id <> ? OR provider_version <> ? LIMIT 1")) {
            ps.setString(1, provider.id());
            ps.setInt(2, provider.version());
            try (ResultSet rs = ps.executeQuery()) {
                mismatch = rs.next();
            }
        }
        if (mismatch) {
            reembedAll(provider);
        }
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }

    private static String readMigration(String name) {
        try (InputStream input = SqliteIndexRepository.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean noSuchTable(SQLException ex) {
        return ex.getErrorCode() == 1 && ex.getMessage() != null && ex.getMessage().contains("no such table");
    }

    private static List<String> splitStatements(String sql) {
        String normalized = sql.replace("\r\n", "\n").replace("\r", "\n");
        String[] raw = normalized.split(";");
        List<String> statements = new ArrayList<>();
        for (String s : raw) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }
}
