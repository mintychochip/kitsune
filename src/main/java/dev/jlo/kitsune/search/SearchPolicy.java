package dev.jlo.kitsune.search;

import java.time.Duration;
import java.util.Objects;

public record SearchPolicy(int radius, double minimumScore, int maxResults, int maxPathsPerRoot, Duration warmupTimeout) {
    public SearchPolicy {
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException("Search radius must be between 1 and 128");
        }
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("Minimum score must be a finite number between 0 and 1");
        }
        if (maxResults < 1 || maxResults > 256) {
            throw new IllegalArgumentException("Max results must be between 1 and 256");
        }
        if (maxPathsPerRoot < 1 || maxPathsPerRoot > 128) {
            throw new IllegalArgumentException("Max paths per root must be between 1 and 128");
        }
        Objects.requireNonNull(warmupTimeout, "Warmup timeout must not be null");
        if (warmupTimeout.isNegative()
            || warmupTimeout.isZero()
            || warmupTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Warmup timeout must be positive and at most 30 seconds");
        }
    }
}
