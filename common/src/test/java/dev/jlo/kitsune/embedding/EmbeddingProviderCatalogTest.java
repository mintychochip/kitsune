package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;
import dev.jlo.kitsune.embedding.remote.OpenAiCompatibleEmbeddingProvider;
import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies provider creation, duplicate-id rejection, and built-in factory registration in {@link EmbeddingProviderCatalog}. */
final class EmbeddingProviderCatalogTest {
    @Test
    void catalogCreatesProviderThroughRegisteredFactory() {
        EmbeddingProviderFactory factory = new DummyFactory("test");

        EmbeddingProvider provider = new EmbeddingProviderCatalog(List.of(factory))
            .create("test", Map.of("model", "demo"), reference -> Optional.of("secret"));

        assertEquals("test:demo:secret", provider.id());
    }

    @Test
    void catalogRejectsDuplicateFactoryIds() {
        EmbeddingProviderFactory first = new DummyFactory("same");
        EmbeddingProviderFactory second = new DummyFactory("same");

        assertThrows(
            IllegalArgumentException.class,
            () -> new EmbeddingProviderCatalog(List.of(first, second))
        );
    }

    @Test
    void defaultCatalogIncludesBuiltInFactories() {
        EmbeddingProviderCatalog catalog = EmbeddingProviderCatalog.defaults();

        assertEquals(
            List.of(
                SparseTagEmbeddingProvider.ID,
                OpenAiCompatibleEmbeddingProvider.FACTORY_ID
            ),
            catalog.factories().stream().map(EmbeddingProviderFactory::id).toList()
        );
        assertEquals(
            SparseTagEmbeddingProvider.ID,
            catalog.create(
                SparseTagEmbeddingProvider.ID,
                Map.of(),
                reference -> Optional.empty()
            ).id()
        );
    }

    /** Test factory whose provider id concatenates the factory id, model, and resolved credential. */
    private static final class DummyFactory implements EmbeddingProviderFactory {
        private final String id;

        private DummyFactory(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public EmbeddingProvider create(
            Map<String, String> settings,
            EmbeddingCredentialResolver credentials
        ) {
            return new DummyProvider(
                id + ":" + settings.get("model") + ":" +
                    credentials.resolve("env:KEY").orElse("missing")
            );
        }
    }

    /** Minimal {@link EmbeddingProvider} stub used to observe the factory-created id. */
    private static final class DummyProvider implements EmbeddingProvider {
        private final String id;

        private DummyProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Embedding embed(ItemDescriptor descriptor) {
            return null;
        }

        @Override
        public Embedding embedQuery(String query) {
            return null;
        }

        @Override
        public Embedding decode(byte[] payload, double norm) {
            return null;
        }
    }
}
