package dev.jlo.kitsune.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record ContainerSnapshot(BlockKey key, String blockType, byte[] fingerprint, List<IndexedItem> items) {
    public ContainerSnapshot {
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

    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    public List<IndexedItem> items() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContainerSnapshot that)) return false;
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
