package dev.jlo.kitsune.item;

import java.util.*;

import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.RegisteredServiceProvider;

import dev.jlo.kitsune.api.item.NestedContentsProvider;

final class NestedContentsRegistry {
    private static final List<NestedContentsProvider> BUILT_IN_PROVIDERS = List.of(
        new ShulkerContentsProvider(),
        new BundleContentsProvider()
    );

    private NestedContentsRegistry() {}

    static List<NestedContentsProvider> providers(Server server) {
        Objects.requireNonNull(server, "Server must not be null");
        ServicesManager services = server.getServicesManager();
        if (services == null) return BUILT_IN_PROVIDERS;

        List<NestedContentsProvider> providers = new ArrayList<>(BUILT_IN_PROVIDERS);
        for (
            RegisteredServiceProvider<NestedContentsProvider> registration
                : services.getRegistrations(NestedContentsProvider.class)
        ) {
            try {
                NestedContentsProvider provider = registration.getProvider();
                if (provider != null) providers.add(provider);
            } catch (Exception failure) {
                ItemFeatureRegistry.reportFailure(server, registration, failure);
            }
        }
        return List.copyOf(providers);
    }
}
