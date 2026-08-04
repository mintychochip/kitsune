package dev.jlo.kitsune.api;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.protection.AccessContext;
import dev.jlo.kitsune.api.protection.AccessDecision;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ApiUsageExampleTest {
    @Test
    void neutralConsumerUsesOnlyPublicContracts() {
        AccessContext context = new AccessContext(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "ExamplePlayer",
            new BlockKey(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                12,
                64,
                -4
            )
        );
        BlockAccessProvider accessProvider = new ExampleAccessProvider();
        assertEquals(AccessDecision.ALLOW, accessProvider.canAccess(context));

        ItemDescriptor descriptor = ItemDescriptor.builder()
            .materialKey("minecraft:stone")
            .amount(1)
            .build();
        EmbeddingProvider embeddings = new ExampleEmbeddingProvider();
        Embedding embedding = embeddings.embed(descriptor);

        assertEquals("example", embeddings.id());
        assertEquals(1, embeddings.version());
        assertNotNull(embedding);
        assertNotNull(embeddings.embedQuery("stone"));
        assertNotNull(embeddings.decode(embedding.encode(), embedding.norm()));
    }

    private static final class ExampleAccessProvider implements BlockAccessProvider {
        @Override
        public AccessDecision canAccess(AccessContext context) {
            return AccessDecision.ALLOW;
        }
    }

    private static final class ExampleEmbeddingProvider implements EmbeddingProvider {
        @Override
        public String id() {
            return "example";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Embedding embed(ItemDescriptor descriptor) {
            return new ExampleEmbedding(new byte[] {(byte) descriptor.materialKey().length()});
        }

        @Override
        public Embedding embedQuery(String query) {
            return new ExampleEmbedding(new byte[] {(byte) query.length()});
        }

        @Override
        public Embedding decode(byte[] payload, double norm) {
            return new ExampleEmbedding(payload);
        }
    }

    private record ExampleEmbedding(byte[] payload) implements Embedding {
        private ExampleEmbedding {
            payload = Arrays.copyOf(payload, payload.length);
        }

        @Override
        public String providerId() {
            return "example";
        }

        @Override
        public int providerVersion() {
            return 1;
        }

        @Override
        public double norm() {
            return 1.0;
        }

        @Override
        public byte[] encode() {
            return Arrays.copyOf(payload, payload.length);
        }

        @Override
        public double cosine(Embedding other) {
            assertArrayEquals(payload, other.encode());
            return 1.0;
        }
    }
}
