package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies {@link EmbeddingRegistry} provider ordering, duplicate/null rejection, and selection validation. */
class EmbeddingRegistryTest {

    @Test
    void builtinIsAlwaysFirst() {
        var registry = new EmbeddingRegistry(new ArrayList<>(), SparseTagEmbeddingProvider.ID);
        assertSame(SparseTagEmbeddingProvider.ID, registry.providers().iterator().next().id());
        assertEquals(SparseTagEmbeddingProvider.ID, registry.selectedProvider().id());
    }

    @Test
    void rejectsDuplicateProviderIdsThroughCollectionSeam() {
        var a = new DummyProvider("same", 1);
        var b = new DummyProvider("same", 2);
        assertThrows(IllegalArgumentException.class,
            () -> new EmbeddingRegistry(new ArrayList<>(List.of(a, b)), "same"));
    }

    @Test
    void rejectsNullBlankAndBuiltinCollisions() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingRegistry(new ArrayList<>(List.of(new DummyProvider(null, 1))), SparseTagEmbeddingProvider.ID));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingRegistry(new ArrayList<>(List.of(new DummyProvider("", 1))), SparseTagEmbeddingProvider.ID));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingRegistry(new ArrayList<>(List.of(new DummyProvider(SparseTagEmbeddingProvider.ID, 1))), SparseTagEmbeddingProvider.ID));
    }

    @Test
    void rejectsMissingSelection() {
        assertThrows(IllegalArgumentException.class,
            () -> new EmbeddingRegistry(new ArrayList<>(), "missing"));
    }

    /** Test {@link EmbeddingProvider} stub that records its id and version without producing real vectors. */
    private static class DummyProvider implements EmbeddingProvider {
        private final String id;
        private final int version;
        DummyProvider(String id, int version) { this.id = id; this.version = version; }
        @Override public String id() { return id; }
        @Override public int version() { return version; }
        @Override public Embedding embed(ItemDescriptor d) { return null; }
        @Override public Embedding embedQuery(String q) { return null; }
        @Override public Embedding decode(byte[] p, double n) { return null; }
    }
}
