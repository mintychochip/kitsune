package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Factory for {@link SparseTagEmbeddingProvider}.
 *
 * <p>The built-in sparse provider accepts no settings; supplying any results in an
 * {@link IllegalArgumentException}.
 */
public final class SparseTagEmbeddingProviderFactory implements EmbeddingProviderFactory {
    @Override
    public String id() {
        return SparseTagEmbeddingProvider.ID;
    }

    /**
     * Creates a {@link SparseTagEmbeddingProvider}.
     *
     * @param settings must be empty, must not be null
     * @param credentials must not be null (unused by this provider)
     * @return a new sparse provider instance
     * @throws IllegalArgumentException if settings is non-empty or either argument is null
     */
    @Override
    public EmbeddingProvider create(
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    ) {
        Objects.requireNonNull(settings, "Settings must not be null");
        Objects.requireNonNull(credentials, "Credentials must not be null");
        if (!settings.isEmpty()) {
            throw new IllegalArgumentException("Built-in sparse provider does not accept settings");
        }
        return new SparseTagEmbeddingProvider();
    }
}
