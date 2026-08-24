package dev.jlo.kitsune.embedding.local;

import java.util.Objects;

/**
 * Specification for a local ONNX embedding model, including Hugging Face location
 * and text-processing configuration.
 *
 * @param modelName model identifier used for catalog lookup and local filenames
 * @param dimension output embedding dimension count
 * @param vocabSize vocabulary size used for token-id sanitization, or {@code 0} to disable
 * @param requiresExternalData whether an external {@code model.onnx_data} file is required
 * @param taskPrefixStrategy strategy for adding retrieval task prefixes before tokenization
 * @param huggingFaceRepo Hugging Face repository id
 * @param modelFileName local ONNX model filename
 */
public record OnnxModelSpec(
    String modelName,
    int dimension,
    int vocabSize,
    boolean requiresExternalData,
    TaskPrefixStrategy taskPrefixStrategy,
    String huggingFaceRepo,
    String modelFileName
) {
    /** Strategy for adding task prefixes to text before embedding. */
    public enum TaskPrefixStrategy {
        /** No prefix; use text as-is. */
        NONE,
        /** Nomic format: {@code search_query:} or {@code search_document:}. */
        NOMIC,
        /** E5 instruct format for retrieval queries. */
        E5_INSTRUCT
    }

    private static final String MODEL_REPO_PATH = "onnx/model.onnx";
    private static final String TOKENIZER_REPO_PATH = "tokenizer.json";
    private static final String EXTERNAL_DATA_REPO_PATH = "onnx/model.onnx_data";
    private static final String TOKENIZER_FILE_NAME = "tokenizer.json";
    private static final String EXTERNAL_DATA_FILE_NAME = "model.onnx_data";

    public OnnxModelSpec {
        modelName = required(modelName, "Model name");
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        if (vocabSize < 0) {
            throw new IllegalArgumentException("Vocabulary size must not be negative");
        }
        Objects.requireNonNull(taskPrefixStrategy, "Task prefix strategy must not be null");
        huggingFaceRepo = required(huggingFaceRepo, "Hugging Face repository");
        modelFileName = required(modelFileName, "Model file name");
    }

    /**
     * Creates a simple model specification without task prefixes.
     */
    public static OnnxModelSpec simple(
        String modelName,
        String huggingFaceRepo,
        int dimension,
        int vocabSize
    ) {
        return new OnnxModelSpec(
            modelName,
            dimension,
            vocabSize,
            false,
            TaskPrefixStrategy.NONE,
            huggingFaceRepo,
            modelName + ".onnx"
        );
    }

    /** Returns the repository-relative ONNX model path. */
    public String modelRepoPath() {
        return MODEL_REPO_PATH;
    }

    /** Returns the repository-relative tokenizer path. */
    public String tokenizerRepoPath() {
        return TOKENIZER_REPO_PATH;
    }

    /** Returns the repository-relative external data path. */
    public String externalDataRepoPath() {
        return EXTERNAL_DATA_REPO_PATH;
    }

    /** Returns the local tokenizer filename. */
    public String tokenizerFileName() {
        return TOKENIZER_FILE_NAME;
    }

    /** Returns the local external data filename. */
    public String externalDataFileName() {
        return EXTERNAL_DATA_FILE_NAME;
    }

    /**
     * Applies the configured task-prefix strategy for the given retrieval task.
     *
     * @param text input text
     * @param taskType retrieval task type, such as {@code RETRIEVAL_QUERY}
     * @return prefixed text ready for tokenization
     */
    public String applyTaskPrefix(String text, String taskType) {
        Objects.requireNonNull(text, "Text must not be null");
        Objects.requireNonNull(taskType, "Task type must not be null");
        return switch (taskPrefixStrategy) {
            case NOMIC -> switch (taskType) {
                case "RETRIEVAL_QUERY" -> "search_query: " + text;
                case "CLUSTERING" -> "clustering: " + text;
                case "CLASSIFICATION" -> "classification: " + text;
                default -> "search_document: " + text;
            };
            case E5_INSTRUCT -> switch (taskType) {
                case "RETRIEVAL_QUERY" ->
                    "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
                        + text;
                default -> text;
            };
            case NONE -> text;
        };
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
