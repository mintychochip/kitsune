package dev.jlo.kitsune.search;

import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.command.SearchRequest;
import dev.jlo.kitsune.index.IndexWarmupTimeoutException;
import dev.jlo.kitsune.index.IndexWorker;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.SemanticDescriptorHash;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;
import dev.jlo.kitsune.model.RootIdentity;
import dev.jlo.kitsune.search.AllowedRoot;
import dev.jlo.kitsune.search.IndexReadiness;
import dev.jlo.kitsune.search.LiveRootAccess;
import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchContext;
import dev.jlo.kitsune.search.SearchGuard;
import dev.jlo.kitsune.search.SearchOutcome;
import dev.jlo.kitsune.search.SearchPolicy;
import dev.jlo.kitsune.search.SearchService;
import dev.jlo.kitsune.search.ServerThreadBridge;
import dev.jlo.kitsune.session.SearchToken;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies search filtering, ranking, pagination, and access handling. */
class SearchServiceTest {
    private static final UUID WORLD_ID = UUID.nameUUIDFromBytes("search-world".getBytes(StandardCharsets.UTF_8));
    private static final UUID PLAYER_ID = UUID.nameUUIDFromBytes("search-player".getBytes(StandardCharsets.UTF_8));
    private static final BlockKey ORIGIN = new BlockKey(WORLD_ID, 0, 64, 0);

