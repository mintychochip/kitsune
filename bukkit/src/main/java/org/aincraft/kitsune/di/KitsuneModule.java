package org.aincraft.kitsune.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.aincraft.kitsune.BukkitPlatform;
import org.aincraft.kitsune.Platform;
import org.aincraft.kitsune.api.serialization.TagProviderRegistry;
import org.aincraft.kitsune.cache.ItemDataCache;
import org.aincraft.kitsune.config.Configuration;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.embedding.EmbeddingService;
import org.aincraft.kitsune.embedding.EmbeddingServiceFactory;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.aincraft.kitsune.indexing.ContainerIndexer;
import org.aincraft.kitsune.listener.*;
import org.aincraft.kitsune.protection.ProtectionProvider;
import org.aincraft.kitsune.protection.ProtectionProviderFactory;
import org.aincraft.kitsune.serialization.BukkitDataComponentTagProvider;
import org.aincraft.kitsune.serialization.BukkitItemSerializer;
import org.aincraft.kitsune.serialization.providers.TagProviders;
import org.aincraft.kitsune.storage.KitsuneStorage;
import org.aincraft.kitsune.storage.PlayerRadiusStorage;
import org.aincraft.kitsune.storage.ProviderMetadata;
import org.aincraft.kitsune.storage.SearchHistoryStorage;
import org.aincraft.kitsune.storage.metadata.ContainerStorage;
import org.aincraft.kitsune.storage.vector.JVectorIndex;
import org.aincraft.kitsune.api.indexing.ContainerLocationResolver;
import org.aincraft.kitsune.cache.EmbeddingCache;
import org.aincraft.kitsune.cache.LayeredEmbeddingCache;
import org.aincraft.kitsune.util.BukkitContainerLocationResolver;
import org.aincraft.kitsune.visualizer.ContainerItemDisplay;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class KitsuneModule extends AbstractModule {

    private final JavaPlugin plugin;

    public KitsuneModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void configure() {
        // Bind core instances first
        bind(JavaPlugin.class).toInstance(plugin);
        bind(Plugin.class).toInstance(plugin);
        bind(Logger.class).annotatedWith(Names.named("Logger")).toInstance(plugin.getLogger());
        bind(Path.class).toInstance(plugin.getDataFolder().toPath());

        bind(EmbeddingServiceFactory.class).in(Singleton.class);
        bind(ContainerLocationResolver.class).to(BukkitContainerLocationResolver.class).in(Singleton.class);

        Multibinder<Listener> listenerBinder = Multibinder.newSetBinder(binder(), Listener.class);
        listenerBinder.addBinding().to(ContainerCloseListener.class);
        listenerBinder.addBinding().to(HopperTransferListener.class);
        listenerBinder.addBinding().to(ContainerBreakListener.class);
        listenerBinder.addBinding().to(ChestPlaceListener.class);
        listenerBinder.addBinding().to(PlayerQuitListener.class);
    }

    @Provides @Singleton
    Configuration provideConfiguration(JavaPlugin plugin) {
        var section = plugin.getConfig();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getString" -> section.getString((String) args[0], (String) args[1]);
            case "getInt" -> section.getInt((String) args[0], (int) args[1]);
            case "getBoolean" -> section.getBoolean((String) args[0], (boolean) args[1]);
            case "getDouble" -> section.getDouble((String) args[0], (double) args[1]);
            case "getKeys" -> {
                var sub = section.getConfigurationSection((String) args[0]);
                yield sub == null ? Collections.emptySet() : sub.getKeys(false);
            }
            case "toString" -> section.toString();
            default -> throw new UnsupportedOperationException("Method not supported: " + method.getName());
        };
        return (Configuration) Proxy.newProxyInstance(
            Configuration.class.getClassLoader(),
            new Class<?>[] { Configuration.class },
            handler
        );
    }

    @Provides @Singleton
    KitsuneConfig provideKitsuneConfig(Configuration config) {
        return new KitsuneConfig(config);
    }

    @Provides @Singleton
    TagProviderRegistry provideTagProviderRegistry() {
        return TagProviderRegistry.INSTANCE;
    }

    @Provides @Singleton
    Platform providePlatform(JavaPlugin plugin, TagProviderRegistry registry) {
        BukkitPlatform platform = new BukkitPlatform(plugin, registry);
        Platform.set(platform);
        return platform;
    }

    @Provides @Singleton
    DataSource provideDataSource(Platform platform) {
        Path dbFile = platform.getDataFolder().resolve("kitsune.db");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setLeakDetectionThreshold(30000);
        return new HikariDataSource(hikariConfig);
    }

    @Provides @Singleton
    EmbeddingCache provideEmbeddingCache(DataSource dataSource, Logger logger) {
        return new LayeredEmbeddingCache(dataSource, logger);
    }

    @Provides @Singleton
    ContainerStorage provideContainerStorage(DataSource dataSource, Logger logger) {
        return new ContainerStorage(dataSource, logger);
    }

    @Provides @Singleton
    JVectorIndex provideJVectorIndex(Logger logger, Platform platform, EmbeddingServiceFactory factory) {
        return new JVectorIndex(logger, platform.getDataFolder(), factory.getDimension());
    }

    @Provides @Singleton
    KitsuneStorage provideKitsuneStorage(Logger logger, ContainerStorage containerStorage, JVectorIndex vectorIndex) {
        return new KitsuneStorage(logger, containerStorage, vectorIndex);
    }

    @Provides @Singleton
    EmbeddingService provideEmbeddingService(EmbeddingServiceFactory factory) {
        return factory.create();
    }

    @Provides @Singleton
    BukkitItemSerializer provideBukkitItemSerializer(TagProviderRegistry registry) {
        registry.register(new BukkitDataComponentTagProvider());
        TagProviders.registerDefaults(registry);
        return new BukkitItemSerializer(registry);
    }

    @Provides @Singleton
    BukkitContainerIndexer provideBukkitContainerIndexer(
            Logger logger,
            EmbeddingService embeddingService,
            KitsuneStorage storage,
            KitsuneConfig config,
            BukkitItemSerializer itemSerializer) {
        return new BukkitContainerIndexer(logger, embeddingService, storage, config, itemSerializer);
    }

    @Provides @Singleton
    ContainerIndexer provideContainerIndexer(BukkitContainerIndexer impl) {
        return impl;
    }

    @Provides @Singleton
    Optional<ProtectionProvider> provideProtectionProvider(
            KitsuneConfig config,
            JavaPlugin plugin,
            Logger logger) {
        return ProtectionProviderFactory.create(config, plugin, logger);
    }

    @Provides @Singleton @Named("searchHistoryExecutor")
    ExecutorService provideSearchHistoryExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Provides @Singleton @Named("playerRadiusExecutor")
    ExecutorService providePlayerRadiusExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Provides @Singleton
    SearchHistoryStorage provideSearchHistoryStorage(
            Logger logger,
            KitsuneStorage storage,
            @Named("searchHistoryExecutor") ExecutorService executor) {
        return new SearchHistoryStorage(logger, storage.getDataSource(), executor);
    }

    @Provides @Singleton
    PlayerRadiusStorage providePlayerRadiusStorage(
            Logger logger,
            KitsuneConfig config,
            KitsuneStorage storage,
            @Named("playerRadiusExecutor") ExecutorService executor) {
        return new PlayerRadiusStorage(logger, storage.getDataSource(), executor, config.searchRadius());
    }

    @Provides @Singleton
    ItemDataCache provideItemDataCache() {
        return new ItemDataCache();
    }

    @Provides @Singleton
    ProviderMetadata provideProviderMetadata(Logger logger, Platform platform) {
        return new ProviderMetadata(logger, platform.getDataFolder());
    }

    @Provides @Singleton
    ContainerItemDisplay provideContainerItemDisplay(Logger logger, KitsuneConfig config, JavaPlugin plugin) {
        return new ContainerItemDisplay(logger, config, plugin);
    }
}
