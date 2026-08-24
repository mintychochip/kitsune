package org.aincraft.kitsune.storage;

import com.google.common.base.Preconditions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.aincraft.kitsune.model.SearchHistoryEntry;

/**
 * Persistent search history storage using SQLite for recording player search queries and results.
 * All operations are asynchronous and use a provided ExecutorService for execution.
 * Supports virtual threads via Executors.newVirtualThreadPerTaskExecutor() for efficient I/O.
 */
public final class SearchHistoryStorage {
    private final Logger logger;
    private final DataSource dataSource;
    private final ExecutorService executor;

    public SearchHistoryStorage(Logger logger, DataSource dataSource, ExecutorService executor) {
        this.logger = Preconditions.checkNotNull(logger, "logger cannot be null");
        this.dataSource = Preconditions.checkNotNull(dataSource, "dataSource cannot be null");
        this.executor = Preconditions.checkNotNull(executor, "executor cannot be null");
    }

    /**
     * Initialize the search history storage by creating necessary tables and indices.
     *
     * @return a CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                try (Connection conn = dataSource.getConnection()) {
                    // Create search_history table
                    conn.createStatement().execute("""
                        CREATE TABLE IF NOT EXISTS search_history (
                            id TEXT PRIMARY KEY,
                            player_id TEXT NOT NULL,
                            player_name TEXT NOT NULL,
                            query TEXT NOT NULL,
                            result_count INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                        """);

                    // Create indices for efficient queries
                    conn.createStatement().execute(
                        "CREATE INDEX IF NOT EXISTS idx_search_history_player ON search_history(player_id, timestamp DESC)"
                    );
                    conn.createStatement().execute(
                        "CREATE INDEX IF NOT EXISTS idx_search_history_timestamp ON search_history(timestamp DESC)"
                    );
                }

                logger.info("Search history storage initialized");
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to initialize search history storage", e);
                throw new RuntimeException("Search history initialization failed", e);
            }
        }, executor);
    }

    /**
     * Record a search in the history.
     *
     * @param entry the search history entry to record
     * @return a CompletableFuture that completes when the record is inserted
     */
    public CompletableFuture<Void> recordSearch(SearchHistoryEntry entry) {
        Preconditions.checkNotNull(entry, "entry cannot be null");

        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO search_history (id, player_id, player_name, query, result_count, timestamp) " +
                     "VALUES (?, ?, ?, ?, ?, ?)")) {

                stmt.setString(1, entry.id().toString());
                stmt.setString(2, entry.playerId().toString());
                stmt.setString(3, entry.playerName());
                stmt.setString(4, entry.query());
                stmt.setInt(5, entry.resultCount());
                stmt.setLong(6, entry.timestamp());

                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to record search history", e);
                throw new RuntimeException("Failed to record search", e);
            }
        }, executor);
    }

    /**
     * Get recent search history for a specific player.
     *
     * @param playerId the player's UUID
     * @param limit the maximum number of records to return
     * @return a CompletableFuture containing the list of recent search entries
     */
    public CompletableFuture<List<SearchHistoryEntry>> getPlayerHistory(UUID playerId, int limit) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        Preconditions.checkArgument(limit > 0, "limit must be positive");

        return CompletableFuture.supplyAsync(() -> {
            List<SearchHistoryEntry> results = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, player_id, player_name, query, result_count, timestamp " +
                     "FROM search_history " +
                     "WHERE player_id = ? " +
                     "ORDER BY timestamp DESC " +
                     "LIMIT ?")) {

                stmt.setString(1, playerId.toString());
                stmt.setInt(2, limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchHistoryEntry(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("player_id")),
                            rs.getString("player_name"),
                            rs.getString("query"),
                            rs.getInt("result_count"),
                            rs.getLong("timestamp")
                        ));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to retrieve player search history", e);
                throw new RuntimeException("Failed to retrieve search history", e);
            }

            return results;
        }, executor);
    }

    /**
     * Get recent search history globally (for admin purposes).
     *
     * @param limit the maximum number of records to return
     * @return a CompletableFuture containing the list of recent search entries
     */
    public CompletableFuture<List<SearchHistoryEntry>> getGlobalHistory(int limit) {
        Preconditions.checkArgument(limit > 0, "limit must be positive");

        return CompletableFuture.supplyAsync(() -> {
            List<SearchHistoryEntry> results = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, player_id, player_name, query, result_count, timestamp " +
                     "FROM search_history " +
                     "ORDER BY timestamp DESC " +
                     "LIMIT ?")) {

                stmt.setInt(1, limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchHistoryEntry(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("player_id")),
                            rs.getString("player_name"),
                            rs.getString("query"),
                            rs.getInt("result_count"),
                            rs.getLong("timestamp")
                        ));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to retrieve global search history", e);
                throw new RuntimeException("Failed to retrieve search history", e);
            }

            return results;
        }, executor);
    }

    /**
     * Clear all search history for a specific player.
     *
     * @param playerId the player's UUID
     * @return a CompletableFuture that completes when the deletion is done
     */
    public CompletableFuture<Void> clearPlayerHistory(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");

        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM search_history WHERE player_id = ?")) {

                stmt.setString(1, playerId.toString());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    logger.info("Cleared " + deleted + " search history entries for player " + playerId);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to clear player search history", e);
                throw new RuntimeException("Failed to clear player history", e);
            }
        }, executor);
    }

    /**
     * Clear all search history globally (admin operation).
     *
     * @return a CompletableFuture that completes when the deletion is done
     */
    public CompletableFuture<Void> clearAllHistory() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM search_history")) {

                int deleted = stmt.executeUpdate();
                logger.info("Cleared all " + deleted + " search history entries");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to clear all search history", e);
                throw new RuntimeException("Failed to clear all history", e);
            }
        }, executor);
    }

    /**
     * Remove search history entries older than the specified number of days.
     *
     * @param maxAgeDays the maximum age in days to retain
     * @return a CompletableFuture that completes when the deletion is done
     */
    public CompletableFuture<Void> pruneOldEntries(int maxAgeDays) {
        Preconditions.checkArgument(maxAgeDays > 0, "maxAgeDays must be positive");

        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM search_history WHERE timestamp < ?")) {

                long cutoffTime = System.currentTimeMillis() - (long) maxAgeDays * 24 * 60 * 60 * 1000;
                stmt.setLong(1, cutoffTime);

                int deleted = stmt.executeUpdate();
                logger.info("Pruned " + deleted + " search history entries older than " + maxAgeDays + " days");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to prune search history", e);
                throw new RuntimeException("Failed to prune history", e);
            }
        }, executor);
    }

    /**
     * Close the storage. Note: DataSource lifecycle is managed externally.
     */
    public void close() {
        // DataSource lifecycle is managed by KitsuneStorage/MetadataStorage
        logger.info("Search history storage closed");
    }
}
