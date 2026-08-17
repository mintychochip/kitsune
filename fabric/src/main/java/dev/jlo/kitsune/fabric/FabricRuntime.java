package dev.jlo.kitsune.fabric;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.command.SearchRequest;
import dev.jlo.kitsune.config.KitsuneConfig;
import dev.jlo.kitsune.embedding.EmbeddingRegistry;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.IndexWorker;
import dev.jlo.kitsune.index.SqliteIndexRepository;
import dev.jlo.kitsune.model.BlockKey;
import dev.jlo.kitsune.model.ChunkKey;
import dev.jlo.kitsune.model.ContainerSnapshot;
import dev.jlo.kitsune.model.IndexedItem;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.search.IndexReadiness;
import dev.jlo.kitsune.search.RootMatch;
import dev.jlo.kitsune.search.SearchContext;
import dev.jlo.kitsune.search.SearchOutcome;
import dev.jlo.kitsune.search.SearchPolicy;
import dev.jlo.kitsune.search.SearchService;
import dev.jlo.kitsune.session.SearchSessionManager;
import dev.jlo.kitsune.session.SearchToken;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the Kitsune services for a Minecraft server.
 *
 * <p>On {@link #start(MinecraftServer)} the runtime loads configuration,
 * opens the index repository and embedding provider, constructs the search
 * service and session machinery, registers the command and world/session
 * listeners, and queues an initial scan. It then reconciles the index each
 * server tick when a scan is queued or the reconciliation period elapses.
 * {@link #close()} shuts down all owned services and releases resources.
 */
public final class FabricRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Kitsune");

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private final Map<BlockKey, ChunkKey> indexedRoots = new HashMap<>();

    private MinecraftServer server;
    private KitsuneConfig config;
    private IndexRepository repository;
    private IndexWorker worker;
    private EmbeddingProvider embeddings;
    private FabricWorldAccess worldAccess;
    private FabricServerThreadBridge serverBridge;
    private SearchService searchService;
    private SearchSessionManager sessions;
    private FabricSessionScheduler sessionScheduler;
    private FabricSessionListener sessionListener;
    private FabricChangeListener changeListener;
    private CompletableFuture<Void> indexReady;
    private long ticks;

    /**
     * Initializes the runtime for the given server and queues an initial scan.
     * A second call while already started is a no-op.
     *
     * @param server server to index and serve
     * @throws IllegalStateException if initialization fails, in which case the
     *                               runtime is closed before rethrowing
     */
    public void start(MinecraftServer server) {
        Objects.requireNonNull(server, "Server must not be null");
        if (!started.compareAndSet(false, true)) return;
        this.server = server;

        try {
            Path configPath = server.getRunDirectory().resolve("config/kitsune.properties");
            config = FabricConfigLoader.load(configPath);
            EmbeddingRegistry registry = new EmbeddingRegistry(List.of(), config.embeddingProvider());
            embeddings = registry.selectedProvider();
            repository = SqliteIndexRepository.open(
                server.getRunDirectory().resolve("config/kitsune-index.sqlite"),
                embeddings
            );
            worker = new IndexWorker(repository);
            worldAccess = new FabricWorldAccess(
                server,
                new FabricItemAccess(server.getRegistryManager()),
                config.maximumDepth(),
                config.maximumStacksPerRoot()
            );
            serverBridge = new FabricServerThreadBridge(server);
            sessions = new SearchSessionManager(
                sessionScheduler = new FabricSessionScheduler(server),
                Duration.ofSeconds(config.markerDurationSeconds())
            );
            indexReady = new CompletableFuture<>();
            SearchPolicy policy = new SearchPolicy(
                config.radius(),
                config.minimumScore(),
                config.maxResults(),
                config.maxPathsPerRoot(),
                Duration.ofSeconds(config.warmupTimeoutSeconds())
            );
            IndexReadiness readiness = (chunks, timeout) -> indexReady
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            searchService = new SearchService(
                readiness,
                worker,
                repository,
                embeddings,
                serverBridge,
                worldAccess,
                policy
            );
            sessionListener = new FabricSessionListener(sessions);
            sessionListener.register();
            changeListener = new FabricChangeListener(this::requestScan);
            changeListener.register();
            ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
            FabricCommand.register(server.getCommandManager().getDispatcher(), this);
            repository.markAllChunksUnavailable();
            requestScan();
            LOGGER.info("Kitsune Fabric runtime enabled");
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("Failed to initialize Kitsune Fabric runtime", failure);
        }
    }

    /**
     * Sends command usage feedback to the source.
     *
     * @param source command source to notify
     */
    public void sendUsage(ServerCommandSource source) {
        source.sendError(Text.literal("Usage: /kitsune [--verbose] <query>"));
    }

    /**
     * Runs a search for the issuing player's current world position and
     * schedules the result feedback back on the server thread.
     *
     * @param source command source for the player issuing the search
     * @param rawQuery whitespace-delimited raw query
     */
    public void executeSearch(ServerCommandSource source, String rawQuery) {
        if (closed.get() || searchService == null || sessions == null) {
            source.sendError(Text.literal("Search is currently unavailable."));
            return;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only players can use this command."));
            return;
        }

        SearchRequest request;
        try {
            request = SearchRequest.parse(rawQuery.trim().split("\\s+"));
        } catch (IllegalArgumentException failure) {
            source.sendError(Text.literal(failure.getMessage()));
            return;
        }

        ServerWorld world = player.getServerWorld();
        BlockPos position = player.getBlockPos();
        SearchContext context = new SearchContext(
            player.getUuid(),
            new BlockKey(
                FabricWorldAccess.worldId(world),
                position.getX(),
                position.getY(),
                position.getZ()
            )
        );
        SearchToken token;
        try {
            token = sessions.begin(player.getUuid());
            searchService.search(context, request, token, sessions)
                .whenComplete((outcome, failure) -> serverBridge.run(() -> sendResult(source, token, outcome, failure)));
        } catch (RuntimeException failure) {
            source.sendError(Text.literal("Search failed. Please try again later."));
        }
    }

    /**
     * Queues a rescan to be performed on the next server tick.
     */
    public void requestScan() {
        scanQueued.set(true);
    }

    /**
     * Closes the runtime, stopping all owned services and releasing resources.
     * A second call is a no-op.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (changeListener != null) changeListener.close();
        if (sessionListener != null) sessionListener.close();
        if (sessions != null) sessions.clearAll();
        if (sessionScheduler != null) sessionScheduler.close();

        Throwable failure = null;
        if (worker != null) {
            try {
                worker.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
        } else if (repository != null) {
            try {
                repository.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
        }
        if (failure != null) LOGGER.error("Failed to close Kitsune Fabric runtime", failure);
        repository = null;
        worker = null;
        searchService = null;
        server = null;
    }

    private void onEndTick(MinecraftServer ignored) {
        if (closed.get()) return;
        ticks++;
        if (scanQueued.getAndSet(false) || ticks % config.reconciliationPeriodTicks() == 0) {
            scanAndSubmit();
        }
    }

    private void scanAndSubmit() {
        if (worker == null || worldAccess == null) return;
        List<FabricWorldAccess.ContainerData> current = worldAccess.scanLoadedContainers();
        Map<BlockKey, FabricWorldAccess.ContainerData> byKey = new HashMap<>();
        for (FabricWorldAccess.ContainerData container : current) {
            byKey.put(container.draft().key(), container);
        }
        Map<BlockKey, ChunkKey> previous = new HashMap<>(indexedRoots);
        indexedRoots.clear();
        byKey.forEach((key, container) -> indexedRoots.put(key, container.chunk()));

        worker.submit(() -> {
            long currentRevision = revision.incrementAndGet();
            Set<ChunkKey> currentChunks = new HashSet<>();
            for (FabricWorldAccess.ContainerData container : current) {
                currentChunks.add(container.chunk());
                List<IndexedItem> items = new ArrayList<>();
                for (ItemDraft item : container.draft().items()) {
                    items.add(new IndexedItem(
                        item.path(),
                        item.amount(),
                        item.descriptor(),
                        embeddings.embed(item.descriptor())
                    ));
                }
                repository.setChunkAvailable(container.chunk(), true, currentRevision);
                repository.replaceRoot(new ContainerSnapshot(
                    container.draft().key(),
                    container.draft().blockType(),
                    container.draft().fingerprint(),
                    items
                ), currentRevision);
            }
            for (Map.Entry<BlockKey, ChunkKey> entry : previous.entrySet()) {
                if (!byKey.containsKey(entry.getKey())) repository.deleteRoot(entry.getKey());
            }
            if (scanIsReady(current, previous)) indexReady.complete(null);
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null && !indexReady.isDone()) indexReady.completeExceptionally(unwrap(failure));
        });
    }

    /**
     * Determines whether the current scan makes the index ready. Currently
     * always returns true once both inputs are non-null.
     *
     * @param current current scan results
     * @param previous previous index state
     * @return {@code true} if the index is ready
     */
    static boolean scanIsReady(List<?> current, Map<?, ?> previous) {
        Objects.requireNonNull(current, "Current scan must not be null");
        Objects.requireNonNull(previous, "Previous index state must not be null");
        return true;
    }

    private void sendResult(
        ServerCommandSource source,
        SearchToken token,
        SearchOutcome outcome,
        Throwable failure
    ) {
        if (closed.get() || sessions == null) return;
        if (failure != null || outcome == null) {
            sessions.clear(token);
            source.sendError(Text.literal("Search failed. Please try again later."));
            return;
        }
        switch (outcome.status()) {
            case CANCELED -> sessions.clear(token);
            case NO_MATCHES -> {
                sessions.clear(token);
                source.sendFeedback(() -> Text.literal("No accessible nearby storage matched."), false);
            }
            case UNSUPPORTED_QUERY, INDEX_WARMING, FAILURE -> {
                sessions.clear(token);
                source.sendError(Text.literal("Search failed. Please try again later."));
            }
            case SUCCESS -> {
                if (!sessions.attach(token, List.of())) return;
                source.sendFeedback(
                    () -> Text.literal("Found " + outcome.totalAccessibleMatchingStacks() + " matching stacks."),
                    false
                );
                for (RootMatch root : outcome.roots()) {
                    source.sendFeedback(
                        () -> Text.literal(
                            root.identity().blockType() + " at " + root.key().x() + ", "
                                + root.key().y() + ", " + root.key().z()
                        ),
                        false
                    );
                }
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }
}
