package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.ContainerDraft;

import java.util.Objects;
import java.util.Optional;

/** Accessible live root information with an optional current container snapshot. */
public record LiveRootSnapshot(
    AllowedRoot allowedRoot,
    Optional<ContainerDraft> draft
) {
    public LiveRootSnapshot {
        Objects.requireNonNull(allowedRoot, "Allowed root must not be null");
        Objects.requireNonNull(draft, "Draft optional must not be null");
    }
}
