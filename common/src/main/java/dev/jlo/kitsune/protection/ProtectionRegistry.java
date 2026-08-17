package dev.jlo.kitsune.protection;

import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Evaluates access to protected blocks using registered providers. */
public final class ProtectionRegistry {
    private static final long RATE_LIMIT_NANOS = 60_000_000_000L;

    private final List<BlockAccessProvider> providers;
    private final Logger logger;
    private final Map<String, Long> lastReportNanos = new ConcurrentHashMap<>();

    /** Creates a registry using the default class logger. */
    public ProtectionRegistry(List<BlockAccessProvider> providers) {
        this(providers, Logger.getLogger(ProtectionRegistry.class.getName()));
    }

    /** Creates a registry with the supplied providers and failure logger. */
    public ProtectionRegistry(List<BlockAccessProvider> providers, Logger logger) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "Providers must not be null"));
        this.logger = Objects.requireNonNull(logger, "Logger must not be null");
    }

    /** Returns the immutable registered-provider list. */
    public List<BlockAccessProvider> providers() {
        return providers;
    }

    /** Returns whether every provider permits the access context. */
    public boolean canAccess(AccessContext context) {
        Objects.requireNonNull(context, "Context must not be null");
        for (BlockAccessProvider provider : providers) {
            if (!allows(provider, context)) {
                return false;
            }
        }
        return true;
    }

    private boolean allows(BlockAccessProvider provider, AccessContext context) {
        try {
            AccessDecision decision = provider.canAccess(context);
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
