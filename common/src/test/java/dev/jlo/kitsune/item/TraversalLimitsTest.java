package dev.jlo.kitsune.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies validation of nested-item traversal limits. */
class TraversalLimitsTest {

    @Test
    void acceptsPositiveLimits() {
        var limits = new TraversalLimits(4, 4096);
        assertEquals(4, limits.maximumDepth());
        assertEquals(4096, limits.maximumStacksPerRoot());
    }

    @Test
    void rejectsZeroDepth() {
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(0, 10));
    }

    @Test
    void rejectsZeroStacks() {
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(10, 0));
    }

    @Test
    void rejectsNegativeDepth() {
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(-2, 10));
    }

    @Test
    void rejectsNegativeStacks() {
        assertThrows(IllegalArgumentException.class, () -> new TraversalLimits(10, -2));
    }
}
