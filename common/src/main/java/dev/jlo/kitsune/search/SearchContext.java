package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.BlockKey;

import java.util.Objects;
import java.util.UUID;

/** Identifies the player and origin for a search request. */
public record SearchContext(UUID playerId, BlockKey origin) {
    /** Validates the player and origin. */
    public SearchContext {
        Objects.requireNonNull(playerId, "PlayerId must not be null");
        Objects.requireNonNull(origin, "Origin must not be null");
    }
}
