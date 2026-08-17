package dev.jlo.kitsune.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and validates Kitsune configuration from Bukkit configuration sections.
 */
public final class ConfigLoader {
    private ConfigLoader() {}

    /**
     * Loads configuration from a plugin's current Bukkit configuration.
     *
     * @param plugin plugin providing the configuration
     * @return validated Kitsune configuration
     */
    public static KitsuneConfig load(JavaPlugin plugin) {
        return load(plugin.getConfig());
    }

    static KitsuneConfig load(ConfigurationSection config) {
        int radius = config.getInt("search.radius");
        int maxRadius = config.getInt("search.max-radius");
        double minimumScore = config.getDouble("search.minimum-score", Double.NaN);
        int maxResults = config.getInt("search.max-results");
        int maxPathsPerRoot = config.getInt("search.max-paths-per-root");
        int warmupTimeoutSeconds = config.getInt("search.warmup-timeout-seconds");
        int markerDurationSeconds = config.getInt("markers.duration-seconds");
        int chunksPerTick = config.getInt("index.chunks-per-tick");
        int rootsPerTick = config.getInt("index.roots-per-tick");
        int reconciliationPeriodTicks = config.getInt("index.reconciliation-period-ticks");
        int maximumDepth = config.getInt("index.maximum-depth");
        int maximumStacksPerRoot = config.getInt("index.maximum-stacks-per-root");
        String embeddingProvider = config.getString("embedding.provider");
        return KitsuneConfig.validated(radius, maxRadius, minimumScore, maxResults, maxPathsPerRoot,
                warmupTimeoutSeconds, markerDurationSeconds, chunksPerTick, rootsPerTick,
                reconciliationPeriodTicks, maximumDepth, maximumStacksPerRoot, embeddingProvider);
    }
}
