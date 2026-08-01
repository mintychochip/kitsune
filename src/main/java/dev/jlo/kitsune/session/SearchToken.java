package dev.jlo.kitsune.session;

import java.util.Objects;
import java.util.UUID;

public record SearchToken(UUID playerId, long generation) {
    public SearchToken {
        Objects.requireNonNull(playerId, "Player ID must not be null");
        if (generation < 0) {
            throw new IllegalArgumentException("Generation must not be negative");
        }
    }
}
