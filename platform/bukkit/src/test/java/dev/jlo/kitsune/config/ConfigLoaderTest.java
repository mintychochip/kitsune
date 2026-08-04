package dev.jlo.kitsune.config;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @Test
    void publicLoadAcceptsJavaPlugin() throws Exception {
        Method m = ConfigLoader.class.getMethod("load", org.bukkit.plugin.java.JavaPlugin.class);
        assertEquals(1, m.getParameterCount());
        assertEquals(org.bukkit.plugin.java.JavaPlugin.class, m.getParameterTypes()[0]);
    }

    @Test
    void loadsValidConfiguration() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("search.radius", 32);
        cfg.set("search.max-radius", 128);
        cfg.set("search.minimum-score", 0.30);
        cfg.set("search.max-results", 32);
        cfg.set("search.max-paths-per-root", 16);
        cfg.set("search.warmup-timeout-seconds", 3);
        cfg.set("markers.duration-seconds", 20);
        cfg.set("index.chunks-per-tick", 2);
        cfg.set("index.roots-per-tick", 8);
        cfg.set("index.reconciliation-period-ticks", 200);
        cfg.set("index.maximum-depth", 4);
        cfg.set("index.maximum-stacks-per-root", 4096);
        cfg.set("embedding.provider", "builtin:sparse-v1");
        KitsuneConfig loaded = ConfigLoader.load(cfg);
        assertEquals(32, loaded.radius());
        assertEquals(0.30, loaded.minimumScore());
        assertEquals(4, loaded.maximumDepth());
    }

    @Test
    void propagatesInvalidSearchRadius() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("search.radius", 129);
        cfg.set("search.max-radius", 128);
        cfg.set("search.minimum-score", 0.30);
        cfg.set("search.max-results", 32);
        cfg.set("search.max-paths-per-root", 16);
        cfg.set("search.warmup-timeout-seconds", 3);
        cfg.set("markers.duration-seconds", 20);
        cfg.set("index.chunks-per-tick", 2);
        cfg.set("index.roots-per-tick", 8);
        cfg.set("index.reconciliation-period-ticks", 200);
        cfg.set("index.maximum-depth", 4);
        cfg.set("index.maximum-stacks-per-root", 4096);
        cfg.set("embedding.provider", "builtin:sparse-v1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(cfg));
        assertEquals("Invalid search radius", ex.getMessage());
    }

    @Test
    void rejectsBlankEmbeddingProvider() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("search.radius", 32);
        cfg.set("search.max-radius", 128);
        cfg.set("search.minimum-score", 0.30);
        cfg.set("search.max-results", 32);
        cfg.set("search.max-paths-per-root", 16);
        cfg.set("search.warmup-timeout-seconds", 3);
        cfg.set("markers.duration-seconds", 20);
        cfg.set("index.chunks-per-tick", 2);
        cfg.set("index.roots-per-tick", 8);
        cfg.set("index.reconciliation-period-ticks", 200);
        cfg.set("index.maximum-depth", 4);
        cfg.set("index.maximum-stacks-per-root", 4096);
        cfg.set("embedding.provider", "   ");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(cfg));
        assertEquals("Invalid embedding provider", ex.getMessage());
    }

    @Test
    void rejectsMissingMinimumScore() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("search.radius", 32);
        cfg.set("search.max-radius", 128);
        cfg.set("search.max-results", 32);
        cfg.set("search.max-paths-per-root", 16);
        cfg.set("search.warmup-timeout-seconds", 3);
        cfg.set("markers.duration-seconds", 20);
        cfg.set("index.chunks-per-tick", 2);
        cfg.set("index.roots-per-tick", 8);
        cfg.set("index.reconciliation-period-ticks", 200);
        cfg.set("index.maximum-depth", 4);
        cfg.set("index.maximum-stacks-per-root", 4096);
        cfg.set("embedding.provider", "builtin:sparse-v1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(cfg));
        assertEquals("Invalid minimum score", ex.getMessage());
    }

    @Test
    void rejectsNonNumericMinimumScore() {
        MemoryConfiguration cfg = new MemoryConfiguration();
        cfg.set("search.radius", 32);
        cfg.set("search.max-radius", 128);
        cfg.set("search.minimum-score", "abc");
        cfg.set("search.max-results", 32);
        cfg.set("search.max-paths-per-root", 16);
        cfg.set("search.warmup-timeout-seconds", 3);
        cfg.set("markers.duration-seconds", 20);
        cfg.set("index.chunks-per-tick", 2);
        cfg.set("index.roots-per-tick", 8);
        cfg.set("index.reconciliation-period-ticks", 200);
        cfg.set("index.maximum-depth", 4);
        cfg.set("index.maximum-stacks-per-root", 4096);
        cfg.set("embedding.provider", "builtin:sparse-v1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(cfg));
        assertEquals("Invalid minimum score", ex.getMessage());
    }
}
