package dev.jlo.kitsune.api.embedding;

import java.util.Map;

/**
 * Creates {@link EmbeddingProvider} instances from settings maps.
 */
public interface EmbeddingProviderFactory {
    /**
     * @return the provider id this factory creates
     */
    String id();

    /**
     * Creates a provider from the given settings and credential resolver.
     *
     * @param settings    key/value settings configuring the provider
     * @param credentials resolver for any credential references in the
     *                    settings
     * @return the created provider
     */
    EmbeddingProvider create(
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    );
}
