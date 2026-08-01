package dev.jlo.kitsune.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemDescriptorBoundsTest {

    @Test
    void normalizedKeyCollisionPreservesBaselineInsertion() {
        String baselineKey = "p" + "a".repeat(255) + "z";
        String contributionKey = "p" + "a".repeat(255);

        ItemDescriptor.Builder baseline = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1)
            .addScalarMetadata(baselineKey, "baseline");

        ItemDescriptor.Builder contribution = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1)
            .addScalarMetadata(contributionKey, "provider");

        baseline.appendMissing(contribution.buildBounded(256, 256));

        ItemDescriptor descriptor = baseline.buildBounded(256, 256);

        String normalizedKey = "p" + "a".repeat(255);
        assertEquals("baseline", descriptor.scalarMetadata().get(normalizedKey));
    }

    @Test
    void boundedBuildDiscardsOptionalBlankAfterTruncation() {
        String blanky = " ".repeat(256) + "x";

        ItemDescriptor.Builder builder = ItemDescriptor.builder()
            .materialKey("stone")
            .amount(1)
            .addDisplayText(blanky)
            .addLore(blanky)
            .addEnchantment(blanky, 1)
            .addAttribute(blanky, 1.0)
            .addTrait(blanky)
            .addScalarMetadata(blanky, blanky)
            .addCustomTag(blanky);

        ItemDescriptor bounded = builder.buildBounded(256, 256);

        assertTrue(bounded.displayText().isEmpty());
        assertTrue(bounded.lore().isEmpty());
        assertTrue(bounded.enchantments().isEmpty());
        assertTrue(bounded.attributes().isEmpty());
        assertTrue(bounded.traits().isEmpty());
        assertTrue(bounded.scalarMetadata().isEmpty());
        assertTrue(bounded.customTags().isEmpty());

        ItemDescriptor unbounded = builder.build();
        assertFalse(unbounded.displayText().isEmpty());
        assertFalse(unbounded.lore().isEmpty());
        assertFalse(unbounded.enchantments().isEmpty());
        assertFalse(unbounded.attributes().isEmpty());
        assertFalse(unbounded.traits().isEmpty());
        assertFalse(unbounded.scalarMetadata().isEmpty());
        assertFalse(unbounded.customTags().isEmpty());
    }
}
