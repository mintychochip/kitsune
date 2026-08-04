package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingCredentialResolver;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.EmbeddingProviderFactory;
import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
