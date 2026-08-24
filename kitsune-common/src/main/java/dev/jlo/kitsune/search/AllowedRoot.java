package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.RootIdentity;

import java.util.Objects;

/** A root identity that passed access validation, with its distance from the origin. */
public record AllowedRoot(RootIdentity identity, double distance) {
    /** Validates the identity and non-negative finite distance. */
    public AllowedRoot {
        Objects.requireNonNull(identity, "Identity must not be null");
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Distance must be a finite non-negative number");
        }
    }
}
