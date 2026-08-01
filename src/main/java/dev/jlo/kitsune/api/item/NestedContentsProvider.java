package dev.jlo.kitsune.api.item;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Provides nested item contents for container-like items.
 *
 * <p>Implementations must run on the server thread, must not retain Bukkit objects
 * after {@link #children(ItemStack)} returns, and must clone stacks before handing
 * them to callers.
 */
public interface NestedContentsProvider {
    boolean supports(ItemStack stack);
    List<ChildItem> children(ItemStack stack);

    record ChildItem(String label, int slot, ItemStack stack) {
        public ChildItem {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
            if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
            if (stack == null) throw new IllegalArgumentException("Stack must not be null");
        }
    }
}
