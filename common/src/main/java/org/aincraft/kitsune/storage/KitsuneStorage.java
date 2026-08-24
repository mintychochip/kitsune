package org.aincraft.kitsune.storage;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.sql.DataSource;

import org.aincraft.kitsune.api.ContainerLocations;
import org.aincraft.kitsune.Location;
import org.aincraft.kitsune.model.ContainerChunk;
import org.aincraft.kitsune.model.SearchResult;
import org.aincraft.kitsune.model.StorageStats;
import org.aincraft.kitsune.storage.metadata.ChunkWithLocation;
import org.aincraft.kitsune.storage.metadata.ContainerStorage;
import org.aincraft.kitsune.storage.vector.VectorIndex;
import org.aincraft.kitsune.storage.vector.VectorSearchResult;
import org.aincraft.kitsune.api.model.ContainerPath;

/**
 * Storage coordinator between ContainerStorage (SQLite metadata) and VectorIndex (embeddings).
 * Wraps sync ContainerStorage calls in CompletableFuture for async execution.
 */
public final class KitsuneStorage {
    private final Logger logger;
    private final ContainerStorage containerStorage;
    private final VectorIndex vectorIndex;
    private final AtomicInteger nextOrdinal;

    public KitsuneStorage(Logger logger, ContainerStorage containerStorage, VectorIndex vectorIndex) {
        this.logger = logger;
        this.containerStorage = containerStorage;
        this.vectorIndex = vectorIndex;
        this.nextOrdinal = new AtomicInteger(0);
    }

    public void initialize() {
        logger.info("Initializing KitsuneStorage");
        containerStorage.initialize();
        vectorIndex.initialize().join();
        long count = containerStorage.getChunkCount();
        if (count > 0) {
            nextOrdinal.set((int) Math.min(count, Integer.MAX_VALUE));
        }
        logger.info("KitsuneStorage initialized. Max ordinal: " + nextOrdinal.get());
    }

    public CompletableFuture<Void> indexChunks(UUID containerId, List<ContainerChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            logger.info("No chunks to index for container " + containerId);
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Indexing " + chunks.size() + " chunks for container " + containerId);

        return CompletableFuture.runAsync(() -> {
            containerStorage.deleteChunksByContainer(containerId);

            List<CompletableFuture<Void>> addFutures = new ArrayList<>();
            for (ContainerChunk chunk : chunks) {
                int ordinal = nextOrdinal.getAndIncrement();
                UUID chunkId = UUID.randomUUID();
                containerStorage.saveChunk(containerId, chunkId, ordinal, chunk);
                addFutures.add(vectorIndex.addVector(ordinal, chunk.embedding()));
            }

            // Wait for all adds, then rebuild
            CompletableFuture.allOf(addFutures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> vectorIndex.rebuildIndex())
                .join();
        });
    }

    // TODO: PERF - Search may trigger unexpected index rebuild
    // If indexDirty=true, search() blocks on full HNSW rebuild before returning results
    // Fix: Use stale index for search, rebuild in background, or accept dirty reads
    public CompletableFuture<List<SearchResult>> search(float[] embedding, int limit, String worldName) {
        if (embedding == null || embedding.length == 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Embedding cannot be null or empty"));
        }

        logger.fine("Searching with embedding, limit=" + limit + ", world=" + worldName);

        return CompletableFuture.supplyAsync(() -> {
            List<VectorSearchResult> vectorResults = vectorIndex.search(embedding, limit).join();
            logger.fine("Vector search returned " + vectorResults.size() + " results");

            Set<Integer> candidateOrdinals = new HashSet<>();
            for (VectorSearchResult vectorResult : vectorResults) {
                candidateOrdinals.add(vectorResult.ordinal());
            }

            List<ChunkWithLocation> chunks = containerStorage.getChunksByOrdinals(candidateOrdinals);
            Map<Integer, ChunkWithLocation> chunkMap = new HashMap<>();
            for (ChunkWithLocation chunk : chunks) {
                chunkMap.put(chunk.metadata().ordinal(), chunk);
            }

            List<SearchResult> results = new ArrayList<>();
            for (VectorSearchResult vectorResult : vectorResults) {
                try {
                    ChunkWithLocation chunk = chunkMap.get(vectorResult.ordinal());
                    if (chunk != null) {
                        Location location = chunk.location();

                        if (worldName != null && !location.getWorld().getName().equals(worldName)) {
                            continue;
                        }

                        ContainerPath containerPath = ContainerPath.ROOT;
                        if (chunk.metadata().containerPath() != null && !chunk.metadata().containerPath().isEmpty()) {
                            try {
                                containerPath = ContainerPath.fromJson(chunk.metadata().containerPath());
                            } catch (Exception e) {
                                logger.fine("Failed to parse container path: " + e.getMessage());
                            }
                        }

                        SearchResult result = new SearchResult(
                            location,
                            List.of(location),
                            vectorResult.score(),
                            chunk.metadata().contentText(),
                            chunk.metadata().contentText(),
                            containerPath
                        );
                        results.add(result);

                        if (results.size() >= limit) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING,
                        "Error processing vector result for ordinal " + vectorResult.ordinal(), e);
                }
            }

            logger.fine("Search returning " + results.size() + " results after filtering");
            return results;
        });
    }

