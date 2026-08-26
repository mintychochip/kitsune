package dev.jlo.kitsune.config;

/**
 * Immutable configuration for the Kitsune indexing and search engine.
 *
 * <p>All values are validated by {@link #validated} before construction; a record built
 * directly bypasses that validation. Fields control coordinate-based search, per-tick work
 * budgets, reconciliation, traversal depth, and the selected embedding provider.
 */
public record KitsuneConfig(int radius, int maxRadius, double minimumScore, int maxResults,
                            int maxPathsPerRoot, int warmupTimeoutSeconds, int markerDurationSeconds,
                            int chunksPerTick, int rootsPerTick, int reconciliationPeriodTicks,
                            int maximumDepth, int maximumStacksPerRoot, int rrfK, int fullTextLimit,
                            int semanticLimit, String embeddingProvider) {

    /**
     * Validates all configuration values and returns a config only when every bound holds.
     *
     * @param radius minimum search radius, at least 1
     * @param maxRadius maximum search radius, between 1 and 128 and not below {@code radius}
     * @param minimumScore required similarity score in {@code [0.0, 1.0]}, finite
     * @param maxResults maximum result count, between 1 and 256
     * @param maxPathsPerRoot maximum paths indexed per root, between 1 and 128
     * @param warmupTimeoutSeconds warmup timeout in seconds, between 1 and 30
     * @param markerDurationSeconds marker display duration in seconds, between 1 and 300
     * @param chunksPerTick chunk work budget per tick, between 1 and 16
     * @param rootsPerTick root work budget per tick, between 1 and 128
     * @param reconciliationPeriodTicks reconciliation period in ticks, between 20 and 72000
     * @param maximumDepth maximum traversal depth, between 1 and 8
     * @param maximumStacksPerRoot maximum stacks stored per root, between 1 and 16384
     * @param rrfK reciprocal rank fusion constant, between 1 and 1000
     * @param fullTextLimit maximum full-text retriever results, between 1 and 256
     * @param semanticLimit maximum semantic retriever results, between 1 and 256
     * @param embeddingProvider identifier of the configured embedding provider, non-blank
     * @return a validated configuration
     * @throws IllegalArgumentException if any value is outside its permitted range
     */
    public static KitsuneConfig validated(int radius, int maxRadius, double minimumScore,
                                          int maxResults, int maxPathsPerRoot,
                                          int warmupTimeoutSeconds, int markerDurationSeconds,
                                          int chunksPerTick, int rootsPerTick,
                                          int reconciliationPeriodTicks, int maximumDepth,
                                          int maximumStacksPerRoot, int rrfK, int fullTextLimit,
                                          int semanticLimit, String embeddingProvider) {
        if (radius < 1 || maxRadius < 1 || maxRadius > 128 || radius > maxRadius)
            throw invalid("search radius");
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0)
            throw invalid("minimum score");
        if (maxResults < 1 || maxResults > 256)
            throw invalid("max results");
        if (maxPathsPerRoot < 1 || maxPathsPerRoot > 128)
            throw invalid("max paths per root");
        if (warmupTimeoutSeconds < 1 || warmupTimeoutSeconds > 30)
            throw invalid("warmup timeout");
        if (markerDurationSeconds < 1 || markerDurationSeconds > 300)
            throw invalid("marker duration");
        if (chunksPerTick < 1 || chunksPerTick > 16 || rootsPerTick < 1 || rootsPerTick > 128)
            throw invalid("index budget");
        if (reconciliationPeriodTicks < 20 || reconciliationPeriodTicks > 72_000)
            throw invalid("reconciliation period");
        if (maximumDepth < 1 || maximumDepth > 8)
            throw invalid("maximum depth");
        if (maximumStacksPerRoot < 1 || maximumStacksPerRoot > 16_384)
            throw invalid("maximum stacks per root");
        if (rrfK < 1 || rrfK > 1000)
            throw invalid("hybrid rrf k");
        if (fullTextLimit < 1 || fullTextLimit > 256)
            throw invalid("hybrid full-text limit");
        if (semanticLimit < 1 || semanticLimit > 256)
            throw invalid("hybrid semantic limit");
        if (embeddingProvider == null || embeddingProvider.isBlank())
            throw invalid("embedding provider");
        return new KitsuneConfig(radius, maxRadius, minimumScore, maxResults, maxPathsPerRoot,
                warmupTimeoutSeconds, markerDurationSeconds, chunksPerTick, rootsPerTick,
                reconciliationPeriodTicks, maximumDepth, maximumStacksPerRoot, rrfK, fullTextLimit,
                semanticLimit, embeddingProvider);
    }

    /** Builds an {@link IllegalArgumentException} naming the offending configuration category. */
    private static IllegalArgumentException invalid(String category) {
        return new IllegalArgumentException("Invalid " + category);
    }
}
