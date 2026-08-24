package dev.jlo.kitsune.embedding.remote;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable settings configuring an OpenAI-compatible embedding provider.
 *
 * <p>Values are validated on construction; unsupported endpoint schemes,
 * out-of-range limits, and missing required values are rejected.
 *
 * @param endpoint            base endpoint of the embedding HTTP API, using
 *                            HTTPS unless loopback HTTP is explicitly allowed
 * @param model               model name to request
 * @param credentialReference reference used to resolve credentials, or empty
 *                            when unauthenticated
 * @param documentPrefix      prefix applied to document text before embedding
 * @param queryPrefix         prefix applied to query text before embedding
 * @param requestTimeout      per-request timeout, between 1ms and 60s
 * @param maxBatchSize        maximum number of inputs per request
 * @param maxResponseBytes    maximum accepted response payload size
 * @param maxRequestBytes     maximum accepted request payload size
 * @param maxRetries          number of retries for failed requests
 * @param allowInsecureHttp   whether loopback HTTP is accepted for the endpoint
 * @param dimensions          requested embedding dimensions, or {@code 0} for
 *                            the model default
 */
public record OpenAiCompatibleEmbeddingSettings(
    URI endpoint,
    String model,
    String credentialReference,
    String documentPrefix,
    String queryPrefix,
    Duration requestTimeout,
    int maxBatchSize,
    int maxResponseBytes,
    int maxRequestBytes,
    int maxRetries,
    boolean allowInsecureHttp,
    int dimensions
) {
    /** Maximum supported embedding dimensions. */
    public static final int MAX_DIMENSIONS = 16_384;
    /** Maximum supported batch size. */
    public static final int MAX_BATCH_SIZE = 256;
    /** Maximum accepted response payload size in bytes. */
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    /** Maximum accepted request payload size in bytes. */
    public static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    /** Maximum supported number of retries. */
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
        if (maxRequestBytes < 1 || maxRequestBytes > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request limit is out of bounds");
        }
        if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("Retries must be between 0 and " + MAX_RETRIES);
        }
        if (dimensions < 0 || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Dimensions are out of bounds");
        }
    }

    /**
     * Builds settings from a flat key/value map.
     *
     * <p>The {@code endpoint} and {@code model} keys are required; all other
     * settings fall back to defaults when absent or blank.
     *
     * @param settings key/value settings to parse
     * @return the parsed settings
     * @throws NullPointerException     if the map is null
     * @throws IllegalArgumentException if a required value is missing or any
     *         value is invalid
     */
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
        int requestBytes = integerSetting(settings, "max-request-bytes", 1 * 1024 * 1024);
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
            requestBytes,
            retries,
            allowInsecureHttp,
            dimensions
        );
    }

    /**
     * @return a representation of the settings affecting embedding output,
     *         suitable for cache or fingerprint keying
     */
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
