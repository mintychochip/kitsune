package dev.jlo.kitsune.storage;

import dev.jlo.kitsune.model.SearchHistoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises search history persistence, retrieval, pruning, and clearing. */
class SearchHistoryStorageTest {

    @Test
    void recordsAndRetrievesPlayerHistory(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            UUID playerId = UUID.randomUUID();
            Instant older = Instant.parse("2024-01-01T00:00:00Z");
            Instant newer = Instant.parse("2024-06-01T00:00:00Z");

            storage.recordSearch(new SearchHistoryEntry(playerId, "diamond", older, 3));
            storage.recordSearch(new SearchHistoryEntry(playerId, "emerald", newer, 5));

            List<SearchHistoryEntry> history = storage.getPlayerHistory(playerId, 10);
            assertEquals(2, history.size());
            assertEquals("emerald", history.get(0).query());
            assertEquals(5, history.get(0).resultCount());
            assertEquals("diamond", history.get(1).query());
        }
    }

    @Test
    void returnsGlobalHistoryAcrossPlayers(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            UUID firstPlayer = UUID.randomUUID();
            UUID secondPlayer = UUID.randomUUID();

            storage.recordSearch(new SearchHistoryEntry(firstPlayer, "old query", Instant.parse("2024-01-01T00:00:00Z"), 1));
            storage.recordSearch(new SearchHistoryEntry(secondPlayer, "new query", Instant.parse("2024-06-01T00:00:00Z"), 2));

            List<SearchHistoryEntry> history = storage.getGlobalHistory(10);
            assertEquals(2, history.size());
            assertEquals("new query", history.get(0).query());
            assertEquals(secondPlayer, history.get(0).playerId());
        }
    }

    @Test
    void clearsPlayerAndAllHistory(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            UUID keepPlayer = UUID.randomUUID();
            UUID clearPlayer = UUID.randomUUID();

            storage.recordSearch(new SearchHistoryEntry(keepPlayer, "keep", Instant.now(), 1));
            storage.recordSearch(new SearchHistoryEntry(clearPlayer, "remove", Instant.now(), 1));

            storage.clearPlayerHistory(clearPlayer);
            assertTrue(storage.getPlayerHistory(clearPlayer, 10).isEmpty());
            assertEquals(1, storage.getGlobalHistory(10).size());

            storage.clearAllHistory();
            assertTrue(storage.getGlobalHistory(10).isEmpty());
        }
    }

    @Test
    void prunesEntriesOlderThanRetentionWindow(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            UUID playerId = UUID.randomUUID();
            Instant stale = Instant.now().minusSeconds(40L * 24L * 60L * 60L);
            Instant fresh = Instant.now();

            storage.recordSearch(new SearchHistoryEntry(playerId, "stale", stale, 1));
            storage.recordSearch(new SearchHistoryEntry(playerId, "fresh", fresh, 2));

            storage.pruneOldEntries(30);

            List<SearchHistoryEntry> history = storage.getPlayerHistory(playerId, 10);
            assertEquals(1, history.size());
            assertEquals("fresh", history.get(0).query());
        }
    }

    @Test
    void initializeOnSharedConnectionCreatesSchema(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("shared.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(true);
            SearchHistoryStorage.initialize(connection);

            try (SearchHistoryStorage storage = new SearchHistoryStorage(connection)) {
                UUID playerId = UUID.randomUUID();
                storage.recordSearch(new SearchHistoryEntry(playerId, "shared", Instant.now(), 4));
                assertEquals(1, storage.getPlayerHistory(playerId, 5).size());
            }
        }
    }

    @Test
    void rejectsInvalidLimits(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            assertThrows(IllegalArgumentException.class, () -> storage.getPlayerHistory(UUID.randomUUID(), 0));
            assertThrows(IllegalArgumentException.class, () -> storage.getGlobalHistory(0));
            assertThrows(IllegalArgumentException.class, () -> storage.pruneOldEntries(0));
        }
    }

    @Test
    void canInsertDirectlyWithEpochMillis(@TempDir Path tempDir) throws Exception {
        try (SearchHistoryStorage storage = SearchHistoryStorage.open(tempDir.resolve("history.sqlite"))) {
            UUID playerId = UUID.randomUUID();
            long timestamp = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli();

            try (PreparedStatement statement = storage.connection().prepareStatement(
                    "INSERT INTO search_history (id, player_id, query, timestamp, result_count) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, "manual");
                statement.setLong(4, timestamp);
                statement.setInt(5, 7);
                statement.executeUpdate();
            }

            SearchHistoryEntry entry = storage.getPlayerHistory(playerId, 1).get(0);
            assertEquals("manual", entry.query());
            assertEquals(7, entry.resultCount());
            assertEquals(timestamp, entry.timestamp().toEpochMilli());
        }
    }
}
