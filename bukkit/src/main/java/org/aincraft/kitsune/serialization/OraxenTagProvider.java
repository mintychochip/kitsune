package org.aincraft.kitsune.serialization;

import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.api.serialization.TagProvider;
import org.aincraft.kitsune.api.serialization.Tags;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tag provider for Oraxen custom items.
 * Self-selects by checking for Oraxen's PDC key.
 *
 * Oraxen is a Minecraft plugin that allows creation of custom items.
 * This provider detects Oraxen items by their persistent data container marker
 * and adds relevant tags for searching and categorization.
 */
public class OraxenTagProvider implements TagProvider {

    // Oraxen stores item ID at namespace "oraxen", key "id"
    private static final NamespacedKey ORAXEN_ID = new NamespacedKey("oraxen", "id");

    @Override
    public void appendTags(Tags tags, Item item) {
        ItemStack stack = item.unwrap(ItemStack.class);
        if (stack == null) return;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        String oraxenId = meta.getPersistentDataContainer()
                .get(ORAXEN_ID, PersistentDataType.STRING);

        if (oraxenId == null) return;  // Not an Oraxen item, early exit (self-selection)

        // Add Oraxen-specific tags
        tags.push("oraxen", "custom", "oraxen:" + oraxenId.toLowerCase());
    }
}
