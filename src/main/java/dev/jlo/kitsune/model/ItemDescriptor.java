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

    private ItemDescriptor(Builder builder) {
        this.materialKey = builder.materialKey;
        this.amount = builder.amount;
        this.displayText = List.copyOf(sort(builder.displayText));
        this.lore = List.copyOf(sort(builder.lore));
        this.enchantments = unmodifiableSortedMap(sort(builder.enchantments));
        this.attributes = unmodifiableSortedMap(sort(builder.attributes));
        this.traits = unmodifiableSortedSet(sort(builder.traits));
        this.scalarMetadata = unmodifiableSortedMap(sort(builder.scalarMetadata));
        this.customTags = unmodifiableSortedSet(sort(builder.customTags));
    }

    private static <T extends Comparable<? super T>> List<T> sort(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> sort(Map<K, V> map) {
        Map<K, V> copy = new LinkedHashMap<>();
        List<K> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.naturalOrder());
        for (K k : keys) {
            copy.put(k, map.get(k));
        }
        return copy;
    }

    private static <T extends Comparable<? super T>> Set<T> sort(Set<T> set) {
        Set<T> copy = new LinkedHashSet<>();
        List<T> list = new ArrayList<>(set);
        list.sort(Comparator.naturalOrder());
        for (T t : list) {
            copy.add(t);
        }
        return copy;
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> unmodifiableSortedMap(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static <T extends Comparable<? super T>> Set<T> unmodifiableSortedSet(Set<T> set) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
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
        return amount == that.amount &&
                Objects.equals(materialKey, that.materialKey) &&
                Objects.equals(displayText, that.displayText) &&
                Objects.equals(lore, that.lore) &&
                Objects.equals(enchantments, that.enchantments) &&
                Objects.equals(attributes, that.attributes) &&
                Objects.equals(traits, that.traits) &&
                Objects.equals(scalarMetadata, that.scalarMetadata) &&
                Objects.equals(customTags, that.customTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(materialKey, amount, displayText, lore, enchantments, attributes, traits, scalarMetadata, customTags);
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
            if (materialKey == null || materialKey.isBlank()) throw new IllegalArgumentException("Material key must not be blank");
            this.materialKey = materialKey;
            return this;
        }

        public Builder amount(int amount) {
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            this.amount = amount;
            return this;
        }

        public Builder addDisplayText(String text) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Display text must not be blank");
            this.displayText.add(text);
            return this;
        }

        public Builder addLore(String text) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Lore must not be blank");
            this.lore.add(text);
            return this;
        }

        public Builder addEnchantment(String key, int level) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Enchantment key must not be blank");
            if (level <= 0) throw new IllegalArgumentException("Enchantment level must be positive");
            this.enchantments.put(key, level);
            return this;
        }

        public Builder addAttribute(String key, double value) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Attribute key must not be blank");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute value must be finite");
            this.attributes.put(key, value);
            return this;
        }

        public Builder addTrait(String trait) {
            if (trait == null || trait.isBlank()) throw new IllegalArgumentException("Trait must not be blank");
            this.traits.add(trait);
            return this;
        }

        public Builder addScalarMetadata(String key, String value) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Metadata key must not be blank");
            Objects.requireNonNull(value, "Metadata value must not be null");
            this.scalarMetadata.put(key, value);
            return this;
        }

        public Builder addCustomTag(String tag) {
            if (tag == null || tag.isBlank()) throw new IllegalArgumentException("Custom tag must not be blank");
            this.customTags.add(tag);
            return this;
        }

        public ItemDescriptor build() {
            if (materialKey == null || materialKey.isBlank()) throw new IllegalArgumentException("Material key must not be blank");
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            return new ItemDescriptor(this);
        }
    }
}
