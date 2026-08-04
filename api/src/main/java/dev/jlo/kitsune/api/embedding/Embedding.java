package dev.jlo.kitsune.api.embedding;

public interface Embedding {
    String providerId();
    int providerVersion();
    double norm();
    byte[] encode();
    double cosine(Embedding other);
}
