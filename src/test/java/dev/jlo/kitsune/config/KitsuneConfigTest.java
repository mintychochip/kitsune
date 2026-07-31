package dev.jlo.kitsune.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class KitsuneConfigTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 129, 200})
    void rejectsInvalidRadius(int radius) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(radius, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129, 200})
    void rejectsInvalidMaxRadius(int maxRadius) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, maxRadius, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidScore(double score) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, score, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 257, 500})
    void rejectsInvalidMaxResults(int maxResults) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, maxResults, 16, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129, 200})
    void rejectsInvalidMaxPathsPerRoot(int maxPathsPerRoot) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, maxPathsPerRoot, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 100})
    void rejectsInvalidWarmupTimeout(int warmupTimeoutSeconds) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, warmupTimeoutSeconds, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 301, 500})
    void rejectsInvalidMarkerDuration(int markerDurationSeconds) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, markerDurationSeconds, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 17, 100})
    void rejectsInvalidChunksPerTick(int chunksPerTick) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, chunksPerTick, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129, 500})
    void rejectsInvalidRootsPerTick(int rootsPerTick) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, rootsPerTick, 200, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 72_001, 100_000})
    void rejectsInvalidReconciliationPeriod(int reconciliationPeriodTicks) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, 8, reconciliationPeriodTicks, 4, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9, 10})
    void rejectsInvalidMaximumDepth(int maximumDepth) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, maximumDepth, 4096,
                "builtin:sparse-v1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16_385, 50_000})
    void rejectsInvalidMaximumStacksPerRoot(int maximumStacksPerRoot) {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, maximumStacksPerRoot,
                "builtin:sparse-v1"));
    }

    @Test
    void rejectsNullEmbeddingProvider() {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096, null));
    }

    @Test
    void rejectsBlankEmbeddingProvider() {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(32, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096, "   "));
    }

    @Test
    void acceptsValidBoundaries() {
        KitsuneConfig lower = KitsuneConfig.validated(1, 1, 0.0, 1, 1, 1, 1, 1, 1, 20, 1, 1, "p");
        assertEquals(1, lower.radius());
        assertEquals(1, lower.maxRadius());
        assertEquals(0.0, lower.minimumScore());
        assertEquals(1, lower.maxResults());
        assertEquals(1, lower.maxPathsPerRoot());
        assertEquals(1, lower.warmupTimeoutSeconds());
        assertEquals(1, lower.markerDurationSeconds());
        assertEquals(1, lower.chunksPerTick());
        assertEquals(1, lower.rootsPerTick());
        assertEquals(20, lower.reconciliationPeriodTicks());
        assertEquals(1, lower.maximumDepth());
        assertEquals(1, lower.maximumStacksPerRoot());

        KitsuneConfig upper = KitsuneConfig.validated(128, 128, 1.0, 256, 128, 30, 300, 16, 128, 72_000, 8, 16_384, "p");
        assertEquals(128, upper.radius());
        assertEquals(128, upper.maxRadius());
        assertEquals(1.0, upper.minimumScore());
        assertEquals(256, upper.maxResults());
        assertEquals(128, upper.maxPathsPerRoot());
        assertEquals(30, upper.warmupTimeoutSeconds());
        assertEquals(300, upper.markerDurationSeconds());
        assertEquals(16, upper.chunksPerTick());
        assertEquals(128, upper.rootsPerTick());
        assertEquals(72_000, upper.reconciliationPeriodTicks());
        assertEquals(8, upper.maximumDepth());
        assertEquals(16_384, upper.maximumStacksPerRoot());
    }

    @Test
    void rejectsInvertedRadiusRelation() {
        assertThrows(IllegalArgumentException.class,
            () -> KitsuneConfig.validated(64, 32, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
                "builtin:sparse-v1"));
    }
}
