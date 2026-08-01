package dev.jlo.kitsune.api.item;

import org.bukkit.inventory.ItemStack;
import dev.jlo.kitsune.model.ItemDescriptor;

/**
 * Contributes additional features to an item descriptor.
 *
 * <p>Implementations must run on the server thread and must not retain Bukkit objects.
 */
public interface ItemFeatureProvider {
    void contribute(ItemStack stack, ItemDescriptor.Builder descriptor);
}
