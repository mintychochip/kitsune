package dev.jlo.kitsune.api.embedding;

import java.util.Optional;

@FunctionalInterface
public interface EmbeddingCredentialResolver {
    Optional<String> resolve(String reference);
}
