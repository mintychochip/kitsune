package org.aincraft.kitsune.storage.vector;

import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.disk.ReaderSupplierFactory;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JVector-based implementation of VectorIndex for approximate nearest neighbor search.
 * Uses an in-memory vector store with an on-disk graph index for persistence.
 *
 * Architecture:
 * - In-memory: Map of databaseOrdinal -> VectorFloat<?>
 * - On-disk: vectors.idx containing the HNSW graph and vector data
 * - Thread-safe: ReadWriteLock ensures safe concurrent access
 * - Lazy rebuild: Index marked dirty on mutations, rebuilt before next search
 * - Ordinal mapping: Internal JVector ordinals (0,1,2...) mapped to database ordinals
 *
 * Graph parameters (configurable):
 * - GRAPH_DEGREE = 16 (max neighbors per node)
 * - CONSTRUCTION_SEARCH_DEPTH = 100 (search depth during graph construction)
 * - OVERFLOW_FACTOR = 1.2f (tolerance for degree overflow during construction)
 * - ALPHA = 1.2f (used in distance calculations for graph pruning)
 */
public final class JVectorIndex implements VectorIndex {

    private static final int GRAPH_DEGREE = 16;
    private static final int CONSTRUCTION_SEARCH_DEPTH = 100;
    private static final float OVERFLOW_FACTOR = 1.2f;
    private static final float ALPHA = 1.2f;

    private final Logger logger;
    private final Path dataDir;
    private final int dimension;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    // Map: database ordinal -> vector (sparse, preserves original ordinals)
    private final Map<Integer, VectorFloat<?>> vectorMap = new HashMap<>();

    // Mapping: internal ordinal (0,1,2...) -> database ordinal
    private final List<Integer> internalToDatabaseOrdinal = new ArrayList<>();

    // Reverse mapping: database ordinal -> internal ordinal
    private final Map<Integer, Integer> databaseToInternalOrdinal = new HashMap<>();

    // On-disk graph index state
    private OnDiskGraphIndex graphIndex;
    private ReaderSupplier readerSupplier;

    // Track if index needs rebuilding after mutations
    private volatile boolean indexDirty = false;

