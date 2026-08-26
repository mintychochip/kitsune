package dev.jlo.kitsune.command;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemHoverPayload;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.ItemMatch;
import dev.jlo.kitsune.search.RootMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies live item payload refresh and persisted-snapshot fallback. */
class LiveItemHoverRefresherTest {
    private static final UUID WORLD_ID = UUID.randomUUID();
    private static final BlockKey ROOT = new BlockKey(WORLD_ID, 1, 64, 2);
    private static final ItemPath PATH = new ItemPath(List.of(
        new ItemPathStep("minecraft:barrel", 4)
    ));

    @Test
    void replacesPersistedPayloadWhenLiveFingerprintAndPathMatch() {
        byte[] fingerprint = {1, 2, 3};
        ItemDescriptor indexed = descriptor(ItemHoverPayload.base("minecraft:diamond_pickaxe", 1));
        ItemHoverPayload complete = new ItemHoverPayload(
            "minecraft:diamond_pickaxe",
            1,
            null,
            Map.of(
                "minecraft:custom_name", "'{\"text\":\"Miner\"}'",
                "minecraft:enchantments", "{levels:{\"minecraft:mending\":1}}"
            ),
            Set.of()
        );
        RootMatch root = root(fingerprint, indexed);
        ContainerDraft live = new ContainerDraft(
            ROOT,
            "minecraft:barrel",
            fingerprint,
            List.of(new ItemDraft(PATH, 1, descriptor(complete)))
        );

        List<ItemMatch> refreshed = LiveItemHoverRefresher.refresh(
            root,
            Optional.of(live)
        );

        assertEquals(complete, refreshed.getFirst().descriptor().hoverPayload());
        assertEquals(root.itemMatches().getFirst().score(), refreshed.getFirst().score());
        assertEquals(PATH, refreshed.getFirst().path());
    }

    @Test
    void retainsPersistedPayloadWhenLiveFingerprintChanged() {
        ItemDescriptor indexed = descriptor(ItemHoverPayload.base("minecraft:diamond_pickaxe", 1));
        RootMatch root = root(new byte[]{1, 2, 3}, indexed);
        ContainerDraft changed = new ContainerDraft(
            ROOT,
            "minecraft:barrel",
            new byte[]{9, 9, 9},
            List.of(new ItemDraft(PATH, 1, descriptor(new ItemHoverPayload(
                "minecraft:diamond_pickaxe",
                1,
                null,
                Map.of("minecraft:custom_name", "'{\"text\":\"Changed\"}'"),
                Set.of()
            ))))
        );

        List<ItemMatch> refreshed = LiveItemHoverRefresher.refresh(
            root,
            Optional.of(changed)
        );

        assertSame(root.itemMatches(), refreshed);
        assertEquals(indexed.hoverPayload(), refreshed.getFirst().descriptor().hoverPayload());
    }

    private static ItemDescriptor descriptor(ItemHoverPayload hover) {
        return ItemDescriptor.builder()
            .materialKey("minecraft:diamond_pickaxe")
            .amount(1)
            .hoverPayload(hover)
            .build();
    }

    private static RootMatch root(byte[] fingerprint, ItemDescriptor descriptor) {
        return new RootMatch(
            new RootIdentity(ROOT, "minecraft:barrel", fingerprint, 1),
            5,
            0.9,
            1,
            List.of(new ItemMatch(descriptor, PATH, 0.9, 1))
        );
    }
}
