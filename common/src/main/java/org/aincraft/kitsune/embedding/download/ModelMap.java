package org.aincraft.kitsune.embedding.download;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Map of known ONNX model specifications.
 */
public final class ModelMap {

    private static final ModelMap INSTANCE = new ModelMap();

    private final Map<String, ModelSpec> models = new HashMap<>();

    private ModelMap() {
        register("nomic-embed-text-v1.5",
            "nomic-ai/nomic-embed-text-v1.5",
            768, 30528, ModelSpec.TaskPrefixStrategy.NOMIC, false);
        register("all-minilm-l6-v2",
            "sentence-transformers/all-MiniLM-L6-v2",
            384, 30522, ModelSpec.TaskPrefixStrategy.NONE, false);
        register("bge-m3",
            "BAAI/bge-m3",
            1024, 250002, ModelSpec.TaskPrefixStrategy.NONE, false);
        register("multilingual-e5-large-instruct",
            "intfloat/multilingual-e5-large-instruct",
            1024, 250002, ModelSpec.TaskPrefixStrategy.E5_INSTRUCT, true);
    }

    public static ModelMap getInstance() {
        return INSTANCE;
    }

    private void register(String name, String repo, int dimension,
                          int vocabSize, ModelSpec.TaskPrefixStrategy strategy,
                          boolean requiresExternalData) {
        models.put(name.toLowerCase(), new ModelSpec(
            name, repo, "onnx/model.onnx", "tokenizer.json",
            dimension, vocabSize, strategy, requiresExternalData
        ));
    }

    public Optional<ModelSpec> get(String modelName) {
        if (modelName == null) return Optional.empty();
        return Optional.ofNullable(models.get(modelName.toLowerCase()));
    }

    public boolean contains(String modelName) {
        return modelName != null && models.containsKey(modelName.toLowerCase());
    }
}
