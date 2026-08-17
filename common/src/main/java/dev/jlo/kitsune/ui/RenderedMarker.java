package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;

import java.util.UUID;

/**
 * A marker rendered in the world, owned by a search session.
 */
public interface RenderedMarker {
    /**
     * @return the unique id of this marker
     */
    UUID id();

    /**
     * @return the block root this marker is associated with
     */
    BlockKey root();

    /**
     * Removes this marker from the world.
     */
    void remove();
}
