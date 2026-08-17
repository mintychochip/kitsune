package dev.jlo.kitsune.embedding.remote;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies parsing and validation of OpenAI-compatible embedding settings.
 */
final class OpenAiCompatibleEmbeddingSettingsTest {
    /** Ensures configured values and defaults are applied when parsing settings. */
    @Test
    void parsesConfiguredValuesAndDefaults() {
        OpenAiCompatibleEmbeddingSettings settings = OpenAiCompatibleEmbeddingSettings.from(Map.of(
            "endpoint", "https://example.test/v1/embeddings",
            "model", "embed-v1",
            "credential-reference", "env:API_KEY",
            "document-prefix", "document: ",
            "query-prefix", "query: ",
            "request-timeout-millis", "2500",
            "max-batch-size", "8",
            "max-response-bytes", "123456",
            "max-retries", "2",
            "dimensions", "768"
        ));

        assertEquals(URI.create("https://example.test/v1/embeddings"), settings.endpoint());
        assertEquals("embed-v1", settings.model());
        assertEquals("env:API_KEY", settings.credentialReference());
        assertEquals("document: ", settings.documentPrefix());
        assertEquals("query: ", settings.queryPrefix());
        assertEquals(Duration.ofMillis(2500), settings.requestTimeout());
        assertEquals(8, settings.maxBatchSize());
        assertEquals(123456, settings.maxResponseBytes());
        assertEquals(2, settings.maxRetries());
        assertEquals(768, settings.dimensions());
    }

    @Test
    void rejectsNonLocalHttpUnlessExplicitlyAllowed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiCompatibleEmbeddingSettings.from(Map.of(
                "endpoint", "http://example.test/v1/embeddings",
                "model", "embed-v1"
            ))
        );
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiCompatibleEmbeddingSettings.from(Map.of(
                "endpoint", "https://example.test/v1/embeddings",
                "model", "embed-v1",
                "max-retries", "4"
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenAiCompatibleEmbeddingSettings.from(Map.of(
                "endpoint", "https://example.test/v1/embeddings",
                "model", "embed-v1",
                "dimensions", "20000"
            ))
        );
    }

    @Test
    void allowsExplicitLocalHttpForDevelopment() {
        OpenAiCompatibleEmbeddingSettings settings = OpenAiCompatibleEmbeddingSettings.from(Map.of(
            "endpoint", "http://127.0.0.1:8080/v1/embeddings",
            "model", "embed-v1",
            "allow-insecure-http", "true"
        ));

        assertEquals("127.0.0.1", settings.endpoint().getHost());
    }
}
