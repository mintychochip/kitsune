package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemHoverPayload;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.ItemMatch;
import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchOutcome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the chat and marker text produced by {@link ChatTreeRenderer},
 * including aggregate output, verbose trees, totals, truncation, and hover events.
 */
class ChatTreeRendererTest {
    private static final ChatTreeRenderer RENDERER = new ChatTreeRenderer();
    private static final UUID WORLD_ID = UUID.nameUUIDFromBytes("chat-tree-renderer".getBytes(StandardCharsets.UTF_8));
    private static final String LONG_SURROGATE_SEGMENT = "\uD83D\uDC8E".repeat(16);
    private static final String LONG_LABEL_SEGMENT = LONG_SURROGATE_SEGMENT + LONG_SURROGATE_SEGMENT + " ";
    private static final String LONG_LABEL = LONG_LABEL_SEGMENT.repeat(100);

    /**
     * Normal chat renders the per-root tree with locations, nested items, and
     * amounts, but omits scores. Normal marker stays compact.
     */
    @Test
    void normalChatShowsTreeWithoutScoresAndMarkerIsMinimal() {
        SearchOutcome outcome = oneNestedMatchOutcome(1, 1);
        String marker = RENDERER.normalMarker("diamond sword", outcome.roots().getFirst());
        Component chat = RENDERER.normalChat(outcome);

        assertEquals("DIAMOND SWORD FOUND · 1 · 7m", marker);
        assertFalse(marker.contains("0.91"));
        assertFalse(marker.contains("12, 64, -8"));

        String chatPlain = PlainTextComponentSerializer.plainText().serialize(chat);
        assertFalse(chatPlain.contains("score"));

        assertEquals(
            "[1] Barrel @ 12, 64, -8\n"
            + "└─ slot 4: Purple Shulker Box x1\n"
            + "   └─ slot 12: Diamond Pickaxe x1\n"
            + "      └─ Mending I",
            chatPlain
        );
    }

    /**
     * The verbose chat rendering shows root slots, nested slots, amounts,
     * scores, and coordinates, color-coded.
     */
    @Test
    void verboseTreeShowsRootSlotsNestedSlotsAmountsScoresAndCoordinates() {
        Component text = RENDERER.verboseChat("mending", oneNestedMatchOutcome(1, 1));

        String stripped = PlainTextComponentSerializer.plainText().serialize(text);
        assertEquals(
            "[1] Barrel @ 12, 64, -8 · score 0.91\n"
            + "└─ slot 4: Purple Shulker Box x1\n"
            + "   └─ slot 12: Diamond Pickaxe x1 · score 0.91\n"
            + "      └─ Mending I",
            stripped
        );

        assertTrue(hasComponent(text, "[1]", NamedTextColor.YELLOW));
        assertTrue(hasComponent(text, "Barrel", NamedTextColor.AQUA));
        assertTrue(hasComponent(text, " @ 12, 64, -8", NamedTextColor.GRAY));
        assertTrue(hasComponent(text, " · score ", NamedTextColor.GRAY));
        assertTrue(hasComponent(text, "0.91", NamedTextColor.GREEN));
        assertTrue(hasComponent(text, "└─ slot 4: ", NamedTextColor.DARK_GRAY));
        assertTrue(hasComponent(text, "Purple Shulker Box", NamedTextColor.GOLD));
        assertTrue(hasComponent(text, "└─ slot 12: ", NamedTextColor.DARK_GRAY));
        assertTrue(hasComponent(text, "Diamond Pickaxe", NamedTextColor.GOLD));
    }
    /**
     * The leaf item name has an item hover tooltip showing the item with its enchantments.
     */
    @Test
    void leafItemNameHasHoverTooltip() {
        Component text = RENDERER.verboseChat("mending", oneNestedMatchOutcome(1, 1));

        TextComponent leaf = findTextComponent(text, c -> c.content().contains("Diamond Pickaxe"));
        assertNotNull(leaf);

        HoverEvent<?> hover = leaf.hoverEvent();
        assertNotNull(hover);
        assertEquals(HoverEvent.Action.SHOW_ITEM, hover.action());

        HoverEvent.ShowItem showItem = (HoverEvent.ShowItem) hover.value();
        assertEquals(Key.key("minecraft:diamond_pickaxe"), showItem.item());
        assertEquals(1, showItem.count());

        var enchantments = showItem.dataComponents().get(Key.key("minecraft:enchantments"));
        assertNotNull(enchantments);
        assertTrue(enchantments instanceof BinaryTagHolder);
        String enchantNbt = ((BinaryTagHolder) enchantments).string().toLowerCase(Locale.ROOT);

        var customName = showItem.dataComponents().get(Key.key("minecraft:custom_name"));
        assertNotNull(customName);
        assertTrue(customName instanceof BinaryTagHolder);
        assertTrue(((BinaryTagHolder) customName).string().contains("Miner"));
        assertTrue(enchantNbt.contains("mending"), "Expected mending in enchantments: " + enchantNbt);
    }

    /**
     * Verbose and aggregate output use outcome totals and append a
     * "showing X of Y" suffix only when results are capped.
     */
    @Test
    void verboseAndAggregateOutputUseOutcomeTotalsAndSuffixOnlyWhenCapped() {
        SearchOutcome capped = outcomeWithTotals(1, 1, 2, 4);
        SearchOutcome uncapped = outcomeWithTotals(1, 1, 1, 1);

        Component normalCapped = RENDERER.normalChat(capped);
        Component normalUncapped = RENDERER.normalChat(uncapped);

        String normalCappedPlain = PlainTextComponentSerializer.plainText().serialize(normalCapped);
        String normalUncappedPlain = PlainTextComponentSerializer.plainText().serialize(normalUncapped);

        assertFalse(normalCappedPlain.contains("score"));
        assertFalse(normalUncappedPlain.contains("score"));
        assertTrue(normalCappedPlain.contains("[1] Barrel"));

        Component cappedVerbose = RENDERER.verboseChat("diamond", capped);
        String cappedVerbosePlain = PlainTextComponentSerializer.plainText().serialize(cappedVerbose);
        assertTrue(cappedVerbosePlain.contains("Showing 1 of 2 matching storage blocks"));

        Component uncappedVerbose = RENDERER.verboseChat("diamond", uncapped);
        String uncappedVerbosePlain = PlainTextComponentSerializer.plainText().serialize(uncappedVerbose);
        assertFalse(uncappedVerbosePlain.contains("Showing 1 of"));
    }

    /**
     * The verbose marker truncates at a code-point boundary without splitting
     * lines or splitting surrogate pairs.
     */
    @Test
    void verboseMarkerTruncatesAtCodePointBoundaryWithoutSplittingLinesOrSurrogates() {
        String text = RENDERER.verboseMarker("diamond", overflowRootMatch());

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
            .hoverPayload(new ItemHoverPayload(
                "minecraft:diamond_pickaxe",
                1,
                null,
                java.util.Map.of(
                    "minecraft:custom_name",
                    "'{\"text\":\"Miner\"}'",
                    "minecraft:enchantments",
                    "{levels:{\"minecraft:mending\":1}}"
                ),
                java.util.Set.of()
            ))
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

    private static boolean hasComponent(Component root, String content, NamedTextColor color) {
        return findTextComponent(root, c -> c.content().contains(content) && color.equals(c.color())) != null;
    }

    private static TextComponent findTextComponent(Component root, Predicate<TextComponent> predicate) {
        if (root instanceof TextComponent text && predicate.test(text)) {
            return text;
        }
        for (Component child : root.children()) {
            TextComponent found = findTextComponent(child, predicate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
