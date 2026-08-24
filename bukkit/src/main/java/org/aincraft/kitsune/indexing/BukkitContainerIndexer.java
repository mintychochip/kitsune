package org.aincraft.kitsune.indexing;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.BukkitLocation;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.api.indexing.SerializedItem;
import org.aincraft.kitsune.api.model.ContainerPath;
import org.aincraft.kitsune.config.KitsuneConfig;
import org.aincraft.kitsune.embedding.EmbeddingService;
import org.aincraft.kitsune.model.ContainerChunk;
import org.aincraft.kitsune.serialization.BukkitItemSerializer;
import org.aincraft.kitsune.storage.KitsuneStorage;
import org.aincraft.kitsune.util.ItemDataExtractor;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bukkit container indexer implementing ContainerIndexer interface.
 * Provides all indexing logic plus Bukkit-specific methods.
 */
public class BukkitContainerIndexer implements ContainerIndexer {
    private final Logger logger;
    private final EmbeddingService embeddingService;
    private final KitsuneStorage storage;
    private final KitsuneConfig config;
    private final BukkitItemSerializer itemSerializer;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final Map<Location, ScheduledFuture<?>> pendingIndexes = new HashMap<>();
    private final int debounceDelayMs;

    public BukkitContainerIndexer(Logger logger, EmbeddingService embeddingService,
                                  KitsuneStorage storage, KitsuneConfig config,
                                  BukkitItemSerializer itemSerializer) {
        this.logger = logger;
        this.embeddingService = embeddingService;
        this.storage = storage;
        this.config = config;
        this.itemSerializer = itemSerializer;
        this.debounceDelayMs = config.indexingDebounceMs();
    }

    @Override
    public void scheduleIndex(ContainerLocations locations, List<SerializedItem> serializedItems) {
        Location primaryLocation = locations.primaryLocation();

        storage.getOrCreateContainer(locations).thenAccept(containerId -> {
            synchronized (pendingIndexes) {
                ScheduledFuture<?> existing = pendingIndexes.get(primaryLocation);
                if (existing != null && !existing.isDone()) {
                    existing.cancel(false);
                }

                ScheduledFuture<?> future = executor.schedule(
                    () -> performIndex(containerId, serializedItems),
                    debounceDelayMs,
                    TimeUnit.MILLISECONDS
                );

                pendingIndexes.put(primaryLocation, future);
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING,
                "Failed to get or create container at " + primaryLocation, ex);
            return null;
        });
    }

    private void performIndex(UUID containerId, List<SerializedItem> serializedItems) {
        synchronized (pendingIndexes) {
            pendingIndexes.values().removeIf(ScheduledFuture::isDone);
        }

        if (serializedItems.isEmpty()) {
            storage.deleteContainer(containerId).exceptionally(ex -> {
                logger.log(Level.WARNING, "Failed to delete empty container", ex);
                return null;
            });
            return;
        }

        long timestamp = System.currentTimeMillis();
        List<String> embeddingTexts = new ArrayList<>();
        List<ContainerPath> containerPaths = new ArrayList<>();

        for (SerializedItem serialized : serializedItems) {
            embeddingTexts.add(serialized.embeddingText().toLowerCase());
            containerPaths.add(ItemDataExtractor.extractContainerPath(serialized.storageJson(), logger));
        }

        CompletableFuture<List<float[]>> embeddingFuture = embeddingService.embedBatch(embeddingTexts, "RETRIEVAL_DOCUMENT");

        CompletableFuture<List<ContainerChunk>> chunkFuture = embeddingFuture.thenApply(embeddings -> {
            List<ContainerChunk> chunks = new ArrayList<>();
            for (int i = 0; i < embeddings.size(); i++) {
                SerializedItem serialized = serializedItems.get(i);
                ContainerChunk chunk = new ContainerChunk(
                    containerId,
                    i,
                    serialized.storageJson(),
                    embeddings.get(i),
                    timestamp,
                    containerPaths.get(i)
                );
                chunks.add(chunk);
            }
            return chunks;
        });

        chunkFuture.thenCompose(chunks -> storage.indexChunks(containerId, chunks))
            .exceptionally(ex -> {
                logger.log(Level.WARNING, "Failed to index container " + containerId, ex);
                return null;
            });
    }

    // ===== Bukkit-specific methods =====

    public void scheduleIndex(ContainerLocations locations, ItemStack[] items) {
        List<SerializedItem> serializedItems = itemSerializer.serialize(items);
        scheduleIndex(locations, serializedItems);
    }

    public void scheduleIndex(org.bukkit.Location location, ItemStack[] items) {
        if (location.getWorld() == null) {
            return;
        }
        Location locationData = BukkitLocation.from(location);
        List<SerializedItem> serializedItems = itemSerializer.serialize(items);
        scheduleIndex(ContainerLocations.single(locationData), serializedItems);
    }

    public CompletableFuture<Integer> reindexRadius(org.bukkit.Location center, int radius) {
        if (center.getWorld() == null) {
            return CompletableFuture.completedFuture(0);
        }

        String worldName = center.getWorld().getName();
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();

        return storage.getContainersInRadius(worldName, x, y, z, radius)
            .thenCompose(containerIds -> {
                if (containerIds.isEmpty()) {
                    return CompletableFuture.completedFuture(0);
                }

                List<CompletableFuture<?>> tasks = new ArrayList<>();

                for (UUID containerId : containerIds) {
                    CompletableFuture<?> task = storage.getPrimaryLocationForContainer(containerId)
                        .thenAccept(locOpt -> {
                            locOpt.ifPresent(loc -> {
                                org.bukkit.Location bukkitLoc = BukkitLocation.toBukkit(loc);
                                if (bukkitLoc != null && bukkitLoc.getWorld() != null) {
                                    if (center.distance(bukkitLoc) <= radius) {
                                        var block = bukkitLoc.getBlock();
                                        if (block.getState() instanceof Container container) {
                                            Inventory inv = container.getInventory();
                                            scheduleIndex(bukkitLoc, inv.getContents());
                                        }
                                    }
                                }
                            });
                        });
                    tasks.add(task);
                }

                return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .thenApply(v -> containerIds.size());
            });
    }

    @Override
    public void shutdown() {
        synchronized (pendingIndexes) {
            for (ScheduledFuture<?> future : pendingIndexes.values()) {
                if (future != null && !future.isDone()) {
                    future.cancel(false);
                }
            }
            pendingIndexes.clear();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
