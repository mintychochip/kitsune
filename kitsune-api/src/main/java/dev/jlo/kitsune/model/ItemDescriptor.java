package dev.jlo.kitsune.model;

import java.util.*;

/**
 * An immutable, neutral description of an item used for indexing and
 * search.
 *
 * <p>Collection values are normalized, deduplicated, sorted, and exposed as
 * unmodifiable views. Bounded construction truncates values to configured
 * entry and code-point limits.
 *
 * @see Builder
 */
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
    private final ItemHoverPayload hoverPayload;

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
        this.hoverPayload = builder.hoverPayload == null
            ? ItemHoverPayload.base(this.materialKey, this.amount)
            : builder.hoverPayload;
        if (!this.materialKey.equals(this.hoverPayload.itemKey())
            || this.amount != this.hoverPayload.count()) {
            throw new IllegalArgumentException(
                "Hover payload item key and count must match the descriptor"
            );
        }
    }

    /** @return the material identifier of the item */
    public String materialKey() { return materialKey; }
    /** @return the stack amount of the item */
    public int amount() { return amount; }
    /** @return display text lines, sorted and unmodifiable */
    public List<String> displayText() { return displayText; }
    /** @return lore lines, sorted and unmodifiable */
    public List<String> lore() { return lore; }
    /** @return enchantment keys mapped to levels, unmodifiable */
    public Map<String, Integer> enchantments() { return enchantments; }
    /** @return attribute keys mapped to values, unmodifiable */
    public Map<String, Double> attributes() { return attributes; }
    /** @return trait tags, sorted and unmodifiable */
    public Set<String> traits() { return traits; }
    /** @return scalar metadata keys mapped to values, unmodifiable */
    public Map<String, String> scalarMetadata() { return scalarMetadata; }
    /** @return custom tags, sorted and unmodifiable */
    public Set<String> customTags() { return customTags; }
    /** @return the complete persisted item hover payload */
    public ItemHoverPayload hoverPayload() { return hoverPayload; }

    /**
     * @return a new builder for an {@link ItemDescriptor}
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder containing every field of this descriptor. */
    public Builder toBuilder() {
        Builder copy = builder()
            .materialKey(materialKey)
            .amount(amount)
            .hoverPayload(hoverPayload);
        displayText.forEach(copy::addDisplayText);
        lore.forEach(copy::addLore);
        enchantments.forEach(copy::addEnchantment);
        attributes.forEach(copy::addAttribute);
        traits.forEach(copy::addTrait);
        scalarMetadata.forEach(copy::addScalarMetadata);
        customTags.forEach(copy::addCustomTag);
        return copy;
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
            && Objects.equals(customTags, that.customTags)
            && Objects.equals(hoverPayload, that.hoverPayload);
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
            customTags,
            hoverPayload
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

    /**
     * Builds {@link ItemDescriptor} instances.
     *
     * <p>Required fields are the material key and a positive amount; these are
     * validated at the end of every build, and per-method where applicable.
     */
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
        private ItemHoverPayload hoverPayload;

        /**
         * Sets the material identifier.
         *
         * @param materialKey material identifier
         * @return this builder
         * @throws IllegalArgumentException if the key is null or blank
         */
        public Builder materialKey(String materialKey) {
            if (materialKey == null || materialKey.isBlank()) {
                throw new IllegalArgumentException("Material key must not be blank");
            }
            this.materialKey = materialKey;
            return this;
        }

        /**
         * Sets the stack amount.
         *
         * @param amount stack amount
         * @return this builder
         * @throws IllegalArgumentException if the amount is not positive
         */
        public Builder amount(int amount) {
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            this.amount = amount;
            return this;
        }

        /**
         * Sets the complete persisted item hover payload.
         *
         * @param hoverPayload payload captured from the source item
         * @return this builder
         */
        public Builder hoverPayload(ItemHoverPayload hoverPayload) {
            this.hoverPayload = Objects.requireNonNull(
                hoverPayload,
                "Hover payload must not be null"
            );
            return this;
        }

        /**
         * Adds a display text line.
         *
         * @param text line to add
         * @return this builder
         */
        public Builder addDisplayText(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Display text must not be blank");
            }
            this.displayText.add(text);
            return this;
        }

        /**
         * Adds a lore line.
         *
         * @param text line to add
         * @return this builder
         */
        public Builder addLore(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Lore must not be blank");
            }
            this.lore.add(text);
            return this;
        }

        /**
         * Adds an enchantment with the given level.
         *
         * @param key   enchantment key
         * @param level enchantment level
         * @return this builder
         */
        public Builder addEnchantment(String key, int level) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Enchantment key must not be blank");
            }
            if (level <= 0) throw new IllegalArgumentException("Enchantment level must be positive");
            this.enchantments.put(key, level);
            return this;
        }

        /**
         * Adds an attribute with the given value.
         *
         * @param key   attribute key
         * @param value attribute value
         * @return this builder
         */
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

        /**
         * Adds a trait tag.
         *
         * @param trait trait to add
         * @return this builder
         */
        public Builder addTrait(String trait) {
            if (trait == null || trait.isBlank()) {
                throw new IllegalArgumentException("Trait must not be blank");
            }
            this.traits.add(trait);
            return this;
        }

        /**
         * Adds a scalar metadata entry.
         *
         * @param key   metadata key
         * @param value metadata value
         * @return this builder
         */
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

        /**
         * Adds a custom tag.
         *
         * @param tag tag to add
         * @return this builder
         */
        public Builder addCustomTag(String tag) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("Custom tag must not be blank");
            }
            this.customTags.add(tag);
            return this;
        }

        /**
         * Builds an unbounded descriptor.
         *
         * @return the built descriptor
         * @throws IllegalArgumentException if required fields are missing
         */
        public ItemDescriptor build() {
            validateRequiredFields();
            return new ItemDescriptor(this, null);
        }

        /**
         * Builds a descriptor bounded to the given entry and code-point
         * limits.
         *
         * @param maximumEntries     maximum collection entries retained
         * @param maximumCodePoints  maximum code points per text value
         * @return the built descriptor
         * @throws IllegalArgumentException if required fields are missing or a
         *         bound is not positive
         */
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
