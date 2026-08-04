package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;

import java.util.Map;
import java.util.Objects;

public final class OpenAiCompatibleEmbeddingProviderFactory implements EmbeddingProviderFactory {
    @Override
    public String id() {
        return OpenAiCompatibleEmbeddingProvider.FACTORY_ID;
    }

    @Override
    public OpenAiCompatibleEmbeddingProvider create(
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    ) {
        Objects.requireNonNull(settings, "Settings must not be null");
        Objects.requireNonNull(credentials, "Credentials must not be null");
        return new OpenAiCompatibleEmbeddingProvider(
            OpenAiCompatibleEmbeddingSettings.from(settings),
            credentials
        );
    }
}
