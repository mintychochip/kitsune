package dev.jlo.kitsune.embedding;

import java.util.*;

/**
 * Tokenization and alias-expansion utilities for sparse embeddings.
 *
 * <p>Text is normalized to lowercase, split on non-alphanumeric characters, and expanded via
 * a fixed set of category aliases (for example {@code diamond} expands to
 * {@code gem, gemstone, precious}). The alias map is fixed at class initialization.
 */
public final class FeatureVocabulary {
    private static final Map<String, Set<String>> ALIASES;
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile("[^a-z0-9]+");

    static {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("diamond", Set.of("gem", "gemstone", "precious"));
        map.put("sword", Set.of("weapon", "melee"));
        map.put("axe", Set.of("tool", "weapon", "chopping"));
        map.put("pickaxe", Set.of("tool", "mining"));
        map.put("shovel", Set.of("tool", "digging"));
        map.put("hoe", Set.of("tool", "farming"));
        map.put("helmet", Set.of("armor", "head"));
        map.put("chestplate", Set.of("armor", "chest"));
        map.put("leggings", Set.of("armor", "legs"));
        map.put("boots", Set.of("armor", "feet"));
        map.put("food", Set.of("edible"));
        map.put(
            "mending",
            Set.of("durability", "repair", "restoration")
        );
        map.put("log", Set.of("logs"));
        map.put("logs", Set.of("log"));
        ALIASES = Collections.unmodifiableMap(map);
    }

    private FeatureVocabulary() {}

    /**
     * Returns the alias expansion set for a token, or an empty immutable set if unknown.
     *
     * @param token the vocabulary token to look up
     * @return immutable set of aliases for the token, possibly empty
     */
    public static Set<String> aliasesFor(String token) {
        return ALIASES.getOrDefault(token, Set.of());
    }

    /**
     * Splits text into normalized tokens.
     *
     * <p>Input is lowercased, runs of non-alphanumeric characters become single spaces, and
     * the result is split on whitespace. Null or blank input yields an empty list.
     *
     * @param text text to tokenize
     * @return unmodifiable list of normalized tokens, possibly empty
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = SPLIT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (normalized.isEmpty()) return List.of();
        return Collections.unmodifiableList(Arrays.asList(normalized.split(" ")));
    }
}
