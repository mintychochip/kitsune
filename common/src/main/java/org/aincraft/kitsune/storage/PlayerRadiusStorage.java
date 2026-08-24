package org.aincraft.kitsune.storage;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Manages per-player radius limits using SQLite for persistent storage.
 * All operations are asynchronous and use a provided ExecutorService for execution.
 * Supports virtual threads via Executors.newVirtualThreadPerTaskExecutor() for efficient I/O.
 */
public final class PlayerRadiusStorage {
    private final Logger logger;
    private final DataSource dataSource;
    private final ExecutorService executor;
    private final int defaultRadius;

    public PlayerRadiusStorage(Logger logger, DataSource dataSource, ExecutorService executor, int defaultRadius) {
        this.logger = Preconditions.checkNotNull(logger, "logger cannot be null");
        this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource cannot be null");
        this.executor = Preconditions.checkNotNull(executor, "executor cannot be null");
        this.defaultRadius = defaultRadius;
    }

    /**
     * Initialize the player radius storage by creating necessary tables and indices.
     *
     * @return a CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                try (Connection conn = dataSource.getConnection()) {
                    // Create player_radius_limits table
                    conn.createStatement().execute("""
                        CREATE TABLE IF NOT EXISTS player_radius_limits (
                            player_id TEXT PRIMARY KEY,
                            player_name TEXT NOT NULL,
                            max_radius INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);

                    // Create index on player_id for efficient lookups
                    conn.createStatement().execute(
                        "CREATE INDEX IF NOT EXISTS idx_player_radius_limits_player_id ON player_radius_limits(player_id)"
                    );
                }

                logger.info("Player radius storage initialized");
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to initialize player radius storage", e);
                throw new RuntimeException("Player radius storage initialization failed", e);
            }
        }, executor);
    }

    /**
     * Get the maximum radius for a player. Returns the player's custom limit if set, otherwise returns default.
     *
     * @param playerId the player's UUID
     * @return a CompletableFuture containing the max radius for the player
     */
    public CompletableFuture<Integer> getMaxRadius(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT max_radius FROM player_radius_limits WHERE player_id = ?")) {

                stmt.setString(1, playerId.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("max_radius");
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to retrieve player radius limit", e);
            }

            // Return default radius if not found
            return defaultRadius;
        }, executor);
    }

    /**
     * Set the maximum radius for a player.
     *
     * @param playerId the player's UUID
     * @param playerName the player's name
     * @param maxRadius the maximum radius to set
     * @return a CompletableFuture that completes when the update is done
     */
    public CompletableFuture<Void> setMaxRadius(UUID playerId, String playerName, int maxRadius) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        Preconditions.checkNotNull(playerName, "playerName cannot be null");
        Preconditions.checkArgument(maxRadius > 0, "maxRadius must be positive");

        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO player_radius_limits (player_id, player_name, max_radius, updated_at) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(player_id) DO UPDATE SET " +
                     "player_name = excluded.player_name, " +
                     "max_radius = excluded.max_radius, " +
                     "updated_at = excluded.updated_at")) {

                long now = System.currentTimeMillis();
                stmt.setString(1, playerId.toString());
                stmt.setString(2, playerName);
                stmt.setInt(3, maxRadius);
                stmt.setLong(4, now);

                stmt.executeUpdate();
                logger.info("Set radius limit for player " + playerName + " (" + playerId + ") to " + maxRadius);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to set player radius limit", e);
                throw new RuntimeException("Failed to set player radius limit", e);
            }
        }, executor);
    }

    /**
     * Get all player radius limits. Used for admin viewing.
     *
     * @return a CompletableFuture containing a map of player names to their max radius values
     */
    public CompletableFuture<Map<String, Integer>> getAllLimits() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Integer> results = new HashMap<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT player_name, max_radius FROM player_radius_limits ORDER BY player_name ASC")) {

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.put(rs.getString("player_name"), rs.getInt("max_radius"));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to retrieve all player radius limits", e);
                throw new RuntimeException("Failed to retrieve player radius limits", e);
            }

            return results;
        }, executor);
    }

    /**
     * Reset a player's radius limit to default.
     *
     * @param playerId the player's UUID
     * @return a CompletableFuture that completes when the deletion is done
     */
    public CompletableFuture<Void> resetRadius(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");

        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM player_radius_limits WHERE player_id = ?")) {

                stmt.setString(1, playerId.toString());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    logger.info("Reset radius limit for player " + playerId + " to default");
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to reset player radius limit", e);
                throw new RuntimeException("Failed to reset player radius limit", e);
            }
        }, executor);
    }

    /**
     * Close the storage. Note: DataSource lifecycle is managed externally.
     */
    public void close() {
        // DataSource lifecycle is managed by KitsuneStorage/MetadataStorage
        logger.info("Player radius storage closed");
    }
}
