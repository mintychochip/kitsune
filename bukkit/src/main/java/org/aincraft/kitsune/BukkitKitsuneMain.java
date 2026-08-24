package org.aincraft.kitsune;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.ClickEvent;
import org.aincraft.kitsune.api.KitsuneService;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.api.model.ContainerNode;
import org.aincraft.kitsune.api.serialization.TagProviderRegistry;
import org.aincraft.kitsune.model.SearchHistoryEntry;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.storage.SearchHistoryStorage;
import org.aincraft.kitsune.embedding.CachedEmbeddingService;
import org.aincraft.kitsune.embedding.EmbeddingService;
import org.aincraft.kitsune.embedding.EmbeddingServiceFactory;
import org.aincraft.kitsune.indexing.BukkitContainerIndexer;
import org.aincraft.kitsune.serialization.*;
import org.aincraft.kitsune.serialization.BukkitDataComponentTagProvider;
// import org.aincraft.kitsune.serialization.OraxenTagProvider;
import org.aincraft.kitsune.serialization.providers.TagProviders;
import org.aincraft.kitsune.listener.ChestPlaceListener;
import org.aincraft.kitsune.listener.ContainerBreakListener;
import org.aincraft.kitsune.listener.ContainerCloseListener;
import org.aincraft.kitsune.listener.HopperTransferListener;
import org.aincraft.kitsune.listener.PlayerQuitListener;
import java.util.logging.Level;
import org.aincraft.kitsune.protection.ProtectionProvider;
import org.aincraft.kitsune.protection.ProtectionProviderFactory;
import org.aincraft.kitsune.storage.ProviderMetadata;
import org.aincraft.kitsune.storage.PlayerRadiusStorage;
import org.aincraft.kitsune.storage.KitsuneStorage;
import org.aincraft.kitsune.storage.metadata.ContainerStorage;
import org.aincraft.kitsune.storage.vector.JVectorIndex;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.aincraft.kitsune.util.BukkitContainerLocationResolver;
import org.aincraft.kitsune.util.BukkitItemLoader;
import org.aincraft.kitsune.model.tree.SearchResultTreeRenderer;
import org.aincraft.kitsune.model.tree.SearchResultTreeBuilder;
import org.aincraft.kitsune.visualizer.ContainerItemDisplay;
import org.aincraft.kitsune.cache.ItemDataCache;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Stage;
import com.google.inject.TypeLiteral;
import jakarta.inject.Inject;
import org.aincraft.kitsune.di.KitsuneModule;
import org.aincraft.kitsune.di.LifecycleService;
import org.bukkit.event.Listener;
import java.util.AbstractMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

public final class BukkitKitsuneMain extends JavaPlugin {

    @Inject
    private KitsuneConfig kitsuneConfig;
    @Inject
    private Optional<ProtectionProvider> protectionProvider;
    @Inject
    private KitsuneStorage storage;
    @Inject
    private EmbeddingService embeddingService;
    @Inject
    private SearchHistoryStorage searchHistoryStorage;
    @Inject
    private PlayerRadiusStorage playerRadiusStorage;
    @Inject
    private ProviderMetadata providerMetadata;
    @Inject
    private BukkitContainerIndexer containerIndexer;
    @Inject
    private ContainerItemDisplay itemDisplayVisualizer;
    @Inject
    private ItemDataCache itemDataCache;
    @Inject
    private LifecycleService lifecycleService;

    private Injector injector;
    private volatile boolean initialized = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Create Guice injector in DEVELOPMENT stage to prevent eager binding resolution
        injector = Guice.createInjector(Stage.DEVELOPMENT, new KitsuneModule(this));

        // Inject this instance
        injector.injectMembers(this);

        // Register tag provider for external plugins
        KitsuneService.register(injector.getInstance(TagProviderRegistry.class));

        getLogger().info("Initializing Kitsune...");

