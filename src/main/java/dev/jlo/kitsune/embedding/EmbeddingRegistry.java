package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;

import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.*;
import java.util.stream.Collectors;

public class EmbeddingRegistry {
    private final Map<String, EmbeddingProvider> providers;
    private final EmbeddingProvider selected;

    public EmbeddingRegistry(ServicesManager servicesManager, String configuredId) {
        this(servicesManager.getRegistrations(EmbeddingProvider.class)
                .stream()
                .map(RegisteredServiceProvider::getProvider)
                .collect(Collectors.toList()),
                configuredId);
    }

    EmbeddingRegistry(Iterable<EmbeddingProvider> registrations, String configuredId) {
        Objects.requireNonNull(registrations, "Registrations must not be null");
        if (configuredId == null || configuredId.isBlank()) throw new IllegalArgumentException("Configured provider ID must not be blank");
        Map<String, EmbeddingProvider> all = new LinkedHashMap<>();
        all.put(SparseTagEmbeddingProvider.ID, new SparseTagEmbeddingProvider());
        for (EmbeddingProvider provider : registrations) {
            String id = provider.id();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Provider ID must not be blank");
            if (all.containsKey(id)) throw new IllegalArgumentException("Duplicate provider id: " + id);
            all.put(id, provider);
        }
        this.providers = Collections.unmodifiableMap(all);
        EmbeddingProvider provider = providers.get(configuredId);
        if (provider == null) throw new IllegalArgumentException("Missing provider: " + configuredId);
        this.selected = provider;
    }

    public Collection<EmbeddingProvider> providers() {
        return Collections.unmodifiableCollection(providers.values());
    }

    public EmbeddingProvider selectedProvider() {
        return selected;
    }
}
