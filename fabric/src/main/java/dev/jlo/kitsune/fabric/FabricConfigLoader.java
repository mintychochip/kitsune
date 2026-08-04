package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.config.KitsuneConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FabricConfigLoader {
    private FabricConfigLoader() {
    }

    public static KitsuneConfig load(Path path) throws IOException {
        Properties properties = defaults();
        Files.createDirectories(path.toAbsolutePath().getParent());
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        } else {
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "Kitsune configuration");
            }
        }
        return KitsuneConfig.validated(
            integer(properties, "search.radius"),
            integer(properties, "search.max-radius"),
            decimal(properties, "search.minimum-score"),
            integer(properties, "search.max-results"),
            integer(properties, "search.max-paths-per-root"),
            integer(properties, "search.warmup-timeout-seconds"),
            integer(properties, "markers.duration-seconds"),
            integer(properties, "index.chunks-per-tick"),
            integer(properties, "index.roots-per-tick"),
            integer(properties, "index.reconciliation-period-ticks"),
            integer(properties, "index.maximum-depth"),
            integer(properties, "index.maximum-stacks-per-root"),
            properties.getProperty("embedding.provider")
        );
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("search.radius", "32");
        properties.setProperty("search.max-radius", "128");
        properties.setProperty("search.minimum-score", "0.30");
        properties.setProperty("search.max-results", "32");
        properties.setProperty("search.max-paths-per-root", "16");
        properties.setProperty("search.warmup-timeout-seconds", "3");
        properties.setProperty("markers.duration-seconds", "20");
        properties.setProperty("index.chunks-per-tick", "2");
        properties.setProperty("index.roots-per-tick", "8");
        properties.setProperty("index.reconciliation-period-ticks", "200");
        properties.setProperty("index.maximum-depth", "4");
        properties.setProperty("index.maximum-stacks-per-root", "4096");
        properties.setProperty("embedding.provider", "builtin:sparse-v1");
        return properties;
    }

    private static int integer(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing configuration: " + key);
        return Integer.parseInt(value.trim());
    }

    private static double decimal(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing configuration: " + key);
        return Double.parseDouble(value.trim());
    }
}
