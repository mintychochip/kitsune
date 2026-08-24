package org.aincraft.kitsune.storage.vector;

/**
 * Result of a vector similarity search operation.
 * Represents a vector found by the similarity search with its ordinal ID and similarity score.
 */
public record VectorSearchResult(int ordinal, double score) {}
