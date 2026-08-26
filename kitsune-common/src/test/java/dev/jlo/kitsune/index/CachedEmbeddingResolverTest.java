package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies semantic deduplication and persistence reuse in {@link CachedEmbeddingResolver}. */
class CachedEmbeddingResolverTest {
    /** Ensures duplicate semantic descriptors are embedded only once per batch. */
    @Test
    void identicalSemanticStacksEmbedOnce() throws Exception {
        CountingProvider provider = new CountingProvider();
        MemoryRepository repository = new MemoryRepository();
        CachedEmbeddingResolver resolver = new CachedEmbeddingResolver();

        List<IndexedItem> items = resolver.resolve(
                List.of(draft(stone(1), 0), draft(stone(64), 1)),
                provider,
                repository
        );

        assertEquals(1, provider.embedAllCalls());
        assertEquals(1, provider.descriptorsSeen());
        assertEquals(1, items.get(0).amount());
        assertEquals(64, items.get(1).amount());
        assertSame(items.get(0).embedding(), items.get(1).embedding());
    }

    /** Ensures a later resolution reuses vectors persisted by an earlier resolution. */
    @Test
    void secondResolveReusesPersistedVectors() throws Exception {
        CountingProvider provider = new CountingProvider();
        MemoryRepository repository = new MemoryRepository();
        CachedEmbeddingResolver resolver = new CachedEmbeddingResolver();

        resolver.resolve(List.of(draft(stone(1), 0)), provider, repository);
        provider.reset();

        List<IndexedItem> items = resolver.resolve(
                List.of(draft(stone(32), 0), draft(dirt(1), 1)),
                provider,
                repository
        );

        assertEquals(1, provider.embedAllCalls());
        assertEquals(1, provider.descriptorsSeen());
        assertEquals("minecraft:dirt", items.get(1).descriptor().materialKey());
        assertEquals(32, items.get(0).amount());
    }

    private static ItemDraft draft(ItemDescriptor descriptor, int slot) {
        return new ItemDraft(
                new ItemPath(List.of(new ItemPathStep("Storage", slot))),
                descriptor.amount(),
                descriptor
        );
    }

    private static ItemDescriptor stone(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(amount).build();
    }

    private static ItemDescriptor dirt(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:dirt").amount(amount).build();
    }

    private static final class CountingProvider implements EmbeddingProvider {
        private final EmbeddingProvider delegate = new SparseTagEmbeddingProvider();
        private final AtomicInteger embedAllCalls = new AtomicInteger();
        private final AtomicInteger descriptorsSeen = new AtomicInteger();

        int embedAllCalls() {
            return embedAllCalls.get();
        }

        int descriptorsSeen() {
            return descriptorsSeen.get();
        }

        void reset() {
            embedAllCalls.set(0);
            descriptorsSeen.set(0);
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public int version() {
            return delegate.version();
        }

        @Override
        public Embedding embed(ItemDescriptor descriptor) {
            descriptorsSeen.incrementAndGet();
            return delegate.embed(descriptor);
        }

        @Override
        public List<Embedding> embedAll(List<ItemDescriptor> descriptors) {
            embedAllCalls.incrementAndGet();
            descriptorsSeen.addAndGet(descriptors.size());
            return descriptors.stream().map(delegate::embed).toList();
        }

        @Override
        public Embedding embedQuery(String query) {
            return delegate.embedQuery(query);
        }

        @Override
        public Embedding decode(byte[] payload, double norm) {
            return delegate.decode(payload, norm);
        }
    }

    private static final class MemoryRepository implements IndexRepository {
        private final Map<SemanticDescriptorHash, Embedding> embeddings = new LinkedHashMap<>();

        @Override
        public void migrate() {}

        @Override
        public void markAllChunksUnavailable() {}

        @Override
        public void setChunkAvailable(ChunkKey chunk, boolean available, long revision) {}

        @Override
        public void replaceRoot(ContainerSnapshot snapshot, long revision) {}

        @Override
        public void deleteRoot(BlockKey key) {}

        @Override
        public CandidatePage findCandidates(
                UUID worldId,
                int minChunkX,
                int maxChunkX,
                int minChunkZ,
                int maxChunkZ,
                CandidateCursor after,
                int limit
        ) {
            return new CandidatePage(List.of(), null);
        }

        @Override
        public Optional<RootIdentity> findRoot(BlockKey key) {
            return Optional.empty();
        }

        @Override
        public Map<SemanticDescriptorHash, Embedding> findEmbeddings(
                EmbeddingProvider provider,
                Set<SemanticDescriptorHash> hashes
        ) {
            Map<SemanticDescriptorHash, Embedding> found = new LinkedHashMap<>();
            for (SemanticDescriptorHash hash : hashes) {
                Embedding embedding = embeddings.get(hash);
                if (embedding != null) {
                    found.put(hash, embedding);
                }
            }
            return Map.copyOf(found);
        }

        @Override
        public void putEmbeddings(
                EmbeddingProvider provider,
                Map<SemanticDescriptorHash, Embedding> stored
        ) {
            embeddings.putAll(stored);
        }

        @Override
        public Map<BlockKey, List<IndexedItem>> loadDocuments(
                Set<BlockKey> allowed,
                EmbeddingProvider provider
        ) {
            return Map.of();
        }


        @Override
        public List<IndexRepository.FullTextMatch> findFullTextMatches(
                String matchExpression,
                java.util.UUID worldId,
                int minChunkX,
                int maxChunkX,
                int minChunkZ,
                int maxChunkZ,
                int limit
        ) {
            return List.of();
        }

        @Override
        public void reembedAll(EmbeddingProvider provider) {}

        @Override
        public void close() {}
    }
}
