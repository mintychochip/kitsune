package org.aincraft.kitsune.indexing;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.api.indexing.SerializedItem;

import java.util.List;

/**
 * Platform-agnostic interface for container indexing.
 * Handles scheduling and indexing of container items.
 */
public interface ContainerIndexer {

    /**
     * Schedules indexing of a container with serialized items.
     *
     * @param locations the container locations (primary and all positions)
     * @param serializedItems the serialized items to index
     */
    void scheduleIndex(ContainerLocations locations, List<SerializedItem> serializedItems);

    /**
     * Shuts down the indexer and cancels pending tasks.
     */
    void shutdown();
}
