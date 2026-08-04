package dev.jlo.kitsune.config;

public record KitsuneConfig(int radius, int maxRadius, double minimumScore, int maxResults,
                            int maxPathsPerRoot, int warmupTimeoutSeconds, int markerDurationSeconds,
                            int chunksPerTick, int rootsPerTick, int reconciliationPeriodTicks,
                            int maximumDepth, int maximumStacksPerRoot, String embeddingProvider) {

    public static KitsuneConfig validated(int radius, int maxRadius, double minimumScore,
                                          int maxResults, int maxPathsPerRoot,
                                          int warmupTimeoutSeconds, int markerDurationSeconds,
                                          int chunksPerTick, int rootsPerTick,
                                          int reconciliationPeriodTicks, int maximumDepth,
                                          int maximumStacksPerRoot, String embeddingProvider) {
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
        if (embeddingProvider == null || embeddingProvider.isBlank())
            throw invalid("embedding provider");
        return new KitsuneConfig(radius, maxRadius, minimumScore, maxResults, maxPathsPerRoot,
                warmupTimeoutSeconds, markerDurationSeconds, chunksPerTick, rootsPerTick,
                reconciliationPeriodTicks, maximumDepth, maximumStacksPerRoot, embeddingProvider);
    }

    private static IllegalArgumentException invalid(String category) {
        return new IllegalArgumentException("Invalid " + category);
    }
}
