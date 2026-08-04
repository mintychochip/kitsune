package dev.jlo.kitsune.index;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import dev.jlo.kitsune.item.BukkitTraversalAdapter;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalLimits;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerIndexLoadedRootRestorationTest {

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

    @Test
    void staleCompletionDoesNotRestoreLoadedRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();
            fixture.removeAndBlockReplacement();
            fixture.index.markDirty(fixture.root);

            fixture.repository.releaseBlockedReplacement();
            fixture.repository.awaitReplacement();
            fixture.awaitWorker();
            fixture.tick(4L);

            assertTrue(fixture.index.loadedRoots().isEmpty());
        }
    }

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

    @Test
    void deleteInvalidatesLoadedRootBeforeRepositoryWork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.delete(fixture.root);

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

    @Test
    void chunkUnloadInvalidatesEveryKnownRootImmediately() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.onChunkUnloaded(fixture.root.chunkKey());

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

    @Test
    void dirtyRootInvalidatesItsPublishedMarkersImmediately() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.finishInitialIndexing();

            fixture.index.markDirty(fixture.root);

            assertEquals(List.of(fixture.root), fixture.invalidatedRoots);
        }
    }

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

    private static final class Fixture implements AutoCloseable {
        private final UUID worldId = UUID.randomUUID();
        private final BlockKey root = new BlockKey(worldId, 0, 64, 0);
        private final AtomicLong currentTick = new AtomicLong();
        private final BlockingRepository repository = new BlockingRepository();
        private final IndexWorker worker = new IndexWorker(repository);
        private final List<BlockKey> invalidatedRoots = new ArrayList<>();
        private final ContainerIndex index;

        private Fixture() {
            RootResolver<Inventory> resolver = rootResolver(root);
            ContainerSnapshotter snapshotter = snapshotter(resolver);
            EmbeddingProvider embeddingProvider = new SparseTagEmbeddingProvider();
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

    private static final class BlockingRepository implements IndexRepository {
        private final Semaphore replacements = new Semaphore(0);
        private final ConcurrentLinkedQueue<BlockKey> replacedRoots =
            new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<BlockKey> deletedRoots =
            new ConcurrentLinkedQueue<>();
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
            replacedRoots.add(snapshot.key());
            replacements.release();
        }

        @Override
        public void deleteRoot(BlockKey key) {
            deletedRoots.add(key);
        }

        @Override
        public List<RootIdentity> findCandidates(
            UUID worldId,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
        ) {
            return List.of();
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
