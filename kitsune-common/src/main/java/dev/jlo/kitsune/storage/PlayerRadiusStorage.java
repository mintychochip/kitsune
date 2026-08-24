package dev.jlo.kitsune.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
    private final boolean ownsConnection;
    private final int defaultRadius;

    /**
     * Creates storage over an existing SQLite connection.
     *
     * @param connection open SQLite connection
     * @param defaultRadius radius to use when no override exists
     */
    public PlayerRadiusStorage(Connection connection, int defaultRadius) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.defaultRadius = validateRadius(defaultRadius);
        this.ownsConnection = false;
    }

    private PlayerRadiusStorage(Connection connection, int defaultRadius, boolean ownsConnection) {
        this.connection = connection;
        this.defaultRadius = validateRadius(defaultRadius);
        this.ownsConnection = ownsConnection;
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
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        connection.setAutoCommit(true);
        PlayerRadiusStorage storage = new PlayerRadiusStorage(connection, defaultRadius, true);
        storage.initialize();
        return storage;
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
        if (ownsConnection) {
            connection.close();
        }
    }

    private static int validateRadius(int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS);
        }
        return radius;
    }
}
