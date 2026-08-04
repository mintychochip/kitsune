package dev.jlo.kitsune.item;

import java.util.List;

import org.bukkit.Server;

final class NestedContentsRegistry {
    private static final List<BukkitNestedContentsProvider> BUILT_IN_PROVIDERS = List.of(
        new ShulkerContentsProvider(),
        new BundleContentsProvider()
    );

    private NestedContentsRegistry() {}

    static List<BukkitNestedContentsProvider> providers(Server server) {
        if (server == null) throw new IllegalArgumentException("Server must not be null");
        return BUILT_IN_PROVIDERS;
    }
}
