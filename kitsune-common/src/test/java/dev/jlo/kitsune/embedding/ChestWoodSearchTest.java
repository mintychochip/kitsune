package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.item.MaterialTaxonomyFeatureProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that wooden-storage queries such as {@code wood} and {@code plank}
 * match chests, which players think of as wooden containers.
 */
class ChestWoodSearchTest {

    private final MaterialTaxonomyFeatureProvider taxonomy = new MaterialTaxonomyFeatureProvider();
    private final SparseTagEmbeddingProvider embed = new SparseTagEmbeddingProvider();

    @Test
    void woodAndPlankQueriesMatchChest() {
        assertChestScoresAtLeast("minecraft:chest");
    }

    @Test
    void woodAndPlankQueriesMatchTrappedChest() {
        assertChestScoresAtLeast("minecraft:trapped_chest");
    }

    @Test
    void plankAliasBridgesSingularQueryToPluralToken() {
        assertTrue(
            FeatureVocabulary.aliasesFor("plank").contains("planks"),
            "plank should alias to planks"
        );
        assertTrue(
            FeatureVocabulary.aliasesFor("planks").contains("plank"),
            "planks should alias to plank"
        );
    }

    @Test
    void chestDescriptorCarriesWoodFamilyTokens() {
        ItemDescriptor descriptor = realisticStorageDescriptor("minecraft:chest");

        assertTrue(
            descriptor.traits().contains("wood"),
            "chest traits should include wood: " + descriptor.traits()
        );
        assertTrue(
            descriptor.customTags().contains("wooden"),
            "chest custom tags should include wooden: " + descriptor.customTags()
        );
        assertTrue(
            descriptor.customTags().contains("planks"),
            "chest custom tags should include planks: " + descriptor.customTags()
        );
    }

    private void assertChestScoresAtLeast(String material) {
        ItemDescriptor descriptor = realisticStorageDescriptor(material);

        double wood = embed.embedQuery("wood").cosine(embed.embed(descriptor));
        double plank = embed.embedQuery("plank").cosine(embed.embed(descriptor));
        double planks = embed.embedQuery("planks").cosine(embed.embed(descriptor));

        assertTrue(wood >= 0.30, "wood score for " + material + " was " + wood);
        assertTrue(plank >= 0.30, "plank score for " + material + " was " + plank);
        assertTrue(planks >= 0.30, "planks score for " + material + " was " + planks);
    }

    private ItemDescriptor realisticStorageDescriptor(String material) {
        ItemDescriptor base = ItemDescriptor.builder()
            .materialKey(material)
            .amount(1)
            .build();
        ItemDescriptor.Builder descriptor = ItemDescriptor.builder()
            .materialKey(material)
            .amount(1);

        taxonomy.contribute(base, descriptor);

        // Simulate the generic block/item/burnable/solid traits that BukkitItemDescriber adds.
        Set.of("block", "item", "burnable", "solid").forEach(descriptor::addTrait);
        descriptor.addScalarMetadata("material_max_stack_size", "64");
        descriptor.addScalarMetadata("material_max_durability", "0");

        return descriptor.build();
    }
}
