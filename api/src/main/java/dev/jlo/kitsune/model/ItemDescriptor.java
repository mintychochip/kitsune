package dev.jlo.kitsune.model;

import java.util.*;

public final class ItemDescriptor {
    private final String materialKey;
    private final int amount;
    private final List<String> displayText;
    private final List<String> lore;
    private final Map<String, Integer> enchantments;
    private final Map<String, Double> attributes;
    private final Set<String> traits;
    private final Map<String, String> scalarMetadata;
    private final Set<String> customTags;

    private ItemDescriptor(Builder builder, Bounds bounds) {
        this.materialKey = boundRequired(builder.materialKey, bounds, "Material key");
        this.amount = builder.amount;
        this.displayText = boundedList(builder.displayText, bounds);
        this.lore = boundedList(builder.lore, bounds);
        this.enchantments = boundedMap(builder.enchantments, bounds);
        this.attributes = boundedMap(builder.attributes, bounds);
        this.traits = boundedSet(builder.traits, bounds);
        this.scalarMetadata = boundedMetadata(builder.scalarMetadata, bounds);
        this.customTags = boundedSet(builder.customTags, bounds);
    }

    public String materialKey() { return materialKey; }
    public int amount() { return amount; }
    public List<String> displayText() { return displayText; }
    public List<String> lore() { return lore; }
    public Map<String, Integer> enchantments() { return enchantments; }
    public Map<String, Double> attributes() { return attributes; }
    public Set<String> traits() { return traits; }
    public Map<String, String> scalarMetadata() { return scalarMetadata; }
    public Set<String> customTags() { return customTags; }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemDescriptor that)) return false;
        return amount == that.amount
            && Objects.equals(materialKey, that.materialKey)
            && Objects.equals(displayText, that.displayText)
            && Objects.equals(lore, that.lore)
            && Objects.equals(enchantments, that.enchantments)
            && Objects.equals(attributes, that.attributes)
            && Objects.equals(traits, that.traits)
            && Objects.equals(scalarMetadata, that.scalarMetadata)
            && Objects.equals(customTags, that.customTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            materialKey,
            amount,
            displayText,
            lore,
            enchantments,
            attributes,
            traits,
            scalarMetadata,
            customTags
        );
    }

    private static String bound(String value, Bounds bounds) {
        if (bounds == null) return value;
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= bounds.maximumCodePoints()) return value;
        int end = value.offsetByCodePoints(0, bounds.maximumCodePoints());
        return value.substring(0, end);
    }

    private static String boundRequired(String value, Bounds bounds, String field) {
        String bounded = bound(value, bounds);
        if (bounded == null || bounded.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return bounded;
    }

    private static List<String> boundedList(Collection<String> values, Bounds bounds) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            String bounded = bound(value, bounds);
            if (bounds == null || !bounded.isBlank()) result.add(bounded);
        }
        result.sort(Comparator.naturalOrder());
        trim(result, bounds);
        return List.copyOf(result);
    }

    private static Set<String> boundedSet(Collection<String> values, Bounds bounds) {
        SortedSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String bounded = bound(value, bounds);
            if (bounds == null || !bounded.isBlank()) sorted.add(bounded);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int limit = entryLimit(sorted.size(), bounds);
        for (String value : sorted) {
            if (result.size() == limit) break;
            result.add(value);
        }
        return Collections.unmodifiableSet(result);
    }

    private static <V> Map<String, V> boundedMap(Map<String, V> values, Bounds bounds) {
        SortedMap<String, V> normalized = new TreeMap<>();
        for (Map.Entry<String, V> entry : values.entrySet()) {
            String key = bound(entry.getKey(), bounds);
            if (bounds != null && key.isBlank()) continue;
            normalized.putIfAbsent(key, entry.getValue());
        }
        return firstEntries(normalized, bounds);
    }

    private static Map<String, String> boundedMetadata(
        Map<String, String> values,
        Bounds bounds
    ) {
        SortedMap<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = bound(entry.getKey(), bounds);
            String value = bound(entry.getValue(), bounds);
            if (bounds != null && (key.isBlank() || value.isBlank())) continue;
            normalized.putIfAbsent(key, value);
        }
        return firstEntries(normalized, bounds);
    }

    private static <V> Map<String, V> firstEntries(
        SortedMap<String, V> values,
        Bounds bounds
    ) {
        LinkedHashMap<String, V> result = new LinkedHashMap<>();
        int limit = entryLimit(values.size(), bounds);
        for (Map.Entry<String, V> entry : values.entrySet()) {
            if (result.size() == limit) break;
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static void trim(List<?> values, Bounds bounds) {
        int limit = entryLimit(values.size(), bounds);
        if (limit < values.size()) {
            values.subList(limit, values.size()).clear();
        }
    }

    private static int entryLimit(int size, Bounds bounds) {
        return bounds == null ? size : Math.min(size, bounds.maximumEntries());
    }

    private record Bounds(int maximumEntries, int maximumCodePoints) {
        private Bounds {
            if (maximumEntries <= 0) {
                throw new IllegalArgumentException("Maximum entries must be positive");
            }
            if (maximumCodePoints <= 0) {
                throw new IllegalArgumentException("Maximum code points must be positive");
            }
        }
    }

    public static final class Builder {
        private String materialKey;
        private int amount;
        private final List<String> displayText = new ArrayList<>();
        private final List<String> lore = new ArrayList<>();
        private final Map<String, Integer> enchantments = new LinkedHashMap<>();
        private final Map<String, Double> attributes = new LinkedHashMap<>();
        private final Set<String> traits = new LinkedHashSet<>();
        private final Map<String, String> scalarMetadata = new LinkedHashMap<>();
        private final Set<String> customTags = new LinkedHashSet<>();

        public Builder materialKey(String materialKey) {
            if (materialKey == null || materialKey.isBlank()) {
                throw new IllegalArgumentException("Material key must not be blank");
            }
            this.materialKey = materialKey;
            return this;
        }

        public Builder amount(int amount) {
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            this.amount = amount;
            return this;
        }

        public Builder addDisplayText(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Display text must not be blank");
            }
            this.displayText.add(text);
            return this;
        }

        public Builder addLore(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Lore must not be blank");
            }
            this.lore.add(text);
            return this;
        }

        public Builder addEnchantment(String key, int level) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Enchantment key must not be blank");
            }
            if (level <= 0) throw new IllegalArgumentException("Enchantment level must be positive");
            this.enchantments.put(key, level);
            return this;
        }

        public Builder addAttribute(String key, double value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Attribute key must not be blank");
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Attribute value must be finite");
            }
            this.attributes.put(key, value);
            return this;
        }

        public Builder addTrait(String trait) {
            if (trait == null || trait.isBlank()) {
                throw new IllegalArgumentException("Trait must not be blank");
            }
            this.traits.add(trait);
            return this;
        }

        public Builder addScalarMetadata(String key, String value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
            this.scalarMetadata.put(
                key,
                Objects.requireNonNull(value, "Metadata value must not be null")
            );
            return this;
        }

        public Builder addCustomTag(String tag) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("Custom tag must not be blank");
            }
            this.customTags.add(tag);
            return this;
        }

        public ItemDescriptor build() {
            validateRequiredFields();
            return new ItemDescriptor(this, null);
        }

        public ItemDescriptor buildBounded(int maximumEntries, int maximumCodePoints) {
            validateRequiredFields();
            return new ItemDescriptor(
                this,
                new Bounds(maximumEntries, maximumCodePoints)
            );
        }

        /**
         * Appends contribution fields without replacing existing keyed values.
         * The contribution's material and amount are intentionally ignored.
         */
        public Builder appendMissing(ItemDescriptor contribution) {
            Objects.requireNonNull(contribution, "Contribution must not be null");
            displayText.addAll(contribution.displayText);
            lore.addAll(contribution.lore);
            contribution.enchantments.forEach(enchantments::putIfAbsent);
            contribution.attributes.forEach(attributes::putIfAbsent);
            traits.addAll(contribution.traits);
            contribution.scalarMetadata.forEach(scalarMetadata::putIfAbsent);
            customTags.addAll(contribution.customTags);
            return this;
        }

        private void validateRequiredFields() {
            if (materialKey == null || materialKey.isBlank()) {
                throw new IllegalArgumentException("Material key must not be blank");
            }
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
