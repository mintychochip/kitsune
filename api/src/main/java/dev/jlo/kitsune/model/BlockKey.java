package dev.jlo.kitsune.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a block within a world by its block coordinates.
 *
 * @param worldId id of the containing world
 * @param x       block x-coordinate
 * @param y       block y-coordinate
 * @param z       block z-coordinate
 */
public record BlockKey(UUID worldId, int x, int y, int z) {
    public BlockKey {
        Objects.requireNonNull(worldId, "World ID must not be null");
    }

    /**
     * @return the key of the chunk (16-block-aligned) containing this block
     */
    public ChunkKey chunkKey() {
        return new ChunkKey(worldId, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }
}
