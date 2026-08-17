package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable registry of embedding providers, always including the built-in sparse provider.
 *
 * <p>Registrations are added after the built-in provider, so a registration duplicating its ID
 * is rejected. The provider whose ID matches the configured value is exposed as the selected
 * provider.
 */
public final class EmbeddingRegistry {
    private final Map<String, EmbeddingProvider> providers;
    private final EmbeddingProvider selected;

    /**
     * Builds a registry from the given providers plus the built-in sparse provider.
     *
     * @param registrations additional providers, must not contain null or the built-in ID
     * @param configuredId ID of the provider to select, must be registered
     * @throws IllegalArgumentException if a registration is null or duplicate, the configured
     *         ID is blank or unregistered, or a provider reports a blank ID
     */
    public EmbeddingRegistry(
        Iterable<EmbeddingProvider> registrations,
        String configuredId
    ) {
        Objects.requireNonNull(registrations, "Registrations must not be null");
        if (configuredId == null || configuredId.isBlank()) {
            throw new IllegalArgumentException("Configured provider ID must not be blank");
        }

        Map<String, EmbeddingProvider> all = new LinkedHashMap<>();
        all.put(SparseTagEmbeddingProvider.ID, new SparseTagEmbeddingProvider());
        for (EmbeddingProvider provider : registrations) {
            Objects.requireNonNull(provider, "Provider must not be null");
            String id = provider.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Provider ID must not be blank");
            }
            if (all.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate provider id: " + id);
            }
            all.put(id, provider);
        }

        this.providers = Collections.unmodifiableMap(all);
        EmbeddingProvider selectedProvider = providers.get(configuredId);
        if (selectedProvider == null) {
            throw new IllegalArgumentException("Missing provider: " + configuredId);
        }
        this.selected = selectedProvider;
    }

    /** Returns an unmodifiable view of all registered providers. */
    public Collection<EmbeddingProvider> providers() {
        return Collections.unmodifiableCollection(providers.values());
    }

    /** Returns the provider matching the configured ID. */
    public EmbeddingProvider selectedProvider() {
        return selected;
    }
}
