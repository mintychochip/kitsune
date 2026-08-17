package dev.jlo.kitsune.item;

import java.util.Objects;

/** Describes a child node and its location in a traversal. */
public record TraversalChild<T>(String label, int slot, T node) {
    /** Validates the child label, slot, and node. */
    public TraversalChild {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
        if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
        Objects.requireNonNull(node, "Node must not be null");
    }
}
