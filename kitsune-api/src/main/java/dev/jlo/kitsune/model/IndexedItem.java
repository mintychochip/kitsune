package dev.jlo.kitsune.model;

import java.util.Objects;

import dev.jlo.kitsune.api.embedding.Embedding;

/**
 * An item as stored in the search index.
 *
 * @param path       path identifying the item within its root
 * @param amount     stack amount
 * @param descriptor describing the item
 * @param embedding  the vector embedding for this item
 */
public record IndexedItem(ItemPath path, int amount, ItemDescriptor descriptor, Embedding embedding) {
    public IndexedItem {
        Objects.requireNonNull(path, "Path must not be null");
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        Objects.requireNonNull(embedding, "Embedding must not be null");
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
    }
}
