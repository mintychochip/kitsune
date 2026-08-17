package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemDraft;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves item embeddings from the repository cache, computing missing vectors in batches. */
public final class CachedEmbeddingResolver {
    /** Resolves drafts using cached embeddings and stores any newly computed vectors.
     *
     * @param drafts item drafts to resolve
     * @param provider embedding provider
     * @param repository persistence backing the cache
     * @return indexed items in draft order
     * @throws SQLException if the repository cannot access its storage
     */
    public List<IndexedItem> resolve(
            List<ItemDraft> drafts,
            EmbeddingProvider provider,
            IndexRepository repository
    ) throws SQLException {
        Objects.requireNonNull(drafts, "Drafts must not be null");
        Objects.requireNonNull(provider, "Embedding provider");
        Objects.requireNonNull(repository, "Repository");
        if (drafts.isEmpty()) {
            return List.of();
        }

        List<SemanticDescriptorHash> hashes = new ArrayList<>(drafts.size());
        Set<SemanticDescriptorHash> unique = new LinkedHashSet<>();
        for (ItemDraft draft : drafts) {
            SemanticDescriptorHash hash = SemanticDescriptorHash.of(draft.descriptor());
            hashes.add(hash);
            unique.add(hash);
        }

        Map<SemanticDescriptorHash, Embedding> cached = new LinkedHashMap<>(
                repository.findEmbeddings(provider, unique)
        );

        Map<SemanticDescriptorHash, ItemDescriptor> missing = new LinkedHashMap<>();
        for (int index = 0; index < drafts.size(); index++) {
            SemanticDescriptorHash hash = hashes.get(index);
            if (!cached.containsKey(hash)) {
                missing.putIfAbsent(hash, drafts.get(index).descriptor());
            }
        }

        if (!missing.isEmpty()) {
            List<ItemDescriptor> descriptors = new ArrayList<>(missing.values());
            List<Embedding> embeddings = provider.embedAll(descriptors);
            if (embeddings.size() != descriptors.size()) {
                throw new IllegalStateException("Embedding provider returned a mismatched batch");
            }
            Map<SemanticDescriptorHash, Embedding> fresh = new LinkedHashMap<>();
            int index = 0;
            for (SemanticDescriptorHash hash : missing.keySet()) {
                fresh.put(hash, embeddings.get(index++));
            }
            repository.putEmbeddings(provider, fresh);
            cached.putAll(fresh);
        }

        List<IndexedItem> items = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            ItemDraft draft = drafts.get(index);
            Embedding embedding = cached.get(hashes.get(index));
            if (embedding == null) {
                throw new IllegalStateException("Missing embedding for semantic descriptor hash");
            }
            items.add(new IndexedItem(
                    draft.path(),
                    draft.amount(),
                    draft.descriptor(),
                    embedding
            ));
        }
        return List.copyOf(items);
    }
}
