package dev.jlo.kitsune.model;

import java.util.Objects;
import java.util.UUID;

public record BlockKey(UUID worldId, int x, int y, int z) {
    public BlockKey {
        Objects.requireNonNull(worldId, "World ID must not be null");
    }

    public ChunkKey chunkKey() {
        return new ChunkKey(worldId, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }
}
