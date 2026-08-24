package dev.jlo.kitsune.api.embedding;

/**
 * A numeric vector embedding produced by an {@link EmbeddingProvider}.
 */
public interface Embedding {
    /**
     * @return the id of the provider that produced this embedding
     */
    String providerId();

    /**
     * @return the version of the producing provider's wire format
     */
    int providerVersion();

    /**
     * @return the L2 norm of the embedded vector
     */
    double norm();

    /**
     * @return the encoded payload of this embedding
     */
    byte[] encode();

    /**
     * Computes the cosine similarity to another embedding.
     *
     * @param other embedding to compare against
     * @return the cosine similarity in {@code [-1, 1]}
     */
    double cosine(Embedding other);
}
