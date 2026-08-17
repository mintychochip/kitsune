package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.util.Objects;

/**
 * Builds plain-text representations of item descriptors and queries for
 * embedding.
 */
public final class EmbeddingTextSerializer {
    private EmbeddingTextSerializer() {}

    /**
     * Serializes the given descriptor into labeled text lines.
     *
     * <p>Empty and null values are omitted. Labels cover material, display
     * text, lore, enchantments, attributes, traits, scalar metadata, and
     * custom tags.
     *
     * @param descriptor descriptor to serialize
     * @return the serialized text
     * @throws NullPointerException if the descriptor is null
     */
    public static String document(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        StringBuilder text = new StringBuilder();
        append(text, "material", descriptor.materialKey());
        descriptor.displayText().forEach(value -> append(text, "display", value));
        descriptor.lore().forEach(value -> append(text, "lore", value));
        descriptor.enchantments().forEach((key, level) -> append(text, "enchantment", key));
        descriptor.attributes().forEach((key, value) -> append(text, "attribute", key + "=" + value));
        descriptor.traits().forEach(value -> append(text, "trait", value));
        descriptor.scalarMetadata().forEach((key, value) -> append(text, "metadata", key + "=" + value));
        descriptor.customTags().forEach(value -> append(text, "tag", value));
        return text.toString();
    }

    /**
     * Normalizes a raw query for embedding.
     *
     * @param query query to normalize
     * @return the normalized query text
     * @throws NullPointerException if the query is null
     */
    public static String query(String query) {
        Objects.requireNonNull(query, "Query must not be null");
        return normalize(query);
    }

    private static void append(StringBuilder target, String label, String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return;
        target.append(label).append(": ").append(normalized).append('\n');
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
