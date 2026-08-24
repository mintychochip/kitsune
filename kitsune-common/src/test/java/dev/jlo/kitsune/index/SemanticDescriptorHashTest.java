package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies semantic descriptor hashing ignores incidental fields and matches SHA-256.
 */
class SemanticDescriptorHashTest {
    @Test
    void amountDoesNotChangeTheHash() {
        assertEquals(hash(stone(1)), hash(stone(64)));
    }

    @Test
    void materialChangeChangesTheHash() {
        assertNotEquals(hash(stone(1)), hash(dirt(1)));
    }

    @Test
    void displayNameChangeChangesTheHash() {
        ItemDescriptor named = ItemDescriptor.builder()
            .materialKey("minecraft:cobblestone")
            .amount(1)
            .addDisplayText("Lucky Cobble")
            .build();
        assertNotEquals(hash(stone(1)), hash(named));
    }

    @Test
    void bytesAreSha256LengthAndStable() {
        SemanticDescriptorHash first = hash(stone(8));
        assertEquals(32, first.bytes().length);
        assertArrayEquals(first.bytes(), hash(stone(8)).bytes());
    }

    @Test
    void rejectsNullDescriptor() {
        assertThrows(NullPointerException.class, () -> SemanticDescriptorHash.of(null));
    }

    private static SemanticDescriptorHash hash(ItemDescriptor descriptor) {
        return SemanticDescriptorHash.of(descriptor);
    }

    private static ItemDescriptor stone(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(amount).build();
    }

    private static ItemDescriptor dirt(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:dirt").amount(amount).build();
    }
}
