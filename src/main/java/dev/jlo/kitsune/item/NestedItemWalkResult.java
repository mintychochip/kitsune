package dev.jlo.kitsune.item;

import java.util.List;

import dev.jlo.kitsune.model.ItemDraft;

public record NestedItemWalkResult(List<ItemDraft> leaves, int visitedStacks, boolean truncated) {
    public NestedItemWalkResult {
        leaves = List.copyOf(leaves);
        if (visitedStacks < 0) throw new IllegalArgumentException("Visited stacks must not be negative");
    }
}
