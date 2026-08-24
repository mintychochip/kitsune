package dev.jlo.kitsune.model;

import java.util.Objects;

/**
 * A single traversal step within an item path.
 *
 * @param label label of the nested container or item
 * @param slot  slot index within the parent
 */
public record ItemPathStep(String label, int slot) {
    public ItemPathStep {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
        if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
    }
}
