package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmbeddingTextSerializerTest {
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

    @Test
    void normalizesQueryWhitespaceWithoutAddingAliases() {
        assertEquals(
            "mining tool",
            EmbeddingTextSerializer.query("  mining\n\ttool  ")
        );
    }

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
