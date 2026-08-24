package org.aincraft.kitsune.embedding;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface EmbeddingService {
    /**
     * Embeds text into a vector representation asynchronously.
     * @param text The text to embed
     * @return CompletableFuture containing the embedding vector
     */
    CompletableFuture<float[]> embed(String text);

    /**
     * Embeds text into a vector representation with specified task type.
     * For services that support task type specification (e.g., Google Gemini).
     * @param text The text to embed
     * @param taskType The task type for embedding (e.g., "RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY")
     * @return CompletableFuture containing the embedding vector
     */
    CompletableFuture<float[]> embed(String text, String taskType);

    /**
     * Embeds multiple texts in a single batch for better performance.
     * ONNX models support batch inference which is significantly faster than individual calls.
     *
     * @param texts List of texts to embed (order preserved in results)
     * @param taskType The task type for embedding (e.g., "RETRIEVAL_DOCUMENT")
     * @return CompletableFuture containing list of embedding vectors (same order as input)
     */
    CompletableFuture<List<float[]>> embedBatch(List<String> texts, String taskType);

    /**
     * Embeds multiple texts in a single batch using default task type.
     *
     * @param texts List of texts to embed (order preserved in results)
     * @return CompletableFuture containing list of embedding vectors (same order as input)
     */
    default CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
        return embedBatch(texts, "RETRIEVAL_DOCUMENT");
    }

    /**
     * Initializes the embedding service.
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Shuts down the embedding service and releases resources.
     */
    void shutdown();

    /**
     * Get the embedding dimension for this service.
     * @return The dimension of the embedding vectors produced by this service
     */
    int getDimension();
}
