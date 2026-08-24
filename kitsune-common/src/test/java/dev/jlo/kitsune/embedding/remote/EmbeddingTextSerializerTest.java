package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies deterministic descriptor and query text serialization.
 */
final class EmbeddingTextSerializerTest {
    /** Ensures canonical descriptor fields are serialized independently of incidental values. */
    @Test
    void serializesCanonicalDescriptorFieldsDeterministically() {
        ItemDescriptor first = descriptor(false);
        ItemDescriptor second = descriptor(true);

        String firstText = EmbeddingTextSerializer.document(first);
        String secondText = EmbeddingTextSerializer.document(second);

        assertEquals(firstText, secondText);
        assertTrue(firstText.contains("material: minecraft:diamond_pickaxe"));
        assertTrue(firstText.contains("enchantment: minecraft:mending"));
        assertTrue(firstText.contains("tag: tool"));
        assertFalse(firstText.contains("amount"));
    }

    /** Ensures query whitespace is normalized without introducing aliases. */
    @Test
    void normalizesQueryWhitespaceWithoutAddingAliases() {
        assertEquals(
            "mining tool",
            EmbeddingTextSerializer.query("  mining\n\ttool  ")
        );
    }

    /** Builds a descriptor whose semantic fields remain stable while incidental values vary. */
    private static ItemDescriptor descriptor(boolean reverseOrder) {
        ItemDescriptor.Builder builder = ItemDescriptor.builder()
            .materialKey("minecraft:diamond_pickaxe")
            .amount(reverseOrder ? 64 : 1)
            .addDisplayText("Diamond Pickaxe")
            .addLore("Useful for mining")
            .addEnchantment("minecraft:mending", 1)
            .addAttribute("minecraft:generic.attack_damage", 5.0)
            .addTrait("tool")
            .addScalarMetadata("custom_model_data", "42")
            .addCustomTag("tool");
        return builder.build();
    }
}
