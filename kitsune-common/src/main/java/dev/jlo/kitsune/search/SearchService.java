package dev.jlo.kitsune.search;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.command.SearchRequest;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.IndexWarmupTimeoutException;
import dev.jlo.kitsune.index.IndexWorker;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.session.SearchToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Coordinates embedding, index lookup, access validation, and result ranking. */
public final class SearchService {
    private static final int CANDIDATE_PAGE_SIZE = 128;
    private static final Comparator<RootMatch> ROOT_COMPARATOR = Comparator
        .comparingDouble(RootMatch::bestScore)
        .reversed()
        .thenComparingDouble(RootMatch::distance)
        .thenComparing(root -> root.key().worldId())
        .thenComparingInt(root -> root.key().x())
        .thenComparingInt(root -> root.key().y())
        .thenComparingInt(root -> root.key().z());

    private static final Comparator<ItemMatch> ITEM_COMPARATOR = Comparator
        .comparingDouble(ItemMatch::score)
        .reversed()
        .thenComparing(ItemMatch::path, SearchService::comparePaths)
        .thenComparingInt(ItemMatch::amount)
        .thenComparing(match -> match.descriptor().materialKey());

    private static final Comparator<SemanticHit> SEMANTIC_HIT_COMPARATOR = Comparator
        .comparingDouble(SemanticHit::cosine)
        .reversed()
        .thenComparing(hit -> hit.item().path(), SearchService::comparePaths)
        .thenComparingInt(hit -> hit.item().amount())
        .thenComparing(hit -> hit.item().descriptor().materialKey());

    private final IndexReadiness readiness;
    private final IndexWorker worker;
    private final IndexRepository repository;
    private final EmbeddingProvider embeddings;
    private final ServerThreadBridge serverBridge;
    private final LiveRootAccess liveRootAccess;
    private final SearchPolicy policy;

    /** Creates a search service from its readiness, storage, server, and policy dependencies. */
    public SearchService(
        IndexReadiness readiness,
        IndexWorker worker,
        IndexRepository repository,
        EmbeddingProvider embeddings,
        ServerThreadBridge serverBridge,
        LiveRootAccess liveRootAccess,
        SearchPolicy policy
    ) {
        this.readiness = Objects.requireNonNull(readiness, "Readiness must not be null");
        this.worker = Objects.requireNonNull(worker, "Worker must not be null");
        this.repository = Objects.requireNonNull(repository, "Repository must not be null");
        this.embeddings = Objects.requireNonNull(embeddings, "Embedding provider must not be null");
        this.serverBridge = Objects.requireNonNull(serverBridge, "Server bridge must not be null");
        this.liveRootAccess = Objects.requireNonNull(liveRootAccess, "Live root access must not be null");
        this.policy = Objects.requireNonNull(policy, "Policy must not be null");
    }

