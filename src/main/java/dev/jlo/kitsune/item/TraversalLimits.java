package dev.jlo.kitsune.item;

public record TraversalLimits(int maximumDepth, int maximumStacksPerRoot) {
    public TraversalLimits {
        if (maximumDepth <= 0) throw new IllegalArgumentException("Maximum depth must be positive");
        if (maximumStacksPerRoot <= 0) throw new IllegalArgumentException("Maximum stacks per root must be positive");
    }

    public static TraversalLimits defaults() {
        return new TraversalLimits(4, 4096);
    }
}
