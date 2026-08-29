package dev.jlo.kitsune.storage;

import dev.jlo.kitsune.model.SearchHistoryEntry;

import com.zaxxer.hikari.HikariConfig;
import org.aincraft.db.sql.SqlDatabase;
import org.jdbi.v3.core.Handle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Synchronous SQLite storage for player and global search history.
 *
 * <p>Schema is created by {@link #initialize(Connection)} using the V3 migration.
 * Instances may share a connection with other storage types or own one opened via
 * {@link #open(Path)}.
 */
public final class SearchHistoryStorage implements AutoCloseable {
    private static final String MIGRATION = "V3__search_history_and_radius.sql";

    private final Connection connection;
    private final Handle handle;
    private final SqlDatabase database;

    /**
     * Creates storage over an existing SQLite connection.
     *
     * @param connection open SQLite connection
     */
    public SearchHistoryStorage(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.handle = null;
        this.database = null;
    }

    private SearchHistoryStorage(SqlDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.handle = database.jdbi().open();
        this.connection = handle.getConnection();
    }

    /**
     * Opens a database file, applies schema initialization, and returns storage that owns the connection.
     *
     * @param path database file path
     * @return initialized storage
     * @throws SQLException if the database cannot be opened or initialized
     */
    public static SearchHistoryStorage open(Path path) throws SQLException {
        Objects.requireNonNull(path, "path must not be null");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        SqlDatabase database = createDatabase(config);
        SearchHistoryStorage storage = null;
        try {
            storage = new SearchHistoryStorage(database);
            configureConnection(storage.connection);
            if (!storage.hasTable("search_history")
                    && !storage.hasTable("player_radius_limits")) {
                migrateWithUtilities(database);
            } else {
                storage.initialize();
            }
            return storage;
        } catch (SQLException | RuntimeException failure) {
            if (storage != null) {
                try {
                    storage.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            } else {
                database.close();
            }
            throw failure;
        }
    }
    private static SqlDatabase createDatabase(HikariConfig config) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SearchHistoryStorage.class.getClassLoader());
        try {
            return SqlDatabase.create(config, "classpath:db/migration/history");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private boolean hasTable(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void migrateWithUtilities(SqlDatabase database) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SearchHistoryStorage.class.getClassLoader());
        try {
            database.migrate();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
    /**
     * Applies the search-history and player-radius schema to the supplied connection.
     *
     * @param connection open SQLite connection
     * @throws SQLException if schema initialization fails
     */
    public static void initialize(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        configureConnection(connection);
        try (Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(readMigration(MIGRATION))) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Initializes schema on this storage's connection.
     *
     * @throws SQLException if schema initialization fails
     */
    public void initialize() throws SQLException {
        initialize(connection);
    }

    /**
     * Records a search history entry.
     *
     * @param entry validated search history entry
     * @throws SQLException if the insert fails
     */
    public void recordSearch(SearchHistoryEntry entry) throws SQLException {
        Objects.requireNonNull(entry, "entry must not be null");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO search_history (id, player_id, query, timestamp, result_count) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, entry.playerId().toString());
            statement.setString(3, entry.query());
            statement.setLong(4, entry.timestamp().toEpochMilli());
            statement.setInt(5, entry.resultCount());
            statement.executeUpdate();
        }
    }

    /**
     * Returns recent search history for a player, newest first.
     *
     * @param playerId player identifier
     * @param limit maximum number of entries to return
     * @return recent entries for the player
     * @throws SQLException if the query fails
     */
    public List<SearchHistoryEntry> getPlayerHistory(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<SearchHistoryEntry> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id, query, timestamp, result_count "
                        + "FROM search_history "
                        + "WHERE player_id = ? "
                        + "ORDER BY timestamp DESC "
                        + "LIMIT ?")) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(readEntry(resultSet));
                }
            }
        }
        return List.copyOf(results);
    }

    /**
     * Returns recent search history across all players, newest first.
     *
     * @param limit maximum number of entries to return
     * @return recent global entries
     * @throws SQLException if the query fails
     */
    public List<SearchHistoryEntry> getGlobalHistory(int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<SearchHistoryEntry> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id, query, timestamp, result_count "
                        + "FROM search_history "
                        + "ORDER BY timestamp DESC "
                        + "LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(readEntry(resultSet));
                }
            }
        }
        return List.copyOf(results);
    }

    /**
     * Deletes all search history for a player.
     *
     * @param playerId player identifier
     * @throws SQLException if the delete fails
     */
    public void clearPlayerHistory(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM search_history WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Deletes all search history entries.
     *
     * @throws SQLException if the delete fails
     */
    public void clearAllHistory() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM search_history")) {
            statement.executeUpdate();
        }
    }

    /**
     * Deletes search history entries older than the supplied retention window.
     *
     * @param maxAgeDays maximum age in days to retain
     * @throws SQLException if the delete fails
     */
    public void pruneOldEntries(int maxAgeDays) throws SQLException {
        if (maxAgeDays <= 0) {
            throw new IllegalArgumentException("maxAgeDays must be positive");
        }
        long cutoff = Instant.now().toEpochMilli() - (long) maxAgeDays * 24L * 60L * 60L * 1000L;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM search_history WHERE timestamp < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        }
    }

    /**
     * Returns the underlying connection used by this storage instance.
     *
     * @return SQLite connection
     */
    Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (handle == null) {
            return;
        }
        Throwable failure = null;
        try {
            handle.close();
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        }
        try {
            database.close();
        } catch (Throwable closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure instanceof SQLException sqlFailure) {
            throw sqlFailure;
        }
        if (failure != null) {
            throw new SQLException("Failed to close SQL storage", failure);
        }
    }

    private static SearchHistoryEntry readEntry(ResultSet resultSet) throws SQLException {
        return new SearchHistoryEntry(
                UUID.fromString(resultSet.getString("player_id")),
                resultSet.getString("query"),
                Instant.ofEpochMilli(resultSet.getLong("timestamp")),
                resultSet.getInt("result_count"));
    }

    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static String readMigration(String name) {
        try (InputStream input = SearchHistoryStorage.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> splitStatements(String sql) {
        String normalized = sql.replace("\r\n", "\n").replace("\r", "\n");
        String[] raw = normalized.split(";");
        List<String> statements = new ArrayList<>();
        for (String statement : raw) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }
}
