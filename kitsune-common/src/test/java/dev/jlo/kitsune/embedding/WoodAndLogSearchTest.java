package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.item.MaterialTaxonomyFeatureProvider;
import dev.jlo.kitsune.model.ItemDescriptor;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that broad material-family queries such as {@code wood} and
 * {@code logs} match the items players expect, without matching unrelated items.
 */
class WoodAndLogSearchTest {

    private final MaterialTaxonomyFeatureProvider taxonomy = new MaterialTaxonomyFeatureProvider();
    private final SparseTagEmbeddingProvider embed = new SparseTagEmbeddingProvider();

    @Test
    void woodQueryMatchesOakFenceGate() {
        ItemDescriptor descriptor = realisticWoodDescriptor("minecraft:oak_fence_gate");

        double wood = embed.embedQuery("wood").cosine(embed.embed(descriptor));
        double logs = embed.embedQuery("logs").cosine(embed.embed(descriptor));

        assertTrue(wood >= 0.30, "wood score for oak fence gate was " + wood);
        assertFalse(logs >= 0.30, "logs should not match oak fence gate; score was " + logs);
    }

    @Test
    void woodAndLogsQueriesMatchOakLog() {
        ItemDescriptor descriptor = realisticWoodDescriptor("minecraft:oak_log");

        double wood = embed.embedQuery("wood").cosine(embed.embed(descriptor));
        double logs = embed.embedQuery("logs").cosine(embed.embed(descriptor));

        assertTrue(wood >= 0.30, "wood score for oak log was " + wood);
        assertTrue(logs >= 0.30, "logs score for oak log was " + logs);
    }

    @Test
    void woodQueryDoesNotMatchCrimsonFenceGate() {
        ItemDescriptor descriptor = realisticWoodDescriptor("minecraft:crimson_fence_gate");

        double wood = embed.embedQuery("wood").cosine(embed.embed(descriptor));

        assertFalse(wood >= 0.30, "wood should not match crimson fence gate; score was " + wood);
    }

    private ItemDescriptor realisticWoodDescriptor(String material) {
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
