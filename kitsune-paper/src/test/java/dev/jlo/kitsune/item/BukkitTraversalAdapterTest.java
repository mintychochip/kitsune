package dev.jlo.kitsune.item;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemHoverPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies platform taxonomy enrichment preserves the captured source-item hover payload. */
class BukkitTraversalAdapterTest {

    @Test
    void enrichmentPreservesCompleteHoverPayload() {
        ItemHoverPayload hover = new ItemHoverPayload(
            "minecraft:oak_fence_gate",
            1,
            null,
            Map.of(
                "minecraft:custom_name",
                "'{\"text\":\"Workshop Gate\"}'",
                "minecraft:lore",
                "['{\"text\":\"Keep closed\"}']"
            ),
            Set.of()
        );
        ItemDescriptor base = ItemDescriptor.builder()
            .materialKey("minecraft:oak_fence_gate")
            .amount(1)
            .hoverPayload(hover)
            .build();

        ItemDescriptor enriched = BukkitTraversalAdapter.enrich(base);

        assertEquals(hover, enriched.hoverPayload());
        assertTrue(enriched.customTags().contains("wood"));
    }

}