        // Register Brigadier commands first
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            registerCommands(event.registrar().getDispatcher());
        });

        // Initialize services async
        lifecycleService.initialize().thenRun(() -> {
            registerListeners();
            initialized = true;
        }).exceptionally(ex -> {
            getLogger().log(Level.SEVERE, "Failed to initialize Kitsune", ex);
            return null;
        });
    }

    
    private void registerListeners() {
        Set<Listener> listeners = injector.getInstance(Key.get(new TypeLiteral<Set<Listener>>() {}));
        var pm = getServer().getPluginManager();
        for (Listener listener : listeners) {
            pm.registerEvents(listener, this);
        }
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /find <query> [radius]
        dispatcher.register(
            Commands.literal("find")
                .then(
                    Commands.argument("query", StringArgumentType.greedyString())
                        .then(
                            Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                                .executes(context -> executeSearch(context.getSource(),
                                    StringArgumentType.getString(context, "query"),
                                    kitsuneConfig.searchDefaultLimit(),
                                    IntegerArgumentType.getInteger(context, "radius")))
                        )
                        .executes(context -> executeSearch(context.getSource(),
                            StringArgumentType.getString(context, "query"),
                            kitsuneConfig.searchDefaultLimit(),
                            null))
                )
                .executes(context -> {
                    context.getSource().getSender().sendMessage("§cUsage: /find <query> [radius]");
                    return 0;
                })
        );

        // /kitsune <subcommand>
        dispatcher.register(
            Commands.literal("kitsune")
                .then(Commands.literal("help")
                    .executes(context -> {
                        sendHelpMessage(context.getSource());
                        return 1;
                    }))
                .then(Commands.literal("reload")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .executes(context -> {
                        reloadConfig();
                        // Rebind config via injector
                        injector.getInstance(Key.get(KitsuneConfig.class));
                        context.getSource().getSender().sendMessage("§aKitsune config reloaded.");
                        return 1;
                    }))
                .then(Commands.literal("stats")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .executes(context -> {
                        if (!lifecycleService.isInitialized()) {
                            context.getSource().getSender().sendMessage("§cKitsune is still initializing...");
                            return 0;
                        }
                        storage.getStats().thenAccept(stats -> {
                            context.getSource().getSender().sendMessage("§6Kitsune Stats:");
                            context.getSource().getSender().sendMessage("§7Indexed containers: §f" + stats.containerCount());
                            context.getSource().getSender().sendMessage("§7Storage provider: §f" + stats.providerName());
                        });
                        return 1;
                    }))
                .then(Commands.literal("reindex")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                        .executes(context -> {
                            if (!(context.getSource().getSender() instanceof Player player)) {
                                context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                                return 0;
                            }
                            int radius = IntegerArgumentType.getInteger(context, "radius");
                            context.getSource().getSender().sendMessage("§7Reindexing containers within " + radius + " blocks...");
                            containerIndexer.reindexRadius(player.getLocation(), radius)
                                .thenAccept(count -> player.sendMessage("§aReindexed " + count + " containers."));
                            return 1;
                        })))
                .then(Commands.literal("fill")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .executes(context -> {
                        if (!(context.getSource().getSender() instanceof Player player)) {
                            context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                            return 0;
                        }
                        return executeFillChest(player);
                    }))
                .then(Commands.literal("purge")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .executes(context -> {
                        if (!lifecycleService.isInitialized()) {
                            context.getSource().getSender().sendMessage("§cKitsune is still initializing...");
                            return 0;
                        }
                        return executePurge(context.getSource());
                    }))
                .then(Commands.literal("threshold")
                    .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                    .executes(context -> {
                        return executeGetThreshold(context.getSource());
                    })
                    .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return executeSetThreshold(context.getSource(), value);
                        })))
                .then(Commands.literal("history")
                    .executes(context -> {
                        if (!(context.getSource().getSender() instanceof Player player)) {
                            context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                            return 0;
                        }
                        return executeShowHistory(context.getSource(), player, 10);
                    })
                    .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                        .executes(context -> {
                            if (!(context.getSource().getSender() instanceof Player player)) {
                                context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                                return 0;
                            }
                            int limit = IntegerArgumentType.getInteger(context, "limit");
                            return executeShowHistory(context.getSource(), player, limit);
                        }))
                    .then(Commands.literal("clear")
                        .executes(context -> {
                            if (!(context.getSource().getSender() instanceof Player player)) {
                                context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                                return 0;
                            }
                            return executeClearHistory(context.getSource(), player);
                        }))
                    .then(Commands.literal("global")
                        .requires(source -> source.getSender().hasPermission("kitsune.admin"))
                        .executes(context -> executeShowGlobalHistory(context.getSource(), 20))))
                .then(Commands.literal("radius")
                    // No args: get self radius
                    .executes(context -> {
                        if (!(context.getSource().getSender() instanceof Player player)) {
                            context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                            return 0;
                        }
                        if (!context.getSource().getSender().hasPermission("kitsune.use")) {
                            context.getSource().getSender().sendMessage("§cYou don't have permission to use this command.");
                            return 0;
                        }
                        return executeGetSelfRadius(context.getSource(), player);
                    })
                    // Integer arg: set self radius
                    .then(Commands.argument("value", IntegerArgumentType.integer(1, 10000))
                        .executes(context -> {
                            if (!(context.getSource().getSender() instanceof Player player)) {
                                context.getSource().getSender().sendMessage("§cThis command can only be used by players.");
                                return 0;
                            }
                            if (!context.getSource().getSender().hasPermission("kitsune.use")) {
                                context.getSource().getSender().sendMessage("§cYou don't have permission to use this command.");
                                return 0;
                            }
                            int radius = IntegerArgumentType.getInteger(context, "value");
                            return executeSetSelfRadius(context.getSource(), player, radius);
                        }))
                    // String arg: get other player radius
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> {
                            if (!context.getSource().getSender().hasPermission("kitsune.admin")) {
                                context.getSource().getSender().sendMessage("§cYou don't have permission to use this command.");
                                return 0;
                            }
                            String playerName = StringArgumentType.getString(context, "player");
                            return executeGetRadius(context.getSource(), playerName);
                        })
                        // String + Integer args: set other player radius
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                            .executes(context -> {
                                if (!context.getSource().getSender().hasPermission("kitsune.admin")) {
                                    context.getSource().getSender().sendMessage("§cYou don't have permission to use this command.");
                                    return 0;
                                }
                                String playerName = StringArgumentType.getString(context, "player");
                                int radius = IntegerArgumentType.getInteger(context, "radius");
                                return executeSetRadius(context.getSource(), playerName, radius);
                            }))))

                .executes(context -> {
                    sendHelpMessage(context.getSource());
                    return 1;
                })
        );
    }

    private void sendHelpMessage(CommandSourceStack source) {
        source.getSender().sendMessage("§6Kitsune Commands:");
        source.getSender().sendMessage("§7/find <query> [radius] §f- Search containers");
        source.getSender().sendMessage("§7/kitsune help §f- Show this help");
        source.getSender().sendMessage("§7/kitsune reload §f- Reload config");
        source.getSender().sendMessage("§7/kitsune stats §f- Show statistics");
        source.getSender().sendMessage("§7/kitsune threshold [value] §f- Get/set search threshold");
        source.getSender().sendMessage("§7/kitsune history [limit] §f- View search history");
        source.getSender().sendMessage("§7/kitsune reindex <radius> §f- Reindex nearby");
        source.getSender().sendMessage("§7/kitsune fill §f- Fill looked-at chest with random items");
        source.getSender().sendMessage("§7/kitsune purge §f- Clear all vectors and cache");
        source.getSender().sendMessage("§7/kitsune radius [value] §f- Get/set your radius limit");
        if (source.getSender().hasPermission("kitsune.admin")) {
            source.getSender().sendMessage("§7/kitsune radius <player> [value] §f- Get/set player radius (admin)");
        }
    }

    private int executeSearch(CommandSourceStack source, String query, int limit, Integer customRadius) {
        if (!source.getSender().hasPermission("kitsune.use")) {
            source.getSender().sendMessage("§cYou don't have permission to use this command.");
            return 0;
        }

        if (!lifecycleService.isInitialized()) {
            source.getSender().sendMessage("§cKitsune is still initializing. Please wait...");
            return 0;
        }

        if (lifecycleService.hasProviderMismatch()) {
            source.getSender().sendMessage("§cEmbedding provider has changed. Run '/kitsune purge' first.");
            return 0;
        }

        Player player = source.getSender() instanceof Player p ? p : null;

        // Determine effective radius with validation
        int effectiveRadius = kitsuneConfig.searchRadius();

        if (customRadius != null) {
            if (player == null) {
                // Console can use default radius only
                source.getSender().sendMessage("§cConsole can only use default radius. Custom radius not supported.");
                return 0;
            }

            // Validate custom radius
            if (playerRadiusStorage != null) {
                // Async validation with PlayerRadiusStorage
                playerRadiusStorage.getMaxRadius(player.getUniqueId()).thenAccept(maxRadius -> {
                    if (customRadius > maxRadius) {
                        player.sendMessage("§cRadius " + customRadius + " exceeds your limit of " + maxRadius + " blocks.");
                    } else {
                        // Re-run search with validated radius
                        executeSearchWithRadius(source, player, query, limit, customRadius);
                    }
                });
                return 1;
            } else {
                // Fallback: validate against config max radius
                int configMaxRadius = kitsuneConfig.searchRadius();
                if (customRadius > configMaxRadius) {
                    source.getSender().sendMessage("§cRadius " + customRadius + " exceeds server limit of " + configMaxRadius + " blocks.");
                    return 0;
                }
                effectiveRadius = customRadius;
            }
        }

        // Execute search with determined radius
        executeSearchWithRadius(source, player, query, limit, effectiveRadius);
        return 1;
    }

    private void executeSearchWithRadius(CommandSourceStack source, Player player, String query, int limit, int radius) {
        // Use radius-limited search if player, otherwise global search
        CompletableFuture<List<org.aincraft.kitsune.model.SearchResult>> searchFuture;
        if (player != null) {
            source.getSender().sendMessage("§7Searching within " + radius + " blocks for: §f" + query);

            org.aincraft.kitsune.Location playerLocation = BukkitLocation.from(player.getLocation());
            searchFuture = embeddingService.embed(query.toLowerCase(), "RETRIEVAL_QUERY").thenCompose(embedding ->
                storage.searchWithinRadius(embedding, limit * 3, playerLocation, radius)
            );
        } else {
            source.getSender().sendMessage("§7Searching for: §f" + query);
            searchFuture = embeddingService.embed(query.toLowerCase(), "RETRIEVAL_QUERY").thenCompose(embedding ->
                storage.search(embedding, limit * 3, null) // Get 3x results for reranking
            );
        }

        searchFuture.thenCompose(results -> {
            // Get the threshold from database
            return storage.getThreshold()
                .thenApply(threshold -> new AbstractMap.SimpleImmutableEntry<>(results, threshold));
        }).thenAccept(entry -> {
            List<org.aincraft.kitsune.model.SearchResult> searchResults = entry.getKey();
            double threshold = entry.getValue();

            // Process results on main thread to allow accessing block entities
            getServer().getScheduler().runTask(this,
                () -> processSearchResults(source, player, searchResults, limit, threshold, query));
        }).exceptionally(ex -> {
            getLogger().log(Level.WARNING, "Search failed", ex);
            source.getSender().sendMessage("§cSearch failed: " + ex.getMessage());
            return null;
        });
    }

    private void processSearchResults(CommandSourceStack source, Player player,
                                       List<org.aincraft.kitsune.model.SearchResult> results, int limit,
                                       double threshold, String query) {
        if (results.isEmpty()) {
            source.getSender().sendMessage("§cNo containers found matching your query.");
            return;
        }

        // Clear previous displays
        if (itemDisplayVisualizer != null && player != null) {
            itemDisplayVisualizer.removeDisplaysForPlayer(player);
        }

        // Take top results
        results = results.subList(0, Math.min(limit, results.size()));

        // Filter results by minimum similarity threshold from database
        var filteredResults = results.stream()
            .filter(r -> r.score() > threshold)
            .toList();

        if (filteredResults.isEmpty()) {
            source.getSender().sendMessage("§cNo containers found matching your query.");
            return;
        }

        // Step 1: Filter results by protection using stream
        var accessibleResults = filteredResults.stream()
            .filter(result -> {
                // Filter by protection if player
                return player == null || protectionProvider.isEmpty() ||
                       protectionProvider.get().canAccess(player.getUniqueId(), result.location());
            })
            .toList();

        if (accessibleResults.isEmpty()) {
            source.getSender().sendMessage("§cNo accessible containers found matching your query.");
            return;
        }

        // Step 2: Build tree structure from common SearchResult objects
        BukkitItemLoader itemLoader = new BukkitItemLoader(getLogger());
        List<org.aincraft.kitsune.model.tree.SearchResultTreeNode> treeRoots = SearchResultTreeBuilder.buildTree(
            accessibleResults, getLogger(), itemLoader, itemDataCache);

        // Step 3: Render tree to components
        List<Component> renderedLines = SearchResultTreeRenderer.INSTANCE.render(treeRoots);

        // Step 4: Send header and all rendered lines to player
        source.getSender().sendMessage("§6Search Results:");
        for (Component line : renderedLines) {
            source.getSender().sendMessage(line);
        }

        // Step 5: Spawn highlights for all locations
        if (player != null && player.isOnline()) {
            for (var result : accessibleResults) {
                Location bukkitLoc = BukkitLocation.toBukkitOrNull(result.location());
                if (bukkitLoc != null) {
                    spawnHighlight(bukkitLoc, player, result);
                }
            }
        }

        // Step 6: Record search in history after results are displayed
        if (searchHistoryStorage != null && kitsuneConfig.historyEnabled() && player != null) {
            SearchHistoryEntry entry = SearchHistoryEntry.of(
                player.getUniqueId(),
                player.getName(),
                query,
                accessibleResults.size()
            );
            searchHistoryStorage.recordSearch(entry);
        }
    }

    /**
     * Extract slot index from JSON content.
     */
    private int extractSlotIndex(String jsonContent) {
        if (jsonContent == null || jsonContent.isEmpty()) {
            return -1;
        }
        try {
            var gson = new com.google.gson.Gson();
            var jsonArray = gson.fromJson(jsonContent, com.google.gson.JsonArray.class);
            if (jsonArray != null && jsonArray.size() > 0) {
                var itemObj = jsonArray.get(0).getAsJsonObject();
                if (itemObj.has("slot")) {
                    return itemObj.get("slot").getAsInt();
                }
            }
        } catch (Exception e) {
            getLogger().warning("extractSlotIndex: failed to parse JSON: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Try to load live item from chest at the specified slot.
     * Returns null if item cannot be loaded (chunk not loaded, item moved, etc.)
     * Must be called from the main server thread.
     *
     * For nested items:
     * - containerPath stores the path to get to the container holding the item
     * - slotIndex is the item's slot within that final container
     *
     * Example: Diamond at slot 3 inside a shulker at chest slot 5
     * - containerPath = [{type:"shulker_box", slot:5}]
     * - slotIndex = 3
     * Navigation: chest -> slot 5 (shulker) -> slot 3 (diamond)
     */
    private org.bukkit.inventory.ItemStack tryLoadLiveItem(org.bukkit.Location chestLoc, int slotIndex, ContainerPath containerPath) {
        if (slotIndex < 0) {
            return null;
        }

        // Must be called from main thread to access block entities
        if (!getServer().isPrimaryThread()) {
            getLogger().fine("tryLoadLiveItem: cannot access from async thread");
            return null;
        }

        try {
            // Get the block at the chest location
            org.bukkit.block.Block block = chestLoc.getBlock();
            if (block == null) {
                return null;
            }

            if (!(block.getState() instanceof org.bukkit.block.Container container)) {
                return null;
            }

            org.bukkit.inventory.Inventory inventory = container.getInventory();
            if (inventory == null) {
                return null;
            }

            // If no container path, get item directly from chest at slotIndex
            if (containerPath.isRoot()) {
                if (slotIndex >= inventory.getSize()) {
                    return null;
                }
                org.bukkit.inventory.ItemStack item = inventory.getItem(slotIndex);
                if (item == null || item.getType().isAir()) {
                    return null;
                }
                return item;
            }

            // Navigate through container path to find the final container,
            // then get the item at slotIndex from that container
            return navigateNestedItem(inventory, containerPath, slotIndex);
        } catch (Exception e) {
            // Silently fail - expected for async access, chunk not loaded, etc.
            return null;
        }
    }

    /**
     * Navigate into nested containers (shulker boxes, bundles) to get the item.
     *
     * @param chestInventory the chest inventory to start from
     * @param path the container path (each ref specifies container type and its slot in parent)
     * @param itemSlot the final item's slot within the deepest container
     */
    private org.bukkit.inventory.ItemStack navigateNestedItem(
            org.bukkit.inventory.Inventory chestInventory,
            ContainerPath path,
            int itemSlot) {

        if (path == null || path.isRoot()) {
            return chestInventory.getItem(itemSlot);
        }

        java.util.List<ContainerNode> refs = path.containerRefs();
        if (refs.isEmpty()) {
            return chestInventory.getItem(itemSlot);
        }

        // Start by getting the first container from the chest
        int firstSlot = refs.get(0).getSlotIndex();
        if (firstSlot < 0 || firstSlot >= chestInventory.getSize()) {
            return null;
        }
        org.bukkit.inventory.ItemStack currentContainer = chestInventory.getItem(firstSlot);
        if (currentContainer == null || currentContainer.getType().isAir()) {
            return null;
        }

        // Navigate through remaining containers in the path (if any)
        for (int i = 1; i < refs.size(); i++) {
            java.util.List<org.bukkit.inventory.ItemStack> contents = getContainerContents(currentContainer);
            if (contents == null || contents.isEmpty()) {
                return null;
            }

            int containerSlot = refs.get(i).getSlotIndex();
            if (containerSlot < 0 || containerSlot >= contents.size()) {
                return null;
            }

            currentContainer = contents.get(containerSlot);
            if (currentContainer == null) {
                return null;
            }
        }

        // Now currentContainer is the final container, get the item at itemSlot
        java.util.List<org.bukkit.inventory.ItemStack> finalContents = getContainerContents(currentContainer);
        if (finalContents == null || finalContents.isEmpty()) {
            return null;
        }

        if (itemSlot < 0 || itemSlot >= finalContents.size()) {
            return null;
        }

        return finalContents.get(itemSlot);
    }

    /**
     * Get contents from a container item (shulker box or bundle).
     */
    private java.util.List<org.bukkit.inventory.ItemStack> getContainerContents(org.bukkit.inventory.ItemStack item) {
        if (item == null) return null;

        // Check for shulker box contents
        if (item.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER)) {
            var container = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER);
            if (container != null) {
                return container.contents();
            }
        }
        // Check for bundle contents
        else if (item.hasData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS)) {
            var bundle = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                return bundle.contents();
            }
        }
        return null;
    }

    /**
     * Extract container path from JSON content.
     */
    private ContainerPath extractContainerPath(String jsonContent) {
        if (jsonContent == null || jsonContent.isEmpty()) {
            return ContainerPath.ROOT;
        }
        try {
            var gson = new com.google.gson.Gson();
            var jsonArray = gson.fromJson(jsonContent, com.google.gson.JsonArray.class);
            if (jsonArray != null && jsonArray.size() > 0) {
                var itemObj = jsonArray.get(0).getAsJsonObject();
                if (itemObj.has("container_path")) {
                    var pathElement = itemObj.get("container_path");

                    // Handle string format (legacy): "Yellow shulker_box" or "Red shulker_box → Bundle"
                    if (pathElement.isJsonPrimitive() && pathElement.getAsJsonPrimitive().isString()) {
                        String pathString = pathElement.getAsString();
                        if (pathString.isEmpty()) {
                            return ContainerPath.ROOT;
                        }
                        // Split by " → " and create container refs
                        String[] parts = pathString.split(" → ");
                        java.util.List<ContainerNode> refs = new java.util.ArrayList<>();
                        for (int i = 0; i < parts.length; i++) {
                            String part = parts[i].trim();
                            // Parse "Yellow shulker_box" or "Bundle" format
                            String containerType = "container";
                            String color = null;
                            String customName = null;

                            if (part.toLowerCase().contains("shulker_box") || part.toLowerCase().contains("shulker box")) {
                                containerType = "shulker_box";
                                // Extract color if present (e.g., "Yellow shulker_box" -> color="yellow")
                                String lowerPart = part.toLowerCase();
                                int shulkerIdx = lowerPart.indexOf("shulker");
                                if (shulkerIdx > 0) {
                                    color = part.substring(0, shulkerIdx).trim().toLowerCase();
                                }
                            } else if (part.toLowerCase().contains("bundle")) {
                                containerType = "bundle";
                            } else {
                                customName = part;
                            }

                            refs.add(new ContainerNode(containerType, color, customName, i, null, null));
                        }
                        return new ContainerPath(refs);
                    }

                    // Handle JSON array format (new): [{"type":"shulker_box","color":"yellow","slot":5}]
                    String pathJson = pathElement.toString();
                    return ContainerPath.fromJson(pathJson);
                }
            }
        } catch (Exception e) {
            getLogger().warning("extractContainerPath: failed to parse JSON: " + e.getMessage());
        }
        return ContainerPath.ROOT;
    }

    /**
     * Build path display string like "Chest → Red Shulker Box → Bundle".
     */
    private String buildPathDisplay(ContainerPath path) {
        if (path.isRoot()) {
            return "Chest";
        }

        StringBuilder sb = new StringBuilder("Chest");
        for (var ref : path.containerRefs()) {
            sb.append(" → ");

            // Format the container name nicely
            String containerName = ref.getCustomName();
            if (containerName == null || containerName.isEmpty()) {
                // Use type with color if available
                String type = ref.getContainerType();
                String color = ref.getColor();

                if ("shulker_box".equals(type)) {
                    if (color != null && !color.isEmpty()) {
                        // Capitalize first letter: "red" -> "Red"
                        containerName = color.substring(0, 1).toUpperCase() + color.substring(1) + " Shulker Box";
                    } else {
                        containerName = "Shulker Box";
                    }
                } else if ("bundle".equals(type)) {
                    containerName = "Bundle";
                } else {
                    containerName = "Container";
                }
            }
            sb.append(containerName);
        }
        return sb.toString();
    }

    /**
     * Extract display name from JSON content.
     * Falls back to item name if display_name not found.
     */
    private String extractDisplayName(String jsonContent) {
        if (jsonContent == null || jsonContent.isEmpty()) {
            getLogger().warning("extractDisplayName: content is null or empty");
            return "Unknown Item";
        }
        try {
            var gson = new com.google.gson.Gson();
            var jsonArray = gson.fromJson(jsonContent, com.google.gson.JsonArray.class);
            if (jsonArray != null && jsonArray.size() > 0) {
                var itemObj = jsonArray.get(0).getAsJsonObject();
                if (itemObj.has("display_name")) {
                    String displayName = itemObj.get("display_name").getAsString();
                    if (displayName != null && !displayName.isEmpty()) {
                        return displayName;
                    }
                }
                if (itemObj.has("name")) {
                    String name = itemObj.get("name").getAsString();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
                // Fall back to material
                if (itemObj.has("material")) {
                    String material = itemObj.get("material").getAsString();
                    return formatMaterialName(material);
                }
            }
            getLogger().warning("extractDisplayName: no valid name found in JSON: " + jsonContent.substring(0, Math.min(100, jsonContent.length())));
        } catch (Exception e) {
            getLogger().warning("extractDisplayName: failed to parse JSON: " + e.getMessage());
        }
        return "Unknown Item";
    }

    private String formatMaterialName(String material) {
        if (material == null) return "Unknown Item";
        // Convert DIAMOND_PICKAXE to Diamond Pickaxe
        String[] words = material.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private int executeFillChest(Player player) {
        // Get the block the player is looking at
        var block = player.getTargetBlockExact(5);
        if (block == null) {
            player.sendMessage("§cYou must be looking at a block");
            return 0;
        }

        var blockState = block.getState();
        if (!(blockState instanceof org.bukkit.block.Container container)) {
            player.sendMessage("§cYou must be looking at a container (chest, barrel, etc.)");
            return 0;
        }

        var inventory = container.getInventory();
        var random = new java.util.Random();

        player.sendMessage("§7Filling container at " + block.getLocation() + "...");

        // Common items for random selection
        org.bukkit.Material[] materials = {
            org.bukkit.Material.DIAMOND_PICKAXE,
            org.bukkit.Material.DIAMOND_SWORD,
            org.bukkit.Material.DIAMOND_AXE,
            org.bukkit.Material.IRON_PICKAXE,
            org.bukkit.Material.IRON_SWORD,
            org.bukkit.Material.IRON_INGOT,
            org.bukkit.Material.GOLD_INGOT,
            org.bukkit.Material.DIAMOND,
            org.bukkit.Material.EMERALD,
            org.bukkit.Material.NETHERITE_PICKAXE,
            org.bukkit.Material.NETHERITE_SWORD,
            org.bukkit.Material.ENCHANTED_BOOK,
            org.bukkit.Material.OAK_LOG,
            org.bukkit.Material.BIRCH_LOG,
            org.bukkit.Material.STONE,
            org.bukkit.Material.COBBLESTONE,
            org.bukkit.Material.DIRT,
            org.bukkit.Material.OAK_PLANKS,
            org.bukkit.Material.BIRCH_PLANKS,
            org.bukkit.Material.REDSTONE,
            org.bukkit.Material.COAL,
            org.bukkit.Material.DIAMOND_ORE,
            org.bukkit.Material.IRON_ORE,
            org.bukkit.Material.GOLDEN_APPLE,
            org.bukkit.Material.BREAD,
            org.bukkit.Material.COOKED_BEEF
        };

        // Clear existing items
        inventory.clear();

        // Fill all slots sequentially instead of random
        int itemCount = 0;
        for (int slot = 0; slot < Math.min(20, inventory.getSize()); slot++) {
            org.bukkit.Material material = materials[random.nextInt(materials.length)];
            int amount = 1 + random.nextInt(64);

            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material, amount);

            // 20% chance to add enchantments
            if (random.nextDouble() < 0.2 && (material.name().contains("PICKAXE") || material.name().contains("SWORD"))) {
                if (material.name().contains("PICKAXE")) {
                    item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 1 + random.nextInt(5));
                } else if (material.name().contains("SWORD")) {
                    item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 1 + random.nextInt(5));
                }
            }

            // Handle enchanted books specially
            if (material == org.bukkit.Material.ENCHANTED_BOOK) {
                var meta = (org.bukkit.inventory.meta.EnchantmentStorageMeta) item.getItemMeta();
                if (meta != null) {
                    org.bukkit.enchantments.Enchantment[] enchants = {
                        org.bukkit.enchantments.Enchantment.SHARPNESS,
                        org.bukkit.enchantments.Enchantment.EFFICIENCY,
                        org.bukkit.enchantments.Enchantment.PROTECTION,
                        org.bukkit.enchantments.Enchantment.FORTUNE,
                        org.bukkit.enchantments.Enchantment.LOOTING
                    };
                    org.bukkit.enchantments.Enchantment ench = enchants[random.nextInt(enchants.length)];
                    meta.addStoredEnchant(ench, 1 + random.nextInt(5), true);
                    item.setItemMeta(meta);
                }
            }

            inventory.setItem(slot, item);
            itemCount++;
        }

        player.sendMessage("§aFilled container with " + itemCount + " random items!");

        // Auto-index the chest
        injector.getInstance(BukkitContainerIndexer.class).scheduleIndex(block.getLocation(), inventory.getContents());
        player.sendMessage("§7Container will be indexed in " + kitsuneConfig.indexingDebounceMs() + "ms...");

        return 1;
    }

    private int executePurge(CommandSourceStack source) {
        source.getSender().sendMessage("§7Purging all vectors and cache...");

        // Purge vectors, cache, and metadata in sequence
        storage.purgeAll()
            .thenCompose(v -> {
                if (embeddingService instanceof CachedEmbeddingService cached) {
                    return cached.clearCache();
                }
                return CompletableFuture.completedFuture(null);
            })
            .thenRun(() -> {
                // Delete provider metadata and save new one
                providerMetadata.delete();
                providerMetadata.save(
                    kitsuneConfig.embeddingProvider(),
                    kitsuneConfig.embeddingModel()
                );

                source.getSender().sendMessage("§aPurge complete! All vectors and cache cleared.");
                source.getSender().sendMessage("§7Provider metadata reset to: §f" +
                    kitsuneConfig.embeddingProvider() + "/" +
                    kitsuneConfig.embeddingModel());
            })
            .exceptionally(ex -> {
                getLogger().log(Level.SEVERE, "Failed to purge", ex);
                source.getSender().sendMessage("§cPurge failed: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeGetThreshold(CommandSourceStack source) {
        if (!lifecycleService.isInitialized()) {
            source.getSender().sendMessage("§cKitsune is still initializing...");
            return 0;
        }

        storage.getThreshold()
            .thenAccept(threshold -> {
                source.getSender().sendMessage("§6Current threshold: §f" + threshold);
            })
            .exceptionally(ex -> {
                getLogger().log(Level.WARNING, "Failed to get threshold", ex);
                source.getSender().sendMessage("§cFailed to get threshold: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeSetThreshold(CommandSourceStack source, float value) {
        if (!lifecycleService.isInitialized()) {
            source.getSender().sendMessage("§cKitsune is still initializing...");
            return 0;
        }

        if (value < 0.0f || value > 1.0f) {
            source.getSender().sendMessage("§cThreshold must be between 0.0 and 1.0");
            return 0;
        }

        storage.setThreshold(value)
            .thenRun(() -> {
                source.getSender().sendMessage("§aThreshold set to §f" + value);
            })
            .exceptionally(ex -> {
                getLogger().log(Level.WARNING, "Failed to set threshold", ex);
                source.getSender().sendMessage("§cFailed to set threshold: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeShowHistory(CommandSourceStack source, Player player, int limit) {
        if (searchHistoryStorage == null || !kitsuneConfig.historyEnabled()) {
            source.getSender().sendMessage("§cSearch history is disabled.");
            return 0;
        }

        searchHistoryStorage.getPlayerHistory(player.getUniqueId(), limit)
            .thenAccept(history -> {
                if (history.isEmpty()) {
                    source.getSender().sendMessage("§7No search history found.");
                    return;
                }

                // Header with divider
                Component header = Component.text("Search History:", NamedTextColor.GOLD);
                source.getSender().sendMessage(header);
                source.getSender().sendMessage(Component.text("─────────────────────", NamedTextColor.DARK_GRAY));

                // Display each entry with numbering and click action
                int index = 1;
                for (SearchHistoryEntry entry : history) {
                    long ago = (System.currentTimeMillis() - entry.timestamp()) / 1000;
                    String timeAgo = formatTimeAgo(ago);

                    // Build interactive component
                    Component entryComponent = Component.text(index + ". ", NamedTextColor.GRAY)
                        .append(Component.text(timeAgo, NamedTextColor.GRAY))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("\"" + entry.query() + "\"", NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.runCommand("/find " + entry.query()))
                            .hoverEvent(HoverEvent.showText(
                                Component.text("Click to search: \"" + entry.query() + "\"", NamedTextColor.YELLOW)
                            )))
                        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(entry.resultCount() + " results", NamedTextColor.GRAY));

                    source.getSender().sendMessage(entryComponent);
                    index++;
                }

                // Footer divider
                source.getSender().sendMessage(Component.text("─────────────────────", NamedTextColor.DARK_GRAY));
            });
        return 1;
    }

    private int executeClearHistory(CommandSourceStack source, Player player) {
        if (searchHistoryStorage == null || !kitsuneConfig.historyEnabled()) {
            source.getSender().sendMessage("§cSearch history is disabled.");
            return 0;
        }

        searchHistoryStorage.clearPlayerHistory(player.getUniqueId())
            .thenRun(() -> source.getSender().sendMessage("§aSearch history cleared."));
        return 1;
    }

    private int executeShowGlobalHistory(CommandSourceStack source, int limit) {
        if (searchHistoryStorage == null || !kitsuneConfig.historyEnabled()) {
            source.getSender().sendMessage("§cSearch history is disabled.");
            return 0;
        }

        searchHistoryStorage.getGlobalHistory(limit)
            .thenAccept(history -> {
                if (history.isEmpty()) {
                    source.getSender().sendMessage("§7No global search history found.");
                    return;
                }

                source.getSender().sendMessage("§6Global Search History:");
                for (SearchHistoryEntry entry : history) {
                    long ago = (System.currentTimeMillis() - entry.timestamp()) / 1000;
                    String timeAgo = formatTimeAgo(ago);
                    source.getSender().sendMessage(String.format(
                        "§7%s §e%s§7: §f\"%s\" §7(%d results)",
                        timeAgo, entry.playerName(), entry.query(), entry.resultCount()
                    ));
                }
            });
        return 1;
    }

    private String formatTimeAgo(long seconds) {
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    private int executeGetSelfRadius(CommandSourceStack source, Player player) {
        if (playerRadiusStorage == null) {
            source.getSender().sendMessage("§cPlayer radius storage is not initialized.");
            return 0;
        }

        playerRadiusStorage.getMaxRadius(player.getUniqueId())
            .thenAccept(radius -> {
                source.getSender().sendMessage("§7Your max search radius: §f" + radius + " blocks");
            })
            .exceptionally(ex -> {
                getLogger().warning("Failed to get self radius for " + player.getName() + ": " + ex.getMessage());
                source.getSender().sendMessage("§cFailed to get radius: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeSetSelfRadius(CommandSourceStack source, Player player, int radius) {
        if (playerRadiusStorage == null) {
            source.getSender().sendMessage("§cPlayer radius storage is not initialized.");
            return 0;
        }

        if (radius <= 0 || radius > 10000) {
            source.getSender().sendMessage("§cRadius must be between 1 and 10000 blocks.");
            return 0;
        }

        playerRadiusStorage.setMaxRadius(player.getUniqueId(), player.getName(), radius)
            .thenRun(() -> {
                source.getSender().sendMessage("§aYour max search radius set to " + radius + " blocks");
            })
            .exceptionally(ex -> {
                getLogger().warning("Failed to set self radius for " + player.getName() + ": " + ex.getMessage());
                source.getSender().sendMessage("§cFailed to set radius: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeSetRadius(CommandSourceStack source, String playerName, int radius) {
        if (playerRadiusStorage == null) {
            source.getSender().sendMessage("§cPlayer radius storage is not initialized.");
            return 0;
        }

        if (radius <= 0 || radius > 10000) {
            source.getSender().sendMessage("§cRadius must be between 1 and 10000 blocks.");
            return 0;
        }

        // Try to find the player by name
        var player = getServer().getPlayerExact(playerName);
        if (player == null) {
            source.getSender().sendMessage("§cPlayer not found: " + playerName);
            return 0;
        }

        // Set the radius asynchronously
        playerRadiusStorage.setMaxRadius(player.getUniqueId(), player.getName(), radius)
            .thenRun(() -> {
                source.getSender().sendMessage("§aSet max radius for " + player.getName() + " to " + radius + " blocks");
            })
            .exceptionally(ex -> {
                getLogger().warning("Failed to set radius for " + playerName + ": " + ex.getMessage());
                source.getSender().sendMessage("§cFailed to set radius: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    private int executeGetRadius(CommandSourceStack source, String playerName) {
        if (playerRadiusStorage == null) {
            source.getSender().sendMessage("§cPlayer radius storage is not initialized.");
            return 0;
        }

        // Try to find the player by name
        var player = getServer().getPlayerExact(playerName);
        if (player == null) {
            source.getSender().sendMessage("§cPlayer not found: " + playerName);
            return 0;
        }

        int defaultRadius = kitsuneConfig.searchRadius();

        // Get the radius asynchronously
        playerRadiusStorage.getMaxRadius(player.getUniqueId())
            .thenAccept(radius -> {
                source.getSender().sendMessage("§7Max radius for " + player.getName() + ": §f" + radius + " blocks");
            })
            .exceptionally(ex -> {
                getLogger().warning("Failed to get radius for " + playerName + ": " + ex.getMessage());
                source.getSender().sendMessage("§cFailed to get radius: " + ex.getMessage());
                return null;
            });

        return 1;
    }

    @Override
    public void onDisable() {
        if (lifecycleService != null) {
            lifecycleService.shutdown();
        }
    }

    // Keep getters for backward compatibility
    public KitsuneConfig getKitsuneConfig() {
        return kitsuneConfig;
    }
    public KitsuneStorage getStorage() {
        return injector.getInstance(KitsuneStorage.class);
    }
    public EmbeddingService getEmbeddingService() {
        return injector.getInstance(EmbeddingService.class);
    }
    public ProtectionProvider getProtectionProvider() {
        return protectionProvider.orElse(null);
    }
    public boolean isInitialized() {
        return lifecycleService.isInitialized();
    }

    /**
     * Get the tag provider registry for external plugins to register custom tag providers.
     */
    public TagProviderRegistry getTagProviderRegistry() {
        return injector.getInstance(TagProviderRegistry.class);
    }

    /**
     * Get the item serializer.
     */
    public BukkitItemSerializer getItemSerializer() {
        return injector.getInstance(BukkitItemSerializer.class);
    }

    /**
     * Get the item display visualizer.
     */
    public ContainerItemDisplay getItemDisplayVisualizer() {
        return itemDisplayVisualizer;
    }

    private void spawnHighlight(Location location, Player player, org.aincraft.kitsune.model.SearchResult searchResult) {
        // Check if ItemDisplay visualization is enabled
        if (kitsuneConfig.visualizerEnabled() && itemDisplayVisualizer != null && searchResult != null) {
            itemDisplayVisualizer.spawnItemDisplays(searchResult, player);
        } else {
            // Fallback to particles if ItemDisplay is disabled
            spawnParticleHighlight(location, player);
        }
    }

    private void spawnParticleHighlight(Location location, Player player) {
        // Spawn glowing particles at the chest location for 10 seconds
        Location particleLoc = location.clone().add(0.5, 0.5, 0.5); // Center of block

        // Schedule repeating task to show particles
        BukkitTask task = getServer().getScheduler().runTaskTimer(this, () -> {
            if (player == null || !player.isOnline()) {
                return;
            }

            // Spawn multiple particle types for visibility
            player.spawnParticle(
                Particle.END_ROD,
                particleLoc,
                10,  // count
                0.3, // offset X
                0.3, // offset Y
                0.3, // offset Z
                0.02 // speed
            );

            player.spawnParticle(
                Particle.GLOW,
                particleLoc,
                5,
                0.3,
                0.3,
                0.3,
                0.01
            );

            // Add a colored dust particle ring
            Color goldColor = Color.fromRGB(255, 215, 0); // Gold color
            Particle.DustOptions dust = new Particle.DustOptions(goldColor, 1.5f);

            for (int i = 0; i < 8; i++) {
                double angle = (Math.PI * 2 / 8) * i;
                double x = Math.cos(angle) * 0.5;
                double z = Math.sin(angle) * 0.5;
                player.spawnParticle(
                    Particle.DUST,
                    particleLoc.clone().add(x, 0, z),
                    1,
                    dust
                );
            }
        }, 0L, 10L); // Run every 10 ticks (0.5 seconds)

        // Cancel after 10 seconds (200 ticks)
        getServer().getScheduler().runTaskLater(this, task::cancel, 200L);
    }
}
