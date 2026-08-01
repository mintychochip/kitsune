package dev.jlo.kitsune.search;

import dev.jlo.kitsune.model.BlockKey;

import java.util.Objects;
import java.util.UUID;

public record SearchContext(UUID playerId, BlockKey origin) {
    public SearchContext {
        Objects.requireNonNull(playerId, "PlayerId must not be null");
        Objects.requireNonNull(origin, "Origin must not be null");
    }
}
