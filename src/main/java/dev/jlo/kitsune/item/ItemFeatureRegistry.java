package dev.jlo.kitsune.item;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import dev.jlo.kitsune.api.item.ItemFeatureProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

final class ItemFeatureRegistry {
    private static final long WARNING_WINDOW_NANOS = 60_000_000_000L;
    private static final Map<String, Long> LAST_WARNING_NANOS = new ConcurrentHashMap<>();

    private ItemFeatureRegistry() {}

    static void contribute(
        Server server,
        org.bukkit.inventory.ItemStack stack,
        ItemDescriptor.Builder descriptor
    ) {
        for (ItemFeatureProvider provider : providers(server)) {
            try {
                applyContribution(
                    descriptor,
                    isolated -> provider.contribute(stack.clone(), isolated)
                );
            } catch (Exception failure) {
                reportFailure(server, provider, failure);
            }
        }
    }

    static void applyContribution(
        ItemDescriptor.Builder baseline,
        Consumer<ItemDescriptor.Builder> providerCall
    ) {
        Objects.requireNonNull(baseline, "Baseline must not be null");
        Objects.requireNonNull(providerCall, "Provider call must not be null");
        ItemDescriptor.Builder isolated = ItemDescriptor.builder()
            .materialKey("kitsune:placeholder")
            .amount(1);
        providerCall.accept(isolated);
        ItemDescriptor contribution = isolated.buildBounded(256, 256);
        baseline.appendMissing(contribution);
    }

    static List<ItemFeatureProvider> providers(Server server) {
        Objects.requireNonNull(server, "Server must not be null");
        ServicesManager services = server.getServicesManager();
        if (services == null) return List.of();

        List<ItemFeatureProvider> providers = new ArrayList<>();
        for (
            RegisteredServiceProvider<ItemFeatureProvider> registration
                : services.getRegistrations(ItemFeatureProvider.class)
        ) {
            try {
                ItemFeatureProvider provider = registration.getProvider();
                if (provider != null) providers.add(provider);
            } catch (Exception failure) {
                reportFailure(server, registration, failure);
            }
        }
        return List.copyOf(providers);
    }

    static void reportFailure(Server server, Object provider, Throwable failure) {
        Objects.requireNonNull(server, "Server must not be null");
        Objects.requireNonNull(provider, "Provider must not be null");
        Objects.requireNonNull(failure, "Failure must not be null");

        String providerClass = provider.getClass().getName();
        long now = System.nanoTime();
        Long previous = LAST_WARNING_NANOS.get(providerClass);
        if (previous != null && now - previous < WARNING_WINDOW_NANOS) return;

        LAST_WARNING_NANOS.put(providerClass, now);
        server.getLogger().log(
            Level.WARNING,
            "Kitsune item provider " + providerClass
                + " failed; using baseline container data",
            failure
        );
    }
}
