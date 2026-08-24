package dev.jlo.kitsune.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A pending definition of a container's contents to be indexed.
 *
 * <p>The fingerprint guards against re-indexing unchanged contents, and the
 * item list is defensively copied on construction.
 *
 * @param key       block key of the container
 * @param blockType type of the container block
 * @param fingerprint binary fingerprint of the observed contents
 * @param items     items to index
 */
public record ContainerDraft(BlockKey key, String blockType, byte[] fingerprint, List<ItemDraft> items) {
    public ContainerDraft {
        Objects.requireNonNull(key, "Key must not be null");
        if (blockType == null || blockType.isBlank()) throw new IllegalArgumentException("Block type must not be blank");
        if (fingerprint == null || fingerprint.length == 0) throw new IllegalArgumentException("Fingerprint must not be empty");
        Objects.requireNonNull(items, "Items must not be null");
        fingerprint = fingerprint.clone();
        items = List.copyOf(items);
        for (var item : items) {
            Objects.requireNonNull(item, "Item must not be null");
        }
    }

    /**
     * @return an immutable copy of the container items
     */
    public List<ItemDraft> items() {
        return items;
    }

    /**
     * @return a defensive copy of the fingerprint
     */
    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContainerDraft that)) return false;
        return Objects.equals(key, that.key) &&
                Objects.equals(blockType, that.blockType) &&
                Arrays.equals(fingerprint, that.fingerprint) &&
                items.equals(that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, blockType, items, Arrays.hashCode(fingerprint));
    }
}
