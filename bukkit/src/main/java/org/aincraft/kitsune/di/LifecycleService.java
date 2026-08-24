package org.aincraft.kitsune.di;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.aincraft.kitsune.api.KitsuneService;
import org.aincraft.kitsune.cache.ItemDataCache;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.embedding.EmbeddingService;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.aincraft.kitsune.storage.KitsuneStorage;
import org.aincraft.kitsune.storage.PlayerRadiusStorage;
import org.aincraft.kitsune.storage.ProviderMetadata;
import org.aincraft.kitsune.storage.SearchHistoryStorage;
import org.aincraft.kitsune.visualizer.ContainerItemDisplay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

@Singleton
public class LifecycleService {
    private final Logger logger;
    private final EmbeddingService embeddingService;
    private final KitsuneStorage storage;
    private final SearchHistoryStorage searchHistoryStorage;
    private final PlayerRadiusStorage playerRadiusStorage;
    private final ProviderMetadata providerMetadata;
    private final KitsuneConfig config;
    private final BukkitContainerIndexer containerIndexer;
    private final ContainerItemDisplay itemDisplayVisualizer;
    private final ItemDataCache itemDataCache;
    private final @Named("searchHistoryExecutor") ExecutorService searchHistoryExecutor;
    private final @Named("playerRadiusExecutor") ExecutorService playerRadiusExecutor;

    private volatile boolean initialized = false;
    private volatile boolean providerMismatch = false;

    @Inject
    public LifecycleService(
            Logger logger,
            EmbeddingService embeddingService,
            KitsuneStorage storage,
            SearchHistoryStorage searchHistoryStorage,
            PlayerRadiusStorage playerRadiusStorage,
            ProviderMetadata providerMetadata,
            KitsuneConfig config,
            BukkitContainerIndexer containerIndexer,
            ContainerItemDisplay itemDisplayVisualizer,
            ItemDataCache itemDataCache,
            @Named("searchHistoryExecutor") ExecutorService searchHistoryExecutor,
            @Named("playerRadiusExecutor") ExecutorService playerRadiusExecutor) {
        this.logger = logger;
        this.embeddingService = embeddingService;
        this.storage = storage;
        this.searchHistoryStorage = searchHistoryStorage;
        this.playerRadiusStorage = playerRadiusStorage;
        this.providerMetadata = providerMetadata;
        this.config = config;
        this.containerIndexer = containerIndexer;
        this.itemDisplayVisualizer = itemDisplayVisualizer;
        this.itemDataCache = itemDataCache;
        this.searchHistoryExecutor = searchHistoryExecutor;
        this.playerRadiusExecutor = playerRadiusExecutor;
    }

    public CompletableFuture<Void> initialize() {
        return embeddingService.initialize()
            .thenRun(() -> storage.initialize())
            .thenCompose(v -> searchHistoryStorage.initialize()
                .thenCompose(v2 -> playerRadiusStorage.initialize()))
            .thenRun(() -> {
                providerMetadata.load();
                checkProviderMismatch();
                initialized = true;
                logger.info("All services initialized successfully!");
            });
    }

    public void shutdown() {
        logger.info("Shutting down Kitsune services...");

        if (containerIndexer != null) containerIndexer.shutdown();
        if (storage != null) storage.shutdown();
        if (embeddingService != null) embeddingService.shutdown();
        if (searchHistoryStorage != null) searchHistoryStorage.close();
        if (playerRadiusStorage != null) playerRadiusStorage.close();
        if (searchHistoryExecutor != null) searchHistoryExecutor.shutdownNow();
        if (playerRadiusExecutor != null) playerRadiusExecutor.shutdownNow();
        if (itemDisplayVisualizer != null) itemDisplayVisualizer.cleanupAll();
        if (itemDataCache != null) itemDataCache.clear();

        KitsuneService.unregister();
        logger.info("Kitsune disabled.");
    }

    private void checkProviderMismatch() {
        String currentProvider = config.embeddingProvider();
        String currentModel = config.embeddingModel();

        providerMetadata.checkMismatch(currentProvider, currentModel).ifPresentOrElse(
            mismatch -> {
                providerMismatch = true;
                logger.warning("=".repeat(60));
                logger.warning("EMBEDDING PROVIDER CHANGED!");
                logger.warning(mismatch.message());
                logger.warning("Indexing and search are DISABLED until you run:");
                logger.warning("  /kitsune purge");
                logger.warning("=".repeat(60));
            },
            () -> providerMetadata.save(currentProvider, currentModel)
        );
    }

    public boolean isInitialized() { return initialized; }
    public boolean hasProviderMismatch() { return providerMismatch; }
}
