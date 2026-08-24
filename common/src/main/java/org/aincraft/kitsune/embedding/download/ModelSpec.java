package org.aincraft.kitsune.embedding.download;

/**
 * Specification for an ONNX embedding model including its Hugging Face location
 * and processing configuration.
 */
public record ModelSpec(
    String modelName,           // e.g., "nomic-embed-text-v1.5"
    String huggingFaceRepo,     // e.g., "nomic-ai/nomic-embed-text-v1.5"
    String modelPath,           // e.g., "onnx/model.onnx"
    String tokenizerPath,       // e.g., "tokenizer.json"
    int dimension,              // e.g., 768
    int vocabSize,              // for token sanitization (0 = disabled)
    TaskPrefixStrategy taskPrefixStrategy,
    boolean requiresExternalData    // for model.onnx_data files (large models)
) {
    /**
     * Strategy for adding task prefixes to text before embedding.
     * Different models require different prefix formats for optimal results.
     */
    public enum TaskPrefixStrategy {
        /** No prefix - use text as-is (AllMiniLM, BGE) */
        NONE,
        /** Nomic format: "search_query: " or "search_document: " */
        NOMIC,
        /** E5 instruct format: "Instruct: Given a web search query...\nQuery: " */
        E5_INSTRUCT
    }

    /**
     * Creates a ModelSpec for a model without task prefixes.
     */
    public static ModelSpec simple(String name, String repo, String modelPath,
                                    String tokenizerPath, int dimension) {
        return new ModelSpec(name, repo, modelPath, tokenizerPath, dimension,
                             0, TaskPrefixStrategy.NONE, false);
    }

    /**
     * Applies the task prefix strategy to text based on task type.
     * @param text the input text
     * @param taskType the task type (e.g., "RETRIEVAL_QUERY", "RETRIEVAL_DOCUMENT")
     * @return the prefixed text ready for tokenization
     */
    public String applyTaskPrefix(String text, String taskType) {
        return switch (taskPrefixStrategy) {
            case NOMIC -> switch (taskType) {
                case "RETRIEVAL_QUERY" -> "search_query: " + text;
                case "CLUSTERING" -> "clustering: " + text;
                case "CLASSIFICATION" -> "classification: " + text;
                default -> "search_document: " + text;
            };
            case E5_INSTRUCT -> switch (taskType) {
                case "RETRIEVAL_QUERY" ->
                    "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: " + text;
                default -> text; // Documents don't get prefix in E5
            };
            case NONE -> text;
        };
    }

    /**
     * Returns the expected model filename (without extension handling).
     */
    public String getModelFileName() {
        return modelName + ".onnx";
    }
}