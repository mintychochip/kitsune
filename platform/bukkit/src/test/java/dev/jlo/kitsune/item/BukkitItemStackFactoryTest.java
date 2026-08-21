package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies descriptor-to-stack reconstruction for marker item displays.
 */
class BukkitItemStackFactoryTest {

    @Test
    void resolvesNamespacedMaterialKeys() {
        ItemDescriptor descriptor = ItemDescriptor.builder()
            .materialKey("minecraft:diamond_pickaxe")
            .amount(1)
            .build();

        ItemStack stack = BukkitItemStackFactory.fromDescriptor(descriptor).orElseThrow();

        assertEquals(Material.DIAMOND_PICKAXE, stack.getType());
        assertEquals(1, stack.getAmount());
    }

    @Test
    void returnsEmptyForUnknownMaterial() {
        ItemDescriptor descriptor = ItemDescriptor.builder()
            .materialKey("minecraft:not_a_real_item")
            .amount(1)
            .build();

        assertTrue(BukkitItemStackFactory.fromDescriptor(descriptor).isEmpty());
    }
}
