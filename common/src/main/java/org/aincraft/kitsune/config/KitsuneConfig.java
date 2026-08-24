package org.aincraft.kitsune.config;

import com.google.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple typed config - reads from Configuration and caches values.
 */
public final class KitsuneConfig {

    private final Configuration config;
    private final Map<String, String> stringCache = new HashMap<>();

    @Inject
    public KitsuneConfig(Configuration config) {
        this.config = config;
    }

    public int getInt(String path, int def) { return config.getInt(path, def); }
    public double getDouble(String path, double def) { return config.getDouble(path, def); }
    public boolean getBoolean(String path, boolean def) { return config.getBoolean(path, def); }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public String getStringCached(String path, String def) {
        return stringCache.computeIfAbsent(path, k -> config.getString(k, def));
    }

    public String embeddingModel() { return getStringCached("embedding.model", "nomic-embed-text-v1.5"); }
    public String embeddingApiKey() { return getString("embedding.api-key", ""); }

    public String embeddingProvider() {
        String model = embeddingModel().toLowerCase();
        if (model.startsWith("text-embedding-")) return "openai";
        if (model.startsWith("embedding-") || model.contains("gecko")) return "google";
        return "onnx";
    }

    public int searchDefaultLimit() { return getInt("search.default-limit", 10); }
    public int searchMaxLimit() { return getInt("search.max-limit", 50); }
    public int searchRadius() { return getInt("search.radius", 500); }
    public double searchThreshold() { return getDouble("search.threshold", 0.7); }

    public int indexingDebounceMs() { return getInt("indexing.debounce-delay-ms", 2000); }
    public boolean indexingHopperTransfers() { return getBoolean("indexing.hopper-transfers", true); }
    public boolean indexingHopperMinecart() { return getBoolean("indexing.hopper-minecart-deposits", true); }

    public boolean protectionEnabled() { return getBoolean("protection.enabled", true); }
    public String protectionPlugin() { return getString("protection.plugin", "auto"); }

    public boolean historyEnabled() { return getBoolean("history.enabled", true); }
    public int historyMaxPerPlayer() { return getInt("history.max-entries-per-player", 50); }
    public int historyMaxGlobal() { return getInt("history.max-global-entries", 500); }
    public int historyRetentionDays() { return getInt("history.retention-days", 30); }

    public int visualizerDisplayCount() { return getInt("visualizer.item-display-count", 6); }
    public double visualizerHeight() { return getDouble("visualizer.display-height", 2.0); }
    public double visualizerRadius() { return getDouble("visualizer.display-radius", 1.0); }
    public int visualizerDurationTicks() { return getInt("visualizer.display-duration-ticks", 200); }
    public boolean visualizerEnabled() { return getBoolean("visualizer.item-display-enabled", true); }
    public boolean visualizerSpinEnabled() { return getBoolean("visualizer.spin-enabled", true); }
    public double visualizerSpinSpeed() { return getDouble("visualizer.spin-speed", 3.0); }
}
