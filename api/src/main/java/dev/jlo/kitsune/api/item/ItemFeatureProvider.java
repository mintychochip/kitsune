package dev.jlo.kitsune.api.item;

import dev.jlo.kitsune.model.ItemDescriptor;

/**
 * Contributes additional features to an item descriptor.
 *
 * <p>Implementations receive only neutral data and must not retain mutable
 * builder state after the call returns.
 */
public interface ItemFeatureProvider {
    void contribute(ItemDescriptor item, ItemDescriptor.Builder descriptor);
}
