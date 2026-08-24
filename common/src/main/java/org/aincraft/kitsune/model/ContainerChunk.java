package org.aincraft.kitsune.model;

import com.google.common.base.Preconditions;
import java.util.UUID;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a chunk of a container's contents with its embedding.
 * Large containers are split into multiple chunks to respect token limits.
 *
 * Can optionally include the path through nested containers where the item was found.
 */
public record ContainerChunk(
    UUID containerId,
    int chunkIndex,
    String contentText,
    float[] embedding,
    long timestamp,
    @Nullable ContainerPath containerPath
) {
    public ContainerChunk {
        Preconditions.checkNotNull(containerId, "Container ID cannot be null");
        Preconditions.checkNotNull(contentText, "Content text cannot be null");
        Preconditions.checkNotNull(embedding, "Embedding cannot be null");
        Preconditions.checkArgument(chunkIndex >= 0, "Chunk index must be non-negative");
    }
}
