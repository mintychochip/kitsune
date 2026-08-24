package dev.jlo.kitsune.embedding.remote;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies environment-based credential reference resolution.
 */
final class EnvironmentCredentialResolverTest {
    /** Ensures only matching environment references are resolved, never other reference forms. */
    @Test
    void resolvesEnvironmentReferenceWithoutExposingOtherReferences() {
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
            Map.of("API_KEY", "secret-value")::get
        );

        assertEquals("secret-value", resolver.resolve("env:API_KEY").orElseThrow());
        assertTrue(resolver.resolve("env:MISSING").isEmpty());
        assertTrue(resolver.resolve("file:key").isEmpty());
        assertTrue(resolver.resolve("").isEmpty());
    }
}
