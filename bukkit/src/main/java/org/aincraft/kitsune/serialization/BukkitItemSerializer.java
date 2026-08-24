package org.aincraft.kitsune.serialization;

import org.aincraft.kitsune.api.serialization.TagProviderRegistry;
import org.aincraft.kitsune.api.indexing.SerializedItem;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.aincraft.kitsune.serialization.ItemSerializationLogic;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bukkit/Paper item serializer with clean typed API.
 * Uses composition with ItemSerializationLogic for core serialization.
 */
public final class BukkitItemSerializer {
    private final ItemSerializationLogic logic;

    public BukkitItemSerializer(TagProviderRegistry tagRegistry) {
        this.logic = new ItemSerializationLogic(tagRegistry);
    }

    /**
     * Serialize inventory contents.
     */
    public List<SerializedItem> serialize(Inventory inventory) {
        if (inventory == null) {
            return Collections.emptyList();
        }
        return serialize(inventory.getContents());
    }

    /**
     * Serialize ItemStack array (Bukkit inventory format).
     */
    public List<SerializedItem> serialize(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return Collections.emptyList();
        }
        List<ItemStack> itemList = Arrays.asList(items);
        return logic.serialize(BukkitItemAdapter.INSTANCE, itemList);
    }

    /**
     * Serialize ItemStack array with tree structure.
     */
    public ContainerNode serializeTree(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return new ContainerNode("inventory", null, null, 0, Collections.emptyList());
        }
        List<ItemStack> itemList = Arrays.asList(items);
        return logic.serializeTree(BukkitItemAdapter.INSTANCE, itemList);
    }
}
