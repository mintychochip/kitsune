package dev.jlo.kitsune.model;

import java.util.Objects;
import java.util.UUID;

public record ChunkKey(UUID worldId, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(worldId, "World ID must not be null");
    }
}
