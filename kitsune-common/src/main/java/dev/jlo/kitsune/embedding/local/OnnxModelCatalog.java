package dev.jlo.kitsune.embedding.local;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Curated catalog of supported local ONNX embedding models.
 */
public final class OnnxModelCatalog {
    private static final String DEFAULT_MODEL_KEY = "nomic-embed-text-v1.5";

    private final Map<String, OnnxModelSpec> models;

    private OnnxModelCatalog(Map<String, OnnxModelSpec> models) {
        this.models = Map.copyOf(models);
    }

    /** Returns the default catalog containing the built-in curated models. */
    public static OnnxModelCatalog defaults() {
        Map<String, OnnxModelSpec> models = new LinkedHashMap<>();
        register(
            models,
            "nomic-embed-text-v1.5",
            "nomic-ai/nomic-embed-text-v1.5",
            768,
            30_528,
            OnnxModelSpec.TaskPrefixStrategy.NOMIC,
            false
        );
        register(
            models,
            "all-minilm-l6-v2",
            "sentence-transformers/all-MiniLM-L6-v2",
            384,
            30_522,
            OnnxModelSpec.TaskPrefixStrategy.NONE,
            false
        );
        register(
            models,
            "bge-m3",
            "BAAI/bge-m3",
            1024,
            250_002,
            OnnxModelSpec.TaskPrefixStrategy.NONE,
            false
        );
        return new OnnxModelCatalog(models);
    }

    /** Returns the default catalog model key. */
    public static String defaultModelKey() {
        return DEFAULT_MODEL_KEY;
    }

    /**
     * Resolves a model specification from the default catalog.
     *
     * @param modelKey catalog key
     * @return the matching specification, if present
     */
    public static Optional<OnnxModelSpec> resolve(String modelKey) {
        return defaults().find(modelKey);
    }

    /**
     * Looks up a model specification by catalog key.
     *
     * @param modelKey catalog key
     * @return the matching specification, if present
     */
    public Optional<OnnxModelSpec> find(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(models.get(normalizeKey(modelKey)));
    }

    /** Returns an unmodifiable view of the registered model specifications. */
    public Collection<OnnxModelSpec> models() {
        return models.values();
    }

    private static void register(
        Map<String, OnnxModelSpec> models,
        String modelName,
        String huggingFaceRepo,
        int dimension,
        int vocabSize,
        OnnxModelSpec.TaskPrefixStrategy strategy,
        boolean requiresExternalData
    ) {
        OnnxModelSpec spec = new OnnxModelSpec(
            modelName,
            dimension,
            vocabSize,
            requiresExternalData,
            strategy,
            huggingFaceRepo,
            modelName + ".onnx"
        );
        String key = normalizeKey(modelName);
        if (models.putIfAbsent(key, spec) != null) {
            throw new IllegalArgumentException("Duplicate model key: " + key);
        }
    }

    private static String normalizeKey(String modelKey) {
        return Objects.requireNonNull(modelKey, "Model key must not be null").trim().toLowerCase();
    }
}
