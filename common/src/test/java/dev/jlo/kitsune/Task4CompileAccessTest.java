package dev.jlo.kitsune;

import dev.jlo.kitsune.index.SqliteIndexRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the repository {@code open()} path is publicly accessible and migrates to the expected schema. */
class Task4CompileAccessTest {
    @Test
    void publicOpenIsAccessibleFromOtherPackage(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("external.db");
        try (SqliteIndexRepository repo = (SqliteIndexRepository) SqliteIndexRepository.open(db)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT value FROM schema_metadata WHERE key = 'schema_version'")) {
                assertTrue(rs.next(), "schema_version should exist after open");
                assertEquals(2, rs.getInt(1), "open() must migrate repository to schema_version=2");
            }
        }
    }
}
