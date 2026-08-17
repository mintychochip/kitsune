package dev.jlo.kitsune.api.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import java.util.List;
import java.util.Objects;

/**
 * Provides nested item contents from a neutral item descriptor.
 */
public interface NestedContentsProvider {
    /**
     * Reports whether this provider understands the given item.
     *
     * @param item item to test
     * @return {@code true} if {@link #children(ItemDescriptor)} applies
     */
    boolean supports(ItemDescriptor item);

    /**
     * Returns the nested children of the given item.
     *
     * @param item item whose children to return
     * @return the nested child items
     */
    List<ChildItem> children(ItemDescriptor item);

    /**
     * A nested child item within a container.
     *
     * @param label label of the child
     * @param slot  slot index within the parent
     * @param item  descriptor of the child
     */
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
