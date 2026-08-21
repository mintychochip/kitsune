package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reconstructs Bukkit {@link ItemStack}s from neutral {@link ItemDescriptor}s
 * for world marker presentation.
 */
public final class BukkitItemStackFactory {
    private BukkitItemStackFactory() {}

    /**
     * Builds a single-item stack representing the descriptor for display.
     *
     * @param descriptor indexed item description
     * @return the stack, or empty when the material cannot be resolved
     */
    public static Optional<ItemStack> fromDescriptor(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        Material material = resolveMaterial(descriptor.materialKey());
        if (material == null || material == Material.AIR) {
            return Optional.empty();
        }

        ItemStack stack = new ItemStack(material, 1);
        if (serverAvailable()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                applyEnchantments(meta, descriptor.enchantments());
                stack.setItemMeta(meta);
            }
        }
        return Optional.of(stack);
    }

    private static void applyEnchantments(ItemMeta meta, Map<String, Integer> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = resolveEnchantment(entry.getKey());
            Integer level = entry.getValue();
            if (enchantment != null && level != null && level > 0) {
                meta.addEnchant(enchantment, level, true);
            }
        }
    }

    private static Material resolveMaterial(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) {
            return null;
        }
        int colon = materialKey.indexOf(':');
        String local = colon >= 0 ? materialKey.substring(colon + 1) : materialKey;
        Material matched = Material.matchMaterial(local.toUpperCase(Locale.ROOT));
        if (matched != null) {
            return matched;
        }
        matched = Material.matchMaterial(materialKey);
        if (matched != null) {
            return matched;
        }
        return resolveMaterialFromRegistry(materialKey);
    }

    private static Material resolveMaterialFromRegistry(String materialKey) {
        if (!serverAvailable()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(materialKey);
        return key == null ? null : Registry.MATERIAL.get(key);
    }

    private static Enchantment resolveEnchantment(String enchantmentKey) {
        if (enchantmentKey == null || enchantmentKey.isBlank()) {
            return null;
        }
        if (!serverAvailable()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(enchantmentKey);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    private static boolean serverAvailable() {
        try {
            return Bukkit.getServer() != null;
        } catch (Throwable failure) {
            return false;
        }
    }
}
