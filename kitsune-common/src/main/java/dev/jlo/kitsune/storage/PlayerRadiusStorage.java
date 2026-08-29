package dev.jlo.kitsune.storage;

import com.zaxxer.hikari.HikariConfig;
import org.aincraft.db.sql.SqlDatabase;
import org.jdbi.v3.core.Handle;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Synchronous SQLite storage for per-player search radius overrides.
 *
 * <p>Players without a stored override fall back to the configured default radius.
 */
public final class PlayerRadiusStorage implements AutoCloseable {
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 128;
    private final Connection connection;
    private final Handle handle;
    private final SqlDatabase database;
    private final int defaultRadius;

    /**
     * Creates storage over an existing SQLite connection.
     *
     * @param connection open SQLite connection
     * @param defaultRadius radius to use when no override exists
     */
    public PlayerRadiusStorage(Connection connection, int defaultRadius) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.handle = null;
        this.database = null;
        this.defaultRadius = validateRadius(defaultRadius);
    }

    private PlayerRadiusStorage(SqlDatabase database, int defaultRadius) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.defaultRadius = validateRadius(defaultRadius);
        this.handle = database.jdbi().open();
        this.connection = handle.getConnection();
    }

    /**
     * Opens a database file, applies schema initialization, and returns storage that owns the connection.
     *
     * @param path database file path
     * @param defaultRadius radius to use when no override exists
     * @return initialized storage
     * @throws SQLException if the database cannot be opened or initialized
     */
    public static PlayerRadiusStorage open(Path path, int defaultRadius) throws SQLException {
        Objects.requireNonNull(path, "path must not be null");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        SqlDatabase database = createDatabase(config);
        PlayerRadiusStorage storage = null;
        try {
            storage = new PlayerRadiusStorage(database, defaultRadius);
            configureConnection(storage.connection);
            if (!storage.hasTable("player_radius_limits")
                    && !storage.hasTable("search_history")) {
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
        Thread.currentThread().setContextClassLoader(PlayerRadiusStorage.class.getClassLoader());
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
        Thread.currentThread().setContextClassLoader(PlayerRadiusStorage.class.getClassLoader());
        try {
            database.migrate();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    /**
     * Applies the search-history and player-radius schema to the supplied connection.
     *
     * @param connection open SQLite connection
     * @throws SQLException if schema initialization fails
     */
    public static void initialize(Connection connection) throws SQLException {
        SearchHistoryStorage.initialize(connection);
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
     * Returns the effective maximum radius for a player.
     *
     * @param playerId player identifier
     * @return stored override or the configured default
     * @throws SQLException if the query fails
     */
    public int getMaxRadius(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT radius FROM player_radius_limits WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("radius");
                }
            }
        }
        return defaultRadius;
    }

    /**
     * Stores a per-player radius override.
     *
     * @param playerId player identifier
     * @param radius maximum radius for the player
     * @throws SQLException if the upsert fails
     */
    public void setMaxRadius(UUID playerId, int radius) throws SQLException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        int validatedRadius = validateRadius(radius);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_radius_limits (player_id, radius, updated_at) "
                        + "VALUES (?, ?, ?) "
                        + "ON CONFLICT(player_id) DO UPDATE SET "
                        + "radius = excluded.radius, "
                        + "updated_at = excluded.updated_at")) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, validatedRadius);
            statement.setLong(3, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    /**
     * Returns the configured default radius.
     *
     * @return default radius
     */
    public int defaultRadius() {
        return defaultRadius;
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

    private static int validateRadius(int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS);
        }
        return radius;
    }
}
