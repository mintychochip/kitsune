package dev.jlo.kitsune.bukkit;

import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.api.protection.BlockAccessProvider;
import dev.jlo.kitsune.bukkit.index.BukkitRootResolver;
import dev.jlo.kitsune.item.BukkitTraversalAdapter;
import dev.jlo.kitsune.protection.LwcProtectionProvider;
import dev.jlo.kitsune.config.ConfigLoader;
import dev.jlo.kitsune.config.KitsuneConfig;
import dev.jlo.kitsune.embedding.EmbeddingRegistry;
import dev.jlo.kitsune.index.ContainerIndex;
import dev.jlo.kitsune.index.ContainerSnapshotter;
import dev.jlo.kitsune.index.IndexListener;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.IndexWorker;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalLimits;
import dev.jlo.kitsune.protection.ProtectionRegistry;
import dev.jlo.kitsune.search.SearchPolicy;
import dev.jlo.kitsune.search.SearchService;
import dev.jlo.kitsune.session.BukkitSessionScheduler;
import dev.jlo.kitsune.session.SearchSessionManager;
import dev.jlo.kitsune.session.SessionListener;
import dev.jlo.kitsune.ui.ChatTreeRenderer;
import dev.jlo.kitsune.ui.MarkerRenderer;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Shared Bukkit lifecycle for the Paper and Spigot entrypoints.
 *
 * <p>The runtime owns only Bukkit-facing wiring. Indexing, embedding, search,
 * and session policy remain in the platform-neutral modules.</p>
 */
public final class BukkitRuntime implements AutoCloseable {
    private static final String LWC_INCOMPATIBILITY_MESSAGE =
        "LWC is enabled but its API is incompatible; Kitsune search is disabled.";

    private final JavaPlugin plugin;
    private final Server server;
    private final Path dataDirectory;
    private final boolean paperCapabilities;
    private final BootstrapLifecycle lifecycle = new BootstrapLifecycle();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong tick = new AtomicLong();

    private volatile BootstrapOpen pendingBootstrap;
    private volatile ExecutorService bootstrapExecutor;
    private volatile ContainerIndex containerIndex;
    private volatile IndexWorker indexWorker;
    private volatile IndexListener indexListener;
    private volatile BukkitTask indexTickTask;
    private volatile SearchSessionManager sessionManager;
    private volatile SessionListener sessionListener;
    private volatile dev.jlo.kitsune.command.KitsuneCommand kitsuneCommand;
    private volatile BlockAccessProvider lwcProtectionProvider;

