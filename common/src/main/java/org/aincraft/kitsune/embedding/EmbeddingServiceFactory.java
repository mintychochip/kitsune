package org.aincraft.kitsune.embedding;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.cache.EmbeddingCache;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.embedding.download.ModelMap;
import org.aincraft.kitsune.embedding.download.ModelSpec;

import java.util.logging.Logger;

/**
 * Factory for creating EmbeddingService instances based on configuration.
 */
public final class EmbeddingServiceFactory {
    private static final int DEFAULT_DIMENSION = 768;

    private final KitsuneConfig config;
    private final Platform platform;
    private final EmbeddingCache cache;
    private final Logger logger;

    @Inject
    public EmbeddingServiceFactory(KitsuneConfig config, Platform platform, EmbeddingCache cache) {
        this.config = config;
        this.platform = platform;
        this.cache = cache;
        this.logger = platform.getLogger();
    }

    /**
     * Get the embedding dimension for the configured provider/model.
     */
    public int getDimension() {
        String provider = config.embeddingProvider().toLowerCase();
        String model = config.embeddingModel().toLowerCase();

        return switch (provider) {
            case "openai" -> {
                if (model.contains("large") || model.contains("3072")) {
                    yield 3072;
                }
                yield 1536;
            }
            case "google" -> 768;
            default -> ModelMap.getInstance().get(model)
                .map(ModelSpec::dimension)
                .orElse(DEFAULT_DIMENSION);
        };
    }

    /**
     * Create an EmbeddingService based on configuration.
     */
    public EmbeddingService create() {
        String provider = config.embeddingProvider();
        String model = config.embeddingModel();

        EmbeddingService baseService = switch (provider.toLowerCase()) {
            case "openai" -> {
                String apiKey = config.embeddingApiKey();
                if (apiKey == null || apiKey.isEmpty()) {
                    logger.warning("OpenAI API key not configured, falling back to local model");
                    yield createLocalService(model);
                }
                yield new OpenAIEmbeddingService(platform, config);
            }
            case "google" -> {
                String apiKey = config.embeddingApiKey();
                if (apiKey == null || apiKey.isEmpty()) {
                    logger.warning("Google API key not configured, falling back to local model");
                    yield createLocalService(model);
                }
                yield new GoogleEmbeddingService(platform, apiKey, model);
            }
            default -> createLocalService(model);
        };

        return new CachedEmbeddingService(logger, baseService, cache);
    }

    private EmbeddingService createLocalService(String model) {
        ModelSpec spec = ModelMap.getInstance().get(model).orElseGet(() -> {
            logger.warning("Unknown model: " + model + ", defaulting to nomic-embed-text-v1.5");
            return ModelMap.getInstance().get("nomic-embed-text-v1.5").orElseThrow();
        });

        logger.info("Creating ONNX embedding service for: " + spec.modelName() +
                   " (" + spec.dimension() + "d, strategy: " + spec.taskPrefixStrategy() + ")");
        return new OnnxEmbeddingService(platform, spec);
    }
}
