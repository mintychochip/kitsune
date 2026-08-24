package org.aincraft.kitsune.serialization;

import org.aincraft.kitsune.Item;
import org.aincraft.kitsune.api.serialization.TagProvider;
import org.aincraft.kitsune.api.serialization.Tags;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;

/**
 * Provides tags based on Bukkit/Paper DataComponents.
 * Adds platform-specific tags for item properties.
 */
public class BukkitDataComponentTagProvider implements TagProvider {

    @Override
    public void appendTags(Tags tags, Item item) {
        ItemStack stack = item.unwrap(ItemStack.class);

        // Durability-related
        if (stack.hasData(DataComponentTypes.UNBREAKABLE)) {
            tags.push("unbreakable", "indestructible");
        }

        if (stack.hasData(DataComponentTypes.MAX_DAMAGE)) {
            int maxDamage = stack.getData(DataComponentTypes.MAX_DAMAGE);
            int damage = stack.getDataOrDefault(DataComponentTypes.DAMAGE, 0);
            if (damage > 0) {
                tags.push("damaged");
                double percent = (double) (maxDamage - damage) / maxDamage;
                if (percent < 0.25) {
                    tags.push("nearlybroke");
                } else if (percent < 0.5) {
                    tags.push("worn");
                }
            } else {
                tags.push("pristine");
            }
        }

        // Appearance
        if (stack.hasData(DataComponentTypes.DYED_COLOR)) {
            tags.push("dyed", "colored");
        }

        if (stack.hasData(DataComponentTypes.TRIM)) {
            tags.push("trimmed", "decorated");
        }

        if (stack.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            tags.push("custommodeldata", "custom");
        }

        // Rarity
        if (stack.hasData(DataComponentTypes.RARITY)) {
            var rarity = stack.getData(DataComponentTypes.RARITY);
            if (rarity != null) {
                tags.push(rarity.name().toLowerCase());
            }
        }

        // Container types
        if (stack.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
            tags.push("bundle", "container");
        }

        if (stack.hasData(DataComponentTypes.CONTAINER)) {
            tags.push("container");
            if (stack.getType().name().contains("SHULKER_BOX")) {
                tags.push("shulker");
            }
        }

        // Charged/loaded weapons
        if (stack.hasData(DataComponentTypes.CHARGED_PROJECTILES)) {
            tags.push("loaded", "charged");
        }

        // Books
        if (stack.hasData(DataComponentTypes.WRITABLE_BOOK_CONTENT)) {
            tags.push("book", "writable");
        }

        if (stack.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT)) {
            tags.push("book", "signed", "written");
        }

        // Potions
        if (stack.hasData(DataComponentTypes.POTION_CONTENTS)) {
            tags.push("potion", "brewable");
        }

        // Food properties
        if (stack.hasData(DataComponentTypes.FOOD)) {
            var food = stack.getData(DataComponentTypes.FOOD);
            if (food != null) {
                if (food.canAlwaysEat()) {
                    tags.push("alwaysedible");
                }
                int nutrition = food.nutrition();
                if (nutrition >= 8) {
                    tags.push("veryfilling");
                } else if (nutrition >= 4) {
                    tags.push("filling");
                } else {
                    tags.push("snack");
                }
            }
        }

        // Tool properties
        if (stack.hasData(DataComponentTypes.TOOL)) {
            var tool = stack.getData(DataComponentTypes.TOOL);
            if (tool != null) {
                float speed = tool.defaultMiningSpeed();
                if (speed >= 12.0) {
                    tags.push("veryfast");
                } else if (speed >= 8.0) {
                    tags.push("fast");
                }
            }
        }

        // Music
        if (stack.getType().isRecord()) {
            tags.push("musicdisc", "record");
        }

        // Gravity
        if (stack.getType().hasGravity()) {
            tags.push("falling", "gravity");
        }

        // Block/item distinction
        if (stack.getType().isBlock()) {
            tags.push("block");
        } else {
            tags.push("item");
        }
    }
}
