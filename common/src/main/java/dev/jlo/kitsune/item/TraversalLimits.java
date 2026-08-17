package dev.jlo.kitsune.item;

/** Limits depth and stack count during nested-item traversal. */
public record TraversalLimits(int maximumDepth, int maximumStacksPerRoot) {
    /** Validates that both traversal limits are positive. */
    public TraversalLimits {
        if (maximumDepth <= 0) throw new IllegalArgumentException("Maximum depth must be positive");
        if (maximumStacksPerRoot <= 0) throw new IllegalArgumentException("Maximum stacks per root must be positive");
    }

    /** Returns the default traversal limits. */
    public static TraversalLimits defaults() {
        return new TraversalLimits(4, 4096);
    }
}
