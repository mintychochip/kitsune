package dev.jlo.kitsune.api.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * Provides nested item contents from a neutral item descriptor.
 */
public interface NestedContentsProvider {
    boolean supports(ItemDescriptor item);
    List<ChildItem> children(ItemDescriptor item);

    record ChildItem(String label, int slot, ItemDescriptor item) {
        public ChildItem {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Label must not be blank");
            }
            if (slot < 0) {
                throw new IllegalArgumentException("Slot must not be negative");
            }
            Objects.requireNonNull(item, "Item must not be null");
        }
    }
}
