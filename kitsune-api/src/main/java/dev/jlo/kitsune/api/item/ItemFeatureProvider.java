package dev.jlo.kitsune.api.item;

import dev.jlo.kitsune.model.ItemDescriptor;

/**
 * Contributes additional features to an item descriptor.
 *
 * <p>Implementations receive only neutral data and must not retain mutable
 * builder state after the call returns.
 */
public interface ItemFeatureProvider {
    /**
     * Contributes features into the descriptor being built.
     *
     * @param item       the neutral item being enriched
     * @param descriptor target builder receiving the contribution
     */
    void contribute(ItemDescriptor item, ItemDescriptor.Builder descriptor);
}
