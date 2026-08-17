package dev.jlo.kitsune.item;

import java.util.List;

import dev.jlo.kitsune.model.ItemDraft;

/** Reports the leaves, visit count, and truncation state of a nested-item walk. */
public record NestedItemWalkResult(List<ItemDraft> leaves, int visitedStacks, boolean truncated) {
    /** Defensively copies leaves and validates the visit count. */
    public NestedItemWalkResult {
        leaves = List.copyOf(leaves);
        if (visitedStacks < 0) throw new IllegalArgumentException("Visited stacks must not be negative");
    }
}