    public CompletableFuture<List<SearchResult>> searchWithinRadius(
            float[] embedding, int limit, Location center, int radius) {

        if (embedding == null || embedding.length == 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Embedding cannot be null or empty"));
        }
        if (center == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Center location cannot be null"));
        }
        if (radius <= 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Radius must be positive"));
        }

        logger.fine("Searching within radius. center=" + center + ", radius=" + radius);

        return CompletableFuture.supplyAsync(() -> {
            int minX = center.blockX() - radius;
            int maxX = center.blockX() + radius;
            int minY = center.blockY() - radius;
            int maxY = center.blockY() + radius;
            int minZ = center.blockZ() - radius;
            int maxZ = center.blockZ() + radius;

            List<Integer> ordinals = containerStorage.getOrdinalsInBoundingBox(
                center.getWorld().getName(), minX, maxX, minY, maxY, minZ, maxZ);
            logger.fine("Found " + ordinals.size() + " ordinals in bounding box");

            Set<Integer> ordinalSet = new HashSet<>(ordinals);
            List<VectorSearchResult> vectorResults = vectorIndex.searchWithFilter(embedding, limit * 2, ordinalSet).join();
            logger.fine("Vector search returned " + vectorResults.size() + " results");

            Set<Integer> candidateOrdinals = new HashSet<>();
          for (VectorSearchResult vectorResult : vectorResults) {
              candidateOrdinals.add(vectorResult.ordinal());
          }

          List<ChunkWithLocation> chunks = containerStorage.getChunksByOrdinals(candidateOrdinals);
          Map<Integer, ChunkWithLocation> chunkMap = new HashMap<>();
          for (ChunkWithLocation chunk : chunks) {
              chunkMap.put(chunk.metadata().ordinal(), chunk);
          }

          List<SearchResult> candidates = new ArrayList<>();
          for (VectorSearchResult vectorResult : vectorResults) {
              try {
                  ChunkWithLocation chunk = chunkMap.get(vectorResult.ordinal());
                  if (chunk != null) {
                      Location location = chunk.location();
                      double distance = center.distanceTo(location);
                      if (distance <= radius) {
                          ContainerPath containerPath = ContainerPath.ROOT;
                          if (chunk.metadata().containerPath() != null && !chunk.metadata().containerPath().isEmpty()) {
                              try {
                                  containerPath = ContainerPath.fromJson(chunk.metadata().containerPath());
                              } catch (Exception e) {
                                  logger.fine("Failed to parse container path: " + e.getMessage());
                              }
                          }

                          SearchResult result = new SearchResult(
                              location,
                              List.of(location),
                              vectorResult.score(),
                              chunk.metadata().contentText(),
                              chunk.metadata().contentText(),
                              containerPath
                          );
                          candidates.add(result);

                          if (candidates.size() >= limit) {
                              break;
                          }
                      }
                  }
              } catch (Exception e) {
                  logger.log(Level.WARNING,
                      "Error processing vector result for ordinal " + vectorResult.ordinal(), e);
              }
          }

            logger.fine("Radius search returning " + candidates.size() + " results");
            return candidates;
        });
    }

    // TODO: PERF - Same blocking rebuild issue as indexChunks()
    // Delete triggers full HNSW rebuild synchronously
    // Fix: Mark dirty, rebuild lazily
    public CompletableFuture<Void> delete(Location location) {
        if (location == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Location cannot be null"));
        }

        logger.info("Deleting container at " + location);

        return CompletableFuture.runAsync(() -> {
            Optional<UUID> containerOpt = containerStorage.getContainerByLocation(location);
            if (containerOpt.isEmpty()) {
                logger.warning("No container found at " + location);
                return;
            }

            UUID containerId = containerOpt.get();
            containerStorage.deleteChunksByContainer(containerId);
            vectorIndex.rebuildIndex().join();
        });
    }

