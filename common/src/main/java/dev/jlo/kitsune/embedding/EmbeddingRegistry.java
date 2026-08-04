package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EmbeddingRegistry {
    private final Map<String, EmbeddingProvider> providers;
    private final EmbeddingProvider selected;

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

    public Collection<EmbeddingProvider> providers() {
        return Collections.unmodifiableCollection(providers.values());
    }

    public EmbeddingProvider selectedProvider() {
        return selected;
    }
}
