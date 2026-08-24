package dev.jlo.kitsune.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises per-player radius overrides and validation. */
class PlayerRadiusStorageTest {

    private static final int DEFAULT_RADIUS = 16;

    @Test
    void returnsDefaultWhenNoOverrideExists(@TempDir Path tempDir) throws Exception {
        try (PlayerRadiusStorage storage = PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), DEFAULT_RADIUS)) {
            assertEquals(DEFAULT_RADIUS, storage.getMaxRadius(UUID.randomUUID()));
        }
    }

    @Test
    void storesAndRetrievesPlayerOverride(@TempDir Path tempDir) throws Exception {
        try (PlayerRadiusStorage storage = PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), DEFAULT_RADIUS)) {
            UUID playerId = UUID.randomUUID();

            storage.setMaxRadius(playerId, 64);
            assertEquals(64, storage.getMaxRadius(playerId));
        }
    }

    @Test
    void updatesExistingOverride(@TempDir Path tempDir) throws Exception {
        try (PlayerRadiusStorage storage = PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), DEFAULT_RADIUS)) {
            UUID playerId = UUID.randomUUID();

            storage.setMaxRadius(playerId, 32);
            storage.setMaxRadius(playerId, 48);

            assertEquals(48, storage.getMaxRadius(playerId));
        }
    }

    @Test
    void sharesSchemaInitializationWithHistoryStorage(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("shared.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(true);
            PlayerRadiusStorage.initialize(connection);

            try (PlayerRadiusStorage storage = new PlayerRadiusStorage(connection, DEFAULT_RADIUS)) {
                UUID playerId = UUID.randomUUID();
                storage.setMaxRadius(playerId, 24);
                assertEquals(24, storage.getMaxRadius(playerId));
            }
        }
    }

    @Test
    void validatesRadiusBoundsOnSet(@TempDir Path tempDir) throws Exception {
        try (PlayerRadiusStorage storage = PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), DEFAULT_RADIUS)) {
            UUID playerId = UUID.randomUUID();

            assertThrows(IllegalArgumentException.class, () -> storage.setMaxRadius(playerId, 0));
            assertThrows(IllegalArgumentException.class, () -> storage.setMaxRadius(playerId, 129));
            assertEquals(DEFAULT_RADIUS, storage.getMaxRadius(playerId));
        }
    }
    @Test
    void validatesDefaultRadius(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class, () ->
                PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), 0));
        assertThrows(NullPointerException.class, () ->
                new PlayerRadiusStorage(null, DEFAULT_RADIUS));
    }

    @Test
    void acceptsBoundaryValues(@TempDir Path tempDir) throws Exception {
        try (PlayerRadiusStorage storage = PlayerRadiusStorage.open(tempDir.resolve("radius.sqlite"), DEFAULT_RADIUS)) {
            UUID low = UUID.randomUUID();
            UUID high = UUID.randomUUID();

            storage.setMaxRadius(low, 1);
            storage.setMaxRadius(high, 128);

            assertEquals(1, storage.getMaxRadius(low));
            assertEquals(128, storage.getMaxRadius(high));
        }
    }
}
