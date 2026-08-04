package dev.jlo.kitsune.api.embedding;

import java.util.Map;

public interface EmbeddingProviderFactory {
    String id();

    EmbeddingProvider create(
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    );
}
