package dev.jlo.kitsune.neoforge;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

public final class NeoForgeRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Kitsune");

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final AtomicBoolean commandRegistered = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private final Map<BlockKey, ChunkKey> indexedRoots = new HashMap<>();

    private MinecraftServer server;
    private KitsuneConfig config;
    private IndexRepository repository;
    private IndexWorker worker;
    private EmbeddingProvider embeddings;
    private NeoForgeWorldAccess worldAccess;
    private NeoForgeServerThreadBridge serverBridge;
    private SearchService searchService;
    private SearchSessionManager sessions;
    private NeoForgeSessionScheduler sessionScheduler;
    private NeoForgeSessionListener sessionListener;
    private NeoForgeChangeListener changeListener;
    private CompletableFuture<Void> indexReady;
    private long ticks;

    public void start(MinecraftServer server) {
        Objects.requireNonNull(server, "Server must not be null");
        if (!started.compareAndSet(false, true)) return;
        this.server = server;
        try {
            Path configPath = server.getServerDirectory().resolve("config/kitsune.properties");
            config = NeoForgeConfigLoader.load(configPath);
            EmbeddingRegistry registry = new EmbeddingRegistry(List.of(), config.embeddingProvider());
            embeddings = registry.selectedProvider();
            repository = SqliteIndexRepository.open(
                server.getServerDirectory().resolve("config/kitsune-index.sqlite"),
                embeddings
            );
            worker = new IndexWorker(repository);
            NeoForgeItemAccess items = new NeoForgeItemAccess(server.registryAccess());
            worldAccess = new NeoForgeWorldAccess(
                server,
                items,
                config.maximumDepth(),
                config.maximumStacksPerRoot()
            );
            serverBridge = new NeoForgeServerThreadBridge(server);
            sessions = new SearchSessionManager(
                sessionScheduler = new NeoForgeSessionScheduler(server),
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
            sessionListener = new NeoForgeSessionListener(sessions);
            sessionListener.register();
            changeListener = new NeoForgeChangeListener(this::requestScan);
            changeListener.register();
            repository.markAllChunksUnavailable();
            requestScan();
            LOGGER.info("Kitsune NeoForge runtime enabled");
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("Failed to initialize Kitsune NeoForge runtime", failure);
        }
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        Objects.requireNonNull(dispatcher, "Dispatcher must not be null");
        if (commandRegistered.compareAndSet(false, true)) NeoForgeCommand.register(dispatcher, this);
    }

    public void sendUsage(CommandSourceStack source) {
        source.sendFailure(Component.literal("Usage: /kitsune [--verbose] <query>"));
    }

    public void executeSearch(CommandSourceStack source, String rawQuery) {
        if (closed.get() || searchService == null || sessions == null) {
            source.sendFailure(Component.literal("Search is currently unavailable."));
            return;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return;
        }

        SearchRequest request;
        try {
            String query = rawQuery == null ? "" : rawQuery.trim();
            request = SearchRequest.parse(query.isEmpty() ? new String[0] : query.split("\\s+"));
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
            return;
        }

        SearchContext context = new SearchContext(
            player.getUUID(),
            new BlockKey(
                NeoForgeWorldAccess.worldId(player.serverLevel()),
                player.blockPosition().getX(),
                player.blockPosition().getY(),
                player.blockPosition().getZ()
            )
        );
        SearchToken token;
        try {
            token = sessions.begin(player.getUUID());
            searchService.search(context, request, token, sessions)
                .whenComplete((outcome, failure) -> serverBridge.run(() -> sendResult(source, token, outcome, failure)));
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Search failed. Please try again later."));
        }
    }

    public void requestScan() {
        scanQueued.set(true);
    }

    public void onServerTick() {
        if (closed.get() || config == null) return;
        ticks++;
        if (scanQueued.getAndSet(false) || ticks % config.reconciliationPeriodTicks() == 0) scanAndSubmit();
    }

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
        if (failure != null) LOGGER.error("Failed to close Kitsune NeoForge runtime", failure);
        repository = null;
        worker = null;
        searchService = null;
        server = null;
    }

    private void scanAndSubmit() {
        if (worker == null || worldAccess == null) return;
        List<NeoForgeWorldAccess.ContainerData> current = worldAccess.scanLoadedContainers();
        Map<BlockKey, NeoForgeWorldAccess.ContainerData> byKey = new HashMap<>();
        for (NeoForgeWorldAccess.ContainerData container : current) byKey.put(container.draft().key(), container);
        Map<BlockKey, ChunkKey> previous = new HashMap<>(indexedRoots);
        indexedRoots.clear();
        byKey.forEach((key, container) -> indexedRoots.put(key, container.chunk()));

        worker.submit(() -> {
            long currentRevision = revision.incrementAndGet();
            Set<ChunkKey> currentChunks = new HashSet<>();
            for (NeoForgeWorldAccess.ContainerData container : current) {
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
            indexReady.complete(null);
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null && !indexReady.isDone()) indexReady.completeExceptionally(unwrap(failure));
        });
    }

    private void sendResult(
        CommandSourceStack source,
        SearchToken token,
        SearchOutcome outcome,
        Throwable failure
    ) {
        if (closed.get() || sessions == null) return;
        if (failure != null || outcome == null) {
            sessions.clear(token);
            source.sendFailure(Component.literal("Search failed. Please try again later."));
            return;
        }
        switch (outcome.status()) {
            case CANCELED -> sessions.clear(token);
            case NO_MATCHES -> {
                sessions.clear(token);
                source.sendSuccess(() -> Component.literal("No accessible nearby storage matched."), false);
            }
            case UNSUPPORTED_QUERY, INDEX_WARMING, FAILURE -> {
                sessions.clear(token);
                source.sendFailure(Component.literal("Search failed. Please try again later."));
            }
            case SUCCESS -> {
                if (!sessions.attach(token, List.of())) return;
                source.sendSuccess(
                    () -> Component.literal("Found " + outcome.totalAccessibleMatchingStacks() + " matching stacks."),
                    false
                );
                for (RootMatch root : outcome.roots()) {
                    source.sendSuccess(
                        () -> Component.literal(
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
