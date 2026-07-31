package dev.jlo.kitsune.model;

import java.util.Objects;

public record ItemPathStep(String label, int slot) {
    public ItemPathStep {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
        if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
    }
}
