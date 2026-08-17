package dev.jlo.kitsune.model;

import dev.jlo.kitsune.api.embedding.Embedding;

import java.util.*;

/**
 * An item as stored in the search index, paired with its embedding.
 *
 * @param path       path identifying the item within its root
 * @param amount     stack amount
 * @param descriptor describing the item
 * @param embedding  vector encoding of the descriptor
 */
public record IndexedItem(ItemPath path, int amount, ItemDescriptor descriptor, Embedding embedding) {
    public IndexedItem {
        Objects.requireNonNull(path, "Path must not be null");
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        Objects.requireNonNull(embedding, "Embedding must not be null");
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
    }
}
