package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemHoverPayload;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies lossless conversion between Paper/Adventure ShowItem data and the index payload. */
class BukkitItemHoverPayloadTest {

    @Test
    void roundTripsAllAdventureShowItemFields() {
        Map<Key, DataComponentValue> components = new LinkedHashMap<>();
        components.put(
            Key.key("minecraft:custom_name"),
            BinaryTagHolder.binaryTagHolder("'{\"text\":\"Miner\"}'")
        );
        components.put(
            Key.key("minecraft:lore"),
            BinaryTagHolder.binaryTagHolder("['{\"text\":\"Deep mine\"}']")
        );
        components.put(
            Key.key("minecraft:trim"),
            BinaryTagHolder.binaryTagHolder("{material:\"minecraft:diamond\",pattern:\"minecraft:spire\"}")
        );
        components.put(
            Key.key("minecraft:potion_contents"),
            BinaryTagHolder.binaryTagHolder("{potion:\"minecraft:strong_healing\"}")
        );
        components.put(
            Key.key("minecraft:attribute_modifiers"),
            BinaryTagHolder.binaryTagHolder("{modifiers:[]}")
        );
        components.put(
            Key.key("minecraft:repair_cost"),
            DataComponentValue.removed()
        );

        HoverEvent.ShowItem source = HoverEvent.ShowItem.showItem(
            Key.key("minecraft:diamond_pickaxe"),
            1,
            BinaryTagHolder.binaryTagHolder("{Damage:7}")
        ).dataComponents(components);

        ItemHoverPayload persisted = BukkitItemHoverPayload.capture(source);
        HoverEvent.ShowItem restored = BukkitItemHoverPayload.toHoverEvent(persisted).value();

        assertEquals(source.item(), restored.item());
        assertEquals(source.count(), restored.count());
        assertEquals(source.nbt().string(), restored.nbt().string());
        assertEquals(source.dataComponents().keySet(), restored.dataComponents().keySet());
        for (Map.Entry<Key, DataComponentValue> entry : source.dataComponents().entrySet()) {
            DataComponentValue restoredValue = restored.dataComponents().get(entry.getKey());
            if (entry.getValue() instanceof DataComponentValue.Removed) {
                assertInstanceOf(DataComponentValue.Removed.class, restoredValue);
            } else {
                BinaryTagHolder expected = ((DataComponentValue.TagSerializable) entry.getValue()).asBinaryTag();
                BinaryTagHolder actual = ((DataComponentValue.TagSerializable) restoredValue).asBinaryTag();
                assertEquals(expected.string(), actual.string());
            }
        }
        assertTrue(persisted.dataComponents().containsKey("minecraft:custom_name"));
    }
}