    /** Executes a search asynchronously and returns its terminal outcome. */
    public CompletableFuture<SearchOutcome> search(
        SearchContext context,
        SearchRequest request,
        SearchToken token,
        SearchGuard guard
    ) {
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(request, "Request must not be null");
        Objects.requireNonNull(token, "Token must not be null");
        Objects.requireNonNull(guard, "Guard must not be null");

        try {
            if (!isCurrent(context, token, guard)) {
                return CompletableFuture.completedFuture(SearchOutcome.canceled());
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(SearchOutcome.failure());
        }

        FullTextQuery fullTextQuery = FullTextQuery.parse(request.query());
        if (fullTextQuery.isEmpty()) {
            return CompletableFuture.completedFuture(SearchOutcome.unsupportedQuery());
        }

        CompletableFuture<SearchOutcome> pipeline = embedQuery(context, request, token, guard)
            .thenCompose(queryEmbedding -> loadedChunks(context, token, guard)
                .thenCompose(chunks -> {
                    requireCurrent(context, token, guard);
                    if (chunks.isEmpty()) {
                        return CompletableFuture.completedFuture(SearchOutcome.noMatches());
                    }
                    return awaitReady(context, token, guard, chunks)
                        .thenCompose(ignored -> runHybridSearch(
                            context,
                            token,
                            guard,
                            queryEmbedding,
                            fullTextQuery
                        ));
                }));

        return pipeline.handle((outcome, failure) -> mapOutcome(context, token, guard, outcome, failure));
    }

    private CompletableFuture<SearchOutcome> runHybridSearch(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        Embedding queryEmbedding,
        FullTextQuery fullTextQuery
    ) {
        CandidateBounds bounds = candidateBounds(context);
        return findFullTextMatches(context, token, guard, fullTextQuery, bounds)
            .thenCompose(ftsMatches -> filterValidatedFtsMatches(context, token, guard, ftsMatches)
                .thenCompose(filteredFts -> collectCandidatePages(
                    context,
                    token,
                    guard,
                    queryEmbedding,
                    bounds,
                    null,
                    new HybridAccumulator(filteredFts.matches(), filteredFts.allowedRoots())
                )));
    }

    private CompletableFuture<Embedding> embedQuery(
        SearchContext context,
        SearchRequest request,
        SearchToken token,
        SearchGuard guard
    ) {
        return worker.submit(() -> {
            requireCurrent(context, token, guard);
            Embedding queryEmbedding = embeddings.embedQuery(request.query());
            if (queryEmbedding == null) {
                throw new IllegalStateException("Embedding provider returned a null query embedding");
            }

            double norm = queryEmbedding.norm();
            if (norm == 0.0) {
                throw new UnsupportedQueryException();
            }
            if (!Double.isFinite(norm) || norm < 0.0) {
                throw new IllegalStateException("Embedding provider returned an invalid query norm");
            }
            requireCurrent(context, token, guard);
            return queryEmbedding;
        }).thenApply(queryEmbedding -> {
            requireCurrent(context, token, guard);
            return queryEmbedding;
        });
    }

    private CompletableFuture<Set<ChunkKey>> loadedChunks(
        SearchContext context,
        SearchToken token,
        SearchGuard guard
    ) {
        CompletableFuture<Set<ChunkKey>> future;
        try {
            requireCurrent(context, token, guard);
            future = serverBridge.supply(() -> {
                requireCurrent(context, token, guard);
                Set<ChunkKey> chunks = liveRootAccess.loadedChunks(context, policy.radius());
                requireCurrent(context, token, guard);
                return Set.copyOf(Objects.requireNonNull(chunks, "Loaded chunks must not be null"));
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Server bridge returned a null future")
            );
        }
        return future.thenApply(chunks -> {
            requireCurrent(context, token, guard);
            return chunks;
        });
    }

    private CompletableFuture<Void> awaitReady(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        Set<ChunkKey> chunks
    ) {
        CompletionStage<Void> stage;
        try {
            requireCurrent(context, token, guard);
            stage = readiness.awaitReady(chunks, policy.warmupTimeout());
            if (stage == null) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("Index readiness returned a null stage")
                );
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return stage.toCompletableFuture().thenRun(() -> requireCurrent(context, token, guard));
    }

    private CompletableFuture<List<IndexRepository.FullTextMatch>> findFullTextMatches(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        FullTextQuery fullTextQuery,
        CandidateBounds bounds
    ) {
        return worker.submit(() -> {
            requireCurrent(context, token, guard);
            List<IndexRepository.FullTextMatch> matches = repository.findFullTextMatches(
                fullTextQuery.matchExpression(),
                bounds.worldId(),
                bounds.minChunkX(),
                bounds.maxChunkX(),
                bounds.minChunkZ(),
                bounds.maxChunkZ(),
                policy.fullTextLimit()
            );
            requireCurrent(context, token, guard);
            return Objects.requireNonNull(matches, "Full-text matches must not be null");
        }).thenApply(matches -> {
            requireCurrent(context, token, guard);
            return matches;
        });
    }

    private CompletableFuture<FilteredFts> filterValidatedFtsMatches(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        List<IndexRepository.FullTextMatch> ftsMatches
    ) {
        if (ftsMatches.isEmpty()) {
            return CompletableFuture.completedFuture(new FilteredFts(List.of(), Map.of()));
        }

        List<RootIdentity> roots = new ArrayList<>();
        Set<BlockKey> seen = new LinkedHashSet<>();
        for (IndexRepository.FullTextMatch match : ftsMatches) {
            BlockKey key = match.root().key();
            if (!seen.contains(key)) {
                seen.add(key);
                roots.add(match.root());
            }
        }

        return validateCandidates(context, token, guard, roots)
            .thenApply(allowedRoots -> {
                List<IndexRepository.FullTextMatch> filtered = new ArrayList<>();
                for (IndexRepository.FullTextMatch match : ftsMatches) {
                    if (allowedRoots.containsKey(match.root().key())) {
                        filtered.add(match);
                    }
                }
                if (filtered.size() > policy.fullTextLimit()) {
                    filtered = filtered.subList(0, policy.fullTextLimit());
                }
                return new FilteredFts(List.copyOf(filtered), allowedRoots);
            });
    }

    private CompletableFuture<IndexRepository.CandidatePage> findCandidates(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        CandidateBounds bounds,
        IndexRepository.CandidateCursor after
    ) {
        return worker.submit(() -> {
            requireCurrent(context, token, guard);
            IndexRepository.CandidatePage candidates = repository.findCandidates(
                bounds.worldId(),
                bounds.minChunkX(),
                bounds.maxChunkX(),
                bounds.minChunkZ(),
                bounds.maxChunkZ(),
                after,
                CANDIDATE_PAGE_SIZE
            );
            requireCurrent(context, token, guard);
            return Objects.requireNonNull(candidates, "Candidate page must not be null");
        }).thenApply(candidates -> {
            requireCurrent(context, token, guard);
            return candidates;
        });
    }

    private CompletableFuture<SearchOutcome> collectCandidatePages(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        Embedding queryEmbedding,
        CandidateBounds bounds,
        IndexRepository.CandidateCursor after,
        HybridAccumulator accumulator
    ) {
        return findCandidates(context, token, guard, bounds, after)
            .thenCompose(page -> {
                requireCurrent(context, token, guard);
                if (page.roots().isEmpty()) {
                    return continueCandidatePages(
                        context,
                        token,
                        guard,
                        queryEmbedding,
                        bounds,
                        page.next(),
                        accumulator
                    );
                }
                return validateCandidates(context, token, guard, page.roots())
                    .thenCompose(allowedRoots -> {
                        requireCurrent(context, token, guard);
                        if (allowedRoots.isEmpty()) {
                            return continueCandidatePages(
                                context,
                                token,
                                guard,
                                queryEmbedding,
                                bounds,
                                page.next(),
                                accumulator
                            );
                        }
                        return loadAndCollectSemantic(
                            context,
                            token,
                            guard,
                            queryEmbedding,
                            allowedRoots
                        ).thenCompose(semanticHits -> {
                            requireCurrent(context, token, guard);
                            accumulator.addSemanticHits(semanticHits);
                            return continueCandidatePages(
                                context,
                                token,
                                guard,
                                queryEmbedding,
                                bounds,
                                page.next(),
                                accumulator
                            );
                        });
                    });
            });
    }

    private CompletableFuture<SearchOutcome> continueCandidatePages(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        Embedding queryEmbedding,
        CandidateBounds bounds,
        IndexRepository.CandidateCursor next,
        HybridAccumulator accumulator
    ) {
        if (next == null) {
            return CompletableFuture.completedFuture(accumulator.finish());
        }
        return collectCandidatePages(
            context,
            token,
            guard,
            queryEmbedding,
            bounds,
            next,
            accumulator
        );
    }

    private CandidateBounds candidateBounds(SearchContext context) {
        BlockKey origin = context.origin();
        int radius = policy.radius();
        return new CandidateBounds(
            origin.worldId(),
            chunkCoordinate((long) origin.x() - radius),
            chunkCoordinate((long) origin.x() + radius),
            chunkCoordinate((long) origin.z() - radius),
            chunkCoordinate((long) origin.z() + radius)
        );
    }

    private CompletableFuture<Map<BlockKey, AllowedRoot>> validateCandidates(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        List<RootIdentity> candidates
    ) {
        CompletableFuture<Map<BlockKey, AllowedRoot>> future;
        try {
            requireCurrent(context, token, guard);
            future = serverBridge.supply(() -> {
                requireCurrent(context, token, guard);
                Map<BlockKey, AllowedRoot> allowedRoots = new LinkedHashMap<>();
                for (RootIdentity candidate : candidates) {
                    requireCurrent(context, token, guard);
                    AllowedRoot allowed = liveRootAccess.validate(context, candidate, policy.radius());
                    requireCurrent(context, token, guard);
                    if (allowed == null || !candidate.equals(allowed.identity())) {
                        continue;
                    }
                    allowedRoots.putIfAbsent(candidate.key(), allowed);
                }
                requireCurrent(context, token, guard);
                return Map.copyOf(allowedRoots);
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (future == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Server bridge returned a null future")
            );
        }
        return future.thenApply(allowedRoots -> {
            requireCurrent(context, token, guard);
            return allowedRoots;
        });
    }

    private CompletableFuture<List<SemanticHit>> loadAndCollectSemantic(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        Embedding queryEmbedding,
        Map<BlockKey, AllowedRoot> allowedRoots
    ) {
        Set<BlockKey> allowedKeys = Set.copyOf(allowedRoots.keySet());
        return worker.submit(() -> {
            requireCurrent(context, token, guard);
            Map<BlockKey, List<IndexedItem>> documents = repository.loadDocuments(
                allowedKeys,
                embeddings
            );
            requireCurrent(context, token, guard);
            List<SemanticHit> hits = collectSemanticHits(
                Objects.requireNonNull(documents, "Documents must not be null"),
                queryEmbedding,
                allowedRoots
            );
            requireCurrent(context, token, guard);
            return hits;
        }).thenApply(hits -> {
            requireCurrent(context, token, guard);
            return hits;
        });
    }

    private List<SemanticHit> collectSemanticHits(
        Map<BlockKey, List<IndexedItem>> documents,
        Embedding queryEmbedding,
        Map<BlockKey, AllowedRoot> allowedRoots
    ) {
        List<SemanticHit> hits = new ArrayList<>();
        for (AllowedRoot allowedRoot : allowedRoots.values()) {
            List<IndexedItem> items = documents.get(allowedRoot.identity().key());
            if (items == null || items.isEmpty()) {
                continue;
            }
            for (IndexedItem item : items) {
                Objects.requireNonNull(item, "Indexed item must not be null");
                double score = item.embedding().cosine(queryEmbedding);
                if (!Double.isFinite(score) || score < -1.0 || score > 1.0) {
                    throw new IllegalStateException("Embedding provider returned an invalid cosine score");
                }
                if (score >= policy.minimumScore()) {
                    hits.add(new SemanticHit(allowedRoot, item, score));
                }
            }
        }
        return hits;
    }

    private SearchOutcome mapOutcome(
        SearchContext context,
        SearchToken token,
        SearchGuard guard,
        SearchOutcome outcome,
        Throwable failure
    ) {
        if (failure == null) {
            try {
                return isCurrent(context, token, guard) ? outcome : SearchOutcome.canceled();
            } catch (RuntimeException guardFailure) {
                return SearchOutcome.failure();
            }
        }

        Throwable cause = unwrap(failure);
        try {
            if (!isCurrent(context, token, guard)) {
                return SearchOutcome.canceled();
            }
        } catch (RuntimeException guardFailure) {
            return SearchOutcome.failure();
        }
        if (cause instanceof CanceledSearchException) {
            return SearchOutcome.canceled();
        }
        if (cause instanceof UnsupportedQueryException) {
            return SearchOutcome.unsupportedQuery();
        }
        if (cause instanceof IndexWarmupTimeoutException) {
            return SearchOutcome.indexWarming();
        }
        return SearchOutcome.failure();
    }

    private static int comparePaths(ItemPath left, ItemPath right) {
        List<ItemPathStep> leftSteps = left.steps();
        List<ItemPathStep> rightSteps = right.steps();
        int sharedSize = Math.min(leftSteps.size(), rightSteps.size());
        for (int index = 0; index < sharedSize; index++) {
            ItemPathStep leftStep = leftSteps.get(index);
            ItemPathStep rightStep = rightSteps.get(index);
            int labelComparison = leftStep.label().compareTo(rightStep.label());
            if (labelComparison != 0) {
                return labelComparison;
            }
            int slotComparison = Integer.compare(leftStep.slot(), rightStep.slot());
            if (slotComparison != 0) {
                return slotComparison;
            }
        }
        return Integer.compare(leftSteps.size(), rightSteps.size());
    }

    private static int chunkCoordinate(long blockCoordinate) {
        return Math.toIntExact(Math.floorDiv(blockCoordinate, 16L));
    }

    private static boolean isCurrent(
        SearchContext context,
        SearchToken token,
        SearchGuard guard
    ) {
        return context.playerId().equals(token.playerId()) && guard.isCurrent(token);
    }

    private static void requireCurrent(
        SearchContext context,
        SearchToken token,
        SearchGuard guard
    ) {
        if (!isCurrent(context, token, guard)) {
            throw new CanceledSearchException();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private record CandidateBounds(
        UUID worldId,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ
    ) {}

    private record FilteredFts(
        List<IndexRepository.FullTextMatch> matches,
        Map<BlockKey, AllowedRoot> allowedRoots
    ) {}

    private record SemanticHit(AllowedRoot allowedRoot, IndexedItem item, double cosine) {}

    private record FusionKey(BlockKey rootKey, ItemPath path) {
        static FusionKey of(BlockKey rootKey, ItemPath path) {
            return new FusionKey(rootKey, path);
        }
    }

    private final class HybridAccumulator {
        private final List<IndexRepository.FullTextMatch> ftsMatches;
        private final Map<BlockKey, AllowedRoot> ftsAllowedRoots;
        private final List<SemanticHit> semanticHits = new ArrayList<>();

        HybridAccumulator(
            List<IndexRepository.FullTextMatch> ftsMatches,
            Map<BlockKey, AllowedRoot> ftsAllowedRoots
        ) {
            this.ftsMatches = List.copyOf(ftsMatches);
            this.ftsAllowedRoots = Map.copyOf(ftsAllowedRoots);
        }

        void addSemanticHits(List<SemanticHit> hits) {
            semanticHits.addAll(hits);
        }

        SearchOutcome finish() {
            List<SemanticHit> rankedSemantic = new ArrayList<>(semanticHits);
            rankedSemantic.sort(SEMANTIC_HIT_COMPARATOR);
            if (rankedSemantic.size() > policy.semanticLimit()) {
                rankedSemantic = rankedSemantic.subList(0, policy.semanticLimit());
            }

            Map<FusionKey, Integer> ftsRankByKey = new LinkedHashMap<>();
            Map<FusionKey, IndexRepository.FullTextMatch> ftsByKey = new LinkedHashMap<>();
            int ftsRank = 1;
            for (IndexRepository.FullTextMatch match : ftsMatches) {
                FusionKey key = FusionKey.of(match.root().key(), match.path());
                if (!ftsRankByKey.containsKey(key)) {
                    ftsRankByKey.put(key, ftsRank++);
                    ftsByKey.put(key, match);
                }
            }

            Map<FusionKey, Integer> semanticRankByKey = new LinkedHashMap<>();
            Map<FusionKey, SemanticHit> semanticByKey = new LinkedHashMap<>();
            int semanticRank = 1;
            for (SemanticHit hit : rankedSemantic) {
                FusionKey key = FusionKey.of(hit.allowedRoot().identity().key(), hit.item().path());
                if (!semanticRankByKey.containsKey(key)) {
                    semanticRankByKey.put(key, semanticRank++);
                    semanticByKey.put(key, hit);
                }
            }

            Set<FusionKey> union = new LinkedHashSet<>();
            union.addAll(ftsRankByKey.keySet());
            union.addAll(semanticRankByKey.keySet());
            if (union.isEmpty()) {
                return SearchOutcome.noMatches();
            }

            Map<BlockKey, List<ItemMatch>> itemsByRoot = new LinkedHashMap<>();
            Map<BlockKey, AllowedRoot> allowedByRoot = new LinkedHashMap<>();

            for (FusionKey key : union) {
                Integer ftsItemRank = ftsRankByKey.get(key);
                Integer semanticItemRank = semanticRankByKey.get(key);
                double displayScore = ReciprocalRankFusion.displayScore(
                    policy.rrfK(),
                    ftsItemRank,
                    semanticItemRank
                );

                SemanticHit semantic = semanticByKey.get(key);
                IndexRepository.FullTextMatch fts = ftsByKey.get(key);
                AllowedRoot allowedRoot;
                ItemDescriptor descriptor;
                ItemPath path;
                int amount;
                if (semantic != null) {
                    allowedRoot = semantic.allowedRoot();
                    descriptor = semantic.item().descriptor();
                    path = semantic.item().path();
                    amount = semantic.item().amount();
                } else {
                    allowedRoot = ftsAllowedRoots.get(key.rootKey());
                    if (allowedRoot == null) {
                        continue;
                    }
                    descriptor = fts.descriptor();
                    path = fts.path();
                    amount = fts.amount();
                }

                itemsByRoot.computeIfAbsent(key.rootKey(), ignored -> new ArrayList<>())
                    .add(new ItemMatch(descriptor, path, displayScore, amount));
                allowedByRoot.putIfAbsent(key.rootKey(), allowedRoot);
            }

            if (itemsByRoot.isEmpty()) {
                return SearchOutcome.noMatches();
            }

            List<RootMatch> matchingRoots = new ArrayList<>();
            int totalMatchingStacks = 0;
            for (Map.Entry<BlockKey, List<ItemMatch>> entry : itemsByRoot.entrySet()) {
                AllowedRoot allowedRoot = allowedByRoot.get(entry.getKey());
                if (allowedRoot == null) {
                    continue;
                }
                List<ItemMatch> fusedItems = entry.getValue();
                fusedItems.sort(ITEM_COMPARATOR);
                int matchingStacksForRoot = fusedItems.size();
                totalMatchingStacks = Math.addExact(totalMatchingStacks, matchingStacksForRoot);
                List<ItemMatch> visibleItems = fusedItems.size() <= policy.maxPathsPerRoot()
                    ? List.copyOf(fusedItems)
                    : List.copyOf(fusedItems.subList(0, policy.maxPathsPerRoot()));

                matchingRoots.add(new RootMatch(
                    allowedRoot.identity(),
                    allowedRoot.distance(),
                    fusedItems.getFirst().score(),
                    matchingStacksForRoot,
                    visibleItems
                ));
            }

            matchingRoots.sort(ROOT_COMPARATOR);
            int totalMatchingRoots = matchingRoots.size();
            List<RootMatch> visibleRoots = matchingRoots.size() <= policy.maxResults()
                ? List.copyOf(matchingRoots)
                : List.copyOf(matchingRoots.subList(0, policy.maxResults()));

            return SearchOutcome.success(
                visibleRoots,
                totalMatchingRoots,
                totalMatchingStacks
            );
        }
    }

    private static final class CanceledSearchException extends RuntimeException {
    }

    private static final class UnsupportedQueryException extends RuntimeException {
    }
}
