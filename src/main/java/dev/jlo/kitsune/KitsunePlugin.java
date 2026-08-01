package dev.jlo.kitsune;

import dev.jlo.kitsune.config.ConfigLoader;
import dev.jlo.kitsune.config.KitsuneConfig;
import dev.jlo.kitsune.api.embedding.EmbeddingProvider;
import dev.jlo.kitsune.embedding.EmbeddingRegistry;
import dev.jlo.kitsune.index.ContainerIndex;
import dev.jlo.kitsune.index.ContainerSnapshotter;
import dev.jlo.kitsune.index.IndexListener;
import dev.jlo.kitsune.index.IndexRepository;
import dev.jlo.kitsune.index.IndexWorker;
import dev.jlo.kitsune.index.RootResolver;
import dev.jlo.kitsune.index.SqliteIndexRepository;
import dev.jlo.kitsune.item.NestedItemWalker;
import dev.jlo.kitsune.item.TraversalLimits;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class KitsunePlugin extends JavaPlugin {
    private final BootstrapLifecycle bootstrapLifecycle = new BootstrapLifecycle();

    private KitsuneConfig config;
    private ContainerIndex containerIndex;
    private IndexWorker indexWorker;
    private IndexListener indexListener;
    private BukkitTask indexTickTask;

    private ExecutorService bootstrapExecutor;
    private BootstrapOpen pendingBootstrap;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final KitsuneConfig loadedConfig;
        final EmbeddingProvider embeddingProvider;

        try {
            loadedConfig = ConfigLoader.load(this);
            EmbeddingRegistry registry = new EmbeddingRegistry(
                    getServer().getServicesManager(),
                    loadedConfig.embeddingProvider()
            );
            embeddingProvider = registry.selectedProvider();
        } catch (Throwable failure) {
            getLogger().log(
                    Level.SEVERE,
                    "Failed to initialize indexing lifecycle",
                    failure
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long generation = bootstrapLifecycle.begin();
        Path indexPath = getDataFolder().toPath().resolve("index.sqlite");

        ExecutorService executor = Executors.newSingleThreadExecutor(
                task -> Thread.ofPlatform().name("kitsune-bootstrap").unstarted(task)
        );
        CompletableFuture<BootstrapOpen> future = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return new BootstrapOpen(
                                loadedConfig,
                                embeddingProvider,
                                SqliteIndexRepository.open(indexPath, embeddingProvider)
                        );
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                },
                executor
        );
        synchronized (this) {
            bootstrapExecutor = executor;
        }
        future.whenComplete((result, failure) ->
                onBootstrapFutureComplete(generation, executor, result, failure));
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
                if (bootstrapExecutor == executor
                        && bootstrapLifecycle.isCurrent(generation)) {
                    pendingBootstrap = result;
                    retained = true;
                }
            }
            if (!retained) {
                closeUnpublishedRepository(result);
                clearBootstrapFuture(executor);
                finishBootstrapExecutor(executor);
                return;
            }
        } else if (!bootstrapLifecycle.isCurrent(generation)) {
            clearBootstrapFuture(executor);
            finishBootstrapExecutor(executor);
            return;
        }

        try {
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    if (failure != null) {
                        getLogger().log(
                                Level.SEVERE,
                                "Failed to initialize indexing lifecycle",
                                unwrapFailure(failure)
                        );
                        if (!bootstrapLifecycle.isStopping()) {
                            getServer().getPluginManager().disablePlugin(this);
                        }
                        return;
                    }

                    BootstrapOpen owned = takePendingBootstrap(result);
                    if (owned == null) return;
                    if (!bootstrapLifecycle.isCurrent(generation)) {
                        closeUnpublishedRepository(owned);
                        return;
                    }
                    publishBootstrap(generation, owned);
                } finally {
                    clearBootstrapFuture(executor);
                    finishBootstrapExecutor(executor);
                }
            });
        } catch (Throwable schedulingFailure) {
            closeUnpublishedRepository(takePendingBootstrap(result));
            clearBootstrapFuture(executor);
            finishBootstrapExecutor(executor);
            if (!bootstrapLifecycle.isStopping()) {
                getLogger().log(
                        Level.SEVERE,
                        "Failed to publish indexing lifecycle on the server thread",
                        schedulingFailure
                );
                bootstrapLifecycle.stop();
            }
        }
    }

    private void publishBootstrap(
            long generation,
            BootstrapOpen open
    ) {
        if (!bootstrapLifecycle.isCurrent(generation) || open == null) {
            closeUnpublishedRepository(open);
            return;
        }
        if (!open.claim()) return;

        ContainerIndex localIndex = null;
        IndexWorker localWorker = null;
        IndexListener localListener = null;
        BukkitTask localTickTask = null;
        Throwable failure = null;

        try {
            localWorker = new IndexWorker(open.repository());
            RootResolver<Inventory> rootResolver = RootResolver.forServer(getServer());
            TraversalLimits limits = new TraversalLimits(
                    open.config().maximumDepth(),
                    open.config().maximumStacksPerRoot()
            );
            NestedItemWalker<ItemStack> walker = NestedItemWalker.forBukkit(
                    getServer(),
                    limits
            );
            ContainerSnapshotter snapshotter = new ContainerSnapshotter(
                    rootResolver,
                    walker
            );
            localIndex = new ContainerIndex(
                    rootResolver,
                    snapshotter,
                    localWorker,
                    open.repository(),
                    open.embeddingProvider(),
                    open.config().chunksPerTick(),
                    open.config().rootsPerTick(),
                    open.config().reconciliationPeriodTicks(),
                    Bukkit::getCurrentTick
            );
            localListener = new IndexListener(localIndex);
            getServer().getPluginManager().registerEvents(localListener, this);
            localTickTask = Bukkit.getScheduler().runTaskTimer(
                    this,
                    localIndex::tick,
                    1L,
                    1L
            );

            for (World world : getServer().getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    localIndex.onChunkLoaded(chunk);
                }
            }

            config = open.config();
            containerIndex = localIndex;
            indexWorker = localWorker;
            indexListener = localListener;
            indexTickTask = localTickTask;
            getLogger().info("Kitsune enabled");
        } catch (Throwable publishFailure) {
            failure = publishFailure;
            if (localTickTask != null) {
                localTickTask.cancel();
            }
            if (localListener != null) {
                HandlerList.unregisterAll(localListener);
            }
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
                closeRepositoryOffThread(open.repository())
                        .whenComplete((ignored, closeFailure) -> {
                            if (closeFailure != null) {
                                getLogger().log(
                                        Level.SEVERE,
                                        "Failed to close unpublished index repository",
                                        unwrapFailure(closeFailure)
                                );
                            }
                        });
            }

            if (!bootstrapLifecycle.isStopping()) {
                getLogger().log(
                        Level.SEVERE,
                        "Failed to initialize indexing lifecycle",
                        failure
                );
                getServer().getPluginManager().disablePlugin(this);
            } else if (failure != null) {
                getLogger().log(
                        Level.SEVERE,
                        "Failed to initialize indexing lifecycle during shutdown",
                        failure
                );
            }
        }
    }

    @Override
    public void onDisable() {
        bootstrapLifecycle.stop();

        BootstrapOpen unpublished;
        ExecutorService bootstrap;
        synchronized (this) {
            unpublished = pendingBootstrap;
            pendingBootstrap = null;
            bootstrap = bootstrapExecutor;
            bootstrapExecutor = null;
        }
        closeUnpublishedRepository(unpublished);
        if (bootstrap != null) bootstrap.shutdownNow();

        Throwable failure = null;

        if (containerIndex != null) {
            containerIndex.stopAccepting();
        }

        if (indexTickTask != null) {
            indexTickTask.cancel();
        }

        if (indexListener != null) {
            HandlerList.unregisterAll(indexListener);
        }

        if (containerIndex != null) {
            try {
                containerIndex.close();
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }

        if (indexWorker != null) {
            try {
                indexWorker.close();
            } catch (InterruptedException closeFailure) {
                failure = appendFailure(failure, closeFailure);
                Thread.currentThread().interrupt();
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }

        if (failure != null) {
            getLogger().log(Level.SEVERE, "Failed to close indexing resources", failure);
        }

        containerIndex = null;
        indexWorker = null;
        indexListener = null;
        indexTickTask = null;

        getLogger().info("Kitsune disabled");
    }

    private void closeUnpublishedRepository(BootstrapOpen bootstrap) {
        if (bootstrap == null || !bootstrap.claim()) return;
        closeRepositoryOffThread(bootstrap.repository())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        getLogger().log(
                                Level.SEVERE,
                                "Failed to close unpublished index repository",
                                unwrapFailure(failure)
                        );
                    }
                });
    }

    static CompletableFuture<Void> closeRepositoryOffThread(
            IndexRepository repository
    ) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        try {
            Thread.ofPlatform().name("kitsune-close").start(() -> {
                try {
                    repository.close();
                    closed.complete(null);
                } catch (Throwable closeFailure) {
                    closed.completeExceptionally(closeFailure);
                }
            });
        } catch (Throwable startFailure) {
            closed.completeExceptionally(startFailure);
        }
        return closed;
    }


    private void finishBootstrapExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private synchronized void clearBootstrapFuture(
            ExecutorService executor
    ) {
        if (bootstrapExecutor != executor) return;
        bootstrapExecutor = null;
    }

    private synchronized BootstrapOpen takePendingBootstrap(
            BootstrapOpen expected
    ) {
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

        BootstrapOpen(
                KitsuneConfig config,
                EmbeddingProvider embeddingProvider,
                IndexRepository repository
        ) {
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
