package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import dev.jlo.kitsune.item.BukkitTraversalAdapter;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalLimits;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.model.RootIdentity;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the container index restores, invalidates, and reconciles
 * loaded roots across the indexing worker and repository lifecycle.
 */
class ContainerIndexLoadedRootRestorationTest {

    /**
     * A successful, current snapshot restores the previously loaded root.
     */
    @Test
    void successfulCurrentSnapshotRestoresLoadedRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            fixture.removeAndBlockReplacement();

            fixture.repository.releaseBlockedReplacement();
            fixture.repository.awaitReplacement();
            fixture.awaitWorker();
            fixture.tick(4L);

            assertEquals(Set.of(fixture.root), fixture.index.loadedRoots());
        }
    }

    /**
     * An unchanged snapshot skips both repository replacement and embedding.
     */
    @Test
    void unchangedSnapshotSkipsReplacementAndEmbedding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            int initialEmbeddings = fixture.embeddingProvider.embedCalls();

            fixture.index.markDirty(fixture.root);
            fixture.tick(3L);
            fixture.awaitWorker();
            fixture.tick(4L);

            assertEquals(List.of(fixture.root), fixture.repository.replacedRoots());
            assertEquals(initialEmbeddings, fixture.embeddingProvider.embedCalls());
            assertEquals(Set.of(fixture.root), fixture.index.loadedRoots());
        }
    }

    /**
     * A delete issued while a snapshot is in flight prevents restoration.
     */
    @Test
    void deleteDuringInFlightSnapshotPreventsRestore() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            fixture.removeAndBlockReplacement();
            fixture.index.delete(fixture.root);

            fixture.repository.releaseBlockedReplacement();
            fixture.repository.awaitReplacement();
            fixture.awaitWorker();
            fixture.tick(4L);

            assertTrue(fixture.index.loadedRoots().isEmpty());
        }
    }

    /**
     * An unloaded chunk blocks restoration of the loaded root.
     */
    @Test
    void unloadedChunkBlocksRestoration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            fixture.removeAndBlockReplacement();
            fixture.index.onChunkUnloaded(fixture.root.chunkKey());

            fixture.repository.releaseBlockedReplacement();
            fixture.repository.awaitReplacement();
            fixture.awaitWorker();
            fixture.tick(4L);

            assertTrue(fixture.index.loadedRoots().isEmpty());
        }
    }

    /**
     * Deleting a root invalidates its loaded status before repository work.
     */
    @Test
    void deleteInvalidatesLoadedRootBeforeRepositoryWork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.delete(fixture.root);

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

    /**
     * Chunk unload invalidates every known loaded root immediately.
     */
    @Test
    void chunkUnloadInvalidatesEveryKnownRootImmediately() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.onChunkUnloaded(fixture.root.chunkKey());

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

    /**
     * Marking a root dirty invalidates its published markers immediately.
     */
    @Test
    void dirtyRootInvalidatesItsPublishedMarkersImmediately() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.markDirty(fixture.root);

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

    /**
     * An unavailable snapshot preserves the persisted root without deleting it.
     */
    @Test
    void unavailableSnapshotPreservesThePersistedRoot() throws Exception {
        BlockKey root = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        AtomicLong currentTick = new AtomicLong();
        BlockingRepository repository = new BlockingRepository();
        IndexWorker worker = new IndexWorker(repository);
        RootResolver<Inventory> resolver = unavailableResolver(root);
        ContainerIndex index = new ContainerIndex(
            resolver,
            snapshotter(resolver),
            worker,
            repository,
            new SparseTagEmbeddingProvider(),
            1,
            1,
            100,
            currentTick::get,
            ignored -> {}
        );

        try {
            index.onChunkLoaded(fakeEmptyChunk(root));
            index.tick();
            index.markDirty(root);
            currentTick.set(1L);
            index.tick();
            worker.submit(() -> null).get(5, TimeUnit.SECONDS);

            assertTrue(repository.replacedRoots().isEmpty());
            assertTrue(repository.deletedRoots().isEmpty());
        } finally {
            index.close();
            worker.close();
        }
    }

    /**
     * Unloading a chunk before the delayed snapshot preserves committed data.
     */
    @Test
    void unloadingBeforeDelayedSnapshotPreservesCommittedData()
        throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            fixture.index.markDirty(fixture.root);
            fixture.index.onChunkUnloaded(fixture.root.chunkKey());

            fixture.tick(3L);
            fixture.awaitWorker();

            assertEquals(
                List.of(fixture.root),
                fixture.repository.replacedRoots()
            );
            assertTrue(fixture.repository.deletedRoots().isEmpty());
        }
    }

    /**
     * Canonical replacement deletes the obsolete requested coordinate root.
     */
    @Test
    void canonicalReplacementDeletesTheObsoleteRequestedCoordinate()
        throws Exception {
        UUID worldId = UUID.randomUUID();
        BlockKey canonical = new BlockKey(worldId, 0, 64, 0);
        BlockKey requested = new BlockKey(worldId, 0, 64, 1);
        AtomicLong currentTick = new AtomicLong();
        List<BlockKey> invalidatedRoots = new ArrayList<>();
        BlockingRepository repository = new BlockingRepository();
        IndexWorker worker = new IndexWorker(repository);
        RootResolver<Inventory> resolver = doubleChestResolver(
            canonical,
            requested
        );
        ContainerIndex index = new ContainerIndex(
            resolver,
            snapshotter(resolver),
            worker,
            repository,
            new SparseTagEmbeddingProvider(),
            1,
            1,
            100,
            currentTick::get,
            invalidatedRoots::add
        );

        try {
            index.onChunkLoaded(fakeEmptyChunk(requested));
            index.tick();
            index.markDirty(requested);
            currentTick.set(1L);
            index.tick();
            repository.awaitReplacement();
            worker.submit(() -> null).get(5, TimeUnit.SECONDS);
            invalidatedRoots.clear();
            currentTick.set(2L);
            index.tick();

            assertEquals(List.of(canonical), repository.replacedRoots());
            assertEquals(List.of(requested), repository.deletedRoots());
            assertEquals(List.of(requested), invalidatedRoots);
        } finally {
            index.close();
            worker.close();
        }
    }

    /**
     * Builds a fully-wired {@link ContainerIndex} together with a blocking
     * repository, worker, and tick source for exercising restoration flows.
     */
    private static final class Fixture implements AutoCloseable {
        private final UUID worldId = UUID.randomUUID();
        private final BlockKey root = new BlockKey(worldId, 0, 64, 0);
        private final AtomicLong currentTick = new AtomicLong();
        private final BlockingRepository repository = new BlockingRepository();
        private final CountingEmbeddingProvider embeddingProvider =
            new CountingEmbeddingProvider();
        private final IndexWorker worker = new IndexWorker(repository);
        private final List<BlockKey> invalidatedRoots = new ArrayList<>();
        private final ContainerIndex index;

        private Fixture() {
            RootResolver<Inventory> resolver = rootResolver(root);
            ContainerSnapshotter snapshotter = snapshotter(resolver);
            index = new ContainerIndex(
                resolver,
                snapshotter,
                worker,
                repository,
                embeddingProvider,
                1,
                1,
                100,
                currentTick::get,
                invalidatedRoots::add
            );
        }

        private void finishInitialIndexing() throws Exception {
            index.onChunkLoaded(fakeChunk(root));
            tick(0L);
            assertEquals(Set.of(root), index.loadedRoots());
            tick(1L);
            repository.awaitReplacement();
            awaitWorker();
            tick(2L);
        }

        private void removeAndBlockReplacement() throws Exception {
            index.delete(root);
            index.markDirty(root);
            assertTrue(index.loadedRoots().isEmpty());
            repository.blockNextReplacement();
            tick(3L);
            repository.awaitBlockedReplacement();
        }

        private void awaitWorker() throws Exception {
            worker.submit(() -> null).get(5, TimeUnit.SECONDS);
        }

        private void tick(long tick) {
            currentTick.set(tick);
            index.tick();
        }

        @Override
        public void close() throws Exception {
            index.close();
            worker.close();
        }
    }

    /**
     * An {@link IndexRepository} whose replacements can be blocked and released
     * to simulate in-flight repository work.
     */
    private static final class BlockingRepository implements IndexRepository {
        private final Semaphore replacements = new Semaphore(0);
        private final ConcurrentLinkedQueue<BlockKey> replacedRoots =
            new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<BlockKey> deletedRoots =
            new ConcurrentLinkedQueue<>();
        private final Map<BlockKey, RootIdentity> storedRoots =
            new ConcurrentHashMap<>();
        private final Map<SemanticDescriptorHash, Embedding> storedEmbeddings =
            new ConcurrentHashMap<>();
        private volatile CountDownLatch blockedReplacementStarted;
        private volatile CountDownLatch blockedReplacementRelease;

        private void blockNextReplacement() {
            blockedReplacementStarted = new CountDownLatch(1);
            blockedReplacementRelease = new CountDownLatch(1);
        }

        private void awaitBlockedReplacement() throws InterruptedException {
            assertTrue(
                blockedReplacementStarted.await(5, TimeUnit.SECONDS),
                "Replacement did not start"
            );
        }

        private void releaseBlockedReplacement() {
            blockedReplacementRelease.countDown();
        }

        private void awaitReplacement() throws InterruptedException {
            assertTrue(
                replacements.tryAcquire(5, TimeUnit.SECONDS),
                "Replacement did not complete"
            );
        }
        private List<BlockKey> replacedRoots() {
            return List.copyOf(replacedRoots);
        }

        private List<BlockKey> deletedRoots() {
            return List.copyOf(deletedRoots);
        }


        @Override
        public void migrate() {}

        @Override
        public void markAllChunksUnavailable() {}

        @Override
        public void setChunkAvailable(
            ChunkKey chunk,
            boolean available,
            long revision
        ) {}

        @Override
        public void replaceRoot(ContainerSnapshot snapshot, long revision)
            throws SQLException {
            CountDownLatch started = blockedReplacementStarted;
            CountDownLatch release = blockedReplacementRelease;
            if (started != null && release != null) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Replacement interrupted", interrupted);
                }
                blockedReplacementStarted = null;
                blockedReplacementRelease = null;
            }
            storedRoots.put(
                snapshot.key(),
                new RootIdentity(
                    snapshot.key(),
                    snapshot.blockType(),
                    snapshot.fingerprint(),
                    revision
                )
            );
            replacedRoots.add(snapshot.key());
            replacements.release();
        }

        @Override
        public void deleteRoot(BlockKey key) {
            storedRoots.remove(key);
            deletedRoots.add(key);
        }

        @Override
        public IndexRepository.CandidatePage findCandidates(
            UUID worldId,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            IndexRepository.CandidateCursor after,
            int limit
        ) {
            return new IndexRepository.CandidatePage(List.of(), null);
        }

        @Override
        public java.util.Optional<RootIdentity> findRoot(BlockKey key) {
            return java.util.Optional.ofNullable(storedRoots.get(key));
        }

        @Override
        public Map<SemanticDescriptorHash, Embedding> findEmbeddings(
            EmbeddingProvider provider,
            Set<SemanticDescriptorHash> hashes
        ) {
            Map<SemanticDescriptorHash, Embedding> found = new java.util.LinkedHashMap<>();
            for (SemanticDescriptorHash hash : hashes) {
                Embedding embedding = storedEmbeddings.get(hash);
                if (embedding != null) {
                    found.put(hash, embedding);
                }
            }
            return Map.copyOf(found);
        }

        @Override
        public void putEmbeddings(
            EmbeddingProvider provider,
            Map<SemanticDescriptorHash, Embedding> embeddings
        ) {
            storedEmbeddings.putAll(embeddings);
        }

        @Override
        public Map<BlockKey, List<IndexedItem>> loadDocuments(
            Set<BlockKey> allowed,
            EmbeddingProvider provider
        ) {
            return Map.of();
        }

        @Override
        public void reembedAll(EmbeddingProvider provider) {}

        @Override
        public void close() {}
    }

    /**
     * An {@link EmbeddingProvider} that counts every embed call, delegating
     * the actual computation to a sparse-tag provider.
     */
    private static final class CountingEmbeddingProvider
        implements EmbeddingProvider {
        private final EmbeddingProvider delegate = new SparseTagEmbeddingProvider();
        private final AtomicInteger embedCalls = new AtomicInteger();

        int embedCalls() {
            return embedCalls.get();
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
            embedCalls.incrementAndGet();
            return delegate.embed(descriptor);
        }

        @Override
        public List<Embedding> embedAll(List<ItemDescriptor> descriptors) {
            embedCalls.addAndGet(descriptors.size());
            return delegate.embedAll(descriptors);
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

    private static RootResolver<Inventory> rootResolver(BlockKey root) {
        Inventory inventory = fakeInventory();
        return new RootResolver<>(key -> key.equals(root)
            ? new RootResolver.RootProbe<>(
                key,
                "minecraft:chest",
                inventory,
                true,
                false,
                null,
                true
            )
            : null);
    }
    private static RootResolver<Inventory> unavailableResolver(BlockKey root) {
        return new RootResolver<>(key -> key.equals(root)
            ? new RootResolver.RootProbe<>(
                key,
                "minecraft:chest",
                null,
                true,
                false,
                null,
                true
            )
            : null);
    }

    private static RootResolver<Inventory> doubleChestResolver(
        BlockKey canonical,
        BlockKey requested
    ) {
        Inventory inventory = fakeInventory();
        return new RootResolver<>(key -> {
            if (key.equals(canonical)) {
                return connectedProbe(canonical, requested, inventory);
            }
            if (key.equals(requested)) {
                return connectedProbe(requested, canonical, inventory);
            }
            return null;
        });
    }

    private static RootResolver.RootProbe<Inventory> connectedProbe(
        BlockKey key,
        BlockKey connected,
        Inventory inventory
    ) {
        return new RootResolver.RootProbe<>(
            key,
            "minecraft:chest",
            inventory,
            true,
            false,
            connected,
            true
        );
    }

    private static ContainerSnapshotter snapshotter(
        RootResolver<Inventory> resolver
    ) {
        return new ContainerSnapshotter(
            resolver,
            new NestedItemWalker<>(
                TraversalLimits.defaults(),
                new BukkitTraversalAdapter(
                    proxy(
                        Server.class,
                        (method, arguments) ->
                            defaultValue(method.getReturnType())
                    )
                )
            )
        );
    }


    private static Chunk fakeChunk(BlockKey root) {
        return fakeChunk(root, true);
    }

    private static Chunk fakeEmptyChunk(BlockKey root) {
        return fakeChunk(root, false);
    }

    private static Chunk fakeChunk(BlockKey root, boolean includeRoot) {
        World world = proxy(World.class, (method, arguments) -> {
            if (method.getName().equals("getUID")) return root.worldId();
            return defaultValue(method.getReturnType());
        });
        List<BlockState> tileEntities = includeRoot
            ? List.of(fakeTileEntity(root, world))
            : List.of();
        return proxy(Chunk.class, (method, arguments) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getX" -> root.chunkKey().x();
            case "getZ" -> root.chunkKey().z();
            case "isLoaded" -> true;
            case "getTileEntities" -> method.getReturnType().isArray()
                ? tileEntities.toArray(BlockState[]::new)
                : tileEntities;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static BlockState fakeTileEntity(BlockKey root, World world) {
        Block block = proxy(Block.class, (method, arguments) -> switch (method.getName()) {
            case "getWorld" -> world;
            case "getX" -> root.x();
            case "getY" -> root.y();
            case "getZ" -> root.z();
            default -> defaultValue(method.getReturnType());
        });
        return (BlockState) proxy(
            new Class<?>[] {BlockState.class, BlockInventoryHolder.class},
            (method, arguments) -> switch (method.getName()) {
                case "getX" -> root.x();
                case "getY" -> root.y();
                case "getZ" -> root.z();
                case "getBlock" -> block;
                case "getInventory" -> fakeInventory();
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Inventory fakeInventory() {
        return proxy(Inventory.class, (method, arguments) -> switch (method.getName()) {
            case "getSize" -> 0;
            case "getItem" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments)
            throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(proxy(new Class<?>[] {type}, invocation));
    }

    private static Object proxy(Class<?>[] types, Invocation invocation) {
        return Proxy.newProxyInstance(
            types[0].getClassLoader(),
            types,
            (ignored, method, arguments) -> invocation.invoke(method, arguments)
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        throw new AssertionError("Unknown primitive type: " + type);
    }
}