    public CompletableFuture<StorageStats> getStats() {
        logger.fine("Getting storage statistics");
        return CompletableFuture.supplyAsync(() -> {
            long count = containerStorage.getChunkCount();
            return new StorageStats(
                count,
                "KitsuneStorage(" + containerStorage.getClass().getSimpleName() +
                    " + " + vectorIndex.getClass().getSimpleName() + ")"
            );
        });
    }

    public CompletableFuture<Void> purgeAll() {
        logger.warning("Purging all data from storage");

        return CompletableFuture.runAsync(() -> {
            containerStorage.purgeAll();
            vectorIndex.purgeAll().join();
            nextOrdinal.set(0);
            logger.info("Storage purge complete");
        });
    }

    public void shutdown() {
        logger.info("Shutting down KitsuneStorage");
        // DataSource lifecycle managed externally
        vectorIndex.shutdown();
    }

    public CompletableFuture<Void> registerContainerPositions(ContainerLocations locations) {
        if (locations == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Locations cannot be null"));
        }
        return CompletableFuture.runAsync(() -> containerStorage.registerContainerPositions(locations));
    }

    public CompletableFuture<Optional<Location>> getPrimaryLocation(Location anyPosition) {
        if (anyPosition == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Position cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getPrimaryLocation(anyPosition));
    }

    public CompletableFuture<List<Location>> getAllPositions(Location primaryLocation) {
        if (primaryLocation == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Primary location cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getAllPositions(primaryLocation));
    }

    public CompletableFuture<Void> deleteContainerPositions(Location primaryLocation) {
        if (primaryLocation == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Primary location cannot be null"));
        }
        return CompletableFuture.runAsync(() -> containerStorage.deleteContainerPositions(primaryLocation));
    }

    public CompletableFuture<UUID> getOrCreateContainer(ContainerLocations locations) {
        if (locations == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Locations cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getOrCreateContainer(locations));
    }

    public CompletableFuture<Optional<UUID>> getContainerByLocation(Location location) {
        if (location == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Location cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getContainerByLocation(location));
    }

    public CompletableFuture<List<Location>> getContainerLocations(UUID containerId) {
        if (containerId == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Container ID cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getContainerLocations(containerId));
    }

    public CompletableFuture<Optional<Location>> getPrimaryLocationForContainer(UUID containerId) {
        if (containerId == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Container ID cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> containerStorage.getPrimaryLocationForContainer(containerId));
    }

    public CompletableFuture<Void> deleteContainer(UUID containerId) {
        if (containerId == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Container ID cannot be null"));
        }

        logger.info("Deleting container " + containerId);

        return CompletableFuture.runAsync(() -> {
            containerStorage.deleteContainer(containerId);
            vectorIndex.rebuildIndex().join();
        });
    }

    public CompletableFuture<List<UUID>> getContainersInRadius(
            String world, int centerX, int centerY, int centerZ, int radius) {

        if (world == null || world.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("World cannot be null or blank"));
        }
        if (radius <= 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Radius must be positive"));
        }

        logger.fine("Getting containers in radius. world=" + world +
            ", center=(" + centerX + "," + centerY + "," + centerZ + "), radius=" + radius);

        return CompletableFuture.supplyAsync(() -> {
            int minX = centerX - radius;
            int maxX = centerX + radius;
            int minY = centerY - radius;
            int maxY = centerY + radius;
            int minZ = centerZ - radius;
            int maxZ = centerZ + radius;

            List<Integer> ordinals = containerStorage.getOrdinalsInBoundingBox(
                world, minX, maxX, minY, maxY, minZ, maxZ);
            logger.fine("Found " + ordinals.size() + " ordinals in bounding box");

            List<ChunkWithLocation> chunks = containerStorage.getChunksByOrdinals(new HashSet<>(ordinals));
            Set<UUID> containerIds = new HashSet<>();
            for (ChunkWithLocation chunk : chunks) {
                try {
                    // TODO: Need to track container ID in chunk metadata
                    logger.fine("Added container from ordinal " + chunk.metadata().ordinal());
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error processing chunk from ordinal " + chunk.metadata().ordinal(), e);
                }
            }

            logger.fine("Radius query returning " + containerIds.size() + " unique containers");
            return new ArrayList<>(containerIds);
        });
    }

    // DataSource access

    public DataSource getDataSource() {
        return containerStorage.getDataSource();
    }

    public CompletableFuture<Double> getThreshold() {
        return CompletableFuture.supplyAsync(() -> containerStorage.getThreshold());
    }

    public CompletableFuture<Void> setThreshold(double threshold) {
        return CompletableFuture.runAsync(() -> containerStorage.setThreshold(threshold));
    }
}
