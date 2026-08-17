package dev.jlo.kitsune.ui;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.search.ItemMatch;
import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * Renders search results as human-readable chat and marker text.
 */
public final class ChatTreeRenderer {

    /**
     * Renders the compact world marker text for a matching root.
     *
     * @param query normalized user query
     * @param root matching root
     * @return marker text
     */
    public String normalMarker(String query, RootMatch root) {
        String normalized = normalizeQuery(query);
        String text = normalized + " FOUND · " + root.totalMatchingStacks() + " · " + roundDistance(root.distance()) + "m";
        return text;
    }

    /**
     * Renders a detailed, multi-line marker text for a matching root.
     *
     * @param query normalized user query
     * @param root matching root
     * @return detailed marker text
     */
    public String verboseMarker(String query, RootMatch root) {
        String normalized = normalizeQuery(query);
        String header = normalized + " FOUND · " + root.totalMatchingStacks() + " · " + roundDistance(root.distance()) + "m";
        String rootLine = formatBlockType(root.identity().blockType()) + " · score " + formatScore(root.bestScore());

        StringBuilder tree = new StringBuilder();
        tree.append(header).append('\n').append(rootLine);

        for (ItemMatch match : root.itemMatches()) {
            for (String line : formatItemMatchLines(match)) {
                tree.append('\n').append(line);
            }
        }

        return truncateVerboseMarker(tree.toString());
    }

    /**
     * Renders the compact chat summary for a search outcome.
     *
     * @param outcome search outcome
     * @return chat summary text
     */
    public String normalChat(SearchOutcome outcome) {
        int roots = outcome.totalAccessibleMatchingRoots();
        int stacks = outcome.totalAccessibleMatchingStacks();
        String rootWord = roots == 1 ? "block" : "blocks";
        String stackWord = stacks == 1 ? "stack" : "stacks";
        String text = "Found " + roots + " accessible storage " + rootWord + " containing " + stacks + " matching " + stackWord + ".";
        return text;
    }

    /**
     * Renders a detailed per-root chat listing for a search outcome.
     *
     * @param query normalized user query
     * @param outcome search outcome
     * @return detailed chat text
     */
    public String verboseChat(String query, SearchOutcome outcome) {
        StringBuilder chat = new StringBuilder();

        List<RootMatch> roots = outcome.roots();
        for (int i = 0; i < roots.size(); i++) {
            RootMatch root = roots.get(i);
            String rootLine = "[" + (i + 1) + "] " + formatBlockType(root.identity().blockType())
                + " @ " + root.key().x() + ", " + root.key().y() + ", " + root.key().z()
                + " · score " + formatScore(root.bestScore());
            chat.append(rootLine);

            for (ItemMatch match : root.itemMatches()) {
                for (String line : formatItemMatchLines(match)) {
                    chat.append('\n').append(line);
                }
            }

            if (i < roots.size() - 1) {
                chat.append('\n');
            }
        }

        if (outcome.totalAccessibleMatchingRoots() > roots.size()) {
            chat.append('\n');
            chat.append("Showing ").append(roots.size())
                .append(" of ").append(outcome.totalAccessibleMatchingRoots())
                .append(" matching storage blocks");
        }

        return chat.toString();
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String normalized = query.strip().replaceAll("\\s+", " ");
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static int roundDistance(double distance) {
        return (int) Math.round(distance);
    }

    private static String formatScore(double score) {
        return String.format(Locale.ROOT, "%.2f", score);
    }

    private static String formatBlockType(String blockType) {
        return formatName(blockType);
    }

    private static String formatName(String namespaced) {
        if (namespaced == null || namespaced.isBlank()) {
            return "";
        }
        int colon = namespaced.indexOf(':');
        String stripped = colon >= 0 && colon < namespaced.length() - 1
            ? namespaced.substring(colon + 1)
            : namespaced;
        String normalized = stripped.replace('_', ' ');
        StringBuilder title = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isWhitespace(codePoint)) {
                title.appendCodePoint(codePoint);
                capitalize = true;
            } else {
                title.appendCodePoint(
                    capitalize
                        ? Character.toTitleCase(codePoint)
                        : Character.toLowerCase(codePoint)
                );
                capitalize = false;
            }
            offset += Character.charCount(codePoint);
        }
        return title.toString();
    }

    private static String leafName(ItemDescriptor descriptor) {
        List<String> displayText = descriptor.displayText();
        if (displayText != null && !displayText.isEmpty()) {
            for (String text : displayText) {
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return formatName(descriptor.materialKey());
    }

    private static List<String> formatItemMatchLines(ItemMatch match) {
        List<String> lines = new ArrayList<>();
        List<ItemPathStep> steps = match.path().steps();

        for (int i = 0; i < steps.size(); i++) {
            ItemPathStep step = steps.get(i);
            String prefix = "   ".repeat(i) + "└─ ";
            String name;
            String amountPart;
            String scorePart = "";

            if (i < steps.size() - 1) {
                name = formatName(steps.get(i + 1).label());
                amountPart = "x1";
            } else {
                name = leafName(match.descriptor());
                amountPart = "x" + match.amount();
                scorePart = " · score " + formatScore(match.score());
            }

            lines.add(prefix + "slot " + step.slot() + ": " + name + " " + amountPart + scorePart);
        }

        ItemDescriptor descriptor = match.descriptor();
        Map<String, Integer> enchantments = descriptor.enchantments();
        if (enchantments != null && !enchantments.isEmpty()) {
            String indent = "   ".repeat(Math.max(0, steps.size()));
            String enchantPrefix = indent + "└─ ";
            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                String enchantName = formatName(entry.getKey());
                String level = formatRomanNumeral(entry.getValue());
                lines.add(enchantPrefix + enchantName + " " + level);
            }
        }

        return lines;
    }

    private static String formatRomanNumeral(int number) {
        if (number <= 0) {
            return String.valueOf(number);
        }
        if (number >= 4000) {
            return String.valueOf(number);
        }
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};

        return thousands[number / 1000]
            + hundreds[(number % 1000) / 100]
            + tens[(number % 100) / 10]
            + ones[number % 10];
    }

    private static String truncateVerboseMarker(String text) {
        if (text.codePointCount(0, text.length()) <= 1024) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int codePoints = 0;
        boolean first = true;

        for (String line : lines) {
            int lineCp = line.codePointCount(0, line.length());
            int newlineCp = first ? 0 : 1;
            if (codePoints + newlineCp + lineCp + 2 > 1024) {
                break;
            }
            if (!first) {
                result.append('\n');
            }
            result.append(line);
            codePoints += newlineCp + lineCp;
            first = false;
        }

        if (result.length() == 0) {
            return "…";
        }

        return result.append("\n…").toString();
    }
}
