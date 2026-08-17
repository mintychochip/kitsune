package dev.jlo.kitsune.api.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Produces {@link Embedding} vectors for item descriptors and queries.
 */
public interface EmbeddingProvider {
    /**
     * @return the stable id identifying this provider
     */
    String id();

    /**
     * @return the version of the wire format produced by this provider
     */
    int version();

    /**
     * Embeds an item descriptor.
     *
     * @param descriptor descriptor to embed
     * @return the resulting embedding
     */
    Embedding embed(dev.jlo.kitsune.model.ItemDescriptor descriptor);

    /**
     * Embeds many item descriptors, preserving input order.
     *
     * @param descriptors descriptors to embed
     * @return embeddings aligned with {@code descriptors}
     */
    default List<Embedding> embedAll(List<dev.jlo.kitsune.model.ItemDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "Descriptors must not be null");
        List<Embedding> embeddings = new ArrayList<>(descriptors.size());
        for (dev.jlo.kitsune.model.ItemDescriptor descriptor : descriptors) {
            embeddings.add(embed(descriptor));
        }
        return List.copyOf(embeddings);
    }

    /**
     * Embeds a textual search query.
     *
     * @param query query to embed
     * @return the resulting embedding
     */
    Embedding embedQuery(String query);

    /**
     * Decodes an embedding previously produced by this provider.
     *
     * @param payload encoded embedding payload
     * @param norm    recorded vector norm
     * @return the decoded embedding
     */
    Embedding decode(byte[] payload, double norm);
}