    public BukkitRuntime(
        JavaPlugin plugin,
        Server server,
        Path dataDirectory,
        boolean paperCapabilities
    ) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin must not be null");
        this.server = Objects.requireNonNull(server, "Server must not be null");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "Data directory must not be null");
        this.paperCapabilities = paperCapabilities;
    }

    public boolean paperCapabilities() {
        return paperCapabilities;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        if (!setCommandUnavailable("Kitsune is still starting.")) {
            failStart(new IllegalStateException("Missing /kitsune command declaration"));
            return;
        }

        plugin.saveDefaultConfig();

        final KitsuneConfig loadedConfig;
        final EmbeddingProvider embeddingProvider;
        try {
            loadedConfig = ConfigLoader.load(plugin);
            EmbeddingRegistry registry = new EmbeddingRegistry(
                serviceProviders(EmbeddingProvider.class),
                loadedConfig.embeddingProvider()
            );
            embeddingProvider = registry.selectedProvider();
            registerLwcIntegrationIfEnabled();
        } catch (Throwable failure) {
            failStart(failure);
            return;
        }

        long generation = lifecycle.begin();
        ExecutorService executor = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().name("kitsune-bootstrap").unstarted(task)
        );
        CompletableFuture<BootstrapOpen> future = CompletableFuture.supplyAsync(
            () -> {
                try {
                    return new BootstrapOpen(
                        loadedConfig,
                        embeddingProvider,
                        dev.jlo.kitsune.index.SqliteIndexRepository.open(
                            dataDirectory.resolve("index.sqlite"),
                            embeddingProvider
                        )
                    );
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            },
            executor
        );
        bootstrapExecutor = executor;
        future.whenComplete((result, failure) ->
            onBootstrapFutureComplete(generation, executor, result, failure)
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        lifecycle.stop();

        BootstrapOpen unpublished = pendingBootstrap;
        pendingBootstrap = null;
        ExecutorService bootstrap = bootstrapExecutor;
        bootstrapExecutor = null;
        closeUnpublishedRepository(unpublished);
        if (bootstrap != null) bootstrap.shutdownNow();

        Throwable failure = null;
        if (kitsuneCommand != null) kitsuneCommand.stop();
        setCommandUnavailable("Kitsune is unavailable.");
        if (sessionManager != null) sessionManager.clearAll();
        if (sessionListener != null) HandlerList.unregisterAll(sessionListener);

        BlockAccessProvider registeredLwcProvider = lwcProtectionProvider;
        lwcProtectionProvider = null;
        if (registeredLwcProvider != null) {
            try {
                server.getServicesManager().unregister(BlockAccessProvider.class, registeredLwcProvider);
            } catch (Throwable unregisterFailure) {
                failure = appendFailure(failure, unregisterFailure);
            }
        }

        ContainerIndex index = containerIndex;
        if (index != null) index.stopAccepting();
        BukkitTask tickTask = indexTickTask;
        if (tickTask != null) tickTask.cancel();
        if (indexListener != null) HandlerList.unregisterAll(indexListener);

        if (index != null) {
            try {
                index.close();
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }
        IndexWorker worker = indexWorker;
        if (worker != null) {
            try {
                worker.close();
            } catch (InterruptedException closeFailure) {
                Thread.currentThread().interrupt();
                failure = appendFailure(failure, closeFailure);
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }

        containerIndex = null;
        indexWorker = null;
        indexListener = null;
        indexTickTask = null;
        sessionManager = null;
        sessionListener = null;
        kitsuneCommand = null;

        if (failure != null) {
            plugin.getLogger().log(Level.SEVERE, "Failed to close indexing resources", failure);
        }
    }

    private void onBootstrapFutureComplete(
        long generation,
        ExecutorService executor,
        BootstrapOpen result,
        Throwable failure
    ) {
        boolean retained = false;
        if (result != null) {
            synchronized (this) {
                if (bootstrapExecutor == executor && lifecycle.isCurrent(generation)) {
                    pendingBootstrap = result;
                    retained = true;
                }
            }
            if (!retained) {
                closeUnpublishedRepository(result);
                clearBootstrapExecutor(executor);
                executor.shutdown();
                return;
            }
        } else if (!lifecycle.isCurrent(generation)) {
            clearBootstrapExecutor(executor);
            executor.shutdown();
            return;
        }

        try {
            server.getScheduler().runTask(plugin, () -> {
                try {
                    if (failure != null) {
                        closeUnpublishedRepository(takePendingBootstrap(result));
                        if (!lifecycle.isStopping()) {
                            failStart(unwrapFailure(failure));
                        }
                        return;
                    }

                    BootstrapOpen owned = takePendingBootstrap(result);
                    if (owned == null) return;
                    if (!lifecycle.isCurrent(generation)) {
                        closeUnpublishedRepository(owned);
                        return;
                    }
                    publishBootstrap(generation, owned);
                } finally {
                    clearBootstrapExecutor(executor);
                    executor.shutdown();
                }
            });
        } catch (Throwable schedulingFailure) {
            closeUnpublishedRepository(takePendingBootstrap(result));
            clearBootstrapExecutor(executor);
            executor.shutdown();
            if (!lifecycle.isStopping()) failStart(schedulingFailure);
        }
    }

    private void publishBootstrap(long generation, BootstrapOpen open) {
        if (!lifecycle.isCurrent(generation) || open == null || !open.claim()) {
            closeUnpublishedRepository(open);
            return;
        }

        ContainerIndex localIndex = null;
        IndexWorker localWorker = null;
        IndexListener localListener = null;
        BukkitTask localTickTask = null;
        SearchSessionManager localSessionManager = null;
        SessionListener localSessionListener = null;
        dev.jlo.kitsune.command.KitsuneCommand localCommand = null;
        Throwable failure = null;

        try {
            localSessionManager = new SearchSessionManager(
                new BukkitSessionScheduler(plugin),
                Duration.ofSeconds(open.config().markerDurationSeconds())
            );
            localWorker = new IndexWorker(open.repository());
            dev.jlo.kitsune.index.RootResolver<Inventory> rootResolver = BukkitRootResolver.forServer(server);
            TraversalLimits limits = new TraversalLimits(
                open.config().maximumDepth(),
                open.config().maximumStacksPerRoot()
            );
            NestedItemWalker<ItemStack> walker = new NestedItemWalker<>(
                limits,
                new BukkitTraversalAdapter(server)
            );
            ContainerSnapshotter snapshotter = new ContainerSnapshotter(rootResolver, walker);
            AtomicLong currentTick = tick;
            localIndex = new ContainerIndex(
                rootResolver,
                snapshotter,
                localWorker,
                open.repository(),
                open.embeddingProvider(),
                open.config().chunksPerTick(),
                open.config().rootsPerTick(),
                open.config().reconciliationPeriodTicks(),
                currentTick::get,
                localSessionManager::invalidateRoot
            );
            ContainerIndex readyIndex = localIndex;
            localListener = new IndexListener(localIndex);
            server.getPluginManager().registerEvents(localListener, plugin);
            localTickTask = server.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    tick.incrementAndGet();
                    readyIndex.tick();
                },
                1L,
                1L
            );

            for (World world : server.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) localIndex.onChunkLoaded(chunk);
            }

            SearchPolicy searchPolicy = new SearchPolicy(
                open.config().radius(),
                open.config().minimumScore(),
                open.config().maxResults(),
                open.config().maxPathsPerRoot(),
                Duration.ofSeconds(open.config().warmupTimeoutSeconds())
            );
            BukkitServerThreadBridge serverThread = new BukkitServerThreadBridge(plugin);
            ProtectionRegistry protectionRegistry = new ProtectionRegistry(
                serviceProviders(BlockAccessProvider.class),
                plugin.getLogger()
            );
            BukkitLiveRootAccess liveRoots = new BukkitLiveRootAccess(
                server,
                snapshotter,
                protectionRegistry
            );
            SearchService searchService = new SearchService(
                localIndex::awaitReady,
                localWorker,
                open.repository(),
                open.embeddingProvider(),
                serverThread,
                liveRoots,
                searchPolicy
            );

            localSessionListener = new SessionListener(localSessionManager);
            server.getPluginManager().registerEvents(localSessionListener, plugin);
            localCommand = new dev.jlo.kitsune.command.KitsuneCommand(
                server,
                searchService,
                localSessionManager,
                serverThread,
                liveRoots,
                searchPolicy,
                new ChatTreeRenderer(),
                new MarkerRenderer(plugin)
            );
            PluginCommand pluginCommand = Objects.requireNonNull(
                plugin.getCommand("kitsune"),
                "Missing /kitsune command declaration"
            );
            pluginCommand.setExecutor(localCommand);

            containerIndex = localIndex;
            indexWorker = localWorker;
            indexListener = localListener;
            indexTickTask = localTickTask;
            sessionManager = localSessionManager;
            sessionListener = localSessionListener;
            kitsuneCommand = localCommand;
            plugin.getLogger().info("Kitsune enabled");
        } catch (Throwable publishFailure) {
            failure = publishFailure;
            if (localCommand != null) localCommand.stop();
            if (localSessionManager != null) localSessionManager.clearAll();
            if (localSessionListener != null) HandlerList.unregisterAll(localSessionListener);
            setCommandUnavailable("Kitsune is unavailable.");
            if (localTickTask != null) localTickTask.cancel();
            if (localListener != null) HandlerList.unregisterAll(localListener);
            if (localIndex != null) {
                try {
                    localIndex.close();
                } catch (Throwable closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
            }
            if (localWorker != null) {
                try {
                    localWorker.close();
                } catch (InterruptedException closeFailure) {
                    Thread.currentThread().interrupt();
                    failure = appendFailure(failure, closeFailure);
                } catch (Throwable closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
            } else {
                closeRepositoryOffThread(open.repository()).whenComplete((ignored, closeFailure) -> {
                    if (closeFailure != null) {
                        plugin.getLogger().log(
                            Level.SEVERE,
                            "Failed to close unpublished index repository",
                            unwrapFailure(closeFailure)
                        );
                    }
                });
            }

            if (!lifecycle.isStopping()) {
                plugin.getLogger().log(Level.SEVERE, "Failed to initialize indexing lifecycle", failure);
                server.getPluginManager().disablePlugin(plugin);
            }
        }
    }

    private void registerLwcIntegrationIfEnabled() {
        Plugin lwcPlugin = server.getPluginManager().getPlugin("LWC");
        if (lwcPlugin == null || !lwcPlugin.isEnabled()) return;

        BlockAccessProvider provider = null;
        try {
            provider = new LwcProtectionProvider();
            server.getServicesManager().register(
                BlockAccessProvider.class,
                provider,
                plugin,
                ServicePriority.Normal
            );
            lwcProtectionProvider = provider;
        } catch (Throwable failure) {
            if (provider != null) {
                try {
                    server.getServicesManager().unregister(BlockAccessProvider.class, provider);
                } catch (Throwable unregisterFailure) {
                    failure.addSuppressed(unregisterFailure);
                }
            }
            throw new IllegalStateException(LWC_INCOMPATIBILITY_MESSAGE, failure);
        }
    }

    private boolean setCommandUnavailable(String message) {
        PluginCommand command = plugin.getCommand("kitsune");
        if (command == null) return false;
        command.setExecutor((sender, ignoredCommand, ignoredLabel, ignoredArguments) -> {
            sender.sendMessage(message);
            return true;
        });
        return true;
    }

    private void failStart(Throwable failure) {
        plugin.getLogger().log(Level.SEVERE, "Failed to initialize indexing lifecycle", failure);
        if (!lifecycle.isStopping()) server.getPluginManager().disablePlugin(plugin);
    }

    private <T> List<T> serviceProviders(Class<T> type) {
        List<T> providers = new ArrayList<>();
        for (RegisteredServiceProvider<T> registration : server.getServicesManager().getRegistrations(type)) {
            T provider = registration.getProvider();
            if (provider != null) providers.add(provider);
        }
        return List.copyOf(providers);
    }

    private void closeUnpublishedRepository(BootstrapOpen bootstrap) {
        if (bootstrap == null || !bootstrap.claim()) return;
        closeRepositoryOffThread(bootstrap.repository()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to close unpublished index repository",
                    unwrapFailure(failure)
                );
            }
        });
    }

    private static CompletableFuture<Void> closeRepositoryOffThread(IndexRepository repository) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        try {
            Thread.ofPlatform().name("kitsune-close").start(() -> {
                try {
                    repository.close();
                    closed.complete(null);
                } catch (Throwable failure) {
                    closed.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            closed.completeExceptionally(failure);
        }
        return closed;
    }

    private synchronized void clearBootstrapExecutor(ExecutorService executor) {
        if (bootstrapExecutor == executor) bootstrapExecutor = null;
    }

    private synchronized BootstrapOpen takePendingBootstrap(BootstrapOpen expected) {
        if (pendingBootstrap != expected) return null;
        pendingBootstrap = null;
        return expected;
    }

    private static Throwable appendFailure(Throwable prior, Throwable current) {
        if (prior == null) return current;
        prior.addSuppressed(current);
        return prior;
    }

    private static Throwable unwrapFailure(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    static final class BootstrapLifecycle {
        private final AtomicLong generation = new AtomicLong();
        private final AtomicBoolean stopping = new AtomicBoolean();

        long begin() {
            stopping.set(false);
            return generation.incrementAndGet();
        }

        void stop() {
            stopping.set(true);
            generation.incrementAndGet();
        }

        boolean isCurrent(long expectedGeneration) {
            return !stopping.get() && expectedGeneration == generation.get();
        }

        boolean isStopping() {
            return stopping.get();
        }
    }

    static final class BootstrapOpen {
        private final KitsuneConfig config;
        private final EmbeddingProvider embeddingProvider;
        private final IndexRepository repository;
        private final AtomicBoolean claimed = new AtomicBoolean();

        BootstrapOpen(KitsuneConfig config, EmbeddingProvider embeddingProvider, IndexRepository repository) {
            this.config = config;
            this.embeddingProvider = embeddingProvider;
            this.repository = repository;
        }

        KitsuneConfig config() {
            return config;
        }

        EmbeddingProvider embeddingProvider() {
            return embeddingProvider;
        }

        IndexRepository repository() {
            return repository;
        }

        boolean claim() {
            return claimed.compareAndSet(false, true);
        }
    }
}
