package dev.jlo.kitsune.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a chunk within a world by chunk coordinates.
 *
 * @param worldId id of the containing world
 * @param x       chunk x-coordinate
 * @param z       chunk z-coordinate
 */
public record ChunkKey(UUID worldId, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(worldId, "World ID must not be null");
    }
}
