package org.aincraft.kitsune.storage.metadata;

import org.aincraft.kitsune.Location;

import java.util.Objects;

/**
 * Represents a chunk with its associated container location.
 */
public record ChunkWithLocation(ChunkMetadata metadata, Location location) {

}
