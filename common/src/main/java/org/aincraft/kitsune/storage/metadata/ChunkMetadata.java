package org.aincraft.kitsune.storage.metadata;

import java.util.UUID;

/**
 * Represents metadata for a chunk of container content.
 */
public record ChunkMetadata(UUID chunkId, int ordinal, int chunkIndex, String contentText,
                            long timestamp, String containerPath) {

}