    /**
     * Create a new JVectorIndex.
     *
     * @param logger Logger for diagnostic output
     * @param dataDir Path to directory for storing index files
     * @param dimension Dimension of vectors to be indexed
     */
    public JVectorIndex(Logger logger, Path dataDir, int dimension) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.dimension = dimension;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dataDir);
                loadIndex();
                logger.info("JVectorIndex initialized at " + dataDir.toAbsolutePath());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize JVectorIndex", e);
                throw new RuntimeException("JVectorIndex initialization failed", e);
            }
        }, executor);
    }

    /**
     * Load existing index from disk if available.
     * Gracefully handles missing or corrupted index files by marking for rebuild.
     */
    private void loadIndex() {
        Path indexPath = dataDir.resolve("vectors.idx");
        Path mappingPath = dataDir.resolve("ordinals.map");

        if (Files.exists(indexPath) && Files.exists(mappingPath)) {
            try {
                // Load ordinal mapping first
                List<String> lines = Files.readAllLines(mappingPath);
                internalToDatabaseOrdinal.clear();
                databaseToInternalOrdinal.clear();

                for (int internalOrdinal = 0; internalOrdinal < lines.size(); internalOrdinal++) {
                    int dbOrdinal = Integer.parseInt(lines.get(internalOrdinal).trim());
                    internalToDatabaseOrdinal.add(dbOrdinal);
                    databaseToInternalOrdinal.put(dbOrdinal, internalOrdinal);
                }

                readerSupplier = ReaderSupplierFactory.open(indexPath);
                graphIndex = OnDiskGraphIndex.load(readerSupplier);

                int graphSize = graphIndex.size();
                logger.info("Loaded JVectorIndex graph with " + graphSize + " nodes");

                // Load vectors from graph's view into map
                var graphView = graphIndex.getView();
                vectorMap.clear();

                for (int internalOrdinal = 0; internalOrdinal < graphSize && internalOrdinal < internalToDatabaseOrdinal.size(); internalOrdinal++) {
                    try {
                        VectorFloat<?> vec = graphView.getVector(internalOrdinal);
                        if (vec != null) {
                            int dbOrdinal = internalToDatabaseOrdinal.get(internalOrdinal);
                            vectorMap.put(dbOrdinal, vec);
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to load vector for internal ordinal " + internalOrdinal + ": " + e.getMessage());
                    }
                }

                logger.info("Loaded " + vectorMap.size() + " vectors from JVectorIndex graph");
            } catch (Exception e) {
                logger.warning("Failed to load existing index, will rebuild: " + e.getMessage());
                cleanupGraphIndex();
                indexDirty = true;
            }
        } else if (Files.exists(indexPath)) {
            // Index exists but no mapping - need to rebuild
            logger.warning("Index exists but ordinal mapping missing, will rebuild");
            cleanupGraphIndex();
            indexDirty = true;
        }
    }

    // TODO: PERF - addVector is just a HashMap put but callers block on .join()
    // Consider: fire-and-forget returns, or addVectorsBatch() for bulk operations
    @Override
    public CompletableFuture<Void> addVector(int databaseOrdinal, float[] embedding) {
        return CompletableFuture.runAsync(() -> {
            indexLock.writeLock().lock();
            try {
                vectorMap.put(databaseOrdinal, toVectorFloat(embedding));
                indexDirty = true;
                logger.fine("Added vector at database ordinal " + databaseOrdinal);
            } finally {
                indexLock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeVector(int databaseOrdinal) {
        return CompletableFuture.runAsync(() -> {
            indexLock.writeLock().lock();
            try {
                vectorMap.remove(databaseOrdinal);
                indexDirty = true;
                logger.fine("Removed vector at database ordinal " + databaseOrdinal);
            } finally {
                indexLock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<VectorSearchResult>> search(float[] queryEmbedding, int limit) {
        return search(queryEmbedding, limit, null);
    }

    @Override
    public CompletableFuture<List<VectorSearchResult>> searchWithFilter(
            float[] queryEmbedding, int limit, Set<Integer> allowedOrdinals) {
        return search(queryEmbedding, limit, allowedOrdinals);
    }

    /**
     * Internal search implementation supporting both filtered and unfiltered search.
     *
     * @param queryEmbedding the query vector
     * @param limit maximum results
     * @param allowedDatabaseOrdinals optional set of DATABASE ordinals to filter by; null means no filtering
     * @return list of search results with DATABASE ordinals
     */
    // TODO: PERF - Search triggers full rebuild if indexDirty, causing unpredictable latency
    // Current: Search blocks on O(n log n) graph rebuild if any writes occurred
    // Fix: Background scheduled rebuild, or accept stale index with dirty flag check
    private CompletableFuture<List<VectorSearchResult>> search(
            float[] queryEmbedding, int limit, Set<Integer> allowedDatabaseOrdinals) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: PERF - Inline rebuild on search path causes latency spikes
            // Rebuild index if needed - BEFORE acquiring read lock to avoid deadlock
            if (indexDirty || graphIndex == null) {
                indexLock.writeLock().lock();
                try {
                    // Double-check after acquiring lock
                    if (indexDirty || graphIndex == null) {
                        rebuildIndexInternal();
                    }
                } finally {
                    indexLock.writeLock().unlock();
                }
            }

            indexLock.readLock().lock();
            try {
                if (graphIndex == null || vectorMap.isEmpty()) {
                    logger.info("Search aborted: graphIndex=" + (graphIndex != null)
                            + ", vectorCount=" + vectorMap.size());
                    return Collections.emptyList();
                }

                VectorFloat<?> queryVector = toVectorFloat(queryEmbedding);
                List<VectorSearchResult> results = new ArrayList<>();

                // TODO: PERF - Allocates new ArrayList on every search (hot path)
                // Consider: Reuse/cached vector list, or lazy initialization
                // Build list of vectors for search (in internal ordinal order)
                List<VectorFloat<?>> searchVectors = new ArrayList<>();
                for (int i = 0; i < internalToDatabaseOrdinal.size(); i++) {
                    int dbOrdinal = internalToDatabaseOrdinal.get(i);
                    searchVectors.add(vectorMap.get(dbOrdinal));
                }

                try (GraphSearcher searcher = new GraphSearcher(graphIndex)) {
                    ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(searchVectors, dimension);
                    DefaultSearchScoreProvider ssp = DefaultSearchScoreProvider.exact(
                            queryVector, VectorSimilarityFunction.COSINE, ravv
                    );

                    // Convert database ordinal filter to internal ordinal filter
                    Bits filterBits;
                    if (allowedDatabaseOrdinals != null && !allowedDatabaseOrdinals.isEmpty()) {
                        // Create filter for internal ordinals that map to allowed database ordinals
                        filterBits = internalOrdinal -> {
                            if (internalOrdinal >= internalToDatabaseOrdinal.size()) return false;
                            int dbOrdinal = internalToDatabaseOrdinal.get(internalOrdinal);
                            return allowedDatabaseOrdinals.contains(dbOrdinal);
                        };
                    } else {
                        filterBits = Bits.ALL;
                    }

                    int searchLimit = Math.min(limit * 10, searchVectors.size());
                    SearchResult sr = searcher.search(ssp, searchLimit, filterBits);

                    // Convert search results: internal ordinal -> database ordinal
                    for (SearchResult.NodeScore nodeScore : sr.getNodes()) {
                        int internalOrdinal = nodeScore.node;

                        // Skip if out of range
                        if (internalOrdinal >= internalToDatabaseOrdinal.size()) {
                            logger.warning("Skipping stale internal ordinal " + internalOrdinal);
                            continue;
                        }

                        int databaseOrdinal = internalToDatabaseOrdinal.get(internalOrdinal);

                        // Verify vector still exists
                        if (vectorMap.containsKey(databaseOrdinal)) {
                            results.add(new VectorSearchResult(databaseOrdinal, nodeScore.score));
                            if (results.size() >= limit) {
                                break;
                            }
                        }
                    }
                }

                logger.fine("Search complete: " + results.size() + " results returned");
                return results;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Search failed", e);
                return Collections.emptyList();
            } finally {
                indexLock.readLock().unlock();
            }
        }, executor);
    }

    @Override
    public Optional<float[]> getVector(int databaseOrdinal) {
        indexLock.readLock().lock();
        try {
            VectorFloat<?> vf = vectorMap.get(databaseOrdinal);
            if (vf != null) {
                float[] arr = new float[dimension];
                for (int i = 0; i < Math.min(dimension, vf.length()); i++) {
                    arr[i] = vf.get(i);
                }
                return Optional.of(arr);
            }
            return Optional.empty();
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Void> rebuildIndex() {
        return CompletableFuture.runAsync(() -> {
            indexLock.writeLock().lock();
            try {
                rebuildIndexInternal();
            } finally {
                indexLock.writeLock().unlock();
            }
        }, executor);
    }

    /**
     * Internal index rebuild logic - must be called with write lock held.
     * Builds contiguous index with ordinal mapping to preserve database ordinals.
     */
    private void rebuildIndexInternal() {
        try {
            if (!indexDirty && graphIndex != null) {
                return;
            }

            if (vectorMap.isEmpty()) {
                logger.info("No vectors to index, clearing index");
                cleanupGraphIndex();
                internalToDatabaseOrdinal.clear();
                databaseToInternalOrdinal.clear();
                indexDirty = false;
                return;
            }

            logger.info("Rebuilding JVectorIndex with " + vectorMap.size() + " vectors");

            // Delete old index files
            Path indexPath = dataDir.resolve("vectors.idx");
            Path mappingPath = dataDir.resolve("ordinals.map");
            cleanupGraphIndex();
            Files.deleteIfExists(indexPath);
            Files.deleteIfExists(mappingPath);

            // Build sorted list of database ordinals for consistent ordering
            List<Integer> sortedDbOrdinals = new ArrayList<>(vectorMap.keySet());
            Collections.sort(sortedDbOrdinals);

            // Build mapping and vector list
            internalToDatabaseOrdinal.clear();
            databaseToInternalOrdinal.clear();
            List<VectorFloat<?>> indexedVectors = new ArrayList<>();

            for (int internalOrdinal = 0; internalOrdinal < sortedDbOrdinals.size(); internalOrdinal++) {
                int dbOrdinal = sortedDbOrdinals.get(internalOrdinal);
                internalToDatabaseOrdinal.add(dbOrdinal);
                databaseToInternalOrdinal.put(dbOrdinal, internalOrdinal);
                indexedVectors.add(vectorMap.get(dbOrdinal));
            }

            // Build graph index
            ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(indexedVectors, dimension);
            BuildScoreProvider bsp = BuildScoreProvider.randomAccessScoreProvider(
                    ravv, VectorSimilarityFunction.COSINE
            );

            try (GraphIndexBuilder builder = new GraphIndexBuilder(
                    bsp, dimension, GRAPH_DEGREE, CONSTRUCTION_SEARCH_DEPTH,
                    OVERFLOW_FACTOR, ALPHA,
                    false)) {

                var index = builder.build(ravv);

                // Write index to disk
                OnDiskGraphIndex.write(index, ravv, indexPath);

                // Write ordinal mapping to disk
                List<String> mappingLines = new ArrayList<>();
                for (int dbOrdinal : internalToDatabaseOrdinal) {
                    mappingLines.add(String.valueOf(dbOrdinal));
                }
                Files.write(mappingPath, mappingLines);

                // Reload from disk
                readerSupplier = ReaderSupplierFactory.open(indexPath);
                graphIndex = OnDiskGraphIndex.load(readerSupplier);

                indexDirty = false;
                logger.info("JVectorIndex rebuilt successfully with " + indexedVectors.size() + " vectors");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to rebuild index", e);
            throw new RuntimeException("Index rebuild failed", e);
        }
    }

    /**
     * Clean up graph index resources.
     */
    private void cleanupGraphIndex() {
        if (readerSupplier != null) {
            try {
                readerSupplier.close();
            } catch (Exception ignored) {}
            readerSupplier = null;
        }
        graphIndex = null;
    }

    @Override
    public CompletableFuture<Void> purgeAll() {
        return CompletableFuture.runAsync(() -> {
            indexLock.writeLock().lock();
            try {
                vectorMap.clear();
                internalToDatabaseOrdinal.clear();
                databaseToInternalOrdinal.clear();

                cleanupGraphIndex();

                Path indexPath = dataDir.resolve("vectors.idx");
                Path mappingPath = dataDir.resolve("ordinals.map");
                Files.deleteIfExists(indexPath);
                Files.deleteIfExists(mappingPath);

                indexDirty = false;
                logger.info("Purged all vectors from JVectorIndex");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to purge all vectors", e);
                throw new RuntimeException("Purge failed", e);
            } finally {
                indexLock.writeLock().unlock();
            }
        }, executor);
    }

    @Override
    public int size() {
        indexLock.readLock().lock();
        try {
            return vectorMap.size();
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public boolean isDirty() {
        return indexDirty;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public void shutdown() {
        try {
            if (indexDirty) {
                indexLock.writeLock().lock();
                try {
                    rebuildIndexInternal();
                } finally {
                    indexLock.writeLock().unlock();
                }
            }

            cleanupGraphIndex();

            logger.info("JVectorIndex shut down successfully");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during shutdown", e);
        }
        executor.shutdown();
    }

    /**
     * Convert a float array to JVector's VectorFloat type.
     */
    private VectorFloat<?> toVectorFloat(float[] array) {
        return io.github.jbellis.jvector.vector.VectorizationProvider
                .getInstance()
                .getVectorTypeSupport()
                .createFloatVector(array);
    }
}
