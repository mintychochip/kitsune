package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProtectionRegistry {
    private static final long RATE_LIMIT_NANOS = 60_000_000_000L;

    private final ServicesManager servicesManager;
    private final List<BlockAccessProvider> injectedProviders;
    private final Logger logger;
    private final Map<String, Long> lastReportNanos = new ConcurrentHashMap<>();

    public ProtectionRegistry(ServicesManager servicesManager, Logger logger) {
        this.servicesManager = Objects.requireNonNull(servicesManager, "Services manager must not be null");
        this.injectedProviders = null;
        this.logger = Objects.requireNonNull(logger, "Logger must not be null");
    }

    ProtectionRegistry(List<BlockAccessProvider> providers) {
        this.servicesManager = null;
        this.injectedProviders = List.copyOf(Objects.requireNonNull(providers, "Providers must not be null"));
        this.logger = null;
    }

    public boolean canAccess(Player player, Block block) {
        Objects.requireNonNull(player, "Player must not be null");
        Objects.requireNonNull(block, "Block must not be null");

        if (injectedProviders != null) {
            for (BlockAccessProvider provider : injectedProviders) {
                if (!allows(provider, player, block)) {
                    return false;
                }
            }
            return true;
        }

        Collection<RegisteredServiceProvider<BlockAccessProvider>> registrations;
        try {
            registrations = servicesManager.getRegistrations(BlockAccessProvider.class);
        } catch (RuntimeException failure) {
            reportFailure(servicesManager, failure);
            return false;
        }
        if (registrations == null) {
            reportFailure(servicesManager, new NullPointerException("Services manager returned null registrations"));
            return false;
        }

        for (RegisteredServiceProvider<BlockAccessProvider> registration : registrations) {
            if (registration == null) {
                reportFailure(servicesManager, new NullPointerException("Services manager returned a null registration"));
                return false;
            }

            BlockAccessProvider provider;
            try {
                provider = registration.getProvider();
            } catch (RuntimeException failure) {
                reportFailure(registration, failure);
                return false;
            }
            if (provider == null) {
                reportFailure(registration, new NullPointerException("Registration returned a null provider"));
                return false;
            }
            if (!allows(provider, player, block)) {
                return false;
            }
        }
        return true;
    }

    private boolean allows(BlockAccessProvider provider, Player player, Block block) {
        try {
            AccessDecision decision = provider.canAccess(player, block);
            if (decision == null) {
                reportFailure(provider, new NullPointerException("Provider returned a null decision"));
                return false;
            }
            return decision != AccessDecision.DENY;
        } catch (RuntimeException failure) {
            reportFailure(provider, failure);
            return false;
        }
    }

    private void reportFailure(Object provider, Throwable failure) {
        if (logger == null) {
            return;
        }

        String providerClass = provider.getClass().getName();
        long now = System.nanoTime();
        boolean[] shouldLog = {false};
        lastReportNanos.compute(providerClass, (_key, previous) -> {
            if (previous == null || now - previous >= RATE_LIMIT_NANOS) {
                shouldLog[0] = true;
                return now;
            }
            return previous;
        });
        if (shouldLog[0]) {
            logger.log(
                Level.WARNING,
                "Kitsune protection provider " + providerClass + " failed; denying access",
                failure
            );
        }
    }
}
