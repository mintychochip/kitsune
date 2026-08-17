package dev.jlo.kitsune.model;

import java.util.*;

/**
 * A pending item definition to be indexed.
 *
 * @param path       path identifying the item within its root
 * @param amount     stack amount
 * @param descriptor describing the item
 */
public record ItemDraft(ItemPath path, int amount, ItemDescriptor descriptor) {
    public ItemDraft {
        Objects.requireNonNull(path, "Path must not be null");
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
    }
}
