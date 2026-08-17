package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;

import java.util.Objects;

/** Describes one matched item, its path, similarity score, and amount. */
public record ItemMatch(ItemDescriptor descriptor, ItemPath path, double score, int amount) {
    /** Validates the descriptor, path, score, and positive amount. */
    public ItemMatch {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        Objects.requireNonNull(path, "Path must not be null");
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("Score must be a finite number between 0 and 1");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
