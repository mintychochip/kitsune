package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the material taxonomy feature provider.
 */
class MaterialTaxonomyFeatureProviderTest {

    private final MaterialTaxonomyFeatureProvider provider = new MaterialTaxonomyFeatureProvider();

    private ItemDescriptor contribute(String materialKey) {
        ItemDescriptor base = ItemDescriptor.builder()
            .materialKey(materialKey)
            .amount(1)
            .build();
        ItemDescriptor.Builder enriched = ItemDescriptor.builder()
            .materialKey(materialKey)
            .amount(1);
        provider.contribute(base, enriched);
        return enriched.build();
    }

    @Test
    void diamondSwordGetsWeaponAndMaterialTraits() {
        ItemDescriptor descriptor = contribute("minecraft:diamond_sword");
        assertTrue(descriptor.traits().contains("weapon"), "weapon trait missing");
        assertTrue(descriptor.traits().contains("sword"), "sword trait missing");
        assertTrue(descriptor.traits().contains("melee"), "melee trait missing");
        assertTrue(descriptor.traits().contains("combat"), "combat trait missing");
        assertTrue(descriptor.traits().contains("diamond"), "diamond trait missing");
    }

    @Test
    void ironPickaxeGetsToolAndMaterialTraits() {
        ItemDescriptor descriptor = contribute("minecraft:iron_pickaxe");
        assertTrue(descriptor.traits().contains("tool"), "tool trait missing");
        assertTrue(descriptor.traits().contains("pickaxe"), "pickaxe trait missing");
        assertTrue(descriptor.traits().contains("mining"), "mining trait missing");
        assertTrue(descriptor.traits().contains("iron"), "iron trait missing");
    }

    @Test
    void diamondChestplateGetsArmorTraits() {
        ItemDescriptor descriptor = contribute("minecraft:diamond_chestplate");
        assertTrue(descriptor.traits().contains("armor"), "armor trait missing");
        assertTrue(descriptor.traits().contains("chestplate"), "chestplate trait missing");
        assertTrue(descriptor.traits().contains("wearable"), "wearable trait missing");
        assertTrue(descriptor.traits().contains("diamond"), "diamond trait missing");
    }

    @Test
    void redstoneOreGetsMineralAndRedstoneTraits() {
        ItemDescriptor descriptor = contribute("minecraft:redstone_ore");
        assertTrue(descriptor.traits().contains("ore"), "ore trait missing");
        assertTrue(descriptor.traits().contains("mineral"), "mineral trait missing");
        assertTrue(descriptor.traits().contains("mineable"), "mineable trait missing");
        assertTrue(descriptor.traits().contains("redstone"), "redstone trait missing");
    }

    @Test
    void chestGetsStorageTraits() {
        ItemDescriptor descriptor = contribute("minecraft:chest");
        assertTrue(descriptor.traits().contains("chest"), "chest trait missing");
        assertTrue(descriptor.traits().contains("storage"), "storage trait missing");
    }

    @Test
    void namespaceIsStrippedBeforeMatching() {
        ItemDescriptor descriptor = contribute("minecraft:diamond_sword");
        assertTrue(descriptor.traits().contains("diamond"), "namespace not stripped before matching");
    }

    @Test
    void unknownMaterialGetsNoTaxonomyTraits() {
        ItemDescriptor descriptor = contribute("minecraft:nether_star");
        assertFalse(descriptor.traits().contains("weapon"), "nether star should not be a weapon");
        assertFalse(descriptor.traits().contains("tool"), "nether star should not be a tool");
        assertFalse(descriptor.traits().contains("armor"), "nether star should not be armor");
    }

    @Test
    void enchantedItemGetsEnchantedTrait() {
        ItemDescriptor base = ItemDescriptor.builder()
            .materialKey("minecraft:diamond_sword")
            .amount(1)
            .addEnchantment("sharpness", 5)
            .build();
        ItemDescriptor.Builder enriched = ItemDescriptor.builder()
            .materialKey("minecraft:diamond_sword")
            .amount(1);
        provider.contribute(base, enriched);
        ItemDescriptor descriptor = enriched.build();
        assertTrue(descriptor.traits().contains("enchanted"), "enchanted trait missing");
        assertTrue(descriptor.traits().contains("sharpness"), "enchantment key trait missing");
        assertTrue(descriptor.traits().contains("sharpness_5"), "enchantment level trait missing");
    }

    @Test
    void coloredWoolGetsColorAndMaterialTraits() {
        ItemDescriptor descriptor = contribute("minecraft:red_wool");
        assertTrue(descriptor.traits().contains("red"), "color trait missing");
        assertTrue(descriptor.traits().contains("wool"), "wool trait missing");
        assertTrue(descriptor.traits().contains("soft"), "soft trait missing");
    }

    /** Base metadata must survive taxonomy enrichment via the registry flow. */
    @Test
    void registryEnrichmentPreservesBaseMetadata() {
        ItemDescriptor base = ItemDescriptor.builder()
            .materialKey("minecraft:diamond_sword")
            .amount(3)
            .addDisplayText("Legendary Blade")
            .addLore("Forged in fire")
            .addEnchantment("sharpness", 5)
            .addAttribute("generic.attack_damage", 7.0)
            .addTrait("custom:glowing")
            .addScalarMetadata("rarity", "epic")
            .addCustomTag("pdc_key:myplugin:tag")
            .build();

        // Mirror BukkitTraversalAdapter.copyBase: populate a fresh builder with all base fields.
        ItemDescriptor.Builder enriched = ItemDescriptor.builder()
            .materialKey(base.materialKey())
            .amount(base.amount());
        base.displayText().forEach(enriched::addDisplayText);
        base.lore().forEach(enriched::addLore);
        base.enchantments().forEach(enriched::addEnchantment);
        base.attributes().forEach(enriched::addAttribute);
        base.traits().forEach(enriched::addTrait);
        base.scalarMetadata().forEach(enriched::addScalarMetadata);
        base.customTags().forEach(enriched::addCustomTag);

        ItemFeatureRegistry registry = new ItemFeatureRegistry(
            java.util.List.of(new MaterialTaxonomyFeatureProvider()),
            failure -> {
                throw new IllegalStateException("contribution failed", failure);
            }
        );
        registry.contribute(base, enriched);
        ItemDescriptor descriptor = enriched.build();

        // Base metadata preserved
        assertEquals(3, descriptor.amount());
        assertTrue(descriptor.displayText().contains("Legendary Blade"), "display text lost");
        assertTrue(descriptor.lore().contains("Forged in fire"), "lore lost");
        assertEquals(5, descriptor.enchantments().get("sharpness"));
        assertEquals(7.0, descriptor.attributes().get("generic.attack_damage"));
        assertTrue(descriptor.traits().contains("custom:glowing"), "custom trait lost");
        assertEquals("epic", descriptor.scalarMetadata().get("rarity"));
        assertTrue(descriptor.customTags().contains("pdc_key:myplugin:tag"), "custom tag lost");

        // Taxonomy traits added
        assertTrue(descriptor.traits().contains("weapon"), "weapon trait missing");
        assertTrue(descriptor.traits().contains("diamond"), "diamond trait missing");
    }
}
