package dev.jlo.kitsune.api.embedding;

public interface EmbeddingProvider {
    String id();
    int version();
    Embedding embed(dev.jlo.kitsune.model.ItemDescriptor descriptor);
    Embedding embedQuery(String query);
    Embedding decode(byte[] payload, double norm);
}
