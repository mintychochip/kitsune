package dev.jlo.kitsune.embedding.remote;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.util.Objects;

public final class EmbeddingTextSerializer {
    private EmbeddingTextSerializer() {}

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
