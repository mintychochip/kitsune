package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.util.*;

/**
 * Built-in provider producing sparse embeddings from {@link ItemDescriptor} features.
 *
 * <p>Each descriptor category contributes weighted tokens (material, display text, tags,
 * enchantments, attributes, traits, scalar metadata, and lore). Query embedding runs the
 * query through {@link FeatureVocabulary} tokenization and alias expansion. This provider
 * requires no settings or credentials.
 */
public class SparseTagEmbeddingProvider implements EmbeddingProvider {
    /** Stable identifier used to select and serialize this provider. */
    public static final String ID = "builtin:sparse-v1";
    /** Provider schema/behavior version. */
    public static final int VERSION = 2;

    @Override
    public String id() { return ID; }

    @Override
    public int version() { return VERSION; }

    /**
     * Embeds an item descriptor into a sparse feature map keyed by tokenized categories.
     *
     * @param descriptor the item descriptor to embed
     * @return the resulting sparse embedding
     */
    @Override
    public Embedding embed(ItemDescriptor descriptor) {
        Map<String, Double> values = new LinkedHashMap<>();
        addCategory(values, descriptor.materialKey(), 8.0);
        descriptor.displayText().forEach(text -> addCategory(values, text, 8.0));
        descriptor.customTags().forEach(tag -> addCategory(values, tag, 6.0));
        descriptor.enchantments().keySet().forEach(key -> addCategory(values, key, 5.0));
        descriptor.attributes().keySet().forEach(key -> addCategory(values, key, 5.0));
        descriptor.traits().forEach(trait -> addCategory(values, trait, 4.0));
        descriptor.scalarMetadata().forEach((k, v) -> {
            addCategory(values, k, 3.0);
            addCategory(values, v, 3.0);
        });
        descriptor.lore().forEach(line -> addCategory(values, line, 2.0));
        return SparseEmbedding.of(ID, VERSION, values);
    }

    /**
     * Embeds a natural-language query by tokenizing it and expanding aliases.
     *
     * @param query the query text; blank input yields an empty embedding
     * @return the resulting sparse query embedding
     */
    @Override
    public Embedding embedQuery(String query) {
        if (query == null || query.isBlank()) {
            return SparseEmbedding.of(ID, VERSION, Map.of());
        }
        Map<String, Double> values = new LinkedHashMap<>();
        List<String> tokens = FeatureVocabulary.tokenize(query);
        Set<String> seen = new HashSet<>();
        for (String token : tokens) {
            if (seen.add(token)) {
                merge(values, "token:" + token, 1.0);
            }
            for (String alias : FeatureVocabulary.aliasesFor(token)) {
                if (seen.add(alias)) {
                    merge(values, "token:" + alias, 1.0);
                }
            }
        }
        return SparseEmbedding.of(ID, VERSION, values);
    }

    /** Decodes a {@link SparseEmbedding} payload for this provider. */
    @Override
    public Embedding decode(byte[] payload, double norm) {
        return SparseEmbedding.decode(ID, VERSION, payload, norm);
    }

    private static void addCategory(Map<String, Double> values, String text, double weight) {
        if (text == null || text.isBlank()) return;
        List<String> tokens = FeatureVocabulary.tokenize(text);
        Set<String> seen = new HashSet<>();
        for (String token : tokens) {
            if (seen.add(token)) {
                merge(values, "token:" + token, weight);
            }
            for (String alias : FeatureVocabulary.aliasesFor(token)) {
                if (seen.add(alias)) {
                    merge(values, "token:" + alias, weight);
                }
            }
        }
    }

    private static void merge(Map<String, Double> values, String key, double weight) {
        values.merge(key, weight, Double::sum);
    }
}
