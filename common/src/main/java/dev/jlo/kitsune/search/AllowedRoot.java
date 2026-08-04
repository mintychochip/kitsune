package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.RootIdentity;

import java.util.Objects;

public record AllowedRoot(RootIdentity identity, double distance) {
    public AllowedRoot {
        Objects.requireNonNull(identity, "Identity must not be null");
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Distance must be a finite non-negative number");
        }
    }
}
