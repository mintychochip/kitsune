package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.ItemMatch;
import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchOutcome;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTreeRendererTest {
    private static final ChatTreeRenderer RENDERER = new ChatTreeRenderer();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final UUID WORLD_ID = UUID.nameUUIDFromBytes("chat-tree-renderer".getBytes(StandardCharsets.UTF_8));
    private static final String LONG_SURROGATE_SEGMENT = "\uD83D\uDC8E".repeat(16);
    private static final String LONG_LABEL_SEGMENT = LONG_SURROGATE_SEGMENT + LONG_SURROGATE_SEGMENT + " ";
    private static final String LONG_LABEL = LONG_LABEL_SEGMENT.repeat(100);

    @Test
    void normalOutputContainsOnlyAggregateAndMinimalMarker() {
        SearchOutcome outcome = oneNestedMatchOutcome(1, 1);
        String marker = plain(RENDERER.normalMarker("diamond sword", outcome.roots().getFirst()));
        String chat = plain(RENDERER.normalChat(outcome));

        assertEquals("DIAMOND SWORD FOUND · 1 · 7m", marker);
        assertEquals("Found 1 accessible storage block containing 1 matching stack.", chat);
        assertFalse(marker.contains("0.91"));
        assertFalse(marker.contains("12, 64, -8"));
        assertFalse(chat.contains("0.91"));
        assertFalse(chat.contains("12, 64, -8"));
    }

    @Test
    void verboseTreeShowsRootSlotsNestedSlotsAmountsScoresAndCoordinates() {
        String text = plain(RENDERER.verboseChat("mending", oneNestedMatchOutcome(1, 1)));

        assertEquals(
            "[1] Barrel @ 12, 64, -8 · score 0.91\n"
            + "└─ slot 4: Purple Shulker Box x1\n"
            + "   └─ slot 12: Diamond Pickaxe x1 · score 0.91\n"
            + "      └─ Mending I",
            text
        );
    }

    @Test
    void verboseAndAggregateOutputUseOutcomeTotalsAndSuffixOnlyWhenCapped() {
        SearchOutcome capped = outcomeWithTotals(1, 1, 2, 4);
        SearchOutcome uncapped = outcomeWithTotals(1, 1, 1, 1);

        assertEquals(
            "Found 2 accessible storage blocks containing 4 matching stacks.",
            plain(RENDERER.normalChat(capped))
        );

        String cappedVerbose = plain(RENDERER.verboseChat("diamond", capped));
        assertTrue(cappedVerbose.contains("Showing 1 of 2 matching storage blocks"));

        String uncappedVerbose = plain(RENDERER.verboseChat("diamond", uncapped));
        assertFalse(uncappedVerbose.contains("Showing 1 of"));
    }

    @Test
    void verboseMarkerTruncatesAtCodePointBoundaryWithoutSplittingLinesOrSurrogates() {
        String text = plain(RENDERER.verboseMarker("diamond", overflowRootMatch()));

        assertTrue(text.codePointCount(0, text.length()) <= 1024);
        assertTrue(text.startsWith("DIAMOND FOUND · 1 · 7m\nBarrel · score 0.91"));
        assertFalse(text.contains("12, 64, -8"));
        assertFalse(text.contains(LONG_LABEL_PREFIX(16)));
        assertTrue(text.endsWith("…"));
        assertTrue(text.contains("\n…"));
        assertLinesPreserveUtf16Integrity(text);
    }

    private static SearchOutcome oneNestedMatchOutcome(int rootAmount, int totalStackCount) {
        return SearchOutcome.success(List.of(
            new RootMatch(
                rootIdentity(),
                7,
                0.91,
                totalStackCount,
                List.of(leafMatch(rootAmount))
            )
        ), 1, totalStackCount);
    }

    private static SearchOutcome outcomeWithTotals(int x,
                                                  int y,
                                                  int totalRoots,
                                                  int totalMatchingStacks) {
        return SearchOutcome.success(
            List.of(new RootMatch(
                shiftedRootIdentity(x, y),
                7,
                0.91,
                1,
                List.of(leafMatch(1))
            )),
            totalRoots,
            Math.max(totalMatchingStacks, 1)
        );
    }

    private static SearchOutcome overflowLabelOutcome() {
        return SearchOutcome.success(
            List.of(overflowRootMatch()),
            1,
            1
        );
    }

    private static RootMatch overflowRootMatch() {
        return new RootMatch(
            rootIdentity(),
            7,
            0.91,
            1,
            List.of(new ItemMatch(
                ItemDescriptor.builder()
                    .materialKey("minecraft:diamond_pickaxe")
                    .amount(1)
                    .addDisplayText(LONG_LABEL)
                    .build(),
                new ItemPath(List.of(new ItemPathStep("minecraft:barrel", 4))),
                0.91,
                1
            ))
        );
    }

    private static ItemMatch leafMatch(int amount) {
        return new ItemMatch(
            pickaxeDescriptor(),
            new ItemPath(List.of(
                new ItemPathStep("minecraft:barrel", 4),
                new ItemPathStep("minecraft:purple_shulker_box", 12)
            )),
            0.91,
            amount
        );
    }

    private static ItemDescriptor pickaxeDescriptor() {
        return ItemDescriptor.builder()
            .materialKey("minecraft:diamond_pickaxe")
            .amount(1)
            .addEnchantment("minecraft:mending", 1)
            .build();
    }

    private static RootIdentity rootIdentity() {
        return new RootIdentity(
            new BlockKey(WORLD_ID, 12, 64, -8),
            "minecraft:barrel",
            new byte[]{1, 2, 3, 4, 5},
            1L
        );
    }

    private static RootIdentity shiftedRootIdentity(int x, int z) {
        return new RootIdentity(
            new BlockKey(WORLD_ID, x, 64, z),
            "minecraft:barrel",
            new byte[]{1, 2, 3, 4, 5},
            1L
        );
    }

    private static String LONG_LABEL_PREFIX(int codePoints) {
        return LONG_LABEL.codePointCount(0, LONG_LABEL.length()) < codePoints
            ? LONG_LABEL
            : LONG_LABEL.substring(0, LONG_LABEL.offsetByCodePoints(0, codePoints));
    }

    private static void assertLinesPreserveUtf16Integrity(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < text.length());
                assertTrue(Character.isLowSurrogate(text.charAt(index + 1)));
            } else if (Character.isLowSurrogate(current)) {
                assertTrue(index > 0);
                assertTrue(Character.isHighSurrogate(text.charAt(index - 1)));
            }
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PLAIN_TEXT.serialize(component);
    }
}