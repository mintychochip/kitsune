package dev.jlo.kitsune.item;

import java.util.Objects;

public record TraversalChild<T>(String label, int slot, T node) {
    public TraversalChild {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
        if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
        Objects.requireNonNull(node, "Node must not be null");
    }
}
