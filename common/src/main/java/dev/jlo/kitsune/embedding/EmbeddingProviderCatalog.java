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

public final class EmbeddingProviderCatalog {
    private final Map<String, EmbeddingProviderFactory> factories;

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

    public static EmbeddingProviderCatalog defaults() {
        return new EmbeddingProviderCatalog(java.util.List.of(
            new SparseTagEmbeddingProviderFactory(),
            new OpenAiCompatibleEmbeddingProviderFactory()
        ));
    }

    public Collection<EmbeddingProviderFactory> factories() {
        return Collections.unmodifiableCollection(factories.values());
    }

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
