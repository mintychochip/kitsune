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
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.session.SearchToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class SearchService {
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

    private final IndexReadiness readiness;
    private final IndexWorker worker;
    private final IndexRepository repository;
    private final EmbeddingProvider embeddings;
    private final ServerThreadBridge serverBridge;
    private final LiveRootAccess liveRootAccess;
    private final SearchPolicy policy;

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

        CompletableFuture<SearchOutcome> pipeline = embedQuery(context, request, token, guard)
            .thenCompose(queryEmbedding -> loadedChunks(context, token, guard)
                .thenCompose(chunks -> {
                    requireCurrent(context, token, guard);
                    if (chunks.isEmpty()) {
                        return CompletableFuture.completedFuture(SearchOutcome.noMatches());
                    }
                    return awaitReady(context, token, guard, chunks)
                        .thenCompose(ignored -> findCandidates(context, token, guard))
                        .thenCompose(candidates -> {
                            requireCurrent(context, token, guard);
                            if (candidates.isEmpty()) {
                                return CompletableFuture.completedFuture(SearchOutcome.noMatches());
                            }
                            return validateCandidates(context, token, guard, candidates)
                                .thenCompose(allowedRoots -> {
                                    requireCurrent(context, token, guard);
                                    if (allowedRoots.isEmpty()) {
                                        return CompletableFuture.completedFuture(SearchOutcome.noMatches());
                                    }
                                    return loadAndRank(
                                        context,
                                        token,
                                        guard,
                                        queryEmbedding,
                                        allowedRoots
                                    );
                                });
                        });
                }));

        return pipeline.handle((outcome, failure) -> mapOutcome(context, token, guard, outcome, failure));
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

    private CompletableFuture<List<RootIdentity>> findCandidates(
        SearchContext context,
        SearchToken token,
        SearchGuard guard
    ) {
        BlockKey origin = context.origin();
        int radius = policy.radius();
        int minChunkX = chunkCoordinate((long) origin.x() - radius);
        int maxChunkX = chunkCoordinate((long) origin.x() + radius);
        int minChunkZ = chunkCoordinate((long) origin.z() - radius);
        int maxChunkZ = chunkCoordinate((long) origin.z() + radius);

        return worker.submit(() -> {
            requireCurrent(context, token, guard);
            List<RootIdentity> candidates = repository.findCandidates(
                origin.worldId(),
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ
            );
            requireCurrent(context, token, guard);
            return List.copyOf(Objects.requireNonNull(candidates, "Candidates must not be null"));
        }).thenApply(candidates -> {
            requireCurrent(context, token, guard);
            return candidates;
        });
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

    private CompletableFuture<SearchOutcome> loadAndRank(
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
            SearchOutcome outcome = rankDocuments(
                Objects.requireNonNull(documents, "Documents must not be null"),
                queryEmbedding,
                allowedRoots
            );
            requireCurrent(context, token, guard);
            return outcome;
        }).thenApply(outcome -> {
            requireCurrent(context, token, guard);
            return outcome;
        });
    }

    private SearchOutcome rankDocuments(
        Map<BlockKey, List<IndexedItem>> documents,
        Embedding queryEmbedding,
        Map<BlockKey, AllowedRoot> allowedRoots
    ) {
        List<RootMatch> matchingRoots = new ArrayList<>();
        int totalMatchingStacks = 0;

        for (AllowedRoot allowedRoot : allowedRoots.values()) {
            List<IndexedItem> items = documents.get(allowedRoot.identity().key());
            if (items == null || items.isEmpty()) {
                continue;
            }

            List<ItemMatch> matchingItems = new ArrayList<>();
            for (IndexedItem item : items) {
                Objects.requireNonNull(item, "Indexed item must not be null");
                double score = item.embedding().cosine(queryEmbedding);
                if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                    throw new IllegalStateException("Embedding provider returned an invalid cosine score");
                }
                if (score >= policy.minimumScore()) {
                    matchingItems.add(new ItemMatch(
                        item.descriptor(),
                        item.path(),
                        score,
                        item.amount()
                    ));
                }
            }
            if (matchingItems.isEmpty()) {
                continue;
            }

            matchingItems.sort(ITEM_COMPARATOR);
            int matchingStacksForRoot = matchingItems.size();
            totalMatchingStacks = Math.addExact(totalMatchingStacks, matchingStacksForRoot);
            List<ItemMatch> visibleItems = matchingItems.size() <= policy.maxPathsPerRoot()
                ? List.copyOf(matchingItems)
                : List.copyOf(matchingItems.subList(0, policy.maxPathsPerRoot()));

            matchingRoots.add(new RootMatch(
                allowedRoot.identity(),
                allowedRoot.distance(),
                matchingItems.getFirst().score(),
                matchingStacksForRoot,
                visibleItems
            ));
        }

        if (matchingRoots.isEmpty()) {
            return SearchOutcome.noMatches();
        }

        matchingRoots.sort(ROOT_COMPARATOR);
        int totalMatchingRoots = matchingRoots.size();
        List<RootMatch> visibleRoots = matchingRoots.size() <= policy.maxResults()
            ? List.copyOf(matchingRoots)
            : List.copyOf(matchingRoots.subList(0, policy.maxResults()));
        return SearchOutcome.success(visibleRoots, totalMatchingRoots, totalMatchingStacks);
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

    private static final class CanceledSearchException extends RuntimeException {
    }

    private static final class UnsupportedQueryException extends RuntimeException {
    }
}
