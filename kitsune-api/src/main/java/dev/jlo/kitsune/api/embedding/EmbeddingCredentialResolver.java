package dev.jlo.kitsune.api.embedding;

import java.util.Optional;

/**
 * Resolves a credential reference to its secret value.
 */
@FunctionalInterface
public interface EmbeddingCredentialResolver {
    /**
     * Resolves a credential reference.
     *
     * @param reference credential reference to resolve
     * @return the resolved secret, or an empty optional when the reference is
     *         unrecognized or no value is available
     */
    Optional<String> resolve(String reference);
}
