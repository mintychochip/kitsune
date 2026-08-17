package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.BlockKey;

/** Identifies a root update, its revision, and requested action. */
public record PendingRoot(BlockKey key, long revision, PendingAction action) {
    /** Validates the root key and action. */
    public PendingRoot {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("Revision must be positive");
        }
        if (action == null) {
            throw new NullPointerException("Action must not be null");
        }
    }

}
