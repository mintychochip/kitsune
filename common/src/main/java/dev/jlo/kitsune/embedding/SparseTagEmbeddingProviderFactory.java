package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;

import java.util.Map;
import java.util.Objects;

public final class SparseTagEmbeddingProviderFactory implements EmbeddingProviderFactory {
    @Override
    public String id() {
        return SparseTagEmbeddingProvider.ID;
    }

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
