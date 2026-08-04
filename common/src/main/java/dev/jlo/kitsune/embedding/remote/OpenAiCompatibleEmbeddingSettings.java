package dev.jlo.kitsune.embedding.remote;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record OpenAiCompatibleEmbeddingSettings(
    URI endpoint,
    String model,
    String credentialReference,
    String documentPrefix,
    String queryPrefix,
    Duration requestTimeout,
    int maxBatchSize,
    int maxResponseBytes,
    int maxRetries,
    boolean allowInsecureHttp,
    int dimensions
) {
    public static final int MAX_DIMENSIONS = 16_384;
    public static final int MAX_BATCH_SIZE = 256;
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RETRIES = 3;

    public OpenAiCompatibleEmbeddingSettings {
        Objects.requireNonNull(endpoint, "Endpoint must not be null");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("Endpoint must be absolute and include a host");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Endpoint must not contain user info or a fragment");
        }
        String scheme = endpoint.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            if (!allowInsecureHttp || !isLoopback(endpoint.getHost()) || !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Endpoint must use HTTPS unless explicitly enabled for loopback HTTP");
            }
        }
        model = required(model, "Model");
        credentialReference = credentialReference == null ? "" : credentialReference.trim();
        documentPrefix = documentPrefix == null ? "" : documentPrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        Objects.requireNonNull(requestTimeout, "Request timeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
            || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Request timeout must be between 1ms and 60s");
        }
        if (maxBatchSize < 1 || maxBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Response limit is out of bounds");
        }
        if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("Retries must be between 0 and " + MAX_RETRIES);
        }
        if (dimensions < 0 || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Dimensions are out of bounds");
        }
    }

    public static OpenAiCompatibleEmbeddingSettings from(Map<String, String> settings) {
        Objects.requireNonNull(settings, "Settings must not be null");
        URI endpoint = URI.create(requiredSetting(settings, "endpoint"));
        String model = requiredSetting(settings, "model");
        String credentialReference = settings.getOrDefault("credential-reference", "");
        String documentPrefix = settings.getOrDefault("document-prefix", "");
        String queryPrefix = settings.getOrDefault("query-prefix", "");
        Duration timeout = Duration.ofMillis(integerSetting(settings, "request-timeout-millis", 10_000));
        int batchSize = integerSetting(settings, "max-batch-size", 32);
        int responseBytes = integerSetting(settings, "max-response-bytes", 4 * 1024 * 1024);
        int retries = integerSetting(settings, "max-retries", 2);
        boolean allowInsecureHttp = booleanSetting(settings, "allow-insecure-http", false);
        int dimensions = integerSetting(settings, "dimensions", 0);
        return new OpenAiCompatibleEmbeddingSettings(
            endpoint,
            model,
            credentialReference,
            documentPrefix,
            queryPrefix,
            timeout,
            batchSize,
            responseBytes,
            retries,
            allowInsecureHttp,
            dimensions
        );
    }

    public String fingerprintMaterial() {
        return endpoint + "\n"
            + model + "\n"
            + documentPrefix + "\n"
            + queryPrefix + "\n"
            + requestTimeout.toMillis() + "\n"
            + maxBatchSize + "\n"
            + maxResponseBytes + "\n"
            + maxRetries + "\n"
            + allowInsecureHttp + "\n"
            + dimensions;
    }

    private static String requiredSetting(Map<String, String> settings, String key) {
        return required(settings.get(key), key);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }


    private static int integerSetting(Map<String, String> settings, String key, int defaultValue) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer setting: " + key, exception);
        }
    }

    private static boolean booleanSetting(Map<String, String> settings, String key, boolean defaultValue) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        throw new IllegalArgumentException("Invalid boolean setting: " + key);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "[::1]".equals(host)
            || "::1".equals(host);
    }
}
