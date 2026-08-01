package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.util.*;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemFeatureRegistryTest {

    @Test
    void failingConsumerDoesNotMutateBaseline() {
        ItemDescriptor.Builder baseline = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1)
            .addEnchantment("power", 1)
            .addAttribute("generic.attack_damage", 1.0)
            .addScalarMetadata("damage", "1")
            .addCustomTag("baseline");

        Consumer<ItemDescriptor.Builder> failingConsumer = builder -> {
            builder.addEnchantment("power", 5);
            builder.addAttribute("generic.attack_damage", 2.0);
            builder.addScalarMetadata("damage", "9");
            builder.addCustomTag("attempted");
            throw new IllegalStateException("consumer failure");
        };

        assertThrows(IllegalStateException.class, () ->
            ItemFeatureRegistry.applyContribution(baseline, failingConsumer)
        );

        ItemDescriptor descriptor = baseline.build();
        assertEquals(1, descriptor.enchantments().get("power"));
        assertEquals(1.0, descriptor.attributes().get("generic.attack_damage"));
        assertEquals("1", descriptor.scalarMetadata().get("damage"));
        assertTrue(descriptor.customTags().contains("baseline"));
        assertFalse(descriptor.customTags().contains("attempted"));
    }

    @Test
    void successfulConsumerCannotReplaceBaselineKeyedEntries() {
        ItemDescriptor.Builder baseline = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1)
            .addEnchantment("power", 1)
            .addAttribute("generic.attack_damage", 1.0)
            .addScalarMetadata("damage", "1")
            .addCustomTag("baseline");

        Consumer<ItemDescriptor.Builder> appendConsumer = builder -> {
            builder.addEnchantment("power", 5);
            builder.addAttribute("generic.attack_damage", 2.0);
            builder.addScalarMetadata("damage", "8");
            builder.addCustomTag("appended");
        };

        ItemFeatureRegistry.applyContribution(baseline, appendConsumer);

        ItemDescriptor descriptor = baseline.build();
        assertEquals(1, descriptor.enchantments().get("power"));
        assertEquals(1.0, descriptor.attributes().get("generic.attack_damage"));
        assertEquals("1", descriptor.scalarMetadata().get("damage"));
        assertTrue(descriptor.customTags().contains("appended"));
        assertTrue(descriptor.customTags().contains("baseline"));
    }
}
