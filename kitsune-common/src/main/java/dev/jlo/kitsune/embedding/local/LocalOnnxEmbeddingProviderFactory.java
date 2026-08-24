package dev.jlo.kitsune.embedding.local;

import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;
import dev.jlo.kitsune.embedding.EmbeddingProviderCatalog;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProviderFactory;
import dev.jlo.kitsune.embedding.remote.OpenAiCompatibleEmbeddingProviderFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Factory creating {@link LocalOnnxEmbeddingProvider} instances from settings maps.
 */
public final class LocalOnnxEmbeddingProviderFactory implements EmbeddingProviderFactory {
    /** Provider id reported by created providers. */
    public static final String FACTORY_ID = LocalOnnxEmbeddingProvider.PROVIDER_ID;

    private static final String SETTING_MODEL = "model";
    private static final String SETTING_DATA_DIR = "data-dir";
    private static final String SETTING_AUTO_DOWNLOAD = "auto-download";

    @Override
    public String id() {
        return FACTORY_ID;
    }

    @Override
    public EmbeddingProvider create(
        Map<String, String> settings,
        EmbeddingCredentialResolver credentials
    ) {
        Objects.requireNonNull(settings, "Settings must not be null");
        Objects.requireNonNull(credentials, "Credentials must not be null");
        LocalOnnxEmbeddingSettings parsed = LocalOnnxEmbeddingSettings.from(settings);
        return LocalOnnxEmbeddingProvider.create(
            parsed.dataDir(),
            parsed.spec(),
            parsed.autoDownload()
        );
    }

    /**
     * Returns the default provider catalog extended with the local ONNX factory.
     *
     * @return catalog containing built-in and local ONNX provider factories
     */
    public static EmbeddingProviderCatalog defaultsWithLocalOnnx() {
        return new EmbeddingProviderCatalog(List.of(
            new SparseTagEmbeddingProviderFactory(),
            new OpenAiCompatibleEmbeddingProviderFactory(),
            new LocalOnnxEmbeddingProviderFactory()
        ));
    }

    /**
     * Immutable settings parsed from a provider configuration map.
     *
     * @param dataDir directory containing local ONNX model artifacts
     * @param spec selected model specification
     * @param autoDownload whether missing artifacts should be downloaded automatically
     */
    record LocalOnnxEmbeddingSettings(Path dataDir, OnnxModelSpec spec, boolean autoDownload) {
        static LocalOnnxEmbeddingSettings from(Map<String, String> settings) {
            Objects.requireNonNull(settings, "Settings must not be null");
            String dataDirValue = settings.get(SETTING_DATA_DIR);
            if (dataDirValue == null || dataDirValue.isBlank()) {
                throw new IllegalArgumentException("data-dir setting is required");
            }

            String modelKey = settings.getOrDefault(SETTING_MODEL, OnnxModelCatalog.defaultModelKey());
            OnnxModelSpec spec = OnnxModelCatalog.resolve(modelKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelKey));

            boolean autoDownload = parseBoolean(settings.get(SETTING_AUTO_DOWNLOAD), true);
            return new LocalOnnxEmbeddingSettings(Path.of(dataDirValue.trim()), spec, autoDownload);
        }

        private static boolean parseBoolean(String value, boolean defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return switch (value.trim().toLowerCase()) {
                case "true", "yes", "1", "on" -> true;
                case "false", "no", "0", "off" -> false;
                default -> throw new IllegalArgumentException("Invalid boolean setting: " + value);
            };
        }
    }
}