    @Test
    void deniedAndStaleRootsAreNeverPassedToLoadDocuments() throws Exception {
        RootSeed accessible = seedRoot(1, 64, 0, false, false, scoreMatch("allowed", 0.95, 0));
        RootSeed denied = seedRoot(3, 64, 0, true, false, scoreMatch("denied", 0.94, 0));
        RootSeed stale = seedRoot(-2, 64, 0, false, true, scoreMatch("stale", 0.92, 0));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), accessible, denied, stale)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, outcome.totalAccessibleMatchingRoots());
            assertEquals(List.of(accessible.identity().key()),
                outcome.roots().stream().map(RootMatch::key).toList());
            assertEquals(Set.of(accessible.identity().key()), harness.loadedDocumentKeys());
            assertTrue(harness.validatedKeys().contains(accessible.identity().key()));
            assertEquals(1, harness.loadDocumentCalls());
        }
    }

    @Test
    void noDeniedPathDataLeaksIntoSuccessfulSearchOutcome() throws Exception {
        RootSeed allowed = seedRoot(1, 64, 0, false, false,
            scoreMatch("allowed", 0.99, 0));
        RootSeed denied = seedRoot(2, 64, 0, true, false,
            scoreMatch("denied-stack", 0.98, 0));
        SearchPolicy policy = new SearchPolicy(16, 0.80, 10, 4, Duration.ofSeconds(1));

        try (SearchHarness harness = SearchHarness.create(policy, denied, allowed)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, outcome.roots().size());
            RootMatch matched = outcome.roots().getFirst();
            assertEquals(allowed.identity().key(), matched.key());
            assertEquals(1, matched.totalMatchingStacks());
            assertEquals(1, matched.itemMatches().size());
            assertNotEquals(denied.identity().key(), matched.key());
            assertEquals("allowed", matched.itemMatches().get(0).path().steps().getFirst().label());
            assertEquals(0.99, matched.bestScore());
            assertEquals(Set.of(allowed.identity().key()), harness.loadedDocumentKeys());
        }
    }


    @Test
    void ranksByScoreThenDistanceThenCoordinates() throws Exception {
        RootSeed nearestA = seedRoot(-1, 64, 0, false, false,
            scoreMatch("a", 0.95, 0));
        RootSeed nearestB = seedRoot(1, 64, 0, false, false,
            scoreMatch("b", 0.95, 1));
        RootSeed farther = seedRoot(3, 64, 0, false, false,
            scoreMatch("c", 0.80, 2));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), nearestA, nearestB, farther)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(List.of(
                    nearestA.identity().key(),
                    nearestB.identity().key(),
                    farther.identity().key()
                ),
                outcome.roots().stream().map(RootMatch::key).toList()
            );
        }
    }

    @Test
    void totalCountsExcludeDeniedAndRootsAreAppliedAfterAccess() throws Exception {
        SearchPolicy policy = new SearchPolicy(32, 0.75, 2, 4, Duration.ofSeconds(1));
        RootSeed topOne = seedRoot(-3, 64, 0, false, false, scoreMatch("a", 0.90, 0));
        RootSeed topTwo = seedRoot(-2, 64, 0, false, false, scoreMatch("b", 0.89, 0));
        RootSeed denied = seedRoot(-1, 64, 0, true, false, scoreMatch("c", 0.88, 0));
        RootSeed stale = seedRoot(6, 64, 0, false, true, scoreMatch("d", 0.87, 0));

        try (SearchHarness harness = SearchHarness.create(policy, topOne, topTwo, denied, stale)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(2, outcome.roots().size());
            assertEquals(2, outcome.totalAccessibleMatchingRoots());
            assertEquals(Set.of(topOne.identity().key(), topTwo.identity().key()), harness.loadedDocumentKeys());
        }
    }

    @Test
    void emptyCandidatePageContinuesWhenCursorExists() throws Exception {
        RootSeed packed = seedRoot(0, 64, 0, false, false, scoreMatch("a", 0.95, 0));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), packed)
            .emptyFirstCandidatePage()) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(1, outcome.totalAccessibleMatchingRoots());
            assertEquals(packed.identity().key(), outcome.roots().getFirst().key());
            assertEquals(2, harness.findCandidateCalls());
            assertEquals(List.of(0, 1), harness.candidatePageSizes());
        }
    }

    @Test
    void candidatePagesKeepGlobalCountsAndBestRoot() throws Exception {
        SearchPolicy policy = new SearchPolicy(32, 0.75, 1, 4, Duration.ofSeconds(1));
        List<RootSeed> roots = new ArrayList<>();
        for (int index = 0; index < 129; index++) {
            double score = index == 128 ? 0.99 : 0.80;
            roots.add(seedRoot(0, 64 + index, 0, false, false,
                scoreMatch("item-" + index, score, index)));
        }
        RootSeed lateBest = roots.get(128);

        try (SearchHarness harness = SearchHarness.create(policy, roots)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(129, outcome.totalAccessibleMatchingRoots());
            assertEquals(129, outcome.totalAccessibleMatchingStacks());
            assertEquals(lateBest.identity().key(), outcome.roots().getFirst().key());
            assertEquals(2, harness.findCandidateCalls());
            assertEquals(2, harness.loadDocumentCalls());
            assertEquals(List.of(128, 1), harness.candidatePageSizes());
            assertEquals(List.of(128, 1), harness.validationBatchSizes());
        }
    }

    @Test
    void cappedItemMatchesKeepHighestScoredPaths() throws Exception {
        RootSeed packed = seedRoot(0, 64, 0, false, false,
            scoreMatch("top", 0.95, 0),
            scoreMatch("middle", 0.84, 1),
            scoreMatch("low", 0.76, 2)
        );
        SearchPolicy policy = new SearchPolicy(32, 0.50, 3, 2, Duration.ofSeconds(1));

        try (SearchHarness harness = SearchHarness.create(policy, packed)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            RootMatch root = outcome.roots().getFirst();

            assertEquals(3, root.totalMatchingStacks());
            assertEquals(2, root.itemMatches().size());
            assertEquals(packed.identity().key(), root.key());
            assertEquals(0.95, root.bestScore());
            assertEquals(0.95, root.itemMatches().get(0).score());
            assertEquals(0.84, root.itemMatches().get(1).score());
        }
    }

    @Test
    void readyChunksAreForwardedExactlyToReadinessCheck() throws Exception {
        Set<ChunkKey> chunks = Set.of(
            new ChunkKey(WORLD_ID, 0, 0),
            new ChunkKey(WORLD_ID, 1, 0)
        );
        RootSeed packed = seedRoot(4, 64, 0, false, false, scoreMatch("a", 0.90, 0));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), packed)
            .withLoadedChunks(chunks)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
            assertEquals(List.of(chunks), harness.awaitedChunkRequests());
        }
    }

    @Test
    void zeroNormQueryReturnsUnsupportedQueryAndLoadsNothing() throws Exception {
        try (SearchHarness harness = SearchHarness.create(defaultPolicy())) {
            SearchOutcome outcome = harness.search("zero-norm");

            assertEquals(SearchOutcome.Status.UNSUPPORTED_QUERY, outcome.status());
            assertTrue(harness.awaitedChunkRequests().isEmpty());
            assertEquals(Set.of(), harness.loadedDocumentKeys());
            assertEquals(0, harness.loadDocumentCalls());
            assertEquals(0, harness.findCandidateCalls());
        }
    }

    @Test
    void warmupTimeoutReturnsIndexWarmingAndDoesNotLoadDocuments() throws Exception {
        SearchPolicy policy = new SearchPolicy(16, 0.40, 10, 4, Duration.ofMillis(250));
        RootSeed packed = seedRoot(2, 64, 0, false, false, scoreMatch("a", 0.99, 0));

        try (SearchHarness harness = SearchHarness.create(policy, packed)) {
            harness.failWarmup(new IndexWarmupTimeoutException("timed out"));

            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.INDEX_WARMING, outcome.status());
            assertEquals(Set.of(), harness.loadedDocumentKeys());
            assertEquals(1, harness.awaitedChunkRequests().size());
            assertEquals(0, harness.findCandidateCalls());

        }
    }

    @Test
    void repositoryOrProviderFailureMapsToFailureWithoutOutput() throws Exception {
        RootSeed packed = seedRoot(2, 64, 0, false, false, scoreMatch("a", 0.95, 0));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), packed)) {
            harness.failQueryEmbedding(new IllegalStateException("provider failed"));

            SearchOutcome outcome = harness.search("query-fails");

            assertEquals(SearchOutcome.Status.FAILURE, outcome.status());
            assertTrue(harness.awaitedChunkRequests().isEmpty());
            assertEquals(Set.of(), harness.loadedDocumentKeys());
            assertEquals(0, harness.findCandidateCalls());
        }
    }

    @Test
    void emptyScoresReturnNoMatchesButStillUseMatchingRoots() throws Exception {
        SearchPolicy policy = new SearchPolicy(16, 0.90, 10, 3, Duration.ofSeconds(1));
        RootSeed packed = seedRoot(2, 64, 0, false, false,
            scoreMatch("a", 0.70, 0),
            scoreMatch("b", 0.71, 1));

        try (SearchHarness harness = SearchHarness.create(policy, packed)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.NO_MATCHES, outcome.status());
            assertEquals(0, outcome.totalAccessibleMatchingRoots());
            assertEquals(0, outcome.roots().size());
            assertEquals(Set.of(packed.identity().key()), harness.loadedDocumentKeys());
        }
    }

    @Test
    void negativeCosineIsValidAndCanBeFiltered() throws Exception {
        SearchPolicy policy = new SearchPolicy(16, 0.0, 10, 3, Duration.ofSeconds(1));
        RootSeed packed = seedRoot(2, 64, 0, false, false,
            scoreMatch("opposite", -0.25, 0));

        try (SearchHarness harness = SearchHarness.create(policy, packed)) {
            SearchOutcome outcome = harness.search("diamond");

            assertEquals(SearchOutcome.Status.NO_MATCHES, outcome.status());
            assertEquals(0, outcome.totalAccessibleMatchingRoots());
        }
    }

    @Test
    void supersededTokenCancelsBeforeDocumentLoad() throws Exception {
        RootSeed packed = seedRoot(2, 64, 0, false, false, scoreMatch("a", 0.95, 0));

        try (SearchHarness harness = SearchHarness.create(defaultPolicy(), packed)
            .pauseValidation()) {
            CompletableFuture<SearchOutcome> first = harness.start("diamond");
            harness.awaitValidationStarted().get(5, TimeUnit.SECONDS);
            harness.supersede();
            harness.resumeValidation();

            SearchOutcome outcome = first.orTimeout(5, TimeUnit.SECONDS)
                .join();

            assertEquals(SearchOutcome.Status.CANCELED, outcome.status());
            assertEquals(Set.of(), harness.loadedDocumentKeys());
            assertEquals(0, harness.loadDocumentCalls());
        }
    }

    private static SearchPolicy defaultPolicy() {
        return new SearchPolicy(16, 0.75, 10, 4, Duration.ofSeconds(1));
    }

    private static RootSeed seedRoot(int x,
                                   int y,
                                   int z,
                                   boolean denied,
                                   boolean stale,
                                   ScoreMatch... matches) {
        BlockKey key = new BlockKey(WORLD_ID, x, y, z);
        RootIdentity identity = new RootIdentity(
            key,
            "minecraft:chest",
            ByteBuffer.allocate(8).putLong(0x5F4F6B7C_91D2E3F4L).array(),
            1L
        );

        return new RootSeed(identity, denied, stale, Arrays.asList(matches));
    }

    private static ScoreMatch scoreMatch(String path, double score, int slot) {
        return new ScoreMatch(path, score, slot, 1);
    }

    private record SearchHarness(
        SearchPolicy policy,
        SearchService service,
        FakeIndexRepository repository,
        FakeIndexReadiness readiness,
        FakeLiveRootAccess live,
        DeterministicEmbeddingProvider embeddings,
        IndexWorker worker,
        AtomicLong generation,
        SearchContext context,
        SearchGuard guard
    ) implements AutoCloseable {

        static SearchHarness create(SearchPolicy policy, RootSeed... roots) {
            return create(policy, Arrays.asList(roots));
        }
        static SearchHarness create(SearchPolicy policy, List<RootSeed> roots) {
            AtomicLong generation = new AtomicLong(0);
            ValidationBatchRecorder validationBatches = new ValidationBatchRecorder();
            FakeIndexReadiness readiness = new FakeIndexReadiness();
            FakeLiveRootAccess live = new FakeLiveRootAccess(
                Set.of(new ChunkKey(WORLD_ID, 0, 0)),
                validationBatches
            );
            for (RootSeed root : roots) {
                if (root.denied()) live.deny(root.identity().key());
                if (root.stale()) live.stale(root.identity().key());
            }
            DeterministicEmbeddingProvider embeddings = new DeterministicEmbeddingProvider();
            FakeIndexRepository repository = new FakeIndexRepository(roots, embeddings);
            IndexWorker worker = new IndexWorker(repository);
            SearchContext context = new SearchContext(PLAYER_ID, ORIGIN);

            SearchService service = new SearchService(
                readiness,
                worker,
                repository,
                embeddings,
                new ImmediateServerThreadBridge(validationBatches),
                live,
                policy
            );

            SearchGuard guard = token ->
                token.generation() == generation.get() && token.playerId().equals(PLAYER_ID);
            return new SearchHarness(
                policy,
                service,
                repository,
                readiness,
                live,
                embeddings,
                worker,
                generation,
                context,
                guard
            );
        }

        SearchHarness withLoadedChunks(Set<ChunkKey> chunks) {
            live.setLoadedChunks(chunks);
            return this;
        }

        SearchHarness emptyFirstCandidatePage() {
            repository.emptyFirstPage();
            return this;
        }

        SearchHarness failWarmup(Throwable failure) {
            readiness.fail(failure);
            return this;
        }

        SearchHarness failQueryEmbedding(RuntimeException failure) {
            embeddings.failQuery(failure);
            return this;
        }

        SearchHarness pauseValidation() {
            live.pauseValidation();
            return this;
        }

        void resumeValidation() {
            live.resumeValidation();
        }

        CompletableFuture<Void> awaitValidationStarted() {
            return live.validationStarted();
        }

        CompletableFuture<SearchOutcome> start(String query) {
            SearchToken token = new SearchToken(PLAYER_ID, generation.get());
            SearchRequest request = new SearchRequest(query, false);
            return service.search(context, request, token, guard);
        }

        SearchOutcome search(String query) {
            try {
                return start(query)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof AssertionError assertion) {
                    throw assertion;
                }
                throw failure;
            }
        }

        Set<BlockKey> loadedDocumentKeys() {
            return repository.loadedDocumentKeys();
        }

        Set<BlockKey> validatedKeys() {
            return live.validated();
        }

        int findCandidateCalls() {
            return repository.findCandidateCalls();
        }

        int loadDocumentCalls() {
            return repository.loadDocumentCalls();
        }

        List<Integer> validationBatchSizes() {
            return live.validationBatchSizes();
        }

        List<Integer> candidatePageSizes() {
            return repository.candidatePageSizes();
        }

        List<Set<ChunkKey>> awaitedChunkRequests() {
            return readiness.awaitedChunks();
        }

        void supersede() {
            generation.incrementAndGet();
        }

        @Override
        public void close() throws Exception {
            worker.close();
        }
    }

    private static final class FakeIndexReadiness implements IndexReadiness {
        private final List<Set<ChunkKey>> awaited = new ArrayList<>();
        private volatile Throwable failure;

        void fail(Throwable failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> awaitReady(Set<ChunkKey> chunks, Duration timeout) {
            awaited.add(Set.copyOf(chunks));
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }

        List<Set<ChunkKey>> awaitedChunks() {
            return Collections.unmodifiableList(awaited);
        }
    }

    private static final class ValidationBatchRecorder {
        private final ThreadLocal<Integer> current = new ThreadLocal<>();
        private final List<Integer> completed = Collections.synchronizedList(new ArrayList<>());

        void begin() {
            current.set(0);
        }

        void recordValidation() {
            Integer count = current.get();
            if (count == null) {
                throw new IllegalStateException("Validation occurred outside server bridge operation");
            }
            current.set(count + 1);
        }

        void finish() {
            Integer count = current.get();
            current.remove();
            if (count != null && count > 0) {
                completed.add(count);
            }
        }

        List<Integer> completed() {
            return List.copyOf(completed);
        }
    }

    private static final class FakeLiveRootAccess implements LiveRootAccess {
        private Set<ChunkKey> loadedChunks;
        private final ValidationBatchRecorder validationBatches;
        private final Set<BlockKey> deniedRoots = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<BlockKey> staleRoots = Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<BlockKey> validated = Collections.synchronizedSet(new LinkedHashSet<>());
        private final AtomicReference<CompletableFuture<Void>> validationGate = new AtomicReference<>();
        private final CompletableFuture<Void> validationStarted = new CompletableFuture<>();

        FakeLiveRootAccess(
            Set<ChunkKey> loadedChunks,
            ValidationBatchRecorder validationBatches
        ) {
            this.loadedChunks = Objects.requireNonNull(loadedChunks, "loaded chunks");
            this.validationBatches = Objects.requireNonNull(
                validationBatches,
                "validation batches"
            );
            validationGate.set(CompletableFuture.completedFuture(null));
        }

        void setLoadedChunks(Set<ChunkKey> loadedChunks) {
            this.loadedChunks = Objects.requireNonNull(loadedChunks, "loaded chunks");
        }

        @Override
        public Set<ChunkKey> loadedChunks(SearchContext context, int radius) {
            return loadedChunks;
        }

        @Override
        public AllowedRoot validate(SearchContext context, RootIdentity identity, int radius) {
            validationBatches.recordValidation();
            validated.add(identity.key());
            if (deniedRoots.contains(identity.key()) || staleRoots.contains(identity.key())) {
                return null;
            }
            validationStarted.complete(null);
            validationGate.get().join();

            double distance = Math.abs(identity.key().x() - context.origin().x())
                + Math.abs(identity.key().z() - context.origin().z());

            if (distance > radius) {
                return null;
            }

            return new AllowedRoot(identity, distance);
        }

        CompletableFuture<Void> validationStarted() {
            return validationStarted;
        }

        Set<BlockKey> validated() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(validated));
        }

        List<Integer> validationBatchSizes() {
            return validationBatches.completed();
        }

        void pauseValidation() {
            validationGate.set(new CompletableFuture<>());
        }

        void resumeValidation() {
            validationGate.get().complete(null);
        }

        void deny(BlockKey root) {
            deniedRoots.add(root);
        }

        void stale(BlockKey root) {
            staleRoots.add(root);
        }
    }

    private static final class FakeIndexRepository implements IndexRepository {
        private final Map<BlockKey, List<CandidateItem>> itemsByRoot = new LinkedHashMap<>();
        private final Set<BlockKey> loadedKeys = Collections.synchronizedSet(new LinkedHashSet<>());
        private final List<RootIdentity> candidates;
        private final DeterministicEmbeddingProvider embeddings;
        private final List<Integer> pageSizes = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean emptyFirstPage;
        private volatile int findCalls;
        private volatile int loadCalls;
        FakeIndexRepository(List<RootSeed> roots, DeterministicEmbeddingProvider embeddings) {
            this.embeddings = embeddings;
            this.candidates = roots.stream()
                .map(RootSeed::identity)
                .sorted(Comparator
                    .comparing((RootIdentity root) -> root.key().worldId())
                    .thenComparingInt(root -> root.key().x())
                    .thenComparingInt(root -> root.key().y())
                    .thenComparingInt(root -> root.key().z()))
                .toList();
            for (RootSeed seed : roots) {
                List<CandidateItem> stored = seed.matches().stream()
                    .map(match -> new CandidateItem(
                        descriptorForPath(match),
                        match.amount(),
                        new ItemPath(List.of(new ItemPathStep(match.path(), match.slot()))),
                        match.slot(),
                        match.score()
                    ))
                    .toList();
                itemsByRoot.put(seed.identity().key(), stored);
            }
        }

        Set<BlockKey> loadedDocumentKeys() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(loadedKeys));
        }

        int findCandidateCalls() {
            return findCalls;
        }

        int loadDocumentCalls() {
            return loadCalls;
        }

        List<Integer> candidatePageSizes() {
            return List.copyOf(pageSizes);
        }

        void emptyFirstPage() {
            emptyFirstPage = true;
        }

        @Override
        public void migrate() throws SQLException {
            throw new UnsupportedOperationException("IndexRepository is a fake");
        }

        @Override
        public void markAllChunksUnavailable() throws SQLException {
            // no-op
        }

        @Override
        public void setChunkAvailable(ChunkKey chunk, boolean available, long revision) {
            // no-op
        }

        @Override
        public void replaceRoot(dev.jlo.kitsune.model.ContainerSnapshot snapshot, long revision) {
            // no-op
        }

        @Override
        public void deleteRoot(BlockKey key) {
            // no-op
        }

        @Override
        public IndexRepository.CandidatePage findCandidates(
                                                UUID worldId,
                                                int minChunkX,
                                                int maxChunkX,
                                                int minChunkZ,
                                                int maxChunkZ,
                                                IndexRepository.CandidateCursor after,
                                                int limit) {
            findCalls += 1;
            if (emptyFirstPage && findCalls == 1) {
                emptyFirstPage = false;
                pageSizes.add(0);
                return new IndexRepository.CandidatePage(
                    List.of(),
                    new IndexRepository.CandidateCursor(-1, Integer.MIN_VALUE, Integer.MIN_VALUE)
                );
            }
            int start = 0;
            if (after != null) {
                while (start < candidates.size()
                        && compareCursor(candidates.get(start), after) <= 0) {
                    start++;
                }
            }
            int end = Math.min(start + limit, candidates.size());
            List<RootIdentity> page = List.copyOf(candidates.subList(start, end));
            pageSizes.add(page.size());
            IndexRepository.CandidateCursor next = end < candidates.size() && !page.isEmpty()
                ? cursor(page.getLast())
                : null;
            return new IndexRepository.CandidatePage(page, next);
        }

        @Override
        public java.util.Optional<RootIdentity> findRoot(BlockKey key) {
            return java.util.Optional.empty();
        }

        @Override
        public Map<SemanticDescriptorHash, Embedding> findEmbeddings(
                EmbeddingProvider provider,
                Set<SemanticDescriptorHash> hashes
        ) {
            return Map.of();
        }

        @Override
        public void putEmbeddings(
                EmbeddingProvider provider,
                Map<SemanticDescriptorHash, Embedding> embeddings
        ) {}

        private static int compareCursor(
                RootIdentity root,
                IndexRepository.CandidateCursor cursor
        ) {
            int x = Integer.compare(root.key().x(), cursor.x());
            if (x != 0) return x;
            int y = Integer.compare(root.key().y(), cursor.y());
            if (y != 0) return y;
            return Integer.compare(root.key().z(), cursor.z());
        }

        private static IndexRepository.CandidateCursor cursor(RootIdentity root) {
            return new IndexRepository.CandidateCursor(
                root.key().x(),
                root.key().y(),
                root.key().z()
            );
        }

        @Override
        public Map<BlockKey, List<IndexedItem>> loadDocuments(Set<BlockKey> allowed, EmbeddingProvider provider) {
            loadCalls += 1;
            loadedKeys.clear();
            loadedKeys.addAll(allowed);

            Map<BlockKey, List<IndexedItem>> loaded = new LinkedHashMap<>();
            for (BlockKey key : allowed) {
                List<CandidateItem> staged = itemsByRoot.get(key);
                if (staged == null) continue;
                List<IndexedItem> indexed = staged.stream()
                    .map(item -> {
                        Embedding embedded = embeddings.embeddingFor(item.descriptor().materialKey(), item.score());
                        return new IndexedItem(
                            item.path(),
                            item.amount(),
                            item.descriptor(),
                            embedded
                        );
                    })
                    .toList();
                loaded.put(key, indexed);
            }

            return loaded;
        }

        @Override
        public void reembedAll(EmbeddingProvider provider) {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }

        private static ItemDescriptor descriptorForPath(ScoreMatch match) {
            return ItemDescriptor.builder()
                .materialKey("test/item/%s".formatted(match.path()))
                .amount(match.amount())
                .addCustomTag("search-test")
                .build();
        }
    }

    private static final class ImmediateServerThreadBridge implements ServerThreadBridge {
        private final ValidationBatchRecorder validationBatches;

        private ImmediateServerThreadBridge(ValidationBatchRecorder validationBatches) {
            this.validationBatches = validationBatches;
        }

        @Override
        public <T> CompletableFuture<T> supply(java.util.concurrent.Callable<T> operation) {
            validationBatches.begin();
            try {
                return CompletableFuture.completedFuture(operation.call());
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            } finally {
                validationBatches.finish();
            }
        }

        @Override
        public CompletableFuture<Void> run(Runnable operation) {
            try {
                operation.run();
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private static final class DeterministicEmbeddingProvider implements EmbeddingProvider {
        private volatile RuntimeException queryFailure;

        void failQuery(RuntimeException failure) {
            this.queryFailure = failure;
        }

        @Override
        public String id() {
            return "test-provider";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Embedding embed(ItemDescriptor descriptor) {
            return VectorEmbedding.of(0.0, 0.0, descriptor.materialKey());
        }

        @Override
        public Embedding embedQuery(String query) {
            if (queryFailure != null) {
                throw queryFailure;
            }
            if (query.equals("zero-norm")) {
                return VectorEmbedding.of(0.0, 0.0, "query:zero-norm");
            }
            return VectorEmbedding.of(1.0, 0.0, "query:" + query);
        }

        @Override
        public Embedding decode(byte[] payload, double norm) {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            double first = buffer.getDouble();
            double second = buffer.getDouble();
            return VectorEmbedding.of(first, second, Base64.getEncoder().encodeToString(payload));
        }

        VectorEmbedding embeddingFor(String label, double score) {
            if (Math.abs(score) > 1.0) {
                throw new IllegalArgumentException("Score must fit cosine range");
            }
            double x = score;
            double y = Math.sqrt(Math.max(0.0, 1.0 - score * score));
            return VectorEmbedding.of(x, y, label);
        }
    }

    private static final record VectorEmbedding(double[] coordinates, String key, String providerId, int providerVersion) implements Embedding {
        VectorEmbedding {
            coordinates = coordinates.clone();
        }

        static VectorEmbedding of(double x, double y, String key) {
            return new VectorEmbedding(new double[] {x, y}, key, "test-provider", 1);
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public int providerVersion() {
            return providerVersion;
        }

        @Override
        public double norm() {
            return Math.sqrt((coordinates[0] * coordinates[0]) + (coordinates[1] * coordinates[1]));
        }

        @Override
        public byte[] encode() {
            ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES * 2);
            buffer.putDouble(coordinates[0]);
            buffer.putDouble(coordinates[1]);
            return buffer.array();
        }

        @Override
        public double cosine(Embedding other) {
            if (!(other instanceof VectorEmbedding comparable)) {
                throw new IllegalArgumentException("Unknown embedding implementation: " + other.getClass());
            }
            double otherNorm = comparable.norm();
            if (norm() == 0.0 || otherNorm == 0.0) {
                return 0.0;
            }
            double dot = (coordinates[0] * comparable.coordinates[0]) + (coordinates[1] * comparable.coordinates[1]);
            return dot / (norm() * otherNorm);
        }
    }

    private static final record CandidateItem(ItemDescriptor descriptor,
                                            int amount,
                                            ItemPath path,
                                            int slot,
                                            double score) {
    }

    private static final record RootSeed(RootIdentity identity, boolean denied, boolean stale, List<ScoreMatch> matches) {
        RootSeed(RootIdentity identity, boolean denied, boolean stale, List<ScoreMatch> matches) {
            this.identity = identity;
            this.denied = denied;
            this.stale = stale;
            this.matches = List.copyOf(matches);
        }
    }

    private static final record ScoreMatch(String path, double score, int slot, int amount) {
    }
}
