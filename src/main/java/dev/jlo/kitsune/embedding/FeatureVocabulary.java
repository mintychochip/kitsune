package dev.jlo.kitsune.embedding;

import java.util.*;

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
        ALIASES = Collections.unmodifiableMap(map);
    }

    private FeatureVocabulary() {}

    public static Set<String> aliasesFor(String token) {
        return ALIASES.getOrDefault(token, Set.of());
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = SPLIT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (normalized.isEmpty()) return List.of();
        return Collections.unmodifiableList(Arrays.asList(normalized.split(" ")));
    }
}
