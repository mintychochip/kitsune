package dev.jlo.kitsune.embedding.local;

import dev.jlo.kitsune.embedding.EmbeddingProviderCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies catalog metadata, task-prefix behavior, and factory settings parsing. */
final class LocalOnnxEmbeddingProviderTest {
    @Test
    void defaultCatalogContainsCuratedModels() {
        OnnxModelCatalog catalog = OnnxModelCatalog.defaults();

        assertEquals(3, catalog.models().size());
        assertTrue(catalog.find("nomic-embed-text-v1.5").isPresent());
        assertTrue(catalog.find("all-minilm-l6-v2").isPresent());
        assertTrue(catalog.find("bge-m3").isPresent());
        assertEquals("nomic-embed-text-v1.5", OnnxModelCatalog.defaultModelKey());
    }

    @Test
    void catalogLookupIsCaseInsensitive() {
        Optional<OnnxModelSpec> spec = OnnxModelCatalog.resolve("BGE-M3");

        assertTrue(spec.isPresent());
        assertEquals(1024, spec.orElseThrow().dimension());
        assertEquals("BAAI/bge-m3", spec.orElseThrow().huggingFaceRepo());
    }

    @Test
    void nomicTaskPrefixStrategyAddsRetrievalPrefixes() {
        OnnxModelSpec spec = OnnxModelCatalog.resolve("nomic-embed-text-v1.5").orElseThrow();

        assertEquals("search_query: find diamonds", spec.applyTaskPrefix("find diamonds", "RETRIEVAL_QUERY"));
        assertEquals("search_document: iron sword", spec.applyTaskPrefix("iron sword", "RETRIEVAL_DOCUMENT"));
        assertEquals("clustering: grouped", spec.applyTaskPrefix("grouped", "CLUSTERING"));
        assertEquals("classification: labeled", spec.applyTaskPrefix("labeled", "CLASSIFICATION"));
    }

    @Test
    void e5InstructTaskPrefixStrategyAddsQueryInstruction() {
        OnnxModelSpec spec = new OnnxModelSpec(
            "multilingual-e5-large-instruct",
            1024,
            250_002,
            true,
            OnnxModelSpec.TaskPrefixStrategy.E5_INSTRUCT,
            "intfloat/multilingual-e5-large-instruct",
            "multilingual-e5-large-instruct.onnx"
        );

        assertEquals(
            "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: find emeralds",
            spec.applyTaskPrefix("find emeralds", "RETRIEVAL_QUERY")
        );
        assertEquals("emerald ore", spec.applyTaskPrefix("emerald ore", "RETRIEVAL_DOCUMENT"));
    }

    @Test
    void meanPoolingAndNormalizationProduceUnitVector() {
        float[][] hiddenStates = {
            {1.0f, 2.0f},
            {3.0f, 4.0f},
            {0.0f, 0.0f}
        };
        long[] attentionMask = {1L, 1L, 0L};

        float[] pooled = LocalOnnxEmbeddingProvider.meanPooling(hiddenStates, attentionMask, 2);

        float expectedScale = (float) (1.0d / Math.sqrt(13.0d));
        assertEquals(2.0f * expectedScale, pooled[0], 1.0e-6f);
        assertEquals(3.0f * expectedScale, pooled[1], 1.0e-6f);
        double norm = Math.sqrt((pooled[0] * pooled[0]) + (pooled[1] * pooled[1]));
        assertEquals(1.0d, norm, 1.0e-6d);
    }

    @Test
    void sanitizeTokenIdsReplacesOutOfRangeValues() {
        long[] sanitized = LocalOnnxEmbeddingProvider.sanitizeTokenIds(
            new long[] {1L, 99_999L, -1L},
            100
        );

        assertEquals(1L, sanitized[0]);
        assertEquals(100L, sanitized[1]);
        assertEquals(100L, sanitized[2]);
    }

    @Test
    void sanitizeTokenIdsCanBeDisabledWithZeroVocabularySize() {
        long[] input = {1L, 99_999L};

        assertEquals(input, LocalOnnxEmbeddingProvider.sanitizeTokenIds(input, 0));
    }

    @Test
    void factoryRequiresDataDirSetting() {
        LocalOnnxEmbeddingProviderFactory factory = new LocalOnnxEmbeddingProviderFactory();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> factory.create(Map.of("model", "bge-m3"), reference -> Optional.empty())
        );

        assertEquals("data-dir setting is required", exception.getMessage());
    }

    @Test
    void factoryRejectsUnknownModelKeys() {
        LocalOnnxEmbeddingProviderFactory factory = new LocalOnnxEmbeddingProviderFactory();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> factory.create(
                Map.of("data-dir", "/tmp/models", "model", "missing-model"),
                reference -> Optional.empty()
            )
        );

        assertEquals("Unknown model: missing-model", exception.getMessage());
    }

    @Test
    void factoryParsesAutoDownloadSetting() {
        LocalOnnxEmbeddingProviderFactory.LocalOnnxEmbeddingSettings disabled =
            LocalOnnxEmbeddingProviderFactory.LocalOnnxEmbeddingSettings.from(
                Map.of("data-dir", "/tmp/models", "auto-download", "false")
            );
        LocalOnnxEmbeddingProviderFactory.LocalOnnxEmbeddingSettings defaulted =
            LocalOnnxEmbeddingProviderFactory.LocalOnnxEmbeddingSettings.from(
                Map.of("data-dir", "/tmp/models")
            );

        assertFalse(disabled.autoDownload());
        assertTrue(defaulted.autoDownload());
        assertEquals("nomic-embed-text-v1.5", defaulted.spec().modelName());
        assertEquals(Path.of("/tmp/models"), defaulted.dataDir());
    }

    @Test
    void defaultsWithLocalOnnxIncludesLocalFactory() {
        EmbeddingProviderCatalog catalog = LocalOnnxEmbeddingProviderFactory.defaultsWithLocalOnnx();

        assertTrue(
            catalog.factories().stream()
                .anyMatch(factory -> LocalOnnxEmbeddingProviderFactory.FACTORY_ID.equals(factory.id()))
        );
    }

    @Test
    void huggingFaceDownloaderBuildsExpectedUrl() {
        assertEquals(
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model.onnx",
            HuggingFaceModelDownloader.buildDownloadUrl("nomic-ai/nomic-embed-text-v1.5", "onnx/model.onnx")
        );
    }

}
