package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;
import dev.jlo.kitsune.embedding.remote.OpenAiCompatibleEmbeddingProviderFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable registry of embedding provider factories keyed by factory ID.
 *
 * <p>Factories are registered first-come, so later duplicates for the same ID are rejected.
 * {@link #defaults()} exposes the built-in sparse provider and the OpenAI-compatible provider.
 */
public final class EmbeddingProviderCatalog {
    private final Map<String, EmbeddingProviderFactory> factories;

    /**
     * Builds a catalog from the given factories, rejecting nulls, blank IDs, and duplicates.
     *
     * @param registrations factories to register
     * @throws IllegalArgumentException if a factory is null, has a blank ID, or duplicates an
     *         already-registered ID
     */
    public EmbeddingProviderCatalog(Iterable<EmbeddingProviderFactory> registrations) {
        Objects.requireNonNull(registrations, "Registrations must not be null");
        Map<String, EmbeddingProviderFactory> all = new LinkedHashMap<>();
        for (EmbeddingProviderFactory factory : registrations) {
            Objects.requireNonNull(factory, "Factory must not be null");
            String id = factory.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Factory ID must not be blank");
            }
            if (all.putIfAbsent(id, factory) != null) {
                throw new IllegalArgumentException("Duplicate factory id: " + id);
            }
        }
        this.factories = Collections.unmodifiableMap(all);
    }

    /** Returns a catalog containing the built-in dense-sparse and OpenAI-compatible factories. */
    public static EmbeddingProviderCatalog defaults() {
        return new EmbeddingProviderCatalog(java.util.List.of(
            new SparseTagEmbeddingProviderFactory(),
            new OpenAiCompatibleEmbeddingProviderFactory()
        ));
    }

    /** Returns an unmodifiable view of the registered factories. */
    public Collection<EmbeddingProviderFactory> factories() {
        return Collections.unmodifiableCollection(factories.values());
    }

    /**
     * Instantiates the provider whose factory ID matches {@code id}, passing a defensive copy
     * of the settings and the given credential resolver.
     *
     * @param id factory ID of the provider to create
     * @param settings provider settings, must not be null
     * @param credentials resolver for provider credentials, must not be null
     * @return the instantiated provider
     * @throws IllegalArgumentException if the ID is blank, the settings or credentials are
     *         null, no factory exists for the ID, or the factory returns a null provider
     */
    public EmbeddingProvider create(
        String id,
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Factory ID must not be blank");
        }
        Objects.requireNonNull(settings, "Settings must not be null");
        Objects.requireNonNull(credentials, "Credentials must not be null");
        EmbeddingProviderFactory factory = factories.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Missing provider factory: " + id);
        }
        return Objects.requireNonNull(
            factory.create(Map.copyOf(settings), credentials),
            "Factory returned a null provider"
        );
    }
}
