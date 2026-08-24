package org.aincraft.kitsune.api.serialization;

import org.aincraft.kitsune.Item;

/**
 * Provides custom tags for items during serialization.
 * Implementations transform the mutable Tags collection based on item properties.
 */
@FunctionalInterface
public interface TagProvider {
    /**
     * Transforms tags for an item using the fluent Tags API.
     *
     * @param tags The mutable tag collection to transform
     * @param item The item context to extract tags from
     */
    void appendTags(Tags tags, Item item);
}
